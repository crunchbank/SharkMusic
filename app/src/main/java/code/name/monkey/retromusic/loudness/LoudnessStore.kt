/*
 * Copyright (c) 2026 crunchbank.
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.loudness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Remembers each song's loudness, keyed by file path and modification time, so a file that is
 * re-tagged or replaced is looked at again. A row with no loudness records a file that could not
 * be measured, so it isn't retried on every play.
 */
class LoudnessStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "loudness.db", null, 1) {

    sealed class Entry {
        class Known(val info: LoudnessInfo) : Entry()
        object Unmeasurable : Entry()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE loudness (path TEXT PRIMARY KEY, modified INTEGER NOT NULL, " +
                    "loudness REAL, peak REAL, source INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun get(path: String, modified: Long): Entry? = readableDatabase.query(
        "loudness", arrayOf("loudness", "peak", "source"),
        "path = ? AND modified = ?", arrayOf(path, modified.toString()), null, null, null
    ).use { c ->
        if (!c.moveToFirst()) return null
        if (c.isNull(0)) return Entry.Unmeasurable
        Entry.Known(
            LoudnessInfo(
                c.getDouble(0),
                if (c.isNull(1)) null else c.getDouble(1),
                LoudnessInfo.Source.entries.getOrElse(c.getInt(2)) { LoudnessInfo.Source.MEASURED }
            )
        )
    }

    fun put(path: String, modified: Long, info: LoudnessInfo?) {
        val values = ContentValues().apply {
            put("path", path)
            put("modified", modified)
            put("loudness", info?.loudness)
            put("peak", info?.peak)
            put("source", (info?.source ?: LoudnessInfo.Source.MEASURED).ordinal)
        }
        writableDatabase.insertWithOnConflict("loudness", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
