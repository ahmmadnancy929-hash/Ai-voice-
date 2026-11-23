package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EnhancedVoiceDao {
    @Query("SELECT * FROM enhanced_voices ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<EnhancedVoiceEntity>>

    @Query("SELECT * FROM enhanced_voices WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<EnhancedVoiceEntity?>

    @Query("SELECT * FROM enhanced_voices WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EnhancedVoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EnhancedVoiceEntity)

    @Query("DELETE FROM enhanced_voices WHERE id = :id")
    suspend fun deleteById(id: String)
}
