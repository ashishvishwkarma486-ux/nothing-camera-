package com.nogy.autoenhance

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The finished photo exactly as Gemini returned it (never edited by this app). */
class GeneratedImage(val bytes: ByteArray, val mime: String)

object GeminiClient {

    const val PRO_MODEL = "gemini-3-pro-image-preview"   // strongest, up to 4K (needs billing)
    const val FLASH_MODEL = "gemini-2.5-flash-image"     // backup, about 1K

    const val DEFAULT_PROMPT =
        "You are a world-class professional photographer and retoucher. First look at this photo and " +
        "understand it: what it shows, the lighting, the mood, and any people. Then create the final, " +
        "polished version of this exact photo so it looks like it was shot on a high-end full-frame " +
        "camera and is ready to post on Instagram with no further editing.\n" +
        "- Increase the resolution and add realistic fine detail (textures, hair, eyelashes, fabric, " +
        "foliage, and any text that is already readable).\n" +
        "- Balance the exposure: lift dark faces and dim corners, calm blown-out bright areas.\n" +
        "- Fix the white balance (no yellow or blue tint) and make dull colours rich but realistic.\n" +
        "- Remove grain and noise and sharpen blurry areas naturally.\n" +
        "- If there are people: gently smooth blemishes and uneven skin tone but keep real skin " +
        "texture (no plastic look) and keep eyes bright and natural.\n" +
        "- Match the treatment to the scene: food fresh and appetizing, landscapes with depth and a " +
        "rich sky, night photos keep their mood.\n" +
        "- Finish with a subtle warm, slightly cinematic look.\n" +
        "Do NOT change the composition, framing or aspect ratio, anyone's face, identity or " +
        "expression, the objects, or any text. Do not add or remove anything. Return only the " +
        "finished image."

    @Volatile
    var lastError: String = ""

    private fun post(apiKey: String, model: String, body: JSONObject, readTimeoutMs: Int): String? {
        val conn = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        ).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
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

    /** Sends the whole photo + prompt, returns Gemini's finished photo. */
    fun editPhoto(
        apiKey: String, model: String, photo: ByteArray, mime: String,
        aspect: String, prompt: String
    ): GeneratedImage? {
        return try {
            val b64 = Base64.encodeToString(photo, Base64.NO_WRAP)
            val parts = JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", mime).put("data", b64)
                    )
                )
            val imageConfig = JSONObject().put("aspectRatio", aspect)
            if (model.contains("gemini-3")) imageConfig.put("imageSize", "4K")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject().put("imageConfig", imageConfig))

            val raw = post(apiKey, model, body, 300000) ?: return null
            val root = JSONObject(raw)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val why = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
                lastError = "blocked or empty $why".trim()
                return null
            }
            val partsOut = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            var best: GeneratedImage? = null
            if (partsOut != null) {
                for (i in 0 until partsOut.length()) {
                    val p = partsOut.getJSONObject(i)
                    if (p.optBoolean("thought", false)) continue // skip draft images
                    val inl = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                    val data = inl.optString("data", "")
                    if (data.isEmpty()) continue
                    val mt = inl.optString("mimeType", inl.optString("mime_type", "image/png"))
                    best = GeneratedImage(Base64.decode(data, Base64.DEFAULT), mt)
                }
            }
            if (best == null) lastError = "no image returned"
            best
        } catch (t: Throwable) {
            lastError = t.toString().take(80)
            Log.w("AutoEnhance", "Gemini edit failed: $t")
            null
        }
    }
}
