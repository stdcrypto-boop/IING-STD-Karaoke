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
        voiceGain: Float = 1f,
        smoothVocal: Boolean = true,
        vocalClarity: Float = 0.5f
    ): File {
        require(backingGain in 0f..1f && voiceGain in 0f..1f && vocalClarity in 0f..1f)
        val backingTemp = File.createTempFile("backing-", ".pcm", context.cacheDir)
        var voiceTemp: File? = null
        try {
            val vocalTemp = File.createTempFile("voice-", ".pcm", context.cacheDir)
            voiceTemp = vocalTemp
            val backing = decode(context, backingUri, backingTemp)
            val voice = decode(context, voiceUri, vocalTemp)
            val rate = voice.sampleRate
            require(rate > 0 && backing.sampleRate > 0) { "Sample rate audio tidak valid" }
            require(voice.frames > 0 && voice.frames <= (Int.MAX_VALUE.toLong() - 44L) / 4L) {
                "Rekaman terlalu panjang untuk WAV"
            }
            val offset = AudioTiming.backingOffsetFrames(
                backingStartMs, backing.sampleRate, backing.frames
            )
            val needed = AudioTiming.requiredBackingFrames(
                voice.frames, rate, backing.sampleRate
            )
            // A take may end early or run slightly past the backing track.
            // In the latter case, keep the vocal and pad the music with silence.
            // Do not reject a valid short/full take because of decoder duration rounding.
            require(needed > 0) { "Durasi rekaman tidak valid" }
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
                        val vocalProcessor = Array(2) { VocalProcessor(rate, smoothVocal, vocalClarity) }
                        val musicProcessor = Array(2) { MusicProcessor(rate) }
                        val balance = estimateBalance(music, vocal, backing, voice, offset)
                        val balancedMusicGain = (backingGain * balance).coerceIn(0.20, 0.90)
                        val fadeFrames = (rate / 100).coerceAtLeast(1)
                        for (frame in 0 until voice.frames) {
                            val pos = offset.toDouble() + frame.toDouble() * backing.sampleRate / rate
                            val withinMusic = pos < backing.frames.toDouble()
                            val first = pos.toLong().coerceIn(0L, backing.frames - 1)
                            val second = (first + 1).coerceAtMost(backing.frames - 1)
                            val fraction = (pos - first).coerceIn(0.0, 1.0)
                            val fade = minOf(
                                (frame.toFloat() / fadeFrames).coerceIn(0f, 1f),
                                ((voice.frames - 1 - frame).toFloat() / fadeFrames).coerceIn(0f, 1f)
                            )
                            for (channel in 0..1) {
                                val musicSample = if (withinMusic) {
                                    val a = music.sample(first, channel)
                                    val b = music.sample(second, channel)
                                    a + (b - a) * fraction
                                } else 0.0
                                val voiceSample = vocalProcessor[channel].process(
                                    vocal.sample(frame, channel).toDouble() / 32768.0
                                )
                                val combined = musicProcessor[channel].process(musicSample / 32768.0) * balancedMusicGain +
                                    voiceSample * voiceGain
                                val mixed = (softLimit(combined) * fade * 32767.0)
                                    .toInt().coerceIn(-32768, 32767)
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
            voiceTemp?.delete()
        }
    }

    /** Estimate backing loudness against active singing, not silent pauses. */
    private fun estimateBalance(
        music: PcmFrameReader, vocal: PcmFrameReader,
        backing: Pcm, voice: Pcm, offset: Long
    ): Double {
        val step = (voice.sampleRate / 80).coerceAtLeast(1).toLong()
        var vocalPower = 0.0
        var musicPower = 0.0
        var count = 0
        var frame = 0L
        while (frame < voice.frames) {
            val v = vocal.sample(frame, 0) / 32768.0
            if (kotlin.math.abs(v) > 0.018) {
                val pos = offset + frame.toDouble() * backing.sampleRate / voice.sampleRate
                if (pos < backing.frames) {
                    val m = music.sample(pos.toLong(), 0) / 32768.0
                    vocalPower += v * v
                    musicPower += m * m
                    count++
                }
            }
            frame += step
        }
        if (count < 12) return 1.0
        val vocalRms = kotlin.math.sqrt(vocalPower / count)
        val musicRms = kotlin.math.sqrt(musicPower / count)
        if (vocalRms < 0.025 || musicRms < 0.005) return 1.0
        val desiredMusic = vocalRms * 0.70
        return (desiredMusic / (musicRms * 0.75)).coerceIn(0.45, 1.20)
    }

    /** Remove microphone rumble and gently compress loud vocal syllables. */
    private class VocalProcessor(sampleRate: Int, private val smooth: Boolean, private val clarity: Float) {
        private val hpAlpha = kotlin.math.exp(-2.0 * Math.PI * (if (smooth) 110.0 else 75.0) / sampleRate)
        private val attack = kotlin.math.exp(-1.0 / (sampleRate * 0.008))
        private val release = kotlin.math.exp(-1.0 / (sampleRate * 0.120))
        private var lowPass = 0.0
        private var envelope = 0.0
        private val presenceAlpha = kotlin.math.exp(-2.0 * Math.PI * 1700.0 / sampleRate)
        private val airAlpha = kotlin.math.exp(-2.0 * Math.PI * 4300.0 / sampleRate)
        private var presenceLow = 0.0
        private var airLow = 0.0

        fun process(sample: Double): Double {
            lowPass = (1.0 - hpAlpha) * sample + hpAlpha * lowPass
            val highPassed = sample - lowPass
            presenceLow = (1.0 - presenceAlpha) * highPassed + presenceAlpha * presenceLow
            airLow = (1.0 - airAlpha) * highPassed + airAlpha * airLow
            val shaped = highPassed + (highPassed - presenceLow) * clarity * 0.24 +
                (highPassed - airLow) * clarity * 0.12
            val level = kotlin.math.abs(shaped)
            val smoothing = if (level > envelope) attack else release
            envelope = smoothing * envelope + (1.0 - smoothing) * level
            val threshold = if (smooth) 0.12 else 0.20
            val gain = if (envelope > threshold) kotlin.math.sqrt(threshold / envelope) else 1.0
            return shaped * gain * (if (smooth) 1.07 else 1.0)
        }
    }

    /** Subsonic filter removes DC and very-low-frequency thumps, preserving audible bass. */
    private class MusicProcessor(sampleRate: Int) {
        private val alpha = kotlin.math.exp(-2.0 * Math.PI * 28.0 / sampleRate)
        private var low = 0.0
        fun process(sample: Double): Double {
            low = (1.0 - alpha) * sample + alpha * low
            return sample - low
        }
    }

    /** Limit peaks without reducing the entire song's volume. */
    private fun softLimit(value: Double): Double {
        val magnitude = kotlin.math.abs(value)
        return if (magnitude <= 0.80) value else {
            val excess = magnitude - 0.80
            kotlin.math.sign(value) * (0.80 + 0.19 * excess / (0.19 + excess))
        }
    }

    /**
     * Cache a small aligned PCM window instead of seeking for each sample.
     * A frame always contains both channels, so stereo reads remain consistent.
     */
    private class PcmFrameReader(
        private val file: RandomAccessFile,
        private val channels: Int
    ) {
        private val frameBytes = channels * 2
        private val buffer = ByteArray(64 * 1024)
        private var windowStart = -1L
        private var windowLength = 0

        fun sample(frame: Long, channel: Int): Int {
            val bytePosition = frame * frameBytes + if (channels == 1) 0 else channel * 2
            if (bytePosition < windowStart || bytePosition + 2 > windowStart + windowLength) {
                // Frame-aligned windows prevent a 16-bit sample straddling reads.
                val aligned = (bytePosition / frameBytes) * frameBytes
                file.seek(aligned)
                windowStart = aligned
                windowLength = file.read(buffer)
                check(windowLength >= frameBytes) { "PCM terpotong saat mixing" }
            }
            val index = (bytePosition - windowStart).toInt()
            val lo = buffer[index].toInt() and 0xff
            val hi = buffer[index + 1].toInt() and 0xff
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
                        val newRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(newRate > 0 && newChannels in 1..2) {
                            "Format audio hasil decoding tidak didukung"
                        }
                        if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            require(decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == 2) {
                                "Decoder harus menghasilkan PCM 16-bit"
                            }
                        }
                        // A mid-stream format change would corrupt the temporary raw PCM
                        // because it has no per-segment format metadata.
                        require(written == 0L || (newRate == rate && newChannels == channels)) {
                            "Format audio berubah saat decoding"
                        }
                        rate = newRate
                        channels = newChannels
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idle++
                        check(idle < 500) { "Decoder audio tidak merespons" }
                    }
                    else -> if (index >= 0) {
                        idle = 0
                        val buffer = decoder.getOutputBuffer(index) ?: error("Output decoder tidak tersedia")
                        try {
                            if (info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                check(written + info.size <= MAX_PCM_BYTES_PER_TRACK) {
                                    "Audio terlalu panjang untuk mixing versi ini (maksimum 512 MB PCM per track)"
                                }
                                val chunk = ByteArray(info.size)
                                buffer.get(chunk)
                                out.write(chunk)
                                written += chunk.size
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(index, false)
                        }
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
