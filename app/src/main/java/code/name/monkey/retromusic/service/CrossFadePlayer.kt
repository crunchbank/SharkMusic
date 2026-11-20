/*
 * Modifications copyright (c) 2026 crunchbank (Shark Music): streaming mode levels each song's
 * loudness: each player's volume turns loud songs down and a LoudnessEnhancer turns quiet ones up,
 * and crossfades keep both songs at their own level. Licensed under the GNU General Public License v3.
 */
package code.name.monkey.retromusic.service

import android.animation.Animator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.PowerManager
import androidx.core.net.toUri
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.loudness.StreamingMode
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.AudioFader.Companion.createFadeAnimator
import code.name.monkey.retromusic.service.playback.Playback.PlaybackCallbacks
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.PreferenceUtil.playbackPitch
import code.name.monkey.retromusic.util.PreferenceUtil.playbackSpeed
import code.name.monkey.retromusic.util.logE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** @author Prathamesh M */

/*
* To make Crossfade work we need two MediaPlayer's
* Basically, we switch back and forth between those two mp's
* e.g. When song is about to end (Reaches Crossfade duration) we let current mediaplayer
* play but with decreasing volume and start the player with the next song with increasing volume
* and vice versa for upcoming song and so on.
*/
class CrossFadePlayer(context: Context) : AudioManagerPlayback(context),
    MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener {

    private var currentPlayer: CurrentPlayer = CurrentPlayer.NOT_SET
    private var player1 = MediaPlayer()
    private var player2 = MediaPlayer()
    private var durationListener = DurationListener()
    private var mIsInitialized = false
    private var hasDataSource: Boolean = false /* Whether first player has DataSource */
    private var nextDataSource: String? = null
    private var crossFadeAnimator: Animator? = null
    override var callbacks: PlaybackCallbacks? = null
    private var crossFadeDuration = PreferenceUtil.crossFadeDuration
    var isCrossFading = false

    /** The song each player holds, for streaming mode's gain. */
    private val songs = HashMap<MediaPlayer, Song?>()
    private val enhancers = HashMap<Int, LoudnessEnhancer>()
    private var baseVolume = 1f
    private val streamingModeListener = {
        if (!isCrossFading) getCurrentPlayer()?.let { applyGain(it, baseVolume) }
    }

    init {
        player1.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        player2.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        currentPlayer = CurrentPlayer.PLAYER_ONE
        StreamingMode.addListener(streamingModeListener)
    }

    /** Streaming mode's gain for [mp]'s song as a volume factor (cuts only; boosts go to its enhancer). */
    private fun cutFactor(mp: MediaPlayer): Float {
        val gain = StreamingMode.gainDb(songs[mp])
        setBoost(mp, max(gain, 0.0))
        return 10.0.pow(min(gain, 0.0) / 20).toFloat()
    }

    private fun applyGain(mp: MediaPlayer, volume: Float) {
        try {
            val v = volume * cutFactor(mp)
            mp.setVolume(v, v)
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    private fun setBoost(mp: MediaPlayer, db: Double) {
        try {
            val session = mp.audioSessionId
            val enhancer = enhancers[session]
                ?: if (db > 0) LoudnessEnhancer(session).also { enhancers[session] = it } else return
            if (db > 0) enhancer.setTargetGain((db * 100).roundToInt())
            enhancer.enabled = db > 0
        } catch (e: Exception) {
            // Effects can be unavailable (e.g. another app holds the session); play unboosted.
            e.printStackTrace()
        }
    }

    override fun start(): Boolean {
        super.start()
        durationListener.start()
        resumeFade()
        return try {
            getCurrentPlayer()?.start()
            if (isCrossFading) {
                getNextPlayer()?.start()
            }
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override fun release() {
        StreamingMode.removeListener(streamingModeListener)
        stop()
        cancelFade()
        getCurrentPlayer()?.release()
        getNextPlayer()?.release()
        durationListener.cancel()
        enhancers.values.forEach { it.release() }
        enhancers.clear()
    }

    override fun stop() {
        super.stop()
        getCurrentPlayer()?.reset()
        mIsInitialized = false
    }

    override fun pause(): Boolean {
        super.pause()
        durationListener.stop()
        pauseFade()
        getCurrentPlayer()?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        getNextPlayer()?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        return true
    }

    override fun seek(whereto: Int, force: Boolean): Int {
        if (force) {
            endFade()
        }
        getNextPlayer()?.stop()
        return try {
            getCurrentPlayer()?.seekTo(whereto)
            whereto
        } catch (e: java.lang.IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    override fun setVolume(vol: Float): Boolean {
        cancelFade()
        baseVolume = vol
        return try {
            getCurrentPlayer()?.let { applyGain(it, vol) }
            true
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override val isInitialized: Boolean
        get() = mIsInitialized

    override val isPlaying: Boolean
        get() = mIsInitialized && getCurrentPlayer()?.isPlaying == true

    override fun setDataSource(
        song: Song,
        force: Boolean,
        completion: (success: Boolean) -> Unit,
    ) {
        if (force) hasDataSource = false
        mIsInitialized = false
        getCurrentPlayer()?.let { songs[it] = song }
        /* We've already set DataSource if initialized is true in setNextDataSource */
        if (!hasDataSource) {
            getCurrentPlayer()?.let {
                setDataSourceImpl(it, song.uri.toString()) { success ->
                    mIsInitialized = success
                    if (success && !isCrossFading) applyGain(it, baseVolume)
                    completion(success)
                }
            }
            hasDataSource = true
        } else {
            completion(true)
            mIsInitialized = true
        }
    }

    override fun setNextDataSource(path: Uri?) {
        // Store the next song path in nextDataSource, we'll need this just in case
        // if the user closes the app, then we can't get the nextSong from musicService
        // As MusicPlayerRemote won't have access to the musicService
        nextDataSource = path.toString()
    }

    override fun setAudioSessionId(sessionId: Int): Boolean {
        return try {
            getCurrentPlayer()?.audioSessionId = sessionId
            true
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }

    override val audioSessionId: Int
        get() = getCurrentPlayer()?.audioSessionId!!

    /**
     * Gets the duration of the file.
     *
     * @return The duration in milliseconds
     */
    override fun duration(): Int {
        return if (!mIsInitialized) {
            -1
        } else try {
            getCurrentPlayer()?.duration!!
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    /**
     * Gets the current position in audio.
     * @return The position in milliseconds
     */
    override fun position(): Int {
        return if (!mIsInitialized) {
            -1
        } else try {
            getCurrentPlayer()?.currentPosition!!
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            -1
        }
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (mp == getCurrentPlayer()) {
            callbacks?.onTrackEnded()
        }
    }

    private fun getCurrentPlayer(): MediaPlayer? {
        return when (currentPlayer) {
            CurrentPlayer.PLAYER_ONE -> {
                player1
            }

            CurrentPlayer.PLAYER_TWO -> {
                player2
            }

            CurrentPlayer.NOT_SET -> {
                null
            }
        }
    }

    private fun getNextPlayer(): MediaPlayer? {
        return when (currentPlayer) {
            CurrentPlayer.PLAYER_ONE -> {
                player2
            }

            CurrentPlayer.PLAYER_TWO -> {
                player1
            }

            CurrentPlayer.NOT_SET -> {
                null
            }
        }
    }

    private fun crossFade(fadeInMp: MediaPlayer, fadeOutMp: MediaPlayer) {
        isCrossFading = true
        crossFadeAnimator = createFadeAnimator(
            context, fadeInMp, fadeOutMp, cutFactor(fadeInMp), cutFactor(fadeOutMp)
        ) {
            crossFadeAnimator = null
            durationListener.start()
            isCrossFading = false
        }
        crossFadeAnimator?.start()
    }

    private fun endFade() {
        crossFadeAnimator?.end()
        crossFadeAnimator = null
    }

    private fun cancelFade() {
        crossFadeAnimator?.cancel()
        crossFadeAnimator = null
    }

    private fun pauseFade() {
        crossFadeAnimator?.pause()
    }

    private fun resumeFade() {
        if (crossFadeAnimator?.isPaused == true) {
            crossFadeAnimator?.resume()
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        mIsInitialized = false
        mp?.release()
        player1 = MediaPlayer()
        player2 = MediaPlayer()
        mIsInitialized = true
        mp?.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        context.showToast(R.string.unplayable_file)
        logE(what.toString() + extra)
        return false
    }

    enum class CurrentPlayer {
        PLAYER_ONE,
        PLAYER_TWO,
        NOT_SET
    }

    inner class DurationListener : CoroutineScope by crossFadeScope() {

        private var job: Job? = null

        fun start() {
            job?.cancel()
            job = launch {
                while (isActive) {
                    delay(250)
                    onDurationUpdated(position(), duration())
                }
            }
        }

        fun stop() {
            job?.cancel()
        }
    }

    fun onDurationUpdated(progress: Int, total: Int) {
        if (total > 0 && (total - progress).div(1000) == crossFadeDuration) {
            getNextPlayer()?.let { player ->
                val nextSong = MusicPlayerRemote.nextSong
                // Switch to other player (Crossfade) only if next song exists
                // If we get an empty song it's can be because the app was cleared from background
                // And MusicPlayerRemote don't have access to MusicService
                if (nextSong != null && nextSong != Song.emptySong) {
                    nextDataSource = null
                    songs[player] = nextSong
                    setDataSourceImpl(player, nextSong.uri.toString()) { success ->
                        if (success) switchPlayer()
                    }

                }
                // So we have to use the previously stored nextDataSource value
                else if (!nextDataSource.isNullOrEmpty()) {
                    songs[player] = null
                    setDataSourceImpl(player, nextDataSource!!) { success ->
                        if (success) switchPlayer()
                        nextDataSource = null
                    }
                }
            }
        }
    }

    private fun switchPlayer() {
        getNextPlayer()?.start()
        crossFade(getNextPlayer()!!, getCurrentPlayer()!!)
        currentPlayer =
            if (currentPlayer == CurrentPlayer.PLAYER_ONE || currentPlayer == CurrentPlayer.NOT_SET) {
                CurrentPlayer.PLAYER_TWO
            } else {
                CurrentPlayer.PLAYER_ONE
            }
        callbacks?.onTrackEndedWithCrossfade()
    }

    override fun setCrossFadeDuration(duration: Int) {
        crossFadeDuration = duration
    }

    override fun setPlaybackSpeedPitch(speed: Float, pitch: Float) {
        getCurrentPlayer()?.setPlaybackSpeedPitch(speed, pitch)
        if (getNextPlayer()?.isPlaying == true) {
            getNextPlayer()?.setPlaybackSpeedPitch(speed, pitch)
        }
    }

    private fun setDataSourceImpl(
        player: MediaPlayer,
        path: String,
        completion: (success: Boolean) -> Unit,
    ) {
        player.reset()
        try {
            if (path.startsWith("content://")) {
                player.setDataSource(context, path.toUri())
            } else {
                player.setDataSource(path)
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.playbackParams =
                PlaybackParams().setSpeed(playbackSpeed).setPitch(playbackPitch)

            player.setOnPreparedListener {
                player.setOnPreparedListener(null)
                completion(true)
            }
            player.prepare()
        } catch (e: Exception) {
            completion(false)
            e.printStackTrace()
        }
        player.setOnCompletionListener(this)
        player.setOnErrorListener(this)
    }

    companion object {
        val TAG: String = CrossFadePlayer::class.java.simpleName
    }
}

internal fun crossFadeScope(): CoroutineScope = CoroutineScope(Job() + Dispatchers.Default)

fun MediaPlayer.setPlaybackSpeedPitch(speed: Float, pitch: Float) {
    val wasPlaying = isPlaying
    playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch)
    if (!wasPlaying) {
        pause()
    }
}