package de.bewerbung.jobfinder.data

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ortssuche über Photon (OpenStreetMap-Daten, kostenlos, ohne Schlüssel). */
object GeoRepository {

    fun searchPlaces(text: String): List<Place> {
        if (text.isBlank()) return emptyList()
        val places = query(text, "&osm_tag=place")
        // Postleitzahlen o. Ä. sind nicht immer als "place" erfasst – dann ohne Filter suchen
        return places.ifEmpty { query(text, "") }
    }

    private fun query(text: String, filter: String): List<Place> {
        val url = "https://photon.komoot.io/api/?q=${Http.enc(text)}" +
                "&limit=8&lang=de&lat=51.16&lon=10.45$filter"
        val json = JSONObject(Http.get(url))
        val features = json.optJSONArray("features") ?: return emptyList()
        val result = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val name = props.optString("name")
            if (name.isBlank()) continue
            val detail = listOf(
                props.optString("postcode"),
                props.optString("state"),
                props.optString("countrycode").takeIf { it != "DE" } ?: ""
            ).filter { it.isNotBlank() }.joinToString(", ")
            result += Place(name, detail, coords.getDouble(1), coords.getDouble(0))
        }
        return result.distinctBy { it.label }
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
