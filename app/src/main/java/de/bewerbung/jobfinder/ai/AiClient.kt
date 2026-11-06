package de.bewerbung.jobfinder.ai

import de.bewerbung.jobfinder.data.AiProvider
import de.bewerbung.jobfinder.data.Http
import de.bewerbung.jobfinder.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Spricht wahlweise ChatGPT, Gemini oder Claude an – mit dem eigenen API-Schlüssel. */
class AiClient(private val settings: SettingsStore) {

    fun isConfigured(): Boolean {
        val p = settings.aiProvider
        return p != AiProvider.NONE && settings.apiKey(p).isNotBlank()
    }

    suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        val provider = settings.aiProvider
        val key = settings.apiKey(provider)
        val model = settings.model(provider)
        if (provider == AiProvider.NONE || key.isBlank()) {
            throw IllegalStateException("Bitte zuerst in den Einstellungen einen KI-Anbieter und API-Schlüssel eintragen.")
        }
        val text = when (provider) {
            AiProvider.OPENAI -> openAi(key, model, system, user)
            AiProvider.GEMINI -> gemini(key, model, system, user)
            AiProvider.CLAUDE -> claude(key, model, system, user)
            AiProvider.NONE -> ""
        }
        cleanup(text)
    }

    private fun openAi(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val resp = JSONObject(
            Http.post(
                "https://api.openai.com/v1/chat/completions", body.toString(),
                mapOf("Authorization" to "Bearer $key")
            )
        )
        return resp.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    private fun gemini(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
        val resp = JSONObject(
            Http.post(
                "https://generativelanguage.googleapis.com/v1beta/models/${Http.enc(model)}:generateContent",
                body.toString(),
                mapOf("x-goog-api-key" to key)
            )
        )
        val candidates = resp.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini lieferte keine Antwort: ${resp.toString().take(300)}")
        val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    private fun claude(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", system)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val resp = JSONObject(
            Http.post(
                "https://api.anthropic.com/v1/messages", body.toString(),
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            )
        )
        val content = resp.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") sb.append(c.optString("text"))
        }
        return sb.toString()
    }

    /** Entfernt Markdown-Reste, falls das Modell sie trotzdem liefert. */
    private fun cleanup(s: String): String = s
        .replace(Regex("(?m)^```.*$"), "")
        .replace("**", "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .trim()
}
