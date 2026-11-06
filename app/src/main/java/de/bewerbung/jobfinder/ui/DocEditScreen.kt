@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.ai.Prompts
import de.bewerbung.jobfinder.data.AppDocument
import de.bewerbung.jobfinder.data.DocType
import de.bewerbung.jobfinder.data.DocumentStore
import de.bewerbung.jobfinder.util.PdfExport

@Composable
fun DocEditScreen(vm: AppViewModel, docId: String) {
    val ctx = LocalContext.current
    val original = vm.doc(docId)
    if (original == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    var title by remember(docId) { mutableStateOf(original.title) }
    var type by remember(docId) { mutableStateOf(original.type) }
    var text by remember(docId) { mutableStateOf(original.text) }
    var focus by remember(docId) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var cvPasteVisible by remember(docId) { mutableStateOf(false) }

    LaunchedEffect(docId) {
        vm.cvResult = null
        vm.aiError = null
    }

    fun save() {
        vm.saveDoc(original.copy(title = title.ifBlank { type.label }, type = type, text = text))
    }

    fun saveAndBack() {
        save(); vm.back()
    }

    BackHandler { saveAndBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dokument bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = { saveAndBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { save(); vm.toast = "Gespeichert" }) {
                        Icon(Icons.Filled.Save, "Speichern")
                    }
                    IconButton(onClick = { save(); PdfExport.sharePdf(ctx, title, text) }) {
                        Icon(Icons.Filled.PictureAsPdf, "Als PDF teilen")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, "Löschen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Titel") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DocType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Inhalt") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 300.dp)
            )
            Text(
                "${wordCount(text)} Wörter · ca. ${PdfExport.pageCount(text)} Seite(n) als PDF",
                style = MaterialTheme.typography.labelSmall
            )
            OutlinedButton(onClick = { PdfExport.copyToClipboard(ctx, text); vm.toast = "Kopiert" }) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("Text kopieren")
            }

            if (type == DocType.LEBENSLAUF) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lebenslauf mit KI verbessern", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Fasst Punkte zusammen, sortiert modern (neueste Station zuerst) und formuliert prägnanter. " +
                                    "Es werden keine Fakten erfunden – bitte das Ergebnis trotzdem prüfen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = focus, onValueChange = { focus = it },
                            label = { Text("Optional: Ausrichtung (z. B. „für Stellen im Vertrieb“)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                save()
                                shareToChatApp(ctx, Prompts.forChat(Prompts.cvSystem, Prompts.cvUser(text, focus)))
                                cvPasteVisible = true
                            },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Send, null)
                            Spacer(Modifier.width(8.dp))
                            Text("An Chat-App senden (Claude, ChatGPT, Gemini)")
                        }
                        if (cvPasteVisible) {
                            OutlinedButton(
                                onClick = {
                                    val pasted = readClipboard(ctx).trim()
                                    if (pasted.isBlank() || pasted.startsWith("Du bist ein erfahrener")) {
                                        vm.toast = "In der Chat-App zuerst die Antwort kopieren, dann hier einfügen."
                                    } else {
                                        vm.cvResult = pasted.replace("**", "")
                                        cvPasteVisible = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.ContentPaste, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Antwort aus Zwischenablage einfügen")
                            }
                        }
                        if (vm.ai.isConfigured()) {
                            OutlinedButton(
                                onClick = { save(); vm.improveCv(text, focus) },
                                enabled = !vm.cvBusy && text.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (vm.cvBusy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("KI arbeitet …")
                                } else {
                                    Icon(Icons.Filled.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Direkt in der App verbessern")
                                }
                            }
                        }
                        vm.aiError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            vm.cvResult?.let { result ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Vorschlag der KI", style = MaterialTheme.typography.titleMedium)
                        Text(result, style = MaterialTheme.typography.bodySmall)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = {
                                text = result
                                save()
                                vm.cvResult = null
                                vm.toast = "Lebenslauf übernommen"
                            }) { Text("Übernehmen") }
                            OutlinedButton(onClick = {
                                vm.saveDoc(
                                    AppDocument(
                                        DocumentStore.newId(), "$title (überarbeitet)", DocType.LEBENSLAUF,
                                        result, System.currentTimeMillis()
                                    )
                                )
                                vm.cvResult = null
                                vm.toast = "Als neue Version gespeichert"
                            }) { Text("Als Kopie") }
                            TextButton(onClick = { vm.cvResult = null }) { Text("Verwerfen") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Dokument löschen?") },
            text = { Text("„$title“ wird endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteDoc(docId) }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } }
        )
    }
}
