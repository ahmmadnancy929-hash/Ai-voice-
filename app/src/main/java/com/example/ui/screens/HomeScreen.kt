package com.example.ui.screens

import android.content.ClipDescription
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.AudioMetadataHelper
import com.example.data.local.ProcessedTrackEntity
import com.example.data.prefs.ProcessingEngineMode
import com.example.data.prefs.UserPreferences
import com.example.i18n.AppStrings
import com.example.ui.theme.LocalStudioPalette

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    strings: AppStrings,
    preferences: UserPreferences,
    recentTracks: List<ProcessedTrackEntity>,
    onUploadAudio: (Uri) -> Unit,
    onTryDemo: () -> Unit,
    onOpenTrack: (String) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studio = LocalStudioPalette.current
    var isDragHover by remember { mutableStateOf(false) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onUploadAudio(uri)
        }
    }

    val dragTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDragHover = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDragHover = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDragHover = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDragHover = false
                val clipData = event.toAndroidDragEvent().clipData ?: return false
                if (clipData.itemCount > 0) {
                    val droppedUri = clipData.getItemAt(0).uri
                    if (droppedUri != null) {
                        onUploadAudio(droppedUri)
                        return true
                    }
                }
                return false
            }
        }
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
            // Top Studio Bar
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
                                        listOf(studio.instrumentalAccent, studio.vocalAccent)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = strings.appTitle,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (preferences.engineMode == ProcessingEngineMode.REMOTE_DEMUCS) {
                                    strings.engineRemoteDemucs
                                } else {
                                    strings.engineOnDevice
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.instrumentalAccent
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
                            border = BorderStroke(1.dp, studio.borderSubtle),
                            modifier = Modifier.testTag("language_toggle_button")
                        ) {
                            Text(
                                text = preferences.language.displayLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        IconButton(
                            onClick = onToggleTheme,
                            modifier = Modifier.testTag("theme_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (studio.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                                contentDescription = strings.themeLabel,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = strings.settingsTitle,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Hero Upload Card (with Drag-and-Drop target + studio hero art)
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(
                        width = if (isDragHover) 2.dp else 1.dp,
                        color = if (isDragHover) studio.instrumentalAccent else studio.borderSubtle
                    ),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = { event ->
                                event.mimeTypes().any {
                                    it.startsWith("audio/") ||
                                        it == ClipDescription.MIMETYPE_TEXT_URILIST
                                }
                            },
                            target = dragTarget
                        )
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            painter = painterResource(id = R.drawable.img_studio_hero),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = if (studio.isDark) 0.36f else 0.14f,
                            modifier = Modifier.matchParentSize()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                                        )
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = studio.instrumentalContainer.copy(alpha = 0.9f),
                                border = BorderStroke(1.dp, studio.instrumentalAccent.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AllInclusive,
                                        contentDescription = null,
                                        tint = studio.instrumentalAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = strings.studioBadge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = studio.instrumentalAccent
                                    )
                                }
                            }

                            Text(
                                text = strings.heroTitle,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = strings.heroSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = studio.mutedText
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    audioPickerLauncher.launch(
                                        arrayOf(
                                            "audio/*",
                                            "application/ogg",
                                            "audio/mpeg",
                                            "audio/wav",
                                            "audio/x-wav",
                                            "audio/mp4",
                                            "audio/aac",
                                            "audio/flac"
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                contentPadding = PaddingValues(vertical = 15.dp, horizontal = 20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("upload_audio_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = strings.uploadAudioBtn,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            OutlinedButton(
                                onClick = onTryDemo,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, studio.borderSubtle),
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("try_demo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = studio.vocalAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.tryDemoBtn,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = strings.dropHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = studio.mutedText
                            )
                        }
                    }
                }
            }

            // Supported Formats & Unlimited Capacity Strip
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
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
                            Text(
                                text = strings.supportedFormatsTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strings.unlimitedNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = studio.successAccent
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("MP3", "WAV", "M4A", "AAC", "FLAC", "OGG").forEach { fmt ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, studio.borderSubtle)
                                ) {
                                    Text(
                                        text = fmt,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recently Processed Section Header
            item {
                Text(
                    text = strings.recentlyProcessedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (recentTracks.isEmpty()) {
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
                                .padding(vertical = 28.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = null,
                                    tint = studio.instrumentalAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = strings.emptyRecentsTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strings.emptyRecentsSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = studio.mutedText
                            )
                        }
                    }
                }
            } else {
                items(recentTracks, key = { it.id }) { track ->
                    RecentTrackCard(
                        track = track,
                        strings = strings,
                        onClick = { onOpenTrack(track.id) },
                        onDelete = { onDeleteTrack(track.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: ProcessedTrackEntity,
    strings: AppStrings,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val studio = LocalStudioPalette.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, studio.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("recent_track_${track.id}")
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
                    .background(studio.instrumentalContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AudioFile,
                    contentDescription = null,
                    tint = studio.instrumentalAccent,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = AudioMetadataHelper.formatDuration(track.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = studio.instrumentalAccent
                    )
                    Text(
                        text = "•",
                        color = studio.mutedText
                    )
                    Text(
                        text = track.sourceFormat,
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.mutedText
                    )
                    Text(
                        text = "•",
                        color = studio.mutedText
                    )
                    Text(
                        text = track.engineUsed,
                        style = MaterialTheme.typography.labelSmall,
                        color = studio.vocalAccent
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_track_${track.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = strings.deleteTrack,
                    tint = studio.mutedText
                )
            }
        }
    }
}
