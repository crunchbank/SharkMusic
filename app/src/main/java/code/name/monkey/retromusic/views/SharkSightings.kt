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
package code.name.monkey.retromusic.views

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * Now and then, a shark fin surfaces in the now-playing progress line.
 *
 * While a song plays, after it has counted as played, a sighting starts every 30–50 seconds:
 * the line swells into ripples, a fin rises out of it, cruises (sinking halfway to turn), and in
 * every other sighting chases three tiny fish. After a catch it slows down and sinks without
 * turning while the other fish flee. Then the water settles.
 *
 * Everything is drawn in the window's overlay (the slider's own parent can be too short for the
 * fin), so no layout changes; the progress dot is left uncovered (the shark passes behind it). Nothing runs between sightings except one delayed
 * callback. Only for the round-dot slider style.
 */
class SharkSightings(
    private val slider: Slider,
    /** True while sightings may start: playing, past the played threshold, not seeking. */
    private val canStart: () -> Boolean,
    /** True while a running sighting may carry on; when it turns false the shark dives. */
    private val canContinue: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val drawable = SharkDrawable()
    private var animator: TimeAnimator? = null
    private var sim: Sim? = null
    private var sightings = 0
    private var resumed = false

    private val scheduled = Runnable {
        if (resumed && sim == null && canStart() && animationsEnabled()) startSighting()
        schedule()
    }

    fun resume() {
        if (resumed) return
        resumed = true
        schedule()
    }

    /** Stops everything at once (screen left). */
    fun pause() {
        resumed = false
        handler.removeCallbacks(scheduled)
        stopSighting()
    }

    /** Ends a running sighting early with a quick dive (seek, pause, song change). */
    fun dive() {
        sim?.dive(QUICK_DIVE)
    }

    private fun schedule() {
        handler.removeCallbacks(scheduled)
        if (resumed) handler.postDelayed(scheduled, Random.nextLong(MIN_GAP_MS, MAX_GAP_MS))
    }

    private fun startSighting() {
        val host = slider.rootView as? ViewGroup ?: return
        val width = trackWidthDp()
        if (width < 120f) return
        sightings++
        sim = Sim(width, chase = sightings % 2 == 0)
        drawable.setBounds(0, 0, host.width, host.height)
        host.overlay.add(drawable)
        animator = TimeAnimator().apply {
            setTimeListener { _, _, deltaMs ->
                val s = sim
                if (s == null) {
                    stopSighting(); return@setTimeListener
                }
                if (!canContinue()) s.dive(QUICK_DIVE)
                s.width = trackWidthDp()
                if (!s.update(min(deltaMs, 50L) / 1000f)) stopSighting()
                else host.invalidate()
            }
            start()
        }
    }

    private fun stopSighting() {
        animator?.cancel()
        animator = null
        sim = null
        (slider.rootView as? ViewGroup)?.let {
            it.overlay.remove(drawable)
            it.invalidate()
        }
    }

    private fun animationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(
            slider.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) != 0f

    // --- Geometry, read from the slider every frame so it follows layout and theme changes ---

    private val density get() = slider.resources.displayMetrics.density
    private val sliderLoc = IntArray(2)
    private val hostLoc = IntArray(2)

    /** Where the slider sits in the window overlay's coordinates; updated each frame. */
    private fun locateSlider() {
        slider.getLocationInWindow(sliderLoc)
        slider.rootView.getLocationInWindow(hostLoc)
    }
    private fun sliderX() = (sliderLoc[0] - hostLoc[0]).toFloat()
    private fun sliderY() = (sliderLoc[1] - hostLoc[1]).toFloat()
    private fun trackLeftPx() = sliderX() + slider.trackSidePadding
    private fun trackRightPx() = sliderX() + slider.width - slider.trackSidePadding
    private fun trackWidthDp() = (slider.width - 2f * slider.trackSidePadding) / density

    /** The animation state, in dp along the track. Pure maths: no drawing, no Android. */
    private class Sim(var width: Float, val chase: Boolean) {
        var t = 0f
        var end = if (chase) 10f else 8.5f
        var diveStart = end - 1.8f
        var diveDur = 0.7f
        var x = 22f + Random.nextFloat() * (width - 44f)
        var dir = if (Random.nextBoolean()) 1f else -1f
        var face = dir
        var speed = 22f
        var turnAt = 2.6f + Random.nextFloat() * 1.5f
        var turn: Turn? = null
        var eaten = false
        var splashX = 0f
        var splashT = -1f
        var clock = 0f
        val fish = ArrayList<Fish>(3)

        class Turn(val from: Float, val to: Float) { var t = 0f }
        class Fish(var x: Float, var vx: Float, var ph: Float) {
            var alive = true
            var scatter = false
        }

        val diving get() = t >= diveStart

        fun dive(dur: Float, delay: Float = 0f) {
            if (diving) return
            diveStart = t + delay
            diveDur = dur
            end = diveStart + dur + 1f
        }

        private fun startTurn(to: Float): Boolean {
            if (turn != null || to == dir || eaten || diving) return false
            turn = Turn(dir, to)
            return true
        }

        /** Advances by [dt] seconds; false once the sighting is over. */
        fun update(dt: Float): Boolean {
            clock += dt
            t += dt
            if (t > end) return false
            val lo = 22f
            val hi = width - 22f
            if (chase && t > 2.4f && fish.isEmpty() && !eaten && !diving) {
                val bx = x + dir * 55f
                repeat(3) { i ->
                    fish += Fish(bx + dir * i * 9f, dir * (30f + i * 6f), Random.nextFloat() * 6f)
                }
            }
            val target = fish.firstOrNull { it.alive }
            val tr = turn
            when {
                tr != null -> {
                    tr.t += dt
                    speed += (4f - speed) * min(1f, dt * 4f)
                    face = tr.from + (tr.to - tr.from) * ease((tr.t - SINK) / SWING)
                    if (tr.t >= SINK + SWING / 2f) dir = tr.to
                    if (tr.t >= SINK + SWING + UP) {
                        turn = null
                        face = dir
                        turnAt = t + 2.4f + Random.nextFloat() * 1.6f
                    }
                }
                eaten -> speed += (9f - speed) * min(1f, dt * 1.2f)
                target != null && !diving -> {
                    val want = sign(target.x - x).takeIf { it != 0f } ?: dir
                    if (!startTurn(want)) {
                        speed = min(70f, speed + dt * 30f)
                        if (abs(target.x - x) < 8f) {
                            target.alive = false
                            eaten = true
                            splashX = target.x
                            splashT = 0f
                            fish.forEach {
                                if (it.alive) {
                                    val away = sign(it.x - x).takeIf { s -> s != 0f } ?: dir
                                    it.vx = away * abs(it.vx)
                                    it.scatter = true
                                }
                            }
                            dive(2.2f, 0.9f)
                        }
                    }
                }
                else -> {
                    speed += ((if (diving) 14f else 26f) - speed) * min(1f, dt * 1.5f)
                    if (!diving && t > turnAt) startTurn(-dir)
                }
            }
            x += dir * speed * dt
            val locked = eaten || diving
            if (x < lo) {
                x = lo
                if (locked) speed = 0f else if (dir < 0 && turn == null) startTurn(1f)
            }
            if (x > hi) {
                x = hi
                if (locked) speed = 0f else if (dir > 0 && turn == null) startTurn(-1f)
            }
            fish.forEach {
                if (!it.alive) return@forEach
                it.ph += dt * if (it.scatter) 12f else 7f
                it.x += it.vx * dt * if (it.scatter) 3f else 1f
                if (it.x < -10f || it.x > width + 10f) it.alive = false
            }
            if (splashT >= 0f) splashT += dt
            return true
        }

        private fun sinkAmount(): Float {
            val tr = turn ?: return 0f
            return when {
                tr.t < SINK -> ease(tr.t / SINK)
                tr.t < SINK + SWING -> 1f
                else -> 1f - ease((tr.t - SINK - SWING) / UP)
            }
        }

        /** 0 = under water, 1 = fully surfaced. */
        fun rise(): Float {
            val base = min(ease((t - 1f) / 0.7f), 1f - ease((t - diveStart) / diveDur))
            return base * (1f - 0.5f * sinkAmount())
        }

        /** How far the water surface stands above the top of the line at [px] (dp). */
        fun lift(px: Float): Float {
            val a = 3f * min(ease(t / 1.2f), 1f - ease((t - (end - 1.3f)) / 1.3f))
            var h = a * (0.55f + 0.3f * sin(px * 0.22f - clock * 3f) +
                    0.15f * sin(px * 0.41f + clock * 1.7f))
            val d = px - x
            h += rise() * 1.8f * exp(-(d * d) / 150f)
            // Calm at the line's rounded ends.
            return max(0f, h) * ease(px / 8f) * ease((width - px) / 8f)
        }

        companion object {
            const val SINK = 0.4f
            const val SWING = 0.4f
            const val UP = 0.4f
            fun ease(v: Float): Float {
                val c = v.coerceIn(0f, 1f)
                return c * c * (3f - 2f * c)
            }
        }
    }

    /** Draws the current [sim] over the slider. Reuses its paints and paths: no per-frame allocation. */
    private inner class SharkDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val path = Path()
        private val thumbHole = Path()

        override fun draw(canvas: Canvas) {
            val s = sim ?: return
            val d = density
            locateSlider()
            val left = trackLeftPx()
            val right = trackRightPx()
            val cy = sliderY() + slider.height / 2f
            val lineTop = cy - slider.trackHeight / 2f
            val state = slider.drawableState
            val active = slider.trackActiveTintList.getColorForState(state, 0)
            val inactive = slider.trackInactiveTintList.getColorForState(state, 0)
            val finColor = MaterialColors.getColor(slider, android.R.attr.textColorSecondary)
            val span = slider.valueTo - slider.valueFrom
            val fraction = if (span > 0f) ((slider.value - slider.valueFrom) / span).coerceIn(0f, 1f) else 0f
            val thumbX = left + (right - left) * fraction
            val toPx = { dp: Float -> left + dp * d }
            val surface = { px: Float -> lineTop - s.lift((px - left) / d) * d }

            canvas.save()
            // Keep the progress dot clear: everything passes behind it.
            thumbHole.reset()
            thumbHole.addCircle(thumbX, cy, slider.thumbRadius + d, Path.Direction.CW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipOutPath(thumbHole)
            else @Suppress("DEPRECATION") canvas.clipPath(thumbHole, Region.Op.DIFFERENCE)

            // Fin first, clipped to above the water, so the ripples cover where it meets them.
            val r = s.rise()
            if (r > 0.01f) {
                val fx = toPx(s.x)
                canvas.save()
                path.reset()
                path.moveTo(fx - 16 * d, 0f)
                path.lineTo(fx + 16 * d, 0f)
                var px = fx + 16 * d
                while (px >= fx - 16 * d) {
                    path.lineTo(px, surface(px) + d)
                    px -= d
                }
                path.close()
                canvas.clipPath(path)
                val bob = sin(s.clock * 3f) * 0.6f
                canvas.translate(fx, surface(fx) + (3f + (1f - r) * 26f + bob) * d)
                canvas.scale(s.face * d, d)
                path.reset()
                path.moveTo(12f, 12f)
                path.lineTo(12f, 0f)
                path.quadTo(9.5f, -15.5f, -5f, -23f)
                path.quadTo(-4f, -10f, -10.5f, 0f)
                path.lineTo(-10.5f, 12f)
                path.close()
                paint.color = finColor
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            // The water: from the top of the line up to the rippling surface, in the line's colours.
            // The opaque played part tucks a pixel into the line so no hairline shows between them;
            // the translucent rest can't overlap without darkening, and its seam doesn't show.
            drawWater(canvas, left, thumbX, lineTop + 1f, surface, active)
            drawWater(canvas, thumbX, right, lineTop, surface, inactive)

            // Fish swim just above the surface.
            paint.color = active
            s.fish.forEach { f ->
                if (!f.alive) return@forEach
                val fx = toPx(f.x)
                val fy = surface(fx) - (4f - sin(f.ph) * 1.4f) * d
                canvas.save()
                canvas.translate(fx, fy)
                canvas.scale(sign(f.vx) * d, d)
                canvas.drawOval(-3.6f, -1.9f, 3.6f, 1.9f, paint)
                path.reset()
                path.moveTo(-3f, 0f)
                path.lineTo(-6f, -2.2f)
                path.lineTo(-6f, 2.2f)
                path.close()
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            // The gulp.
            if (s.splashT in 0f..0.7f) {
                val k = s.splashT / 0.7f
                val sx = toPx(s.splashX)
                val sy = surface(sx)
                stroke.color = active
                stroke.alpha = ((1f - k) * 255).toInt()
                stroke.strokeWidth = 1.5f * d
                for (i in -1..1) {
                    canvas.drawLine(
                        sx + i * 4 * d, sy - 2 * d,
                        sx + i * (4 + k * 5) * d, sy - (4 + k * 8) * d, stroke
                    )
                }
            }
            canvas.restore()
        }

        private fun drawWater(
            canvas: Canvas, x0: Float, x1: Float, lineTop: Float,
            surface: (Float) -> Float, color: Int
        ) {
            if (x1 <= x0) return
            val step = 1.5f * density
            path.reset()
            path.moveTo(x0, lineTop)
            var x = x0
            while (x < x1) {
                path.lineTo(x, surface(x))
                x += step
            }
            path.lineTo(x1, surface(x1))
            path.lineTo(x1, lineTop)
            path.close()
            paint.color = color
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val MIN_GAP_MS = 30_000L
        private const val MAX_GAP_MS = 50_000L
        private const val QUICK_DIVE = 0.7f
    }
}
