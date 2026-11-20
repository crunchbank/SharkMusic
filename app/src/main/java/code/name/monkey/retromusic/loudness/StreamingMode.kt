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

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.model.Song
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Streaming mode: plays every song at the same loudness, by default -14 LUFS.
 *
 * Loud songs are turned down to the target. Quiet songs are turned up only as far as their peaks
 * allow (keeping [HEADROOM_DB] below full scale), so nothing is limited or clipped. Loudness comes
 * from ReplayGain/R128 tags when a file has them, otherwise it is measured on the device, one song
 * at a time, while streaming mode is on: the playing song first, then the next few in the queue.
 * A song that starts playing unmeasured interrupts the measurement of one further down the queue.
 *
 * Players ask [gainDb] for the song they are about to play and register a listener to hear when
 * the answer changes (the mode is toggled, the target changes or a measurement arrives).
 */
object StreamingMode {

    const val PREF_ENABLED = "streaming_mode"
    const val PREF_TARGET = "streaming_mode_target"
    const val DEFAULT_TARGET = -14.0

    /** Peaks are kept at least this far below full scale when boosting. */
    const val HEADROOM_DB = 1.0

    private const val TAG = "StreamingMode"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: LoudnessStore

    private sealed class Known {
        class Info(val info: LoudnessInfo, val final: Boolean) : Known()
        object Unmeasurable : Known()
    }

    private val known = ConcurrentHashMap<String, Known>()
    private val queue = LinkedBlockingDeque<Song>()
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabledCache = false

    /** The song being measured, and whether an urgent request for another song should stop it. */
    @Volatile
    private var measuring: String? = null

    @Volatile
    private var interrupted = false

    @Volatile
    private var targetCache = DEFAULT_TARGET

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            PREF_ENABLED -> {
                enabledCache = p.getBoolean(PREF_ENABLED, false)
                notifyListeners()
            }
            PREF_TARGET -> {
                targetCache = readTarget(p)
                notifyListeners()
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        store = LoudnessStore(appContext)
        enabledCache = prefs.getBoolean(PREF_ENABLED, false)
        targetCache = readTarget(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        thread(name = "StreamingModeMeter", isDaemon = true, priority = Thread.MIN_PRIORITY) { worker() }
    }

    var enabled: Boolean
        get() = enabledCache
        set(value) = prefs.edit { putBoolean(PREF_ENABLED, value) }

    val target: Double get() = targetCache

    private fun readTarget(p: SharedPreferences): Double =
        p.getString(PREF_TARGET, null)?.toDoubleOrNull() ?: DEFAULT_TARGET

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it() } }
    }

    /**
     * The gain in dB to play [song] at: 0 when streaming mode is off or the song's loudness isn't
     * known yet (it is then measured, and listeners are told when it is).
     */
    fun gainDb(song: Song?): Double {
        if (!enabledCache || song == null || song.id == -1L || song.data.isEmpty()) return 0.0
        return when (val k = lookup(song)) {
            is Known.Info -> {
                if (!k.final) request(song, urgent = true)
                gainFor(k.info, targetCache)
            }
            Known.Unmeasurable -> 0.0
            null -> {
                request(song, urgent = true)
                0.0
            }
        }
    }

    /** Measures, in the background, the songs about to play that aren't known yet. */
    fun prefetch(songs: List<Song>) {
        if (!enabledCache) return
        songs.forEach { song ->
            if (song.id != -1L && song.data.isNotEmpty()) {
                val k = lookup(song)
                if (k == null || (k is Known.Info && !k.final)) request(song, urgent = false)
            }
        }
    }

    internal fun gainFor(info: LoudnessInfo, target: Double): Double {
        val gain = target - info.loudness
        if (gain <= 0) return gain
        val peak = info.peak ?: return 0.0
        val headroom = -20 * log10(max(peak, 1e-6)) - HEADROOM_DB
        return min(gain, max(0.0, headroom))
    }

    private fun key(song: Song) = "${song.data}\u0000${song.dateModified}"

    private fun lookup(song: Song): Known? {
        known[key(song)]?.let { return it }
        val entry = try {
            store.get(song.data, song.dateModified)
        } catch (e: Exception) {
            null
        }
        val k = when (entry) {
            is LoudnessStore.Entry.Known -> Known.Info(entry.info, final = true)
            LoudnessStore.Entry.Unmeasurable -> Known.Unmeasurable
            null -> return null
        }
        known[key(song)] = k
        return k
    }

    private fun request(song: Song, urgent: Boolean) {
        val k = key(song)
        if (urgent) {
            if (measuring == k) return // already under way
            // Move it to the front even if it's already waiting, and stop a measurement of a
            // song further down the queue: the playing song can't wait for it.
            queue.remove(song)
            queue.offerFirst(song)
            pending += k
            if (measuring != null) interrupted = true
        } else if (pending.add(k)) {
            queue.offerLast(song)
        }
    }

    private fun worker() {
        while (true) {
            val song = try {
                queue.takeFirst()
            } catch (e: InterruptedException) {
                return
            }
            val k = key(song)
            var requeued = false
            try {
                val current = known[k]
                if (current is Known.Unmeasurable || (current is Known.Info && current.final)) continue
                if (!enabledCache) continue

                // Tags first: a ReplayGain tag with a peak is all we need.
                val tagged = if (current == null) LoudnessTags.read(song.data) else null
                if (tagged != null) {
                    val final = tagged.peak != null
                    known[k] = Known.Info(tagged, final)
                    if (final) store.put(song.data, song.dateModified, tagged)
                    notifyListeners()
                    if (final) continue
                }

                val started = System.currentTimeMillis()
                interrupted = false
                measuring = k
                val measured = try {
                    LoudnessMeter.measure(appContext, song.uri) { !enabledCache || interrupted }
                } finally {
                    measuring = null
                }
                if (measured == null && interrupted && enabledCache) {
                    // Put back behind the song that interrupted it.
                    Log.d(TAG, "Interrupted ${song.title}")
                    queue.remove(song)
                    queue.offerLast(song)
                    requeued = true
                    continue
                }
                if (measured == null && !enabledCache) continue // cancelled; try again later
                Log.d(TAG, "Measured ${song.title} in ${System.currentTimeMillis() - started} ms: " +
                        "${measured?.loudness} LUFS, peak ${measured?.peak}")
                store.put(song.data, song.dateModified, measured)
                known[k] = if (measured != null) Known.Info(measured, final = true) else Known.Unmeasurable
                notifyListeners()
            } catch (e: Exception) {
                Log.w(TAG, "Can't get loudness of ${song.data}", e)
            } finally {
                if (!requeued) pending -= k
            }
        }
    }
}
