package com.example.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActivePlaybackTarget {
    NONE,
    VOCALS_SOLO,
    INSTRUMENTAL_SOLO,
    DUAL_MIX
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val activeTarget: ActivePlaybackTarget = ActivePlaybackTarget.NONE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val mixBalance: Float = 0.5f // 0f = Pure Instrumental, 1f = Pure Vocals
)

class DualStemAudioPlayer(private val scope: CoroutineScope) {

    private var vocalsPlayer: MediaPlayer? = null
    private var instPlayer: MediaPlayer? = null
    private var loadedVocalsPath: String? = null
    private var loadedInstPath: String? = null
    private var tickerJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun loadStems(vocalsPath: String, instrumentalPath: String, fallbackDurationMs: Long) {
        if (loadedVocalsPath == vocalsPath && loadedInstPath == instrumentalPath &&
            vocalsPlayer != null && instPlayer != null
        ) {
            return
        }
        release()
        loadedVocalsPath = vocalsPath
        loadedInstPath = instrumentalPath

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        runCatching {
            vocalsPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(vocalsPath)
                prepare()
                setOnCompletionListener { onTrackEnded() }
            }
            instPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(instrumentalPath)
                prepare()
                setOnCompletionListener { onTrackEnded() }
            }
            val dur = maxOf(
                vocalsPlayer?.duration?.toLong() ?: 0L,
                instPlayer?.duration?.toLong() ?: 0L,
                fallbackDurationMs
            )
            _state.update {
                it.copy(
                    isPlaying = false,
                    activeTarget = ActivePlaybackTarget.NONE,
                    positionMs = 0L,
                    durationMs = dur
                )
            }
            applyVolumes()
        }
    }

    fun toggleVocals() {
        val current = _state.value
        if (current.isPlaying && current.activeTarget == ActivePlaybackTarget.VOCALS_SOLO) {
            pause()
        } else {
            playTarget(ActivePlaybackTarget.VOCALS_SOLO)
        }
    }

    fun toggleInstrumental() {
        val current = _state.value
        if (current.isPlaying && current.activeTarget == ActivePlaybackTarget.INSTRUMENTAL_SOLO) {
            pause()
        } else {
            playTarget(ActivePlaybackTarget.INSTRUMENTAL_SOLO)
        }
    }

    fun toggleDualMix() {
        val current = _state.value
        if (current.isPlaying && current.activeTarget == ActivePlaybackTarget.DUAL_MIX) {
            pause()
        } else {
            playTarget(ActivePlaybackTarget.DUAL_MIX)
        }
    }

    fun selectComparisonTarget(useSecondTrack: Boolean) {
        val target = if (useSecondTrack) {
            ActivePlaybackTarget.INSTRUMENTAL_SOLO
        } else {
            ActivePlaybackTarget.VOCALS_SOLO
        }
        if (_state.value.isPlaying) {
            _state.update { it.copy(activeTarget = target) }
            applyVolumes()
        } else {
            playTarget(target)
        }
    }

    fun setMixBalance(balance: Float) {
        _state.update { it.copy(mixBalance = balance.coerceIn(0f, 1f)) }
        applyVolumes()
    }

    fun seekToFraction(fraction: Float) {
        val dur = _state.value.durationMs
        if (dur <= 0L) return
        val targetMs = (fraction.coerceIn(0f, 1f) * dur).toInt()
        runCatching {
            vocalsPlayer?.seekTo(targetMs)
            instPlayer?.seekTo(targetMs)
        }
        _state.update { it.copy(positionMs = targetMs.toLong()) }
    }

    fun pause() {
        runCatching {
            if (vocalsPlayer?.isPlaying == true) vocalsPlayer?.pause()
            if (instPlayer?.isPlaying == true) instPlayer?.pause()
        }
        tickerJob?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    private fun playTarget(target: ActivePlaybackTarget) {
        val vPlayer = vocalsPlayer ?: return
        val iPlayer = instPlayer ?: return

        val pos = _state.value.positionMs.toInt().coerceAtLeast(0)
        runCatching {
            if (pos >= _state.value.durationMs - 150) {
                vPlayer.seekTo(0)
                iPlayer.seekTo(0)
            } else {
                vPlayer.seekTo(pos)
                iPlayer.seekTo(pos)
            }
            _state.update { it.copy(isPlaying = true, activeTarget = target) }
            applyVolumes()
            if (!vPlayer.isPlaying) vPlayer.start()
            if (!iPlayer.isPlaying) iPlayer.start()
            startTicker()
        }
    }

    private fun applyVolumes() {
        val current = _state.value
        when (current.activeTarget) {
            ActivePlaybackTarget.VOCALS_SOLO -> {
                vocalsPlayer?.setVolume(1f, 1f)
                instPlayer?.setVolume(0f, 0f)
            }
            ActivePlaybackTarget.INSTRUMENTAL_SOLO -> {
                vocalsPlayer?.setVolume(0f, 0f)
                instPlayer?.setVolume(1f, 1f)
            }
            ActivePlaybackTarget.DUAL_MIX, ActivePlaybackTarget.NONE -> {
                val vocVol = current.mixBalance.coerceIn(0f, 1f)
                val instVol = (1f - current.mixBalance).coerceIn(0f, 1f)
                vocalsPlayer?.setVolume(vocVol, vocVol)
                instPlayer?.setVolume(instVol, instVol)
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value.isPlaying) {
                val pos = vocalsPlayer?.currentPosition?.toLong()
                    ?: instPlayer?.currentPosition?.toLong()
                    ?: 0L
                _state.update { it.copy(positionMs = pos.coerceAtMost(it.durationMs)) }
                delay(50L)
            }
        }
    }

    private fun onTrackEnded() {
        tickerJob?.cancel()
        runCatching {
            vocalsPlayer?.pause()
            instPlayer?.pause()
            vocalsPlayer?.seekTo(0)
            instPlayer?.seekTo(0)
        }
        _state.update { it.copy(isPlaying = false, positionMs = 0L) }
    }

    fun release() {
        tickerJob?.cancel()
        runCatching { vocalsPlayer?.release() }
        runCatching { instPlayer?.release() }
        vocalsPlayer = null
        instPlayer = null
        loadedVocalsPath = null
        loadedInstPath = null
        _state.value = PlaybackState()
    }
}
