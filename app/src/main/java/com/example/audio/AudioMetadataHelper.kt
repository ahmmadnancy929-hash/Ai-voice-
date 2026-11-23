package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class AudioSourceMetadata(
    val title: String,
    val formatLabel: String,
    val sizeBytes: Long,
    val durationMs: Long
)

object AudioMetadataHelper {

    suspend fun inspect(context: Context, uri: Uri): AudioSourceMetadata = withContext(Dispatchers.IO) {
        var displayName = "Audio_Track"
        var sizeBytes = 0L

        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            displayName = file.name
            sizeBytes = file.length()
        } else {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        }

        val ext = displayName.substringAfterLast('.', "").uppercase(Locale.US)
            .ifBlank {
                val mime = context.contentResolver.getType(uri) ?: ""
                when {
                    mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                    mime.contains("wav") -> "WAV"
                    mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "M4A"
                    mime.contains("flac") -> "FLAC"
                    mime.contains("ogg") -> "OGG"
                    else -> "AUDIO"
                }
            }

        val cleanTitle = displayName.substringBeforeLast('.').ifBlank { displayName }

        var durationMs = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            durationMs = 0L
        } finally {
            runCatching { retriever.release() }
        }

        AudioSourceMetadata(
            title = cleanTitle,
            formatLabel = ext,
            sizeBytes = sizeBytes,
            durationMs = durationMs
        )
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
