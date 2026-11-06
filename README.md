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
- Suchwort/Beruf, Ort (mit Vorschlägen), Umkreis als Auswahl: Remote, 25 km, 50 km, 100 km
- Quellen: Jobbörse der Arbeitsagentur (Schnittstelle) sowie LinkedIn, XING, Indeed und StepStone
  (öffentliche Suchseiten werden im Hintergrund wie im Browser geladen und ausgelesen)
- Sortierung nach Entfernung oder Alter der Anzeige
- Falls ein Portal automatische Zugriffe blockiert: Suche direkt im Browser öffnen

**Unterlagen**
- Import von PDF, Bild, Word (.docx) und Text – Texterkennung direkt auf dem Handy
- Typen: Lebenslauf, Anschreiben, Zwischenzeugnis, Arbeitszeugnis, Weiterbildung, Sonstiges
- PDFs und Scans werden zusätzlich als Original gespeichert
- KI: Lebenslauf verbessern; Anschreiben passend zur Stelle (max. 1 DIN-A4-Seite) aus Lebenslauf,
  Zwischenzeugnissen, Zeugnissen und Weiterbildungen
- Ohne API-Schlüssel: „An Chat-App senden“ (Claude, ChatGPT, Gemini) und Antwort einfügen

**Direkt bewerben**
- Bewerbungsmappe als ein PDF (Anschreiben, Lebenslauf, Nachweise im Original)
- Per E-Mail an die Adresse aus der Anzeige oder speichern und auf der Bewerbungsseite hochladen

## KI einrichten
Einstellungen → Anbieter wählen → API-Schlüssel eintragen → Speichern.
- Gemini: Schlüssel bei aistudio.google.com (es gibt ein kostenloses Kontingent)
- ChatGPT: platform.openai.com (Abrechnung nach Verbrauch)
- Claude: console.anthropic.com (Abrechnung nach Verbrauch; ein Claude-Pro-Abo enthält keine API-Nutzung)

Der Modellname ist vorausgefüllt und kann geändert werden, falls ein Anbieter Modelle umbenennt.
