/*
 * Copyright (c) 2019 Hemanth Savarala.
 * Modifications copyright (c) 2026 crunchbank (Shark Music): genre song counts come from one query on Android 11+;
 * on Android 11+ genre strings such as "Pop, Folk" are split into separate genres.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.Audio.Genres
import androidx.annotation.RequiresApi
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.Constants.IS_MUSIC
import code.name.monkey.retromusic.Constants.baseProjection
import code.name.monkey.retromusic.extensions.getLong
import code.name.monkey.retromusic.extensions.getStringOrNull
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.providers.BlacklistStore
import code.name.monkey.retromusic.util.PreferenceUtil
import java.text.Collator
import java.util.Locale

interface GenreRepository {
    fun genres(query: String): List<Genre>

    fun genres(): List<Genre>

    fun songs(genreId: Long): List<Song>

    fun song(genreId: Long): Song
}

class RealGenreRepository(
    private val contentResolver: ContentResolver,
    private val songRepository: RealSongRepository,
    private val context: Context
) : GenreRepository {

    override fun genres(query: String): List<Genre> {
        if (VersionUtils.hasR()) {
            val genres = genreIndex()?.genres ?: return emptyList()
            return sortGenres(genres.filter { it.name.contains(query, ignoreCase = true) })
        }
        return getGenresFromCursor(makeGenreCursor(query))
    }

    override fun genres(): List<Genre> {
        if (VersionUtils.hasR()) return sortGenres(genreIndex()?.genres ?: return emptyList())
        return getGenresFromCursor(makeGenreCursor())
    }

    override fun songs(genreId: Long): List<Song> {
        // The genres table only stores songs that have a genre specified,
        // so we need to get songs without a genre a different way.
        if (genreId == -1L) return getSongsWithNoGenre()
        if (VersionUtils.hasR()) {
            genreIndex()?.members?.get(genreId)?.let { ids ->
                return songRepository.songs(
                    songRepository.makeSongCursor(
                        BaseColumns._ID + " IN (" + ids.joinToString(",") + ")", null
                    )
                )
            }
        }
        // Not a split genre: a MediaStore genre id (Android 10 and earlier, or an old media id).
        return songRepository.songs(makeGenreSongCursor(genreId))
    }

    override fun song(genreId: Long): Song {
        if (VersionUtils.hasR()) {
            genreIndex()?.members?.get(genreId)?.let { ids ->
                return songRepository.song(ids.first())
            }
        }
        return songRepository.song(makeGenreSongCursor(genreId))
    }

    /**
     * The genres of every song, split out of the genre strings MediaStore keeps.
     *
     * MediaStore records one genre string per song: a file tagged "Pop, Folk" is filed under a
     * single genre named "Pop, Folk", and of several separate genre fields it keeps only one. So
     * on Android 11, where each audio row carries its genre string, the Genres tab is built here
     * instead: each string is split on [GENRE_SEPARATORS], names are merged ignoring case, and a
     * song is listed under every genre it names. Songs are filtered as on the Songs tab (blacklist,
     * minimum length).
     *
     * [Genre.id] is a hash of the lower-cased name, so it stays the same across launches for
     * media ids (Android Auto, the media session) that store it.
     */
    private class GenreIndex(
        val key: String,
        val genres: List<Genre>,
        val members: Map<Long, LongArray>
    )

    @Volatile
    private var cachedIndex: GenreIndex? = null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun genreIndex(): GenreIndex? {
        // Rebuilt when the library changes (MediaStore generation) or the song filters do; the
        // genre list asks for one song per genre for its artwork, so this must not rescan each time.
        val key = indexKey()
        cachedIndex?.let { if (key != null && it.key == key) return it }

        class Bucket {
            val spellings = HashMap<String, Int>()
            val ids = ArrayList<Long>()
        }

        val buckets = LinkedHashMap<String, Bucket>()
        val cursor = songRepository.makeSongCursor(
            null,
            null,
            sortOrder = BaseColumns._ID,
            projection = arrayOf(BaseColumns._ID, MediaStore.Audio.AudioColumns.GENRE)
        ) ?: return null
        try {
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(BaseColumns._ID)
                val genreColumn = it.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.GENRE)
                while (it.moveToNext()) {
                    val raw = it.getString(genreColumn) ?: continue
                    val songId = it.getLong(idColumn)
                    for (name in splitGenre(raw)) {
                        val bucket = buckets.getOrPut(name.lowercase(Locale.ROOT)) { Bucket() }
                        bucket.spellings[name] = (bucket.spellings[name] ?: 0) + 1
                        bucket.ids += songId
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        val genres = ArrayList<Genre>(buckets.size)
        val members = HashMap<Long, LongArray>(buckets.size)
        for ((lowerName, bucket) in buckets) {
            val id = stableId(lowerName)
            // Shown with its most common spelling ("Folk" over "folk").
            val name = bucket.spellings.maxByOrNull { it.value }!!.key
            genres += Genre(id, name, bucket.ids.size)
            members[id] = bucket.ids.toLongArray()
        }
        return GenreIndex(key ?: "", genres, members).also { if (key != null) cachedIndex = it }
    }

    /** Null when the library's generation can't be read; the index is then not cached. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun indexKey(): String? = try {
        val generation = MediaStore.getExternalVolumeNames(context)
            .sorted()
            .joinToString(",") { "$it=" + MediaStore.getGeneration(context, it) }
        listOf(
            generation,
            PreferenceUtil.filterLength,
            PreferenceUtil.isWhiteList,
            BlacklistStore.getInstance(context).paths
        ).joinToString("|")
    } catch (e: Exception) {
        null
    }

    private fun sortGenres(genres: List<Genre>): List<Genre> {
        val collator = Collator.getInstance()
        val sorted = genres.sortedWith { a, b -> collator.compare(a.name, b.name) }
        return if (PreferenceUtil.genreSortOrder.endsWith(" DESC", ignoreCase = true)) {
            sorted.reversed()
        } else sorted
    }

    private fun getSongCount(genreId: Long): Int {
        contentResolver.query(
            Genres.Members.getContentUri("external", genreId),
            arrayOf(Genres.Members.AUDIO_ID),
            IS_MUSIC,
            null,
            null
        ).use {
            return it?.count ?: 0
        }
    }

    /**
     * Song counts for every genre from a single query, keyed by genre id.
     *
     * From Android 11 each audio row carries its genre id, so this replaces one
     * [getSongCount] query per genre, which is slow on libraries with hundreds of
     * genre tags. Returns null before Android 11 or if the query fails, in which
     * case the caller falls back to counting per genre.
     */
    private fun getSongCountsByGenre(): Map<Long, Int>? {
        if (!VersionUtils.hasR()) return null
        return try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.AudioColumns.GENRE_ID),
                IS_MUSIC,
                null,
                null
            )?.use { cursor ->
                val counts = HashMap<Long, Int>()
                val genreIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.GENRE_ID)
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(genreIdColumn)) {
                        val genreId = cursor.getLong(genreIdColumn)
                        counts[genreId] = (counts[genreId] ?: 0) + 1
                    }
                }
                counts
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getGenreFromCursor(cursor: Cursor, songCounts: Map<Long, Int>?): Genre {
        val id = cursor.getLong(Genres._ID)
        val name = cursor.getStringOrNull(Genres.NAME)
        val songCount = if (songCounts != null) songCounts[id] ?: 0 else getSongCount(id)
        return Genre(id, name ?: "", songCount)
    }

    private fun getSongsWithNoGenre(): List<Song> {
        val selection =
            BaseColumns._ID + " NOT IN " + "(SELECT " + Genres.Members.AUDIO_ID + " FROM audio_genres_map)"
        return songRepository.songs(songRepository.makeSongCursor(selection, null))
    }

    private fun makeGenreSongCursor(genreId: Long): Cursor? {
        return try {
            contentResolver.query(
                Genres.Members.getContentUri("external", genreId),
                baseProjection,
                IS_MUSIC,
                null,
                SortOrder.SongSortOrder.toQuery(PreferenceUtil.songSortOrder)
            )
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun getGenresFromCursor(cursor: Cursor?): ArrayList<Genre> {
        val genres = arrayListOf<Genre>()
        cursor?.use {
            if (cursor.moveToFirst()) {
                val songCounts = getSongCountsByGenre()
                do {
                    val genre = getGenreFromCursor(cursor, songCounts)
                    if (genre.songCount > 0) {
                        genres.add(genre)
                    }
                } while (cursor.moveToNext())
            }
        }
        return genres
    }

    private fun makeGenreCursor(): Cursor? {
        val projection = arrayOf(Genres._ID, Genres.NAME)
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                PreferenceUtil.genreSortOrder
            )
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun makeGenreCursor(query: String): Cursor? {
        val projection = arrayOf(Genres._ID, Genres.NAME)
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                Genres.NAME + " = ?",
                arrayOf(query),
                PreferenceUtil.genreSortOrder
            )
        } catch (e: SecurityException) {
            return null
        }
    }

    companion object {
        /**
         * What separates genres within one genre string. A bare "/" is left alone because it is
         * part of names such as "Singer/Songwriter". "\u0000" is ID3v2.4's own separator.
         */
        val GENRE_SEPARATORS = charArrayOf(',', ';', '|', '\u0000')
        private val WHITESPACE = Regex("\\s+")

        /** "Pop, Folk;  Singer Songwriter" → ["Pop", "Folk", "Singer Songwriter"], no repeats. */
        fun splitGenre(raw: String): List<String> = raw.split(*GENRE_SEPARATORS)
            .map { it.trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }

        /** FNV-1a over the lower-cased name: stable across launches, positive, never -1. */
        private fun stableId(lowerName: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (c in lowerName) {
                hash = hash xor c.code.toLong()
                hash *= 0x100000001b3L
            }
            return (hash and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
        }
    }
}
