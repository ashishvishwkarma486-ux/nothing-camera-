package com.nogy.autoenhance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Editing settings suggested by the AI (all values clamped for safety). */
class AiParams(
    val scene: String,
    val exposure: Float,
    val warmth: Float,
    val shadows: Float,
    val highlights: Float,
    val contrast: Float,
    val saturation: Float,
    val vibrance: Float,
    val sharpness: Float,
    val clarity: Float
)

object GeminiClient {

    const val DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"
    private const val TEXT_MODEL = "gemini-flash-latest"

    @Volatile
    var lastError: String = ""

    private const val EDIT_PROMPT =
        "Retouch this photo like a professional photographer so it is ready for Instagram and " +
        "looks great in a phone gallery. Lift dark faces and dim corners, calm burnt bright areas, " +
        "fix the white balance (no yellow or blue tint), make dull colours richer, clean grainy noise, " +
        "sharpen blurry details and restore small details (eyelashes, text) only where they are " +
        "already visible. If there are people, gently smooth blemishes, spots and uneven skin tone " +
        "but KEEP natural skin texture (no plastic look), and add a subtle portrait-style " +
        "background blur. Give it a warm, slightly cinematic, Motorola-phone look. " +
        "Do NOT change anyone's face or identity, the composition, the framing or the objects in " +
        "the scene, and do not remove or add anything. Return only the edited image."

    private const val SETTINGS_PROMPT =
        "You are a professional photo editor. Look at this photo and return ONLY a JSON object " +
        "with these fields: scene (one word: food, portrait, landscape, night, indoor, street, other), " +
        "exposure (-1 to 1, negative darkens), warmth (-1 to 1, negative cooler), " +
        "shadows (0 to 1, lifts dark areas), highlights (0 to 1, recovers bright areas), " +
        "contrast (0 to 1), saturation (0 to 1), vibrance (0 to 1), sharpness (0 to 1), clarity (0 to 1). " +
        "Goal: a natural, vivid, social-media-ready look like Motorola phones: slightly warm, " +
        "punchy but realistic colours, clean bright shadows, never over-processed. " +
        "Keep skin tones natural. For night photos keep the mood and use low sharpness."

    private fun jpegB64(bmp: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos) // re-encoded: no GPS/EXIF is sent
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun requestBody(prompt: String, b64: String, config: JSONObject): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", config)
    }

    private fun post(apiKey: String, model: String, body: JSONObject, readTimeoutMs: Int): String? {
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        ).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                } catch (_: Throwable) {
                    null
                }
                lastError = "HTTP $code"
                Log.w("AutoEnhance", "Gemini HTTP $code $err")
                return null
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Gemini image model returns an edited version of the photo. */
    fun editImage(apiKey: String, model: String, img: Bitmap): Bitmap? {
        return try {
            val config = JSONObject().put("responseModalities", JSONArray().put("IMAGE"))
            val body = requestBody(EDIT_PROMPT, jpegB64(img, 88), config)
            val raw = post(apiKey, model, body, 120000) ?: return null
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                if (inl != null) {
                    val bytes = Base64.decode(inl.getString("data"), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) return bmp
                }
            }
            lastError = "no image returned"
            null
        } catch (t: Throwable) {
            lastError = t.toString().take(80)
            Log.w("AutoEnhance", "Gemini image edit failed: $t")
            null
        }
    }

    /** Backup: Gemini only suggests editing settings (needs much less quota). */
    fun analyze(apiKey: String, thumb: Bitmap): AiParams? {
        return try {
            val config = JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2)
            val body = requestBody(SETTINGS_PROMPT, jpegB64(thumb, 80), config)
            val raw = post(apiKey, TEXT_MODEL, body, 40000) ?: return null
            val partsOut = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until partsOut.length()) sb.append(partsOut.getJSONObject(i).optString("text", ""))
            val text = sb.toString().trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val j = JSONObject(text)

            fun f(name: String, lo: Float, hi: Float, def: Float): Float {
                val v = j.optDouble(name, def.toDouble()).toFloat()
                return if (v.isNaN()) def else v.coerceIn(lo, hi)
            }
            AiParams(
                scene = j.optString("scene", "other"),
                exposure = f("exposure", -1f, 1f, 0f),
                warmth = f("warmth", -1f, 1f, 0.4f),
                shadows = f("shadows", 0f, 1f, 0.5f),
                highlights = f("highlights", 0f, 1f, 0.4f),
                contrast = f("contrast", 0f, 1f, 0.45f),
                saturation = f("saturation", 0f, 1f, 0.4f),
                vibrance = f("vibrance", 0f, 1f, 0.5f),
                sharpness = f("sharpness", 0f, 1f, 0.5f),
                clarity = f("clarity", 0f, 1f, 0.4f)
            )
        } catch (t: Throwable) {
            lastError = t.toString().take(80)
            Log.w("AutoEnhance", "Gemini settings failed: $t")
            null
        }
    }
}
