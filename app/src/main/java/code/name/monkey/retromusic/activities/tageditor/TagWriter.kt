/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * Modifications copyright (c) 2026 crunchbank (Shark Music): safer saving on Android 11+ (one
 * copy per song, edited copies verified, originals truncated on write, failures reported).
 */
package code.name.monkey.retromusic.activities.tageditor

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.misc.UpdateToastMediaScannerCompletionListener
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.util.MusicUtil.createAlbumArtFile
import code.name.monkey.retromusic.util.MusicUtil.deleteAlbumArt
import code.name.monkey.retromusic.util.MusicUtil.insertAlbumArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.AudioHeader
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.CannotWriteException
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException
import org.jaudiotagger.tag.FieldDataInvalidException
import org.jaudiotagger.tag.TagException
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.images.Artwork
import java.io.File
import java.io.IOException

class TagWriter {

    companion object {

        suspend fun scan(context: Context, toBeScanned: List<String?>?) {
            if (toBeScanned.isNullOrEmpty()) {
                Log.i("scan", "scan: Empty")
                context.showToast("Scan file from folder")
                return
            }
            MediaScannerConnection.scanFile(
                context,
                toBeScanned.toTypedArray(),
                null,
                withContext(Dispatchers.Main) {
                    if (context is Activity) UpdateToastMediaScannerCompletionListener(
                        context, toBeScanned
                    ) else null
                }
            )
        }

        suspend fun writeTagsToFiles(context: Context, info: AudioTagInfo) {
            withContext(Dispatchers.IO) {
                var artwork: Artwork? = null
                var albumArtFile: File? = null
                if (info.artworkInfo?.artwork != null) {
                    try {
                        albumArtFile = createAlbumArtFile(context).canonicalFile
                        info.artworkInfo.artwork.compress(
                            Bitmap.CompressFormat.JPEG,
                            100,
                            albumArtFile.outputStream()
                        )
                        artwork = AndroidArtwork.createArtworkFromFile(albumArtFile)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                var wroteArtwork = false
                var deletedArtwork = false
                for (filePath in info.filePaths!!) {
                    try {
                        val audioFile = AudioFileIO.read(File(filePath))
                        val tag = audioFile.tagOrCreateAndSetDefault
                        if (info.fieldKeyValueMap != null) {
                            for ((key, newValue) in info.fieldKeyValueMap) {
                                try {
                                    val currentValue = tag.getFirst(key)
                                    if (currentValue != newValue) {
                                        if (newValue.isEmpty()) {
                                            tag.deleteField(key)
                                        } else {
                                            tag.setField(key, newValue)
                                        }
                                    }
                                } catch (e: FieldDataInvalidException) {
                                    withContext(Dispatchers.Main) {
                                        context.showToast(R.string.could_not_write_tags_to_file)
                                    }
                                    return@withContext listOf<File>()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        if (info.artworkInfo != null) {
                            if (info.artworkInfo.artwork == null) {
                                tag.deleteArtworkField()
                                deletedArtwork = true
                            } else if (artwork != null) {
                                tag.deleteArtworkField()
                                tag.setField(artwork)
                                wroteArtwork = true
                            }
                        }
                        audioFile.commit()
                    } catch (e: CannotReadException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: CannotWriteException) {
                        e.printStackTrace()
                    } catch (e: TagException) {
                        e.printStackTrace()
                    } catch (e: ReadOnlyFileException) {
                        e.printStackTrace()
                    } catch (e: InvalidAudioFrameException) {
                        e.printStackTrace()
                    }
                }
                if (wroteArtwork) {
                    insertAlbumArt(context, info.artworkInfo!!.albumId, albumArtFile!!.path)
                } else if (deletedArtwork) {
                    deleteAlbumArt(context, info.artworkInfo!!.albumId)
                }
                scan(context, info.filePaths)
            }
        }

        /**
         * Writes the tags to a private copy of each file, for the caller to copy back over the
         * original once the user grants write access.
         *
         * Returns one entry per path, in order: the edited copy, or null when that file could not
         * be edited or the edited copy failed verification. A null entry must not be written back.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        suspend fun writeTagsToFilesR(context: Context, info: AudioTagInfo): List<File?> =
            withContext(Dispatchers.IO) {
                val cacheFiles = mutableListOf<File?>()
                var artwork: Artwork? = null
                var albumArtFile: File? = null

                if (info.artworkInfo?.artwork != null) {
                    try {
                        albumArtFile = createAlbumArtFile(context).canonicalFile
                        albumArtFile.outputStream().use {
                            info.artworkInfo.artwork.compress(Bitmap.CompressFormat.JPEG, 100, it)
                        }
                        artwork = AndroidArtwork.createArtworkFromFile(albumArtFile)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                var wroteArtwork = false
                var deletedArtwork = false

                // One working folder, emptied first so copies from an earlier session can't linger.
                val workDir = File(context.cacheDir, TAG_EDIT_DIR)
                workDir.deleteRecursively()
                workDir.mkdirs()

                for ((index, filePath) in info.filePaths!!.withIndex()) {
                    // Named by position, not by the original name: two songs in one album can
                    // share a file name (e.g. "01.flac" in CD1/ and CD2/), and a shared copy
                    // would write one song's audio over the other. The extension is kept
                    // because jaudiotagger picks the format from it.
                    val originFile = File(filePath)
                    val cacheFile = File(workDir, "$index.${originFile.extension}")
                    try {
                        originFile.inputStream().use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val audioFile = AudioFileIO.read(cacheFile)
                        val before = audioFile.audioHeader
                        val tag = audioFile.tagOrCreateAndSetDefault

                        if (info.fieldKeyValueMap != null) {
                            for ((key, newValue) in info.fieldKeyValueMap) {
                                try {
                                    val currentValue = tag.getFirst(key)
                                    if (currentValue != newValue) {
                                        if (newValue.isEmpty()) {
                                            tag.deleteField(key)
                                        } else {
                                            tag.setField(key, newValue)
                                        }
                                    }
                                } catch (e: FieldDataInvalidException) {
                                    withContext(Dispatchers.Main) {
                                        context.showToast(R.string.could_not_write_tags_to_file)
                                    }
                                    workDir.deleteRecursively()
                                    return@withContext info.filePaths.map { null }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        if (info.artworkInfo != null) {
                            if (info.artworkInfo.artwork == null) {
                                tag.deleteArtworkField()
                                deletedArtwork = true
                            } else if (artwork != null) {
                                tag.deleteArtworkField()
                                tag.setField(artwork)
                                wroteArtwork = true
                            }
                        }
                        audioFile.commit()

                        if (isSameAudio(before, AudioFileIO.read(cacheFile).audioHeader)) {
                            cacheFiles.add(cacheFile)
                        } else {
                            Log.e(TAG, "Edited copy of $filePath failed verification; not saving it")
                            cacheFile.delete()
                            cacheFiles.add(null)
                        }
                    } catch (e: Exception) {
                        // CannotRead/CannotWrite/Tag/ReadOnly/InvalidAudioFrame and IO errors:
                        // leave this file untouched rather than copying anything back over it.
                        Log.e(TAG, "Could not edit tags of $filePath", e)
                        cacheFile.delete()
                        cacheFiles.add(null)
                    }
                }
                if (wroteArtwork) {
                    insertAlbumArt(context, info.artworkInfo!!.albumId, albumArtFile!!.path)
                } else if (deletedArtwork) {
                    deleteAlbumArt(context, info.artworkInfo!!.albumId)
                }
                cacheFiles
            }

        /**
         * True when two headers describe the same audio stream. Rewriting tags must never change
         * the audio, so a mismatch means the edited copy is damaged and must not be saved.
         */
        private fun isSameAudio(a: AudioHeader, b: AudioHeader): Boolean =
            a.format == b.format &&
                a.sampleRateAsNumber == b.sampleRateAsNumber &&
                a.channels == b.channels &&
                a.trackLength == b.trackLength

        /**
         * Copies an edited file from [writeTagsToFilesR] over the original. Returns false, leaving
         * a log entry, if the copy fails or the result isn't exactly the edited file's size.
         */
        fun copyBack(context: Context, cacheFile: File, target: Uri): Boolean = try {
            // "wt", not "w": MediaStore does not truncate on "w", so a shorter edited file
            // would keep the old file's trailing bytes after its own end.
            val out = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("No output stream for $target")
            out.use { output -> cacheFile.inputStream().use { it.copyTo(output) } }
            val written = context.contentResolver.openFileDescriptor(target, "r")
                ?.use { it.statSize } ?: -1L
            if (written != cacheFile.length()) {
                Log.e(TAG, "Wrote $written bytes to $target, expected ${cacheFile.length()}")
            }
            written == cacheFile.length()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save tags to $target", e)
            false
        }

        private const val TAG = "TagWriter"
        const val TAG_EDIT_DIR = "tag-editor"
    }
}