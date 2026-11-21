package com.iingstd.karaoke

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.os.Build
import java.io.File
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private val Pink = Color(0xFFFF5EAD)
private val Dark = Color(0xFF100C1B)
private data class Lyric(val timeMs: Long, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val player = remember {
                ExoPlayer.Builder(this).build().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true
                    )
                    setHandleAudioBecomingNoisy(true)
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            val library = remember { SongLibrary(this) }
            val recorder = remember { VoiceRecorder(this) }
            var recording by remember { mutableStateOf(false) }
            var recordingPaused by remember { mutableStateOf(false) }
            var microphoneLevel by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(recording, recordingPaused) {
                while (recording) {
                    microphoneLevel = if (recordingPaused) 0f else recorder.peakLevel()
                    delay(120)
                }
                microphoneLevel = 0f
            }
            var externalMic by remember { mutableStateOf(false) }
            var recordingPauseStartedAt by remember { mutableLongStateOf(0L) }
            var recordingPausedMs by remember { mutableLongStateOf(0L) }
            var recordingStartedAt by remember { mutableLongStateOf(0L) }
            var recordingSeconds by remember { mutableLongStateOf(0L) }
            LaunchedEffect(recording) {
                while (recording) {
                    if (!recordingPaused) {
                        recordingSeconds = ((android.os.SystemClock.elapsedRealtime() - recordingStartedAt - recordingPausedMs) / 1000).coerceAtLeast(0)
                    }
                    delay(500)
                }
            }
            var recordingStatus by remember { mutableStateOf("") }
            var sessionSongUri by remember { mutableStateOf<String?>(null) }
            var sessionSongTitle by remember { mutableStateOf<String?>(null) }
            var sessionStartPositionMs by remember { mutableLongStateOf(0L) }
            var sessionMixCompatible by remember { mutableStateOf(true) }
            var sessionMixWarning by remember { mutableStateOf("") }
            val recordingPrefs = remember { getSharedPreferences("karaoke_recording", MODE_PRIVATE) }
            val mixScope = rememberCoroutineScope()
            var mixing by remember { mutableStateOf(false) }
            var mixStatus by remember { mutableStateOf("") }
            var musicGain by remember { mutableFloatStateOf(0.65f) }
            var voiceGain by remember { mutableFloatStateOf(1f) }
            var syncAdjustmentMs by remember { mutableFloatStateOf(0f) }
            var mixedFile by remember {
                mutableStateOf(
                    recordingPrefs.getString("last_mix_path", null)?.let(::File)?.takeIf {
                        it.isFile && it.length() > 44L
                    }
                )
            }
            var lastRecording by remember {
                mutableStateOf(recordingPrefs.getString("last_uri", null)?.let(Uri::parse))
            }
            DisposableEffect(recorder) {
                onDispose {
                    if (recorder.isRecording) runCatching { recorder.stop() }
                }
            }
            var history by remember { mutableStateOf(library.history()) }
            var favorites by remember { mutableStateOf(library.favorites()) }
            var currentSong by remember { mutableStateOf<SavedSong?>(null) }
            var mode by remember { mutableStateOf("Offline") }
            var url by remember { mutableStateOf("") }
            var youtubeVideoId by remember { mutableStateOf<String?>(null) }
            var localVideoUri by remember { mutableStateOf<String?>(null) }
            var track by remember { mutableStateOf("Belum ada lagu") }
            var playing by remember { mutableStateOf(false) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var pitch by remember { mutableFloatStateOf(1f) }
            var volume by remember { mutableFloatStateOf(1f) }
            var repeatOne by remember { mutableStateOf(false) }
            var lyrics by remember { mutableStateOf(emptyList<Lyric>()) }
            var lyricOffsetMs by remember { mutableFloatStateOf(0f) }
            val lyricListState = rememberLazyListState()
            var error by remember { mutableStateOf("") }
            var showPlaybackOptions by remember { mutableStateOf(false) }
            var showMicOptions by remember { mutableStateOf(false) }
            var showMixOptions by remember { mutableStateOf(false) }
            fun beginRecording() {
                try {
                    if (youtubeVideoId != null) {
                        recordingStatus = "Rekaman dengan musik YouTube belum didukung. Pilih MP3 atau MP4 offline terlebih dahulu."
                        return
                    }
                    val song = currentSong
                    if (song == null || song.online) {
                        recordingStatus = "Pilih MP3 atau MP4 offline dahulu agar hasil rekaman bisa berisi musik dan vokal."
                        return
                    }
                    // Always restart the backing track so a new take has a clean timeline.
                    player.seekTo(0)
                    player.playbackParameters = PlaybackParameters(1f, 1f)
                    speed = 1f
                    pitch = 1f
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    repeatOne = false
                    player.play()
                    sessionSongUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    sessionSongTitle = song.title
                    sessionStartPositionMs = player.currentPosition.coerceAtLeast(0)
                    sessionMixCompatible = player.isPlaying &&
                        player.playbackParameters.speed == 1f &&
                        player.playbackParameters.pitch == 1f &&
                        player.repeatMode == Player.REPEAT_MODE_OFF
                    sessionMixWarning = if (sessionMixCompatible) "" else
                        "Mixing membutuhkan musik sedang diputar normal (kecepatan dan pitch 1×, tanpa repeat)"
                    recorder.start(preferExternalMic = externalMic)
                    // The previous mix belongs to an older vocal take.
                    mixedFile = null
                    mixStatus = ""
                    recordingPrefs.edit().remove("last_mix_path").apply()
                    recording = true
                    recordingPaused = false
                    recordingPausedMs = 0L
                    recordingStartedAt = android.os.SystemClock.elapsedRealtime()
                    recordingSeconds = 0
                    recordingStatus = if (sessionMixCompatible && sessionSongUri?.let { Uri.parse(it).scheme in listOf("content", "file") } == true) "Merekam vokal. Musik lokal akan digabung otomatis." else "Merekam vokal saja. Putar MP3/MP4 lokal pada kecepatan normal agar musik bisa digabung otomatis."
                } catch (e: Exception) {
                    recordingStatus = "Gagal merekam: " + (e.message ?: "kesalahan audio")
                }
            }
            val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) beginRecording() else recordingStatus = "Izin mikrofon diperlukan"
            }
            fun playSong(song: SavedSong) {
                try {
                    youtubeVideoId = null
                    localVideoUri = if (!song.online && (song.uri.endsWith(".mp4", true) ||
                        runCatching { contentResolver.getType(Uri.parse(song.uri))?.startsWith("video/") == true }.getOrDefault(false))) song.uri else null
                    player.setMediaItem(MediaItem.fromUri(song.uri))
                    player.prepare()
                    player.play()
                    player.playbackParameters = PlaybackParameters(speed, pitch)
                    currentSong = song
                    lyrics = emptyList()
                    track = song.title
                    library.record(song.copy(playedAt = System.currentTimeMillis()))
                    history = library.history()
                    error = ""
                } catch (e: Exception) { error = e.message ?: "Gagal memutar lagu" }
            }
            val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                        playSong(SavedSong(uri.lastPathSegment?.substringAfterLast('/') ?: "Audio lokal", uri.toString(), false, System.currentTimeMillis()))
                    } catch (e: Exception) { error = e.message ?: "Tidak dapat membuka audio" }
                }
            }
            val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        val content = contentResolver.openInputStream(uri)?.use {
                            BufferedReader(InputStreamReader(it)).readText()
                        }.orEmpty()
                        lyrics = parseLrc(content)
                        lyricOffsetMs = 0f
                        error = if (lyrics.isEmpty()) "Tidak ada timestamp LRC yang valid" else ""
                    } catch (e: Exception) { error = e.message ?: "Gagal membaca lirik" }
                }
            }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(exception: PlaybackException) {
                        error = "Tidak dapat memutar audio: " + (exception.message ?: "format atau jaringan tidak didukung")
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }
            LaunchedEffect(player) {
                while (true) {
                    position = player.currentPosition.coerceAtLeast(0)
                    duration = player.duration.coerceAtLeast(0)
                    playing = player.isPlaying
                    delay(250)
                }
            }
            val activeLyricIndex = lyrics.indexOfLast { it.timeMs <= position + lyricOffsetMs.toLong() }
            LaunchedEffect(activeLyricIndex, lyrics) {
                if (activeLyricIndex >= 0) lyricListState.animateScrollToItem(activeLyricIndex)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Pink, background = Dark, surface = Color(0xFF21182D))) {
                Column(
                    Modifier.fillMaxSize().background(Dark).verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🎤 IING STD KARAOKE", color = Pink, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Your stage. Your voice.", color = Color.LightGray)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Offline", "Online").forEach { item ->
                            Button(onClick = { mode = item }, colors = ButtonDefaults.buttonColors(
                                containerColor = if (mode == item) Pink else Color(0xFF392A47)
                            )) { Text(item) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (mode == "Offline") {
                        Button(onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("📁 Pilih MP3 atau video MP4 dari HP")
                        }
                    } else {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Tempel link YouTube atau audio HTTPS") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val link = url.trim()
                            val parsed = Uri.parse(link)
                            val host = parsed.host?.lowercase().orEmpty()
                            if (parsed.scheme?.lowercase() != "https" || host.isBlank()) {
                                error = "Masukkan URL HTTPS audio yang valid"
                            } else if (host == "youtu.be" || host == "youtube.com" ||
                                host == "www.youtube.com" || host == "m.youtube.com" ||
                                host == "music.youtube.com") {
                                val id = if (host == "youtu.be") parsed.pathSegments.firstOrNull()
                                    else if (parsed.pathSegments.firstOrNull() == "shorts" ||
                                        parsed.pathSegments.firstOrNull() == "embed" ||
                                        parsed.pathSegments.firstOrNull() == "live") parsed.pathSegments.getOrNull(1)
                                    else parsed.getQueryParameter("v")
                                if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                    player.stop()
                                    player.clearMediaItems()
                                    localVideoUri = null
                                    currentSong = null
                                    lyrics = emptyList()
                                    youtubeVideoId = id
                                    track = "YouTube • " + id
                                    error = ""
                                } else {
                                    error = "Link YouTube tidak valid. Salin link video dari tombol Bagikan."
                                }
                            } else {
                                try {
                                    playSong(SavedSong(parsed.lastPathSegment ?: "Streaming online", link, true, System.currentTimeMillis()))
                                } catch (e: Exception) { error = e.message ?: "Streaming gagal" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar streaming") }
                    }
                    localVideoUri?.let { selectedUri ->
                        Text("🎬 Video karaoke offline", color = Pink)
                        key(selectedUri) {
                            AndroidView(
                                factory = { context ->
                                    PlayerView(context).apply {
                                        this.player = player
                                        useController = true
                                    }
                                },
                                update = { view -> view.player = player },
                                onRelease = { view -> view.player = null },
                                modifier = Modifier.fillMaxWidth().height(230.dp)
                            )
                        }
                    }
                    youtubeVideoId?.let { videoId ->
                        Text("▶ Video YouTube", color = Pink)
                        Text("Putar video di bawah. Audio YouTube tidak dapat diekspor atau dicampur dengan rekaman dalam versi ini.",
                            color = Color.LightGray, fontSize = 12.sp)
                        key(videoId) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        webChromeClient = WebChromeClient()
                                        webViewClient = WebViewClient()
                                        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:#000"><iframe width="100%" height="100%" src="https://www.youtube-nocookie.com/embed/$videoId?playsinline=1" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture" allowfullscreen></iframe></body></html>"""
                                        loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "UTF-8", null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(230.dp),
                                onRelease = { webView ->
                                    webView.stopLoading()
                                    webView.loadUrl("about:blank")
                                    webView.destroy()
                                }
                            )
                        }
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                            runCatching { startActivity(intent) }.onFailure {
                                error = "Tidak dapat membuka YouTube: " + (it.message ?: "aplikasi tidak tersedia")
                            }
                        }) { Text("Buka di aplikasi YouTube jika video tidak bisa diputar") }
                    }
                    Spacer(Modifier.height(12.dp))
                    currentSong?.let { song ->
                        val saved = favorites.any { it.uri == song.uri }
                        TextButton(onClick = {
                            library.toggleFavorite(song)
                            favorites = library.favorites()
                        }) { Text(if (saved) "♥ Tersimpan" else "♡ Simpan lagu") }
                    }
                    Text(track, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Slider(value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Color.LightGray)
                        Text(formatTime(duration), color = Color.LightGray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { player.seekTo((position - 10000).coerceAtLeast(0)) }) { Text("⏪ 10s") }
                        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (playing) "⏸ Jeda" else "▶ Putar")
                        }
                        Button(onClick = { player.seekTo(position + 10000) }) { Text("10s ⏩") }
                    }
                    TextButton(onClick = { showPlaybackOptions = !showPlaybackOptions }) {
                        Text(if (showPlaybackOptions) "▴ Tutup pengaturan pemutar" else "⚙ Pengaturan pemutar")
                    }
                    if (showPlaybackOptions) {
                    Spacer(Modifier.height(8.dp))
                    Text("Kecepatan: ${"%.2f".format(speed)}×", color = Color.LightGray)
                    Slider(value = speed, onValueChange = {
                        speed = it
                        player.playbackParameters = PlaybackParameters(it, pitch)
                    }, valueRange = 0.5f..1.5f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Volume", color = Color.LightGray)
                        Slider(value = volume, onValueChange = {
                            volume = it
                            player.volume = it
                        }, modifier = Modifier.weight(1f))
                        Text("${(volume * 100).toInt()}%", color = Color.LightGray)
                    }
                    TextButton(onClick = {
                        repeatOne = !repeatOne
                        player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }) { Text(if (repeatOne) "🔁 Ulangi lagu: ON" else "🔁 Ulangi lagu: OFF") }
                    }
                    Text("Level mikrofon saat merekam", color = Pink)
                    LinearProgressIndicator(
                        progress = { microphoneLevel },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        color = if (microphoneLevel > 0.9f) Color.Red else Pink,
                        trackColor = Color(0xFF392A47)
                    )
                    Text(
                        if (recordingPaused) "Rekaman dijeda"
                        else if (recording) "Indikator input mikrofon • ${(microphoneLevel * 100).toInt()}%"
                        else "Mulai merekam untuk melihat level mikrofon",
                        color = Color.LightGray, fontSize = 12.sp
                    )
                    TextButton(onClick = { showMicOptions = !showMicOptions }) {
                        Text(if (showMicOptions) "▴ Tutup pengaturan mikrofon" else "🎙 Pilihan mikrofon")
                    }
                    if (showMicOptions) {
                    Text("Indikator ini bukan pemantauan suara melalui speaker. Audio mikrofon tidak diputar ulang agar tidak menimbulkan feedback.", color = Color.LightGray, fontSize = 12.sp)
                    Text("Sumber mikrofon", color = Pink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (externalMic) "Coba mikrofon headset / eksternal" else "Gunakan mikrofon HP", color = Color.LightGray,
                            modifier = Modifier.weight(1f))
                        Switch(checked = externalMic, onCheckedChange = { externalMic = it },
                            enabled = !recording)
                    }
                    Text("Sakelar ini hanya memilih sumber input saat mulai rekaman, BUKAN tombol hidup/mati mikrofon atau suara balik ke headset. " +
                        "Untuk speaker aktif atau Bluetooth: pilih mikrofon HP jika tidak ada mikrofon eksternal. " +
                        "Musik lokal digabung dari file asli, bukan direkam ulang dari speaker. " +
                        "Mikrofon Bluetooth mungkin tidak mendukung perekaman bersamaan dengan output musik.",
                        color = Color.LightGray, fontSize = 12.sp)
                    }
                    Button(onClick = {
                        if (recording) {
                            try {
                                val saved = recorder.stop()
                                recording = false
                                recordingPaused = false
                                if (saved != null) {
                                    val elapsed = android.os.SystemClock.elapsedRealtime() - recordingStartedAt - recordingPausedMs
                                    val expectedPosition = sessionStartPositionMs + elapsed.coerceAtLeast(0)
                                    val drift = kotlin.math.abs(player.currentPosition - expectedPosition)
                                    if (player.currentMediaItem?.localConfiguration?.uri?.toString() != sessionSongUri ||
                                        !player.isPlaying || player.playbackParameters.speed != 1f ||
                                        player.playbackParameters.pitch != 1f || drift > 1500L
                                    ) {
                                        sessionMixCompatible = false
                                        sessionMixWarning = "Posisi atau pemutaran musik berubah selama rekaman (selisih ${drift} ms)"
                                    }
                                    lastRecording = saved
                                    recordingPrefs.edit()
                                        .putString("last_uri", saved.toString())
                                        .putString("last_backing_uri", sessionSongUri)
                                        .putString("last_backing_title", sessionSongTitle)
                                        .putLong("last_backing_start_ms", sessionStartPositionMs)
                                        .putBoolean("last_mix_compatible", sessionMixCompatible)
                                        .putString("last_mix_warning", sessionMixWarning)
                                        .apply()
                                }
                                recordingStatus = if (saved != null) "Vokal tersimpan. Menyiapkan hasil karaoke…" else "Rekaman gagal"
                                // A local backing track can be mixed after recording; the microphone
                                // recording alone never contains ExoPlayer's music output.
                                val backing = sessionSongUri?.let(Uri::parse)
                                if (saved != null && sessionMixCompatible && backing != null &&
                                    (backing.scheme == "content" || backing.scheme == "file")) {
                                    val backingStart = sessionStartPositionMs
                                    val selectedMusicGain = musicGain
                                    val selectedVoiceGain = voiceGain
                                    mixing = true
                                    mixStatus = "Menggabungkan musik dan vokal otomatis…"
                                    mixScope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                val folder = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "IING STD Karaoke")
                                                check(folder.exists() || folder.mkdirs()) { "Folder output tidak tersedia" }
                                                val destination = File(folder, "IING-MIX-${System.currentTimeMillis()}.wav")
                                                try {
                                                    AudioMixEngine.mixToWav(
                                                        this@MainActivity, backing, saved, backingStart,
                                                        destination, selectedMusicGain, selectedVoiceGain
                                                    )
                                                } catch (e: Exception) {
                                                    destination.delete()
                                                    throw e
                                                }
                                            }
                                            mixedFile = result
                                            recordingPrefs.edit().putString("last_mix_path", result.absolutePath).apply()
                                            mixStatus = "Hasil karaoke (musik + vokal) siap: ${result.name}"
                                        } catch (e: Exception) {
                                            mixStatus = "Penggabungan otomatis gagal: ${e.message ?: "format tidak didukung"}. Vokal asli tetap tersimpan."
                                        } finally {
                                            mixing = false
                                        }
                                    }
                                } else if (saved != null) {
                                    recordingStatus = "Vokal tersimpan. Musik hanya bisa digabung otomatis dari lagu lokal yang diputar normal tanpa jeda."
                                }
                            } catch (e: Exception) {
                                recording = false
                                recordingPaused = false
                                recordingStatus = "Gagal menyimpan rekaman: " + (e.message ?: "kesalahan audio")
                            }
                        } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginRecording()
                        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (recording) "⏹ Selesai & simpan rekaman" else "🎙 Mulai rekaman baru (musik + vokal)")
                    }
                    Text(recordingStatus, color = Pink, fontSize = 13.sp)
                    if (recording) {
                        Text((if (recordingPaused) "⏸ DIJEDA  " else "● REC  ") + formatTime(recordingSeconds * 1000), color = Pink, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = {
                            try {
                                if (recordingPaused) {
                                    recorder.resume()
                                    recordingPausedMs += android.os.SystemClock.elapsedRealtime() - recordingPauseStartedAt
                                    recordingPaused = false
                                    recordingStatus = "Rekaman dilanjutkan"
                                } else {
                                    recorder.pause()
                                    sessionMixCompatible = false
                                    sessionMixWarning = "Rekaman sempat dijeda; musik dan vokal tidak dapat disinkronkan otomatis"
                                    recordingPauseStartedAt = android.os.SystemClock.elapsedRealtime()
                                    recordingPaused = true
                                    recordingStatus = "Rekaman dijeda"
                                }
                            } catch (e: Exception) {
                                recordingStatus = "Gagal mengubah jeda: " + (e.message ?: "kesalahan perekam")
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (recordingPaused) "▶ Lanjutkan rekaman" else "⏸ Jeda rekaman")
                        }
                    }
                    if (!recording && lastRecording != null) {
                        OutlinedButton(onClick = {
                            try {
                                player.setMediaItem(MediaItem.fromUri(lastRecording!!))
                                player.prepare()
                                player.play()
                                track = "Hasil rekaman suara"
                                currentSong = null
                                lyrics = emptyList()
                                error = ""
                            } catch (e: Exception) {
                                error = "Tidak bisa memutar rekaman: " + (e.message ?: "file tidak tersedia")
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("▶ Dengarkan vokal saja") }
                    }
                    if (!recording && lastRecording != null) {
                        OutlinedButton(onClick = {
                            try {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/mp4"
                                    val source = lastRecording!!
                                    val shareUri = if (source.scheme == "file" && Build.VERSION.SDK_INT < 29) {
                                        FileProvider.getUriForFile(this@MainActivity,
                                            "com.iingstd.karaoke.fileprovider",
                                            File(requireNotNull(source.path)))
                                    } else source
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    clipData = android.content.ClipData.newUri(contentResolver, "Rekaman karaoke", shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(share, "Bagikan rekaman karaoke"))
                            } catch (e: Exception) {
                                recordingStatus = "Tidak dapat membagikan rekaman: " + (e.message ?: "kesalahan")
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("↗ Bagikan rekaman terakhir") }
                    }
                    if (!recording && lastRecording != null) {
                        val backingUri = recordingPrefs.getString("last_backing_uri", null)
                        if (!backingUri.isNullOrBlank()) {
                            Text(
                                "Referensi musik rekaman: " +
                                    (recordingPrefs.getString("last_backing_title", null)
                                        ?: Uri.parse(backingUri).lastPathSegment ?: "Lagu karaoke") +
                                    " • mulai " + formatTime(recordingPrefs.getLong("last_backing_start_ms", 0L)),
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (!recording && lastRecording != null) {
                        Spacer(Modifier.height(12.dp))
                        Text("🎚 Mixing musik + vokal (WAV)", color = Pink, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showMixOptions = !showMixOptions }) {
                            Text(if (showMixOptions) "▴ Tutup pengaturan mixing" else "⚙ Atur volume & sinkronisasi")
                        }
                        if (showMixOptions) {
                        Text("Musik: ${(musicGain * 100).toInt()}%", color = Color.LightGray)
                        Slider(value = musicGain, onValueChange = { musicGain = it }, valueRange = 0f..1f)
                        Text("Vokal: ${(voiceGain * 100).toInt()}%", color = Color.LightGray)
                        Slider(value = voiceGain, onValueChange = { voiceGain = it }, valueRange = 0f..1f)
                        Text("Koreksi sinkronisasi musik: ${syncAdjustmentMs.toInt()} ms", color = Color.LightGray)
                        Slider(
                            value = syncAdjustmentMs,
                            onValueChange = { syncAdjustmentMs = it },
                            valueRange = -500f..500f,
                            steps = 19
                        )
                        Text(
                            "Geser ke kanan jika musik terdengar terlalu cepat dibanding vokal. Uji dengan rekaman pendek.",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                        }
                        val source = recordingPrefs.getString("last_backing_uri", null)
                        val mixCompatible = recordingPrefs.getBoolean("last_mix_compatible", false)
                        val supportedSource = source?.let {
                            val scheme = Uri.parse(it).scheme
                            scheme == "content" || scheme == "file"
                        } == true
                        Button(
                            enabled = !recording && !mixing && supportedSource && mixCompatible,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val backing = source ?: return@Button
                                val voice = lastRecording ?: return@Button
                                val selectedMusicGain = musicGain
                                val selectedVoiceGain = voiceGain
                                val selectedSyncMs = syncAdjustmentMs.toLong()
                                val selectedBackingStartMs = recordingPrefs.getLong("last_backing_start_ms", 0L)
                                mixing = true
                                mixStatus = "Memproses audio di perangkat…"
                                mixScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            val folder = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "IING STD Karaoke")
                                            check(folder.exists() || folder.mkdirs()) { "Folder output tidak tersedia" }
                                            val destination = File(folder, "IING-MIX-${System.currentTimeMillis()}.wav")
                                            try {
                                                AudioMixEngine.mixToWav(
                                                    this@MainActivity,
                                                    Uri.parse(backing),
                                                    voice,
                                                    (selectedBackingStartMs + selectedSyncMs).coerceAtLeast(0L),
                                                    destination,
                                                    selectedMusicGain,
                                                    selectedVoiceGain
                                                )
                                            } catch (e: Exception) {
                                                destination.delete()
                                                throw e
                                            }
                                        }
                                        mixedFile = result
                                        recordingPrefs.edit().putString("last_mix_path", result.absolutePath).apply()
                                        mixStatus = "Mix WAV tersimpan: ${result.name}"
                                    } catch (e: Exception) {
                                        mixStatus = "Mix gagal: ${e.message ?: "format audio tidak didukung"}"
                                    } finally {
                                        mixing = false
                                    }
                                }
                            }
                        ) { Text(if (mixing) "⏳ Sedang mixing…" else "🎶 Gabungkan musik + vokal") }
                        if (!supportedSource) {
                            Text("Mixing hanya mendukung lagu lokal yang dapat diakses, bukan streaming.", color = Color.LightGray, fontSize = 12.sp)
                        } else if (!mixCompatible) {
                            Text(
                                recordingPrefs.getString("last_mix_warning", null)
                                    ?: "Rekaman lama belum memiliki informasi sinkronisasi yang aman",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        mixedFile?.let { file ->
                            OutlinedButton(onClick = {
                                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                player.prepare()
                                player.play()
                                track = "Hasil mixing"
                                currentSong = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar hasil mixing") }
                            OutlinedButton(onClick = {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        this@MainActivity, "com.iingstd.karaoke.fileprovider", file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = android.content.ClipData.newUri(contentResolver, "Hasil mixing", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(Intent.createChooser(intent, "Bagikan hasil mixing"))
                                } catch (e: Exception) {
                                    mixStatus = "Gagal membagikan: ${e.message ?: "kesalahan"}"
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("↗ Bagikan hasil mixing") }
                        }
                        if (mixStatus.isNotBlank()) Text(mixStatus, color = Color.LightGray)
                    }
                    if (recordingStatus.isNotBlank()) Text(recordingStatus, color = Color.LightGray)
                    Text("Rekaman saat ini hanya suara mikrofon, belum dicampur dengan musik.", color = Color.LightGray, fontSize = 12.sp)
                    Text("Nada: " + String.format("%.1f", 12f * (kotlin.math.ln(pitch.toDouble()) / kotlin.math.ln(2.0))) + " semitone", color = Color.LightGray)
                    Slider(value = pitch, onValueChange = {
                        pitch = it
                        player.playbackParameters = PlaybackParameters(speed, it)
                    }, valueRange = 0.7071f..1.4142f)
                    TextButton(onClick = {
                        pitch = 1f
                        player.playbackParameters = PlaybackParameters(speed, 1f)
                    }) { Text("Reset nada") }
                    Button(onClick = { lyricPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth()) { Text("📝 Buka lirik .LRC") }
                    Spacer(Modifier.height(12.dp))
                    if (lyrics.isNotEmpty()) {
                        Text("Sinkronisasi lirik: ${"%.1f".format(lyricOffsetMs / 1000f)} detik", color = Color.LightGray)
                        Slider(value = lyricOffsetMs, onValueChange = { lyricOffsetMs = it },
                            valueRange = -5000f..5000f, steps = 19)
                        TextButton(onClick = { lyricOffsetMs = 0f }) { Text("Reset sinkronisasi") }
                    }
                    var selectedList by remember { mutableStateOf("Lirik") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Lirik", "Disimpan", "Riwayat").forEach { tab ->
                            TextButton(onClick = { selectedList = tab }) {
                                Text(tab, color = if (selectedList == tab) Pink else Color.LightGray)
                            }
                        }
                    }
                    if (selectedList == "Riwayat" && history.isNotEmpty()) {
                        TextButton(onClick = {
                            library.clearHistory()
                            history = library.history()
                        }) { Text("Hapus semua riwayat") }
                    }
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9E9E))
                    val current = activeLyricIndex
                    if (selectedList != "Lirik") {
                        val songs = if (selectedList == "Disimpan") favorites else history
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(260.dp)
                                .background(Color(0xFF21182D), RoundedCornerShape(20.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (songs.isEmpty()) item { Text("Belum ada lagu", color = Color.LightGray) }
                            items(songs.size) { i ->
                                val song = songs[i]
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { playSong(song) }, modifier = Modifier.weight(1f)) {
                                        Text("▶ " + song.title, maxLines = 2)
                                    }
                                    TextButton(onClick = {
                                        if (selectedList == "Disimpan") {
                                            library.toggleFavorite(song)
                                            favorites = library.favorites()
                                        } else {
                                            library.removeHistory(song.uri)
                                            history = library.history()
                                        }
                                    }) { Text("Hapus") }
                                }
                            }
                        }
                    } else LazyColumn(
                        state = lyricListState,
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                            .background(Color(0xFF21182D), RoundedCornerShape(20.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (lyrics.isEmpty()) {
                            item { Text("Lirik akan tampil di sini 🎶\nPilih file .LRC bertimestamp.", color = Color.LightGray, textAlign = TextAlign.Center) }
                        } else {
                            items(lyrics.size) { index ->
                                Text(lyrics[index].text.ifBlank { "♪" },
                                    color = if (index == current) Pink else Color.LightGray,
                                    fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (index == current) 21.sp else 16.sp,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun formatTime(ms: Long): String {
    val sec = ms / 1000
    return "%02d:%02d".format(sec / 60, sec % 60)
}
private fun parseLrc(input: String): List<Lyric> {
    val regex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")
    return input.lines().flatMap { line ->
        val matches = regex.findAll(line).toList()
        val text = line.replace(regex, "").trim()
        matches.map { m ->
            val minutes = m.groupValues[1].toLong()
            val seconds = m.groupValues[2].toLong()
            val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            Lyric((minutes * 60 + seconds) * 1000 + frac, text)
        }
    }.sortedBy { it.timeMs }
}
