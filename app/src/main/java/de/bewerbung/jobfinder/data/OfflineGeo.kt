package de.bewerbung.jobfinder.data

import android.content.Context

/**
 * Offline-Ortsverzeichnis für Deutschland (mitgeliefert in assets/):
 *  - plz.tsv:  PLZ → Mittelpunkt (Koordinaten), Ortsname, Bundesland
 *  - orte.tsv: Ortsname → Mittelpunkt ("Stadtmitte"), Anzahl PLZ, eine PLZ, Bundesland
 *
 * Quellen: GeoNames (CC BY 4.0, www.geonames.org) und "plz_geocoord" des WZB (Apache 2.0).
 * Damit funktionieren PLZ-Suche und Entfernungsschätzung schnell und auch ohne Ortsdienst im Netz.
 */
object OfflineGeo {

    data class PlzEntry(val plz: String, val lat: Double, val lon: Double, val name: String, val state: String)
    data class Ort(val name: String, val lat: Double, val lon: Double, val count: Int, val plz: String, val state: String) {
        val key: String = norm(name)
        val base: String = norm(name.substringBefore(" (").substringBefore(","))
    }

    @Volatile private var plzList: List<PlzEntry> = emptyList()
    @Volatile private var plzMap: Map<String, PlzEntry> = emptyMap()
    @Volatile private var orte: List<Ort> = emptyList()
    @Volatile private var byBase: Map<String, List<Ort>> = emptyMap()

    val isLoaded: Boolean get() = plzList.isNotEmpty()

    @Synchronized
    fun load(context: Context) {
        if (isLoaded) return
        val plz = context.assets.open("plz.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 5) null
                else PlzEntry(p[0], p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null, p[3], p[4])
            }.toList()
        }
        val o = context.assets.open("orte.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 6) null
                else Ort(p[0], p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null,
                    p[3].toIntOrNull() ?: 1, p[4], p[5])
            }.toList()
        }
        orte = o
        byBase = o.groupBy { it.base }
        plzMap = plz.associateBy { it.plz }
        plzList = plz.sortedBy { it.plz }
    }

    fun norm(s: String): String = s.lowercase()
        .replace("ß", "ss")
        .replace(Regex("[^a-zäöü0-9() ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Koordinaten zu einer PLZ – bei unbekannter PLZ Schätzung über die ersten Ziffern. */
    fun plz(code: String): PlzEntry? {
        val c = code.filter { it.isDigit() }
        if (c.length < 2) return null
        plzMap[c]?.let { return it }
        for (len in minOf(c.length, 4) downTo 2) {
            val group = plzList.filter { it.plz.startsWith(c.take(len)) }
            if (group.isNotEmpty()) {
                return PlzEntry(
                    c, group.map { it.lat }.average(), group.map { it.lon }.average(),
                    group.groupingBy { it.name }.eachCount().maxByOrNull { it.value }?.key ?: "", group.first().state
                )
            }
        }
        return null
    }

    /** PLZ-Vorschläge für eine (Teil-)Eingabe wie "101" oder "10115". */
    fun suggestPlz(prefix: String, limit: Int = 12): List<PlzEntry> {
        val c = prefix.filter { it.isDigit() }
        if (c.isEmpty()) return emptyList()
        return plzList.asSequence().filter { it.plz.startsWith(c) && it.name.isNotBlank() }.take(limit).toList()
    }

    /** Orts-Vorschläge (größere Orte zuerst). */
    fun suggestOrte(prefix: String, limit: Int = 8): List<Ort> {
        val q = norm(prefix)
        if (q.length < 2) return emptyList()
        return orte.asSequence().filter { it.key.startsWith(q) }
            .sortedWith(compareBy<Ort> { it.key != q }.thenByDescending { it.count })
            .take(limit).toList()
    }

    /**
     * Findet einen Ort zum Namen ("Stadtmitte"). Gibt es mehrere gleichnamige Orte (z. B. Neustadt),
     * wird der Ort genommen, der dem Suchort [near] am nächsten liegt.
     */
    fun city(rawName: String, nearLat: Double? = null, nearLon: Double? = null): Ort? {
        val q = norm(rawName.substringBefore(","))
        if (q.length < 2) return null
        var candidates = orte.filter { it.key == q }
        if (candidates.isEmpty()) candidates = byBase[norm(q.substringBefore(" ("))].orEmpty()
        if (candidates.isEmpty()) candidates = orte.filter { it.key.startsWith("$q ") || it.base == q }
        if (candidates.isEmpty() && q.contains(" ")) {
            // "Berlin Mitte", "Hamburg Altona" → erstes Wort
            candidates = byBase[q.substringBefore(" ")].orEmpty()
        }
        if (candidates.isEmpty()) return null
        return if (nearLat != null && nearLon != null && !nearLat.isNaN()) {
            candidates.minByOrNull { GeoRepository.distanceKm(nearLat, nearLon, it.lat, it.lon) }
        } else {
            candidates.maxByOrNull { it.count }
        }
    }
}
