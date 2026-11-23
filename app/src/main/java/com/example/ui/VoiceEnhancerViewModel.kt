package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioExporter
import com.example.audio.AudioMetadataHelper
import com.example.audio.AudioSourceMetadata
import com.example.audio.ChunkedVoiceEnhancer
import com.example.audio.DemoVoiceGenerator
import com.example.audio.DualStemAudioPlayer
import com.example.audio.EnhancementStrength
import com.example.audio.ExportFormat
import com.example.audio.InsufficientStorageException
import com.example.audio.PlaybackState
import com.example.audio.VoiceEnhanceStage
import com.example.audio.WavUtils
import com.example.data.local.AppDatabase
import com.example.data.local.EnhancedVoiceEntity
import com.example.data.prefs.ProcessingEngineMode
import com.example.data.prefs.UserPreferences
import com.example.data.prefs.UserPreferencesRepository
import com.example.data.repository.VoiceEnhancerRepository
import com.example.network.RemoteServerResourceException
import com.example.network.RemoteVoiceEnhancerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class StagedVoiceInput(
    val uri: Uri,
    val metadata: AudioSourceMetadata
)

sealed interface VoiceEnhanceUiState {
    data object Idle : VoiceEnhanceUiState

    data class Running(
        val jobId: String,
        val fileName: String,
        val formatLabel: String,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val strength: EnhancementStrength,
        val percent: Int,
        val currentChunk: Int,
        val totalChunks: Int,
        val stage: VoiceEnhanceStage,
        val isRemote: Boolean
    ) : VoiceEnhanceUiState

    data class Error(
        val fileName: String,
        val isStorageError: Boolean,
        val detailMessage: String,
        val canRetry: Boolean
    ) : VoiceEnhanceUiState
}

class VoiceEnhancerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val repo = VoiceEnhancerRepository(db.enhancedVoiceDao())
    private val enhancer = ChunkedVoiceEnhancer(application)
    private val remoteClient = RemoteVoiceEnhancerClient(application)
    private val exporter = AudioExporter(application)
    private val comparisonPlayer = DualStemAudioPlayer(viewModelScope)

    val preferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferences()
    )

    val enhancedHistory: StateFlow<List<EnhancedVoiceEntity>> = repo.allEnhancedVoices.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val playbackState: StateFlow<PlaybackState> = comparisonPlayer.state

    private val _stagedInput = MutableStateFlow<StagedVoiceInput?>(null)
    val stagedInput: StateFlow<StagedVoiceInput?> = _stagedInput.asStateFlow()

    private val _selectedStrength = MutableStateFlow(EnhancementStrength.STRONG)
    val selectedStrength: StateFlow<EnhancementStrength> = _selectedStrength.asStateFlow()

    private val _processingState = MutableStateFlow<VoiceEnhanceUiState>(VoiceEnhanceUiState.Idle)
    val processingState: StateFlow<VoiceEnhanceUiState> = _processingState.asStateFlow()

    private val _activeResultId = MutableStateFlow<String?>(null)
    val activeResultId: StateFlow<String?> = _activeResultId.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>()
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var activeJob: Job? = null
    private var lastAttemptedInput: StagedVoiceInput? = null

    fun selectStrength(strength: EnhancementStrength) {
        _selectedStrength.value = strength
    }

    fun stageMediaUri(uri: Uri) {
        viewModelScope.launch {
            val meta = runCatching {
                AudioMetadataHelper.inspect(getApplication(), uri)
            }.getOrElse {
                AudioSourceMetadata("Voice_Recording", "AUDIO", 0L, 0L)
            }
            comparisonPlayer.pause()
            _activeResultId.value = null
            _processingState.value = VoiceEnhanceUiState.Idle
            _stagedInput.value = StagedVoiceInput(uri, meta)
        }
    }

    fun stageNoisyDemo() {
        viewModelScope.launch {
            val demoUri = DemoVoiceGenerator.createNoisyVoiceDemoUri(getApplication())
            stageMediaUri(demoUri)
        }
    }

    fun clearStagedAndResult() {
        comparisonPlayer.pause()
        _stagedInput.value = null
        _activeResultId.value = null
        _processingState.value = VoiceEnhanceUiState.Idle
    }

    fun startEnhancement() {
        val staged = _stagedInput.value ?: return
        val strength = _selectedStrength.value
        lastAttemptedInput = staged
        activeJob?.cancel()
        comparisonPlayer.pause()
        _activeResultId.value = null

        activeJob = viewModelScope.launch {
            val jobId = UUID.randomUUID().toString().take(12)
            val prefs = preferences.value
            val isRemote = prefs.engineMode == ProcessingEngineMode.REMOTE_DEMUCS
            val meta = staged.metadata

            _processingState.value = VoiceEnhanceUiState.Running(
                jobId = jobId,
                fileName = meta.title,
                formatLabel = meta.formatLabel,
                fileSizeBytes = meta.sizeBytes,
                durationMs = meta.durationMs,
                strength = strength,
                percent = 2,
                currentChunk = 0,
                totalChunks = 1,
                stage = VoiceEnhanceStage.PREPARING,
                isRemote = isRemote
            )

            try {
                val output = if (isRemote) {
                    remoteClient.enhanceRemote(
                        localJobId = jobId,
                        baseUrl = prefs.remoteServerUrl,
                        sourceUri = staged.uri,
                        fileName = "${meta.title}.${meta.formatLabel.lowercase()}",
                        fileSizeBytes = meta.sizeBytes,
                        strength = strength
                    ) { prog ->
                        _processingState.value = VoiceEnhanceUiState.Running(
                            jobId = jobId,
                            fileName = meta.title,
                            formatLabel = meta.formatLabel,
                            fileSizeBytes = meta.sizeBytes,
                            durationMs = if (prog.totalDurationMs > 0) prog.totalDurationMs else meta.durationMs,
                            strength = strength,
                            percent = prog.percent,
                            currentChunk = prog.currentChunk,
                            totalChunks = prog.totalChunks,
                            stage = prog.stage,
                            isRemote = true
                        )
                    }
                } else {
                    enhancer.enhance(
                        jobId = jobId,
                        sourceUri = staged.uri,
                        sourceFileSizeBytes = meta.sizeBytes,
                        strength = strength
                    ) { prog ->
                        _processingState.value = VoiceEnhanceUiState.Running(
                            jobId = jobId,
                            fileName = meta.title,
                            formatLabel = meta.formatLabel,
                            fileSizeBytes = meta.sizeBytes,
                            durationMs = if (prog.totalDurationMs > 0) prog.totalDurationMs else meta.durationMs,
                            strength = strength,
                            percent = prog.percent,
                            currentChunk = prog.currentChunk,
                            totalChunks = prog.totalChunks,
                            stage = prog.stage,
                            isRemote = false
                        )
                    }
                }

                val remoteMp3 = File(output.enhancedWavFile.parentFile, "enhanced.mp3")
                val hasRemoteMp3 = remoteMp3.exists()

                val entity = EnhancedVoiceEntity(
                    id = jobId,
                    title = meta.title,
                    sourceFormat = meta.formatLabel,
                    durationMs = if (output.durationMs > 0L) output.durationMs else meta.durationMs,
                    sampleRate = output.sampleRate,
                    channels = output.channels,
                    fileSizeBytes = meta.sizeBytes,
                    strengthName = strength.name,
                    originalPath = output.originalWavFile.absolutePath,
                    enhancedPath = output.enhancedWavFile.absolutePath,
                    originalWaveformCsv = WavUtils.waveformToCsv(output.originalWaveform),
                    enhancedWaveformCsv = WavUtils.waveformToCsv(output.enhancedWaveform),
                    engineUsed = if (isRemote) "DeepFilterNet AI" else "Studio Voice DSP",
                    isRemoteMp3Available = hasRemoteMp3,
                    remoteEnhancedMp3Path = if (hasRemoteMp3) remoteMp3.absolutePath else null
                )

                repo.saveVoice(entity)
                _processingState.value = VoiceEnhanceUiState.Idle
                _activeResultId.value = entity.id
            } catch (_: CancellationException) {
                _processingState.value = VoiceEnhanceUiState.Idle
            } catch (e: InsufficientStorageException) {
                _processingState.value = VoiceEnhanceUiState.Error(
                    fileName = meta.title,
                    isStorageError = true,
                    detailMessage = e.message ?: "Insufficient device storage",
                    canRetry = true
                )
            } catch (e: RemoteServerResourceException) {
                _processingState.value = VoiceEnhanceUiState.Error(
                    fileName = meta.title,
                    isStorageError = false,
                    detailMessage = e.message ?: "Server resources insufficient",
                    canRetry = true
                )
            } catch (t: Throwable) {
                _processingState.value = VoiceEnhanceUiState.Error(
                    fileName = meta.title,
                    isStorageError = false,
                    detailMessage = t.localizedMessage ?: "Voice enhancement failed",
                    canRetry = lastAttemptedInput != null
                )
            }
        }
    }

    fun cancelEnhancement() {
        activeJob?.cancel()
        activeJob = null
        viewModelScope.launch {
            remoteClient.cancelActiveJob()
        }
        _processingState.value = VoiceEnhanceUiState.Idle
    }

    fun retryEnhancement() {
        val last = lastAttemptedInput
        if (last != null) {
            _stagedInput.value = last
            startEnhancement()
        } else {
            _processingState.value = VoiceEnhanceUiState.Idle
        }
    }

    fun openResult(id: String) {
        comparisonPlayer.pause()
        _processingState.value = VoiceEnhanceUiState.Idle
        _activeResultId.value = id
    }

    fun deleteVoice(id: String) {
        if (_activeResultId.value == id) {
            comparisonPlayer.pause()
            _activeResultId.value = null
        }
        viewModelScope.launch {
            repo.deleteVoice(id)
        }
    }

    fun loadForComparison(entity: EnhancedVoiceEntity) {
        comparisonPlayer.loadStems(entity.originalPath, entity.enhancedPath, entity.durationMs)
    }

    fun toggleOriginal() = comparisonPlayer.toggleVocals()
    fun toggleEnhanced() = comparisonPlayer.toggleInstrumental()
    fun switchBeforeAfter(useEnhanced: Boolean) = comparisonPlayer.selectComparisonTarget(useEnhanced)
    fun seekToFraction(fraction: Float) = comparisonPlayer.seekToFraction(fraction)
    fun pausePlayback() = comparisonPlayer.pause()

    fun exportEnhancedToMusic(
        entity: EnhancedVoiceEntity,
        format: ExportFormat,
        successTemplate: String
    ) {
        viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting
            try {
                val src = resolveSourceFile(entity, format)
                val savedLocation = exporter.exportToPublicMusic(
                    sourceFile = src,
                    sampleRate = entity.sampleRate,
                    channels = entity.channels,
                    outputFileNameBase = "${entity.title}_Enhanced",
                    format = format
                )
                _snackbarEvents.emit(String.format(successTemplate, savedLocation))
            } catch (t: Throwable) {
                _snackbarEvents.emit(t.localizedMessage ?: "Export failed")
            } finally {
                _exportState.value = ExportUiState.Idle
            }
        }
    }

    fun exportEnhancedToUri(
        entity: EnhancedVoiceEntity,
        format: ExportFormat,
        targetUri: Uri,
        successTemplate: String
    ) {
        viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting
            try {
                val src = resolveSourceFile(entity, format)
                exporter.exportToUri(
                    sourceFile = src,
                    sampleRate = entity.sampleRate,
                    channels = entity.channels,
                    format = format,
                    targetUri = targetUri
                )
                _snackbarEvents.emit(
                    String.format(successTemplate, "${entity.title}_Enhanced.${format.extension}")
                )
            } catch (t: Throwable) {
                _snackbarEvents.emit(t.localizedMessage ?: "Export failed")
            } finally {
                _exportState.value = ExportUiState.Idle
            }
        }
    }

    suspend fun buildShareIntent(entity: EnhancedVoiceEntity, format: ExportFormat): Intent {
        val src = resolveSourceFile(entity, format)
        return exporter.createShareIntent(
            sourceFile = src,
            sampleRate = entity.sampleRate,
            channels = entity.channels,
            outputFileNameBase = "${entity.title}_Enhanced",
            format = format
        )
    }

    private fun resolveSourceFile(entity: EnhancedVoiceEntity, format: ExportFormat): File {
        if (format == ExportFormat.MP3 && entity.isRemoteMp3Available) {
            val mp3 = entity.remoteEnhancedMp3Path
            if (mp3 != null && File(mp3).exists()) return File(mp3)
        }
        return File(entity.enhancedPath)
    }

    override fun onCleared() {
        super.onCleared()
        comparisonPlayer.release()
    }
}
