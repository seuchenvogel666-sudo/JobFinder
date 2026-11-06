package de.bewerbung.jobfinder.data

/** Ein Ort aus der Ortssuche. */
data class Place(
    val name: String,
    val detail: String,
    val lat: Double,
    val lon: Double
) {
    val label: String get() = if (detail.isBlank()) name else "$name ($detail)"
}

/** Eine Stellenanzeige – egal aus welcher Quelle. */
data class Job(
    val id: String,
    val source: String,
    val title: String,
    val company: String,
    val location: String,
    val lat: Double?,
    val lon: Double?,
    /** Entfernung in km zum gewählten Ort (falls bekannt). */
    val distanceKm: Double?,
    /** Veröffentlichung als Epoch-Millisekunden (falls bekannt). */
    val publishedMillis: Long?,
    val url: String,
    val snippet: String,
    /** Referenznummer der Bundesagentur (für Detailabruf). */
    val baRefnr: String? = null
)

enum class SortMode(val label: String) {
    DISTANCE("Entfernung"),
    DATE("Alter der Anzeige")
}

enum class DocType(val label: String) {
    LEBENSLAUF("Lebenslauf"),
    ANSCHREIBEN("Anschreiben"),
    ZEUGNIS("Zeugnis"),
    SONSTIGES("Sonstiges")
}

data class AppDocument(
    val id: String,
    val title: String,
    val type: DocType,
    val text: String,
    val updatedMillis: Long
)

enum class AiProvider(val label: String) {
    NONE("Keine KI"),
    OPENAI("ChatGPT (OpenAI)"),
    GEMINI("Gemini (Google)"),
    CLAUDE("Claude (Anthropic)")
}
