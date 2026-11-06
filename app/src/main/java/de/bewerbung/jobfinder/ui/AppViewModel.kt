package de.bewerbung.jobfinder.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.bewerbung.jobfinder.ai.AiClient
import de.bewerbung.jobfinder.ai.Prompts
import de.bewerbung.jobfinder.data.AppDocument
import de.bewerbung.jobfinder.data.DocType
import de.bewerbung.jobfinder.data.DocumentStore
import de.bewerbung.jobfinder.data.GeoRepository
import de.bewerbung.jobfinder.data.Job
import de.bewerbung.jobfinder.data.JobRepository
import de.bewerbung.jobfinder.data.LocationEnricher
import de.bewerbung.jobfinder.data.OfflineGeo
import de.bewerbung.jobfinder.data.Place
import de.bewerbung.jobfinder.data.RadiusOption
import de.bewerbung.jobfinder.data.SettingsStore
import de.bewerbung.jobfinder.data.SortMode
import de.bewerbung.jobfinder.data.TextExtractor
import de.bewerbung.jobfinder.data.WebSite
import de.bewerbung.jobfinder.data.extractEmails
import de.bewerbung.jobfinder.util.PdfExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen {
    data object Tabs : Screen()
    data class JobDetail(val job: Job) : Screen()
    data class DocEdit(val docId: String) : Screen()
    data object CoverLetter : Screen()
    data object Apply : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    private val store = DocumentStore(app)
    val jobsRepo = JobRepository(app)
    private val extractor = TextExtractor(app)
    val ai = AiClient(settings)

    init {
        // Offline-Ortsverzeichnis (PLZ/Orte) im Hintergrund laden
        GeoRepository.appContext = app.applicationContext
        viewModelScope.launch(Dispatchers.IO) { runCatching { OfflineGeo.load(app) } }
    }

    // ---------- Navigation ----------
    var screen by mutableStateOf<Screen>(Screen.Tabs)
    var tab by mutableIntStateOf(0)

    /** Für "Zurück" aus dem Bewerben-Bildschirm. */
    private var screenBeforeApply: Screen = Screen.Tabs

    fun back(): Boolean {
        if (screen == Screen.Tabs) return false
        screen = if (screen == Screen.Apply) screenBeforeApply else Screen.Tabs
        return true
    }

    // ---------- Stellensuche ----------
    var query by mutableStateOf(settings.lastQuery)
    var radius by mutableStateOf(settings.lastRadius)
    var placeText by mutableStateOf(settings.lastPlace)
    var selectedPlace by mutableStateOf<Place?>(null)
    var placeSuggestions by mutableStateOf<List<Place>>(emptyList())
    var placeMenuOpen by mutableStateOf(false)
    var placeLoading by mutableStateOf(false)
    var sortMode by mutableStateOf(SortMode.DISTANCE)
    var maxAgeDays by mutableIntStateOf(0)
    var webSources by mutableStateOf(settings.webSources)
    var jobs by mutableStateOf<List<Job>>(emptyList())
    var searchNotes by mutableStateOf<List<String>>(emptyList())
    var searching by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)

    private var placeJob: CoJob? = null

    fun toggleWebSource(site: WebSite) {
        webSources = if (site in webSources) webSources - site else webSources + site
        settings.webSources = webSources
    }

    fun onPlaceTextChange(text: String) {
        placeText = text
        selectedPlace = null
        placeJob?.cancel()
        if (text.trim().length < 2) {
            placeSuggestions = emptyList(); placeMenuOpen = false; placeLoading = false; return
        }
        placeJob = viewModelScope.launch {
            delay(300)
            placeLoading = true
            val list = withContext(Dispatchers.IO) {
                runCatching { GeoRepository.searchPlaces(text.trim()) }.getOrDefault(emptyList())
            }
            placeLoading = false
            placeSuggestions = list
            placeMenuOpen = true
        }
    }

    fun selectPlace(p: Place) {
        placeJob?.cancel()
        placeLoading = false
        selectedPlace = p
        placeText = p.name
        placeSuggestions = emptyList()
        placeMenuOpen = false
        // PLZ-Einträge haben noch keine Koordinaten – im Hintergrund nachschlagen
        if (p.lat.isNaN()) {
            viewModelScope.launch {
                val resolved = withContext(Dispatchers.IO) { GeoRepository.resolve(p) }
                if (selectedPlace == p) selectedPlace = resolved
            }
        }
    }

    fun sortedJobs(): List<Job> = when (sortMode) {
        SortMode.DISTANCE -> jobs.sortedWith(
            compareBy<Job> { it.distanceKm == null }.thenBy { it.distanceKm ?: 0.0 }
                .thenByDescending { it.publishedMillis ?: 0L }
        )
        SortMode.DATE -> jobs.sortedWith(
            compareBy<Job> { it.publishedMillis == null }.thenByDescending { it.publishedMillis ?: 0L }
                .thenBy { it.distanceKm ?: Double.MAX_VALUE }
        )
    }

    fun search() {
        if (query.isBlank() && placeText.isBlank()) {
            toast = "Bitte einen Beruf/Suchbegriff oder einen Ort eingeben."
            return
        }
        placeSuggestions = emptyList()
        placeMenuOpen = false
        settings.lastQuery = query
        settings.lastRadius = radius
        settings.lastPlace = placeText
        searching = true
        hasSearched = true
        enrichJob?.cancel()
        enrichingIds = emptySet()
        viewModelScope.launch {
            var place = selectedPlace
            if (place != null && place.lat.isNaN()) {
                val p0 = place
                place = withContext(Dispatchers.IO) { GeoRepository.resolve(p0) }
                selectedPlace = place
            }
            if (place == null && placeText.isNotBlank()) {
                place = withContext(Dispatchers.IO) {
                    runCatching { GeoRepository.searchPlaces(placeText.trim()).firstOrNull() }.getOrNull()
                }
                selectedPlace = place
            }
            val r = runCatching {
                jobsRepo.search(
                    query = query.trim(),
                    placeText = place?.name ?: placeText.trim(),
                    place = place,
                    radius = radius,
                    maxAgeDays = maxAgeDays,
                    sites = webSources
                )
            }
            r.onSuccess {
                jobs = it.jobs
                searchNotes = it.notes
                enrichLocations(place)
            }.onFailure {
                jobs = emptyList()
                searchNotes = listOf("Fehler: ${it.message}")
            }
            searching = false
        }
    }

    // ---------- Arbeitsort aus der Anzeige ermitteln (für Entfernung) ----------
    private val enricher by lazy { LocationEnricher(jobsRepo.web) }
    var enrichingIds by mutableStateOf<Set<String>>(emptySet())
    private var enrichJob: CoJob? = null

    /**
     * Für Stellen ohne Entfernung (v. a. LinkedIn/XING) im Hintergrund die Anzeige lesen,
     * den Arbeitsort bestimmen und die Entfernung zum Suchort berechnen.
     */
    private fun enrichLocations(ref: Place?) {
        enrichJob?.cancel()
        if (radius.remote && jobs.none { it.publishedMillis == null }) { enrichingIds = emptySet(); return }
        val targets = jobs.filter { it.baRefnr == null && (it.distanceKm == null || it.publishedMillis == null) }.take(40)
        enrichingIds = targets.map { it.id }.toSet()
        enrichJob = viewModelScope.launch {
            val sem = Semaphore(3)
            targets.map { job ->
                launch {
                    sem.withPermit {
                        val info = runCatching { enricher.enrich(job, ref?.takeIf { !it.lat.isNaN() }) }.getOrNull()
                        if (info != null) {
                            if (info.description.length > 300 && !descriptions.containsKey(job.id)) {
                                descriptions[job.id] = info.description.take(15_000)
                            }
                            val lat = info.lat
                            val lon = info.lon
                            jobs = jobs.map { j ->
                                if (j.id != job.id) j else {
                                    val dist = if (ref != null && !ref.lat.isNaN() && lat != null && lon != null)
                                        GeoRepository.distanceKm(ref.lat, ref.lon, lat, lon) else j.distanceKm
                                    j.copy(
                                        location = info.locality.ifBlank { j.location },
                                        lat = lat ?: j.lat, lon = lon ?: j.lon,
                                        distanceKm = j.distanceKm ?: dist,
                                        publishedMillis = j.publishedMillis ?: info.publishedMillis
                                    )
                                }
                            }
                        }
                        enrichingIds = enrichingIds - job.id
                    }
                }
            }.joinAll()
            enrichingIds = emptySet()
        }
    }

    // Anzeigentexte (Detailansicht)
    val descriptions = mutableStateMapOf<String, String>()
    val descriptionLoading = mutableStateMapOf<String, Boolean>()

    fun loadDescription(job: Job, force: Boolean = false) {
        if (descriptionLoading[job.id] == true) return
        if (!force && descriptions.containsKey(job.id)) return
        descriptionLoading[job.id] = true
        viewModelScope.launch {
            val text = runCatching {
                if (job.baRefnr != null) jobsRepo.loadBaDescription(job.baRefnr)
                else jobsRepo.loadWebPageText(job.url)
            }.getOrElse { "Anzeigentext konnte nicht geladen werden (${it.message}).\n\n${job.snippet}" }
            descriptions[job.id] = text.ifBlank { job.snippet }
            descriptionLoading[job.id] = false
        }
    }

    // ---------- Unterlagen ----------
    var documents by mutableStateOf(store.load())
    var busyMessage by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    fun doc(id: String): AppDocument? = documents.firstOrNull { it.id == id }

    fun saveDoc(doc: AppDocument) {
        documents = store.upsert(doc)
    }

    fun deleteDoc(id: String) {
        documents = store.delete(id)
        screen = Screen.Tabs
    }

    fun newEmptyDoc() {
        val d = AppDocument(DocumentStore.newId(), "Neues Dokument", DocType.LEBENSLAUF, "", System.currentTimeMillis())
        saveDoc(d)
        screen = Screen.DocEdit(d.id)
    }

    /**
     * Datei (PDF, Bild, Word, Text) importieren. PDFs und Bilder werden zusätzlich als Original
     * gespeichert (für die Bewerbungsmappe). Mit [forCoverLetter] wird das Dokument direkt fürs
     * Anschreiben ausgewählt, statt den Editor zu öffnen.
     */
    fun importFile(uri: Uri, forCoverLetter: Boolean = false) {
        busyMessage = "Dokument wird gelesen …"
        viewModelScope.launch {
            val r = runCatching {
                val name = extractor.displayName(uri)
                val text = extractor.extract(uri)
                val id = DocumentStore.newId()
                val original = withContext(Dispatchers.IO) { copyOriginal(uri, name, id) }
                Triple(name.substringBeforeLast('.'), text, original) to id
            }
            busyMessage = null
            r.onSuccess { (t, id) -> createFromText(t.first, t.second, t.third, id, forCoverLetter) }
                .onFailure { toast = "Import fehlgeschlagen: ${it.message}" }
        }
    }

    private fun copyOriginal(uri: Uri, name: String, id: String): String? {
        val lower = name.lowercase()
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: ""
        val ext = when {
            mime == "application/pdf" || lower.endsWith(".pdf") -> "pdf"
            mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") -> null
            else -> return null
        }
        val out = File(store.originalsDir, "$id.pdf")
        if (ext == "pdf") {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
        } else {
            PdfExport.imagesToPdf(getApplication<Application>(), listOf(uri), out)
        }
        return out.absolutePath
    }

    /** Fotos/Scans (eine oder mehrere Seiten) per Texterkennung einlesen. */
    fun importImages(uris: List<Uri>, forCoverLetter: Boolean = false) {
        if (uris.isEmpty()) return
        busyMessage = "Texterkennung läuft …"
        viewModelScope.launch {
            val id = DocumentStore.newId()
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val sb = StringBuilder()
                    for (u in uris) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(extractor.ocrUri(u))
                    }
                    val out = File(store.originalsDir, "$id.pdf")
                    val original = runCatching { PdfExport.imagesToPdf(getApplication<Application>(), uris, out); out.absolutePath }
                        .getOrNull()
                    sb.toString() to original
                }
            }
            busyMessage = null
            r.onSuccess { (text, original) -> createFromText("Scan", text, original, id, forCoverLetter) }
                .onFailure { toast = "Texterkennung fehlgeschlagen: ${it.message}" }
        }
    }

    private fun createFromText(name: String, text: String, original: String?, id: String, forCoverLetter: Boolean) {
        if (text.isBlank() && original == null) {
            toast = "Im Dokument wurde kein Text erkannt."
            return
        }
        val type = DocumentStore.guessType(text)
        val title = if (name == "Scan" || name.isBlank()) type.label else name
        val d = AppDocument(id, title, type, text, System.currentTimeMillis(), original)
        saveDoc(d)
        if (forCoverLetter) {
            if (type == DocType.LEBENSLAUF && clCvId == null) clCvId = id else clExtraDocIds = clExtraDocIds + id
            toast = "„$title“ (${type.label}) hinzugefügt und ausgewählt."
        } else {
            screen = Screen.DocEdit(d.id)
            toast = "Dokument erstellt – bitte Text und Typ kurz prüfen."
        }
    }

    // ---------- KI: Lebenslauf verbessern ----------
    var cvResult by mutableStateOf<String?>(null)
    var cvBusy by mutableStateOf(false)
    var aiError by mutableStateOf<String?>(null)

    fun improveCv(text: String, focus: String) {
        cvBusy = true
        cvResult = null
        aiError = null
        viewModelScope.launch {
            runCatching { ai.complete(Prompts.cvSystem, Prompts.cvUser(text, focus)) }
                .onSuccess { cvResult = it }
                .onFailure { aiError = it.message }
            cvBusy = false
        }
    }

    // ---------- KI: Anschreiben ----------
    var clJob by mutableStateOf<Job?>(null)
    var clTitle by mutableStateOf("")
    var clCompany by mutableStateOf("")
    var clAdText by mutableStateOf("")
    var clCvId by mutableStateOf<String?>(null)
    var clExtraDocIds by mutableStateOf<Set<String>>(emptySet())
    var clNotes by mutableStateOf("")
    var clResult by mutableStateOf("")
    var clBusy by mutableStateOf(false)
    var clLoadingAd by mutableStateOf(false)

    fun openCoverLetter(job: Job?) {
        clJob = job
        clTitle = job?.title ?: ""
        clCompany = job?.company ?: ""
        clAdText = job?.let { descriptions[it.id] ?: it.snippet } ?: ""
        clCvId = documents.firstOrNull { it.type == DocType.LEBENSLAUF }?.id
        // Zwischenzeugnisse, Zeugnisse und Weiterbildungen standardmäßig mit einbeziehen
        clExtraDocIds = documents.filter { it.type in DocType.supporting }.map { it.id }.toSet()
        clNotes = ""
        clResult = ""
        aiError = null
        screen = Screen.CoverLetter
        if (job != null && !descriptions.containsKey(job.id)) {
            loadDescription(job)
            viewModelScope.launch {
                repeat(80) {
                    delay(250)
                    val d = descriptions[job.id]
                    if (d != null) {
                        if (clJob?.id == job.id && (clAdText.isBlank() || clAdText == job.snippet)) clAdText = d
                        return@launch
                    }
                }
            }
        }
    }

    /** Anzeigentext von der Originalseite holen. */
    fun loadAdFromWeb() {
        val job = clJob ?: return
        if (job.url.isBlank()) return
        clLoadingAd = true
        viewModelScope.launch {
            runCatching {
                if (job.baRefnr != null) jobsRepo.loadBaDescription(job.baRefnr)
                else jobsRepo.loadWebPageText(job.url)
            }.onSuccess { if (it.isNotBlank()) { clAdText = it; descriptions[job.id] = it } }
                .onFailure { toast = "Seite konnte nicht geladen werden – Anzeigentext bitte manuell einfügen." }
            clLoadingAd = false
        }
    }

    /** Alle ausgewählten Unterlagen in sinnvoller Reihenfolge. */
    private fun selectedSourceDocs(): List<AppDocument> {
        val ids = listOfNotNull(clCvId) + clExtraDocIds.filter { it != clCvId }
        return ids.mapNotNull { doc(it) }
            .sortedBy { listOf(DocType.LEBENSLAUF, DocType.ZWISCHENZEUGNIS, DocType.ZEUGNIS, DocType.WEITERBILDUNG, DocType.SONSTIGES, DocType.ANSCHREIBEN).indexOf(it.type) }
    }

    /** Baut die Anfrage (System + Nutzer) – oder null mit Fehlermeldung in aiError. */
    private fun coverLetterPromptParts(): Pair<String, String>? {
        val parts = selectedSourceDocs().filter { it.text.isNotBlank() }
        if (parts.isEmpty()) {
            aiError = "Bitte zuerst einen Lebenslauf auswählen oder als Datei hinzufügen."
            return null
        }
        if (clAdText.isBlank() && clTitle.isBlank()) {
            aiError = "Bitte Stellentitel und Anzeigentext angeben."
            return null
        }
        aiError = null
        val cvText = parts.joinToString("\n\n") { "--- ${it.type.label}: ${it.title} ---\n${it.text.take(12_000)}" }
        return Prompts.coverLetterSystem to
                Prompts.coverLetterUser(clTitle, clCompany, clAdText, cvText, settings.senderInfo, clNotes)
    }

    /** Komplette Anfrage als ein Text zum Einfügen in eine Chat-App (Claude, ChatGPT, Gemini). */
    fun coverLetterChatPrompt(): String? =
        coverLetterPromptParts()?.let { (sys, user) -> Prompts.forChat(sys, user) }

    fun generateCoverLetter() {
        val (sys, user) = coverLetterPromptParts() ?: return
        clBusy = true
        viewModelScope.launch {
            runCatching { ai.complete(sys, user) }
                .onSuccess { clResult = it }
                .onFailure { aiError = it.message }
            clBusy = false
        }
    }

    fun saveCoverLetterAsDoc(): AppDocument? {
        if (clResult.isBlank()) return null
        val title = "Anschreiben – ${clCompany.ifBlank { clTitle }}".take(80)
        val d = AppDocument(DocumentStore.newId(), title, DocType.ANSCHREIBEN, clResult, System.currentTimeMillis())
        saveDoc(d)
        toast = "Anschreiben unter \"Unterlagen\" gespeichert."
        return d
    }

    // ---------- Direkt bewerben ----------
    var applyJob by mutableStateOf<Job?>(null)
    var applyTitle by mutableStateOf("")
    var applyCompany by mutableStateOf("")
    var applyEmail by mutableStateOf("")
    var applyEmails by mutableStateOf<List<String>>(emptyList())
    var applyUrl by mutableStateOf("")
    var applyLetterText by mutableStateOf("")
    var applyDocIds by mutableStateOf<List<String>>(emptyList())

    fun openApply(job: Job?) {
        screenBeforeApply = screen
        val j = job ?: clJob
        applyJob = j
        applyTitle = j?.title ?: clTitle
        applyCompany = j?.company ?: clCompany
        val adText = listOfNotNull(j?.let { descriptions[it.id] }, clAdText.takeIf { j == null || clJob?.id == j.id })
            .joinToString("\n")
        applyEmails = extractEmails(adText)
        applyEmail = applyEmails.firstOrNull() ?: ""
        applyUrl = Regex("Bewerbung: (https?://\\S+)").find(adText)?.groupValues?.get(1) ?: j?.url ?: ""
        applyLetterText = if (clResult.isNotBlank() && (j == null || clJob?.id == j.id)) clResult else ""
        // Standardauswahl: Lebenslauf + Zwischenzeugnisse/Zeugnisse/Weiterbildungen
        val cv = clCvId?.let { doc(it) } ?: documents.firstOrNull { it.type == DocType.LEBENSLAUF }
        val supporting = documents.filter { it.type in DocType.supporting }
        applyDocIds = (listOfNotNull(cv) + supporting).map { it.id }
        // Ohne aktuelles Anschreiben: passendes gespeichertes Anschreiben vorschlagen
        if (applyLetterText.isBlank()) {
            documents.firstOrNull {
                it.type == DocType.ANSCHREIBEN && applyCompany.isNotBlank() && it.title.contains(applyCompany, true)
            }?.let { applyDocIds = listOf(it.id) + applyDocIds }
        }
        screen = Screen.Apply
    }

    fun applicantName(): String {
        settings.senderInfo.lines().firstOrNull { it.isNotBlank() }?.let { return it.trim() }
        val cv = documents.firstOrNull { it.type == DocType.LEBENSLAUF }?.text ?: return ""
        return cv.lines().map { it.trim() }.firstOrNull {
            it.isNotBlank() && it.split(" ").size in 2..4 && !it.contains("lebenslauf", true) && it.none { c -> c.isDigit() }
        } ?: ""
    }

    fun applyFileName(): String {
        val name = applicantName().ifBlank { "Bewerbung" }
        return PdfExport.safeName("Bewerbung ${name} ${applyCompany}".take(70)) + ".pdf"
    }

    fun applyEmailSubject(): String = "Bewerbung als ${applyTitle.ifBlank { "[Stelle]" }}" +
            (applyJob?.baRefnr?.let { " – Referenznummer $it" } ?: "")

    fun applyEmailBody(): String {
        val name = applicantName().ifBlank { "[Ihr Name]" }
        return "Sehr geehrte Damen und Herren,\n\n" +
                "anbei sende ich Ihnen meine Bewerbungsunterlagen für die Stelle als " +
                "${applyTitle.ifBlank { "[Stelle]" }}. Alle Unterlagen finden Sie in einer PDF-Datei " +
                "(Anschreiben, Lebenslauf und Nachweise).\n\n" +
                "Über eine Einladung zu einem persönlichen Gespräch freue ich mich.\n\n" +
                "Mit freundlichen Grüßen\n$name"
    }

    /** Erstellt die Bewerbungsmappe (ein PDF) und ruft danach [onReady] auf dem Hauptthread auf. */
    fun buildMappe(onReady: (File) -> Unit) {
        val docs = applyDocIds.mapNotNull { doc(it) }
        if (applyLetterText.isBlank() && docs.isEmpty()) {
            toast = "Bitte mindestens ein Dokument auswählen."
            return
        }
        busyMessage = "Bewerbungsmappe wird erstellt …"
        viewModelScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val pdf = android.graphics.pdf.PdfDocument()
                    var page = 1
                    if (applyLetterText.isNotBlank()) page = PdfExport.drawText(pdf, applyLetterText, page)
                    for (d in docs) {
                        val orig = d.originalPath?.let { File(it) }?.takeIf { it.exists() }
                        // Lebenslauf und Anschreiben als bearbeiteten Text, Nachweise möglichst als Original
                        val useOriginal = orig != null && (d.type !in setOf(DocType.LEBENSLAUF, DocType.ANSCHREIBEN) || d.text.isBlank())
                        page = if (useOriginal) PdfExport.appendPdfFile(pdf, orig!!, page)
                        else PdfExport.drawText(pdf, d.text, page)
                    }
                    val dir = File(getApplication<Application>().cacheDir, "pdf").apply { mkdirs() }
                    val out = File(dir, applyFileName())
                    out.outputStream().use { pdf.writeTo(it) }
                    pdf.close()
                    out
                }
            }
            busyMessage = null
            r.onSuccess { f ->
                val mb = f.length() / 1_048_576.0
                if (mb > 15) toast = "Hinweis: Die Mappe ist %.1f MB groß – für E-Mails evtl. zu groß.".format(mb)
                onReady(f)
            }.onFailure { toast = "Mappe konnte nicht erstellt werden: ${it.message}" }
        }
    }
}
