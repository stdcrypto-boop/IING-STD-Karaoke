package com.iingstd.karaoke

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
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
    data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)
    // Both tracks are decoded in memory; cap each to avoid exhausting device RAM.
    private const val MAX_PCM_BYTES_PER_TRACK = 32 * 1024 * 1024

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
        val backing = decode(context, backingUri)
        val voice = decode(context, voiceUri)
        // Keep the vocal sample rate as output; resample backing with linear interpolation.
        val rate = voice.sampleRate
        val safeStartMs = backingStartMs.coerceAtLeast(0)
        val offsetFramesLong = (safeStartMs / 1000) * backing.sampleRate.toLong() +
            (safeStartMs % 1000) * backing.sampleRate / 1000
        require(offsetFramesLong <= Int.MAX_VALUE) { "Posisi musik terlalu besar" }
        val offsetFrames = offsetFramesLong.toInt()
        val voiceFrames = voice.samples.size / voice.channels
        val backingFrames = backing.samples.size / backing.channels
        require(offsetFrames < backingFrames) { "Posisi awal melewati durasi musik" }
        val requiredBackingFrames = (voiceFrames.toLong() * backing.sampleRate + rate - 1) / rate
        require(requiredBackingFrames <= backingFrames.toLong() - offsetFrames) {
            "Musik pengiring berakhir sebelum rekaman vokal selesai"
        }
        val frames = voiceFrames
        require(frames > 0) { "Tidak ada audio yang dapat digabungkan" }
        require(frames.toLong() * 4 <= Int.MAX_VALUE - 44) { "Rekaman terlalu panjang untuk WAV" }
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { output ->
            val bytes = frames * 4
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
            val combinedGain = backingGain + voiceGain
            val scale = if (combinedGain > 1f) 1f / combinedGain else 1f
            val fadeFrames = (rate / 100).coerceAtLeast(1) // 10 ms
            for (frame in 0 until frames) {
                val fadeIn = (frame.toFloat() / fadeFrames).coerceIn(0f, 1f)
                val fadeOut = ((frames - 1 - frame).toFloat() / fadeFrames).coerceIn(0f, 1f)
                val edgeGain = minOf(fadeIn, fadeOut)
                val backingFrame = offsetFrames.toDouble() +
                    frame.toDouble() * backing.sampleRate / rate
                for (channel in 0..1) {
                    val music = interpolatedSample(backing, backingFrame, channel)
                    val vocal = sample(voice, frame, channel)
                    // Avoid hard clipping when both tracks peak at the same time.
                    // Scale both gains proportionally if their combined gain exceeds unity.
                    val mixed = ((music * backingGain + vocal * voiceGain) * scale * edgeGain)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    if (block.remaining() < 2) {
                        output.write(block.array(), 0, block.position())
                        block.clear()
                    }
                    block.putShort(mixed.toShort())
                }
            }
            if (block.position() > 0) output.write(block.array(), 0, block.position())
        }
        return destination
    }

    private fun sample(pcm: Pcm, frame: Int, channel: Int): Int {
        val index = frame * pcm.channels + if (pcm.channels == 1) 0 else channel
        return pcm.samples[index].toInt()
    }

    private fun interpolatedSample(pcm: Pcm, position: Double, channel: Int): Int {
        val frameCount = pcm.samples.size / pcm.channels
        val first = position.toInt().coerceIn(0, frameCount - 1)
        val second = (first + 1).coerceAtMost(frameCount - 1)
        val fraction = (position - first).coerceIn(0.0, 1.0)
        val a = sample(pcm, first, channel)
        val b = sample(pcm, second, channel)
        return (a + (b - a) * fraction).toInt()
    }

    private fun decode(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
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
                    val estimatedBytes = durationUs / 1000L * sourceRate * sourceChannels * 2L / 1000L
                    require(estimatedBytes <= MAX_PCM_BYTES_PER_TRACK) {
                        "Audio terlalu panjang untuk mixing versi ini (maksimum 32 MB PCM per track)"
                    }
                }
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val out = ByteArrayOutputStream()
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
                            check(out.size().toLong() + info.size <= MAX_PCM_BYTES_PER_TRACK) {
                                "Audio terlalu panjang untuk mixing versi ini (maksimum 32 MB PCM per track)"
                            }
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            out.write(chunk)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            require(channels in 1..2) { "Hanya audio mono atau stereo didukung" }
            val bytes = out.toByteArray()
            require(bytes.size % 2 == 0) { "PCM tidak lengkap" }
            val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(pcm.remaining())
            pcm.get(samples)
            return Pcm(samples, rate, channels)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
