package de.bewerbung.jobfinder.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SearchResult(val jobs: List<Job>, val notes: List<String>)

/**
 * Sammelt Stellenangebote aus mehreren Quellen:
 *  - Bundesagentur für Arbeit (kostenlos, ohne Anmeldung)
 *  - Adzuna (kostenloser Schlüssel, sammelt Anzeigen vieler Jobbörsen)
 *  - Jooble (kostenloser Schlüssel, sammelt Anzeigen vieler Jobbörsen)
 * LinkedIn, XING, Indeed und StepStone bieten keine freie Schnittstelle an –
 * dafür gibt es in der App Direkt-Links mit den eingegebenen Suchwerten.
 */
class JobRepository(private val settings: SettingsStore) {

    suspend fun search(
        query: String,
        placeText: String,
        place: Place?,
        radiusKm: Int,
        maxAgeDays: Int
    ): SearchResult = coroutineScope {
        val ba = async(Dispatchers.IO) { runCatching { searchBa(query, placeText, radiusKm, maxAgeDays) } }
        val adz = async(Dispatchers.IO) {
            if (settings.adzunaAppId.isBlank() || settings.adzunaAppKey.isBlank()) null
            else runCatching { searchAdzuna(query, placeText, radiusKm, maxAgeDays) }
        }
        val joo = async(Dispatchers.IO) {
            if (settings.joobleKey.isBlank()) null
            else runCatching { searchJooble(query, placeText, radiusKm) }
        }

        val notes = mutableListOf<String>()
        val all = mutableListOf<Job>()
        fun collect(name: String, r: Result<List<Job>>?) {
            when {
                r == null -> notes += "$name: nicht eingerichtet (Schlüssel in Einstellungen)"
                r.isSuccess -> {
                    val list = r.getOrDefault(emptyList())
                    all += list
                    notes += "$name: ${list.size} Treffer"
                }
                else -> notes += "$name: Fehler – ${r.exceptionOrNull()?.message?.take(120)}"
            }
        }
        collect("Arbeitsagentur", ba.await())
        collect("Adzuna", adz.await())
        collect("Jooble", joo.await())

        // Entfernungen berechnen, wenn Koordinaten bekannt sind
        val withDistance = all.map { job ->
            if (place != null && job.lat != null && job.lon != null) {
                job.copy(distanceKm = GeoRepository.distanceKm(place.lat, place.lon, job.lat, job.lon))
            } else job
        }

        // Filter: nur Alter. Den Umkreis filtern die Jobbörsen selbst – ein eigener
        // Entfernungsfilter würde bei gleichnamigen Orten fälschlich alles aussortieren.
        val now = System.currentTimeMillis()
        val filtered = withDistance.filter { job ->
            maxAgeDays <= 0 || job.publishedMillis == null ||
                    (now - job.publishedMillis) <= (maxAgeDays + 1) * 86_400_000L
        }

        // Dubletten entfernen (gleicher Titel + gleiche Firma)
        val unique = filtered.distinctBy {
            (it.title.lowercase().replace(Regex("[^a-zäöüß0-9]"), "") + "|" +
                    it.company.lowercase().replace(Regex("[^a-zäöüß0-9]"), ""))
        }
        SearchResult(unique, notes)
    }

    // ---------------- Bundesagentur für Arbeit ----------------

    private val baBase = "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service"
    private val baHeaders = mapOf("X-API-Key" to "jobboerse-jobsuche")

    private fun searchBa(query: String, placeText: String, radiusKm: Int, maxAgeDays: Int): List<Job> {
        val sb = StringBuilder("?angebotsart=1&page=1&size=100")
        if (query.isNotBlank()) sb.append("&was=").append(Http.enc(query))
        if (placeText.isNotBlank()) {
            sb.append("&wo=").append(Http.enc(placeText))
            sb.append("&umkreis=").append(radiusKm.coerceIn(0, 200))
        }
        if (maxAgeDays > 0) sb.append("&veroeffentlichtseit=").append(maxAgeDays.coerceAtMost(100))

        // Die BA stellt ihre Schnittstelle gerade um (v4 -> v6, teils andere Feldnamen).
        // Daher alle bekannten Adressen nacheinander probieren und flexibel auslesen.
        val endpoints = listOf("pc/v6/jobs", "pc/v4/jobs", "pc/v4/app/jobs")
        val errors = mutableListOf<String>()
        var emptyAnswer = false
        for (ep in endpoints) {
            val raw = try {
                Http.get("$baBase/$ep$sb", baHeaders)
            } catch (e: Exception) {
                errors += "$ep: ${e.message?.take(80)}"
                continue
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val arr = findJobArray(json)
            if (arr == null || arr.length() == 0) {
                // Leere Antwort ist ein gültiges Ergebnis – aber nächste Version noch prüfen
                emptyAnswer = true
                continue
            }
            return parseBaJobs(arr)
        }
        if (emptyAnswer) return emptyList()
        throw java.io.IOException(errors.joinToString(" | ").ifBlank { "keine Antwort" })
    }

    private val titleKeys = listOf("titel", "stellenangebotsTitel", "stellentitel", "beruf")
    private val refKeys = listOf("refnr", "referenznummer", "hashId")

    /** Findet die Liste der Stellen im JSON – egal unter welchem Namen. */
    private fun findJobArray(any: Any?): JSONArray? {
        if (any is JSONObject) {
            any.optJSONArray("stellenangebote")?.let { return it }
            var best: JSONArray? = null
            for (k in any.keys()) {
                val found = findJobArray(any.opt(k))
                if (found != null && found.length() > (best?.length() ?: -1)) best = found
            }
            return best
        }
        if (any is JSONArray && any.length() > 0) {
            val first = any.optJSONObject(0)
            if (first != null && (titleKeys + refKeys).any { first.has(it) }) return any
        }
        return null
    }

    private fun JSONObject.str(keys: List<String>): String =
        keys.map { optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    /** Sucht rekursiv ein Objekt mit Koordinaten. */
    private fun findCoords(any: Any?): Pair<Double, Double>? {
        when (any) {
            is JSONObject -> {
                val lat = any.optDouble("lat", any.optDouble("breite"))
                val lon = any.optDouble("lon", any.optDouble("laenge"))
                if (!lat.isNaN() && !lon.isNaN()) return lat to lon
                for (k in any.keys()) findCoords(any.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until any.length()) findCoords(any.opt(i))?.let { return it }
        }
        return null
    }

    private fun parseBaJobs(arr: JSONArray): List<Job> {
        val jobs = mutableListOf<Job>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val refnr = o.str(refKeys)
            val ort = o.optJSONObject("arbeitsort")
                ?: o.optJSONArray("arbeitsorte")?.optJSONObject(0)
                ?: o.optJSONObject("arbeitsorteDto")
            val location = if (ort != null) {
                listOf(ort.optString("plz"), ort.optString("ort"))
                    .filter { it.isNotBlank() && it != "null" }.joinToString(" ")
                    .ifBlank { ort.optString("region") }
            } else ""
            val coords = findCoords(ort ?: o)
            val dist = ort?.optString("entfernung")?.replace(",", ".")?.toDoubleOrNull()
            val externalUrl = o.optString("externeUrl").takeIf { it.startsWith("http") } ?: ""
            val arbeitgeber = o.opt("arbeitgeber")
            val company = when (arbeitgeber) {
                is JSONObject -> arbeitgeber.str(listOf("name", "anzeigeName", "arbeitgeber"))
                is String -> arbeitgeber
                else -> ""
            }.ifBlank { o.str(listOf("arbeitgeberName", "firma")) }
            val title = o.str(titleKeys)
            if (title.isBlank() && refnr.isBlank()) continue
            jobs += Job(
                id = "ba-" + refnr.ifBlank { i.toString() },
                source = "Arbeitsagentur",
                title = title.ifBlank { "Stellenangebot" },
                company = company,
                location = location,
                lat = coords?.first,
                lon = coords?.second,
                distanceKm = dist,
                publishedMillis = parseDate(
                    o.str(
                        listOf(
                            "aktuelleVeroeffentlichungsdatum", "veroeffentlichungsdatum",
                            "veroeffentlichtAm", "modifikationsTimestamp", "eintrittsdatum"
                        )
                    )
                ),
                url = externalUrl.ifBlank {
                    if (refnr.isNotBlank()) "https://www.arbeitsagentur.de/jobsuche/jobdetail/$refnr"
                    else "https://www.arbeitsagentur.de/jobsuche/"
                },
                snippet = o.optString("beruf").takeIf { it != "null" } ?: "",
                baRefnr = refnr.ifBlank { null }
            )
        }
        return jobs
    }

    /** Lädt den vollständigen Anzeigentext einer BA-Stelle. */
    suspend fun loadBaDescription(refnr: String): String = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(refnr.toByteArray(), Base64.NO_WRAP)
        val path = Http.enc(encoded)
        val raw = runCatching { Http.get("$baBase/pc/v4/jobdetails/$path", baHeaders) }
            .getOrElse { Http.get("$baBase/pc/v3/jobdetails/$path", baHeaders) }
        val json = JSONObject(raw)
        val known = listOf("stellenangebotsBeschreibung", "stellenbeschreibung", "beschreibung")
        val direct = known.map { json.optString(it) }.firstOrNull { it.isNotBlank() }
        (direct ?: longestString(json)).stripHtml()
    }

    /** Sucht rekursiv den längsten Text im JSON – robust gegen Feldnamen-Änderungen. */
    private fun longestString(any: Any?): String {
        var best = ""
        when (any) {
            is JSONObject -> any.keys().forEach { k ->
                val s = longestString(any.opt(k)); if (s.length > best.length) best = s
            }
            is JSONArray -> for (i in 0 until any.length()) {
                val s = longestString(any.opt(i)); if (s.length > best.length) best = s
            }
            is String -> best = any
        }
        return best
    }

    // ---------------- Adzuna ----------------

    private fun searchAdzuna(query: String, placeText: String, radiusKm: Int, maxAgeDays: Int): List<Job> {
        val sb = StringBuilder("https://api.adzuna.com/v1/api/jobs/de/search/1")
        sb.append("?app_id=").append(Http.enc(settings.adzunaAppId))
        sb.append("&app_key=").append(Http.enc(settings.adzunaAppKey))
        sb.append("&results_per_page=50&content-type=application/json")
        if (query.isNotBlank()) sb.append("&what=").append(Http.enc(query))
        if (placeText.isNotBlank()) {
            sb.append("&where=").append(Http.enc(placeText))
            if (radiusKm > 0) sb.append("&distance=").append(radiusKm)
        }
        if (maxAgeDays > 0) sb.append("&max_days_old=").append(maxAgeDays)

        val json = JSONObject(Http.get(sb.toString()))
        val arr = json.optJSONArray("results") ?: return emptyList()
        val jobs = mutableListOf<Job>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            jobs += Job(
                id = "adz-" + o.optString("id"),
                source = "Adzuna",
                title = o.optString("title").stripHtml(),
                company = o.optJSONObject("company")?.optString("display_name") ?: "",
                location = o.optJSONObject("location")?.optString("display_name") ?: "",
                lat = o.optDouble("latitude").takeIf { !it.isNaN() },
                lon = o.optDouble("longitude").takeIf { !it.isNaN() },
                distanceKm = null,
                publishedMillis = parseDate(o.optString("created")),
                url = o.optString("redirect_url"),
                snippet = o.optString("description").stripHtml()
            )
        }
        return jobs
    }

    // ---------------- Jooble ----------------

    private fun searchJooble(query: String, placeText: String, radiusKm: Int): List<Job> {
        // Jooble kennt nur feste Radien
        val radius = listOf(0, 4, 8, 16, 26, 40, 80).minByOrNull { kotlin.math.abs(it - radiusKm) } ?: 26
        val body = JSONObject()
            .put("keywords", query)
            .put("location", placeText)
            .put("radius", radius.toString())
            .put("page", "1")
            .toString()
        val json = JSONObject(Http.post("https://de.jooble.org/api/${settings.joobleKey}", body, timeoutMs = 25_000))
        val arr = json.optJSONArray("jobs") ?: return emptyList()
        val jobs = mutableListOf<Job>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val src = o.optString("source")
            jobs += Job(
                id = "joo-" + o.optString("id", i.toString()),
                source = if (src.isNotBlank()) "Jooble · $src" else "Jooble",
                title = o.optString("title").stripHtml(),
                company = o.optString("company"),
                location = o.optString("location"),
                lat = null,
                lon = null,
                distanceKm = null,
                publishedMillis = parseDate(o.optString("updated")),
                url = o.optString("link"),
                snippet = o.optString("snippet").stripHtml()
            )
        }
        return jobs
    }

    /** Versucht, den Text einer beliebigen Anzeigen-Webseite zu laden. */
    suspend fun loadWebPageText(url: String): String = withContext(Dispatchers.IO) {
        val html = Http.get(url, mapOf("Accept" to "text/html"))
        val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        body.stripHtml().take(15_000)
    }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching {
            return LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return null
    }
}

/** Direkt-Links zu Portalen ohne freie Schnittstelle. */
object PortalLinks {
    fun all(query: String, place: String, radiusKm: Int): List<Pair<String, String>> {
        val q = Http.enc(query)
        val l = Http.enc(place)
        val miles = (radiusKm / 1.609).toInt().coerceAtLeast(5)
        val stepstoneQuery = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val stepstonePlace = place.trim().lowercase().replace(Regex("\\s+"), "-")
        return listOf(
            "Indeed" to "https://de.indeed.com/jobs?q=$q&l=$l&radius=$radiusKm&sort=date",
            "LinkedIn" to "https://www.linkedin.com/jobs/search/?keywords=$q&location=$l&distance=$miles&sortBy=DD",
            "XING" to "https://www.xing.com/jobs/search?keywords=$q&location=$l&radius=$radiusKm",
            "StepStone" to "https://www.stepstone.de/jobs/${Http.enc(stepstoneQuery)}/in-${Http.enc(stepstonePlace)}?radius=$radiusKm&sort=2",
            "Arbeitsagentur" to "https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=$q&wo=$l&umkreis=$radiusKm"
        )
    }
}
