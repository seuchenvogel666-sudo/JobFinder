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
import de.bewerbung.jobfinder.data.Place
import de.bewerbung.jobfinder.data.SettingsStore
import de.bewerbung.jobfinder.data.SortMode
import de.bewerbung.jobfinder.data.TextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    data object Tabs : Screen()
    data class JobDetail(val job: Job) : Screen()
    data class DocEdit(val docId: String) : Screen()
    data object CoverLetter : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val settings = SettingsStore(app)
    private val store = DocumentStore(app)
    private val jobsRepo = JobRepository(settings)
    private val extractor = TextExtractor(app)
    val ai = AiClient(settings)

    // ---------- Navigation ----------
    var screen by mutableStateOf<Screen>(Screen.Tabs)
    var tab by mutableIntStateOf(0)

    fun back(): Boolean {
        if (screen == Screen.Tabs) return false
        screen = Screen.Tabs
        return true
    }

    // ---------- Stellensuche ----------
    var query by mutableStateOf(settings.lastQuery)
    var radius by mutableStateOf(settings.lastRadius)
    var placeText by mutableStateOf(settings.lastPlace)
    var selectedPlace by mutableStateOf<Place?>(null)
    var placeSuggestions by mutableStateOf<List<Place>>(emptyList())
    var sortMode by mutableStateOf(SortMode.DATE)
    var maxAgeDays by mutableIntStateOf(0)
    var jobs by mutableStateOf<List<Job>>(emptyList())
    var searchNotes by mutableStateOf<List<String>>(emptyList())
    var searching by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)

    private var placeJob: CoJob? = null

    fun onPlaceTextChange(text: String) {
        placeText = text
        selectedPlace = null
        placeJob?.cancel()
        if (text.trim().length < 2) {
            placeSuggestions = emptyList(); return
        }
        placeJob = viewModelScope.launch {
            delay(350)
            val list = withContext(Dispatchers.IO) {
                runCatching { GeoRepository.searchPlaces(text.trim()) }.getOrDefault(emptyList())
            }
            placeSuggestions = list
        }
    }

    fun selectPlace(p: Place) {
        placeJob?.cancel()
        selectedPlace = p
        placeText = p.name
        placeSuggestions = emptyList()
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
        settings.lastQuery = query
        settings.lastRadius = radius
        settings.lastPlace = placeText
        searching = true
        hasSearched = true
        viewModelScope.launch {
            // Wurde kein Vorschlag angeklickt, nehmen wir den ersten Treffer für die Entfernung
            var place = selectedPlace
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
                    radiusKm = radius.toIntOrNull() ?: 25,
                    maxAgeDays = maxAgeDays
                )
            }
            r.onSuccess {
                jobs = it.jobs
                searchNotes = it.notes
            }.onFailure {
                jobs = emptyList()
                searchNotes = listOf("Fehler: ${it.message}")
            }
            searching = false
        }
    }

    // Anzeigentexte (Detailansicht)
    val descriptions = mutableStateMapOf<String, String>()
    val descriptionLoading = mutableStateMapOf<String, Boolean>()

    fun loadDescription(job: Job, fromWeb: Boolean = false) {
        if (descriptionLoading[job.id] == true) return
        if (!fromWeb && descriptions.containsKey(job.id)) return
        descriptionLoading[job.id] = true
        viewModelScope.launch {
            val text = runCatching {
                when {
                    fromWeb -> jobsRepo.loadWebPageText(job.url)
                    job.baRefnr != null -> jobsRepo.loadBaDescription(job.baRefnr)
                    else -> job.snippet
                }
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

    /** Datei (PDF, Bild, Word, Text) importieren. */
    fun importFile(uri: Uri) {
        busyMessage = "Dokument wird gelesen …"
        viewModelScope.launch {
            val r = runCatching {
                val name = extractor.displayName(uri).substringBeforeLast('.')
                val text = extractor.extract(uri)
                name to text
            }
            busyMessage = null
            r.onSuccess { (name, text) -> createFromText(name, text) }
                .onFailure { toast = "Import fehlgeschlagen: ${it.message}" }
        }
    }

    /** Fotos/Scans (eine oder mehrere Seiten) per Texterkennung einlesen. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busyMessage = "Texterkennung läuft …"
        viewModelScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val sb = StringBuilder()
                    for (u in uris) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append(extractor.ocrUri(u))
                    }
                    sb.toString()
                }
            }
            busyMessage = null
            r.onSuccess { createFromText("Scan", it) }
                .onFailure { toast = "Texterkennung fehlgeschlagen: ${it.message}" }
        }
    }

    private fun createFromText(name: String, text: String) {
        if (text.isBlank()) {
            toast = "Im Dokument wurde kein Text erkannt."
            return
        }
        val type = DocumentStore.guessType(text)
        val title = if (name == "Scan" || name.isBlank()) type.label else name
        val d = AppDocument(DocumentStore.newId(), title, type, text, System.currentTimeMillis())
        saveDoc(d)
        screen = Screen.DocEdit(d.id)
        toast = "Dokument erstellt – bitte Text kurz prüfen."
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

    fun openCoverLetter(job: Job?) {
        clJob = job
        clTitle = job?.title ?: ""
        clCompany = job?.company ?: ""
        clAdText = job?.let { descriptions[it.id] ?: it.snippet } ?: ""
        clCvId = documents.firstOrNull { it.type == DocType.LEBENSLAUF }?.id ?: documents.firstOrNull()?.id
        clExtraDocIds = emptySet()
        clNotes = ""
        clResult = ""
        aiError = null
        screen = Screen.CoverLetter
        if (job != null && !descriptions.containsKey(job.id)) {
            loadDescription(job)
            viewModelScope.launch {
                // Text übernehmen, sobald er geladen ist
                repeat(40) {
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

    var clLoadingAd by mutableStateOf(false)

    /** Anzeigentext von der Originalseite holen (für Adzuna/Jooble/eigene Links). */
    fun loadAdFromWeb() {
        val job = clJob ?: return
        if (job.url.isBlank()) return
        clLoadingAd = true
        viewModelScope.launch {
            runCatching {
                if (job.baRefnr != null) jobsRepo.loadBaDescription(job.baRefnr)
                else jobsRepo.loadWebPageText(job.url)
            }.onSuccess { if (it.isNotBlank()) clAdText = it }
                .onFailure { toast = "Seite konnte nicht geladen werden – Anzeigentext bitte manuell einfügen." }
            clLoadingAd = false
        }
    }

    /** Baut die Anfrage (System + Nutzer) – oder null mit Fehlermeldung in aiError. */
    private fun coverLetterPromptParts(): Pair<String, String>? {
        val cvParts = buildList {
            clCvId?.let { id -> doc(id)?.let { add(it) } }
            clExtraDocIds.filter { it != clCvId }.mapNotNull { doc(it) }.forEach { add(it) }
        }
        if (cvParts.isEmpty()) {
            aiError = "Bitte zuerst unter \"Unterlagen\" einen Lebenslauf anlegen."
            return null
        }
        if (clAdText.isBlank() && clTitle.isBlank()) {
            aiError = "Bitte Stellentitel und Anzeigentext angeben."
            return null
        }
        aiError = null
        val cvText = cvParts.joinToString("\n\n") { "--- ${it.type.label}: ${it.title} ---\n${it.text}" }
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

    fun saveCoverLetterAsDoc() {
        if (clResult.isBlank()) return
        val title = "Anschreiben – ${clCompany.ifBlank { clTitle }}".take(80)
        saveDoc(AppDocument(DocumentStore.newId(), title, DocType.ANSCHREIBEN, clResult, System.currentTimeMillis()))
        toast = "Anschreiben unter \"Unterlagen\" gespeichert."
    }
}
