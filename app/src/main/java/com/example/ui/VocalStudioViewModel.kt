package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioExporter
import com.example.audio.AudioMetadataHelper
import com.example.audio.ChunkedVocalSeparator
import com.example.audio.DemoTrackGenerator
import com.example.audio.DualStemAudioPlayer
import com.example.audio.ExportFormat
import com.example.audio.InsufficientStorageException
import com.example.audio.PlaybackState
import com.example.audio.SeparationStage
import com.example.audio.WavUtils
import com.example.data.local.AppDatabase
import com.example.data.local.ProcessedTrackEntity
import com.example.data.prefs.ProcessingEngineMode
import com.example.data.prefs.UserPreferences
import com.example.data.prefs.UserPreferencesRepository
import com.example.data.repository.TrackRepository
import com.example.i18n.AppLanguage
import com.example.network.RemoteDemucsClient
import com.example.network.RemoteServerResourceException
import com.example.ui.theme.ThemeMode
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface ProcessingUiState {
    data object Idle : ProcessingUiState

    data class Running(
        val jobId: String,
        val fileName: String,
        val formatLabel: String,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val percent: Int,
        val currentChunk: Int,
        val totalChunks: Int,
        val stage: SeparationStage,
        val isRemote: Boolean
    ) : ProcessingUiState

    data class Error(
        val fileName: String,
        val isStorageError: Boolean,
        val detailMessage: String,
        val canRetry: Boolean
    ) : ProcessingUiState

    data class Completed(val trackId: String) : ProcessingUiState
}

sealed interface ExportUiState {
    data object Idle : ExportUiState
    data object Exporting : ExportUiState
}

class VocalStudioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val trackRepo = TrackRepository(application, db.processedTrackDao())
    private val separator = ChunkedVocalSeparator(application)
    private val remoteClient = RemoteDemucsClient(application)
    private val exporter = AudioExporter(application)
    private val player = DualStemAudioPlayer(viewModelScope)

    val preferences: StateFlow<UserPreferences> = prefsRepo.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferences()
    )

    val recentTracks: StateFlow<List<ProcessedTrackEntity>> = trackRepo.allTracks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val playbackState: StateFlow<PlaybackState> = player.state

    private val _processingState = MutableStateFlow<ProcessingUiState>(ProcessingUiState.Idle)
    val processingState: StateFlow<ProcessingUiState> = _processingState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _serverTestState = MutableStateFlow<Boolean?>(null)
    val serverTestState: StateFlow<Boolean?> = _serverTestState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>()
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private var activeJob: Job? = null
    private var lastSourceUri: Uri? = null

    init {
        refreshStorageUsage()
        viewModelScope.launch {
            trackRepo.cleanTempFiles()
        }
    }

    fun observeTrack(trackId: String) = trackRepo.observeTrack(trackId)

    fun startSeparation(uri: Uri) {
        lastSourceUri = uri
        activeJob?.cancel()
        player.pause()

        activeJob = viewModelScope.launch {
            val jobId = UUID.randomUUID().toString().take(12)
            val prefs = preferences.value
            val isRemote = prefs.engineMode == ProcessingEngineMode.REMOTE_DEMUCS

            val meta = runCatching {
                AudioMetadataHelper.inspect(getApplication(), uri)
            }.getOrElse {
                _processingState.value = ProcessingUiState.Error(
                    fileName = "Audio File",
                    isStorageError = false,
                    detailMessage = it.localizedMessage ?: "Cannot open audio file.",
                    canRetry = true
                )
                return@launch
            }

            prefsRepo.savePendingJob(uri.toString(), meta.title)

            _processingState.value = ProcessingUiState.Running(
                jobId = jobId,
                fileName = meta.title,
                formatLabel = meta.formatLabel,
                fileSizeBytes = meta.sizeBytes,
                durationMs = meta.durationMs,
                percent = 2,
                currentChunk = 0,
                totalChunks = 1,
                stage = SeparationStage.PREPARING,
                isRemote = isRemote
            )

            try {
                val output = if (isRemote) {
                    remoteClient.separateRemote(
                        localJobId = jobId,
                        baseUrl = prefs.remoteServerUrl,
                        sourceUri = uri,
                        fileName = "${meta.title}.${meta.formatLabel.lowercase()}",
                        fileSizeBytes = meta.sizeBytes,
                        isolationStrength = prefs.isolationStrength
                    ) { prog ->
                        _processingState.value = ProcessingUiState.Running(
                            jobId = jobId,
                            fileName = meta.title,
                            formatLabel = meta.formatLabel,
                            fileSizeBytes = meta.sizeBytes,
                            durationMs = if (prog.totalDurationMs > 0) prog.totalDurationMs else meta.durationMs,
                            percent = prog.percent,
                            currentChunk = prog.currentChunk,
                            totalChunks = prog.totalChunks,
                            stage = prog.stage,
                            isRemote = true
                        )
                    }
                } else {
                    separator.separate(
                        jobId = jobId,
                        sourceUri = uri,
                        sourceFileSizeBytes = meta.sizeBytes,
                        isolationStrength = prefs.isolationStrength
                    ) { prog ->
                        _processingState.value = ProcessingUiState.Running(
                            jobId = jobId,
                            fileName = meta.title,
                            formatLabel = meta.formatLabel,
                            fileSizeBytes = meta.sizeBytes,
                            durationMs = if (prog.totalDurationMs > 0) prog.totalDurationMs else meta.durationMs,
                            percent = prog.percent,
                            currentChunk = prog.currentChunk,
                            totalChunks = prog.totalChunks,
                            stage = prog.stage,
                            isRemote = false
                        )
                    }
                }

                val vocalsMp3 = File(output.vocalsFile.parentFile, "vocals.mp3")
                val instMp3 = File(output.instrumentalFile.parentFile, "instrumental.mp3")
                val hasRemoteMp3 = vocalsMp3.exists() && instMp3.exists()

                val entity = ProcessedTrackEntity(
                    id = jobId,
                    title = meta.title,
                    sourceFormat = meta.formatLabel,
                    durationMs = if (output.durationMs > 0L) output.durationMs else meta.durationMs,
                    sampleRate = output.sampleRate,
                    channels = output.channels,
                    fileSizeBytes = meta.sizeBytes,
                    vocalsPath = output.vocalsFile.absolutePath,
                    instrumentalPath = output.instrumentalFile.absolutePath,
                    vocalsWaveformCsv = WavUtils.waveformToCsv(output.vocalsWaveform),
                    instrumentalWaveformCsv = WavUtils.waveformToCsv(output.instrumentalWaveform),
                    engineUsed = if (isRemote) "Demucs AI" else "Studio DSP",
                    isRemoteMp3Available = hasRemoteMp3,
                    remoteVocalsMp3Path = if (hasRemoteMp3) vocalsMp3.absolutePath else null,
                    remoteInstrumentalMp3Path = if (hasRemoteMp3) instMp3.absolutePath else null
                )

                trackRepo.saveTrack(entity)
                prefsRepo.savePendingJob(null, null)
                refreshStorageUsage()
                _processingState.value = ProcessingUiState.Completed(entity.id)
            } catch (_: CancellationException) {
                viewModelScope.launch {
                    trackRepo.cleanTempFiles()
                    prefsRepo.savePendingJob(null, null)
                }
                _processingState.value = ProcessingUiState.Idle
            } catch (e: InsufficientStorageException) {
                prefsRepo.savePendingJob(null, null)
                _processingState.value = ProcessingUiState.Error(
                    fileName = meta.title,
                    isStorageError = true,
                    detailMessage = e.message ?: "Insufficient device storage",
                    canRetry = true
                )
            } catch (e: RemoteServerResourceException) {
                _processingState.value = ProcessingUiState.Error(
                    fileName = meta.title,
                    isStorageError = false,
                    detailMessage = e.message ?: "Server resources insufficient",
                    canRetry = true
                )
            } catch (t: Throwable) {
                _processingState.value = ProcessingUiState.Error(
                    fileName = meta.title,
                    isStorageError = false,
                    detailMessage = t.localizedMessage ?: "Audio separation failed",
                    canRetry = lastSourceUri != null
                )
            }
        }
    }

    fun startDemoTrack() {
        viewModelScope.launch {
            val demoUri = DemoTrackGenerator.createDemoAudioUri(getApplication())
            startSeparation(demoUri)
        }
    }

    fun cancelProcessing() {
        activeJob?.cancel()
        activeJob = null
        viewModelScope.launch {
            remoteClient.cancelActiveRemoteJob()
            trackRepo.cleanTempFiles()
            prefsRepo.savePendingJob(null, null)
        }
        _processingState.value = ProcessingUiState.Idle
    }

    fun retryLastJob() {
        val uri = lastSourceUri ?: preferences.value.lastPendingUri?.let { Uri.parse(it) }
        if (uri != null) {
            startSeparation(uri)
        } else {
            _processingState.value = ProcessingUiState.Idle
        }
    }

    fun resetProcessingState() {
        _processingState.value = ProcessingUiState.Idle
    }

    fun loadTrackForPlayback(track: ProcessedTrackEntity) {
        player.loadStems(track.vocalsPath, track.instrumentalPath, track.durationMs)
    }

    fun toggleVocals() = player.toggleVocals()
    fun toggleInstrumental() = player.toggleInstrumental()
    fun toggleDualMix() = player.toggleDualMix()
    fun setMixBalance(balance: Float) = player.setMixBalance(balance)
    fun seekToFraction(fraction: Float) = player.seekToFraction(fraction)
    fun pausePlayback() = player.pause()

    fun exportStemToMusic(
        track: ProcessedTrackEntity,
        isVocals: Boolean,
        format: ExportFormat,
        successTemplate: String
    ) {
        viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting
            try {
                val src = resolveSourceStemFile(track, isVocals, format)
                val suffix = if (isVocals) "Vocals" else "Instrumental"
                val savedLocation = exporter.exportToPublicMusic(
                    sourceFile = src,
                    sampleRate = track.sampleRate,
                    channels = track.channels,
                    outputFileNameBase = "${track.title}_$suffix",
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

    fun exportStemToUri(
        track: ProcessedTrackEntity,
        isVocals: Boolean,
        format: ExportFormat,
        targetUri: Uri,
        successTemplate: String
    ) {
        viewModelScope.launch {
            _exportState.value = ExportUiState.Exporting
            try {
                val src = resolveSourceStemFile(track, isVocals, format)
                exporter.exportToUri(
                    sourceFile = src,
                    sampleRate = track.sampleRate,
                    channels = track.channels,
                    format = format,
                    targetUri = targetUri
                )
                val suffix = if (isVocals) "Vocals" else "Instrumental"
                _snackbarEvents.emit(
                    String.format(successTemplate, "${track.title}_$suffix.${format.extension}")
                )
            } catch (t: Throwable) {
                _snackbarEvents.emit(t.localizedMessage ?: "Export failed")
            } finally {
                _exportState.value = ExportUiState.Idle
            }
        }
    }

    suspend fun buildShareIntent(
        track: ProcessedTrackEntity,
        isVocals: Boolean,
        format: ExportFormat
    ): Intent {
        val src = resolveSourceStemFile(track, isVocals, format)
        val suffix = if (isVocals) "Vocals" else "Instrumental"
        return exporter.createShareIntent(
            sourceFile = src,
            sampleRate = track.sampleRate,
            channels = track.channels,
            outputFileNameBase = "${track.title}_$suffix",
            format = format
        )
    }

    private fun resolveSourceStemFile(
        track: ProcessedTrackEntity,
        isVocals: Boolean,
        format: ExportFormat
    ): File {
        if (format == ExportFormat.MP3 && track.isRemoteMp3Available) {
            val mp3Path = if (isVocals) track.remoteVocalsMp3Path else track.remoteInstrumentalMp3Path
            if (mp3Path != null && File(mp3Path).exists()) return File(mp3Path)
        }
        return File(if (isVocals) track.vocalsPath else track.instrumentalPath)
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch {
            trackRepo.deleteTrack(trackId)
            refreshStorageUsage()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun toggleQuickTheme() {
        val current = preferences.value.themeMode
        val next = if (current == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        setThemeMode(next)
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { prefsRepo.setLanguage(language) }
    }

    fun toggleQuickLanguage() {
        val current = preferences.value.language
        val next = if (current == AppLanguage.ENGLISH) AppLanguage.BANGLA else AppLanguage.ENGLISH
        setLanguage(next)
    }

    fun setEngineMode(mode: ProcessingEngineMode) {
        viewModelScope.launch {
            prefsRepo.setEngineMode(mode)
            _serverTestState.value = null
        }
    }

    fun setRemoteServerUrl(url: String) {
        viewModelScope.launch {
            prefsRepo.setRemoteServerUrl(url)
            _serverTestState.value = null
        }
    }

    fun setIsolationStrength(strength: Float) {
        viewModelScope.launch { prefsRepo.setIsolationStrength(strength) }
    }

    fun testRemoteServerConnection(url: String) {
        viewModelScope.launch {
            _serverTestState.value = null
            val ok = remoteClient.testConnection(url)
            _serverTestState.value = ok
        }
    }

    fun cleanTempCache(toastMsg: String) {
        viewModelScope.launch {
            trackRepo.cleanTempFiles()
            refreshStorageUsage()
            _snackbarEvents.emit(toastMsg)
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            _storageBytes.value = trackRepo.calculateStorageUsageBytes()
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
