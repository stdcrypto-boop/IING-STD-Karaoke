package com.iingstd.karaoke

import android.content.ContentValues
import android.content.Context
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
 * Saved in Music/IING STD Karaoke via MediaStore.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: Uri? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var legacyFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(): Uri {
        check(recorder == null) { "Recording already in progress" }
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
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128000)
                r.setAudioSamplingRate(44100)
                if (fd != null) r.setOutputFile(fd.fileDescriptor)
                else r.setOutputFile(legacyFile?.absolutePath ?: error("Missing output file"))
                r.prepare()
                r.start()
            }
            return uri
        } catch (e: Exception) {
            recorder?.runCatching { reset(); release() }
            recorder = null
            descriptor?.close()
            descriptor = null
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.delete(uri, null, null)
            else legacyFile?.delete()
            legacyFile = null
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
                if (Build.VERSION.SDK_INT >= 29) {
                    if (success) {
                        context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }, null, null)
                    } else context.contentResolver.delete(uri, null, null)
                } else if (!success) legacyFile?.delete()
            }
        }
        legacyFile = null
        return if (success) uri else null
    }
}
