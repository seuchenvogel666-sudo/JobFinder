package de.bewerbung.jobfinder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Ermittelt den Arbeitsort einer Anzeige aus der Detailseite, wenn die Ergebnisliste keinen
 * (brauchbaren) Ort enthält – z. B. bei LinkedIn oder XING.
 *
 * Reihenfolge:
 *  1. Strukturierte Daten der Seite (schema.org "JobPosting": PLZ, Ort, ggf. Koordinaten)
 *  2. Typische Angaben im Text ("Arbeitsort: …", "Standort: …", "12345 Musterstadt")
 * Danach wird der Ort nachgeschlagen, damit die Entfernung (Luftlinie) berechnet werden kann.
 */
class LocationEnricher(private val web: WebJobScraper) {

    data class Info(
        val locality: String,
        val lat: Double?,
        val lon: Double?,
        val remote: Boolean,
        val description: String
    )

    suspend fun enrich(job: Job): Info? {
        var ld: List<String> = emptyList()
        var text = ""
        var html = ""

        // LinkedIn: öffentliche Detailansicht für Gäste
        if (job.source == "LinkedIn") {
            val id = Regex("(\\d{7,})").findAll(job.url).lastOrNull()?.value
            if (id != null) {
                html = withContext(Dispatchers.IO) {
                    runCatching {
                        Http.get(
                            "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/$id",
                            mapOf("Accept" to "text/html", "Accept-Language" to "de-DE,de;q=0.9"), timeoutMs = 12_000
                        )
                    }.getOrDefault("")
                }
                val loc = Regex("topcard__flavor--bullet[^>]*>([\\s\\S]*?)</span>").find(html)
                    ?.groupValues?.get(1)?.stripHtml()?.trim()
                if (!loc.isNullOrBlank()) {
                    val place = withContext(Dispatchers.IO) { GeoRepository.locate(null, loc) }
                    val desc = Regex("show-more-less-html__markup[^>]*>([\\s\\S]*?)</div>").find(html)
                        ?.groupValues?.get(1)?.stripHtml() ?: ""
                    return Info(loc, place?.lat, place?.lon, loc.contains("remote", true), desc)
                }
            }
        }

        // Normale Seite per HTTP (schnell) …
        if (html.isBlank()) {
            html = withContext(Dispatchers.IO) {
                runCatching {
                    Http.get(job.url, mapOf("Accept" to "text/html", "Accept-Language" to "de-DE,de;q=0.9"), timeoutMs = 12_000)
                }.getOrDefault("")
            }
        }
        if (html.isNotBlank()) {
            ld = Regex("<script[^>]*application/ld\\+json[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.groupValues[1] }.toList()
            val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
            text = body.stripHtml()
        }
        // … sonst wie ein Browser (für Seiten, die Inhalte erst per JavaScript laden oder HTTP blockieren)
        if (ld.isEmpty() && text.length < 600) {
            val (l, t) = runCatching { web.pageData(job.url) }.getOrDefault(emptyList<String>() to "")
            ld = l
            text = t
        }

        // 1) Strukturierte Daten
        for (raw in ld) {
            val found = fromJsonLd(raw) ?: continue
            val (postcode, locality, coords) = found
            val remote = raw.contains("TELECOMMUTE", ignoreCase = true)
            val desc = jsonLdDescription(raw).ifBlank { text }
            if (coords != null) {
                return Info(listOfNotNull(postcode, locality).joinToString(" "), coords.first, coords.second, remote, desc)
            }
            val place = withContext(Dispatchers.IO) { GeoRepository.locate(postcode, locality) }
            if (place != null || locality != null) {
                return Info(listOfNotNull(postcode, locality).joinToString(" "), place?.lat, place?.lon, remote, desc)
            }
        }

        // 2) Textmuster
        val candidate = locationFromText(text) ?: return if (text.isNotBlank()) Info("", null, null, false, text) else null
        val plz = Regex("\\b(\\d{5})\\b").find(candidate)?.groupValues?.get(1)
        val locality = candidate.replace(Regex("\\b\\d{5}\\b"), "").trim()
        val place = withContext(Dispatchers.IO) { GeoRepository.locate(plz, locality.ifBlank { null }) }
        return Info(candidate, place?.lat, place?.lon, false, text)
    }

    /** Liefert (PLZ, Ort, Koordinaten) aus einem JSON-LD-Block mit "JobPosting". */
    private fun fromJsonLd(raw: String): Triple<String?, String?, Pair<Double, Double>?>? {
        val root = runCatching { JSONTokener(raw.trim()).nextValue() }.getOrNull() ?: return null
        val posting = findPosting(root) ?: return null
        val locs = when (val l = posting.opt("jobLocation")) {
            is JSONArray -> (0 until l.length()).mapNotNull { l.optJSONObject(it) }
            is JSONObject -> listOf(l)
            else -> emptyList()
        }
        for (loc in locs) {
            val addr = loc.optJSONObject("address")
            val postcode = addr?.optString("postalCode")?.takeIf { it.isNotBlank() }
            val locality = addr?.optString("addressLocality")?.takeIf { it.isNotBlank() }
            val geo = loc.optJSONObject("geo")
            val lat = geo?.optString("latitude")?.toDoubleOrNull()
            val lon = geo?.optString("longitude")?.toDoubleOrNull()
            val coords = if (lat != null && lon != null && lat in 47.0..55.5 && lon in 5.5..15.5) lat to lon else null
            if (postcode != null || locality != null || coords != null) return Triple(postcode, locality, coords)
        }
        return null
    }

    private fun findPosting(any: Any?): JSONObject? {
        when (any) {
            is JSONObject -> {
                val type = any.opt("@type")
                if (type?.toString()?.contains("JobPosting") == true) return any
                any.optJSONArray("@graph")?.let { g -> findPosting(g)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until any.length()) findPosting(any.opt(i))?.let { return it }
        }
        return null
    }

    private fun jsonLdDescription(raw: String): String {
        val root = runCatching { JSONTokener(raw.trim()).nextValue() }.getOrNull()
        return findPosting(root)?.optString("description")?.stripHtml() ?: ""
    }

    companion object {
        private val labelled = Regex(
            "(?im)^(?:\\s*)(arbeitsort|einsatzort|dienstort|standort|arbeitsplatz|location|ort)\\s*[:\\-–]\\s*(.{2,60})$"
        )
        private val plzOrt = Regex(
            "\\b(\\d{5})\\s+([A-ZÄÖÜ][a-zäöüß.]+(?:[ -](?:am|an|der|im|in|bei|ob|vor|[A-ZÄÖÜ(][a-zäöüß.)]+)){0,3})"
        )

        /** Sucht im Anzeigentext nach einer Ortsangabe. */
        fun locationFromText(text: String): String? {
            if (text.isBlank()) return null
            labelled.find(text)?.let { m ->
                val v = m.groupValues[2].trim().trimEnd('.', ',')
                if (v.isNotBlank() && !Regex("(?i)homeoffice|remote|flexibel").containsMatchIn(v)) return v
            }
            plzOrt.find(text)?.let { return it.value.trim() }
            return null
        }
    }
}
