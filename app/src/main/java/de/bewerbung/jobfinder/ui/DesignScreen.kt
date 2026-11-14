@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.bewerbung.jobfinder.data.AccentColor
import de.bewerbung.jobfinder.data.DesignTemplate
import de.bewerbung.jobfinder.util.DesignRenderer
import de.bewerbung.jobfinder.util.PdfExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class PreviewKind(val label: String) { CV("Lebenslauf"), LETTER("Anschreiben"), COVER("Deckblatt") }

/** Kontaktdaten, Vorlage, Farbe, Foto, Unterschrift und Deckblatt – mit Live-Vorschau. */
@Composable
fun DesignScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf(PreviewKind.CV) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var previewPages by remember { mutableStateOf(1) }
    var rendering by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setPhoto(uri)
    }
    val sigLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setSignatureFromUri(uri)
    }

    // Vorschau neu zeichnen, wenn sich etwas ändert
    LaunchedEffect(kind, vm.design, vm.profile, vm.designVersion, vm.documents) {
        delay(250)
        rendering = true
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val assets = vm.loadAssets()
                when (kind) {
                    PreviewKind.CV -> DesignRenderer.previewCv(vm.cvTextForDesign(), vm.profile, vm.design, assets, 900)
                    PreviewKind.LETTER -> DesignRenderer.previewLetter(vm.letterTextForDesign(), vm.profile, vm.design, assets, 900)
                    PreviewKind.COVER -> DesignRenderer.previewCover(
                        vm.profile, vm.design, assets, 900,
                        vm.clTitle.ifBlank { "Mitarbeiter Qualitätssicherung" }, vm.clCompany.ifBlank { "Beispiel GmbH" }
                    ) to 1
                }
            }.getOrNull()
        }
        rendering = false
        result?.let { preview = it.first; previewPages = it.second }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Design & Kontaktdaten") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Vorschau ----------
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewKind.entries.forEach { k ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(595f / 842f)
                            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        preview?.let {
                            Image(it.asImageBitmap(), "Vorschau", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        if (rendering && preview == null) CircularProgressIndicator()
                    }
                    Text(
                        (if (previewPages > 1) "Seite 1 von $previewPages" else "1 Seite") +
                                if (kind != PreviewKind.COVER && vm.documents.none { it.type == de.bewerbung.jobfinder.data.DocType.LEBENSLAUF } && kind == PreviewKind.CV)
                                    " · Beispieltext (noch kein Lebenslauf angelegt)" else "",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (kind == PreviewKind.LETTER && previewPages > 1) {
                        Text(
                            "Das Anschreiben ist länger als eine Seite – bitte kürzen.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (kind != PreviewKind.COVER) {
                        OutlinedButton(onClick = {
                            if (kind == PreviewKind.CV) vm.exportDesigned(true, vm.cvTextForDesign(), "Lebenslauf") { PdfExport.viewFile(ctx, it) }
                            else vm.exportDesigned(false, vm.letterTextForDesign(), "Anschreiben") { PdfExport.viewFile(ctx, it) }
                        }) {
                            Icon(Icons.Filled.PictureAsPdf, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Komplette PDF ansehen")
                        }
                    }
                }
            }

            // ---------- Vorlage ----------
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Vorlage", style = MaterialTheme.typography.titleMedium)
                    DesignTemplate.entries.forEach { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.updateDesign(vm.design.copy(template = t)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = vm.design.template == t, onClick = { vm.updateDesign(vm.design.copy(template = t)) })
                            Column {
                                Text(t.label, fontWeight = FontWeight.SemiBold)
                                Text(t.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (vm.design.template != DesignTemplate.SIMPLE) {
                        Spacer(Modifier.height(8.dp))
                        Text("Akzentfarbe", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AccentColor.entries.forEach { a ->
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .background(Color(a.argb), CircleShape)
                                        .border(
                                            if (vm.design.accent == a) 3.dp else 0.dp,
                                            MaterialTheme.colorScheme.onSurface, CircleShape
                                        )
                                        .clickable { vm.updateDesign(vm.design.copy(accent = a)) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (vm.design.accent == a) Icon(Icons.Filled.Check, a.label, tint = Color.White)
                                }
                            }
                        }
                    } else {
                        Text(
                            "„Schlicht“ verzichtet bewusst auf Foto und Farbflächen – so lesen Bewerberportale den Text am zuverlässigsten.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ---------- Foto ----------
            Card {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val photo = remember(vm.designVersion) {
                        if (vm.photoFile.exists()) BitmapFactory.decodeFile(vm.photoFile.absolutePath) else null
                    }
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (photo != null) Image(
                            photo.asImageBitmap(), "Foto",
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        else Icon(Icons.Filled.PhotoCamera, null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bewerbungsfoto (optional)", style = MaterialTheme.typography.titleSmall)
                        Text("In Deutschland üblich, aber keine Pflicht.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { photoLauncher.launch(arrayOf("image/*")) }) {
                                Text(if (photo == null) "Foto wählen" else "Ändern")
                            }
                            if (photo != null) TextButton(onClick = { vm.removePhoto() }) { Text("Entfernen") }
                        }
                    }
                }
            }

            // ---------- Unterschrift ----------
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Unterschrift (für das Anschreiben)", style = MaterialTheme.typography.titleSmall)
                    val sig = remember(vm.designVersion) {
                        if (vm.signatureFile.exists()) BitmapFactory.decodeFile(vm.signatureFile.absolutePath) else null
                    }
                    if (sig != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(70.dp)
                                .background(Color.White, RoundedCornerShape(6.dp))
                                .padding(6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) { Image(sig.asImageBitmap(), "Unterschrift", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    } else {
                        Text("Noch keine Unterschrift hinterlegt.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { showPad = true }) {
                            Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text("Mit dem Finger zeichnen")
                        }
                        TextButton(onClick = { sigLauncher.launch(arrayOf("image/*")) }) {
                            Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(4.dp)); Text("Foto der Unterschrift")
                        }
                        if (sig != null) TextButton(onClick = { vm.removeSignature() }) {
                            Icon(Icons.Filled.Delete, null); Spacer(Modifier.width(4.dp)); Text("Entfernen")
                        }
                    }
                    Text(
                        "Tipp fürs Foto: auf weißem Papier mit dunklem Stift unterschreiben und gerade von oben fotografieren – " +
                                "das Papier wird automatisch durchsichtig gemacht.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---------- Deckblatt ----------
            Card {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Deckblatt standardmäßig beifügen", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Für Online-Bewerbungen meist nicht nötig. Beim Bewerben lässt es sich jederzeit an- oder abschalten.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = vm.design.coverByDefault,
                        onCheckedChange = { vm.updateDesign(vm.design.copy(coverByDefault = it)) }
                    )
                }
            }

            // ---------- Kontaktdaten ----------
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Meine Kontaktdaten", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Erscheinen im Kopf von Lebenslauf und Anschreiben und auf dem Deckblatt. Werden automatisch gespeichert.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val p = vm.profile
                    ProfileField("Vor- und Nachname", p.name) { vm.updateProfile(vm.profile.copy(name = it)) }
                    ProfileField("Überschrift (optional), z. B. Qualitätssicherung | GMP", p.headline) {
                        vm.updateProfile(vm.profile.copy(headline = it))
                    }
                    ProfileField("Straße und Hausnummer\nPLZ Ort", p.address, minLines = 2) { vm.updateProfile(vm.profile.copy(address = it)) }
                    ProfileField("Telefon", p.phone) { vm.updateProfile(vm.profile.copy(phone = it)) }
                    ProfileField("E-Mail", p.email) { vm.updateProfile(vm.profile.copy(email = it)) }
                    ProfileField("LinkedIn/XING oder Website (optional)", p.web) { vm.updateProfile(vm.profile.copy(web = it)) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPad) {
        SignaturePadDialog(onDismiss = { showPad = false }) { bmp ->
            vm.saveSignature(bmp)
            showPad = false
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minLines == 1,
        minLines = minLines,
        supportingText = if (value.isNotBlank()) ({ Text("✓ Gespeichert", color = Color(0xFF2E7D32)) }) else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Unterschrift mit dem Finger zeichnen. */
@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unterschreiben", style = MaterialTheme.typography.titleMedium)
                Text("Mit dem Finger im weißen Feld unterschreiben (Handy am besten quer halten).", style = MaterialTheme.typography.bodySmall)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(0.5.dp, Color.Gray, RoundedCornerShape(6.dp))
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current.add(it) },
                                onDrag = { change, _ -> current.add(change.position) },
                                onDragEnd = { strokes.add(current.toList()); current.clear() }
                            )
                        }
                ) {
                    val all = strokes + listOf(current.toList())
                    all.forEach { pts ->
                        if (pts.size > 1) {
                            val path = androidx.compose.ui.graphics.Path()
                            path.moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                            drawPath(path, Color(0xFF12265C), style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { strokes.clear(); current.clear() }) { Text("Löschen") }
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    TextButton(
                        onClick = {
                            if (strokes.isEmpty() || size.width == 0) return@TextButton
                            val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                            val c = android.graphics.Canvas(bmp)
                            val paint = Paint().apply {
                                isAntiAlias = true; color = 0xFF12265C.toInt(); strokeWidth = 6f
                                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                            }
                            strokes.forEach { pts ->
                                val path = android.graphics.Path()
                                if (pts.isNotEmpty()) {
                                    path.moveTo(pts[0].x, pts[0].y)
                                    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                                    c.drawPath(path, paint)
                                }
                            }
                            onDone(bmp)
                        }
                    ) { Text("Übernehmen") }
                }
            }
        }
    }
}
