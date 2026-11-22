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
import androidx.activity.compose.BackHandler
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
private val Dark = Color(0xFF263C50)
private val BlueGreySurface = Color(0xFF344D63)
private val BlueGreyButton = Color(0xFF405C73)

class MainActivity : ComponentActivity() {
    private var incomingYouTubeLink by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptSharedYouTube(intent)
    }

    private fun acceptSharedYouTube(intent: Intent?) {
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val link = Regex("https?://[^\\s]+").find(shared)?.value ?: return
        val parsed = Uri.parse(link)
        val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return
        if (host == "youtu.be" || host == "youtube.com" || host == "m.youtube.com" ||
            host == "music.youtube.com") {
            incomingYouTubeLink = link
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptSharedYouTube(intent)
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
            var onlineSearch by remember { mutableStateOf("") }
            var internalSearchUrl by remember { mutableStateOf<String?>(null) }
            var internalBrowser by remember { mutableStateOf<WebView?>(null) }
            var internalBrowserLoading by remember { mutableStateOf(false) }
            var internalBrowserError by remember { mutableStateOf("") }
            var karaokeVideoId by remember { mutableStateOf<String?>(null) }
            var karaokePlayerError by remember { mutableStateOf("") }
            BackHandler(enabled = internalSearchUrl != null) {
                val browser = internalBrowser
                if (karaokeVideoId != null) karaokeVideoId = null
                else if (browser?.canGoBack() == true) browser.goBack() else internalSearchUrl = null
            }

            var youtubeVideoId by remember { mutableStateOf<String?>(null) }
            var localVideoUri by remember { mutableStateOf<String?>(null) }
            var track by remember { mutableStateOf("Belum ada lagu") }
            LaunchedEffect(incomingYouTubeLink) {
                incomingYouTubeLink?.let { link ->
                    val parsed = Uri.parse(link)
                    val host = parsed.host?.lowercase()?.removePrefix("www.")
                    val id = if (host == "youtu.be") parsed.pathSegments.firstOrNull()
                        else if (parsed.pathSegments.firstOrNull() in listOf("shorts", "embed", "live"))
                            parsed.pathSegments.getOrNull(1)
                        else parsed.getQueryParameter("v")
                    if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                        player.stop()
                        player.clearMediaItems()
                        youtubeVideoId = id
                        currentSong = null
                        localVideoUri = null
                        track = "YouTube • " + id
                        mode = "Online"
                        url = link
                    }
                    incomingYouTubeLink = null
                }
            }
            var playing by remember { mutableStateOf(false) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var pitch by remember { mutableFloatStateOf(1f) }
            var volume by remember { mutableFloatStateOf(1f) }
            var repeatOne by remember { mutableStateOf(false) }
            var smoothVocal by remember { mutableStateOf(true) }
            var vocalClarity by remember { mutableFloatStateOf(0.5f) }
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
                    // Restore the selected karaoke song after previewing vocal/mix output.
                    val selectedUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (selectedUri != song.uri) {
                        player.setMediaItem(MediaItem.fromUri(song.uri))
                        player.prepare()
                    }
                    // Always restart the backing track so a new take has a clean timeline.
                    player.seekTo(0)
                    player.playbackParameters = PlaybackParameters(1f, 1f)
                    speed = 1f
                    pitch = 1f
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    repeatOne = false
                    player.play()
                    sessionSongUri = song.uri
                    sessionSongTitle = song.title
                    sessionStartPositionMs = player.currentPosition.coerceAtLeast(0)
                    sessionMixCompatible =
                        player.playbackParameters.speed == 1f &&
                        player.playbackParameters.pitch == 1f &&
                        player.repeatMode == Player.REPEAT_MODE_OFF
                    sessionMixWarning = if (sessionMixCompatible) "" else
                        "Mixing membutuhkan musik sedang diputar normal (kecepatan dan pitch 1×, tanpa repeat)"
                    recorder.start()
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
            val bulkAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                var added = 0
                uris.forEach { uri ->
                    try {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val title = runCatching {
                            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Lagu offline"
                        library.record(SavedSong(title, uri.toString(), false, System.currentTimeMillis()))
                        added++
                    } catch (_: Exception) { }
                }
                history = library.history()
                if (added > 0) {
                    error = "$added lagu ditambahkan ke koleksi offline. Pilih lagu dari riwayat untuk memutar."
                } else if (uris.isNotEmpty()) {
                    error = "Tidak dapat menambahkan file. Pastikan file MP3/MP4 dapat dibuka."
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
            MaterialTheme(
                colorScheme = darkColorScheme(primary = Pink, background = Dark, surface = BlueGreySurface),
                shapes = Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(26.dp)
                )
            ) {
                Column(
                    Modifier.fillMaxSize().background(Dark).verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Open the selected title immediately in a full-screen in-app browser.
                    // Keep the existing karaoke controls intact underneath.
                    internalSearchUrl?.let { searchUrl ->
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = {
                                internalSearchUrl = null
                                karaokeVideoId = null
                                karaokePlayerError = ""
                                internalBrowserError = ""
                            },
                            properties = androidx.compose.ui.window.DialogProperties(
                                usePlatformDefaultWidth = false
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().background(Dark).padding(12.dp)
                            ) {
                                Text("🎤 Karaoke Online", color = Pink, fontWeight = FontWeight.Bold)
                                karaokeVideoId?.let { selectedId ->
                                        Text(
                                        "Jika muncul Error 153, YouTube menolak pemutar tertanam. Gunakan tombol tonton di YouTube di bawah.",
                                        color = Color.LightGray, fontSize = 12.sp
                                    )
                                    Button(
                                        onClick = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://www.youtube.com/watch?v=$selectedId")
                                            )
                                            runCatching { startActivity(intent) }.onFailure {
                                                karaokePlayerError = "Tidak dapat membuka video: " + (it.message ?: "aplikasi tidak tersedia")
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("▶ Tonton di YouTube (jika Error 153)") }
                                    if (karaokePlayerError.isNotBlank()) Text(karaokePlayerError, color = Color(0xFFFF9E9E), fontSize = 12.sp)
                                    key(selectedId) {
                                        AndroidView(
                                            factory = { context ->
                                                WebView(context).apply {
                                                    settings.javaScriptEnabled = true
                                                    settings.domStorageEnabled = true
                                                    settings.mediaPlaybackRequiresUserGesture = true
                                                    webChromeClient = WebChromeClient()
                                                    webViewClient = object : WebViewClient() {
                                                        override fun onReceivedError(
                                                            view: WebView?,
                                                            request: android.webkit.WebResourceRequest?,
                                                            error: android.webkit.WebResourceError?
                                                        ) {
                                                            if (request?.isForMainFrame == true) {
                                                                karaokePlayerError = "Pemutar gagal dimuat. Periksa koneksi atau pilih video lain."
                                                            }
                                                        }
                                                        override fun onReceivedHttpError(
                                                            view: WebView?,
                                                            request: android.webkit.WebResourceRequest?,
                                                            errorResponse: android.webkit.WebResourceResponse?
                                                        ) {
                                                            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                                                                karaokePlayerError = "Video tidak tersedia untuk pemutar ini. Pilih video lain."
                                                            }
                                                        }
                                                    }
                                                    settings.loadWithOverviewMode = true
                                                    settings.useWideViewPort = true
                                                    loadUrl("https://www.youtube.com/embed/$selectedId?playsinline=1&rel=0")
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().weight(1f),
                                            onRelease = { view ->
                                                view.stopLoading()
                                                view.loadUrl("about:blank")
                                                view.destroy()
                                            }
                                        )
                                    }
                                    }
                                if (karaokeVideoId == null && internalBrowserLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                if (internalBrowserError.isNotBlank()) {
                                    Text(internalBrowserError, color = Color(0xFFFF9E9E), fontSize = 12.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        if (karaokeVideoId != null) { karaokeVideoId = null; karaokePlayerError = "" }
                                        else internalBrowser?.let { if (it.canGoBack()) it.goBack() }
                                    }) { Text("← Kembali") }
                                    TextButton(onClick = {
                                        if (karaokeVideoId != null) karaokeVideoId = null
                                        else internalBrowser?.reload()
                                    }) { Text(if (karaokeVideoId != null) "🔎 Pilih video lain" else "↻ Ulang") }
                                    TextButton(onClick = {
                                        internalSearchUrl = null
                                        karaokeVideoId = null
                                        karaokePlayerError = ""
                                        internalBrowserError = ""
                                    }) { Text("✕ Tutup") }
                                }
                                if (karaokeVideoId == null) key(searchUrl) {
                                    AndroidView(
                                        factory = { context ->
                                            WebView(context).apply {
                                                settings.javaScriptEnabled = true
                                                settings.domStorageEnabled = true
                                                settings.loadsImagesAutomatically = true
                                                settings.mediaPlaybackRequiresUserGesture = true
                                                webChromeClient = WebChromeClient()
                                                webViewClient = object : WebViewClient() {
                                                    override fun shouldOverrideUrlLoading(
                                                        view: WebView?,
                                                        request: android.webkit.WebResourceRequest?
                                                    ): Boolean {
                                                        val target = request?.url ?: return false
                                                        val host = target.host?.lowercase()?.removePrefix("www.") ?: return false
                                                        if (host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be") {
                                                            val id = if (host == "youtu.be") target.pathSegments.firstOrNull()
                                                                else if (target.pathSegments.firstOrNull() in listOf("shorts", "live", "embed"))
                                                                    target.pathSegments.getOrNull(1)
                                                                else target.getQueryParameter("v")
                                                            if (id != null && id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                                                karaokeVideoId = id
                                                                return true
                                                            }
                                                        }
                                                        return false
                                                    }
                                                    // YouTube mobile search can navigate with the History API.
                                                    // Catch those changes too, not only normal link clicks.
                                                    override fun doUpdateVisitedHistory(
                                                        view: WebView?, url: String?, isReload: Boolean
                                                    ) {
                                                        super.doUpdateVisitedHistory(view, url, isReload)
                                                        val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                                                        val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return
                                                        if (host !in listOf("youtube.com", "m.youtube.com", "youtu.be")) return
                                                        val videoId = if (host == "youtu.be") parsed.pathSegments.firstOrNull()
                                                            else if (parsed.pathSegments.firstOrNull() in listOf("shorts", "live", "embed"))
                                                                parsed.pathSegments.getOrNull(1)
                                                            else if (parsed.pathSegments.firstOrNull() == "watch")
                                                                parsed.getQueryParameter("v")
                                                            else null
                                                        if (videoId != null && videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                                            karaokeVideoId = videoId
                                                        }
                                                    }
                                                    override fun onPageStarted(
                                                        view: WebView?, url: String?, favicon: android.graphics.Bitmap?
                                                    ) {
                                                        internalBrowserLoading = true
                                                        internalBrowserError = ""
                                                    }
                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                        internalBrowserLoading = false
                                                    }
                                                    override fun onReceivedError(
                                                        view: WebView?,
                                                        request: android.webkit.WebResourceRequest?,
                                                        error: android.webkit.WebResourceError?
                                                    ) {
                                                        if (request?.isForMainFrame == true) {
                                                            internalBrowserLoading = false
                                                            internalBrowserError = "Halaman gagal dimuat. Periksa internet atau muat ulang."
                                                        }
                                                    }
                                                }
                                                internalBrowser = this
                                                loadUrl(searchUrl)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        onRelease = { webView ->
                                            if (internalBrowser === webView) internalBrowser = null
                                            webView.stopLoading()
                                            webView.loadUrl("about:blank")
                                            webView.destroy()
                                        }
                                    )
                                }
                                if (karaokeVideoId == null) Text("Pilih video karaoke tanpa vokal yang memiliki lirik. Pemutaran tergantung izin video YouTube.",
                                    color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("🎤 IING STD KARAOKE", color = Pink, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Spacer(Modifier.height(8.dp))
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        listOf("Offline", "Online").forEach { item ->
                            Button(
                                onClick = { mode = item },
                                modifier = Modifier.size(82.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (mode == item) Pink else BlueGreyButton
                                )
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (item == "Offline") "♫" else "▶", fontSize = 21.sp)
                                    Text(item, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (mode == "Offline") {
                        Text("🎼 Karaoke offline dari file HP", color = Pink, fontWeight = FontWeight.Bold)
                        Text("Tambahkan MP3/MP4 karaoke milikmu untuk diputar tanpa internet.",
                            color = Color.LightGray, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = { bulkAudioPicker.launch(arrayOf("audio/*", "video/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("📚 Tambahkan banyak lagu MP3/MP4 sekaligus") }
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*", "video/mp4", "video/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Text("📁 Pilih MP3 atau video MP4 dari HP")
                        }
                    } else {
                        OutlinedTextField(value = onlineSearch, onValueChange = { onlineSearch = it },
                            label = { Text("Judul lagu atau penyanyi") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(
                            onClick = {
                                val query = onlineSearch.trim()
                                if (query.isBlank()) {
                                    error = "Tulis judul lagu terlebih dahulu"
                                } else {
                                    val search = Uri.parse("https://www.youtube.com/results")
                                        .buildUpon()
                                        .appendQueryParameter("search_query", query + " karaoke tanpa vokal lirik")
                                        .build()
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, search)) }
                                        .onFailure { error = "Tidak dapat membuka YouTube: " + (it.message ?: "aplikasi tidak tersedia") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("▶ Cari karaoke di YouTube (pemutar resmi)") }
                        Text(
                            "YouTube memakai pemutar resminya agar tidak terkena Error 153. Untuk streaming di dalam aplikasi, gunakan tautan langsung MP3/MP4 yang diizinkan.",
                            color = Color.LightGray, fontSize = 12.sp
                        )
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Link streaming MP3/MP4 langsung (opsional)") },
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
                                        // Load the official YouTube embed as the top-level page.
                                        // The previous nested youtube-nocookie iframe returned
                                        // playback error 152-4 on some Android WebViews.
                                        settings.mediaPlaybackRequiresUserGesture = true
                                        settings.loadsImagesAutomatically = true
                                        loadUrl("https://www.youtube.com/embed/$videoId?playsinline=1&rel=0")
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
                        Button(shape = RoundedCornerShape(14.dp), onClick = { player.seekTo((position - 10000).coerceAtLeast(0)) }) { Text("⏪ 10s") }
                        Button(shape = RoundedCornerShape(26.dp), onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (playing) "⏸ Jeda" else "▶ Putar")
                        }
                        Button(shape = RoundedCornerShape(14.dp), onClick = { player.seekTo(position + 10000) }) { Text("10s ⏩") }
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
                    Text("Mikrofon dipilih otomatis: USB/soundcard → headset kabel → mikrofon HP.", color = Pink)
                    Text("Sambungkan Bonkyo F999 Max melalui OTG sebelum menekan Rekam. Bluetooth hanya digunakan sebagai mikrofon bila Android mendukung input audionya. Musik lokal tetap digabung otomatis setelah rekaman.",
                        color = Color.LightGray, fontSize = 12.sp)
                    }
                    Button(onClick = {
                        if (recording) {
                            try {
                                val saved = recorder.stop()
                                recording = false
                                recordingPaused = false
                                if (saved != null) {
                                    // Stop music together with the vocal take.
                                    // Small player clock drift or the backing reaching its end
                                    // must not prevent a short/full recording from auto-mixing.
                                    if (player.currentMediaItem?.localConfiguration?.uri?.toString() != sessionSongUri ||
                                        player.playbackParameters.speed != 1f ||
                                        player.playbackParameters.pitch != 1f) {
                                        sessionMixCompatible = false
                                        sessionMixWarning = "Sumber musik atau kecepatan berubah saat merekam"
                                    }
                                    player.pause()
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
                                    val selectedMusicGain = 0.75f
                                    val selectedVoiceGain = 0.95f
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
                                                        destination, selectedMusicGain, selectedVoiceGain, smoothVocal, vocalClarity
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
                                // Keep the selected backing track for the next recording.
                                            error = ""
                            } catch (e: Exception) {
                                error = "Tidak bisa memutar rekaman: " + (e.message ?: "file tidak tersedia")
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar suara saja (tanpa musik)") }
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
                        Text("🎵 Hasil karaoke otomatis (musik + vokal)", color = Pink, fontWeight = FontWeight.Bold)
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
                            Text("Rekaman ini belum memiliki musik lokal yang bisa digabung. Pilih MP3/MP4 offline lalu mulai rekaman baru.", color = Color.LightGray, fontSize = 12.sp)
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
                            }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar hasil karaoke (musik + vokal)") }
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
                    Text("🎚 Kualitas vokal hasil rekaman", color = Pink, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (smoothVocal) "Vokal halus (disarankan)" else "Vokal natural",
                            color = Color.LightGray, modifier = Modifier.weight(1f))
                        Switch(checked = smoothVocal, onCheckedChange = { smoothVocal = it },
                            enabled = !recording && !mixing)
                    }
                    Text("🎛 Karakter vokal", color = Pink, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Natural" to 0.15f, "Seimbang" to 0.50f, "Terang" to 0.75f).forEach { (label, level) ->
                            OutlinedButton(
                                onClick = { vocalClarity = level },
                                enabled = !recording && !mixing,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (kotlin.math.abs(vocalClarity - level) < 0.01f) BlueGreyButton else Color.Transparent
                                )
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }
                    Text("✨ Treble & kejernihan vokal: ${(vocalClarity * 100).toInt()}%", color = Pink)
                    Slider(value = vocalClarity, onValueChange = { vocalClarity = it },
                        valueRange = 0f..1f, enabled = !recording && !mixing)
                    Text("EQ treble, presence, dan pengurangan low-mid halus pada hasil mixing offline. Tidak mengubah suara langsung di speaker.",
                        color = Color.LightGray, fontSize = 12.sp)
                    Text("Preset diterapkan saat hasil musik + vokal digabung otomatis. Musik dibersihkan dari getaran sangat rendah tanpa menghilangkan bass.",
                        color = Color.LightGray, fontSize = 12.sp)
                    var selectedList by remember { mutableStateOf("Disimpan") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("Disimpan", "Riwayat").forEach { tab ->
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
                    run {
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
