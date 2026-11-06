@file:OptIn(ExperimentalMaterial3Api::class)

package de.bewerbung.jobfinder.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.bewerbung.jobfinder.data.AiProvider
import de.bewerbung.jobfinder.data.SettingsStore

@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val s = vm.settings

    var provider by remember { mutableStateOf(s.aiProvider) }
    val keys = remember { AiProvider.entries.associateWith { mutableStateOf(s.apiKey(it)) } }
    val models = remember {
        AiProvider.entries.associateWith { p ->
            mutableStateOf(if (p == AiProvider.NONE) "" else s.model(p))
        }
    }
    var sender by remember { mutableStateOf(s.senderInfo) }

    fun saveAll() {
        s.aiProvider = provider
        AiProvider.entries.filter { it != AiProvider.NONE }.forEach { p ->
            s.setApiKey(p, keys.getValue(p).value)
            s.setModel(p, models.getValue(p).value)
        }
        s.senderInfo = sender
        vm.toast = "Einstellungen gespeichert ✓"
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- KI ----------
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("KI für Anschreiben & Lebenslauf", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiProvider.entries.forEach { p ->
                        FilterChip(selected = provider == p, onClick = { provider = p; s.aiProvider = p }, label = { Text(p.label) })
                    }
                }
                if (provider != AiProvider.NONE) {
                    val keyState = keys.getValue(provider)
                    val modelState = models.getValue(provider)
                    OutlinedTextField(
                        value = keyState.value,
                        onValueChange = { keyState.value = it; s.setApiKey(provider, it) },
                        label = { Text("API-Schlüssel") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            val saved = keyState.value.replace(Regex("\\s"), "")
                            Text(
                                if (saved.isBlank()) "Noch kein Schlüssel gespeichert"
                                else "✓ Gespeichert (endet auf …${saved.takeLast(4)})"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelState.value,
                        onValueChange = { modelState.value = it; s.setModel(provider, it) },
                        label = { Text("Modell") }, singleLine = true,
                        supportingText = { Text("Standard: ${SettingsStore.defaultModel(provider)} – nur ändern, wenn der Anbieter das Modell umbenennt") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val (linkText, url) = when (provider) {
                        AiProvider.OPENAI -> "Schlüssel bei OpenAI erstellen" to "https://platform.openai.com/api-keys"
                        AiProvider.GEMINI -> "Schlüssel bei Google AI Studio erstellen" to "https://aistudio.google.com/apikey"
                        AiProvider.CLAUDE -> "Schlüssel in der Anthropic Console erstellen" to "https://console.anthropic.com/settings/keys"
                        AiProvider.NONE -> "" to ""
                    }
                    TextButton(onClick = { openUrl(ctx, url) }) { Text(linkText) }
                }
                Text(
                    "Datenschutz: Beim Erstellen eines Anschreibens bzw. beim Verbessern des Lebenslaufs werden " +
                            "Lebenslauf und Anzeigentext an den gewählten KI-Anbieter gesendet. Sonst bleiben alle " +
                            "Unterlagen nur auf diesem Handy.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Meine Absenderdaten (optional)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sender, onValueChange = { sender = it; s.senderInfo = it },
                    label = { Text("Name, Adresse, Telefon, E-Mail") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Wird im Briefkopf des Anschreibens verwendet. Leer lassen = aus dem Lebenslauf übernehmen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(onClick = { saveAll() }, modifier = Modifier.fillMaxWidth()) { Text("Alles speichern (geschieht auch automatisch)") }
        Spacer(Modifier.height(24.dp))
    }
}
