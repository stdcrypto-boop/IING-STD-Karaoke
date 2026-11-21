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
            val offset = backingStartMs.coerceAtLeast(0) * backing.sampleRate / 1000L
            require(offset < backing.frames) { "Posisi awal melewati durasi musik" }
            val needed = (voice.frames * backing.sampleRate + rate - 1) / rate
            require(needed <= backing.frames - offset) {
                "Musik pengiring berakhir sebelum rekaman vokal selesai"
            }
            require(voice.frames > 0 && voice.frames * 4 <= Int.MAX_VALUE - 44) {
                "Rekaman terlalu panjang untuk WAV"
            }
            destination.parentFile?.mkdirs()
            RandomAccessFile(backing.file, "r").use { music ->
                RandomAccessFile(voice.file, "r").use { vocal ->
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
                                val a = sample(music, first, backing.channels, channel)
                                val b = sample(music, second, backing.channels, channel)
                                val musicSample = a + (b - a) * fraction
                                val voiceSample = sample(vocal, frame, voice.channels, channel)
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

    private fun sample(file: RandomAccessFile, frame: Long, channels: Int, channel: Int): Int {
        val index = frame * channels + if (channels == 1) 0 else channel
        file.seek(index * 2)
        val lo = file.readUnsignedByte()
        val hi = file.readUnsignedByte()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun decode(context: Context, uri: Uri, target: File): Pcm {
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
            require(written > 0 && written % (channels * 2L) == 0L) { "PCM tidak lengkap" }
            return Pcm(target, rate, channels, written / (channels * 2L))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
