package com.example.data.repository

import android.content.Context
import com.example.data.local.ProcessedTrackDao
import com.example.data.local.ProcessedTrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class TrackRepository(
    private val context: Context,
    private val dao: ProcessedTrackDao
) {
    val allTracks: Flow<List<ProcessedTrackEntity>> = dao.observeAllTracks()

    fun observeTrack(id: String): Flow<ProcessedTrackEntity?> = dao.observeTrackById(id)

    suspend fun getTrack(id: String): ProcessedTrackEntity? = dao.getTrackById(id)

    suspend fun saveTrack(track: ProcessedTrackEntity) {
        dao.insertTrack(track)
    }

    suspend fun deleteTrack(id: String) {
        val existing = dao.getTrackById(id)
        dao.deleteTrackById(id)
        withContext(Dispatchers.IO) {
            existing?.let { track ->
                File(track.vocalsPath).parentFile?.let { dir ->
                    if (dir.exists() && dir.name == track.id) {
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }

    suspend fun cleanTempFiles() {
        withContext(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "vocal_temp")
            if (tempDir.exists()) tempDir.deleteRecursively()
            val exportsDir = File(context.cacheDir, "exports")
            if (exportsDir.exists()) exportsDir.deleteRecursively()
        }
    }

    suspend fun calculateStorageUsageBytes(): Long = withContext(Dispatchers.IO) {
        val stemsDir = File(context.filesDir, "stems")
        val voiceDir = File(context.filesDir, "voice_enhancer")
        val tempDir = File(context.cacheDir, "vocal_temp")
        val exportsDir = File(context.cacheDir, "exports")
        directorySize(stemsDir) + directorySize(voiceDir) + directorySize(tempDir) + directorySize(exportsDir)
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
