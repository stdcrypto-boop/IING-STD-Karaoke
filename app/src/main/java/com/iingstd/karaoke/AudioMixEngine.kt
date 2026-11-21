package com.iingstd.karaoke

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.RandomAccessFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline prototype for mixing decoded PCM from two accessible local audio URIs.
 * Both streams must decode to 16-bit PCM; backing audio is resampled when needed.
 * Output is stereo PCM WAV. Call on a background thread, never the UI thread.
 */
object AudioMixEngine {
    data class Pcm(val file: File, val sampleRate: Int, val channels: Int, val frames: Long)
    private const val MAX_PCM_BYTES_PER_TRACK = 512L * 1024 * 1024

    fun mixToWav(
        context: Context,
        backingUri: Uri,
        voiceUri: Uri,
        backingStartMs: Long,
        destination: File,
        backingGain: Float = 0.65f,
        voiceGain: Float = 1f
    ): File {
        require(backingGain in 0f..1f && voiceGain in 0f..1f)
        val backingTemp = File.createTempFile("backing-", ".pcm", context.cacheDir)
        val voiceTemp = File.createTempFile("voice-", ".pcm", context.cacheDir)
        try {
            val backing = decode(context, backingUri, backingTemp)
            val voice = decode(context, voiceUri, voiceTemp)
            val rate = voice.sampleRate
            require(rate > 0 && backing.sampleRate > 0) { "Sample rate audio tidak valid" }
            require(voice.frames > 0 && voice.frames <= (Int.MAX_VALUE.toLong() - 44L) / 4L) {
                "Rekaman terlalu panjang untuk WAV"
            }
            val startMs = backingStartMs.coerceAtLeast(0L)
            // Reject impossible offsets before multiplication to prevent Long overflow.
            val maxStartMs = (backing.frames / backing.sampleRate) * 1000L +
                (backing.frames % backing.sampleRate) * 1000L / backing.sampleRate
            require(startMs < maxStartMs) { "Posisi awal melewati durasi musik" }
            val offset = (startMs / 1000L) * backing.sampleRate +
                (startMs % 1000L) * backing.sampleRate / 1000L
            require(offset < backing.frames) { "Posisi awal melewati durasi musik" }
            // Compare durations without multiplying two potentially large frame counts.
            val availableFrames = backing.frames - offset
            val needed = (voice.frames / rate) * backing.sampleRate +
                ((voice.frames % rate) * backing.sampleRate + rate - 1L) / rate
            require(needed <= availableFrames) {
                "Musik pengiring berakhir sebelum rekaman vokal selesai"
            }
            destination.parentFile?.mkdirs()
            RandomAccessFile(backing.file, "r").use { musicFile ->
                RandomAccessFile(voice.file, "r").use { vocalFile ->
                    val music = PcmFrameReader(musicFile, backing.channels)
                    val vocal = PcmFrameReader(vocalFile, voice.channels)
                    FileOutputStream(destination).use { output ->
                        val bytes = (voice.frames * 4).toInt()
                        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                        header.put("RIFF".toByteArray(Charsets.US_ASCII))
                        header.putInt(36 + bytes)
                        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                        header.putInt(16)
                        header.putShort(1)
                        header.putShort(2)
                        header.putInt(rate)
                        header.putInt(rate * 4)
                        header.putShort(4)
                        header.putShort(16)
                        header.put("data".toByteArray(Charsets.US_ASCII))
                        header.putInt(bytes)
                        output.write(header.array())
                        val block = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                        val sum = backingGain + voiceGain
                        val scale = if (sum > 1f) 1f / sum else 1f
                        val fadeFrames = (rate / 100).coerceAtLeast(1)
                        for (frame in 0 until voice.frames) {
                            val pos = offset.toDouble() + frame.toDouble() * backing.sampleRate / rate
                            val first = pos.toLong().coerceAtMost(backing.frames - 1)
                            val second = (first + 1).coerceAtMost(backing.frames - 1)
                            val fraction = (pos - first).coerceIn(0.0, 1.0)
                            val fade = minOf(
                                (frame.toFloat() / fadeFrames).coerceIn(0f, 1f),
                                ((voice.frames - 1 - frame).toFloat() / fadeFrames).coerceIn(0f, 1f)
                            )
                            for (channel in 0..1) {
                                val a = music.sample(first, channel)
                                val b = music.sample(second, channel)
                                val musicSample = a + (b - a) * fraction
                                val voiceSample = vocal.sample(frame, channel)
                                val mixed = ((musicSample * backingGain + voiceSample * voiceGain) *
                                    scale * fade).toInt().coerceIn(-32768, 32767)
                                if (block.remaining() < 2) {
                                    output.write(block.array(), 0, block.position())
                                    block.clear()
                                }
                                block.putShort(mixed.toShort())
                            }
                        }
                        if (block.position() > 0) output.write(block.array(), 0, block.position())
                    }
                }
            }
            return destination
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            backingTemp.delete()
            voiceTemp.delete()
        }
    }

    private class PcmFrameReader(
        private val file: RandomAccessFile,
        private val channels: Int
    ) {
        private var cachedFrame = -1L
        private var left = 0
        private var right = 0

        fun sample(frame: Long, channel: Int): Int {
            if (frame != cachedFrame) {
                file.seek(frame * channels * 2L)
                left = readSample()
                right = if (channels == 2) readSample() else left
                cachedFrame = frame
            }
            return if (channel == 0) left else right
        }

        private fun readSample(): Int {
            val lo = file.readUnsignedByte()
            val hi = file.readUnsignedByte()
            return ((hi shl 8) or lo).toShort().toInt()
        }
    }

    private fun decode(context: Context, uri: Uri, target: File): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pcmOutput: FileOutputStream? = null
        try {
            // MediaExtractor's URI overload handles content providers with unknown
            // asset lengths (and regular file URIs) without passing a negative length.
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Tidak ada track audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Format audio tidak diketahui")
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sourceRate > 0 && sourceChannels in 1..2) { "Hanya audio mono atau stereo didukung" }
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                if (durationUs > 0) {
                    // Divide first to avoid overflow on malformed duration metadata.
                    val estimatedBytes = (durationUs / 1_000_000L) *
                        sourceRate.toLong() * sourceChannels * 2L +
                        ((durationUs % 1_000_000L) * sourceRate / 1_000_000L) *
                        sourceChannels * 2L
                    require(estimatedBytes <= MAX_PCM_BYTES_PER_TRACK) {
                        "Audio terlalu panjang untuk mixing versi ini (maksimum 512 MB PCM per track)"
                    }
                }
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val out = FileOutputStream(target)
            pcmOutput = out
            var written = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: error("Input decoder tidak tersedia")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val decoded = decoder.outputFormat
                        rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            require(decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == 2) {
                                "Decoder harus menghasilkan PCM 16-bit"
                            }
                        }
                        require(channels in 1..2) { "Hanya audio mono atau stereo didukung" }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idle++
                        check(idle < 500) { "Decoder audio tidak merespons" }
                    }
                    else -> if (index >= 0) {
                        idle = 0
                        val buffer = decoder.getOutputBuffer(index) ?: error("Output decoder tidak tersedia")
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            check(written + info.size <= MAX_PCM_BYTES_PER_TRACK) {
                                "Audio terlalu panjang untuk mixing versi ini (maksimum 32 MB PCM per track)"
                            }
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            out.write(chunk)
                            written += chunk.size
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            require(channels in 1..2) { "Hanya audio mono atau stereo didukung" }
            out.close()
            pcmOutput = null
            require(written > 0 && written % (channels * 2L) == 0L) { "PCM tidak lengkap" }
            return Pcm(target, rate, channels, written / (channels * 2L))
        } finally {
            runCatching { pcmOutput?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
