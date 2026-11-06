@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.data.Job
import de.bewerbung.jobfinder.data.PortalLinks
import de.bewerbung.jobfinder.data.SortMode

@Composable
fun JobsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var showNotes by remember { mutableStateOf(false) }
    val sorted = vm.sortedJobs()

    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        label = { Text("Beruf / Suchwort") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        OutlinedTextField(
                            value = vm.placeText,
                            onValueChange = { vm.onPlaceTextChange(it) },
                            label = { Text("Ort / PLZ") },
                            leadingIcon = { Icon(Icons.Filled.Place, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = vm.radius,
                            onValueChange = { v -> vm.radius = v.filter { it.isDigit() }.take(3) },
                            label = { Text("km") },
                            leadingIcon = { Icon(Icons.Filled.Straighten, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                            modifier = Modifier.width(110.dp)
                        )
                    }
                    // Ortsvorschläge
                    if (vm.placeSuggestions.isNotEmpty()) {
                        Card {
                            Column {
                                vm.placeSuggestions.forEach { p ->
                                    Text(
                                        p.label,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { vm.selectPlace(p); focus.clearFocus() }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    vm.selectedPlace?.let {
                        Text(
                            "Gewählter Ort: ${it.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text("Anzeigen nicht älter als:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "egal", 1 to "1 Tag", 3 to "3 Tage", 7 to "7 Tage", 14 to "14 Tage", 30 to "30 Tage")
                            .forEach { (d, label) ->
                                FilterChip(
                                    selected = vm.maxAgeDays == d,
                                    onClick = {
                                        vm.maxAgeDays = d
                                        if (vm.hasSearched) vm.search()
                                    },
                                    label = { Text(label) }
                                )
                            }
                    }

                    Button(
                        onClick = { focus.clearFocus(); vm.search() },
                        enabled = !vm.searching,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (vm.searching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Suche läuft …")
                        } else {
                            Icon(Icons.Filled.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Stellen suchen")
                        }
                    }
                }
            }
        }

        if (vm.hasSearched) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sortieren nach: ", style = MaterialTheme.typography.labelLarge)
                    SortMode.entries.forEach { m ->
                        FilterChip(
                            selected = vm.sortMode == m,
                            onClick = { vm.sortMode = m },
                            label = { Text(m.label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            item {
                Column {
                    Text(
                        "${sorted.size} Stellen gefunden" + if (showNotes) "" else "  (Details zu Quellen ▾)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { showNotes = !showNotes }
                    )
                    if (showNotes) {
                        vm.searchNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Column {
                Text(
                    "Auch direkt bei den großen Portalen suchen (öffnet den Browser):",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PortalLinks.all(vm.query, vm.placeText, vm.radius.toIntOrNull() ?: 25).forEach { (name, url) ->
                        AssistChip(
                            onClick = { openUrl(ctx, url) },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Filled.Public, null, Modifier.size(16.dp)) }
                        )
                    }
                }
                }
            }
        } else {
            item {
                Text(
                    "Gib einen Beruf oder ein Suchwort, einen Ort und die Entfernung ein. " +
                            "Durchsucht werden die Jobbörse der Arbeitsagentur sowie – mit kostenlosem " +
                            "Schlüssel in den Einstellungen – Adzuna und Jooble, die Anzeigen vieler " +
                            "weiterer Jobbörsen bündeln.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(sorted, key = { it.id }) { job ->
            JobCard(job) { vm.screen = Screen.JobDetail(job) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun JobCard(job: Job, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                job.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (job.company.isNotBlank()) {
                Text(job.company, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.location.isNotBlank()) {
                    Icon(Icons.Filled.Place, null, Modifier.size(14.dp))
                    Text(
                        " ${job.location}" + (formatDistance(job.distanceKm).let { if (it.isBlank()) "" else " · $it" }),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (job.publishedMillis != null) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(14.dp))
                    Text(" ${formatAge(job.publishedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                job.source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
