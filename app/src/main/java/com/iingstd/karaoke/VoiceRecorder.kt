package com.iingstd.karaoke

import android.content.ContentValues
import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Microphone-only AAC/M4A recording. Does NOT mix the backing track.
 * Android 10+: public Music folder via MediaStore; Android 8-9: app-specific Music folder.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var legacyFile: File? = null

    private var paused = false
    val isRecording: Boolean get() = recorder != null
    val isPaused: Boolean get() = paused
    /** Read-only microphone peak level (0..1). Does not play mic audio to speakers. */
    fun peakLevel(): Float {
        val active = recorder ?: return 0f
        if (paused) return 0f
        return runCatching {
            (active.maxAmplitude.coerceIn(0, 32767) / 32767f).coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }


    fun pause() {
        check(!paused) { "Recording already paused" }
        val active = recorder ?: error("No recording in progress")
        active.pause()
        paused = true
    }

    fun resume() {
        check(paused) { "Recording is not paused" }
        val active = recorder ?: error("No recording in progress")
        active.resume()
        paused = false
    }


    fun start(preferExternalMic: Boolean = false): Uri {
        check(recorder == null) { "Recording already in progress" }
        paused = false
        val name = "IING-VOICE-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IING STD Karaoke")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot create recording file")
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "IING STD Karaoke")
            check(dir.exists() || dir.mkdirs()) { "Cannot create recordings directory" }
            File(dir, name).also { legacyFile = it }.let(Uri::fromFile)
        }
        output = uri
        try {
            val fd = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.openFileDescriptor(uri, "w") ?: error("Cannot open recording file")
            } else null
            descriptor = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = r
            run {
                r.setAudioSource(if (Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioSource.VOICE_PERFORMANCE else MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(192000)
                r.setAudioSamplingRate(48000)
                if (fd != null) r.setOutputFile(fd.fileDescriptor)
                else r.setOutputFile(legacyFile?.absolutePath ?: error("Missing output file"))
                r.prepare()
                // Automatically prefer a connected USB soundcard or wired mic.
                // Bluetooth SCO is left to Android routing: forcing it can interrupt
                // playback or select a speaker-only Bluetooth connection.
                if (Build.VERSION.SDK_INT >= 28) {
                    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val external = inputs.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: inputs.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    if (external != null && r.setPreferredDevice(external)) {
                    }
                    // Without an external input, Android uses the phone mic.
                }
                r.start()
                // Android may override a preferred route; never discard a valid
                // take solely because a peripheral was unplugged mid-start.
            }
            return uri
        } catch (e: Exception) {
            recorder?.runCatching { reset(); release() }
            recorder = null
            paused = false
            runCatching { descriptor?.close() }
            descriptor = null
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            } else {
                runCatching { legacyFile?.delete() }
            }
            legacyFile = null
            output = null
            throw e
        }
    }

    fun stop(): Uri? {
        val active = recorder ?: return null
        val uri = output
        recorder = null
        paused = false
        output = null
        var success = false
        try {
            active.stop()
            success = true
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
            runCatching { descriptor?.close() }
            descriptor = null
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= 29) {
                    if (success) {
                        runCatching {
                            context.contentResolver.update(uri, ContentValues().apply {
                                put(MediaStore.Audio.Media.IS_PENDING, 0)
                            }, null, null)
                        }
                    } else {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                } else if (!success) {
                    runCatching { legacyFile?.delete() }
                }
            }
            legacyFile = null
        }
        return if (success) uri else null
    }
}
