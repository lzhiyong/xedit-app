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

package x.github.module.editor.view

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

import com.google.android.material.color.MaterialColors

/**
 * How a fold marker answers a tap: the way a Material 3 icon button does.
 *
 *  - **A ripple** spreads from the marker and fades, a circle around it, in the
 *    state-layer color of the theme (colorOnSurfaceVariant at the pressed opacity of
 *    MD3, from the Material components library).
 *  - **The chevron turns** to its new direction (down for open, right for folded),
 *    with the standard easing and the medium duration of MD3 motion, as the theme
 *    defines them (motionEasingStandardInterpolator, motionDurationMedium2); with a
 *    theme that does not, the MD3 values themselves.
 *
 * The markers are drawn on the editor's canvas, not Views, so the ripple is a
 * RippleDrawable laid over the text like the handles ([addOverlay]: the editor draws
 * it and redraws when it changes), and the turn is a fraction the marker is drawn
 * with ([foldedFraction]). Main thread.
 */
internal class FoldMarkerFeedback(
    private val view: View,
    // puts a drawable over the text, see EditorView.addOverlay
    private val addOverlay: (Drawable) -> Unit,
    // the ripple color when the theme has no Material colors
    private val fallbackColor: () -> Int
) {

    private companion object {
        val PRESSED = intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled)
        val RELEASED = intArrayOf(android.R.attr.state_enabled)
        // MD3: the state layer of a pressed component
        const val PRESSED_ALPHA = 0.10f
        // how long the finger counts as down: long enough for the ripple to be seen
        const val RELEASE_DELAY_MS = 120L
        // MD3 motion, for a theme that does not define it
        const val DEFAULT_DURATION_MS = 300L
    }

    // the ripple, put over the text the first time it is needed
    private var rippleDrawable: RippleDrawable? = null

    private fun ripple(): RippleDrawable = rippleDrawable
        // no content and no mask: an unbounded ripple, like a borderless button
        ?: RippleDrawable(ColorStateList.valueOf(rippleColor()), null, null).also {
            rippleDrawable = it
            addOverlay(it)
        }

    private val release = Runnable { rippleDrawable?.state = RELEASED }

    private fun rippleColor(): Int {
        val base = MaterialColors.getColor(
            view, com.google.android.material.R.attr.colorOnSurfaceVariant, fallbackColor()
        )
        return MaterialColors.compositeARGBWithAlpha(base, (PRESSED_ALPHA * 255).toInt())
    }

    /**
     * A tap on the marker whose middle is at [centerX], [centerY] (content
     * coordinates): a ripple of [radius] spreads from there and fades.
     */
    fun press(centerX: Float, centerY: Float, radius: Int) {
        val ripple = ripple()
        // the theme can change (dark mode) while the editor lives on
        ripple.setColor(ColorStateList.valueOf(rippleColor()))
        val x = centerX.toInt()
        val y = centerY.toInt()
        ripple.setBounds(x - radius, y - radius, x + radius, y + radius)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ripple.radius = radius
        }
        ripple.setHotspot(centerX, centerY)
        ripple.state = PRESSED
        view.removeCallbacks(release)
        view.postDelayed(release, RELEASE_DELAY_MS)
    }

    // --- the turn of the chevron ---

    // the marker that turns, and from where to where
    private var turningLine = -1
    private var fromFraction = 0f
    private var toFraction = 0f

    private val turnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { view.invalidate() }
    }

    /**
     * The range starting on [line] was just [folded] or unfolded: its chevron turns
     * there from where it points now.
     */
    fun turn(line: Int, folded: Boolean) {
        val now = foldedFraction(line, !folded)
        turnAnimator.cancel()
        turningLine = line
        fromFraction = now
        toFraction = if (folded) 1f else 0f
        turnAnimator.duration = duration()
        turnAnimator.interpolator = interpolator()
        turnAnimator.start()
    }

    /**
     * How far the chevron of the range starting on [line] is turned towards folded,
     * from 0 (open) to 1 (folded); [folded] says where it ends up when it is not
     * turning (any more).
     */
    fun foldedFraction(line: Int, folded: Boolean): Float {
        if (line != turningLine || !turnAnimator.isRunning) return if (folded) 1f else 0f
        return fromFraction + (toFraction - fromFraction) * (turnAnimator.animatedValue as Float)
    }

    /** Stops whatever is under way, e.g. when another text is loaded. */
    fun cancel() {
        turnAnimator.cancel()
        turningLine = -1
        view.removeCallbacks(release)
        rippleDrawable?.state = RELEASED
    }

    // MD3 medium duration, from the theme if it has one
    private fun duration(): Long {
        val value = TypedValue()
        val theme = view.context.theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.motionDurationMedium2, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_INT && value.type <= TypedValue.TYPE_LAST_INT
        ) {
            value.data.toLong()
        } else {
            DEFAULT_DURATION_MS
        }
    }

    // MD3 standard easing, from the theme if it has one
    private fun interpolator(): Interpolator {
        val value = TypedValue()
        val theme = view.context.theme
        if (theme.resolveAttribute(com.google.android.material.R.attr.motionEasingStandardInterpolator, value, true) &&
            value.resourceId != 0
        ) {
            try {
                return AnimationUtils.loadInterpolator(view.context, value.resourceId)
            } catch (e: Resources.NotFoundException) {
                // not an interpolator after all: the MD3 curve below
            }
        }
        return PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
