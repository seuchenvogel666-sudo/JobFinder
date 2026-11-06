package de.bewerbung.jobfinder.data

import android.content.Context

/** Einstellungen (API-Schlüssel usw.) – liegen nur im privaten App-Speicher. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var aiProvider: AiProvider
        get() = runCatching { AiProvider.valueOf(p.getString("aiProvider", "NONE")!!) }
            .getOrDefault(AiProvider.NONE)
        set(v) = p.edit().putString("aiProvider", v.name).apply()

    fun apiKey(provider: AiProvider): String = p.getString("key_${provider.name}", "") ?: ""
    fun setApiKey(provider: AiProvider, key: String) =
        p.edit().putString("key_${provider.name}", key.replace(Regex("\\s"), "")).apply()

    fun model(provider: AiProvider): String =
        (p.getString("model_${provider.name}", "") ?: "").ifBlank { defaultModel(provider) }

    fun setModel(provider: AiProvider, model: String) =
        p.edit().putString("model_${provider.name}", model.trim()).apply()

    var senderInfo: String
        get() = p.getString("senderInfo", "") ?: ""
        set(v) = p.edit().putString("senderInfo", v).apply()

    /** Aktivierte Jobportale (Standard: alle). */
    var webSources: Set<WebSite>
        get() {
            val raw = p.getStringSet("webSources", null) ?: return WebSite.entries.toSet()
            return raw.mapNotNull { n -> WebSite.entries.firstOrNull { it.name == n } }.toSet()
        }
        set(v) = p.edit().putStringSet("webSources", v.map { it.name }.toSet()).apply()

    // Letzte Suche merken
    var lastQuery: String
        get() = p.getString("lastQuery", "") ?: ""
        set(v) = p.edit().putString("lastQuery", v).apply()
    var lastRadius: RadiusOption
        get() = RadiusOption.entries.firstOrNull { it.name == p.getString("lastRadiusOption", "") }
            ?: RadiusOption.KM25
        set(v) = p.edit().putString("lastRadiusOption", v.name).apply()
    var lastPlace: String
        get() = p.getString("lastPlace", "") ?: ""
        set(v) = p.edit().putString("lastPlace", v).apply()

    companion object {
        /** Modellnamen ändern sich häufig – in den Einstellungen anpassbar. */
        fun defaultModel(provider: AiProvider): String = when (provider) {
            AiProvider.OPENAI -> "gpt-5.4-mini"
            AiProvider.GEMINI -> "gemini-flash-latest"
            AiProvider.CLAUDE -> "claude-sonnet-5-5"
            AiProvider.NONE -> ""
        }
    }
}
