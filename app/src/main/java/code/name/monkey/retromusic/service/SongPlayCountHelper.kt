/*
 * Copyright (c) 2019 Hemanth Savarala.
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
 *
 * Modifications copyright (c) 2026 crunchbank (Shark Music): the timer restarts on a track change while playing.
 */

package code.name.monkey.retromusic.service

import code.name.monkey.retromusic.helper.StopWatch
import code.name.monkey.retromusic.model.Song

class SongPlayCountHelper {

    private val stopWatch = StopWatch()
    var song = Song.emptySong
        private set

    fun shouldBumpPlayCount(): Boolean {
        return song.duration * 0.5 < stopWatch.elapsedTime
    }

    /** How much of [song] has been heard so far, as a fraction of its length (0 if unknown). */
    fun listenedFraction(): Float =
        if (song.duration > 0) stopWatch.elapsedTime.toFloat() / song.duration else 0f

    /**
     * Starts timing [song]. [isPlaying] restarts the timer at once: a track change while playing
     * sends no new play-state event, so without it the next song would never be timed.
     */
    fun notifySongChanged(song: Song, isPlaying: Boolean) {
        synchronized(this) {
            stopWatch.reset()
            if (isPlaying) stopWatch.start()
            this.song = song
        }
    }

    fun notifyPlayStateChanged(isPlaying: Boolean) {
        synchronized(this) {
            if (isPlaying) {
                stopWatch.start()
            } else {
                stopWatch.pause()
            }
        }
    }

    companion object {
        val TAG: String = SongPlayCountHelper::class.java.simpleName
    }
}