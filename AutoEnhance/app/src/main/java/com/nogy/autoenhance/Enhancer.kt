package com.nogy.autoenhance

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Smart auto-enhance: looks at EACH photo (brightness, contrast, colour cast,
 * night or day) and then fixes it, plus a "Moto-style" look on top.
 * Tweak the knobs below to taste.
 */
object Enhancer {

    // ---- "Moto look" knobs ----
    private const val WARMTH = 0.025f        // + warmer, - cooler
    private const val SATURATION = 0.08f     // flat colour boost
    private const val VIBRANCE = 0.20f       // extra boost for dull colours only
    private const val CONTRAST = 0.22f       // S-curve strength
    private const val SHADOW_LIFT = 0.18f    // brighten dark areas
    private const val HIGHLIGHT_SAVE = 0.12f // protect bright areas (sky)

    fun enhance(context: Context, uri: Uri, displayName: String): Uri? {
        val bmp: Bitmap = try {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setMutableRequired(true)
                if (info.size.width.toLong() * info.size.height > 20_000_000L) {
                    decoder.setTargetSampleSize(2) // 50MP mode: shrink to avoid crash
                }
            }
        } catch (e: OutOfMemoryError) {
            return null
        }

        val w = bmp.width
        val h = bmp.height

        // ---------- 1) LOOK at the photo ----------
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
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt().coerceIn(0, 255)
                hist[lum]++
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

        // ---------- 2) DECIDE the fixes ----------
        // gentle white balance (gray-world, only half strength, capped)
        val mr = sumR / n
        val mg = sumG / n
        val mb = sumB / n
        val avg = (mr + mg + mb) / 3.0
        fun gain(m: Double) = (1.0 + 0.5 * (avg / max(m, 1.0) - 1.0)).coerceIn(0.9, 1.1).toFloat()
        val gr = gain(mr) * (1f + WARMTH)
        val gg = gain(mg)
        val gb = gain(mb) * (1f - WARMTH)

        // exposure: day photos go to a healthy middle, night photos stay moody
        val medN = (med / 255f).coerceIn(0.02f, 0.98f)
        val night = medN < 0.22f
        val target = if (night) 0.30f else 0.45f
        var gamma = ln(target) / ln(medN)
        gamma = 1f + 0.8f * (gamma - 1f)
        gamma = gamma.coerceIn(if (night) 0.75f else 0.6f, 1.25f)

        val black = (lo / 255f).coerceAtMost(0.06f)
        val white = (hi / 255f).coerceAtLeast(0.88f)
        val shadowAmt = if (night) SHADOW_LIFT * 0.5f else SHADOW_LIFT

        // one tone curve (256 entries) used for every pixel
        val lut = IntArray(256)
        for (i in 0..255) {
            var v = i / 255f
            v = ((v - black) / (white - black)).coerceIn(0f, 1f)
            v = v.pow(gamma)
            v += shadowAmt * 4f * v * (1f - v) * (1f - v)
            v -= HIGHLIGHT_SAVE * 4f * v * v * (1f - v)
            v = v.coerceIn(0f, 1f)
            val s = v * v * (3f - 2f * v)
            v += (s - v) * CONTRAST
            lut[i] = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }

        // ---------- 3) APPLY to every pixel ----------
        val rows = 128
        val buf = IntArray(w * rows)
        var row = 0
        while (row < h) {
            val hh = min(rows, h - row)
            bmp.getPixels(buf, 0, w, 0, row, w, hh)
            for (i in 0 until w * hh) {
                val p = buf[i]
                var r = lut[(((p shr 16) and 255) * gr).toInt().coerceIn(0, 255)]
                var g = lut[(((p shr 8) and 255) * gg).toInt().coerceIn(0, 255)]
                var b = lut[((p and 255) * gb).toInt().coerceIn(0, 255)]

                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
                val k = 1f + SATURATION + VIBRANCE * (1f - sat)
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                r = (l + (r - l) * k).roundToInt().coerceIn(0, 255)
                g = (l + (g - l) * k).roundToInt().coerceIn(0, 255)
                b = (l + (b - l) * k).roundToInt().coerceIn(0, 255)

                buf[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(buf, 0, w, 0, row, w, hh)
            row += hh
        }

        // ---------- 4) SAVE to Pictures/Enhanced ----------
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ENH_" + displayName.substringBeforeLast('.') + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Enhanced")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (out == null) {
            bmp.recycle()
            return null
        }
        resolver.openOutputStream(out)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(out, values, null, null)
        bmp.recycle()
        return out
    }
}
