package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessedTrackDao {
    @Query("SELECT * FROM processed_tracks ORDER BY createdAtEpochMs DESC")
    fun observeAllTracks(): Flow<List<ProcessedTrackEntity>>

    @Query("SELECT * FROM processed_tracks WHERE id = :id LIMIT 1")
    fun observeTrackById(id: String): Flow<ProcessedTrackEntity?>

    @Query("SELECT * FROM processed_tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): ProcessedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: ProcessedTrackEntity)

    @Query("DELETE FROM processed_tracks WHERE id = :id")
    suspend fun deleteTrackById(id: String)

    @Query("DELETE FROM processed_tracks")
    suspend fun deleteAllTracks()
}
