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
import android.util.Log
import code.name.monkey.retromusic.R
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.Locale

/** Reads everything jaudiotagger can see in a song file, for display only. Never writes. */
object TagDump {

    enum class Kind { AUDIO, TAGS, RAW, ARTWORK }

    class Entry(val name: String, val values: List<String>)

    /** [detail] is the tag format for [Kind.TAGS] (e.g. "ID3v2.4"), otherwise empty. */
    class Section(val kind: Kind, val entries: List<Entry>, val detail: String = "")

    /**
     * Returns the file's sections, an empty list if it has no readable audio header or tags,
     * or null if jaudiotagger can't read the file at all.
     */
    fun read(context: Context, file: File): List<Section>? {
        val audioFile = try {
            AudioFileIO.read(file)
        } catch (e: Exception) {
            Log.w(TAG, "Can't read ${file.path}", e)
            return null
        }
        val sections = mutableListOf<Section>()

        audioFile.audioHeader?.let { h ->
            val entries = listOfNotNull(
                entry(context.getString(R.string.label_file_format), h.format),
                entry(context.getString(R.string.label_encoding), h.encodingType?.takeIf { it != h.format }),
                entry(
                    context.getString(R.string.label_bit_rate),
                    h.bitRate?.let { "$it kb/s" + if (h.isVariableBitRate) " (VBR)" else "" }
                ),
                entry(context.getString(R.string.label_sampling_rate), h.sampleRate?.let { "$it Hz" }),
                entry(
                    context.getString(R.string.label_bit_depth),
                    h.bitsPerSample.takeIf { it > 0 }?.let { "$it-bit" }
                ),
                entry(context.getString(R.string.label_channels), h.channels),
                entry(context.getString(R.string.label_track_length), formatLength(h.trackLength)),
                entry(
                    context.getString(R.string.label_lossless),
                    context.getString(if (h.isLossless) R.string.tag_yes else R.string.tag_no)
                ),
            )
            if (entries.isNotEmpty()) sections += Section(Kind.AUDIO, entries)
        }

        val tag = audioFile.tag ?: return sections
        val standard = standardFields(tag)
        if (standard.isNotEmpty()) sections += Section(Kind.TAGS, standard, tagFormat(tag))

        // Fields stored under their own names that no standard key covers (ReplayGain, custom
        // fields, …). A stored field whose values match a standard entry's is already shown.
        val shown = standard.map { it.values }.toSet()
        val raw = rawFields(tag).filter { it.values !in shown }
        if (raw.isNotEmpty()) sections += Section(Kind.RAW, raw)

        val artwork = try {
            tag.artworkList.mapIndexed { i, art ->
                val type = PictureTypes.getInstanceOf().getValueForId(art.pictureType)
                val size = art.binaryData?.size ?: 0
                Entry(
                    context.getString(R.string.tag_picture_n, i + 1),
                    listOf(listOfNotNull(type, art.mimeType, "${size / 1024} KB").joinToString(" · "))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (artwork.isNotEmpty()) sections += Section(Kind.ARTWORK, artwork)

        return sections
    }

    /** Every standard field the file has, with all of its values (not just the first). */
    private fun standardFields(tag: Tag): List<Entry> = FieldKey.entries
        .filter { it != FieldKey.COVER_ART }
        .mapNotNull { key ->
            val values = try {
                tag.getAll(key).filter { it.isNotBlank() }
            } catch (e: Exception) {
                // Not every key exists in every tag format.
                emptyList()
            }
            if (values.isEmpty()) null else Entry(label(key), values)
        }

    /** Every field exactly as stored, grouped by its id. Artwork excluded. */
    private fun rawFields(tag: Tag): List<Entry> {
        val byId = linkedMapOf<String, MutableList<String>>()
        try {
            val it = tag.fields
            while (it.hasNext()) {
                val field = it.next()
                if (isArtwork(field)) continue
                byId.getOrPut(field.id) { mutableListOf() } += fieldValue(field)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't list raw fields", e)
        }
        return byId.map { (id, values) -> Entry(id, values) }
    }

    private fun isArtwork(field: TagField): Boolean =
        field.id.let { it == "APIC" || it == "PIC" || it == "covr" || it == "METADATA_BLOCK_PICTURE" || it == "COVERART" }

    private fun fieldValue(field: TagField): String = try {
        when {
            field is TagTextField -> field.content
            field.isBinary -> "[binary, ${field.rawContent?.size ?: 0} bytes]"
            else -> field.toString()
        }
    } catch (e: Exception) {
        "[unreadable]"
    }

    private fun tagFormat(tag: Tag): String {
        val name = tag.javaClass.simpleName
        return when {
            name.startsWith("ID3v24") -> "ID3v2.4"
            name.startsWith("ID3v23") -> "ID3v2.3"
            name.startsWith("ID3v22") -> "ID3v2.2"
            name.startsWith("ID3v1") -> "ID3v1"
            name.startsWith("Flac") || name.startsWith("Vorbis") -> "Vorbis comments"
            name.startsWith("Mp4") -> "MP4"
            name.startsWith("Asf") -> "ASF"
            name.startsWith("Wav") -> "WAV"
            name.startsWith("Aiff") -> "AIFF"
            else -> name.removeSuffix("Tag")
        }
    }

    /** ALBUM_ARTIST → "Album artist"; MUSICBRAINZ_TRACK_ID → "Musicbrainz track id". */
    private fun label(key: FieldKey): String =
        key.name.lowercase(Locale.ROOT).replace('_', ' ')
            .replaceFirstChar { it.titlecase(Locale.ROOT) }

    private fun entry(name: String, value: String?): Entry? =
        value?.takeIf { it.isNotBlank() }?.let { Entry(name, listOf(it)) }

    private fun formatLength(seconds: Int): String? =
        if (seconds <= 0) null else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

    private const val TAG = "TagDump"
}
