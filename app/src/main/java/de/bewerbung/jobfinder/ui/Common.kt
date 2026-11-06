@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun JobFinderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF85C1E9), secondary = Color(0xFFF5B041))
        else -> lightColorScheme(primary = Color(0xFF1B4F72), secondary = Color(0xFFB9770E))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current

    BackHandler(enabled = vm.screen != Screen.Tabs) { vm.back() }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }

    when (val s = vm.screen) {
        Screen.Tabs -> TabsScaffold(vm)
        is Screen.JobDetail -> JobDetailScreen(vm, s.job)
        is Screen.DocEdit -> DocEditScreen(vm, s.docId)
        Screen.CoverLetter -> CoverLetterScreen(vm)
        Screen.Apply -> ApplyScreen(vm)
    }

    vm.busyMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Bitte warten") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text(msg)
                }
            }
        )
    }
}

@Composable
private fun TabsScaffold(vm: AppViewModel) {
    val titles = listOf("Stellensuche", "Bewerbungsunterlagen", "Einstellungen")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[vm.tab]) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == 0, onClick = { vm.tab = 0 },
                    icon = { Icon(Icons.Filled.Work, null) }, label = { Text("Stellen") }
                )
                NavigationBarItem(
                    selected = vm.tab == 1, onClick = { vm.tab = 1 },
                    icon = { Icon(Icons.Filled.Description, null) }, label = { Text("Unterlagen") }
                )
                NavigationBarItem(
                    selected = vm.tab == 2, onClick = { vm.tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Einstellungen") }
                )
            }
        }
    ) { padding ->
        val m = Modifier.padding(padding)
        when (vm.tab) {
            0 -> JobsScreen(vm, m)
            1 -> DocumentsScreen(vm, m)
            else -> SettingsScreen(vm, m)
        }
    }
}

// ---------- Hilfsfunktionen ----------

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "Link konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
    }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun formatAge(millis: Long?): String {
    if (millis == null) return ""
    val days = ((System.currentTimeMillis() - millis) / 86_400_000L).coerceAtLeast(0)
    return when (days) {
        0L -> "heute"
        1L -> "gestern"
        else -> "vor $days Tagen"
    }
}

fun formatDistance(km: Double?): String = when {
    km == null -> ""
    km < 1 -> "< 1 km"
    else -> "${km.toInt()} km"
}

fun wordCount(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }

val SectionSpacing = Arrangement.spacedBy(12.dp)

/**
 * Schickt einen Text über das Android-Teilen-Menü an eine Chat-App (Claude, ChatGPT, Gemini …)
 * und legt ihn zusätzlich in die Zwischenablage – falls die App geteilten Text nicht übernimmt.
 */
fun shareToChatApp(context: Context, text: String) {
    de.bewerbung.jobfinder.util.PdfExport.copyToClipboard(context, text)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "An Chat-App senden (z. B. Claude)"))
    }
    Toast.makeText(
        context,
        "Anfrage ist auch in der Zwischenablage. Antwort danach hier einfügen.",
        Toast.LENGTH_LONG
    ).show()
}

/** Liest Text aus der Zwischenablage. */
fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
}
