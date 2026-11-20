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

/**
 * A song's integrated loudness in LUFS and its sample peak (linear, 1.0 = full scale).
 * [peak] is null when only a tag without a peak value is known: such a song is never boosted.
 */
data class LoudnessInfo(val loudness: Double, val peak: Double?, val source: Source) {
    enum class Source { TAG, MEASURED }
}
