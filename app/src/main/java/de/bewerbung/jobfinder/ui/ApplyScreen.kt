@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.data.DocType
import de.bewerbung.jobfinder.util.PdfExport
import java.io.File

/**
 * Direkt bewerben: Bewerbungsmappe (ein PDF aus Anschreiben, Lebenslauf und Nachweisen) erstellen
 * und per E-Mail an die Adresse aus der Anzeige senden – oder speichern und auf der
 * Bewerbungsseite des Arbeitgebers hochladen.
 */
@Composable
fun ApplyScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var pendingSave by remember { mutableStateOf<File?>(null) }

    // "Speichern unter" (z. B. Downloads), damit die Datei in Upload-Formularen auswählbar ist
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pendingSave
        if (uri != null && f != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }.onSuccess { vm.toast = "Bewerbungsmappe gespeichert ✓" }
                .onFailure { vm.toast = "Speichern fehlgeschlagen: ${it.message}" }
        }
        pendingSave = null
    }

    fun sendEmail(file: File) {
        val uri = PdfExport.uriFor(ctx, file)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(vm.applyEmail.trim()))
            putExtra(Intent.EXTRA_SUBJECT, vm.applyEmailSubject())
            putExtra(Intent.EXTRA_TEXT, vm.applyEmailBody())
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Möglichst nur E-Mail-Apps anbieten
        val mailOnly = Intent(base).apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
        try {
            ctx.startActivity(mailOnly)
        } catch (e: ActivityNotFoundException) {
            runCatching { ctx.startActivity(Intent.createChooser(base, "Bewerbung senden mit …")) }
                .onFailure { Toast.makeText(ctx, "Keine E-Mail-App gefunden", Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Direkt bewerben") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
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
            Text(vm.applyTitle.ifBlank { "Bewerbung" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (vm.applyCompany.isNotBlank()) Text(vm.applyCompany, style = MaterialTheme.typography.titleMedium)

            // ---------- Unterlagen ----------
            Text("1. Unterlagen für die Bewerbungsmappe", style = MaterialTheme.typography.titleMedium)
            Text(
                "Alles wird zu EINER PDF zusammengefügt (Reihenfolge wie unten). Lebenslauf und Anschreiben " +
                        "werden aus dem bearbeiteten Text erstellt, Zeugnisse und Nachweise – wenn vorhanden – " +
                        "aus dem Original.",
                style = MaterialTheme.typography.bodySmall
            )
            if (vm.applyLetterText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = true, onCheckedChange = { if (!it) vm.applyLetterText = "" })
                    Text("Aktuelles Anschreiben", fontWeight = FontWeight.SemiBold)
                }
            }
            val order = listOf(DocType.ANSCHREIBEN, DocType.LEBENSLAUF, DocType.ZWISCHENZEUGNIS, DocType.ZEUGNIS, DocType.WEITERBILDUNG, DocType.SONSTIGES)
            vm.documents.sortedBy { order.indexOf(it.type) }.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = d.id in vm.applyDocIds,
                        onCheckedChange = { on ->
                            vm.applyDocIds = if (on) {
                                (vm.applyDocIds + d.id).sortedBy { id -> vm.doc(id)?.type?.let { t -> order.indexOf(t) } ?: 99 }
                            } else vm.applyDocIds - d.id
                        }
                    )
                    Column {
                        Text(d.title)
                        Text(
                            d.type.label + if (d.originalPath != null && d.type in DocType.supporting) " · Original" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (vm.applyLetterText.isBlank() && vm.applyDocIds.none { vm.doc(it)?.type == DocType.ANSCHREIBEN }) {
                Text(
                    "Noch kein Anschreiben ausgewählt – über „Passendes Anschreiben erstellen“ kannst du eins erzeugen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            OutlinedButton(
                onClick = { vm.buildMappe { f -> PdfExport.viewFile(ctx, f) } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Vorschau der Bewerbungsmappe") }

            // ---------- Per E-Mail ----------
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2a. Per E-Mail bewerben", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.applyEmails.isNotEmpty()) "In der Anzeige gefundene Adresse(n): ${vm.applyEmails.joinToString(", ")}"
                        else "In der Anzeige wurde keine E-Mail-Adresse gefunden. Falls die Anzeige eine nennt, hier eintragen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = vm.applyEmail, onValueChange = { vm.applyEmail = it },
                        label = { Text("E-Mail des Arbeitgebers") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { vm.buildMappe { f -> sendEmail(f) } },
                        enabled = vm.applyEmail.contains("@"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Email, null)
                        Spacer(Modifier.width(8.dp))
                        Text("E-Mail mit Bewerbungsmappe öffnen")
                    }
                    Text(
                        "Betreff und kurzer Begleittext werden vorausgefüllt. Vor dem Absenden in der Mail-App bitte prüfen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------- Über die Bewerbungsseite ----------
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2b. Über die Bewerbungsseite bewerben", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Viele Arbeitgeber, LinkedIn („Einfach bewerben“), XING, Indeed und StepStone nutzen eigene " +
                                "Formulare. Speichere zuerst die Mappe, öffne dann die Seite und lade die Datei dort hoch.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            vm.buildMappe { f ->
                                pendingSave = f
                                saveLauncher.launch(f.name)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("1) Bewerbungsmappe speichern (PDF)")
                    }
                    OutlinedButton(
                        onClick = { openUrl(ctx, vm.applyUrl) },
                        enabled = vm.applyUrl.startsWith("http"),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Public, null)
                        Spacer(Modifier.width(8.dp))
                        Text("2) Bewerbungsseite öffnen")
                    }
                    TextButton(onClick = { vm.buildMappe { f -> PdfExport.shareFile(ctx, f, vm.applyEmailSubject()) } }) {
                        Icon(Icons.Filled.Share, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Mappe teilen (z. B. an Drive, WhatsApp, andere App)")
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
