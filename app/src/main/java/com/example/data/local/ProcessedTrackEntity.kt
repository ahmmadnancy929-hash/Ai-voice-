package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_tracks")
data class ProcessedTrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceFormat: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val fileSizeBytes: Long,
    val vocalsPath: String,
    val instrumentalPath: String,
    val vocalsWaveformCsv: String,
    val instrumentalWaveformCsv: String,
    val engineUsed: String,
    val isRemoteMp3Available: Boolean = false,
    val remoteVocalsMp3Path: String? = null,
    val remoteInstrumentalMp3Path: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
