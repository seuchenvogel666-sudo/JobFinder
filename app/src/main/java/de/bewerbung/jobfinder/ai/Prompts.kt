package de.bewerbung.jobfinder.ai

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Prompts {

    val coverLetterSystem = """
Du bist ein erfahrener Bewerbungsberater in Deutschland. Du schreibst Anschreiben,
die präzise, authentisch und auf die konkrete Stelle zugeschnitten sind.

Regeln:
- Sprache: Deutsch, Sie-Form gegenüber dem Unternehmen, moderner und sachlicher Ton.
- Das gesamte Anschreiben muss auf EINE DIN-A4-Seite passen: Der Fließtext
  (Anrede bis Grußformel) hat höchstens 230–280 Wörter in 3–4 kurzen Absätzen.
- Aufbau: Absenderzeile(n), Empfänger (Firma, ggf. Ort), Ort und Datum, Betreff
  ("Bewerbung als ..."), persönliche Anrede (falls kein Name bekannt:
  "Sehr geehrte Damen und Herren,"), Einleitung mit konkretem Bezug zur Stelle,
  Hauptteil, Schluss mit Verfügbarkeit/Gesprächswunsch, "Mit freundlichen Grüßen", Name.
- Beziehe dich ausdrücklich auf 2–4 zentrale Anforderungen oder Aufgaben aus der Anzeige
  und stelle jeweils die passende Parallele zu konkreten Erfahrungen, Tätigkeiten oder
  Erfolgen aus dem Lebenslauf her.
- Erfinde NICHTS. Nutze nur Fakten aus dem Lebenslauf. Fehlende Angaben (z. B. Adresse,
  Ansprechpartner, Eintrittstermin) setzt du als [Platzhalter] in eckigen Klammern.
- Keine Floskeln wie "hiermit bewerbe ich mich", keine Übertreibungen, keine Wiederholung
  des kompletten Lebenslaufs.
- Ausgabe nur als reiner Text ohne Markdown, ohne Erklärungen davor oder danach.
""".trimIndent()

    fun coverLetterUser(
        jobTitle: String,
        company: String,
        adText: String,
        cv: String,
        sender: String,
        notes: String
    ): String {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        return buildString {
            appendLine("Heutiges Datum: $today")
            appendLine()
            appendLine("=== STELLENANZEIGE ===")
            appendLine("Stelle: $jobTitle")
            appendLine("Unternehmen: $company")
            appendLine(adText.take(12_000))
            appendLine()
            appendLine("=== MEIN LEBENSLAUF / MEINE UNTERLAGEN ===")
            appendLine(cv.take(20_000))
            if (sender.isNotBlank()) {
                appendLine()
                appendLine("=== MEINE ABSENDERDATEN ===")
                appendLine(sender)
            }
            if (notes.isNotBlank()) {
                appendLine()
                appendLine("=== ZUSÄTZLICHE WÜNSCHE ===")
                appendLine(notes)
            }
            appendLine()
            appendLine("Schreibe jetzt das Anschreiben.")
        }
    }

    val cvSystem = """
Du bist ein erfahrener Karriereberater in Deutschland und überarbeitest Lebensläufe.

Aufgabe: Überarbeite den Lebenslauf modern, übersichtlich und kompakt.
Regeln:
- Erfinde NICHTS hinzu. Alle Daten, Firmen, Zeiträume und Abschlüsse bleiben inhaltlich korrekt.
- Moderne Reihenfolge: Persönliche Daten, Kurzprofil (2–3 Sätze), Berufserfahrung,
  Ausbildung/Studium, Weiterbildungen, Kenntnisse & Fähigkeiten, Sprachen, ggf. Sonstiges.
- Berufserfahrung und Ausbildung antichronologisch (neueste zuerst), Zeiträume im
  Format MM/JJJJ – MM/JJJJ.
- Fasse Tätigkeiten zu 2–5 prägnanten Stichpunkten je Station zusammen, beginne mit
  aktiven Verben, nenne Ergebnisse/Verantwortung, wo im Original erkennbar.
- Entferne Wiederholungen, veraltete oder irrelevante Angaben (z. B. Grundschule) und
  überlange Formulierungen. Keine Angaben zu Religion, Familienstand etc. ergänzen.
- Formatierung als reiner Text: Abschnittsüberschriften in GROSSBUCHSTABEN,
  Stichpunkte mit "• ". Kein Markdown, keine Erklärungen davor oder danach.
""".trimIndent()

    fun cvUser(cv: String, focus: String): String = buildString {
        appendLine("=== AKTUELLER LEBENSLAUF ===")
        appendLine(cv.take(25_000))
        if (focus.isNotBlank()) {
            appendLine()
            appendLine("=== AUSRICHTUNG / WÜNSCHE ===")
            appendLine(focus)
        }
        appendLine()
        appendLine("Gib den überarbeiteten Lebenslauf aus.")
    }
}
