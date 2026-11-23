package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.AudioMetadataHelper
import com.example.audio.SeparationStage
import com.example.i18n.AppStrings
import com.example.ui.ProcessingUiState
import com.example.ui.components.ProcessingOrbVisualizer
import com.example.ui.theme.LocalStudioPalette

@Composable
fun ProcessingScreen(
    state: ProcessingUiState,
    strings: AppStrings,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioPalette.current

    BackHandler {
        if (state is ProcessingUiState.Running) {
            onCancel()
        } else {
            onBackHome()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when (state) {
                is ProcessingUiState.Running -> {
                    Text(
                        text = strings.processingTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    // Audio File Metadata Card
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, studio.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.fileName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = studio.instrumentalContainer
                                ) {
                                    Text(
                                        text = state.formatLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = studio.instrumentalAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (state.durationMs > 0L) {
                                    Text(
                                        text = AudioMetadataHelper.formatDuration(state.durationMs),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (state.fileSizeBytes > 0L) {
                                    Text(
                                        text = "• ${AudioMetadataHelper.formatFileSize(state.fileSizeBytes)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = studio.mutedText
                                    )
                                }
                            }
                        }
                    }

                    ProcessingOrbVisualizer(progressPercent = state.percent)

                    val stageLabel = when (state.stage) {
                        SeparationStage.PREPARING -> strings.stagePreparing
                        SeparationStage.DECODING -> strings.stageDecoding
                        SeparationStage.SEPARATING -> if (state.isRemote) strings.stageRemoteProcessing else strings.stageSeparating
                        SeparationStage.MERGING -> strings.stageMerging
                        SeparationStage.FINALIZING -> strings.stageFinalizing
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stageLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )

                        LinearProgressIndicator(
                            progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                            color = studio.instrumentalAccent,
                            trackColor = studio.borderSubtle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(
                                    strings.chunkProgressFormat,
                                    state.currentChunk,
                                    state.totalChunks
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.mutedText
                            )
                            Text(
                                text = "${state.percent}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.instrumentalAccent
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cancel_processing_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.cancelProcessing,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is ProcessingUiState.Error -> {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = strings.processingErrorTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (state.isStorageError) {
                                    strings.insufficientStorageError
                                } else {
                                    "${state.detailMessage}\n\n${strings.networkRecoveryHint}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = studio.mutedText,
                                textAlign = TextAlign.Center
                            )

                            if (state.canRetry) {
                                Button(
                                    onClick = onRetry,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("retry_processing_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = strings.retryProcessing)
                                }
                            }

                            OutlinedButton(
                                onClick = onBackHome,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("error_back_home_button")
                            ) {
                                Text(text = strings.backToHome)
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}
