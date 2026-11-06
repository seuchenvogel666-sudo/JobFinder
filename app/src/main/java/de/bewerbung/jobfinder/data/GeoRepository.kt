package de.bewerbung.jobfinder.data

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ortssuche über Photon (OpenStreetMap-Daten, kostenlos, ohne Schlüssel).
 * Es werden nur Orte in Deutschland geliefert.
 */
object GeoRepository {

    /** Rechteck um Deutschland (West, Süd, Ost, Nord). */
    private const val DE_BBOX = "5.86,47.27,15.05,55.06"

    /** Wird beim App-Start gesetzt, damit das Offline-Ortsverzeichnis geladen werden kann. */
    @Volatile var appContext: android.content.Context? = null

    private fun offline(): Boolean {
        if (!OfflineGeo.isLoaded) appContext?.let { runCatching { OfflineGeo.load(it) } }
        return OfflineGeo.isLoaded
    }

    fun searchPlaces(text: String): List<Place> {
        val q = text.trim()
        if (q.isBlank()) return emptyList()
        if (q.first().isDigit()) {
            // Postleitzahl: Offline-Verzeichnis (schnell, auch Teil-Eingaben wie "101")
            if (offline()) {
                val list = OfflineGeo.suggestPlz(q).map { Place("${it.plz} ${it.name}", it.state, it.lat, it.lon) }
                if (list.isNotEmpty()) return list
            }
            return runCatching { searchPostcodes(q) }.getOrDefault(emptyList())
                .ifEmpty { query(q, "", postcodeMode = true) }
        }
        if (offline()) {
            val list = OfflineGeo.suggestOrte(q).map {
                val detail = (if (it.count > 1) "PLZ ${it.plz} u. a." else "PLZ ${it.plz}") + ", " + it.state
                Place(it.name, detail, it.lat, it.lon)
            }
            if (list.isNotEmpty()) return list
        }
        return query(q, "&osm_tag=place", postcodeMode = false).ifEmpty { query(q, "", postcodeMode = false) }
    }

    /** Sucht PLZ über openplzapi.org (nur Deutschland). Koordinaten werden erst bei Auswahl ermittelt. */
    private fun searchPostcodes(prefix: String): List<Place> {
        val digits = prefix.filter { it.isDigit() }.take(5)
        if (digits.isEmpty()) return emptyList()
        val url = "https://openplzapi.org/de/Localities?postalCode=${Http.enc("^$digits")}&page=1&pageSize=30"
        val arr = org.json.JSONArray(Http.get(url))
        val result = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val plz = o.optString("postalCode")
            val ort = o.optString("name")
            if (plz.isBlank() || ort.isBlank()) continue
            val district = o.optJSONObject("district")?.optString("name") ?: ""
            val state = o.optJSONObject("federalState")?.optString("name") ?: ""
            val detail = listOf(district.takeIf { it != ort } ?: "", state).filter { it.isNotBlank() }.joinToString(", ")
            result += Place("$plz $ort", detail, Double.NaN, Double.NaN)
        }
        return result.distinctBy { it.name }.sortedBy { it.name }.take(12)
    }

    /** Ermittelt die Koordinaten für einen PLZ- oder Ortseintrag (nötig für die Entfernungsberechnung). */
    fun resolve(place: Place): Place {
        if (!place.lat.isNaN()) return place
        val plz = Regex("\\b\\d{5}\\b").find(place.name)?.value
        val ort = place.name.replace(Regex("\\b\\d{5}\\b"), "").trim()
        val hit = locate(plz, ort.ifBlank { null })
        return if (hit != null) place.copy(lat = hit.lat, lon = hit.lon) else place
    }

    private fun query(text: String, filter: String, postcodeMode: Boolean): List<Place> {
        val url = "https://photon.komoot.io/api/?q=${Http.enc(text)}" +
                "&limit=15&lang=de&bbox=$DE_BBOX$filter"
        val json = JSONObject(Http.get(url))
        val features = json.optJSONArray("features") ?: return emptyList()
        val result = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: continue
            // Nur Deutschland
            if (props.optString("countrycode").uppercase() != "DE") continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val postcode = props.optString("postcode")
            val city = props.optString("city").ifBlank { props.optString("name") }
            val state = props.optString("state")
            if (postcodeMode) {
                val digits = text.filter { it.isDigit() }.take(5)
                if (postcode.isBlank() || !postcode.startsWith(digits)) continue
                result += Place("$postcode $city", listOf(state).filter { it.isNotBlank() }.joinToString(", "),
                    coords.getDouble(1), coords.getDouble(0))
            } else {
                val name = props.optString("name")
                if (name.isBlank()) continue
                val district = props.optString("county").takeIf { it.isNotBlank() && it != name } ?: ""
                val detail = listOf(postcode, district, state).filter { it.isNotBlank() }.distinct().joinToString(", ")
                result += Place(name, detail, coords.getDouble(1), coords.getDouble(0))
            }
        }
        return result.distinctBy { it.label }.take(8)
    }

    /** Entfernt Zusätze wie "Metropolregion", "und Umgebung", "(Hybrid)", Land usw. */
    fun cleanLocality(raw: String): String = raw
        .replace(Regex("\\(.*?\\)"), " ")
        .replace(Regex("(?iu)\\b(metropolregion|großraum|grossraum|region|und umgebung|umgebung|area|landkreis|kreis|stadtgebiet)\\b"), " ")
        .replace(Regex("(?iu)\\b(homeoffice|home office|remote|hybrid|vor ort|deutschland|germany|bundesweit)\\b"), " ")
        .replace(Regex("\\b\\d{5}\\b"), " ")
        .split(",", "/", "|", "•", "·", " - ").map { it.trim() }.firstOrNull { it.length > 1 } ?: ""

    /** Findet einen Ort ("Stadtmitte") – zuerst offline, dann über den Ortsdienst. */
    fun findCity(raw: String, near: Place? = null): Place? {
        val c = cleanLocality(raw).replace(Regex("\\s+"), " ").trim()
        if (c.length < 2) return null
        if (offline()) {
            OfflineGeo.city(c, near?.lat, near?.lon)?.let { return Place(it.name, it.state, it.lat, it.lon) }
        }
        return runCatching { query(c, "&osm_tag=place", postcodeMode = false).firstOrNull() }.getOrNull()
    }

    /**
     * Ort bestimmen: über die PLZ (genau), sonst über den Ortsnamen (Stadtmitte).
     * Bei gleichnamigen Orten gewinnt der, der [near] (dem Suchort) am nächsten liegt.
     */
    fun locate(postcode: String?, locality: String?, near: Place? = null): Place? {
        val plz = postcode?.filter { it.isDigit() }?.takeIf { it.length == 5 }
        if (plz != null && offline()) {
            OfflineGeo.plz(plz)?.let { return Place("${it.plz} ${it.name}", it.state, it.lat, it.lon) }
        }
        locality?.let { l -> findCity(l, near)?.let { return it } }
        if (plz != null) {
            return runCatching { query(plz, "", postcodeMode = true).firstOrNull() }.getOrNull()
        }
        return null
    }

    /** Luftlinie in km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
