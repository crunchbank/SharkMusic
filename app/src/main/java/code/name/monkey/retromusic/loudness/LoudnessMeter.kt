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
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Measures a song's integrated loudness (ITU-R BS.1770-4 with EBU R128 gating) and its sample
 * peak, by decoding it with the platform decoders.
 */
object LoudnessMeter {

    /**
     * Returns the measurement, or null if the file can't be decoded or holds less than one
     * 400 ms block of audio. [isCancelled] is polled between buffers.
     */
    fun measure(context: Context, uri: Uri, isCancelled: () -> Boolean = { false }): LoudnessInfo? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var analyser: Analyser? = null
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var outputDone = false
            var idlePolls = 0 // polls in a row that produced nothing: a stalled decoder gives up
            while (!outputDone) {
                if (isCancelled() || idlePolls > MAX_IDLE_POLLS) return null
                // Give the decoder every input buffer it has free.
                var fed = false
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    fed = true
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // Take every output buffer that's ready; wait only when there was nothing to do.
                var waitUs = if (fed) 0L else TIMEOUT_US
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, waitUs)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!fed) idlePolls++
                        break
                    }
                    idlePolls = 0
                    waitUs = 0
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = codec.outputFormat
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT &&
                            encoding != AudioFormat.ENCODING_PCM_FLOAT
                        ) return null
                        analyser = Analyser(
                            out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        )
                        continue
                    }
                    if (outIndex < 0) continue
                    if (info.size > 0) {
                        val a = analyser ?: Analyser(
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        ).also { analyser = it }
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buffer.asFloatBuffer()
                            val n = fb.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            fb.get(floats, 0, n)
                            a.addFloats(floats, n)
                        } else {
                            val sb = buffer.asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            a.addShorts(shorts, n)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        break
                    }
                }
            }
            return analyser?.result()
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_POLLS = 500 // 5 s

    /** Two-stage K-weighting filter per channel, 100 ms sub-blocks, gated integration. */
    internal class Analyser(sampleRate: Int, private val channels: Int) {
        private val shelf = Biquad.highShelf(sampleRate.toDouble())
        private val highPass = Biquad.highPass(sampleRate.toDouble())
        private val state = Array(channels) { DoubleArray(8) }
        private val weights = DoubleArray(channels) { channelWeight(it, channels) }
        private val subBlockFrames = sampleRate / 10
        private val subBlockSum = DoubleArray(channels)
        private var subBlockCount = 0
        private val subBlocks = ArrayList<Double>() // weighted mean square of each 100 ms
        private var channel = 0
        var peak = 0.0
            private set

        fun addShorts(samples: ShortArray, count: Int) {
            for (i in 0 until count) add(samples[i] * (1.0 / 32768))
        }

        fun addFloats(samples: FloatArray, count: Int) {
            for (i in 0 until count) add(samples[i].toDouble())
        }

        @Suppress("NOTHING_TO_INLINE")
        private inline fun add(sample: Double) {
            val a = abs(sample)
            if (a > peak) peak = a
            val s = state[channel]
            val y1 = shelf.process(sample, s, 0)
            val y2 = highPass.process(y1, s, 4)
            subBlockSum[channel] += y2 * y2
            channel++
            if (channel == channels) {
                channel = 0
                subBlockCount++
                if (subBlockCount == subBlockFrames) {
                    var power = 0.0
                    for (c in 0 until channels) {
                        power += weights[c] * subBlockSum[c] / subBlockFrames
                        subBlockSum[c] = 0.0
                    }
                    subBlocks += power
                    subBlockCount = 0
                }
            }
        }

        fun result(): LoudnessInfo? {
            // 400 ms blocks with 75% overlap: every run of four consecutive 100 ms sub-blocks.
            if (subBlocks.size < 4) return null
            val blocks = DoubleArray(subBlocks.size - 3) { i ->
                (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4
            }
            val absoluteGated = blocks.filter { loudness(it) > ABSOLUTE_GATE }
            if (absoluteGated.isEmpty()) return null
            val relativeGate = loudness(absoluteGated.average()) - RELATIVE_GATE
            val gated = absoluteGated.filter { loudness(it) > relativeGate }
            if (gated.isEmpty()) return null
            return LoudnessInfo(loudness(gated.average()), peak, LoudnessInfo.Source.MEASURED)
        }

        private fun loudness(power: Double) = -0.691 + 10 * log10(max(power, 1e-20))

        companion object {
            private const val ABSOLUTE_GATE = -70.0
            private const val RELATIVE_GATE = 10.0

            /**
             * BS.1770 weights: 1.0 for front channels, 1.41 for surrounds, 0 for LFE (5.1 layout).
             * A mono file counts as if played on two speakers, as the listener hears it.
             */
            fun channelWeight(index: Int, channels: Int): Double = when {
                channels == 1 -> 2.0
                channels == 6 && index == 3 -> 0.0
                channels == 6 && index >= 4 -> 1.41
                else -> 1.0
            }
        }
    }

    /** Direct form I biquad; the per-channel state holds x1, x2, y1, y2 at [offset]. */
    internal class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        fun process(x: Double, s: DoubleArray, offset: Int): Double {
            val y = b0 * x + b1 * s[offset] + b2 * s[offset + 1] - a1 * s[offset + 2] - a2 * s[offset + 3]
            s[offset + 1] = s[offset]
            s[offset] = x
            s[offset + 3] = s[offset + 2]
            s[offset + 2] = y
            return y
        }

        companion object {
            // K-weighting parameters valid at any sample rate (as in libebur128).
            fun highShelf(rate: Double): Biquad {
                val f0 = 1681.974450955533
                val g = 3.999843853973347
                val q = 0.7071752369554196
                val k = tan(PI * f0 / rate)
                val vh = 10.0.pow(g / 20)
                val vb = vh.pow(0.4996667741545416)
                val a0 = 1 + k / q + k * k
                return Biquad(
                    (vh + vb * k / q + k * k) / a0,
                    2 * (k * k - vh) / a0,
                    (vh - vb * k / q + k * k) / a0,
                    2 * (k * k - 1) / a0,
                    (1 - k / q + k * k) / a0,
                )
            }

            fun highPass(rate: Double): Biquad {
                val f0 = 38.13547087602444
                val q = 0.5003270373238773
                val k = tan(PI * f0 / rate)
                val a0 = 1 + k / q + k * k
                return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
            }
        }
    }
}
