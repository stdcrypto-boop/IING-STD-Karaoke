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
        )),
        Track("hujan_rindu", "Hujan Membawa Rindu", "Dangdut melayu • Original", 90, 55, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Hujan turun di ujung malam"),
            Line(10000, "Membawa kisah yang terpendam"), Line(15000, "Kuingat senyum di masa lalu"),
            Line(20000, "Yang selalu hadir di kalbu"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Hujan turun di ujung malam"), Line(35000, "Membawa kisah yang terpendam"),
            Line(40000, "Kuingat senyum di masa lalu"), Line(45000, "♪ Outro ♪")
        )),
        Track("bulan_purnama", "Di Bawah Purnama", "Dangdut klasik • Original", 102, 59, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Bulan purnama terang berseri"),
            Line(10000, "Menyinari jalan yang sepi"), Line(15000, "Kunyanyikan sebuah cerita"),
            Line(20000, "Tentang harapan dan cinta"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Bulan purnama terang berseri"), Line(35000, "Menyinari jalan yang sepi"),
            Line(40000, "Kunyanyikan sebuah cerita"), Line(45000, "♪ Outro ♪")
        )),
        Track("langkah_cinta", "Langkah Cinta", "Dangdut modern • Original", 116, 62, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Kita melangkah bersama"),
            Line(10000, "Menyusuri jalan berdua"), Line(15000, "Irama hati terus bernyanyi"),
            Line(20000, "Menemani setiap hari"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Kita melangkah bersama"), Line(35000, "Menyusuri jalan berdua"),
            Line(40000, "Irama hati terus bernyanyi"), Line(45000, "♪ Outro ♪")
        )),
        Track("pesta_desa", "Pesta di Desa", "Dangdut koplo • Original", 138, 60, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Lampu menyala di tengah desa"),
            Line(10000, "Semua datang penuh gembira"), Line(15000, "Tabuhan kendang mulai terdengar"),
            Line(20000, "Ayo menari sampai fajar"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Lampu menyala di tengah desa"), Line(35000, "Semua datang penuh gembira"),
            Line(40000, "Tabuhan kendang mulai terdengar"), Line(45000, "♪ Outro ♪")
        )),
        Track("pantai_senja", "Pantai di Waktu Senja", "Dangdut santai • Original", 94, 57, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Ombak berlari menuju pantai"),
            Line(10000, "Angin membawa harum melati"), Line(15000, "Kutunggu kabar dari seberang"),
            Line(20000, "Di bawah langit yang terang"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Ombak berlari menuju pantai"), Line(35000, "Angin membawa harum melati"),
            Line(40000, "Kutunggu kabar dari seberang"), Line(45000, "♪ Outro ♪")
        )),
        Track("janji_kita", "Janji di Ujung Jalan", "Dangdut melayu • Original", 86, 53, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Kau pernah berjanji padaku"),
            Line(10000, "Menjaga cerita yang dulu"), Line(15000, "Kini kupandang jalan panjang"),
            Line(20000, "Menyimpan harapan yang datang"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Kau pernah berjanji padaku"), Line(35000, "Menjaga cerita yang dulu"),
            Line(40000, "Kini kupandang jalan panjang"), Line(45000, "♪ Outro ♪")
        )),
        Track("bunga_melati", "Bunga Melati", "Dangdut klasik • Original", 100, 60, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Bunga melati di halaman"),
            Line(10000, "Harumnya terbawa angin"), Line(15000, "Senyummu jadi kenangan"),
            Line(20000, "Yang kusimpan dalam batin"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Bunga melati di halaman"), Line(35000, "Harumnya terbawa angin"),
            Line(40000, "Senyummu jadi kenangan"), Line(45000, "♪ Outro ♪")
        )),
        Track("rantau_hati", "Rantau di Hati", "Nuansa Minang • Original", 84, 55, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Jalan berliku menuju rantau"),
            Line(10000, "Bukit tinggi tinggal di belakang"), Line(15000, "Walau jauh dari kampung halaman"),
            Line(20000, "Kasih keluarga tetap terkenang"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Jalan berliku menuju rantau"), Line(35000, "Bukit tinggi tinggal di belakang"),
            Line(40000, "Walau jauh dari kampung halaman"), Line(45000, "♪ Outro ♪")
        )),
        Track("kendang_meriah", "Kendang Meriah", "Dangdut koplo • Original", 144, 62, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Dum tak dum kendang berbunyi"),
            Line(10000, "Mengajak semua ikut menari"), Line(15000, "Langkah kaki mengikuti nada"),
            Line(20000, "Pesta meriah penuh tawa"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Dum tak dum kendang berbunyi"), Line(35000, "Mengajak semua ikut menari"),
            Line(40000, "Langkah kaki mengikuti nada"), Line(45000, "♪ Outro ♪")
        )),
        Track("bintang_malam", "Bintang Malam", "Dangdut romantis • Original", 80, 57, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Bintang kecil di langit malam"),
            Line(10000, "Menjadi saksi kisah terpendam"), Line(15000, "Kirim salam lewat cahaya"),
            Line(20000, "Untuk dia yang jauh di sana"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Bintang kecil di langit malam"), Line(35000, "Menjadi saksi kisah terpendam"),
            Line(40000, "Kirim salam lewat cahaya"), Line(45000, "♪ Outro ♪")
        )),
        Track("cinta_sederhana", "Cinta Sederhana", "Dangdut modern • Original", 112, 59, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Tak perlu emas dan permata"),
            Line(10000, "Cukup senyum yang setia"), Line(15000, "Kita bernyanyi bersama"),
            Line(20000, "Menjaga cinta sederhana"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Tak perlu emas dan permata"), Line(35000, "Cukup senyum yang setia"),
            Line(40000, "Kita bernyanyi bersama"), Line(45000, "♪ Outro ♪")
        )),
        Track("pasar_malam", "Pasar Malam", "Dangdut ceria • Original", 124, 60, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Lampu warna warni menyala"),
            Line(10000, "Anak kecil tertawa bahagia"), Line(15000, "Alunan musik dari kejauhan"),
            Line(20000, "Membuat hati ikut berdendang"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Lampu warna warni menyala"), Line(35000, "Anak kecil tertawa bahagia"),
            Line(40000, "Alunan musik dari kejauhan"), Line(45000, "♪ Outro ♪")
        )),
        Track("jalan_pulang", "Jalan Pulang", "Dangdut lawas • Original", 92, 53, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Di persimpangan kuterdiam"),
            Line(10000, "Mengingat rumah yang tenteram"), Line(15000, "Meski langkah jauh berjalan"),
            Line(20000, "Selalu ada jalan pulang"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Di persimpangan kuterdiam"), Line(35000, "Mengingat rumah yang tenteram"),
            Line(40000, "Meski langkah jauh berjalan"), Line(45000, "♪ Outro ♪")
        )),
        Track("rindu_ibu", "Doa untuk Ibu", "Dangdut melayu • Original", 78, 55, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Ibu dengarlah lagu ini"),
            Line(10000, "Dari anakmu di sini"), Line(15000, "Setiap doa yang kau kirim"),
            Line(20000, "Menjadi terang dalam hati"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Ibu dengarlah lagu ini"), Line(35000, "Dari anakmu di sini"),
            Line(40000, "Setiap doa yang kau kirim"), Line(45000, "♪ Outro ♪")
        )),
        Track("sawah_hijau", "Sawah Menghijau", "Dangdut klasik • Original", 106, 57, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Pagi cerah di hamparan sawah"),
            Line(10000, "Burung bernyanyi riang gembira"), Line(15000, "Petani tersenyum penuh harapan"),
            Line(20000, "Syukur mengalun sepanjang jalan"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Pagi cerah di hamparan sawah"), Line(35000, "Burung bernyanyi riang gembira"),
            Line(40000, "Petani tersenyum penuh harapan"), Line(45000, "♪ Outro ♪")
        )),
        Track("goyang_sore", "Goyang Sore Hari", "Dangdut koplo • Original", 136, 64, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Sore tiba musik menyapa"),
            Line(10000, "Kita berkumpul tertawa"), Line(15000, "Kendang berdetak tambah cepat"),
            Line(20000, "Semangat menari makin hebat"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Sore tiba musik menyapa"), Line(35000, "Kita berkumpul tertawa"),
            Line(40000, "Kendang berdetak tambah cepat"), Line(45000, "♪ Outro ♪")
        )),
        Track("perahu_rindu", "Perahu Rindu", "Nuansa Minang • Original", 89, 58, listOf(
            Line(0, "♪ Intro instrumental ♪"), Line(5000, "Perahu kecil berlayar jauh"),
            Line(10000, "Menyusuri sungai yang teduh"), Line(15000, "Pesan rindu kutitip angin"),
            Line(20000, "Untuk kampung yang kurindukan"), Line(25000, "♪ Jeda instrumental ♪"),
            Line(30000, "Perahu kecil berlayar jauh"), Line(35000, "Menyusuri sungai yang teduh"),
            Line(40000, "Pesan rindu kutitip angin"), Line(45000, "♪ Outro ♪")
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
