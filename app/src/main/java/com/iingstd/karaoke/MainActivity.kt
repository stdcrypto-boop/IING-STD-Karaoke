package com.iingstd.karaoke

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader

private val Pink = Color(0xFFFF5EAD)
private val Dark = Color(0xFF100C1B)
private data class Lyric(val timeMs: Long, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val player = remember { ExoPlayer.Builder(this).build() }
            DisposableEffect(player) { onDispose { player.release() } }
            var mode by remember { mutableStateOf("Offline") }
            var url by remember { mutableStateOf("") }
            var track by remember { mutableStateOf("Belum ada lagu") }
            var playing by remember { mutableStateOf(false) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var lyrics by remember { mutableStateOf(emptyList<Lyric>()) }
            val lyricListState = rememberLazyListState()
            var error by remember { mutableStateOf("") }
            val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    try {
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                        player.play()
                        playing = true
                        track = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio lokal"
                        error = ""
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
            val activeLyricIndex = lyrics.indexOfLast { it.timeMs <= position }
            LaunchedEffect(activeLyricIndex, lyrics) {
                if (activeLyricIndex >= 0) lyricListState.animateScrollToItem(activeLyricIndex)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Pink, background = Dark, surface = Color(0xFF21182D))) {
                Column(
                    Modifier.fillMaxSize().background(Dark).padding(20.dp),
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
                        Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("📁 Pilih lagu dari HP")
                        }
                    } else {
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("Tautan audio HTTPS langsung") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            if (!url.trim().startsWith("https://", ignoreCase = true)) {
                                error = "Gunakan tautan audio HTTPS langsung"
                            } else {
                                try {
                                    player.setMediaItem(MediaItem.fromUri(url.trim()))
                                    player.prepare()
                                    player.play()
                                    track = "Streaming online"
                                    error = ""
                                } catch (e: Exception) { error = e.message ?: "Streaming gagal" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("▶ Putar streaming") }
                    }
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(8.dp))
                    Text("Kecepatan: ${"%.2f".format(speed)}×", color = Color.LightGray)
                    Slider(value = speed, onValueChange = {
                        speed = it
                        player.setPlaybackSpeed(it)
                    }, valueRange = 0.5f..1.5f)
                    Button(onClick = { lyricPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth()) { Text("📝 Buka lirik .LRC") }
                    Spacer(Modifier.height(12.dp))
                    if (error.isNotBlank()) Text(error, color = Color(0xFFFF9E9E))
                    val current = activeLyricIndex
                    LazyColumn(
                        state = lyricListState,
                        modifier = Modifier.fillMaxWidth().weight(1f)
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
