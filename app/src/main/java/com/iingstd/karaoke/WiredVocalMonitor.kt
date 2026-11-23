package com.iingstd.karaoke

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wired-only live vocal monitor preview.
 * Does not record or mix audio. Never enable together with VoiceRecorder:
 * Android microphone capture concurrency is not guaranteed.
 */
class WiredVocalMonitor(private val context: Context) {
    private val active = AtomicBoolean(false)
    @Volatile private var gain = 0.5f
    @Volatile var inputPeak: Float = 0f
        private set
    @Volatile var processedPeak: Float = 0f
        private set
    @Volatile var studioDsp: StudioLiveDsp? = null
    @Volatile var lastError: String = ""
        private set
    private var worker: Thread? = null
    private var input: AudioRecord? = null
    private var output: AudioTrack? = null

    // Match the UI monitor slider (0–200%). Values above 100% are
    // intentional digital gain; the PCM loop clips safely to 16-bit range.
    fun setVolume(value: Float) { gain = value.coerceIn(0f, 2f) }

    private fun wiredOutput(): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    // Serialize start/stop so two callers cannot initialize separate AudioRecord
    // instances before active is set, or stop during initialization.
    @Synchronized
    fun start(): Boolean {
        lastError = ""
        if (active.get()) return true
        // A previous monitor may still be releasing its AudioRecord. Do not
        // open another microphone while that worker is winding down.
        if (worker?.isAlive == true) { lastError = "Monitor sebelumnya masih berhenti"; return false }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastError = "Izin mikrofon belum diberikan"; return false }
        val device = wiredOutput() ?: run { lastError = "Output earphone atau USB tidak ditemukan"; return false }
        val rate = 48000
        val minInput = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOutput = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minInput <= 0 || minOutput <= 0) { lastError = "Format PCM 48 kHz tidak didukung"; return false }
        val frameBytes = 960
        var pendingRecord: AudioRecord? = null
        var pendingTrack: AudioTrack? = null
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val supportsRaw = android.os.Build.VERSION.SDK_INT >= 24 &&
                manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val record = AudioRecord(
                if (supportsRaw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC,
                rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minInput, frameBytes * 4)
            )
            pendingRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) { lastError = "AudioRecord gagal dibuat"; record.release(); return false }
            val usbInput = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            if (usbInput != null && !record.setPreferredDevice(usbInput)) {
                lastError = "Tidak dapat memilih input mikrofon USB untuk monitoring"
                record.release()
                return false
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minOutput, frameBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            pendingTrack = track
            if (track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(device)) {
                lastError = "Output monitor tidak dapat diarahkan ke earphone/USB"
                track.release(); record.release(); return false
            }
            input = record; output = track
            active.set(true)
            worker = Thread {
                val samples = ShortArray(480)
                try {
                    record.startRecording()
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        lastError = "Mikrofon gagal masuk mode rekam"
                        return@Thread
                    }
                    if (usbInput != null && record.routedDevice?.id != usbInput.id) {
                        lastError = "Input monitor bukan mikrofon USB yang dipilih"
                        return@Thread
                    }
                    track.play()
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        lastError = "Output monitor gagal mulai"
                        return@Thread
                    }
                    var routeChecks = 0
                    while (active.get()) {
                        if (wiredOutput() == null) {
                            lastError = "Output earphone/USB terputus saat monitoring"
                            break
                        }
                        if (++routeChecks >= 20) {
                            routeChecks = 0
                            if (usbInput != null && record.routedDevice?.id != usbInput.id) {
                                lastError = "Input USB monitor berpindah saat LIVE"
                                break
                            }
                            if (track.routedDevice?.id != device.id) {
                                lastError = "Output monitor berpindah dari earphone/USB"
                                break
                            }
                        }
                        val n = record.read(samples, 0, samples.size)
                        if (n <= 0) {
                            if (active.get()) lastError = "Pembacaan mikrofon monitor gagal (kode $n)"
                            break
                        }
                        var rawMax = 0
                        for (i in 0 until n) rawMax = maxOf(rawMax, kotlin.math.abs(samples[i].toInt()))
                        inputPeak = rawMax / 32768f
                        studioDsp?.process(samples, n)
                        var effectMax = 0
                        for (i in 0 until n) effectMax = maxOf(effectMax, kotlin.math.abs(samples[i].toInt()))
                        processedPeak = effectMax / 32768f
                        val factor = gain
                        for (i in 0 until n) samples[i] = (samples[i] * factor).toInt().coerceIn(-32768, 32767).toShort()
                        // AudioTrack may accept fewer samples than requested. Dropping
                        // the remainder creates audible gaps and crackling on busy phones.
                        var written = 0
                        while (written < n && active.get()) {
                            val count = track.write(samples, written, n - written, AudioTrack.WRITE_BLOCKING)
                            if (count <= 0) {
                                if (active.get()) lastError = "Output monitor gagal menulis audio (kode $count)"
                                break
                            }
                            written += count
                        }
                        if (written < n) {
                            if (active.get() && lastError.isEmpty()) lastError = "Output monitor terputus sebelum seluruh audio terkirim"
                            break
                        }
                    }
                } catch (e: Exception) {
                    lastError = "Monitoring terhenti: " + (e.message ?: e.javaClass.simpleName)
                } finally {
                    active.set(false)
                    inputPeak = 0f
                    processedPeak = 0f
                    runCatching { record.stop() }
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.release() }
                    runCatching { record.release() }
                    input = null; output = null
                }
            }.apply { name = "IING-Wired-Vocal-Monitor"; isDaemon = true; start() }
            return true
        } catch (e: Exception) {
            lastError = "Monitor gagal dimulai: " + (e.message ?: e.javaClass.simpleName)
            active.set(false)
            // Construction can fail before input/output are assigned. Release
            // the local handles as well, otherwise the microphone stays reserved.
            runCatching { pendingTrack?.release() }
            runCatching { pendingRecord?.release() }
            input = null; output = null
            return false
        }
    }

    fun isActive() = active.get()
    @Synchronized
    fun stop(): Boolean {
        active.set(false)
        // AudioRecord.read() may be blocking. Stopping it allows the worker to
        // leave the loop and release the microphone before recording starts.
        runCatching { input?.stop() }
        runCatching { output?.pause() }
        val previous = worker
        if (previous != null && previous !== Thread.currentThread()) {
            runCatching { previous.join(1000) }
        }
        // Do not clear the worker reference if teardown has not finished:
        // start() must reject a second capture until the old worker exits.
        if (previous?.isAlive == false) worker = null
        // Recording must not open a second AudioRecord until teardown completes.
        return previous?.isAlive != true
    }
}
