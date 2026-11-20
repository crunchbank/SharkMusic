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

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.TagTextField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import java.io.File
import java.util.Locale

/**
 * Reads a song's loudness from ReplayGain or R128 tags.
 *
 * - ReplayGain 2 (REPLAYGAIN_TRACK_GAIN, relative to -18 LUFS, plus REPLAYGAIN_TRACK_PEAK)
 *   in Vorbis comments, ID3 TXXX frames, MP4 freeform atoms and APE-style keys.
 * - R128_TRACK_GAIN (Opus, Q7.8 dB relative to -23 LUFS). It carries no peak.
 */
object LoudnessTags {

    fun read(path: String): LoudnessInfo? {
        val fields = try {
            val tag = AudioFileIO.read(File(path)).tag ?: return null
            val map = HashMap<String, String>()
            val it = tag.fields
            while (it.hasNext()) {
                val field = it.next()
                val (key, value) = keyValue(field) ?: continue
                map.putIfAbsent(key, value)
            }
            map
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        return fromFields(fields)
    }

    /** [fields] keys are lower case, e.g. "replaygain_track_gain". */
    internal fun fromFields(fields: Map<String, String>): LoudnessInfo? {
        parseDb(fields["replaygain_track_gain"])?.let { gain ->
            val peak = fields["replaygain_track_peak"]?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
            return LoudnessInfo(RG_REFERENCE - gain, peak, LoudnessInfo.Source.TAG)
        }
        fields["r128_track_gain"]?.trim()?.toIntOrNull()?.let { q78 ->
            return LoudnessInfo(R128_REFERENCE - q78 / 256.0, null, LoudnessInfo.Source.TAG)
        }
        return null
    }

    /** "-7.53 dB", "+1.2dB", "-7,53 dB" → the number. */
    internal fun parseDb(value: String?): Double? = value
        ?.trim()
        ?.removeSuffix("dB")?.removeSuffix("db")?.removeSuffix("DB")
        ?.trim()?.removePrefix("+")
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > -60 && it < 60 }

    private fun keyValue(field: TagField): Pair<String, String>? {
        if (field.isBinary) return null
        // ID3: user text frames carry the name in their description.
        if (field is AbstractID3v2Frame) {
            val body = field.body
            if (body is FrameBodyTXXX) {
                return body.description.lowercase(Locale.ROOT) to body.firstTextValue
            }
            return null
        }
        val value = (field as? TagTextField)?.content ?: return null
        // MP4 freeform atoms: "----:com.apple.iTunes:replaygain_track_gain".
        val key = field.id.substringAfterLast(':').lowercase(Locale.ROOT)
        return key to value
    }

    private const val RG_REFERENCE = -18.0
    private const val R128_REFERENCE = -23.0
}
