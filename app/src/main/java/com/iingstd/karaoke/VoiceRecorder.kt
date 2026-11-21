package com.iingstd.karaoke

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Microphone-only AAC/M4A recording. Does NOT mix the backing track.
 * Saved in Music/IING STD Karaoke via MediaStore.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Uri {
        check(recorder == null) { "Recording already in progress" }
        val name = "IING-VOICE-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/IING STD Karaoke")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create recording file")
        output = uri
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "w") ?: error("Cannot open recording file")
            descriptor = fd
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = r
            run {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128000)
                r.setAudioSamplingRate(44100)
                r.setOutputFile(fd.fileDescriptor)
                r.prepare()
                r.start()
            }
            return uri
        } catch (e: Exception) {
            recorder?.runCatching { reset(); release() }
            recorder = null
            descriptor?.close()
            descriptor = null
            context.contentResolver.delete(uri, null, null)
            output = null
            throw e
        }
    }

    fun stop(): Uri? {
        val r = recorder ?: return null
        val uri = output
        recorder = null
        output = null
        var success = false
        try {
            r.stop()
            success = true
        } finally {
            runCatching { r.reset() }
            r.release()
            descriptor?.close()
            descriptor = null
            if (uri != null) {
                if (success) {
                    context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }, null, null)
                } else context.contentResolver.delete(uri, null, null)
            }
        }
        return if (success) uri else null
    }
}
