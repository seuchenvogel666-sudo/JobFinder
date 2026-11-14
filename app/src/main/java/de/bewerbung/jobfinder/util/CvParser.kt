package de.bewerbung.jobfinder.util

/**
 * Zerlegt einen Lebenslauf-Text in Abschnitte und Stationen, damit er gestaltet werden kann.
 *
 * Erwartetes Format (so liefert es auch die KI-Überarbeitung):
 *   BERUFSERFAHRUNG
 *   03/2021 – heute | Laborant | Muster Pharma GmbH, Berlin
 *   • Stichpunkt
 *   SPRACHEN
 *   Englisch – C1
 * Klappt aber auch mit frei geschriebenen Lebensläufen recht gut.
 */
object CvParser {

    enum class Kind { PROFILE, PERSONAL, SIDE, MAIN }

    sealed class Item {
        data class Station(val date: String, val title: String, val sub: String, val bullets: List<String>) : Item()
        data class Bullet(val text: String) : Item()
        data class Para(val text: String) : Item()
        data class KeyValue(val key: String, val value: String) : Item()
    }

    data class Section(val title: String, val kind: Kind, val items: List<Item>)
    data class Cv(val intro: List<String>, val sections: List<Section>)

    private val dateRange = Regex(
        "^\\s*((seit|ab)\\s+)?((\\d{1,2}[./])?\\d{4})(?!\\d)(\\s*(–|-|—|bis)\\s*((\\d{1,2}[./])?\\d{4}|heute|jetzt|aktuell|dato|laufend))?",
        RegexOption.IGNORE_CASE
    )
    private val bulletStart = Regex("^\\s*[•·▪\\-–*]\\s+")

    private fun isHeading(line: String): Boolean {
        val t = line.trim().trimEnd(':')
        if (t.length < 3 || t.length > 45 || bulletStart.containsMatchIn(line)) return false
        if (dateRange.containsMatchIn(t) && t.firstOrNull()?.isDigit() == true) return false
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return true
        // "Berufserfahrung:" als Überschrift
        return line.trim().endsWith(":") && t.split(" ").size <= 4
    }

    fun kindOf(title: String): Kind {
        val t = title.lowercase()
        return when {
            Regex("kurzprofil|profil|über mich|zusammenfassung|summary").containsMatchIn(t) -> Kind.PROFILE
            Regex("persönlich|kontakt").containsMatchIn(t) -> Kind.PERSONAL
            Regex("kenntnis|fähigkeit|skill|kompetenz|sprache|edv|it-|software|interess|hobby|führerschein|stärken|soft").containsMatchIn(t) -> Kind.SIDE
            else -> Kind.MAIN
        }
    }

    fun parse(text: String): Cv {
        val lines = text.replace("\r", "").lines()
        val intro = mutableListOf<String>()
        val sections = mutableListOf<Section>()
        var title: String? = null
        val items = mutableListOf<Item>()

        // Offene Station, an die Stichpunkte angehängt werden
        var stDate: String? = null
        var stTitle = ""
        var stSub = ""
        val stBullets = mutableListOf<String>()

        fun closeStation() {
            val d = stDate ?: return
            items.add(Item.Station(d, stTitle, stSub, stBullets.toList()))
            stDate = null; stTitle = ""; stSub = ""; stBullets.clear()
        }

        fun closeSection() {
            closeStation()
            title?.let { t -> if (items.isNotEmpty()) sections.add(Section(t, kindOf(t), items.toList())) }
            items.clear()
        }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (isHeading(line)) {
                closeSection()
                title = line.trimEnd(':').trim()
                continue
            }
            if (title == null) {
                intro.add(line); continue
            }
            val kind = kindOf(title!!)
            val bullet = bulletStart.find(line)
            val date = dateRange.find(line)
            when {
                bullet != null -> {
                    val b = line.substring(bullet.range.last + 1).trim()
                    if (stDate != null) stBullets.add(b) else items.add(Item.Bullet(b))
                }
                date != null && date.range.first == 0 && line.firstOrNull()?.let { it.isDigit() || it.lowercaseChar() == 's' || it.lowercaseChar() == 'a' } == true
                        && kind != Kind.SIDE -> {
                    closeStation()
                    val rest = line.substring(date.range.last + 1).trim().trimStart('|', ':', '-', '–', ' ').trim()
                    stDate = date.value.trim()
                    val parts = rest.split("|").map { it.trim() }.filter { it.isNotBlank() }
                    stTitle = parts.getOrNull(0) ?: ""
                    stSub = parts.drop(1).joinToString(" · ")
                }
                stDate != null && stTitle.isBlank() -> stTitle = line
                stDate != null && stSub.isBlank() && stBullets.isEmpty() -> stSub = line
                stDate != null -> stBullets.add(line)
                kind == Kind.SIDE || kind == Kind.PERSONAL -> {
                    val kv = Regex("^(.{2,40}?)\\s*(:| – | - |\\t)\\s*(.+)$").find(line)
                    items.add(
                        if (kv != null) Item.KeyValue(kv.groupValues[1].trim(), kv.groupValues[3].trim())
                        else Item.Bullet(line)
                    )
                }
                else -> items.add(Item.Para(line))
            }
        }
        closeSection()
        // Kein einziger Abschnitt erkannt: alles als ein Abschnitt darstellen
        if (sections.isEmpty() && intro.isNotEmpty()) {
            return Cv(emptyList(), listOf(Section("Lebenslauf", Kind.MAIN, intro.map { Item.Para(it) })))
        }
        return Cv(intro, sections)
    }
}
