package com.nogy.autoenhance

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Auto enhance v4. For every photo, in order:
 *  1) on-phone ML finds faces + the person outline (free, offline)
 *  2) Gemini IMAGE model edits a 1024px copy -> saved as "AI_..." and its colours are copied
 *     onto the FULL-resolution photo.  (Fallback: Gemini settings, then local auto-fix)
 *  3) full-res: noise clean-up -> tone + colour -> sharpen + local contrast
 *     -> skin smoothing (keeps texture) -> subtle background blur
 *  4) saved as "ENH_..." in Pictures/Enhanced (full resolution)
 */
object Enhancer {

    private const val SKIN_STRENGTH = 0.55f   // 0 = off, 1 = plastic
    private const val BG_BLUR_STRENGTH = 0.85f

    private class Look(
        val warmth: Float, val shadow: Float, val highlight: Float, val contrast: Float,
        val saturation: Float, val vibrance: Float, val exposure: Float,
        val sharp: Float, val clarity: Float
    )

    private val DEFAULT_LOOK = Look(0.025f, 0.18f, 0.12f, 0.26f, 0.08f, 0.20f, 0f, 0.5f, 0.16f)

    private fun lookFrom(ai: AiParams) = Look(
        warmth = ai.warmth * 0.06f,
        shadow = ai.shadows * 0.35f,
        highlight = ai.highlights * 0.30f,
        contrast = ai.contrast * 0.55f,
        saturation = ai.saturation * 0.20f,
        vibrance = ai.vibrance * 0.40f,
        exposure = ai.exposure,
        sharp = ai.sharpness * 0.80f,
        clarity = ai.clarity * 0.35f
    )

    private fun scaleTo(bmp: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        if (scale >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp, max(1, (bmp.width * scale).roundToInt()), max(1, (bmp.height * scale).roundToInt()), true
        )
    }

    private fun friendly(err: String): String = when {
        err.contains("429") -> "free quota used up for the image model"
        err.contains("403") || err.contains("400") -> "key or model not allowed (image model may need billing)"
        err.contains("404") -> "model name not found"
        err.isEmpty() -> "unknown error"
        else -> err
    }

    fun enhance(context: Context, uri: Uri, displayName: String): List<Uri> {
        val results = ArrayList<Uri>()
        val bmp: Bitmap = try {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                if (info.size.width.toLong() * info.size.height > 20_000_000L) {
                    decoder.setTargetSampleSize(2)
                }
            }
        } catch (e: OutOfMemoryError) {
            return results
        }
        val w = bmp.width
        val h = bmp.height
        val baseName = displayName.substringBeforeLast('.')

        val prefs = context.getSharedPreferences("enhance", Context.MODE_PRIVATE)
        val key = prefs.getString("gemini_key", "")?.trim().orEmpty()
        val model = prefs.getString("image_model", "")?.trim().orEmpty()
            .ifEmpty { GeminiClient.DEFAULT_IMAGE_MODEL }
        val wantSkin = prefs.getBoolean("skin", true)
        val wantBlur = prefs.getBoolean("bgblur", true)
        val saveAi = prefs.getBoolean("save_ai", true)

        val thumb = scaleTo(bmp, 1024)

        // ---------- 1) on-phone ML ----------
        var faces: List<FaceBox> = emptyList()
        var mask: PersonMask? = null
        if (wantSkin || wantBlur) {
            faces = OnDevice.faces(thumb)
            if (wantBlur && faces.isNotEmpty()) mask = OnDevice.personMask(thumb)
        }

        // ---------- 2) Gemini ----------
        var luts: Array<IntArray>? = null
        var ai: AiParams? = null
        var result = "AI is off, used on-phone fix"
        if (key.isNotEmpty()) {
            val edited = GeminiClient.editImage(key, model, thumb)
            if (edited != null) {
                luts = histogramMatch(thumb, edited)
                if (saveAi) saveJpeg(context, edited, "AI_$baseName.jpg")?.let { results.add(it) }
                edited.recycle()
                result = "Gemini image edit worked"
            } else {
                val editErr = friendly(GeminiClient.lastError)
                val t2 = scaleTo(bmp, 512)
                ai = GeminiClient.analyze(key, t2)
                if (t2 !== bmp) t2.recycle()
                result = "Gemini image edit failed ($editErr). Used " +
                    (if (ai != null) "Gemini settings" else "on-phone fix")
            }
        }
        if (thumb !== bmp) thumb.recycle()
        result += if (faces.isNotEmpty()) " | faces: ${faces.size}" else " | no faces"
        prefs.edit().putString("last_result", result).apply()

        val look = if (ai != null) lookFrom(ai) else DEFAULT_LOOK

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        // ---------- quick analysis ----------
        val stepX = max(1, w / 200)
        val stepY = max(1, h / 200)
        val hist = IntArray(256)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                hist[(0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)]++
                sumR += r; sumG += g; sumB += b
                n++
                x += stepX
            }
            y += stepY
        }
        fun pct(q: Float): Int {
            val target = (n * q).toInt()
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        val lo = pct(0.005f)
        val hi = pct(0.995f)
        val med = pct(0.5f)
        val medN = (med / 255f).coerceIn(0.02f, 0.98f)
        val night = medN < 0.22f

        // ---------- 3a) noise clean-up ----------
        denoise(px, w, h, night)

        // ---------- 3b) tone + colour ----------
        val sharpBase: Float
        val clarityBase: Float
        if (luts != null) {
            val lr = luts[0]
            val lg = luts[1]
            val lb = luts[2]
            parallel(w * h) { from, to ->
                for (i in from until to) {
                    val p = px[i]
                    val r = lr[(p shr 16) and 255]
                    val g = lg[(p shr 8) and 255]
                    val b = lb[p and 255]
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            sharpBase = 0.6f
            clarityBase = 0.22f
        } else {
            val mr = sumR / n
            val mg = sumG / n
            val mb = sumB / n
            val avg = (mr + mg + mb) / 3.0
            fun gain(m: Double) = (1.0 + 0.5 * (avg / max(m, 1.0) - 1.0)).coerceIn(0.9, 1.1).toFloat()
            val gr = gain(mr) * (1f + look.warmth)
            val gg = gain(mg)
            val gb = gain(mb) * (1f - look.warmth)

            val target = if (night) 0.30f else 0.45f
            var gamma = ln(target) / ln(medN)
            gamma = 1f + 0.8f * (gamma - 1f)
            gamma = gamma.coerceIn(if (night) 0.75f else 0.6f, 1.25f)
            gamma *= 2f.pow(-look.exposure * 0.6f)
            gamma = gamma.coerceIn(if (night) 0.7f else 0.5f, 1.4f)

            val black = (lo / 255f).coerceAtMost(0.06f)
            val white = (hi / 255f).coerceAtLeast(0.88f)
            val shadowAmt = if (night) look.shadow * 0.5f else look.shadow

            val lut = IntArray(256)
            for (i in 0..255) {
                var v = i / 255f
                v = ((v - black) / (white - black)).coerceIn(0f, 1f)
                v = v.pow(gamma)
                v += shadowAmt * 4f * v * (1f - v) * (1f - v)
                v -= look.highlight * 4f * v * v * (1f - v)
                v = v.coerceIn(0f, 1f)
                val s = v * v * (3f - 2f * v)
                v += (s - v) * look.contrast
                lut[i] = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
            }

            val sat = look.saturation
            val vib = look.vibrance
            parallel(w * h) { from, to ->
                for (i in from until to) {
                    val p = px[i]
                    var r = lut[(((p shr 16) and 255) * gr).toInt().coerceIn(0, 255)]
                    var g = lut[(((p shr 8) and 255) * gg).toInt().coerceIn(0, 255)]
                    var b = lut[((p and 255) * gb).toInt().coerceIn(0, 255)]
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val s = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
                    val k = 1f + sat + vib * (1f - s)
                    val l = 0.299f * r + 0.587f * g + 0.114f * b
                    r = (l + (r - l) * k).roundToInt().coerceIn(0, 255)
                    g = (l + (g - l) * k).roundToInt().coerceIn(0, 255)
                    b = (l + (b - l) * k).roundToInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            sharpBase = look.sharp
            clarityBase = look.clarity
        }

        // ---------- 3c) sharpening + local contrast ----------
        val sharpAmt = if (night) sharpBase * 0.5f else sharpBase
        val clarity = clarityBase
        if (sharpAmt > 0.02f || clarity > 0.02f) {
            val lum = ByteArray(w * h)
            parallel(w * h) { from, to ->
                for (i in from until to) {
                    val p = px[i]
                    lum[i] = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) +
                        0.114f * (p and 255)).toInt().toByte()
                }
            }
            val small = boxBlur(lum, w, h, max(1, w / 1800))
            val large = boxBlur(lum, w, h, max(6, w / 150))
            parallel(w * h) { from, to ->
                for (i in from until to) {
                    val l = lum[i].toInt() and 255
                    val d = ((l - (small[i].toInt() and 255)) * sharpAmt +
                        (l - (large[i].toInt() and 255)) * clarity).coerceIn(-45f, 45f).roundToInt()
                    if (d != 0) {
                        val p = px[i]
                        val r = (((p shr 16) and 255) + d).coerceIn(0, 255)
                        val g = (((p shr 8) and 255) + d).coerceIn(0, 255)
                        val b = ((p and 255) + d).coerceIn(0, 255)
                        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        // ---------- 3d) skin smoothing, 3e) background blur ----------
        try {
            if (wantSkin && faces.isNotEmpty()) smoothSkin(px, w, h, faces)
            val m = mask
            if (wantBlur && m != null) blurBackground(px, w, h, m)
        } catch (t: Throwable) {
            android.util.Log.w("AutoEnhance", "Portrait steps failed: $t")
        }

        // ---------- 4) save full-res result ----------
        val outBmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        saveJpeg(context, outBmp, "ENH_$baseName.jpg")?.let { results.add(it) }
        outBmp.recycle()
        return results
    }

    // ================= helpers =================

    private fun saveJpeg(context: Context, bmp: Bitmap, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Enhanced")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(out)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (t: Throwable) {
            resolver.delete(out, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(out, values, null, null)
        return out
    }

    /** Removes grain: strong on colour blotches, gentle on brightness, keeps edges. */
    private fun denoise(px: IntArray, w: Int, h: Int, night: Boolean) {
        val n = w * h
        val yA = ByteArray(n)
        val cbA = ByteArray(n)
        val crA = ByteArray(n)
        parallel(n) { from, to ->
            for (i in from until to) {
                val p = px[i]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                yA[i] = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255).toByte()
                cbA[i] = (128f - 0.168736f * r - 0.331264f * g + 0.5f * b).roundToInt().coerceIn(0, 255).toByte()
                crA[i] = (128f + 0.5f * r - 0.418688f * g - 0.081312f * b).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val chromaR = max(2, w / 900)
        val cbB = boxBlur(cbA, w, h, chromaR)
        val crB = boxBlur(crA, w, h, chromaR)
        val yB = boxBlur(yA, w, h, 1)
        val threshold = if (night) 22f else 14f
        val strength = if (night) 0.8f else 0.45f
        parallel(n) { from, to ->
            for (i in from until to) {
                val y0 = yA[i].toInt() and 255
                val yb = yB[i].toInt() and 255
                val wgt = (1f - abs(y0 - yb) / threshold).coerceIn(0f, 1f)
                val yy = y0 + (yb - y0) * wgt * strength
                val cb = 0.3f * (cbA[i].toInt() and 255) + 0.7f * (cbB[i].toInt() and 255) - 128f
                val cr = 0.3f * (crA[i].toInt() and 255) + 0.7f * (crB[i].toInt() and 255) - 128f
                val r = (yy + 1.402f * cr).roundToInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * cb - 0.714136f * cr).roundToInt().coerceIn(0, 255)
                val b = (yy + 1.772f * cb).roundToInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Smooths blemishes on skin only (colour-checked), and keeps ~45% of the texture. */
    private fun smoothSkin(px: IntArray, w: Int, h: Int, faces: List<FaceBox>) {
        for (f in faces) {
            val fw = (f.right - f.left) * w
            val fh = (f.bottom - f.top) * h
            val cx = (f.left + f.right) / 2f * w
            val cy = (f.top + f.bottom) / 2f * h
            val rx = fw * 0.62f
            val ry = fh * 0.70f
            val x0 = max(0, (cx - rx).toInt())
            val x1 = min(w, (cx + rx).toInt() + 1)
            val y0 = max(0, (cy - ry).toInt())
            val y1 = min(h, (cy + ry).toInt() + 1)
            val cw = x1 - x0
            val ch = y1 - y0
            if (cw < 16 || ch < 16) continue

            val rA = ByteArray(cw * ch)
            val gA = ByteArray(cw * ch)
            val bA = ByteArray(cw * ch)
            for (yy in 0 until ch) {
                for (xx in 0 until cw) {
                    val p = px[(y0 + yy) * w + (x0 + xx)]
                    val k = yy * cw + xx
                    rA[k] = ((p shr 16) and 255).toByte()
                    gA[k] = ((p shr 8) and 255).toByte()
                    bA[k] = (p and 255).toByte()
                }
            }
            val rad = max(2, (fw / 45f).toInt())
            val rB = boxBlur(boxBlur(rA, cw, ch, rad), cw, ch, rad)
            val gB = boxBlur(boxBlur(gA, cw, ch, rad), cw, ch, rad)
            val bB = boxBlur(boxBlur(bA, cw, ch, rad), cw, ch, rad)

            for (yy in 0 until ch) {
                for (xx in 0 until cw) {
                    val dx = (x0 + xx - cx) / rx
                    val dy = (y0 + yy - cy) / ry
                    val d2 = dx * dx + dy * dy
                    if (d2 >= 1f) continue
                    val falloff = ((1f - d2) / 0.6f).coerceIn(0f, 1f)
                    val i = (y0 + yy) * w + (x0 + xx)
                    val k = yy * cw + xx
                    val p = px[i]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                    val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
                    val dcb = max(max(77f - cb, cb - 127f), 0f)
                    val dcr = max(max(133f - cr, cr - 173f), 0f)
                    val skin = (1f - max(dcb, dcr) / 12f).coerceIn(0f, 1f)
                    val a = falloff * skin * SKIN_STRENGTH
                    if (a <= 0.01f) continue
                    val nr = (r + ((rB[k].toInt() and 255) - r) * a).roundToInt().coerceIn(0, 255)
                    val ng = (g + ((gB[k].toInt() and 255) - g) * a).roundToInt().coerceIn(0, 255)
                    val nb = (b + ((bB[k].toInt() and 255) - b) * a).roundToInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }

    /** Portrait-style blur: only when a person fills a sensible part of the photo. */
    private fun blurBackground(px: IntArray, w: Int, h: Int, mask: PersonMask) {
        var covered = 0L
        for (v in mask.data) if ((v.toInt() and 255) > 128) covered++
        val frac = covered.toFloat() / mask.data.size
        if (frac < 0.04f || frac > 0.85f) return

        val soft = boxBlur(mask.data, mask.w, mask.h, max(2, mask.w / 80))
        val n = w * h
        val rad = max(8, w / 110)

        fun blurredChannel(shift: Int): ByteArray {
            val ch = ByteArray(n)
            parallel(n) { from, to ->
                for (i in from until to) ch[i] = ((px[i] shr shift) and 255).toByte()
            }
            return boxBlur(boxBlur(ch, w, h, rad), w, h, rad)
        }
        val rB = blurredChannel(16)
        val gB = blurredChannel(8)
        val bB = blurredChannel(0)

        val mw = mask.w
        val mh = mask.h
        parallel(n) { from, to ->
            for (i in from until to) {
                val x = i % w
                val yy = i / w
                val mx = min(mw - 1, x * mw / w)
                val my = min(mh - 1, yy * mh / h)
                val person = (soft[my * mw + mx].toInt() and 255) / 255f
                val a = (1f - person) * BG_BLUR_STRENGTH
                if (a > 0.01f) {
                    val p = px[i]
                    val r = (p shr 16) and 255
                    val g = (p shr 8) and 255
                    val b = p and 255
                    val nr = (r + ((rB[i].toInt() and 255) - r) * a).roundToInt().coerceIn(0, 255)
                    val ng = (g + ((gB[i].toInt() and 255) - g) * a).roundToInt().coerceIn(0, 255)
                    val nb = (b + ((bB[i].toInt() and 255) - b) * a).roundToInt().coerceIn(0, 255)
                    px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
                }
            }
        }
    }

    /** Learns "original colours -> Gemini colours" for each of R, G, B (histogram matching). */
    private fun histogramMatch(src: Bitmap, dst: Bitmap): Array<IntArray> {
        val sp = IntArray(src.width * src.height)
        src.getPixels(sp, 0, src.width, 0, 0, src.width, src.height)
        val dp = IntArray(dst.width * dst.height)
        dst.getPixels(dp, 0, dst.width, 0, 0, dst.width, dst.height)

        return Array(3) { c ->
            val shift = 16 - 8 * c // 16 = red, 8 = green, 0 = blue
            val hs = LongArray(256)
            val hd = LongArray(256)
            for (p in sp) hs[(p shr shift) and 255]++
            for (p in dp) hd[(p shr shift) and 255]++
            val cs = DoubleArray(256)
            val cd = DoubleArray(256)
            var a = 0L
            var b = 0L
            for (i in 0..255) {
                a += hs[i]
                b += hd[i]
                cs[i] = a.toDouble() / sp.size
                cd[i] = b.toDouble() / dp.size
            }
            val map = FloatArray(256)
            var j = 0
            for (i in 0..255) {
                while (j < 255 && cd[j] < cs[i]) j++
                map[i] = j.toFloat()
            }
            IntArray(256) { i ->
                var s = 0f
                for (k in -4..4) s += map[(i + k).coerceIn(0, 255)]
                val sm = s / 9f
                (0.9f * sm + 0.1f * i).roundToInt().coerceIn(0, 255)
            }
        }
    }

    /** Runs body(from, to) on all CPU cores, each core gets its own slice of pixels. */
    private fun parallel(total: Int, body: (Int, Int) -> Unit) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val chunk = (total + threads - 1) / threads
            val tasks = (0 until threads).map { t ->
                Callable { body(t * chunk, min(total, (t + 1) * chunk)) }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** Fast box blur using running sums. */
    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val d = 2 * r + 1
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val o = y * w
            var sum = 0
            for (k in -r..r) sum += src[o + k.coerceIn(0, w - 1)].toInt() and 255
            for (x in 0 until w) {
                tmp[o + x] = (sum / d).toByte()
                val add = (x + r + 1).coerceAtMost(w - 1)
                val rem = (x - r).coerceAtLeast(0)
                sum += (src[o + add].toInt() and 255) - (src[o + rem].toInt() and 255)
            }
        }
        val col = IntArray(w)
        for (k in -r..r) {
            val o = k.coerceIn(0, h - 1) * w
            for (x in 0 until w) col[x] += tmp[o + x].toInt() and 255
        }
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) out[o + x] = (col[x] / d).toByte()
            val add = (y + r + 1).coerceAtMost(h - 1) * w
            val rem = (y - r).coerceAtLeast(0) * w
            for (x in 0 until w) col[x] += (tmp[add + x].toInt() and 255) - (tmp[rem + x].toInt() and 255)
        }
        return out
    }
}
