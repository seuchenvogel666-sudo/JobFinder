package de.bewerbung.jobfinder.data

/** Gestaltungsvorlagen für Lebenslauf, Anschreiben und Deckblatt. */
enum class DesignTemplate(val label: String, val description: String) {
    HEADER("Kopfband", "Name im farbigen Band oben – seriös, ideal für Pharma/QM"),
    SIDEBAR("Seitenleiste", "Foto, Kontakt und Kenntnisse links – modern"),
    TIMELINE("Zeitleiste", "Werdegang als Linie mit Punkten – Entwicklung auf einen Blick"),
    SIMPLE("Schlicht", "Einspaltig, ohne Foto und Farbflächen – ideal für Online-Portale")
}

/** Wählbare Akzentfarben (dezent, gut druckbar). */
enum class AccentColor(val label: String, val argb: Int) {
    BLUE("Blau", 0xFF1B4F72.toInt()),
    PETROL("Petrol", 0xFF0E6466.toInt()),
    ANTHRACITE("Anthrazit", 0xFF37474F.toInt()),
    BORDEAUX("Bordeaux", 0xFF7B1E3A.toInt()),
    GREEN("Grün", 0xFF2E6B3A.toInt())
}

/** Kontaktdaten für Briefkopf, Lebenslauf und Deckblatt. */
data class Profile(
    val name: String = "",
    val headline: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val web: String = ""
) {
    val isEmpty: Boolean get() = name.isBlank() && address.isBlank() && phone.isBlank() && email.isBlank()

    /** Kontaktzeilen in sinnvoller Reihenfolge. */
    fun contactLines(): List<String> =
        (address.lines().map { it.trim() } + listOf(phone.trim(), email.trim(), web.trim())).filter { it.isNotBlank() }

    /** Als Text für die KI (Absenderdaten). */
    fun asText(): String = (listOf(name) + contactLines()).filter { it.isNotBlank() }.joinToString("\n")

    companion object {
        /** Liest Kontaktdaten aus einem Freitext (alte Absenderdaten oder Lebenslauf-Kopf). */
        fun parse(text: String): Profile {
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return Profile()
            val email = lines.firstOrNull { Regex("[\\w.%+-]+@[\\w.-]+\\.[a-zA-Z]{2,}").containsMatchIn(it) }
                ?.let { Regex("[\\w.%+-]+@[\\w.-]+\\.[a-zA-Z]{2,}").find(it)?.value } ?: ""
            val phone = lines.firstOrNull {
                Regex("(\\+|\\b0)\\d[\\d /()-]{6,}").containsMatchIn(it) && !it.contains("@")
            }?.let { Regex("(\\+|\\b0)\\d[\\d /()-]{6,}").find(it)?.value?.trim() } ?: ""
            val web = lines.firstOrNull { Regex("(?i)linkedin|xing|www\\.|https?://").containsMatchIn(it) && !it.contains("@") } ?: ""
            val name = lines.first().takeIf { it.split(" ").size in 2..5 && it.none { c -> c.isDigit() } && !it.contains("@") } ?: ""
            val address = lines.filter {
                it != name && !it.contains(email.ifBlank { "§§" }) && (phone.isBlank() || !it.contains(phone)) && it != web &&
                        (Regex("\\b\\d{5}\\b").containsMatchIn(it) || Regex("(?i)(str\\.|straße|strasse|weg|allee|platz|gasse|ring)\\s*\\d").containsMatchIn(it))
            }.take(2).joinToString("\n")
            return Profile(name = name, address = address, phone = phone, email = email, web = web)
        }
    }
}

/** Gestaltungs-Einstellungen. */
data class DesignConfig(
    val template: DesignTemplate = DesignTemplate.HEADER,
    val accent: AccentColor = AccentColor.BLUE,
    /** Deckblatt standardmäßig in die Bewerbungsmappe aufnehmen (für Online-Bewerbungen meist unnötig). */
    val coverByDefault: Boolean = false
)
