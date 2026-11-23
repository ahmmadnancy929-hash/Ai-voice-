package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "enhanced_voices")
data class EnhancedVoiceEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceFormat: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val fileSizeBytes: Long,
    val strengthName: String,
    val originalPath: String,
    val enhancedPath: String,
    val originalWaveformCsv: String,
    val enhancedWaveformCsv: String,
    val engineUsed: String,
    val isRemoteMp3Available: Boolean = false,
    val remoteEnhancedMp3Path: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
