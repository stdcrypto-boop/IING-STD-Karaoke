package com.iingstd.karaoke

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedSong(val title: String, val uri: String, val online: Boolean, val playedAt: Long)

class SongLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("karaoke_song_library", Context.MODE_PRIVATE)

    fun history(): List<SavedSong> = read("history")
    fun favorites(): List<SavedSong> = read("favorites")

    fun record(song: SavedSong) {
        val updated = listOf(song) + history().filterNot { it.uri == song.uri }
        write("history", updated.take(100))
    }

    fun toggleFavorite(song: SavedSong) {
        val old = favorites()
        write("favorites", if (old.any { it.uri == song.uri }) old.filterNot { it.uri == song.uri } else listOf(song) + old)
    }

    fun removeHistory(uri: String) = write("history", history().filterNot { it.uri == uri })
    fun clearHistory() = write("history", emptyList())

    private fun read(key: String): List<SavedSong> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = obj.optString("uri")
            if (uri.isBlank()) null else SavedSong(
                obj.optString("title", "Lagu"),
                uri,
                obj.optBoolean("online", false),
                obj.optLong("playedAt", 0L)
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun write(key: String, songs: List<SavedSong>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(JSONObject().put("title", song.title).put("uri", song.uri)
                .put("online", song.online).put("playedAt", song.playedAt))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
