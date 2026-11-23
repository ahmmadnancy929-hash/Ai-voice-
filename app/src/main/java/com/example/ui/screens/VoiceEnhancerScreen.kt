package com.example.ui.screens

import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.ActivePlaybackTarget
import com.example.audio.AudioMetadataHelper
import com.example.audio.EnhancementStrength
import com.example.audio.ExportFormat
import com.example.audio.PlaybackState
import com.example.audio.VoiceEnhanceStage
import com.example.audio.WavUtils
import com.example.data.local.EnhancedVoiceEntity
import com.example.data.prefs.UserPreferences
import com.example.i18n.AppStrings
import com.example.ui.ExportUiState
import com.example.ui.StagedVoiceInput
import com.example.ui.VoiceEnhanceUiState
import com.example.ui.components.InteractiveWaveform
import com.example.ui.components.ProcessingOrbVisualizer
import com.example.ui.theme.LocalStudioPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun VoiceEnhancerScreen(
    strings: AppStrings,
    preferences: UserPreferences,
    stagedInput: StagedVoiceInput?,
    selectedStrength: EnhancementStrength,
    processingState: VoiceEnhanceUiState,
    activeResult: EnhancedVoiceEntity?,
    history: List<EnhancedVoiceEntity>,
    playbackState: PlaybackState,
    exportState: ExportUiState,
    onStageMediaUri: (Uri) -> Unit,
    onTryNoisyDemo: () -> Unit,
    onSelectStrength: (EnhancementStrength) -> Unit,
    onStartEnhancement: () -> Unit,
    onCancelEnhancement: () -> Unit,
    onRetryEnhancement: () -> Unit,
    onClearStagedOrResult: () -> Unit,
    onOpenResult: (String) -> Unit,
    onDeleteResult: (String) -> Unit,
    onLoadComparison: (EnhancedVoiceEntity) -> Unit,
    onToggleOriginal: () -> Unit,
    onToggleEnhanced: () -> Unit,
    onSwitchBeforeAfter: (Boolean) -> Unit,
    onSeek: (Float) -> Unit,
    onPausePlayback: () -> Unit,
    onExportToMusic: (EnhancedVoiceEntity, ExportFormat) -> Unit,
    onExportToUri: (EnhancedVoiceEntity, ExportFormat, Uri) -> Unit,
    onBuildShareIntent: suspend (EnhancedVoiceEntity, ExportFormat) -> Intent,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isDragHover by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var selectedExportFormat by remember { mutableStateOf(ExportFormat.WAV) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onStageMediaUri(uri)
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val res = activeResult ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            onExportToUri(res, selectedExportFormat, uri)
            showExportSheet = false
        }
    }

    val dragTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { isDragHover = true }
            override fun onExited(event: DragAndDropEvent) { isDragHover = false }
            override fun onEnded(event: DragAndDropEvent) { isDragHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragHover = false
                val clip = event.toAndroidDragEvent().clipData ?: return false
                if (clip.itemCount > 0) {
                    clip.getItemAt(0).uri?.let {
                        onStageMediaUri(it)
                        return true
                    }
                }
                return false
            }
        }
    }

    LaunchedEffect(activeResult?.id) {
        if (activeResult != null) {
            onLoadComparison(activeResult)
        }
    }

    DisposableEffect(Unit) {
        onDispose { onPausePlayback() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Studio Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(studio.successAccent, studio.instrumentalAccent)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = strings.navVoiceEnhancer,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = strings.navVoiceEnhancer,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = strings.veBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.successAccent
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            onClick = onToggleLanguage,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, studio.borderSubtle)
                        ) {
                            Text(
                                text = preferences.language.displayLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                        IconButton(onClick = onToggleTheme) {
                            Icon(
                                imageVector = if (studio.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = strings.themeLabel,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = strings.settingsTitle,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Main Interactive Section: Processing vs Active Comparison vs Upload & Strength Studio
            when {
                processingState is VoiceEnhanceUiState.Running -> {
                    item {
                        VoiceProcessingCard(
                            state = processingState,
                            strings = strings,
                            onCancel = onCancelEnhancement
                        )
                    }
                }

                processingState is VoiceEnhanceUiState.Error -> {
                    item {
                        VoiceErrorCard(
                            state = processingState,
                            strings = strings,
                            onRetry = onRetryEnhancement,
                            onReset = onClearStagedOrResult
                        )
                    }
                }

                activeResult != null -> {
                    item {
                        VoiceComparisonSection(
                            entity = activeResult,
                            strings = strings,
                            playbackState = playbackState,
                            onToggleOriginal = onToggleOriginal,
                            onToggleEnhanced = onToggleEnhanced,
                            onSwitchBeforeAfter = onSwitchBeforeAfter,
                            onSeek = onSeek,
                            onDownload = {
                                val quickFormat = if (activeResult.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                                onExportToMusic(activeResult, quickFormat)
                            },
                            onDownloadOptions = {
                                selectedExportFormat = if (activeResult.isRemoteMp3Available) ExportFormat.MP3 else ExportFormat.WAV
                                showExportSheet = true
                            },
                            onShare = {
                                scope.launch {
                                    val intent = onBuildShareIntent(activeResult, ExportFormat.WAV)
                                    context.startActivity(Intent.createChooser(intent, strings.shareBtn))
                                }
                            },
                            onEnhanceAnother = onClearStagedOrResult
                        )
                    }
                }

                else -> {
                    // Upload & Drag-and-Drop Card
                    item {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(
                                width = if (isDragHover) 2.dp else 1.dp,
                                color = if (isDragHover) studio.successAccent else studio.borderSubtle
                            ),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .dragAndDropTarget(
                                    shouldStartDragAndDrop = { event ->
                                        event.mimeTypes().any {
                                            it.startsWith("audio/") ||
                                                it.startsWith("video/") ||
                                                it == ClipDescription.MIMETYPE_TEXT_URILIST
                                        }
                                    },
                                    target = dragTarget
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                studio.successAccent.copy(alpha = if (studio.isDark) 0.10f else 0.06f),
                                                MaterialTheme.colorScheme.surface
                                            )
                                        )
                                    )
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = strings.veHeroTitle,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = strings.veHeroSubtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = studio.mutedText
                                )

                                // Noise/Defect capabilities pill row
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "De-Noise", "De-Reverb", "De-Muffle",
                                        "Fan & Wind", "Traffic", "Auto-Level"
                                    ).forEach { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, studio.borderSubtle)
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = studio.successAccent,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        mediaPicker.launch(
                                            arrayOf("audio/*", "video/*", "application/ogg")
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = studio.successAccent,
                                        contentColor = Color(0xFF002419)
                                    ),
                                    contentPadding = PaddingValues(vertical = 15.dp, horizontal = 20.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ve_upload_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.UploadFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = strings.veUploadBtn,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }

                                OutlinedButton(
                                    onClick = onTryNoisyDemo,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, studio.borderSubtle),
                                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ve_demo_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Mic,
                                        contentDescription = null,
                                        tint = studio.instrumentalAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = strings.veDemoBtn,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = strings.veDropHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = studio.mutedText
                                )
                            }
                        }
                    }

                    // Staged File + Strength Control + Start Enhancement Card
                    if (stagedInput != null) {
                        item {
                            StagedEnhancementControlCard(
                                staged = stagedInput,
                                selectedStrength = selectedStrength,
                                strings = strings,
                                onSelectStrength = onSelectStrength,
                                onStartEnhancement = onStartEnhancement,
                                onClear = onClearStagedOrResult
                            )
                        }
                    }
                }
            }

            // Recent Enhanced Voices History
            item {
                Text(
                    text = strings.veRecentTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (history.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, studio.borderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 26.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = studio.successAccent,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Text(
                                text = strings.veEmptyTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strings.veEmptySubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = studio.mutedText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(history, key = { it.id }) { item ->
                    EnhancedHistoryCard(
                        item = item,
                        strings = strings,
                        onOpen = { onOpenResult(item.id) },
                        onDelete = { onDeleteResult(item.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showExportSheet && activeResult != null) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.veDownloadBtn,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format(
                        strings.veDownloadOriginalRateNote,
                        "${activeResult.sampleRate / 1000.0} kHz"
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = studio.successAccent
                )

                VoiceExportFormatRow(
                    title = strings.exportWavTitle,
                    description = strings.exportWavDesc,
                    selected = selectedExportFormat == ExportFormat.WAV,
                    onClick = { selectedExportFormat = ExportFormat.WAV }
                )

                VoiceExportFormatRow(
                    title = strings.exportM4aTitle,
                    description = strings.exportM4aDesc,
                    selected = selectedExportFormat == ExportFormat.M4A,
                    onClick = { selectedExportFormat = ExportFormat.M4A }
                )

                if (activeResult.isRemoteMp3Available) {
                    VoiceExportFormatRow(
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
                        onExportToMusic(activeResult, selectedExportFormat)
                        showExportSheet = false
                    },
                    enabled = exportState !is ExportUiState.Exporting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ve_save_music_button")
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.exportSaveMusicBtn)
                }

                OutlinedButton(
                    onClick = {
                        createDocLauncher.launch(
                            "${activeResult.title}_Enhanced.${selectedExportFormat.extension}"
                        )
                    },
                    enabled = exportState !is ExportUiState.Exporting,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ve_save_as_button")
                ) {
                    Text(strings.exportSaveAsBtn)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StagedEnhancementControlCard(
    staged: StagedVoiceInput,
    selectedStrength: EnhancementStrength,
    strings: AppStrings,
    onSelectStrength: (EnhancementStrength) -> Unit,
    onStartEnhancement: () -> Unit,
    onClear: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, studio.successAccent.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.veSelectedFileLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.successAccent
                    )
                    Text(
                        text = staged.metadata.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = staged.metadata.formatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.instrumentalAccent
                        )
                        if (staged.metadata.durationMs > 0L) {
                            Text(
                                text = "• ${AudioMetadataHelper.formatDuration(staged.metadata.durationMs)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.mutedText
                            )
                        }
                        if (staged.metadata.sizeBytes > 0L) {
                            Text(
                                text = "• ${AudioMetadataHelper.formatFileSize(staged.metadata.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.mutedText
                            )
                        }
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = strings.cancelProcessing,
                        tint = studio.mutedText
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.veStrengthTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StrengthChip(
                        label = strings.veStrengthLight,
                        selected = selectedStrength == EnhancementStrength.LIGHT,
                        onClick = { onSelectStrength(EnhancementStrength.LIGHT) }
                    )
                    StrengthChip(
                        label = strings.veStrengthMedium,
                        selected = selectedStrength == EnhancementStrength.MEDIUM,
                        onClick = { onSelectStrength(EnhancementStrength.MEDIUM) }
                    )
                    StrengthChip(
                        label = strings.veStrengthStrong,
                        selected = selectedStrength == EnhancementStrength.STRONG,
                        onClick = { onSelectStrength(EnhancementStrength.STRONG) }
                    )
                    StrengthChip(
                        label = strings.veStrengthFullStudio,
                        selected = selectedStrength == EnhancementStrength.FULL_STUDIO,
                        onClick = { onSelectStrength(EnhancementStrength.FULL_STUDIO) }
                    )
                }

                Slider(
                    value = selectedStrength.level.toFloat(),
                    onValueChange = { raw ->
                        val lvl = raw.roundToInt().coerceIn(0, 3)
                        onSelectStrength(EnhancementStrength.entries.first { it.level == lvl })
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    colors = SliderDefaults.colors(
                        thumbColor = studio.successAccent,
                        activeTrackColor = studio.successAccent
                    ),
                    modifier = Modifier.testTag("ve_strength_slider")
                )

                val desc = when (selectedStrength) {
                    EnhancementStrength.LIGHT -> strings.veStrengthDescLight
                    EnhancementStrength.MEDIUM -> strings.veStrengthDescMedium
                    EnhancementStrength.STRONG -> strings.veStrengthDescStrong
                    EnhancementStrength.FULL_STUDIO -> strings.veStrengthDescFull
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.mutedText
                )
            }

            Button(
                onClick = onStartEnhancement,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 15.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ve_start_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.veStartBtn,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun StrengthChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun VoiceProcessingCard(
    state: VoiceEnhanceUiState.Running,
    strings: AppStrings,
    onCancel: () -> Unit
) {
    val studio = LocalStudioPalette.current
    val stageLabel = when (state.stage) {
        VoiceEnhanceStage.PREPARING -> strings.stagePreparing
        VoiceEnhanceStage.EXTRACTING_AUDIO -> strings.veStageExtracting
        VoiceEnhanceStage.DENOISING_AND_DEREVERB -> strings.veStageDenoising
        VoiceEnhanceStage.STUDIO_MASTERING -> strings.veStageMastering
        VoiceEnhanceStage.FINALIZING -> strings.stageFinalizing
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, studio.borderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = state.fileName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            ProcessingOrbVisualizer(progressPercent = state.percent)

            Text(
                text = stageLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            LinearProgressIndicator(
                progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                color = studio.successAccent,
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
                    text = String.format(strings.chunkProgressFormat, state.currentChunk, state.totalChunks),
                    style = MaterialTheme.typography.labelMedium,
                    color = studio.mutedText
                )
                Text(
                    text = "${state.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = studio.successAccent
                )
            }

            Text(
                text = strings.veBackgroundNote,
                style = MaterialTheme.typography.bodySmall,
                color = studio.mutedText,
                textAlign = TextAlign.Center
            )

            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ve_cancel_button")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.cancelProcessing, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun VoiceErrorCard(
    state: VoiceEnhanceUiState.Error,
    strings: AppStrings,
    onRetry: () -> Unit,
    onReset: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = strings.processingErrorTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (state.isStorageError) strings.insufficientStorageError else state.detailMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = studio.mutedText,
                textAlign = TextAlign.Center
            )
            if (state.canRetry) {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.retryProcessing)
                }
            }
            OutlinedButton(
                onClick = onReset,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.veResetUpload)
            }
        }
    }
}

@Composable
private fun VoiceComparisonSection(
    entity: EnhancedVoiceEntity,
    strings: AppStrings,
    playbackState: PlaybackState,
    onToggleOriginal: () -> Unit,
    onToggleEnhanced: () -> Unit,
    onSwitchBeforeAfter: (Boolean) -> Unit,
    onSeek: (Float) -> Unit,
    onDownload: () -> Unit,
    onDownloadOptions: () -> Unit,
    onShare: () -> Unit,
    onEnhanceAnother: () -> Unit
) {
    val studio = LocalStudioPalette.current
    val origPeaks = remember(entity.originalWaveformCsv) { WavUtils.csvToWaveform(entity.originalWaveformCsv) }
    val enhPeaks = remember(entity.enhancedWaveformCsv) { WavUtils.csvToWaveform(entity.enhancedWaveformCsv) }

    val durationMs = maxOf(entity.durationMs, playbackState.durationMs, 1L)
    val progressFrac = (playbackState.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val isEnhancedSelected = playbackState.activeTarget != ActivePlaybackTarget.VOCALS_SOLO

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Comparison Header + Quick Live Before/After A/B Switcher
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, studio.borderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entity.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = AudioMetadataHelper.formatDuration(durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = studio.successAccent
                            )
                            Text(text = "•", color = studio.mutedText)
                            Text(
                                text = "${entity.sampleRate / 1000.0} kHz",
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.mutedText
                            )
                            Text(text = "•", color = studio.mutedText)
                            Text(
                                text = entity.engineUsed,
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

                // Live Before / After Toggle Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onSwitchBeforeAfter(false) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (!isEnhancedSelected) studio.vocalContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (!isEnhancedSelected) studio.vocalAccent else studio.borderSubtle
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ve_before_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.veOriginalTab,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (!isEnhancedSelected) studio.vocalAccent else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CompareArrows,
                        contentDescription = strings.veComparisonTitle,
                        tint = studio.mutedText,
                        modifier = Modifier.size(20.dp)
                    )

                    Surface(
                        onClick = { onSwitchBeforeAfter(true) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isEnhancedSelected) studio.instrumentalContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (isEnhancedSelected) studio.successAccent else studio.borderSubtle
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ve_after_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = strings.veEnhancedTab,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isEnhancedSelected) studio.successAccent else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Text(
                    text = strings.veBeforeAfterHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = studio.mutedText
                )
            }
        }

        // Player 1: Original Audio Player
        VoiceTrackPlayerCard(
            title = strings.veOriginalPlayerTitle,
            accentColor = studio.vocalAccent,
            peaks = origPeaks,
            isPlaying = playbackState.isPlaying &&
                playbackState.activeTarget == ActivePlaybackTarget.VOCALS_SOLO,
            positionMs = playbackState.positionMs,
            durationMs = durationMs,
            progressFraction = progressFrac,
            playButtonTag = "ve_play_original_button",
            onTogglePlay = onToggleOriginal,
            onSeek = onSeek
        )

        // Player 2: Enhanced Studio Audio Player + Download & Share
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(
                width = if (playbackState.isPlaying && playbackState.activeTarget == ActivePlaybackTarget.INSTRUMENTAL_SOLO) 1.5.dp else 1.dp,
                color = if (playbackState.isPlaying && playbackState.activeTarget == ActivePlaybackTarget.INSTRUMENTAL_SOLO) studio.successAccent else studio.borderSubtle
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val enhPlaying = playbackState.isPlaying &&
                    playbackState.activeTarget == ActivePlaybackTarget.INSTRUMENTAL_SOLO
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(studio.successAccent)
                            .clickable(onClick = onToggleEnhanced)
                            .testTag("ve_play_enhanced_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (enhPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = strings.veEnhancedPlayerTitle,
                            tint = Color(0xFF002419),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.veEnhancedPlayerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${AudioMetadataHelper.formatDuration(playbackState.positionMs)} / ${AudioMetadataHelper.formatDuration(durationMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = studio.mutedText
                        )
                    }
                }

                InteractiveWaveform(
                    peaks = enhPeaks,
                    progressFraction = progressFrac,
                    activeColor = studio.successAccent,
                    inactiveColor = studio.borderSubtle,
                    onSeek = onSeek
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("ve_share_button")
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = strings.shareBtn, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.shareBtn)
                    }

                    Button(
                        onClick = onDownload,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = studio.successAccent,
                            contentColor = Color(0xFF002419)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ve_download_button")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.veDownloadBtn,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDownloadOptions,
                        modifier = Modifier.testTag("ve_download_options_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = strings.exportSheetTitle,
                            tint = studio.mutedText
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onEnhanceAnother,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ve_enhance_another_button")
        ) {
            Text(strings.veResetUpload)
        }
    }
}

@Composable
private fun VoiceTrackPlayerCard(
    title: String,
    accentColor: Color,
    peaks: List<Float>,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    progressFraction: Float,
    playButtonTag: String,
    onTogglePlay: () -> Unit,
    onSeek: (Float) -> Unit
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${AudioMetadataHelper.formatDuration(positionMs)} / ${AudioMetadataHelper.formatDuration(durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.mutedText
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
        }
    }
}

@Composable
private fun EnhancedHistoryCard(
    item: EnhancedVoiceEntity,
    strings: AppStrings,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, studio.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(studio.successAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = studio.successAccent,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = AudioMetadataHelper.formatDuration(item.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.successAccent
                    )
                    Text(text = "•", color = studio.mutedText)
                    Text(
                        text = item.sourceFormat,
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.mutedText
                    )
                    Text(text = "•", color = studio.mutedText)
                    Text(
                        text = item.strengthName.replace('_', ' '),
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.instrumentalAccent
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = strings.deleteTrack,
                    tint = studio.mutedText
                )
            }
        }
    }
}

@Composable
private fun VoiceExportFormatRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) studio.successAccent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) studio.successAccent else studio.borderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = studio.mutedText)
            }
        }
    }
}
