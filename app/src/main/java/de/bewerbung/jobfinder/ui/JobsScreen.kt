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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.PopupProperties
import de.bewerbung.jobfinder.data.Job
import de.bewerbung.jobfinder.data.PortalLinks
import de.bewerbung.jobfinder.data.SortMode
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import de.bewerbung.jobfinder.data.RadiusOption
import de.bewerbung.jobfinder.data.WebSite

@Composable
fun JobsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var showNotes by remember { mutableStateOf(false) }
    var radiusMenu by remember { mutableStateOf(false) }
    val sorted = vm.sortedJobs()
    val listState = rememberLazyListState()

    // Nach der Suche automatisch zur Trefferliste unter dem Suchfeld scrollen
    LaunchedEffect(vm.searching) {
        if (!vm.searching && vm.hasSearched) listState.animateScrollToItem(1)
    }

    LazyColumn(
        state = listState,
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
                        // Ort/PLZ mit aufklappender Vorschlagsliste (nur Deutschland)
                        Box(Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = vm.placeText,
                                onValueChange = { vm.onPlaceTextChange(it) },
                                label = { Text("Ort / PLZ") },
                                leadingIcon = { Icon(Icons.Filled.Place, null) },
                                trailingIcon = {
                                    if (vm.placeLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
                                modifier = Modifier.fillMaxWidth()
                            )
                            DropdownMenu(
                                expanded = vm.placeMenuOpen,
                                onDismissRequest = { vm.placeMenuOpen = false },
                                // Tastatur bleibt offen, man kann weitertippen
                                properties = PopupProperties(focusable = false)
                            ) {
                                if (vm.placeSuggestions.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Kein Ort in Deutschland gefunden", style = MaterialTheme.typography.bodySmall) },
                                        onClick = { vm.placeMenuOpen = false },
                                        enabled = false
                                    )
                                }
                                vm.placeSuggestions.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                                if (p.detail.isNotBlank()) {
                                                    Text(p.detail, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Place, null, Modifier.size(18.dp)) },
                                        onClick = { vm.selectPlace(p); focus.clearFocus() }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Feste Umkreis-Auswahl (aufklappbar)
                        Box {
                            OutlinedButton(
                                onClick = { radiusMenu = true },
                                modifier = Modifier.padding(top = 8.dp).height(56.dp)
                            ) {
                                Icon(Icons.Filled.Straighten, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (vm.radius.remote) "Remote" else vm.radius.label)
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = radiusMenu, onDismissRequest = { radiusMenu = false }) {
                                RadiusOption.entries.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.label) },
                                        onClick = { vm.radius = r; radiusMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    if (vm.radius.remote) {
                        Text(
                            "Remote: Es werden bundesweit Stellen mit Homeoffice/Remote gesucht – der Ort ist optional.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (vm.selectedPlace == null && vm.placeText.isNotBlank() && !vm.radius.remote) {
                        Text(
                            "Tipp: Ort aus den Vorschlägen antippen – dann wird die Entfernung genau berechnet.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    vm.selectedPlace?.let {
                        Text(
                            "Gewählter Ort: ${it.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Text("Quellen:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = true, onClick = {}, label = { Text("Arbeitsagentur") })
                        WebSite.entries.forEach { site ->
                            FilterChip(
                                selected = site in vm.webSources,
                                onClick = { vm.toggleWebSource(site) },
                                label = { Text(site.label) }
                            )
                        }
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
                        if (vm.searching) "Suche läuft …" else "${sorted.size} Stellen gefunden",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val hasError = vm.searchNotes.any { it.contains("Fehler") }
                    if (showNotes || hasError || (sorted.isEmpty() && !vm.searching)) {
                        vm.searchNotes.forEach {
                            Text(
                                "• $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("Fehler")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "Quellen anzeigen ▾",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showNotes = true }
                        )
                    }
                    if (sorted.isEmpty() && !vm.searching) {
                        Text(
                            "Keine Treffer. Tipp: allgemeineren Begriff (z. B. „Lager“ statt „Lagerlogistiker m/w/d“) " +
                                    "oder größeren Umkreis versuchen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    "Gib einen Beruf oder ein Suchwort, einen Ort und den Umkreis ein. " +
                            "Durchsucht werden die Jobbörse der Arbeitsagentur sowie die öffentlichen " +
                            "Suchseiten von LinkedIn, XING, Indeed und StepStone. Die Suche dauert " +
                            "dadurch etwa 10–20 Sekunden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(sorted, key = { it.id }) { job ->
            JobCard(job, vm.radius.remote, job.id in vm.enrichingIds) { vm.screen = Screen.JobDetail(job) }
        }
        if (vm.hasSearched) {
            item {
                Column {
                Text(
                    "Suche direkt auf der Seite öffnen (falls eine Quelle blockiert hat):",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PortalLinks.all(vm.jobsRepo.web, vm.query, vm.placeText, vm.radius).forEach { (name, url) ->
                        AssistChip(
                            onClick = { openUrl(ctx, url) },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Filled.Public, null, Modifier.size(16.dp)) }
                        )
                    }
                }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun JobCard(job: Job, remote: Boolean, enriching: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    job.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                // Entfernung zur Arbeitsstelle (Luftlinie) als gut sichtbares Kästchen
                val dist = when {
                    job.distanceKm != null -> "ca. " + formatDistance(job.distanceKm)
                    remote -> "Remote"
                    enriching -> "…"
                    else -> "? km"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (job.distanceKm != null || remote) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (enriching && job.distanceKm == null) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        } else {
                            Icon(Icons.Filled.Straighten, null, Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(dist, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (job.company.isNotBlank()) {
                Text(job.company, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.location.isNotBlank()) {
                    Icon(Icons.Filled.Place, null, Modifier.size(14.dp))
                    Text(
                        " ${job.location}",
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
