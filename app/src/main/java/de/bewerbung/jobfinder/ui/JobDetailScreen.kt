@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.data.Job

@Composable
fun JobDetailScreen(vm: AppViewModel, job: Job) {
    val ctx = LocalContext.current
    LaunchedEffect(job.id) { vm.loadDescription(job) }
    val desc = vm.descriptions[job.id]
    val loading = vm.descriptionLoading[job.id] == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stellenanzeige") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(job.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (job.company.isNotBlank()) Text(job.company, style = MaterialTheme.typography.titleMedium)
            val meta = listOf(
                job.location,
                formatDistance(job.distanceKm).let { if (it.isBlank()) "" else "$it entfernt (Luftlinie)" },
                formatAge(job.publishedMillis).let { if (it.isBlank()) "" else "veröffentlicht $it" }
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium)
            Text("Quelle: ${job.source}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

            Spacer(Modifier.height(4.dp))
            Button(onClick = { vm.openCoverLetter(job) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text("Passendes Anschreiben erstellen")
            }
            Button(onClick = { vm.openApply(job) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Send, null)
                Spacer(Modifier.width(8.dp))
                Text("Direkt bewerben (Bewerbungsmappe)")
            }
            OutlinedButton(onClick = { openUrl(ctx, job.url) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Public, null)
                Spacer(Modifier.width(8.dp))
                Text("Originalanzeige öffnen")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row {
                Text("Anzeigentext", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (job.url.isNotBlank()) {
                    TextButton(onClick = { vm.loadDescription(job, force = true) }, enabled = !loading) {
                        Text("Neu laden")
                    }
                }
            }
            when {
                loading -> CircularProgressIndicator()
                desc != null -> SelectionContainer { Text(desc, style = MaterialTheme.typography.bodyMedium) }
                else -> Text(job.snippet)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
