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

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import x.editor.app.R

/**
 * Where the dots of [BusyDotsDrawable] stand when they rest: what a [BusyMotion] moves, and
 * what the dots run together from and split into between two motions.
 */
enum class BusyShape(internal val dots: Int) {
    /** four dots at the corners of a square */
    SQUARE(4),
    /** three dots at the corners of a triangle, one on top */
    TRIANGLE(3),
    /** three dots in a row, a little low (they jump up) */
    ROW(3),
}

/**
 * One of the sub-animations of [BusyDotsDrawable]. Each one alone loops without a seam (it
 * ends where it began), so a [BusyDotsSpec] of one motion is a busy mark of its own; several
 * play in turn, and between two of different shapes the dots run together into one dot and
 * split into the next shape (the morph, [BusyDotsSpec.morph]).
 */
sealed class BusyMotion {
    abstract val shape: BusyShape

    /**
     * The square: the two dots of one diagonal swap places, straight across through the
     * middle, the other two staying still; then the two of the other diagonal. [rounds]
     * times (a round: both diagonals).
     */
    data class SquareSwap(val rounds: Int = 1) : BusyMotion() {
        init { require(rounds >= 1) { "rounds must be at least 1: $rounds" } }
        override val shape get() = BusyShape.SQUARE
    }

    /**
     * The triangle: its three dots all move on along its sides to the next corner, a corner
     * a step, a short rest after each; [laps] times round (a lap: three steps).
     */
    data class TriangleWalk(val laps: Int = 1) : BusyMotion() {
        init { require(laps >= 1) { "laps must be at least 1: $laps" } }
        override val shape get() = BusyShape.TRIANGLE
    }

    /** The row: its three dots jump in turn, a wave; [waves] times. */
    data class RowWave(val waves: Int = 2) : BusyMotion() {
        init { require(waves >= 1) { "waves must be at least 1: $waves" } }
        override val shape get() = BusyShape.ROW
    }

    /**
     * The dots of [shape] run together into one bigger dot, which swells a little and splits
     * back into them; [times] times. (The morph between two motions is the same run
     * together and split, into another shape.)
     */
    data class Pulse(override val shape: BusyShape = BusyShape.SQUARE, val times: Int = 1) : BusyMotion() {
        init { require(times >= 1) { "times must be at least 1: $times" } }
    }
}

/**
 * What a [BusyDotsDrawable] plays, in a loop: [motions] in turn. [morph]: between two of
 * different shapes (and from the last back to the first) the dots run together into one and
 * split into the next shape; off, the next shape is simply there. [speed]: 2 plays twice as
 * fast, 0.5 half as fast.
 *
 *     BusyDotsSpec.DEFAULT                                         // the toolchain sheet's
 *     BusyDotsSpec.of(BusyMotion.RowWave())                        // the wave alone
 *     BusyDotsSpec.of(BusyMotion.SquareSwap(rounds = 2), BusyMotion.Pulse())
 *     BusyDotsSpec.of(BusyMotion.TriangleWalk(), BusyMotion.RowWave(waves = 1), speed = 1.5f)
 */
data class BusyDotsSpec(
    val motions: List<BusyMotion>,
    val morph: Boolean = true,
    val speed: Float = 1f,
) {
    init {
        require(motions.isNotEmpty()) { "at least one motion" }
        require(speed > 0f && speed.isFinite()) { "speed must be above 0: $speed" }
    }

    companion object {
        @JvmStatic
        fun of(vararg motions: BusyMotion, morph: Boolean = true, speed: Float = 1f) =
            BusyDotsSpec(motions.toList(), morph, speed)

        /** the whole loop: the square, the triangle, the row, a morph between each */
        @JvmField
        val DEFAULT = of(BusyMotion.SquareSwap(), BusyMotion.TriangleWalk(), BusyMotion.RowWave())
    }
}

// #region on a view

/**
 * [dots] as this button's icon, going round, while [busy]; then [idleIcon] again (or, 0, the
 * icon the button had before). It can be called as often as wanted (on every change of
 * state): nothing is done while the icon is already the one wanted, so the dots do not start
 * over. The dots take the button's iconTint.
 */
fun MaterialButton.setBusyDots(busy: Boolean, dots: BusyDotsDrawable, @DrawableRes idleIcon: Int = 0) {
    swapBusyDots(busy, dots, current = { icon }, set = { icon = it }, setRes = { setIconResource(it) }, idleIcon)
}

/**
 * [dots] as this image's drawable, going round, while [busy]; then [idleImage] again (or,
 * 0, the drawable it had before). As [MaterialButton.setBusyDots]. The dots take the
 * view's imageTintList, if it has one.
 */
fun ImageView.setBusyDots(busy: Boolean, dots: BusyDotsDrawable, @DrawableRes idleImage: Int = 0) {
    swapBusyDots(busy, dots, current = { drawable }, set = { setImageDrawable(it) }, setRes = { setImageResource(it) }, idleImage)
}

/**
 * [dots] at the start of this text (its start compound drawable), going round, while [busy];
 * then the start drawable it had before again. A word next to them says what is going on
 * ("Thinking"). As [MaterialButton.setBusyDots]: it can be called on every change of state.
 * The dots keep their own tint ([BusyDotsDrawable.setTintList]); the gap to the text is the
 * view's compoundDrawablePadding.
 */
fun TextView.setBusyDotsAtStart(busy: Boolean, dots: BusyDotsDrawable) {
    swapBusyDots(
        busy, dots,
        current = { compoundDrawablesRelative[0] },
        set = { start ->
            // a compound drawable is drawn in its bounds: none yet, its own size
            if (start != null && start.bounds.isEmpty) start.setBounds(0, 0, start.intrinsicWidth, start.intrinsicHeight)
            val now = compoundDrawablesRelative
            setCompoundDrawablesRelative(start, now[1], now[2], now[3])
        },
        setRes = {},
        idle = 0,
    )
}

// The drawable a view had before the dots, kept in a tag of it while they are shown (the
// tag itself: whether they are). No drawable before is kept as NoDrawable.
private object NoDrawable

private inline fun View.swapBusyDots(
    busy: Boolean,
    dots: BusyDotsDrawable,
    current: () -> Drawable?,
    set: (Drawable?) -> Unit,
    setRes: (Int) -> Unit,
    @DrawableRes idle: Int,
) {
    val shown = getTag(R.id.busy_dots_previous) != null
    if (busy) {
        if (!shown) {
            setTag(R.id.busy_dots_previous, current() ?: NoDrawable)
            set(dots)
        }
        dots.start()
    } else if (shown) {
        val previous = getTag(R.id.busy_dots_previous)
        setTag(R.id.busy_dots_previous, null)
        dots.stop()
        when {
            idle != 0 -> setRes(idle)
            else -> set(previous as? Drawable)
        }
    }
}

// #endregion
