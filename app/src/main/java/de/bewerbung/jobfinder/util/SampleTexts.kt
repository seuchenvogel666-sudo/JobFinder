package de.bewerbung.jobfinder.util

import android.graphics.Bitmap
import android.graphics.Color

/** Beispieltexte für die Design-Vorschau, solange noch keine eigenen Unterlagen vorhanden sind. */
object SampleTexts {
    val CV = """
KURZPROFIL
Gewissenhafte Fachkraft mit Erfahrung in Labor und Qualitätssicherung. Sorgfältige Dokumentation nach GMP ist für mich selbstverständlich.

BERUFSERFAHRUNG
03/2021 – heute | Chemielaborant | Beispiel Pharma GmbH, Musterstadt
• Mikrobiologische Prüfungen und Keimzahlbestimmung
• Dokumentation nach GMP
09/2017 – 02/2021 | Laborhelfer | Muster Labor AG, Musterstadt
• Probenvorbereitung und Pflege der Laborgeräte

AUSBILDUNG
09/2014 – 07/2017 | Ausbildung zum Chemielaboranten | Berufsschule Musterstadt

KENNTNISSE
GMP-Dokumentation – sehr gut
MS Office – sehr gut
SAP QM – Grundkenntnisse

SPRACHEN
Deutsch – Muttersprache
Englisch – B2
""".trimIndent()

    val LETTER = """
Beispiel Pharma GmbH
Personalabteilung
Musterstraße 1
12345 Musterstadt

Musterstadt, 13.11.2026
Bewerbung als Mitarbeiter Qualitätssicherung

Sehr geehrte Damen und Herren,

dies ist eine Vorschau Ihres Anschreibens im gewählten Design. Hier steht später der Text, den die KI passend zur Stelle erstellt.

Der zweite Absatz zeigt, wie der Fließtext auf der Seite wirkt.

Mit freundlichen Grüßen

Max Mustermann
""".trimIndent()
}

/** Bearbeitung von Unterschriften. */
object SignatureTools {

    /** Macht helles Papier durchsichtig und färbt die Schrift dunkelblau (wie Kugelschreiber). */
    fun toInk(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // Helligkeitsschwelle aus dem Bild ableiten (Papier ist meist deutlich heller als Tinte)
        var sum = 0L
        for (c in px) sum += lum(c)
        val avg = (sum / px.size.coerceAtLeast(1)).toInt()
        val threshold = (avg * 0.72f).toInt().coerceIn(60, 200)
        for (i in px.indices) {
            val l = lum(px[i])
            px[i] = if (l < threshold) {
                val alpha = (255 * (threshold - l) / threshold.toFloat() * 1.8f).toInt().coerceIn(90, 255)
                Color.argb(alpha, 18, 38, 92)
            } else Color.TRANSPARENT
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return crop(out)
    }

    private fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Schneidet leere (durchsichtige) Ränder ab. */
    fun crop(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 20) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return src
        val pad = 8
        val l = (minX - pad).coerceAtLeast(0)
        val t = (minY - pad).coerceAtLeast(0)
        val r = (maxX + pad).coerceAtMost(w - 1)
        val b = (maxY + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }
}
