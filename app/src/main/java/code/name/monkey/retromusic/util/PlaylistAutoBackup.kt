/*
 * Copyright (c) 2026 crunchbank (Shark Music).
 *
 * This file is part of Shark Music, a modified version of Retro Music Player.
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.util

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.db.PlaylistDao
import code.name.monkey.retromusic.db.PlaylistWithSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Keeps a copy of every playlist as an .m3u file in the Music folder, rewritten a moment after
 * any playlist changes. Song paths are written relative to the Music folder, so the files still
 * work if the music is copied to another device or another storage location.
 *
 * Only files carrying [MARKER] are ever overwritten or deleted: an .m3u the user or another app
 * put in the Music folder is left alone, even if it has a playlist's name.
 */
object PlaylistAutoBackup : KoinComponent {

    const val PREF_KEY = "auto_backup_playlists"
    private const val MARKER = "# Backed up by Shark Music. Edits to this file will be overwritten."
    private const val TAG = "PlaylistAutoBackup"
    private const val DEBOUNCE_MS = 2000L

    private val dao: PlaylistDao by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val appContext = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val enabled = callbackFlow {
            trySend(prefs.getBoolean(PREF_KEY, true))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == PREF_KEY) trySend(p.getBoolean(PREF_KEY, true))
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        combine(dao.playlistsWithSongsFlow(), enabled) { playlists, on -> playlists to on }
            .debounce(DEBOUNCE_MS)
            .onEach { (playlists, on) -> if (on) sync(appContext, playlists) }
            .catch { Log.e(TAG, "Playlist backup stopped", it) }
            .launchIn(scope)
    }

    private fun sync(context: Context, playlists: List<PlaylistWithSongs>) {
        val musicDir = getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (!musicDir.isDirectory && !musicDir.mkdirs()) {
            Log.w(TAG, "No Music folder at ${musicDir.path}")
            return
        }
        val changed = mutableListOf<String>()
        val written = mutableSetOf<String>()

        for (playlist in playlists.sortedBy { it.playlistEntity.playListId }) {
            val file = freeFile(musicDir, fileName(playlist.playlistEntity.playlistName), written)
            written += file.name
            val text = render(playlist, musicDir.absolutePath)
            try {
                if (file.exists() && file.readText() == text) continue
                file.writeText(text)
                changed += file.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Can't write ${file.path}", e)
            }
        }

        // Playlists that were deleted or renamed: remove the files we wrote for them.
        musicDir.listFiles { f -> f.isFile && f.name.endsWith(".m3u", ignoreCase = true) }
            ?.filter { it.name !in written && isOurs(it) }
            ?.forEach {
                if (it.delete()) changed += it.absolutePath
                else Log.w(TAG, "Can't delete ${it.path}")
            }

        if (changed.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, changed.toTypedArray(), null, null)
        }
    }

    private fun render(playlist: PlaylistWithSongs, musicPath: String): String = buildString {
        append("#EXTM3U\n")
        append(MARKER).append('\n')
        append("#PLAYLIST:").append(playlist.playlistEntity.playlistName).append('\n')
        for (song in playlist.songs.sortedBy { it.songPrimaryKey }) {
            val artist = song.artistName.takeUnless { it.isBlank() || it == "<unknown>" }
            append("#EXTINF:").append(song.duration / 1000).append(',')
            if (artist != null) append(artist).append(" - ")
            append(song.title).append('\n')
            append(relativePath(musicPath, song.data)).append('\n')
        }
    }

    /** [target] relative to the folder [base], using "../" to climb out of it if needed. */
    internal fun relativePath(base: String, target: String): String {
        val from = base.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val to = target.split('/').filter { it.isNotEmpty() }
        if (!target.startsWith("/")) return target
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
        val up = List(from.size - common) { ".." }
        return (up + to.drop(common)).joinToString("/")
    }

    /** A playlist name made safe for a file name. */
    private fun fileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return clean.ifEmpty { "Playlist" }
    }

    /**
     * The file for [base]: "<base>.m3u" if it's free or ours, otherwise "<base> (2).m3u" and so on.
     * Names already used in this sync are skipped, so two playlists never share a file.
     */
    private fun freeFile(dir: File, base: String, taken: Set<String>): File {
        var n = 1
        while (true) {
            val name = if (n == 1) "$base.m3u" else "$base ($n).m3u"
            val file = File(dir, name)
            if (name !in taken && (!file.exists() || isOurs(file))) return file
            n++
        }
    }

    private fun isOurs(file: File): Boolean = try {
        file.bufferedReader().use { r -> generateSequence { r.readLine() }.take(3).any { it == MARKER } }
    } catch (e: Exception) {
        false
    }
}
