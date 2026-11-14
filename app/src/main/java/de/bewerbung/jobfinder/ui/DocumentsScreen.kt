@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import de.bewerbung.jobfinder.data.AppDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DocumentsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    // Datei-Import (PDF, Bild, Word, Text)
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }

    // Dokumenten-Scanner von Google (schneidet automatisch zu, mehrere Seiten)
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uris = result?.pages?.map { it.imageUri } ?: emptyList()
            vm.importImages(uris)
        }
    }

    // Ausweichlösung: normale Kamera-App
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = photoUri
        if (ok && u != null) vm.importImages(listOf(u))
    }

    fun startCameraFallback() {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "scan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        photoUri = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { Toast.makeText(ctx, "Keine Kamera-App gefunden", Toast.LENGTH_SHORT).show() }
    }

    fun startScan() {
        val activity = ctx.findActivity()
        if (activity == null) {
            startCameraFallback(); return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { startCameraFallback() }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unterlagen digitalisieren", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lebenslauf, Zwischenzeugnisse, Zeugnisse, Weiterbildungsnachweise oder alte Anschreiben als Datei (PDF) importieren oder abfotografieren. " +
                                "Der Text wird erkannt und kann danach bearbeitet werden.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                fileLauncher.launch(
                                    arrayOf(
                                        "application/pdf", "image/*", "text/plain",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.AttachFile, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Datei")
                        }
                        Button(onClick = { startScan() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PhotoCamera, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Foto/Scan")
                        }
                    }
                    OutlinedButton(onClick = { vm.newEmptyDoc() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Leeres Dokument (Text eintippen/einfügen)")
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { vm.navigate(Screen.Design) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Palette, null)
                Spacer(Modifier.width(6.dp))
                Text("Design, Foto, Unterschrift & Kontaktdaten")
            }
        }

        item {
            FilledTonalButton(onClick = { vm.openCoverLetter(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(6.dp))
                Text("Anschreiben für eigene Anzeige (z. B. LinkedIn)")
            }
        }

        if (!vm.ai.isConfigured()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        "Für Anschreiben und Lebenslauf-Verbesserung bitte unter „Einstellungen“ einen KI-Anbieter " +
                                "mit API-Schlüssel eintragen. Import, Texterkennung und Bearbeiten funktionieren auch ohne KI.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .clickable { vm.tab = 2 }
                    )
                }
            }
        }

        if (vm.documents.isEmpty()) {
            item {
                Text(
                    "Noch keine Unterlagen vorhanden.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp)
                )
            }
        } else {
            item { Text("Meine Unterlagen", style = MaterialTheme.typography.titleMedium) }
        }

        items(vm.documents, key = { it.id }) { doc ->
            DocCard(doc) { vm.screen = Screen.DocEdit(doc.id) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DocCard(doc: AppDocument, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                doc.type.label.uppercase() + if (doc.originalPath != null) "  ·  ORIGINAL-PDF" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(doc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                doc.text.take(160).replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Geändert: ${fmt.format(Date(doc.updatedMillis))} · ${wordCount(doc.text)} Wörter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
