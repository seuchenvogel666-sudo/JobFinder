@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.data.DocType
import de.bewerbung.jobfinder.util.PdfExport

@Composable
fun CoverLetterScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var cvMenu by remember { mutableStateOf(false) }
    val configured = vm.ai.isConfigured()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anschreiben erstellen") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
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
            if (!configured) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Noch keine KI eingerichtet. Bitte in den Einstellungen Anbieter und API-Schlüssel eintragen.")
                        TextButton(onClick = { vm.screen = Screen.Tabs; vm.tab = 2 }) { Text("Zu den Einstellungen") }
                    }
                }
            }

            Text("1. Stelle", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = vm.clTitle, onValueChange = { vm.clTitle = it },
                label = { Text("Stellenbezeichnung") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.clCompany, onValueChange = { vm.clCompany = it },
                label = { Text("Unternehmen") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.clAdText, onValueChange = { vm.clAdText = it },
                label = { Text("Anzeigentext (Aufgaben, Anforderungen …)") },
                placeholder = { Text("Text der Stellenanzeige hier einfügen") },
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
            )
            val job = vm.clJob
            if (job != null && job.url.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.loadAdFromWeb() }, enabled = !vm.clLoadingAd) {
                        Text("Vollständigen Anzeigentext laden")
                    }
                    if (vm.clLoadingAd) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            Text("2. Meine Unterlagen", style = MaterialTheme.typography.titleMedium)
            if (vm.documents.isEmpty()) {
                Text(
                    "Noch keine Unterlagen vorhanden – bitte zuerst unter „Unterlagen“ den Lebenslauf importieren.",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val cv = vm.clCvId?.let { vm.doc(it) }
                Box {
                    OutlinedButton(onClick = { cvMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Lebenslauf: " + (cv?.title ?: "auswählen"),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = cvMenu, onDismissRequest = { cvMenu = false }) {
                        vm.documents.forEach { d ->
                            DropdownMenuItem(
                                text = { Text("${d.title} (${d.type.label})") },
                                onClick = { vm.clCvId = d.id; cvMenu = false }
                            )
                        }
                    }
                }
                val extras = vm.documents.filter { it.id != vm.clCvId && it.type != DocType.ANSCHREIBEN }
                if (extras.isNotEmpty()) {
                    Text("Zusätzlich berücksichtigen:", style = MaterialTheme.typography.labelLarge)
                    extras.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = d.id in vm.clExtraDocIds,
                                onCheckedChange = { on ->
                                    vm.clExtraDocIds = if (on) vm.clExtraDocIds + d.id else vm.clExtraDocIds - d.id
                                }
                            )
                            Text("${d.title} (${d.type.label})")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = vm.clNotes, onValueChange = { vm.clNotes = it },
                label = { Text("Optional: Wünsche (z. B. „Eintritt ab 01.03.“, „Teilzeit“)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { vm.generateCoverLetter() },
                enabled = !vm.clBusy && configured,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.clBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("KI schreibt …")
                } else {
                    Icon(Icons.Filled.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.clResult.isBlank()) "Anschreiben generieren" else "Neu generieren")
                }
            }
            vm.aiError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (vm.clResult.isNotBlank()) {
                Text("3. Ergebnis (bearbeitbar)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = vm.clResult, onValueChange = { vm.clResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp)
                )
                val pages = PdfExport.pageCount(vm.clResult)
                Text(
                    "${wordCount(vm.clResult)} Wörter · " +
                            if (pages <= 1) "passt auf eine DIN-A4-Seite ✓"
                            else "zu lang ($pages Seiten) – kürzen oder mit Wunsch „kürzer“ neu generieren",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (pages <= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    "Bitte prüfen: Platzhalter in [eckigen Klammern] ausfüllen und Inhalte kontrollieren.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        PdfExport.sharePdf(ctx, "Anschreiben ${vm.clCompany}".trim(), vm.clResult)
                    }) {
                        Icon(Icons.Filled.PictureAsPdf, null)
                        Spacer(Modifier.width(6.dp))
                        Text("PDF")
                    }
                    OutlinedButton(onClick = {
                        PdfExport.copyToClipboard(ctx, vm.clResult); vm.toast = "Kopiert"
                    }) {
                        Icon(Icons.Filled.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Kopieren")
                    }
                    OutlinedButton(onClick = { vm.saveCoverLetterAsDoc() }) {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Speichern")
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
