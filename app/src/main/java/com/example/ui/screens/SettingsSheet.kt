package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.audio.AudioMetadataHelper
import com.example.data.prefs.ProcessingEngineMode
import com.example.data.prefs.UserPreferences
import com.example.i18n.AppLanguage
import com.example.i18n.AppStrings
import com.example.ui.theme.LocalStudioPalette
import com.example.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    strings: AppStrings,
    preferences: UserPreferences,
    storageBytes: Long,
    serverTestResult: Boolean?,
    onDismiss: () -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSelectTheme: (ThemeMode) -> Unit,
    onSelectEngine: (ProcessingEngineMode) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onTestConnection: (String) -> Unit,
    onUpdateStrength: (Float) -> Unit,
    onCleanCache: () -> Unit
) {
    val studio = LocalStudioPalette.current
    var urlDraft by remember(preferences.remoteServerUrl) { mutableStateOf(preferences.remoteServerUrl) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Language
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.languageLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = preferences.language == AppLanguage.ENGLISH,
                        onClick = { onSelectLanguage(AppLanguage.ENGLISH) },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = preferences.language == AppLanguage.BANGLA,
                        onClick = { onSelectLanguage(AppLanguage.BANGLA) },
                        label = { Text("বাংলা (Bangla)") }
                    )
                }
            }

            // Theme
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.themeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = preferences.themeMode == ThemeMode.DARK,
                        onClick = { onSelectTheme(ThemeMode.DARK) },
                        label = { Text(strings.themeDark) }
                    )
                    FilterChip(
                        selected = preferences.themeMode == ThemeMode.LIGHT,
                        onClick = { onSelectTheme(ThemeMode.LIGHT) },
                        label = { Text(strings.themeLight) }
                    )
                    FilterChip(
                        selected = preferences.themeMode == ThemeMode.SYSTEM,
                        onClick = { onSelectTheme(ThemeMode.SYSTEM) },
                        label = { Text(strings.themeSystem) }
                    )
                }
            }

            // Processing Engine
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.processingEngineLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                EngineOptionCard(
                    title = strings.engineOnDevice,
                    description = strings.engineOnDeviceDesc,
                    selected = preferences.engineMode == ProcessingEngineMode.ON_DEVICE_DSP,
                    onClick = { onSelectEngine(ProcessingEngineMode.ON_DEVICE_DSP) }
                )

                EngineOptionCard(
                    title = strings.engineRemoteDemucs,
                    description = strings.engineRemoteDesc,
                    selected = preferences.engineMode == ProcessingEngineMode.REMOTE_DEMUCS,
                    onClick = { onSelectEngine(ProcessingEngineMode.REMOTE_DEMUCS) }
                )

                if (preferences.engineMode == ProcessingEngineMode.REMOTE_DEMUCS) {
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = {
                            urlDraft = it
                            onUpdateServerUrl(it)
                        },
                        label = { Text(strings.serverUrlLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(onClick = { onTestConnection(urlDraft) }) {
                            Text(strings.testConnectionBtn)
                        }
                        when (serverTestResult) {
                            true -> Text(
                                text = strings.connectionSuccess,
                                style = MaterialTheme.typography.bodySmall,
                                color = studio.successAccent
                            )
                            false -> Text(
                                text = strings.connectionFailed,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            null -> Unit
                        }
                    }
                }
            }

            // Vocal Isolation Strength
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = strings.isolationStrengthLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${(preferences.isolationStrength * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.instrumentalAccent
                    )
                }
                Slider(
                    value = preferences.isolationStrength,
                    onValueChange = onUpdateStrength,
                    valueRange = 0.2f..1.0f
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(strings.strengthGentle, style = MaterialTheme.typography.labelSmall, color = studio.mutedText)
                    Text(strings.strengthBalanced, style = MaterialTheme.typography.labelSmall, color = studio.mutedText)
                    Text(strings.strengthAggressive, style = MaterialTheme.typography.labelSmall, color = studio.mutedText)
                }
            }

            // Storage & Temp Cache
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, studio.borderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.storageCacheLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = AudioMetadataHelper.formatFileSize(storageBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.instrumentalAccent
                        )
                    }
                    OutlinedButton(onClick = onCleanCache) {
                        Text(strings.clearCacheBtn)
                    }
                }
            }

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("close_settings_button")
            ) {
                Text(strings.closeBtn)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EngineOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) studio.instrumentalContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (selected) studio.instrumentalAccent else studio.borderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.mutedText
                )
            }
        }
    }
}
