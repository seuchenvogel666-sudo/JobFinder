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

    fun searchPlaces(text: String): List<Place> {
        val q = text.trim()
        if (q.isBlank()) return emptyList()
        val isPostcode = q.first().isDigit()
        return if (isPostcode) {
            // Postleitzahl: ohne Ortstyp-Filter suchen und nach PLZ + Ort gruppieren
            query(q, "", postcodeMode = true)
        } else {
            query(q, "&osm_tag=place", postcodeMode = false).ifEmpty { query(q, "", postcodeMode = false) }
        }
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
                if (postcode.isBlank() || !postcode.startsWith(text.take(5))) continue
                result += Place(city, listOf(postcode, state).filter { it.isNotBlank() }.joinToString(", "),
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
