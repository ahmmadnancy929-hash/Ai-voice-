package com.example.data.repository

import com.example.data.local.EnhancedVoiceDao
import com.example.data.local.EnhancedVoiceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class VoiceEnhancerRepository(
    private val dao: EnhancedVoiceDao
) {
    val allEnhancedVoices: Flow<List<EnhancedVoiceEntity>> = dao.observeAll()

    fun observeVoice(id: String): Flow<EnhancedVoiceEntity?> = dao.observeById(id)

    suspend fun saveVoice(entity: EnhancedVoiceEntity) {
        dao.insert(entity)
    }

    suspend fun deleteVoice(id: String) {
        val existing = dao.getById(id)
        dao.deleteById(id)
        withContext(Dispatchers.IO) {
            existing?.let { item ->
                File(item.enhancedPath).parentFile?.let { dir ->
                    if (dir.exists() && dir.name == item.id) {
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }
}
