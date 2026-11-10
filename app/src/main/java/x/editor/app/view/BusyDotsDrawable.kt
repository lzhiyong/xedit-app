/*
 * Copyright © 2026 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.editor.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A "busy" mark of dots, in a loop, made of the sub-animations of [BusyMotion]: what it
 * plays is its [spec] ([BusyDotsSpec]). By default ([BusyDotsSpec.DEFAULT], the toolchain
 * sheet's Update):
 *
 *  1. four dots at the corners of a square: the two of one diagonal swap places (straight
 *     across, through the middle), the other two staying still; then the two of the other
 *     diagonal ([BusyMotion.SquareSwap]);
 *  2. they run together into one dot, which splits into three at the corners of a
 *     triangle (one on top, two at the bottom), which all move on along its sides to the
 *     next corner, a corner a step, a short rest after each, once round
 *     ([BusyMotion.TriangleWalk]);
 *  3. they run together into one again, which splits into three in a row, which jump in
 *     turn, a wave, twice ([BusyMotion.RowWave]);
 *  4. they run together once more, and split into the four of the square: the loop again.
 *
 * Every move eases in and out; a dot that moves is a little smaller half way (as if going
 * behind), and the dot they run into is a little bigger, and swells as they meet. Drawn in
 * the tint it is given (a MaterialButton's iconTint), so it takes the button's colour; the
 * dots of a translucent tint are drawn in a layer of their own, so they do not darken
 * where they overlap.
 *
 * Anywhere a drawable goes: a button's icon or an image ([setBusyDots] puts it there and
 * back), a menu item's icon, a view's background. [start] and [stop] as any [Animatable];
 * while the view it is in is not shown ([setVisible] false) it pauses, and goes on where it
 * was when shown again. Stopped, it shows the first shape of its spec at rest.
 */
class BusyDotsDrawable @JvmOverloads constructor(
    private val sizePx: Int,
    spec: BusyDotsSpec = BusyDotsSpec.DEFAULT,
) : Drawable(), Animatable {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var tint: ColorStateList? = null
    private var color = 0xFF000000.toInt()
    private var drawableAlpha = 255

    private val ease = PathInterpolator(0.65f, 0f, 0.35f, 1f)

    // the dots at the time drawn: how many, where (in sides of the drawable, from its
    // centre) and how big
    private var count = 4
    private val xs = FloatArray(4)
    private val ys = FloatArray(4)
    private val rs = FloatArray(4)

    // the loop of the spec, part by part, and where each part ends (milliseconds)
    private var parts: List<Part> = timeline(spec)
    private var ends: LongArray = endsOf(parts)

    // where in the loop, in milliseconds
    private var time = 0f

    // started and not stopped (it may be paused meanwhile: not shown)
    private var wanted = false

    private val animator = ValueAnimator.ofFloat(0f, ends.last().toFloat()).apply {
        duration = ends.last()
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            time = it.animatedValue as Float
            invalidateSelf()
        }
    }

    /**
     * What it plays. Set while it goes round, the new loop starts from its beginning; set to
     * one equal to it, nothing changes.
     */
    var spec: BusyDotsSpec = spec
        set(value) {
            if (value == field) return
            field = value
            parts = timeline(value)
            ends = endsOf(parts)
            animator.cancel()
            animator.setFloatValues(0f, ends.last().toFloat())
            animator.duration = ends.last()
            time = 0f
            if (wanted && isVisible) animator.start()
            invalidateSelf()
        }

    /** how long a loop of [spec] takes, in milliseconds */
    val loopMillis: Long get() = ends.last()

    // #region the loop

    private enum class Move { TURN, MERGE, SPLIT, BOUNCE }
    // [diagonal]: of a turn of the square, which diagonal swaps (0: top left and bottom
    // right, 1: top right and bottom left); [waves]: of a bounce, how many
    private class Part(val move: Move, val shape: BusyShape, val millis: Long, val diagonal: Int = 0, val waves: Int = 1)

    // where every dot is at [time]
    private fun layOut() {
        var index = ends.indexOfFirst { time < it }
        if (index < 0) index = parts.lastIndex
        val part = parts[index]
        val start = if (index == 0) 0L else ends[index - 1]
        val t = ((time - start) / part.millis).coerceIn(0f, 1f)
        when (part.move) {
            Move.TURN -> if (part.shape == BusyShape.SQUARE) swap(part.diagonal, t) else step(t)
            Move.MERGE -> merge(part.shape, t)
            Move.SPLIT -> split(part.shape, t)
            Move.BOUNCE -> bounce(t, part.waves)
        }
    }

    // The two dots of a [diagonal] of the square swapping places, straight across through
    // the middle, then resting there; the other two staying still
    private fun swap(diagonal: Int, t: Float) {
        val u = ease.getInterpolation((t / SWAP_PART).coerceIn(0f, 1f))
        rest(BusyShape.SQUARE)
        // the dots of a diagonal: 0 and 2 (bottom right, top left), 1 and 3 (bottom left, top right)
        for (i in intArrayOf(diagonal, diagonal + 2)) {
            // to the opposite corner: the place turned round the middle
            xs[i] *= 1f - 2f * u
            ys[i] *= 1f - 2f * u
            rs[i] = DOT * (1f - 0.2f * sin(PI.toFloat() * u))
        }
    }

    // The three dots of the triangle each moving on along a side to the next corner (top,
    // bottom right, bottom left), then resting there
    private fun step(t: Float) {
        val u = ease.getInterpolation((t / STEP_PART).coerceIn(0f, 1f))
        count = 3
        // a little smaller half way, as if going round behind
        val r = DOT * (1f - 0.18f * (1f - abs(2 * u - 1)))
        for (i in 0 until 3) {
            val to = (i + 1) % 3
            xs[i] = TRIANGLE_X[i] + (TRIANGLE_X[to] - TRIANGLE_X[i]) * u
            ys[i] = TRIANGLE_Y[i] + (TRIANGLE_Y[to] - TRIANGLE_Y[i]) * u
            rs[i] = r
        }
    }

    // The dots of [shape] running together into one bigger dot, then that dot swelling a
    // little as they meet and settling
    private fun merge(shape: BusyShape, t: Float) {
        val u = ease.getInterpolation((t / MERGE_PART).coerceIn(0f, 1f))
        rest(shape)
        for (i in 0 until count) {
            xs[i] *= 1f - u
            ys[i] *= 1f - u
            rs[i] = DOT + (MERGED - DOT) * u
        }
        if (t > MERGE_PART) {
            // the swell: up and back, over the rest of the part
            val v = (t - MERGE_PART) / (1f - MERGE_PART)
            val swell = 1f + 0.12f * sin(PI.toFloat() * v)
            for (i in 0 until count) rs[i] = MERGED * swell
        }
    }

    // One dot splitting into the dots of [shape], which go out to their places
    private fun split(shape: BusyShape, t: Float) {
        val u = ease.getInterpolation(t)
        rest(shape)
        for (i in 0 until count) {
            xs[i] *= u
            ys[i] *= u
            rs[i] = MERGED + (DOT - MERGED) * u
        }
    }

    // Three in a row, each jumping in turn (a wave), [waves] times
    private fun bounce(t: Float, waves: Int) {
        rest(BusyShape.ROW)
        val wave = (t * waves) % 1f
        for (i in 0 until count) {
            // a dot's jump: its part of the wave, a little after the one before it
            val u = (wave - i * JUMP_DELAY) / JUMP_PART
            if (u in 0f..1f) {
                // up and down as a thrown thing goes (a parabola)
                ys[i] -= JUMP * 4f * u * (1f - u)
            }
            rs[i] = DOT
        }
    }

    // the dots of [shape] at rest
    private fun rest(shape: BusyShape) {
        count = shape.dots
        when (shape) {
            BusyShape.SQUARE -> for (i in 0 until 4) {
                // bottom right, bottom left, top left, top right
                val a = (PI / 4 + i * PI / 2).toFloat()
                xs[i] = SQUARE * cos(a)
                ys[i] = SQUARE * sin(a)
            }
            BusyShape.TRIANGLE -> for (i in 0 until 3) {
                xs[i] = TRIANGLE_X[i]
                ys[i] = TRIANGLE_Y[i]
            }
            BusyShape.ROW -> for (i in 0 until 3) {
                xs[i] = (i - 1) * ROW_GAP
                ys[i] = ROW_Y
            }
        }
        for (i in 0 until count) rs[i] = DOT
    }

    // #endregion

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        layOut()
        val side = min(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val alpha = Color.alpha(color) * drawableAlpha / 255
        paint.color = color or 0xFF000000.toInt()
        // translucent: in a layer, so that dots over each other are not darker
        val layer = if (alpha < 255) canvas.saveLayerAlpha(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), alpha) else -1
        for (i in 0 until count) canvas.drawCircle(cx + xs[i] * side, cy + ys[i] * side, rs[i] * side, paint)
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        updateColor()
    }

    override fun isStateful() = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean = updateColor()

    private fun updateColor(): Boolean {
        val next = tint?.getColorForState(state, tint!!.defaultColor) ?: color
        if (next == color) return false
        color = next
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = drawableAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // the view it is in shown or not (a TextView's, an ImageView's drawables are told):
    // not shown, it pauses; shown again, it goes on where it was
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (wanted) {
            if (!visible) animator.pause() else resumeOrStart()
        }
        return changed
    }

    override fun start() {
        wanted = true
        if (isVisible) resumeOrStart()
    }

    private fun resumeOrStart() {
        if (animator.isPaused) animator.resume() else if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        wanted = false
        animator.cancel()
        time = 0f
        invalidateSelf()
    }

    override fun isRunning() = wanted

    companion object {
        /** the size of a button's icon */
        const val DEFAULT_SIZE_DP = 18f

        /** a drawable of [sizeDp] (its intrinsic size), playing [spec] */
        @JvmStatic
        @JvmOverloads
        fun ofDp(context: Context, sizeDp: Float = DEFAULT_SIZE_DP, spec: BusyDotsSpec = BusyDotsSpec.DEFAULT) =
            BusyDotsDrawable((sizeDp * context.resources.displayMetrics.density).roundToInt(), spec)

        // sizes and places, in sides of the drawable (from its centre): a dot, the dot they
        // run into, how far the square's dots are from the centre, the
        // row's dots from each other, where the row is (a little low: they jump up), and
        // how high a dot jumps
        private const val DOT = 0.11f
        private const val MERGED = 0.18f
        private const val SQUARE = 0.32f
        private const val ROW_GAP = 0.29f
        private const val ROW_Y = 0.12f
        private const val JUMP = 0.30f

        // the triangle's corners: top, bottom right, bottom left (the order the dots go round in)
        private val TRIANGLE_X = floatArrayOf(0f, 0.322f, -0.322f)
        private val TRIANGLE_Y = floatArrayOf(-0.298f, 0.263f, 0.263f)

        // the part of a swap and of a step that moves (then it rests), of a merge that runs
        // together (then the dot swells)
        private const val SWAP_PART = 0.78f
        private const val STEP_PART = 0.7f
        private const val MERGE_PART = 0.68f

        // a jump of the wave (a part of a wave), and how long after the one before it a dot jumps
        private const val JUMP_PART = 0.5f
        private const val JUMP_DELAY = 0.16f

        // milliseconds, at speed 1
        private const val SWAP = 620L
        private const val STEP = 520L
        private const val MERGE = 420L
        private const val SPLIT = 300L
        private const val WAVE = 620L

        // The parts of a loop of [spec]: each motion, and after it, if the next one (the
        // first, after the last) is of another shape and the spec morphs, the dots running
        // together and splitting into that shape. A pulse that the morph follows splits
        // straight into the next shape (not back into its own and together again).
        private fun timeline(spec: BusyDotsSpec): List<Part> {
            fun ms(millis: Long) = (millis / spec.speed).roundToLong().coerceAtLeast(1L)
            val parts = ArrayList<Part>()
            val motions = spec.motions
            for ((index, motion) in motions.withIndex()) {
                val next = motions[(index + 1) % motions.size].shape
                val morph = spec.morph && next != motion.shape
                when (motion) {
                    is BusyMotion.SquareSwap -> repeat(motion.rounds) {
                        parts += Part(Move.TURN, BusyShape.SQUARE, ms(SWAP), diagonal = 0)
                        parts += Part(Move.TURN, BusyShape.SQUARE, ms(SWAP), diagonal = 1)
                    }
                    is BusyMotion.TriangleWalk -> repeat(3 * motion.laps) {
                        parts += Part(Move.TURN, BusyShape.TRIANGLE, ms(STEP))
                    }
                    is BusyMotion.RowWave ->
                        parts += Part(Move.BOUNCE, BusyShape.ROW, ms(WAVE * motion.waves), waves = motion.waves)
                    is BusyMotion.Pulse -> repeat(motion.times) { n ->
                        parts += Part(Move.MERGE, motion.shape, ms(MERGE))
                        val last = n == motion.times - 1
                        parts += Part(Move.SPLIT, if (last && morph) next else motion.shape, ms(SPLIT))
                    }
                }
                if (morph && motion !is BusyMotion.Pulse) {
                    parts += Part(Move.MERGE, motion.shape, ms(MERGE))
                    parts += Part(Move.SPLIT, next, ms(SPLIT))
                }
            }
            return parts
        }

        private fun endsOf(parts: List<Part>): LongArray {
            var end = 0L
            return LongArray(parts.size) { end += parts[it].millis; end }
        }
    }
}
