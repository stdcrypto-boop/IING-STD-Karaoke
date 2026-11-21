package com.iingstd.karaoke

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.exp

/** Original, generated instrumental practice tracks. No third-party recordings are bundled. */
object OfflineKaraoke {
    data class Line(val atMs: Long, val text: String)
    data class Track(val id: String, val title: String, val style: String, val bpm: Int, val root: Int, val lyrics: List<Line>)
    val tracks = listOf(
        Track("dangdut_senja", "Senja di Kampung", "Dangdut klasik • Original", 96, 57, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Senja datang menyapa desa"),
            Line(10000, "Angin lembut membawa cerita"), Line(15000, "Langkah kecil menuju rumah"),
            Line(20000, "Hati tenang penuh bahagia"), Line(25000, "♪ Musik instrumental ♪"),
            Line(30000, "Mari bernyanyi bersama"), Line(35000, "Irama indah menyatukan kita"),
            Line(40000, "Senyum hangat takkan terlupa"), Line(45000, "♪ Outro ♪")
        )),
        Track("koplo_gembira", "Goyang Bahagia", "Dangdut koplo • Original", 132, 60, listOf(
            Line(0, "♪ Intro koplo ♪"), Line(5000, "Dengar kendang mulai berbunyi"),
            Line(10000, "Ayo kawan kita menari"), Line(15000, "Lupakan resah di dalam hati"),
            Line(20000, "Sambut pagi penuh energi"), Line(25000, "♪ Musik instrumental ♪"),
            Line(30000, "Goyang kanan goyang kiri"), Line(35000, "Bernyanyi riang sampai nanti"),
            Line(40000, "Kita bersama bersuka hati"), Line(45000, "♪ Outro ♪")
        )),
        Track("minang_rindu", "Rindu Ranah", "Nuansa Minang • Original", 88, 55, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Jauh di rantau teringat rumah"),
            Line(10000, "Bukit hijau di tanah kelahiran"), Line(15000, "Doa ibu selalu menyertai"),
            Line(20000, "Membawa damai di dalam hati"), Line(25000, "♪ Musik instrumental ♪"),
            Line(30000, "Kelak pulang membawa cerita"), Line(35000, "Bersua lagi dengan saudara"),
            Line(40000, "Rindu ini tetap terjaga"), Line(45000, "♪ Outro ♪")
        ))
    )

    fun prepare(context: Context, track: Track): File {
        val folder = File(context.filesDir, "karaoke_original").apply { mkdirs() }
        val output = File(folder, track.id + ".wav")
        if (output.exists() && output.length() > 100_000) return output
        val rate = 22050
        val seconds = 50
        val total = rate * seconds
        val beatSeconds = 60.0 / track.bpm
        val scale = intArrayOf(0, 3, 5, 7, 10, 7, 5, 3)
        val pcmBytes = total * 2
        FileOutputStream(output).use { stream ->
            fun writeInt(v: Int) { for (n in 0..3) stream.write((v ushr (8 * n)) and 255) }
            fun writeShort(v: Int) { stream.write(v and 255); stream.write((v ushr 8) and 255) }
            stream.write("RIFF".toByteArray(Charsets.US_ASCII)); writeInt(36 + pcmBytes)
            stream.write("WAVEfmt ".toByteArray(Charsets.US_ASCII)); writeInt(16)
            writeShort(1); writeShort(1); writeInt(rate); writeInt(rate * 2)
            writeShort(2); writeShort(16)
            stream.write("data".toByteArray(Charsets.US_ASCII)); writeInt(pcmBytes)
            val buffer = ByteArray(8192)
            var frame = 0
            while (frame < total) {
                val count = minOf(buffer.size / 2, total - frame)
                for (j in 0 until count) {
                    val t = (frame + j).toDouble() / rate
                    val beat = t / beatSeconds
                    val phase = beat - kotlin.math.floor(beat)
                    val step = (beat.toInt() / 2) % scale.size
                    val note = track.root + scale[step]
                    val hz = 440.0 * Math.pow(2.0, (note - 69) / 12.0)
                    val chord = 0.15 * sin(2 * PI * hz * t) +
                        0.09 * sin(2 * PI * hz * 1.25 * t) +
                        0.06 * sin(2 * PI * hz * 1.5 * t)
                    val bassHz = hz / 4
                    val bass = 0.23 * sin(2 * PI * bassHz * t) * exp(-3.5 * phase)
                    val kickPhase = beat % 2
                    val kick = if (kickPhase < 0.22) 0.32 * sin(2 * PI * (90 - 45 * kickPhase) * t) * exp(-19 * kickPhase) else 0.0
                    val tick = if (phase < 0.06) 0.075 * sin(2 * PI * 2300 * t) * exp(-75 * phase) else 0.0
                    val leadNote = track.root + 12 + scale[(beat.toInt() + 2) % scale.size]
                    val leadHz = 440.0 * Math.pow(2.0, (leadNote - 69) / 12.0)
                    val lead = if ((beat.toInt() % 8) < 4) 0.12 * sin(2 * PI * leadHz * t) * exp(-2 * phase) else 0.0
                    val fade = minOf(1.0, t / 0.5, (seconds - t) / 1.5).coerceAtLeast(0.0)
                    val sample = ((chord + bass + kick + tick + lead) * fade * 0.8).coerceIn(-1.0, 1.0)
                    val v = (sample * 32767).toInt()
                    buffer[j * 2] = v.toByte()
                    buffer[j * 2 + 1] = (v ushr 8).toByte()
                }
                stream.write(buffer, 0, count * 2)
                frame += count
            }
        }
        return output
    }
}
