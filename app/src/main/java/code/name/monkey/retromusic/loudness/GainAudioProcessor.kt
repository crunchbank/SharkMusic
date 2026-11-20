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

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Applies a gain (cut or boost) to ExoPlayer's decoded audio for streaming mode. Changes made
 * while a song plays glide over about [GLIDE_SECONDS]; after a seek or a new song the gain
 * applies at once. Samples are clamped to full scale, though streaming mode never boosts a song
 * past its peak headroom.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var target = 1.0

    private var current = 1.0
    private var step = 0.0 // per-frame smoothing factor

    fun setGainDb(db: Double) {
        val linear = 10.0.pow(db / 20)
        if (linear != target) Log.d(TAG, "Gain %.2f dB".format(db))
        target = linear
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) throw UnhandledAudioFormatException(inputAudioFormat)
        step = 1.0 / (GLIDE_SECONDS * inputAudioFormat.sampleRate / 4)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val goal = target
        if (current == 1.0 && goal == 1.0) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val channels = inputAudioFormat.channelCount
        inputBuffer.order(ByteOrder.nativeOrder())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (inputBuffer.remaining() >= 4 * channels) {
                val g = advance(goal).toFloat()
                repeat(channels) {
                    out.putFloat((inputBuffer.getFloat() * g).coerceIn(-1f, 1f))
                }
            }
        } else {
            while (inputBuffer.remaining() >= 2 * channels) {
                val g = advance(goal)
                repeat(channels) {
                    val v = (inputBuffer.getShort() * g).toInt()
                    out.putShort(v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Moves the applied gain one frame closer to [goal] and returns it. */
    private fun advance(goal: Double): Double {
        if (current != goal) {
            current += (goal - current) * step
            if (abs(goal - current) < 1e-4) current = goal
        }
        return current
    }

    override fun onFlush() {
        current = target
    }

    override fun onReset() {
        current = target
    }

    companion object {
        private const val GLIDE_SECONDS = 0.5
        private const val TAG = "StreamingMode"
    }
}
