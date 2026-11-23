package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportFormat(val extension: String, val mimeType: String) {
    WAV("wav", "audio/wav"),
    M4A("m4a", "audio/mp4"),
    MP3("mp3", "audio/mpeg")
}

class AudioExporter(private val context: Context) {

    suspend fun exportToPublicMusic(
        sourceFile: File,
        sampleRate: Int,
        channels: Int,
        outputFileNameBase: String,
        format: ExportFormat
    ): String = withContext(Dispatchers.IO) {
        val preparedFile = prepareFormattedTempFile(sourceFile, sampleRate, channels, format)
        val finalName = "${sanitizeFileName(outputFileNameBase)}.${format.extension}"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                    put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/AI Vocal Remover Pro"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create Downloads entry")

                try {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        FileInputStream(preparedFile).use { input ->
                            input.copyTo(out, 65536)
                        }
                    } ?: throw IllegalStateException("Could not open Downloads output stream")

                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, doneValues, null, null)
                } catch (e: Throwable) {
                    resolver.delete(itemUri, null, null)
                    throw e
                }
                "Download/AI Vocal Remover Pro/$finalName"
            } else {
                val exportsDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "AI Vocal Remover Pro"
                ).apply { mkdirs() }
                val dest = File(exportsDir, finalName)
                FileInputStream(preparedFile).use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out, 65536)
                    }
                }
                dest.absolutePath
            }
        } finally {
            if (preparedFile != sourceFile && preparedFile.exists()) {
                preparedFile.delete()
            }
        }
    }

    suspend fun exportToUri(
        sourceFile: File,
        sampleRate: Int,
        channels: Int,
        format: ExportFormat,
        targetUri: Uri
    ) = withContext(Dispatchers.IO) {
        val preparedFile = prepareFormattedTempFile(sourceFile, sampleRate, channels, format)
        try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                FileInputStream(preparedFile).use { input ->
                    input.copyTo(out, 65536)
                }
            } ?: throw IllegalStateException("Unable to write to selected destination")
        } finally {
            if (preparedFile != sourceFile && preparedFile.exists()) {
                preparedFile.delete()
            }
        }
    }

    suspend fun createShareIntent(
        sourceFile: File,
        sampleRate: Int,
        channels: Int,
        outputFileNameBase: String,
        format: ExportFormat
    ): Intent = withContext(Dispatchers.IO) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val shareFile = File(exportsDir, "${sanitizeFileName(outputFileNameBase)}.${format.extension}")
        val prepared = prepareFormattedTempFile(sourceFile, sampleRate, channels, format)
        try {
            if (prepared != shareFile) {
                FileInputStream(prepared).use { input ->
                    FileOutputStream(shareFile).use { out -> input.copyTo(out, 65536) }
                }
            }
        } finally {
            if (prepared != sourceFile && prepared != shareFile && prepared.exists()) {
                prepared.delete()
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile
        )
        Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun prepareFormattedTempFile(
        sourceFile: File,
        sampleRate: Int,
        channels: Int,
        format: ExportFormat
    ): File {
        if (format == ExportFormat.WAV || format == ExportFormat.MP3) {
            // WAV stems and remote-downloaded MP3 stems are already in their final format
            return sourceFile
        }
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outM4a = File(exportsDir, "temp_${System.currentTimeMillis()}.m4a")
        encodeWavToM4a(sourceFile, outM4a, sampleRate, channels)
        return outM4a
    }

    private fun encodeWavToM4a(
        wavFile: File,
        outputM4a: File,
        sampleRate: Int,
        channels: Int
    ) {
        val validChannels = channels.coerceIn(1, 2)
        val validRate = sampleRate.coerceIn(8000, 96000)
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            validRate,
            validChannels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIdx = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val ioBuf = ByteArray(16384)
        var inputDone = false
        var outputDone = false
        var totalBytesFed = 0L
        val bytesPerSec = validRate.toLong() * validChannels * 2L

        FileInputStream(wavFile).use { fis ->
            // Skip 44-byte WAV header
            val header = ByteArray(WavUtils.HEADER_SIZE)
            fis.read(header)

            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val maxRead = minOf(ioBuf.size, inBuf.remaining())
                            val read = fis.read(ioBuf, 0, maxRead)
                            if (read <= 0) {
                                val ptsUs = (totalBytesFed * 1_000_000L) / maxOf(1L, bytesPerSec)
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                inBuf.put(ioBuf, 0, read)
                                val ptsUs = (totalBytesFed * 1_000_000L) / maxOf(1L, bytesPerSec)
                                codec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                                totalBytesFed += read
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrackIdx = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIdx)
                            if (encoded != null && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                muxerStarted
                            ) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerTrackIdx, encoded, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trim().ifEmpty { "exported_stem" }
}
