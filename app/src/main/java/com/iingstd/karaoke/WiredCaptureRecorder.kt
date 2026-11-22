package com.iingstd.karaoke

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Single AudioRecord input feeds both a WAV vocal take and a wired AudioTrack.
 * No competing MediaRecorder capture session. WAV is readable by Android's decoder
 * and can be passed to AudioMixEngine like the existing M4A vocal recording.
 */
class WiredCaptureRecorder(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    @Volatile private var monitorGain = 0.5f
    @Volatile private var peak = 0f
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var playback: AudioTrack? = null
    private var file: File? = null
    private var failure: Throwable? = null

    fun setMonitorVolume(gain: Float) { monitorGain = gain.coerceIn(0f, 1f) }
    fun peakLevel() = peak
    fun isActive() = running.get()
    fun isPaused() = paused.get()

    private fun wiredOutput(): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    fun start(gain: Float): Uri {
        check(!running.get()) { "Capture already running" }
        val device = wiredOutput() ?: error("Headphone kabel tidak terdeteksi")
        val rate = 48000
        val inputMin = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outputMin = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(inputMin > 0 && outputMin > 0) { "Format audio tidak didukung" }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "IING STD Karaoke")
        check(dir.exists() || dir.mkdirs()) { "Tidak dapat membuat folder rekaman" }
        val destination = File(dir, "IING-WIRED-${System.currentTimeMillis()}.wav")
        var input: AudioRecord? = null
        var output: AudioTrack? = null
        try {
            input = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(inputMin, 4096))
            check(input.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon tidak tersedia" }
            output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(outputMin, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            check(output.state == AudioTrack.STATE_INITIALIZED && output.setPreferredDevice(device)) { "Tidak bisa mengarahkan suara ke headphone" }
            val raf = RandomAccessFile(destination, "rw")
            try { raf.setLength(0); raf.write(ByteArray(44)) } finally { raf.close() }
            file = destination
            record = input
            playback = output
            monitorGain = gain.coerceIn(0f, 1f)
            failure = null
            paused.set(false)
            running.set(true)
            val capture = input
            val monitor = output
            thread = Thread {
                var bytes = 0L
                try {
                    RandomAccessFile(destination, "rw").use { wav ->
                        wav.seek(44)
                        capture.startRecording()
                        monitor.play()
                        val samples = ShortArray(480)
                        val listen = ShortArray(480)
                        while (running.get()) {
                            val n = capture.read(samples, 0, samples.size)
                            if (n <= 0) break
                            if (paused.get()) continue
                            var max = 0
                            for (i in 0 until n) {
                                val value = samples[i].toInt()
                                max = maxOf(max, abs(value))
                                listen[i] = (value * monitorGain).toInt().coerceIn(-32768, 32767).toShort()
                                wav.write(value and 255)
                                wav.write((value shr 8) and 255)
                            }
                            bytes += n * 2L
                            peak = max / 32768f
                            // Never send monitored voice to the loudspeaker if wired output disappears.
                            if (wiredOutput() == null) break
                            if (monitor.write(listen, 0, n) < 0) break
                        }
                        writeHeader(wav, bytes, rate)
                    }
                } catch (t: Throwable) { failure = t }
                finally {
                    running.set(false)
                    runCatching { capture.stop() }
                    runCatching { monitor.pause() }
                    runCatching { monitor.flush() }
                    runCatching { capture.release() }
                    runCatching { monitor.release() }
                    peak = 0f
                }
            }.apply { name = "IING-Wired-Record-Monitor"; start() }
            return Uri.fromFile(destination)
        } catch (t: Throwable) {
            running.set(false)
            runCatching { input?.release() }
            runCatching { output?.release() }
            destination.delete()
            file = null; record = null; playback = null
            throw t
        }
    }

    fun pause() { check(running.get()); paused.set(true) }
    fun resume() { check(running.get()); paused.set(false) }
    fun stop(): Uri? {
        running.set(false)
        runCatching { record?.stop() }
        thread?.join(5000)
        check(thread?.isAlive != true) { "Perekam belum berhenti" }
        thread = null; record = null; playback = null
        val finished = file
        file = null
        if (failure != null || finished == null || finished.length() <= 44L) {
            finished?.delete()
            return null
        }
        return Uri.fromFile(finished)
    }

    private fun writeHeader(wav: RandomAccessFile, dataBytes: Long, sampleRate: Int) {
        require(dataBytes in 1..(0xFFFFFFFFL - 36L)) { "Rekaman WAV terlalu panjang" }
        wav.seek(0)
        fun ascii(v: String) = wav.write(v.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { wav.write(v and 255); wav.write((v shr 8) and 255) }
        fun le32(v: Long) { for (i in 0..3) wav.write(((v shr (8 * i)) and 255).toInt()) }
        ascii("RIFF"); le32(dataBytes + 36); ascii("WAVEfmt ")
        le32(16); le16(1); le16(1); le32(sampleRate.toLong()); le32(sampleRate * 2L)
        le16(2); le16(16); ascii("data"); le32(dataBytes)
    }
}
