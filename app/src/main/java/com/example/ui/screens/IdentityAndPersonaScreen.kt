package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TeenaIdentityEngine
import com.example.ai.TeenaIdentityProfile
import com.example.data.local.toIdentityProfile
import com.example.ui.theme.NeuralCyan
import com.example.ui.theme.NeuralMint
import com.example.ui.viewmodel.TeenaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityAndPersonaScreen(viewModel: TeenaViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile = settings.toIdentityProfile()
    val activePreset = TeenaIdentityEngine.presetFor(settings.personalityId)

    var nameInput by remember { mutableStateOf(settings.userName) }
    var addressingInput by remember { mutableStateOf(settings.userPreferredAddressing) }
    var bioInput by remember { mutableStateOf(settings.userBioOrFocus) }
    var customToneInput by remember { mutableStateOf(settings.customPersonaInstructions) }

    LaunchedEffect(
        settings.userName,
        settings.userPreferredAddressing,
        settings.userBioOrFocus,
        settings.customPersonaInstructions
    ) {
        nameInput = settings.userName
        addressingInput = settings.userPreferredAddressing
        bioInput = settings.userBioOrFocus
        customToneInput = settings.customPersonaInstructions
    }

    val livePreviewGreeting = TeenaIdentityEngine.buildPersonalizedGreeting(
        TeenaIdentityProfile(
            personalityId = settings.personalityId,
            userName = nameInput,
            preferredAddressing = addressingInput,
            userBioOrFocus = bioInput,
            customPersonaInstructions = customToneInput,
            syncVoiceWithPersonality = settings.syncVoiceWithPersonality,
            activeVoiceId = settings.selectedVoice
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("identity_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Active Identity & Greeting Preview
        item {
            NeuralCard(borderColor = NeuralCyan.copy(alpha = 0.55f)) {
                SectionHeader(
                    title = "Teena AI Identity & Persona",
                    subtitle = "Shapes both Typed Chat and Gemini Live Voice tone (never leaks to External Advisors)",
                    trailing = { StatusPill("ACTIVE") }
                )
                Text(
                    text = livePreviewGreeting,
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuralMint,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("identity_greeting_preview")
                )
                Text(
                    text = "Active Voice: ${settings.selectedVoice} • Preset: ${activePreset.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Personality Presets Selector
        item {
            NeuralCard {
                SectionHeader(
                    title = "Select Teena Personality",
                    subtitle = "Pick how Teena speaks, reasons, and pairs voice personas"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-Pair Voice Persona", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Switch Live voice automatically when changing personality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.syncVoiceWithPersonality,
                        onCheckedChange = { sync ->
                            viewModel.updateSettings { it.copy(syncVoiceWithPersonality = sync) }
                        },
                        modifier = Modifier.testTag("identity_sync_voice_switch")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeenaIdentityEngine.presets.forEach { preset ->
                        val selected = preset.id == activePreset.id
                        NeuralCard(
                            borderColor = if (selected) NeuralMint else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = "${preset.title} • Voice: ${preset.defaultVoiceId}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) NeuralMint else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.tagline,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.selectPersonality(preset.id) },
                                    label = { Text(if (selected) "Selected" else "Use") },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("personality_chip_${preset.id}")
                                )
                            }
                        }
                    }
                }
            }
        }

        // User Identity & Custom Persona Instructions
        item {
            NeuralCard {
                SectionHeader(
                    title = "Your User Identity",
                    subtitle = "Teena remembers your name, role & preferred tone locally in DataStore"
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Your Name (e.g. Ahmad)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_name_input")
                )

                Text("How should Teena address you?", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TeenaIdentityEngine.addressingOptions.forEach { option ->
                        FilterChip(
                            selected = addressingInput.equals(option, ignoreCase = true),
                            onClick = { addressingInput = option },
                            label = { Text(option, maxLines = 1, softWrap = false) }
                        )
                    }
                }

                OutlinedTextField(
                    value = bioInput,
                    onValueChange = { bioInput = it },
                    label = { Text("Your Role / Focus (e.g. Android developer, student)") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_bio_input")
                )

                OutlinedTextField(
                    value = customToneInput,
                    onValueChange = { customToneInput = it },
                    label = { Text("Custom Teena Style Note (optional)") },
                    placeholder = { Text("e.g. Explain code with short Roman Urdu summaries first") },
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_custom_style_input")
                )
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.saveUserIdentity(
                        userName = nameInput,
                        preferredAddressing = addressingInput,
                        userBioOrFocus = bioInput,
                        customPersonaInstructions = customToneInput
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("identity_save_btn")
            ) {
                Text("Save Identity & Style")
            }
        }
    }
}

@Composable
fun PersonalityPresetStrip(
    selectedPersonalityId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(TeenaIdentityEngine.presets, key = { it.id }) { preset ->
            FilterChip(
                selected = preset.id == selectedPersonalityId,
                onClick = { onSelect(preset.id) },
                label = { Text(preset.title, maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag("quick_personality_chip_${preset.id}")
            )
        }
    }
}
