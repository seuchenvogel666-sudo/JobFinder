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

        // Filter: Umkreis und Alter
        val now = System.currentTimeMillis()
        val filtered = withDistance.filter { job ->
            val okDist = job.distanceKm == null || radiusKm <= 0 || job.distanceKm <= radiusKm + 5
            val okAge = maxAgeDays <= 0 || job.publishedMillis == null ||
                    (now - job.publishedMillis) <= (maxAgeDays + 1) * 86_400_000L
            okDist && okAge
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
        val sb = StringBuilder("?angebotsart=1&page=1&size=100&pav=false")
        if (query.isNotBlank()) sb.append("&was=").append(Http.enc(query))
        if (placeText.isNotBlank()) {
            sb.append("&wo=").append(Http.enc(placeText))
            sb.append("&umkreis=").append(radiusKm.coerceIn(0, 200))
        }
        if (maxAgeDays > 0) sb.append("&veroeffentlichtseit=").append(maxAgeDays.coerceAtMost(100))

        // Die BA stellt ihre Versionen gelegentlich um – daher mit Ausweichadresse
        val raw = runCatching { Http.get("$baBase/pc/v4/jobs$sb", baHeaders) }
            .getOrElse { Http.get("$baBase/pc/v6/jobs$sb", baHeaders) }
        val json = JSONObject(raw)
        val arr = json.optJSONArray("stellenangebote") ?: return emptyList()
        val jobs = mutableListOf<Job>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val refnr = o.optString("refnr")
            val ort = o.optJSONObject("arbeitsort")
            val coords = ort?.optJSONObject("koordinaten")
            val location = listOf(ort?.optString("plz") ?: "", ort?.optString("ort") ?: "")
                .filter { it.isNotBlank() }.joinToString(" ")
            val dist = ort?.optString("entfernung")?.replace(",", ".")?.toDoubleOrNull()
            val externalUrl = o.optString("externeUrl")
            jobs += Job(
                id = "ba-$refnr",
                source = "Arbeitsagentur",
                title = o.optString("titel").ifBlank { o.optString("beruf") },
                company = o.optString("arbeitgeber"),
                location = location,
                lat = coords?.optDouble("lat")?.takeIf { !it.isNaN() },
                lon = coords?.optDouble("lon")?.takeIf { !it.isNaN() },
                distanceKm = dist,
                publishedMillis = parseDate(
                    o.optString("aktuelleVeroeffentlichungsdatum")
                        .ifBlank { o.optString("modifikationsTimestamp") }
                ),
                url = externalUrl.ifBlank { "https://www.arbeitsagentur.de/jobsuche/jobdetail/$refnr" },
                snippet = o.optString("beruf"),
                baRefnr = refnr
            )
        }
        return jobs
    }

    /** Lädt den vollständigen Anzeigentext einer BA-Stelle. */
    suspend fun loadBaDescription(refnr: String): String = withContext(Dispatchers.IO) {
        val encoded = Base64.encodeToString(refnr.toByteArray(), Base64.NO_WRAP)
        val json = JSONObject(Http.get("$baBase/pc/v4/jobdetails/${Http.enc(encoded)}", baHeaders))
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
