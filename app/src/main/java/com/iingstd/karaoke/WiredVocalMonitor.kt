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
    private var worker: Thread? = null
    private var input: AudioRecord? = null
    private var output: AudioTrack? = null

    fun setVolume(value: Float) { gain = value.coerceIn(0f, 1f) }

    private fun wiredOutput(): AudioDeviceInfo? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    fun start(): Boolean {
        if (active.get()) return true
        // A previous monitor may still be releasing its AudioRecord. Do not
        // open another microphone while that worker is winding down.
        if (worker?.isAlive == true) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val device = wiredOutput() ?: return false
        val rate = 48000
        val minInput = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minOutput = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minInput <= 0 || minOutput <= 0) return false
        val frameBytes = 960
        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minInput, frameBytes * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minOutput, frameBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            if (track.state != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(device)) {
                track.release(); record.release(); return false
            }
            input = record; output = track
            active.set(true)
            worker = Thread {
                val samples = ShortArray(480)
                try {
                    record.startRecording()
                    track.play()
                    while (active.get() && wiredOutput() != null) {
                        val n = record.read(samples, 0, samples.size)
                        if (n <= 0) break
                        val factor = gain
                        for (i in 0 until n) samples[i] = (samples[i] * factor).toInt().coerceIn(-32768, 32767).toShort()
                        if (track.write(samples, 0, n) < 0) break
                    }
                } catch (_: Exception) {
                } finally {
                    active.set(false)
                    runCatching { record.stop() }
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.release() }
                    runCatching { record.release() }
                    input = null; output = null
                }
            }.apply { name = "IING-Wired-Vocal-Monitor"; isDaemon = true; start() }
            return true
        } catch (_: Exception) {
            active.set(false)
            runCatching { input?.release() }
            runCatching { output?.release() }
            input = null; output = null
            return false
        }
    }

    fun isActive() = active.get()
    fun stop() {
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
    }
}
