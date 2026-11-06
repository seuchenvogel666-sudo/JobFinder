package de.bewerbung.jobfinder.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 *  - Bundesagentur für Arbeit (öffentliche Schnittstelle)
 *  - LinkedIn, XING, Indeed, StepStone (öffentlich einsehbare Suchseiten, siehe [WebJobScraper])
 */
class JobRepository(context: Context) {

    val web = WebJobScraper(context)

    suspend fun search(
        query: String,
        placeText: String,
        place: Place?,
        radius: RadiusOption,
        maxAgeDays: Int,
        sites: Set<WebSite>
    ): SearchResult = coroutineScope {
        val ba = async(Dispatchers.IO) { "Arbeitsagentur" to runCatching { searchBa(query, placeText, radius, maxAgeDays) } }
        val webJobs = sites.map { site ->
            async { site.label to runCatching { web.search(site, query, placeText, radius) } }
        }

        val notes = mutableListOf<String>()
        val all = mutableListOf<Job>()
        (listOf(ba.await()) + webJobs.awaitAll()).forEach { (name, r) ->
            r.onSuccess { list ->
                all += list
                notes += if (list.isEmpty() && name != "Arbeitsagentur")
                    "$name: 0 Treffer (Seite evtl. blockiert – unten im Browser öffnen)"
                else "$name: ${list.size} Treffer"
            }.onFailure { notes += "$name: Fehler – ${it.message?.take(120)}" }
        }

        // Orte ohne Koordinaten (Webportale) nachschlagen, damit nach Entfernung sortiert werden kann
        val withCoords = if (place != null && !radius.remote) geocodeMissing(all) else all
        val withDistance = withCoords.map { job ->
            if (place != null && job.lat != null && job.lon != null) {
                job.copy(distanceKm = GeoRepository.distanceKm(place.lat, place.lon, job.lat, job.lon))
            } else job
        }

        // Filter: Alter der Anzeige. Den Umkreis filtern die Portale selbst.
        val now = System.currentTimeMillis()
        val filtered = withDistance.filter { job ->
            maxAgeDays <= 0 || job.publishedMillis == null ||
                    (now - job.publishedMillis) <= (maxAgeDays + 1) * 86_400_000L
        }

        // Dubletten entfernen (gleicher Titel + gleiche Firma) – die erste Quelle gewinnt
        val unique = filtered.distinctBy {
            (it.title.lowercase().replace(Regex("[^a-zäöüß0-9]"), "") + "|" +
                    it.company.lowercase().replace(Regex("[^a-zäöüß0-9]"), ""))
        }
        SearchResult(unique, notes)
    }

    /** Schlägt bis zu 25 unterschiedliche Ortsangaben nach (parallel, mit Zwischenspeicher). */
    private suspend fun geocodeMissing(jobs: List<Job>): List<Job> = coroutineScope {
        fun key(loc: String) = loc.substringBefore(",").substringBefore("(").replace(Regex("\\b\\d{5}\\b"), "").trim()
        val keys = jobs.filter { it.lat == null && it.location.isNotBlank() }
            .map { key(it.location) }.filter { it.length > 1 && !it.contains("remote", true) }
            .distinct().filter { it !in geoCache }.take(25)
        keys.map { k ->
            async(Dispatchers.IO) { k to runCatching { GeoRepository.searchPlaces(k).firstOrNull() }.getOrNull() }
        }.awaitAll().forEach { (k, p) -> geoCache[k] = p }
        jobs.map { j ->
            if (j.lat != null || j.location.isBlank()) j
            else geoCache[key(j.location)]?.let { p -> j.copy(lat = p.lat, lon = p.lon) } ?: j
        }
    }

    private val geoCache = java.util.Collections.synchronizedMap(HashMap<String, Place?>())

    // ---------------- Bundesagentur für Arbeit ----------------

    private val baBase = "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service"
    private val baHeaders = mapOf("X-API-Key" to "jobboerse-jobsuche")

    private fun searchBa(query: String, placeText: String, radius: RadiusOption, maxAgeDays: Int): List<Job> {
        val sb = StringBuilder("?angebotsart=1&page=1&size=100")
        if (query.isNotBlank()) sb.append("&was=").append(Http.enc(query))
        if (radius.remote) {
            // Homeoffice-Stellen bundesweit
            sb.append("&arbeitszeit=ho")
        } else if (placeText.isNotBlank()) {
            sb.append("&wo=").append(Http.enc(placeText))
            sb.append("&umkreis=").append(radius.km)
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
        val text = (direct ?: longestString(json)).stripHtml()
        // Kontakt-E-Mail(s) aus den Detaildaten anhängen, falls nicht schon im Text (für "Direkt bewerben")
        val mails = extractEmails(raw).filter { !text.contains(it, ignoreCase = true) }
        val bewerbungsUrl = listOf("externeUrl", "bewerbungsUrl", "allianzpartnerUrl")
            .map { json.optString(it) }.firstOrNull { it.startsWith("http") }
        buildString {
            append(text)
            if (mails.isNotEmpty()) append("\n\nKontakt: ").append(mails.joinToString(", "))
            if (bewerbungsUrl != null && !text.contains(bewerbungsUrl)) append("\nBewerbung: ").append(bewerbungsUrl)
        }
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

    /** Versucht, den Text einer beliebigen Anzeigen-Webseite zu laden. */
    suspend fun loadWebPageText(url: String): String {
        val viaBrowser = runCatching { web.pageText(url) }.getOrDefault("")
        if (viaBrowser.length > 300) return viaBrowser
        return withContext(Dispatchers.IO) {
            val html = Http.get(url, mapOf("Accept" to "text/html"))
            val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
            body.stripHtml().take(15_000)
        }
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

/** Direkt-Links zu den Portalen (zum Öffnen im Browser). */
object PortalLinks {
    fun all(scraper: WebJobScraper, query: String, place: String, radius: RadiusOption): List<Pair<String, String>> {
        val q = Http.enc(query)
        val l = Http.enc(place)
        val ba = if (radius.remote) "https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=$q&arbeitszeit=ho"
        else "https://www.arbeitsagentur.de/jobsuche/suche?angebotsart=1&was=$q&wo=$l&umkreis=${radius.km}"
        return WebSite.entries.map { it.label to scraper.searchUrl(it, query, place, radius) } + ("Arbeitsagentur" to ba)
    }
}

/** Findet E-Mail-Adressen in einem Text. */
fun extractEmails(text: String): List<String> =
    Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(text)
        .map { it.value.trimEnd('.') }
        .filter { !it.contains("example", true) && !it.endsWith(".png") && !it.endsWith(".jpg") }
        .distinct().toList()
