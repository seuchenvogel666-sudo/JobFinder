# JobFinder – Stellensuche & Bewerbungsunterlagen (Android)

## APK über GitHub bauen
1. Neues Repository auf github.com anlegen (z. B. `JobFinder`).
2. Den **Inhalt** dieses Ordners hochladen („Add file → Upload files“) – wichtig: auch den
   versteckten Ordner `.github` (enthält `workflows/build.yml`). Am einfachsten per
   GitHub Desktop oder `git push`; beim Hochladen im Browser den Ordner `.github` per Drag & Drop mitziehen.
3. Reiter **Actions** öffnen → der Build „Android APK bauen“ startet automatisch
   (oder „Run workflow“ klicken). Dauer ca. 5–8 Minuten.
4. Nach dem grünen Haken unten bei **Artifacts** `JobFinder-APK` herunterladen, ZIP entpacken,
   `JobFinder.apk` aufs Handy kopieren und installieren („Unbekannte Apps installieren“ erlauben).

## Funktionen
**Stellen**
- Suchwort/Beruf, Ort (mit Vorschlägen), Umkreis in km, Höchstalter der Anzeige
- Quellen: Jobbörse der Arbeitsagentur (immer aktiv), Adzuna und Jooble (kostenlose Schlüssel
  in den Einstellungen; bündeln Anzeigen vieler weiterer Jobbörsen)
- Sortierung nach Entfernung oder Alter der Anzeige
- Direkt-Links zu Indeed, LinkedIn, XING, StepStone mit den eingegebenen Suchwerten
  (diese Portale haben keine freie Schnittstelle)

**Unterlagen**
- Import von PDF, Bild, Word (.docx) und Text – Texterkennung direkt auf dem Handy
- Foto/Scan mit dem Google-Dokumentenscanner (mehrere Seiten, automatisches Zuschneiden)
- Bearbeiten, als PDF teilen/speichern
- KI: Lebenslauf verbessern; Anschreiben passend zu einer Stelle (max. 1 DIN-A4-Seite, mit Seitenprüfung)
- „Anschreiben für eigene Anzeige“: Anzeigentext z. B. aus LinkedIn einfügen

## KI einrichten
Einstellungen → Anbieter wählen → API-Schlüssel eintragen → Speichern.
- Gemini: Schlüssel bei aistudio.google.com (es gibt ein kostenloses Kontingent)
- ChatGPT: platform.openai.com (Abrechnung nach Verbrauch)
- Claude: console.anthropic.com (Abrechnung nach Verbrauch; ein Claude-Pro-Abo enthält keine API-Nutzung)

Der Modellname ist vorausgefüllt und kann geändert werden, falls ein Anbieter Modelle umbenennt.
