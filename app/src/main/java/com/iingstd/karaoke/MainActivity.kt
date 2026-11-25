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
private val Dark = Color(0xFF142333)
private val BlueGreySurface = Color(0xFF243A4F)
private val BlueGreyButton = Color(0xFF334C65)

class MainActivity : ComponentActivity() {
    private var incomingYouTubeLink by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
            val wiredMonitor = remember { WiredVocalMonitor(this) }
            DisposableEffect(wiredMonitor) { onDispose { wiredMonitor.stop() } }
            var monitorEnabled by remember { mutableStateOf(false) }
            var monitorVolume by remember { mutableFloatStateOf(1f) }
            var recordWithWiredMonitor by remember { mutableStateOf(false) }
            var recording by remember { mutableStateOf(false) }
            var recordingPaused by remember { mutableStateOf(false) }
            var microphoneLevel by remember { mutableFloatStateOf(0f) }
            var inputTooHot by remember { mutableStateOf(false) }
            LaunchedEffect(recording, recordingPaused) {
                var consecutiveHotPeaks = 0
                while (recording) {
                    microphoneLevel = if (recordingPaused) 0f else recorder.peakLevel()
                    // MediaRecorder.maxAmplitude reports the largest peak since the
                    // previous read. Sustained near-full-scale input indicates a
                    // likely clipped soundcard/ADC signal, not a mixing issue.
                    consecutiveHotPeaks = if (!recordingPaused && microphoneLevel >= 0.97f)
                        (consecutiveHotPeaks + 1).coerceAtMost(10) else 0
                    if (consecutiveHotPeaks >= 3) inputTooHot = true
                    delay(120)
                }
                microphoneLevel = 0f
            }
            var externalMic by remember { mutableStateOf(false) }
            var microphoneSource by remember { mutableStateOf("auto") }
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
            // The AudioRecord worker starts asynchronously. A successful start()
            // only means the thread was launched, not that USB capture succeeded.
            LaunchedEffect(monitorEnabled) {
                if (monitorEnabled) {
                    delay(350)
                    while (monitorEnabled) {
                        if (!wiredMonitor.isActive()) {
                            monitorEnabled = false
                            recordingStatus = "Studio LIVE berhenti: " +
                                wiredMonitor.lastError.ifBlank { "input atau output audio terputus" }
                            break
                        }
                        delay(250)
                    }
                }
            }
            var sessionSongUri by remember { mutableStateOf<String?>(null) }
            var sessionSongTitle by remember { mutableStateOf<String?>(null) }
            var sessionStartPositionMs by remember { mutableLongStateOf(0L) }
            var sessionMixCompatible by remember { mutableStateOf(true) }
            var sessionMixWarning by remember { mutableStateOf("") }
            val recordingPrefs = remember { getSharedPreferences("karaoke_recording", MODE_PRIVATE) }
            val mixScope = rememberCoroutineScope()
            var mixing by remember { mutableStateOf(false) }
            var mixStatus by remember { mutableStateOf("") }
            var musicGain by remember { mutableFloatStateOf(0.85f) }
            var voiceGain by remember { mutableFloatStateOf(1.15f) }
            var vocalPriority by remember { mutableStateOf(true) }
            var syncAdjustmentMs by remember { mutableFloatStateOf(0f) }
            var autoMusicDelayMs by remember { mutableLongStateOf(0L) }
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
            var streamTitle by remember { mutableStateOf("") }
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
                        mode = "Offline"
                        url = ""
                    }
                    incomingYouTubeLink = null
                }
            }
            var playing by remember { mutableStateOf(false) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var pitch by remember { mutableFloatStateOf(1f) }
            var volume by remember { mutableFloatStateOf(0.85f) }
            var repeatOne by remember { mutableStateOf(false) }
            var smoothVocal by remember { mutableStateOf(true) }
            var studioNaturalVocal by remember { mutableStateOf(true) }
            var vocalClarity by remember { mutableFloatStateOf(0.5f) }
            var phoneSpeakerLevel by remember { mutableFloatStateOf(0.30f) }
            var recordingOnPhone by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            var showPlaybackOptions by remember { mutableStateOf(false) }
            var showMicOptions by remember { mutableStateOf(false) }
            var showMixOptions by remember { mutableStateOf(false) }
            var showSoundcardSettings by remember { mutableStateOf(false) }
            var soundcardSettingsLocked by remember { mutableStateOf(false) }
            var smartAudioEnabled by remember { mutableStateOf(true) }
            var studioLiveRequested by remember { mutableStateOf(false) }
            val studioDsp = remember { StudioLiveDsp() }
            var studioBass by remember { mutableFloatStateOf(0f) }
            var studioMid by remember { mutableFloatStateOf(0f) }
            var studioTreble by remember { mutableFloatStateOf(0f) }
            var studioReverb by remember { mutableFloatStateOf(0f) }
            var studioCompressor by remember { mutableFloatStateOf(0f) }
            var studioMonitorGain by remember { mutableFloatStateOf(0.7f) }
            var studioVocalGain by remember { mutableFloatStateOf(1f) }
            var studioMusicVolume by remember { mutableFloatStateOf(0.85f) }

            var sessionExternalAudio by remember { mutableStateOf(false) }
            fun beginRecording() {
                if (!wiredMonitor.stop()) {
                    recordingStatus = "Monitor mikrofon masih berhenti. Tunggu sebentar lalu coba rekam lagi."
                    return
                }
                monitorEnabled = false
                try {
                    if (youtubeVideoId != null) {
                        recordingStatus = "Rekaman dengan musik YouTube belum didukung. Pilih MP3 atau MP4 offline terlebih dahulu."
                        return
                    }
                    val song = currentSong
                    if (song == null || (song.online && Uri.parse(song.uri).scheme != "https")) {
                        recordingStatus = "Pilih lagu MP3/MP4 offline terlebih dahulu."
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
                    // Start capture before playback so the recording does not miss the song opening.
                    // Speaker playback can leak into the built-in microphone and be
                    // duplicated when the clean backing track is mixed afterward.
                    val inputManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    val hasExternalInput = inputManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).any {
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    val hasWiredOutput = inputManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    recordingOnPhone = !hasExternalInput && !hasWiredOutput
                    sessionExternalAudio = hasExternalInput
                    // Do not attenuate music for an analog LIVE connection: the cable
                    // already isolates playback from the phone's built-in speaker.
                    player.volume = if (studioLiveRequested) studioMusicVolume else if (recordingOnPhone) volume * phoneSpeakerLevel else if (hasExternalInput) 1f else volume
                    if (hasExternalInput) {
                        val outputNames = inputManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                            .filter { it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET }
                            .joinToString { it.productName.toString() }
                        if (outputNames.isNotBlank()) recordingStatus = "Output audio Android terdeteksi: $outputNames. Tombol ACCOMPANY soundcard hanya bekerja bila jalur hardware mendukungnya."
                    }
                    sessionSongUri = song.uri
                    sessionSongTitle = song.title
                    sessionStartPositionMs = player.currentPosition.coerceAtLeast(0)
                    sessionMixCompatible =
                        player.playbackParameters.speed == 1f &&
                        player.playbackParameters.pitch == 1f &&
                        player.repeatMode == Player.REPEAT_MODE_OFF
                    sessionMixWarning = if (sessionMixCompatible) "" else
                        "Mixing membutuhkan musik sedang diputar normal (kecepatan dan pitch 1×, tanpa repeat)"
                    // LIVE OTG is a USB audio input when Android exposes one.
                    // Prefer it explicitly; otherwise keep Android's default route
                    // for analog LIVE adapters and soundcards without USB capture.
                    val liveUsbInputAvailable = inputManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).any {
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    recorder.microphoneSource = if (microphoneSource == "live") {
                        if (liveUsbInputAvailable) "usb" else "system"
                    } else microphoneSource
                    // External soundcards often have zero-latency direct monitoring.
                    // Smart mode avoids a second, delayed monitoring signal by default.
                    // Explicit opt-in: software monitoring can be delayed on some USB soundcards.
                    recorder.wiredMonitoringEnabled = if (studioLiveRequested) true else recordWithWiredMonitor && !hasExternalInput
                    recorder.studioDsp = if (studioLiveRequested) studioDsp.also { it.outputGain = studioVocalGain; it.reset() } else null
                    recorder.wiredMonitorVolume = if (studioLiveRequested) studioMonitorGain else monitorVolume
                    val captureStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                    recorder.start()
                    inputTooHot = false
                    val playbackRequestNs = android.os.SystemClock.elapsedRealtimeNanos()
                    player.play()
                    // Estimate startup offset using monotonic timestamps. This does not
                    // measure hardware latency; the manual fine-tuning control remains.
                    autoMusicDelayMs = ((playbackRequestNs - captureStartNs) / 1_000_000L).coerceIn(0L, 2000L)
                    recordingPrefs.edit().putLong("last_auto_music_delay_ms", autoMusicDelayMs).apply()
                    // The previous mix belongs to an older vocal take.
                    mixedFile = null
                    mixStatus = ""
                    recordingPrefs.edit().remove("last_mix_path").apply()
                    recording = true
                    recordingPaused = false
                    recordingPausedMs = 0L
                    recordingStartedAt = android.os.SystemClock.elapsedRealtime()
                    recordingSeconds = 0
                    recordingStatus = if (sessionMixCompatible && sessionSongUri?.let { Uri.parse(it).scheme in listOf("content", "file", "https") } == true) "Merekam vokal. Musik akan digabung otomatis setelah selesai." else "Merekam vokal saja. Putar MP3/MP4 lokal pada kecepatan normal agar musik bisa digabung otomatis."
                    if (recorder.routeNotice.isNotBlank()) recordingStatus += "\n" + recorder.routeNotice
                } catch (e: Exception) {
                    player.pause()
                    player.volume = volume
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
                val landscapeStudio = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                Column(Modifier.fillMaxSize().background(Dark)) {
                    if (!landscapeStudio) {
                        Column(
                            Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.28f).coerceIn(112f, 204f).dp).padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("IING STD • Karaoke", color = Pink, fontWeight = FontWeight.Bold)
                            Column(
                                Modifier.fillMaxWidth().weight(1f).background(BlueGreySurface, RoundedCornerShape(12.dp)).padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                localVideoUri?.let { selectedUri ->
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
                                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                                        )
                                    }
                                }
                                youtubeVideoId?.let { videoId ->
                                    key(videoId) {
                                        AndroidView(
                                            factory = { context ->
                                                WebView(context).apply {
                                                    settings.javaScriptEnabled = true
                                                    settings.domStorageEnabled = true
                                                    settings.mediaPlaybackRequiresUserGesture = true
                                                    settings.loadsImagesAutomatically = true
                                                    webChromeClient = WebChromeClient()
                                                    webViewClient = WebViewClient()
                                                    loadUrl("https://www.youtube.com/embed/$videoId?playsinline=1&rel=0")
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                                            onRelease = { webView ->
                                                webView.stopLoading()
                                                webView.loadUrl("about:blank")
                                                webView.destroy()
                                            }
                                        )
                                    }
                                }
                                if (localVideoUri == null && youtubeVideoId == null) {
                                    Text(track, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                                    Text("Layar lagu tetap di atas", color = Color.LightGray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    if (landscapeStudio) {
                        Column(
                            Modifier.weight(1.7f).fillMaxHeight().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("IING STD • Layar Karaoke", color = Pink, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier.fillMaxWidth().weight(1f).background(BlueGreySurface, RoundedCornerShape(16.dp)).padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
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
                                if (localVideoUri == null && youtubeVideoId == null) {
                                    Text(track, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                                    Text("Layar lagu tetap terlihat saat panel kanan digulir", color = Color.LightGray, fontSize = 12.sp)
                                }
                            }
                            Text(if (recording) "● MEREKAM" else "Studio siap", color = if (recording) Color.Red else Pink)
                        }
                    }
                Column(
                    Modifier.weight(if (landscapeStudio) 1f else 1.7f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = if (landscapeStudio) 12.dp else 16.dp, vertical = 12.dp),
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
                    Text("🎵 Studio karaoke offline", color = Pink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Pilih lagu MP3/MP4 dari HP. Rekam vokal dan gabungkan dengan musik tanpa internet.", color = Color.LightGray, fontSize = 12.sp)
                    Text("💡 Tanpa headset: kecilkan volume speaker HP agar musik tidak bocor dua kali ke mikrofon.", color = Color.LightGray, fontSize = 12.sp)
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
                        Text("🎵 Koleksi streaming tersimpan", color = Pink, fontWeight = FontWeight.Bold)
                        val onlineMatches = history.filter { song ->
                            song.online && Uri.parse(song.uri).scheme == "https" &&
                                (onlineSearch.isBlank() || song.title.contains(onlineSearch, ignoreCase = true))
                        }.distinctBy { it.uri }.take(12)
                        if (onlineMatches.isEmpty()) {
                            Text("Belum ada streaming tersimpan. Tambahkan tautan audio HTTPS berizin di bawah. Katalog lagu populer belum tersedia.",
                                color = Color.LightGray, fontSize = 12.sp)
                        } else {
                            onlineMatches.forEach { song ->
                                OutlinedButton(
                                    onClick = { playSong(song) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp)
                                ) { Text("▶ " + song.title, maxLines = 2) }
                            }
                        }
                        TextButton(onClick = {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://carf-coder.github.io/keynata-commons/")))
                            }.onFailure { error = "Gagal membuka katalog: " + (it.message ?: "browser tidak tersedia") }
                        }) { Text("🌐 Jelajahi musik karaoke CC0 (Keynata Commons)") }
                        OutlinedTextField(value = streamTitle, onValueChange = { streamTitle = it },
                            label = { Text("Nama lagu streaming (opsional)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
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
                                    playSong(SavedSong(streamTitle.trim().ifBlank { parsed.lastPathSegment ?: "Streaming online" }, link, true, System.currentTimeMillis()))
                                } catch (e: Exception) { error = e.message ?: "Streaming gagal" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar streaming") }
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
                    OutlinedButton(onClick = { showPlaybackOptions = !showPlaybackOptions }, shape = RoundedCornerShape(24.dp)) {
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
                            player.volume = if (studioLiveRequested) studioMusicVolume else if (recording && sessionExternalAudio && !recordingOnPhone) 1f else if (recording && recordingOnPhone) it * phoneSpeakerLevel else it
                        }, modifier = Modifier.weight(1f))
                        Text("${(volume * 100).toInt()}%", color = Color.LightGray)
                    }
                    TextButton(onClick = {
                        repeatOne = !repeatOne
                        player.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }) { Text(if (repeatOne) "🔁 Ulangi lagu: ON" else "🔁 Ulangi lagu: OFF") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showSoundcardSettings = !showSoundcardSettings }, shape = RoundedCornerShape(24.dp)) {
                            Text(if (showSoundcardSettings) "▾ Tutup pengaturan soundcard" else "▸ Soundcard & Monitoring", color = Pink)
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { soundcardSettingsLocked = !soundcardSettingsLocked }, shape = RoundedCornerShape(24.dp)) {
                            Text(if (soundcardSettingsLocked) "🔒 Terkunci" else "🔓 Kunci", color = Color.White)
                        }
                    }
                    if (showSoundcardSettings) {
                        Text("Penguncian ini mencegah perubahan kontrol aplikasi secara tidak sengaja; tombol fisik F999 NEO tetap bekerja.", color = Color.LightGray, fontSize = 12.sp)
                    Text("🎛 F999 NEO: ACCOMPANY tidak dapat dikendalikan aplikasi jika knob hanya mengatur jalur input musik analog. Saat musik diputar lewat USB OTG, kontrol itu mungkin dilewati oleh hardware. Gunakan volume musik pada soundcard bila tersedia; jangan menaikkan MIC/RECORD untuk memperkeras monitor.", color = Color.LightGray, fontSize = 12.sp)
                    Text("🎚 Volume speaker HP saat merekam: ${(phoneSpeakerLevel * 100).toInt()}%", color = Pink)
                    Slider(value = phoneSpeakerLevel, onValueChange = { phoneSpeakerLevel = it }, valueRange = 0.15f..0.60f, enabled = !recording && !soundcardSettingsLocked)
                    Text("Mode HP langsung: musik lebih pelan, vokal lebih menonjol. Gunakan 15–30% bila suara musik masih dobel.", color = Color.LightGray, fontSize = 12.sp)
                    if (microphoneSource == "live") {
                        Text("🎤 Soundcard LIVE: EQ dan efek berasal dari F999 NEO; pemrosesan tambahan aplikasi harus nonaktif agar tidak bertumpuk. Studio LIVE (mixer aplikasi) nantinya memerlukan EQ/REVERB/EFFECT fisik pada posisi netral atau minimum secara manual. Aplikasi tidak bisa mematikan efek analog F999 NEO.", color = Pink, fontSize = 12.sp)
                        Text("Pantau indikator level mikrofon saat rekaman dan periksa tulisan Input aktif. Jika tertulis Mikrofon HP atau muncul PERINGATAN, suara yang ditangkap bukan jalur USB F999 NEO. Jangan menaikkan MIC hingga pecah untuk memperkeras monitoring.", color = Color.LightGray, fontSize = 12.sp)
                        Text("Monitoring digital aplikasi dimatikan otomatis saat Smart Audio aktif dan soundcard terdeteksi, untuk menghindari gema dan keterlambatan ganda. Kepekaan tombol EQ/reverb analog tidak bisa diperbesar dari aplikasi tanpa pemrosesan monitoring tambahan yang berisiko delay.", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text("🎛 Mode efek", color = Pink, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !studioLiveRequested, onClick = { if (!recording && !soundcardSettingsLocked) { studioLiveRequested = false; wiredMonitor.stop(); monitorEnabled = false; wiredMonitor.studioDsp = null } }, enabled = !recording && !soundcardSettingsLocked)
                        Text("Soundcard LIVE (aktif, efek fisik F999 NEO)", color = Color.White, fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = studioLiveRequested, onClick = {
                            if (!recording && !soundcardSettingsLocked) {
                                if (!studioLiveRequested) {
                                    if (wiredMonitor.stop()) {
                                        monitorEnabled = false
                                        studioLiveRequested = true
                                        recordingStatus = "Studio LIVE dipilih. Monitor digital belum aktif."
                                    } else {
                                        recordingStatus = "Monitor sebelumnya masih berhenti. Coba pilih Studio LIVE lagi."
                                    }
                                }
                            }
                        }, enabled = !recording && !soundcardSettingsLocked)
                        Text("Studio LIVE (uji coba: EQ real-time)", color = Color.White, fontSize = 12.sp)
                    }
                    if (studioLiveRequested) {
                        Text("Studio LIVE: tekan AKTIFKAN MONITOR sebelum merekam untuk mendengar EQ langsung. Saat REKAM dimulai, monitor pratinjau berhenti dan pemrosesan yang sama diteruskan ke perekam. Gunakan earphone. Jika monitor langsung F999 NEO sudah terdengar, biarkan monitor digital nonaktif untuk menghindari suara ganda. Efek fisik dan monitor langsung adalah pengaturan perangkat yang terpisah.", color = Pink, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                if (monitorEnabled) {
                                    wiredMonitor.stop()
                                    monitorEnabled = false
                                    recordingStatus = "Monitor Studio LIVE dihentikan."
                                } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    recordingStatus = "Izinkan mikrofon dahulu melalui pengaturan aplikasi atau tombol rekam."
                                } else {
                                    studioDsp.outputGain = studioVocalGain
                                    studioDsp.reset()
                                    wiredMonitor.studioDsp = studioDsp
                                    wiredMonitor.setVolume(studioMonitorGain)
                                    monitorEnabled = wiredMonitor.start(if (microphoneSource == "live") "auto" else microphoneSource)
                                    recordingStatus = if (monitorEnabled) "Studio LIVE aktif: atur EQ sambil mendengarkan." else "Monitor gagal: ${wiredMonitor.lastError}"
                                }
                            },
                            enabled = !recording,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (monitorEnabled) "⏹ Matikan Monitor Studio LIVE" else "🎧 Aktifkan Monitor Studio LIVE (sebelum rekam)") }
                        Text(if (recording) "● EQ aktif pada rekaman dan monitor." else if (monitorEnabled) "● EQ aktif pada monitor pratinjau." else "○ Tekan Aktifkan Monitor agar EQ terdengar sebelum merekam.", color = Color.LightGray, fontSize = 12.sp)
                        if (!monitorEnabled && wiredMonitor.lastError.isNotBlank()) Text("⚠ ${wiredMonitor.lastError}", color = Color.Yellow, fontSize = 12.sp)
                        Text("🎚 Volume Vokal LIVE (monitor + rekaman): ${(studioVocalGain * 100).toInt()}%", color = Color.White)
                        Slider(value = studioVocalGain, onValueChange = { studioVocalGain = it; studioDsp.outputGain = it }, valueRange = 0f..2f, enabled = !soundcardSettingsLocked)
                        Text("🎵 Volume Musik LIVE: ${(studioMusicVolume * 100).toInt()}%", color = Color.White)
                        Slider(value = studioMusicVolume, onValueChange = { studioMusicVolume = it; player.volume = it }, valueRange = 0f..1f, enabled = !soundcardSettingsLocked)
                        Text("Volume vokal memengaruhi audio yang direkam Studio LIVE. Volume musik ini hanya mengatur playback saat bernyanyi, bukan level hasil mixing akhir.", color = Color.LightGray, fontSize = 12.sp)
                        Text("Bass: ${studioBass.toInt()} dB", color = Color.White)
                        Slider(value = studioBass, onValueChange = { studioBass = it; studioDsp.bassDb = it }, valueRange = -12f..12f, enabled = !soundcardSettingsLocked)
                        Text("Mid: ${studioMid.toInt()} dB", color = Color.White)
                        Slider(value = studioMid, onValueChange = { studioMid = it; studioDsp.midDb = it }, valueRange = -12f..12f, enabled = !soundcardSettingsLocked)
                        Text("Treble: ${studioTreble.toInt()} dB", color = Color.White)
                        Slider(value = studioTreble, onValueChange = { studioTreble = it; studioDsp.trebleDb = it }, valueRange = -12f..12f, enabled = !soundcardSettingsLocked)
                        Text("Reverb: ${(studioReverb * 100).toInt()}%", color = Color.White)
                        Slider(value = studioReverb, onValueChange = { studioReverb = it; studioDsp.reverbMix = it }, valueRange = 0f..0.35f, enabled = !soundcardSettingsLocked)
                        Text("Compressor: ${(studioCompressor * 100).toInt()}%", color = Color.White)
                        Slider(value = studioCompressor, onValueChange = { studioCompressor = it; studioDsp.compressorAmount = it }, valueRange = 0f..1f, enabled = !soundcardSettingsLocked)
                        Text("Monitor vokal: ${(studioMonitorGain * 100).toInt()}%", color = Color.White)
                        Slider(value = studioMonitorGain, onValueChange = { studioMonitorGain = it; if (monitorEnabled) wiredMonitor.setVolume(it); if (recording && studioLiveRequested) recorder.updateLiveMonitorVolume(it) }, valueRange = 0.1f..1.5f, enabled = !soundcardSettingsLocked)

                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = smartAudioEnabled, onCheckedChange = { if (!soundcardSettingsLocked) smartAudioEnabled = it }, enabled = !recording && !soundcardSettingsLocked)
                        Spacer(Modifier.width(8.dp))
                        Text("Smart Audio otomatis", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text("Aktif: input eksternal memakai mixing bersih tanpa EQ/reverb aplikasi dan suara balik tambahan dimatikan untuk mencegah gema ganda. Efek fisik soundcard tidak dapat dimatikan aplikasi.", color = Color.LightGray, fontSize = 12.sp)

                    }
                    val audioInputs = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager)
                        .getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                    val detectedUsbCard = audioInputs.any {
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    val detectedWiredMic = audioInputs.any {
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                    Text(
                        if (detectedUsbCard) "🎛 Smart Audio: USB soundcard terdeteksi • efek tambahan tidak diaktifkan otomatis"
                        else if (detectedWiredMic) "🎧 Smart Audio: mikrofon headset terdeteksi"
                        else "📱 Smart Audio: input HP / perangkat default",
                        color = Pink, fontWeight = FontWeight.Bold, fontSize = 12.sp
                    )
                    Text("Deteksi ini mengenali perangkat, bukan tombol EQ/reverb fisik. Bila soundcard sudah punya monitoring, biarkan suara balik aplikasi nonaktif.", color = Color.LightGray, fontSize = 12.sp)
                    Text("🎧 Rekam + dengar vokal bersamaan (headphone kabel)", color = Pink, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = recordWithWiredMonitor, onCheckedChange = { recordWithWiredMonitor = it }, enabled = !recording)
                        Spacer(Modifier.width(8.dp))
                        Text(if (recordWithWiredMonitor) "Aktif: rekaman WAV + suara balik" else "Nonaktif: rekaman standar", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text("LIVE OTG: aktifkan untuk memperkuat suara balik saat merekam melalui headphone/USB. Jika soundcard sudah memonitor mikrofon langsung, matikan monitoring perangkat agar tidak terdengar dobel, telat, atau bergema. Bila USB tidak mendukung jalur output, nonaktifkan opsi ini.", color = Color.LightGray, fontSize = 12.sp)
                    Text("🎧 Suara balik vokal headphone kabel (uji monitoring)", color = Pink, fontWeight = FontWeight.Bold)
                    Text("Mode uji: dengarkan mikrofon melalui headphone kabel. Matikan sebelum merekam; monitoring dan rekaman bersamaan belum tersedia.", color = Color.LightGray, fontSize = 12.sp)
                    Text("Penguatan suara balik saat rekam: ${"%.1f".format(monitorVolume)}×", color = Color.LightGray, fontSize = 12.sp)
                    Slider(value = monitorVolume, onValueChange = { monitorVolume = it; wiredMonitor.setVolume(it) }, valueRange = 0f..2f, enabled = !recording)
                    OutlinedButton(onClick = {
                        if (monitorEnabled) { wiredMonitor.stop(); monitorEnabled = false }
                        else if (recording) recordingStatus = "Selesaikan rekaman sebelum menguji monitoring."
                        else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) recordingStatus = "Berikan izin mikrofon melalui tombol rekaman terlebih dahulu."
                        else {
                            wiredMonitor.setVolume(monitorVolume)
                            monitorEnabled = wiredMonitor.start(if (microphoneSource == "live") "auto" else microphoneSource)
                            recordingStatus = if (monitorEnabled) "Monitoring headphone kabel aktif (mode uji)." else "Hubungkan headphone kabel/USB dan periksa izin mikrofon."
                        }
                    }, enabled = !recording, modifier = Modifier.fillMaxWidth()) { Text(if (monitorEnabled) "⏹ Matikan suara balik" else "🎧 Uji suara balik headphone") }
                    if (inputTooHot) {
                        Text(
                            "⚠️ INPUT TERLALU KERAS: kemungkinan distorsi pada rekaman. Turunkan MIC GAIN soundcard sebelum mengulang. Mengurangi volume mixing tidak memperbaiki clipping.",
                            color = Color(0xFFFFB36B), fontWeight = FontWeight.Bold, fontSize = 12.sp
                        )
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
                    OutlinedButton(onClick = { showMicOptions = !showMicOptions }, shape = RoundedCornerShape(24.dp)) {
                        Text(if (showMicOptions) "▴ Tutup pengaturan mikrofon" else "🎙 Pilihan mikrofon")
                    }
                    if (showMicOptions) {
                    Text("Indikator ini bukan pemantauan suara melalui speaker. Audio mikrofon tidak diputar ulang agar tidak menimbulkan feedback.", color = Color.LightGray, fontSize = 12.sp)
                    Text("Pilih input mikrofon sesuai kabel yang benar-benar terhubung:", color = Pink)
                    listOf("live" to "Soundcard LIVE (kabel analog)", "auto" to "Otomatis", "usb" to "USB / soundcard", "headset" to "Mic headset kabel", "phone" to "Mic HP").forEach { (id, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = microphoneSource == id, onClick = { if (!recording) microphoneSource = id }, enabled = !recording)
                            Text(title, color = Color.White, fontSize = 13.sp)
                        }
                    }
                    Text("Jika HP terhubung ke port LIVE1 soundcard lewat kabel analog, pilih Soundcard LIVE. Port ACCOMPANY biasanya input musik, bukan keluaran mic ke HP. Matikan suara balik aplikasi; dengarkan monitor langsung dari soundcard.", color = Color.LightGray, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = studioNaturalVocal, onCheckedChange = { if (!recording && !soundcardSettingsLocked) studioNaturalVocal = it }, enabled = !recording && !soundcardSettingsLocked)
                        Text("Studio: vokal natural (hindari boost dan efek berlebihan)", color = Color.White, fontSize = 12.sp)
                    }
                    Text("Sambungkan Bonkyo F999 NEO melalui OTG sebelum menekan Rekam. Bluetooth hanya digunakan sebagai mikrofon bila Android mendukung input audionya. Musik lokal tetap digabung otomatis setelah rekaman.",
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
                                    player.volume = volume
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
                                    (backing.scheme == "content" || backing.scheme == "file" || backing.scheme == "https")) {
                                    val backingStart = sessionStartPositionMs
                                    // Phone-speaker mode prioritizes vocals and lowers clean backing gain.
                                    // It cannot fully remove speaker bleed already in the mic signal.
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
                                                        destination, selectedMusicGain, selectedVoiceGain,
                                                        if (smartAudioEnabled && sessionExternalAudio) false else if (studioNaturalVocal) false else smoothVocal,
                                                        if (smartAudioEnabled && sessionExternalAudio) 0f else if (studioNaturalVocal) 0.15f else vocalClarity,
                                                        if (smartAudioEnabled && sessionExternalAudio) false else studioNaturalVocal,
                                                        autoMusicDelayMs + syncAdjustmentMs.toLong(),
                                                        cleanExternal = smartAudioEnabled && sessionExternalAudio,
                                                        vocalPriority = vocalPriority
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
                                    recordingStatus = "Vokal tersimpan. Musik hanya bisa digabung dari MP3/MP4 lokal atau HTTPS langsung yang diputar normal tanpa jeda."
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
                        OutlinedButton(onClick = { showMixOptions = !showMixOptions }, shape = RoundedCornerShape(24.dp)) {
                            Text(if (showMixOptions) "▴ Tutup pengaturan mixing" else "⚙ Atur volume & sinkronisasi")
                        }
                        if (showMixOptions) {
                        Text("Musik: ${(musicGain * 100).toInt()}%", color = Color.LightGray)
                        Slider(value = musicGain, onValueChange = { musicGain = it }, valueRange = 0f..1f)
                        Text("Penguatan vokal: ${"%.1f".format(voiceGain)}×", color = Color.LightGray)
                        Slider(value = voiceGain, onValueChange = { voiceGain = it }, valueRange = 0f..6f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = vocalPriority, onCheckedChange = { vocalPriority = it })
                            Spacer(Modifier.width(8.dp))
                            Text("Utamakan vokal: musik otomatis sedikit mengecil saat bernyanyi", color = Color.LightGray, fontSize = 12.sp)
                        }
                        Text("Sesudah mengubah penguatan, tekan Gabungkan Vokal dan Musik lagi. Slider tidak mengubah file hasil mixing lama secara langsung.", color = Color.LightGray, fontSize = 12.sp)
                        Text("Sinkronisasi awal terukur: ${recordingPrefs.getLong("last_auto_music_delay_ms", 0L)} ms (perkiraan waktu mulai, tanpa acuan tetap)", color = Color.LightGray, fontSize = 12.sp)
                        Text("Koreksi sinkronisasi musik: ${syncAdjustmentMs.toInt()} ms (geser kanan untuk menunda musik)", color = Color.LightGray)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { syncAdjustmentMs = (syncAdjustmentMs - 1f).coerceAtLeast(-500f) }) { Text("−1 ms") }
                            OutlinedButton(onClick = { syncAdjustmentMs = (syncAdjustmentMs + 1f).coerceAtMost(500f) }) { Text("+1 ms") }
                            OutlinedButton(onClick = { syncAdjustmentMs = 0f }) { Text("Reset") }
                        }
                        Slider(
                            value = syncAdjustmentMs,
                            onValueChange = { syncAdjustmentMs = it },
                            valueRange = -500f..500f
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
                                val selectedAutoDelayMs = recordingPrefs.getLong("last_auto_music_delay_ms", 0L)
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
                                                    selectedBackingStartMs.coerceAtLeast(0L),
                                                    destination,
                                                    selectedMusicGain,
                                                    selectedVoiceGain,
                                                    smoothVocal = false,
                                                    vocalClarity = if (smartAudioEnabled && sessionExternalAudio) 0f else 0.15f,
                                                    naturalStudio = !(smartAudioEnabled && sessionExternalAudio),
                                                    musicDelayMs = selectedAutoDelayMs + selectedSyncMs,
                                                    cleanExternal = smartAudioEnabled && sessionExternalAudio,
                                                    vocalPriority = vocalPriority
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
}
}
private fun formatTime(ms: Long): String {
    val sec = ms / 1000
    return "%02d:%02d".format(sec / 60, sec % 60)
}
