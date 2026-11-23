package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.ActivePlaybackTarget
import com.example.audio.AudioMetadataHelper
import com.example.audio.ExportFormat
import com.example.audio.PlaybackState
import com.example.audio.WavUtils
import com.example.data.local.ProcessedTrackEntity
import com.example.i18n.AppStrings
import com.example.ui.ExportUiState
import com.example.ui.components.InteractiveWaveform
import com.example.ui.theme.LocalStudioPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    track: ProcessedTrackEntity,
    strings: AppStrings,
    playbackState: PlaybackState,
    exportState: ExportUiState,
    onLoadStems: (ProcessedTrackEntity) -> Unit,
    onToggleVocals: () -> Unit,
    onToggleInstrumental: () -> Unit,
    onToggleDualMix: () -> Unit,
    onMixBalanceChange: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onPausePlayback: () -> Unit,
    onExportToMusic: (ProcessedTrackEntity, Boolean, ExportFormat) -> Unit,
    onExportToUri: (ProcessedTrackEntity, Boolean, ExportFormat, android.net.Uri) -> Unit,
    onBuildShareIntent: suspend (ProcessedTrackEntity, Boolean, ExportFormat) -> Intent,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exportModalForVocals by remember { mutableStateOf<Boolean?>(null) }
    var selectedExportFormat by remember { mutableStateOf(ExportFormat.WAV) }

    LaunchedEffect(track.id) {
        onLoadStems(track)
    }

    DisposableEffect(Unit) {
        onDispose { onPausePlayback() }
    }

    BackHandler {
        onPausePlayback()
        onBackHome()
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val isVocals = exportModalForVocals ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            onExportToUri(track, isVocals, selectedExportFormat, uri)
            exportModalForVocals = null
        }
    }

    val vocalsPeaks = remember(track.vocalsWaveformCsv) {
        WavUtils.csvToWaveform(track.vocalsWaveformCsv)
    }
    val instPeaks = remember(track.instrumentalWaveformCsv) {
        WavUtils.csvToWaveform(track.instrumentalWaveformCsv)
    }

    val durationMs = maxOf(track.durationMs, playbackState.durationMs, 1L)
    val progressFrac = (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        onPausePlayback()
                        onBackHome()
                    },
                    modifier = Modifier.testTag("results_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = strings.backToHome,
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = AudioMetadataHelper.formatDuration(durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.successAccent
                        )
                        Text(text = "•", color = studio.mutedText)
                        Text(
                            text = "${track.sampleRate / 1000.0} kHz",
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.mutedText
                        )
                        Text(text = "•", color = studio.mutedText)
                        Text(
                            text = track.engineUsed,
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.instrumentalAccent
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = studio.successAccent,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Master Synchronized Mix Controller
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, studio.borderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = strings.masterMixTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${AudioMetadataHelper.formatDuration(playbackState.positionMs)} / ${AudioMetadataHelper.formatDuration(durationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.mutedText
                            )
                        }

                        val isMixPlaying = playbackState.isPlaying &&
                            playbackState.activeTarget == ActivePlaybackTarget.DUAL_MIX

                        Button(
                            onClick = onToggleDualMix,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.testTag("play_mix_button")
                        ) {
                            Icon(
                                imageVector = if (isMixPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = if (isMixPlaying) strings.pauseBoth else strings.playBoth)
                        }
                    }

                    Slider(
                        value = playbackState.mixBalance,
                        onValueChange = onMixBalanceChange,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.onSurface,
                            activeTrackColor = studio.vocalAccent,
                            inactiveTrackColor = studio.instrumentalAccent
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.mixInstrumentalLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.instrumentalAccent
                        )
                        Text(
                            text = strings.mixBalancedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.mutedText
                        )
                        Text(
                            text = strings.mixVocalsLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.vocalAccent
                        )
                    }
                }
            }

            // Track 1: Vocals Card
            StemTrackCard(
                title = strings.vocalsTrackTitle,
                subtitle = strings.vocalsTrackSubtitle,
                icon = Icons.Rounded.Mic,
                accentColor = studio.vocalAccent,
                containerColor = studio.vocalContainer,
                peaks = vocalsPeaks,
                isPlaying = playbackState.isPlaying &&
                    playbackState.activeTarget == ActivePlaybackTarget.VOCALS_SOLO,
                positionMs = playbackState.positionMs,
                durationMs = durationMs,
                progressFraction = progressFrac,
                downloadLabel = strings.downloadBtn,
                shareLabel = strings.shareBtn,
                playButtonTag = "play_vocals_button",
                downloadButtonTag = "download_vocals_button",
                shareButtonTag = "share_vocals_button",
                onTogglePlay = onToggleVocals,
                onSeek = onSeek,
                onDownload = {
                    val quickFormat = if (track.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                    onExportToMusic(track, true, quickFormat)
                },
                onDownloadOptions = {
                    selectedExportFormat = if (track.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                    exportModalForVocals = true
                },
                onShare = {
                    scope.launch {
                        val intent = onBuildShareIntent(track, true, ExportFormat.WAV)
                        context.startActivity(Intent.createChooser(intent, strings.shareBtn))
                    }
                }
            )

            // Track 2: Instrumental Card
            StemTrackCard(
                title = strings.instrumentalTrackTitle,
                subtitle = strings.instrumentalTrackSubtitle,
                icon = Icons.Rounded.MusicNote,
                accentColor = studio.instrumentalAccent,
                containerColor = studio.instrumentalContainer,
                peaks = instPeaks,
                isPlaying = playbackState.isPlaying &&
                    playbackState.activeTarget == ActivePlaybackTarget.INSTRUMENTAL_SOLO,
                positionMs = playbackState.positionMs,
                durationMs = durationMs,
                progressFraction = progressFrac,
                downloadLabel = strings.downloadBtn,
                shareLabel = strings.shareBtn,
                playButtonTag = "play_instrumental_button",
                downloadButtonTag = "download_instrumental_button",
                shareButtonTag = "share_instrumental_button",
                onTogglePlay = onToggleInstrumental,
                onSeek = onSeek,
                onDownload = {
                    val quickFormat = if (track.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                    onExportToMusic(track, false, quickFormat)
                },
                onDownloadOptions = {
                    selectedExportFormat = if (track.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                    exportModalForVocals = false
                },
                onShare = {
                    scope.launch {
                        val intent = onBuildShareIntent(track, false, ExportFormat.WAV)
                        context.startActivity(Intent.createChooser(intent, strings.shareBtn))
                    }
                }
            )

            OutlinedButton(
                onClick = {
                    onPausePlayback()
                    onBackHome()
                },
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, studio.borderSubtle),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("separate_another_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = strings.separateAnotherBtn)
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // Export Options ModalBottomSheet
    val modalVocals = exportModalForVocals
    if (modalVocals != null) {
        ModalBottomSheet(
            onDismissRequest = { exportModalForVocals = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.exportSheetTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = if (modalVocals) strings.vocalsTrackTitle else strings.instrumentalTrackTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (modalVocals) studio.vocalAccent else studio.instrumentalAccent
                )

                FormatOptionRow(
                    title = strings.exportWavTitle,
                    description = strings.exportWavDesc,
                    selected = selectedExportFormat == ExportFormat.WAV,
                    onClick = { selectedExportFormat = ExportFormat.WAV }
                )

                FormatOptionRow(
                    title = strings.exportM4aTitle,
                    description = strings.exportM4aDesc,
                    selected = selectedExportFormat == ExportFormat.M4A,
                    onClick = { selectedExportFormat = ExportFormat.M4A }
                )

                if (track.isRemoteMp3Available) {
                    FormatOptionRow(
                        title = strings.exportMp3Title,
                        description = strings.exportMp3Desc,
                        selected = selectedExportFormat == ExportFormat.MP3,
                        onClick = { selectedExportFormat = ExportFormat.MP3 }
                    )
                }

                if (exportState is ExportUiState.Exporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = strings.exportInProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedText
                    )
                }

                Button(
                    onClick = {
                        onExportToMusic(track, modalVocals, selectedExportFormat)
                        exportModalForVocals = null
                    },
                    enabled = exportState !is ExportUiState.Exporting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_save_music_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.exportSaveMusicBtn)
                }

                OutlinedButton(
                    onClick = {
                        val suffix = if (modalVocals) "Vocals" else "Instrumental"
                        createDocLauncher.launch(
                            "${track.title}_$suffix.${selectedExportFormat.extension}"
                        )
                    },
                    enabled = exportState !is ExportUiState.Exporting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("export_save_as_button")
                ) {
                    Text(strings.exportSaveAsBtn)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun StemTrackCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    peaks: List<Float>,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    progressFraction: Float,
    downloadLabel: String,
    shareLabel: String,
    playButtonTag: String,
    downloadButtonTag: String,
    shareButtonTag: String,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit,
    onDownload: () -> Unit,
    onDownloadOptions: () -> Unit,
    onShare: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (isPlaying) 1.5.dp else 1.dp,
            color = if (isPlaying) accentColor else studio.borderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                        .clickable(onClick = onTogglePlay)
                        .testTag(playButtonTag),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = studio.mutedText
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = containerColor
                ) {
                    Text(
                        text = AudioMetadataHelper.formatDuration(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            InteractiveWaveform(
                peaks = peaks,
                progressFraction = progressFraction,
                activeColor = accentColor,
                inactiveColor = studio.borderSubtle,
                onSeek = onSeek
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${AudioMetadataHelper.formatDuration(positionMs)} / ${AudioMetadataHelper.formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = studio.mutedText
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, studio.borderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag(shareButtonTag)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = shareLabel,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = shareLabel, style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag(downloadButtonTag)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = downloadLabel,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = downloadLabel, style = MaterialTheme.typography.labelLarge)
                    }

                    IconButton(onClick = onDownloadOptions) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Export format options",
                            tint = studio.mutedText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) studio.instrumentalContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) studio.instrumentalAccent else studio.borderSubtle
        ),
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
