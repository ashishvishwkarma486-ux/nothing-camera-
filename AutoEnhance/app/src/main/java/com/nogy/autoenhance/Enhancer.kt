package com.nogy.autoenhance

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ln

/**
 * v5: this app does NO editing.
 *  1) take the whole original photo (location data removed, nothing resized)
 *  2) send it to Gemini with the prompt
 *  3) save Gemini's finished photo to Pictures/Enhanced, byte for byte
 */
object Enhancer {

    private const val MAX_SEND_BYTES = 14_500_000 // Gemini's request limit is 20 MB after base64

    private class Prepared(val bytes: ByteArray, val aspect: String)

    private val RATIOS = listOf(
        "1:1" to 1f, "2:3" to 2f / 3f, "3:2" to 1.5f, "3:4" to 0.75f, "4:3" to 4f / 3f,
        "4:5" to 0.8f, "5:4" to 1.25f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "21:9" to 21f / 9f
    )

    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    private fun friendly(err: String): String = when {
        err.contains("429") -> "limit or quota reached"
        err.contains("403") -> "not allowed (key, or this model needs billing)"
        err.contains("400") -> "request not accepted"
        err.contains("404") -> "model name not found"
        err.isEmpty() -> "unknown error"
        else -> err
    }

    private fun closestAspect(w: Int, h: Int): String {
        val r = w.toFloat() / max1(h)
        return RATIOS.minByOrNull { abs(ln(it.second / r)) }!!.first
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    /** Original bytes with GPS removed. Re-encodes only if rotation, size or format needs it. */
    private fun preparePhoto(context: Context, uri: Uri): Prepared? {
        val resolver = context.contentResolver
        val original: ByteArray? = try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }
        if (original == null) return null
        val mime = resolver.getType(uri) ?: "image/jpeg"

        var orientation = ExifInterface.ORIENTATION_NORMAL
        var stripped: ByteArray = original
        if (mime == "image/jpeg") {
            val tmp = File(context.cacheDir, "send.jpg")
            try {
                tmp.writeBytes(original)
                val exif = ExifInterface(tmp.absolutePath)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                for (tag in GPS_TAGS) exif.setAttribute(tag, null)
                exif.saveAttributes()
                stripped = tmp.readBytes()
            } catch (t: Throwable) {
                Log.w("AutoEnhance", "Could not strip location: $t")
            } finally {
                tmp.delete()
            }
        }

        val rotated = orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED
        val needsReencode = mime != "image/jpeg" || rotated || stripped.size > MAX_SEND_BYTES

        if (!needsReencode) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(stripped, 0, stripped.size, opts)
            return Prepared(stripped, closestAspect(opts.outWidth, opts.outHeight))
        }

        // upright, full-size, very high quality re-encode (ImageDecoder applies rotation)
        return try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                if (info.size.width.toLong() * info.size.height > 24_000_000L) {
                    decoder.setTargetSampleSize(2)
                }
            }
            var quality = 97
            var bytes: ByteArray
            do {
                val baos = ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos)
                bytes = baos.toByteArray()
                quality -= 7
            } while (bytes.size > MAX_SEND_BYTES && quality > 60)
            val aspect = closestAspect(bmp.width, bmp.height)
            bmp.recycle()
            Prepared(bytes, aspect)
        } catch (t: Throwable) {
            Log.w("AutoEnhance", "Re-encode failed: $t")
            null
        }
    }

    fun enhance(context: Context, uri: Uri, displayName: String): List<Uri> {
        val prefs = context.getSharedPreferences("enhance", Context.MODE_PRIVATE)
        val key = prefs.getString("gemini_key", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            prefs.edit().putString("last_result", "No Gemini key saved, photo left as it is").apply()
            return emptyList()
        }
        val prompt = prefs.getString("prompt", "")?.trim().orEmpty().ifEmpty { GeminiClient.DEFAULT_PROMPT }
        val custom = prefs.getString("image_model", "")?.trim().orEmpty()
        val models = (listOf(custom) + listOf(GeminiClient.PRO_MODEL, GeminiClient.FLASH_MODEL))
            .filter { it.isNotEmpty() }.distinct()

        val prepared = preparePhoto(context, uri)
        if (prepared == null) {
            prefs.edit().putString("last_result", "Could not read the photo").apply()
            return emptyList()
        }

        var errors = ""
        for (m in models) {
            val img = GeminiClient.editPhoto(key, m, prepared.bytes, "image/jpeg", prepared.aspect, prompt)
            if (img != null) {
                val saved = save(context, img, "AI_" + displayName.substringBeforeLast('.'))
                prefs.edit().putString(
                    "last_result",
                    if (saved != null) "Done with $m" else "Gemini finished but saving failed"
                ).apply()
                return if (saved != null) listOf(saved) else emptyList()
            }
            errors += "$m: ${friendly(GeminiClient.lastError)}. "
        }
        prefs.edit().putString("last_result", "Gemini failed. $errors Original left untouched.").apply()
        return emptyList()
    }

    /** Writes Gemini's bytes exactly as received. */
    private fun save(context: Context, img: GeneratedImage, baseName: String): Uri? {
        val ext: String
        val mimeOut: String
        when {
            img.mime.contains("png") -> { ext = "png"; mimeOut = "image/png" }
            img.mime.contains("webp") -> { ext = "webp"; mimeOut = "image/webp" }
            else -> { ext = "jpg"; mimeOut = "image/jpeg" }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$baseName.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mimeOut)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Enhanced")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(out)?.use { it.write(img.bytes) }
        } catch (t: Throwable) {
            resolver.delete(out, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(out, values, null, null)
        return out
    }
}
