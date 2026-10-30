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

package x.github.module.editor.input

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.interpolator.view.animation.FastOutLinearInInterpolator

import x.github.module.editor.R

import x.github.module.editor.EditorGeometry

/**
 * Owns the vertical/horizontal scrollbar thumb drawables: their bounds, fade in/out
 * animation, and touch handling (press / drag / release). EditorView forwards the
 * relevant lifecycle and touch callbacks here instead of holding this state itself.
 *
 * IMPORTANT for callers: scrollbar hit-testing must run only *after* selection/cursor
 * handle hit-testing has already failed, matching the original onDown / onSingleTapUp /
 * onLongPress / onUp branch order in EditorView. This class has no knowledge of those
 * other drawables, so that priority order has to be enforced by whoever calls it
 * ([GestureController]).
 */
class ScrollbarController(
    context: Context,
    private val host: ScrollbarController.Host
) {

    /**
     * Everything this controller needs from the View around it, on top of the shared
     * [EditorGeometry] every controller needs: scrollTo, requesting the parent not to
     * intercept touch, and the two Handler-style callback methods. Nested here (rather
     * than a top-level type) since it only ever makes sense paired with
     * ScrollbarController, the same way GestureDetector.OnGestureListener is nested
     * under GestureDetector.
     *
     * scrollTo, postDelayed and removeCallbacks are plain methods (not properties), so
     * EditorView satisfies them by inheriting View's own implementations with no extra
     * code. postDelayed/removeCallbacks return Boolean to match View's signatures exactly
     * (not Unit), and using View's versions gives attach-state-safe queuing instead of
     * touching a raw Handler reference.
     */
    interface Host : EditorGeometry {
        fun scrollTo(x: Int, y: Int)
        fun requestDisallowInterceptTouchEvent(disallow: Boolean)
        fun postDelayed(action: Runnable, delayMillis: Long): Boolean
        fun removeCallbacks(action: Runnable): Boolean
    }

    companion object {
        // how long a bar stays visible after the last scroll/drag before it fades out
        private const val FADE_DELAY_MS = 2000L
        private const val FADE_DURATION_MS = 300L
        // how far a scroll goes to slide a bar fully in or out, see Thumb.follow
        private const val SLIDE_DISTANCE_DP = 32f
    }

    /**
     * One scrollbar thumb and everything that belongs to it: the drawable, how far
     * the fade has pushed it out of the viewport, the fade animation itself and the
     * delayed start of that animation.
     *
     * The fade is kept as an [offset] on top of the position the scroll gives the
     * thumb, not as a change of the bounds. That way the thumb can be put where the
     * scroll says at any time (the other axis scrolls while this bar fades out) and
     * the fade simply carries on from where it is.
     */
    private inner class Thumb(val drawable: Drawable, val vertical: Boolean) {
        // How far the thumb is slid out of the viewport, in px: 0 is fully in, its
        // thickness is fully out. A float: the scroll moves it a fraction of a pixel
        // at a time.
        var offset = 0f

        // Where the finger took hold of the thumb, measured along the track from the
        // thumb's leading edge (its top for the vertical bar, its left for the
        // horizontal one). The thumb is dragged by that point: wherever it was
        // grabbed stays under the finger.
        var grabOffset = 0

        // how thick the thumb is across its track: how far it slides to be out
        val thickness: Float
            get() = (if (vertical) drawable.bounds.width() else drawable.bounds.height()).toFloat()

        // The fade once the bar has been left alone: slides the thumb the rest of the
        // way out, from wherever it is, then takes it off the overlay so it is no
        // longer drawn and no longer catches touches.
        val fadeAnimator: ValueAnimator = ValueAnimator().apply {
            interpolator = FastOutLinearInInterpolator()
            addUpdateListener { animator ->
                offset = animator.animatedValue as Float
                updateBounds()
            }
            addListener(object : AnimatorListenerAdapter() {
                // cancel() is followed by onAnimationEnd as well; a cancelled fade
                // means the bar is wanted again, and must not be taken away
                private var cancelled = false

                override fun onAnimationStart(animation: Animator) {
                    cancelled = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) takeAway()
                }
            })
        }

        // starts the fade once the bar has been left alone for FADE_DELAY_MS
        val fadeAction = Runnable { fadeOut() }

        val isShown: Boolean get() = host.hasOverlay(drawable)
        val isFading: Boolean get() = fadeAnimator.isRunning

        /**
         * Follows a scroll step that moved [along] px on this bar's axis and [across]
         * px on the other one: the bar comes in by as much as its own axis leads, and
         * goes out by as much as the other one leads. So it slides with the finger: a
         * finger that stops leaves it where it is, one that turns back brings it back.
         */
        fun follow(along: Int, across: Int) {
            if (drawable.isPressed) return
            val lead = along - across
            if (lead == 0) return
            if (lead > 0) {
                if (!isShown) {
                    // coming in from outside the viewport
                    offset = thickness
                    host.addOverlay(drawable)
                }
                stopTimedFade()
                offset = Math.max(0f, offset - lead * slideRate())
                scheduleFade()
            } else if (isShown) {
                stopTimedFade()
                offset = Math.min(thickness, offset - lead * slideRate())
                if (offset >= thickness) takeAway() else scheduleFade()
            }
        }

        // px of slide per px of scroll: the thickness over SLIDE_DISTANCE_DP of scroll
        private fun slideRate(): Float = thickness / slideDistance

        // shown fully, until it has been left alone for FADE_DELAY_MS
        fun show() {
            stopFade()
            host.addOverlay(drawable)
            scheduleFade()
        }

        private fun scheduleFade() {
            host.removeCallbacks(fadeAction)
            host.postDelayed(fadeAction, FADE_DELAY_MS)
        }

        fun fadeOut() {
            host.removeCallbacks(fadeAction)
            if (!isShown || isFading || drawable.isPressed) {
                return
            }
            // the rest of the way, in the part of the time the rest takes
            val from = offset
            val to = thickness
            if (from >= to) {
                takeAway()
                return
            }
            fadeAnimator.setFloatValues(from, to)
            fadeAnimator.duration = Math.max(1L, (FADE_DURATION_MS * (to - from) / to).toLong())
            fadeAnimator.start()
        }

        fun hide() {
            host.removeCallbacks(fadeAction)
            stopFade()
            host.removeOverlay(drawable)
        }

        // out of the viewport and off the overlay, ready to come back next time
        private fun takeAway() {
            host.removeCallbacks(fadeAction)
            host.removeOverlay(drawable)
            offset = 0f
            updateBounds()
        }

        // calls off a running timed fade, leaving the thumb where it is
        private fun stopTimedFade() {
            if (fadeAnimator.isRunning) {
                fadeAnimator.cancel()
            }
        }

        // calls off a running fade and puts the thumb back fully in
        fun stopFade() {
            stopTimedFade()
            if (offset != 0f) {
                offset = 0f
                updateBounds()
            }
        }

        fun updateBounds() {
            val scrollX = lastScrollX
            val scrollY = lastScrollY
            val slid = offset.toInt()
            with(drawable) {
                val width = bounds.width()
                val height = bounds.height()
                if (vertical) {
                    // a text that fits needs no scrolling, and must not divide by zero
                    val maxScroll = host.maxScrollY
                    val deltaY = if (maxScroll > 0)
                        // the track ends where the keyboard begins, if there is one
                        ((host.visibleHeight - height).toFloat() * scrollY / maxScroll).toInt()
                    else 0
                    val right = scrollX + host.viewWidth - host.paddingHorizontal + slid
                    setBounds(right - width, scrollY + deltaY, right, scrollY + deltaY + height)
                } else {
                    val maxScroll = host.maxScrollX
                    val deltaX = if (maxScroll > 0)
                        ((host.viewportWidth - width).toFloat() * scrollX / maxScroll).toInt()
                    else 0
                    // at the bottom of what can be seen: above the keyboard, not under it
                    val bottom = scrollY + host.visibleHeight + slid
                    setBounds(scrollX + deltaX, bottom - height, scrollX + deltaX + width, bottom)
                }
            }
        }
    }

    // how far a scroll goes to slide a bar fully in or out, see Thumb.follow
    private val slideDistance: Float = SLIDE_DISTANCE_DP * context.resources.displayMetrics.density

    val verticalThumb: Drawable
    val horizontalThumb: Drawable

    private val vertical: Thumb
    private val horizontal: Thumb

    // the scroll position the thumbs were last placed for
    private var lastScrollX = 0
    private var lastScrollY = 0

    init {
        val colorStateList = ResourcesCompat.getColorStateList(
            context.resources,
            R.drawable.scrollbar_state_lists,
            context.theme
        )

        verticalThumb = ResourcesCompat.getDrawable(
            context.resources, R.drawable.ic_vert_scrollbar_thumb, null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }

        horizontalThumb = ResourcesCompat.getDrawable(
            context.resources, R.drawable.ic_horiz_scrollbar_thumb, null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }

        vertical = Thumb(verticalThumb, vertical = true)
        horizontal = Thumb(horizontalThumb, vertical = false)
    }

    // EditorView had ONE shared `Drawable.isPressed` extension reused by every handle
    // and thumb. Scoping a private copy here keeps ScrollbarController self-contained
    // instead of depending on an extension defined somewhere else in the View.
    //
    // Only the state_pressed flag is added/removed here — any other state dimension
    // the drawable's selector might carry (state_enabled, state_selected, ...) is
    // preserved instead of being wiped out. `state` is documented to always return a
    // non-null array (StateSet.WILD_CARD when nothing has been set), so no null check
    // is needed on the getter.
    private var Drawable.isPressed: Boolean
        get() = state.contains(android.R.attr.state_pressed)
        set(value) {
            state = if (value) {
                if (state.contains(android.R.attr.state_pressed)) state
                else state + android.R.attr.state_pressed
            } else {
                state.filterNot { it == android.R.attr.state_pressed }.toIntArray()
            }
        }

    // The smallest area a finger can reliably hit: 48dp, the size Android asks of
    // every touch target.
    private val minTouchSize = (48 * context.resources.displayMetrics.density).toInt()

    private val touchRect = Rect()

    // The area in which a touch takes hold of the thumb. It is larger than the thumb
    // looks, on every side:
    //  - across the bar, it reaches into the content by at least the thumb's own
    //    thickness again (and to 48dp in all), and out to the edge of the view by
    //    one more thickness, so a finger coming in from the bezel still finds it;
    //  - along the bar, a thumb shorter than 48dp is padded equally at both ends.
    private fun Thumb.touchBounds(out: Rect) {
        val bounds = drawable.bounds
        if (vertical) {
            val reach = Math.max(bounds.width() * 2, minTouchSize)
            val padding = Math.max(0, (minTouchSize - bounds.height()) / 2)
            out.set(bounds.right - reach, bounds.top - padding, bounds.right + bounds.width(), bounds.bottom + padding)
        } else {
            val reach = Math.max(bounds.height() * 2, minTouchSize)
            val padding = Math.max(0, (minTouchSize - bounds.width()) / 2)
            out.set(bounds.left - padding, bounds.bottom - reach, bounds.right + padding, bounds.bottom + bounds.height())
        }
    }

    // The isShown check matters: both thumbs keep their bounds up to date, so without
    // it a hidden one would still catch touches. A bar that is fading out is still
    // shown and can still be grabbed: that brings it back (see onDown).
    private fun Thumb.containsTouch(x: Int, y: Int): Boolean {
        if (!isShown) {
            return false
        }
        touchBounds(touchRect)
        return touchRect.contains(x, y)
    }

    /**
     * The touch area of the vertical thumb, in content coordinates.
     *
     * @return false if the bar is not shown (and [out] is left alone)
     *
     * The view uses it to keep the system's back gesture off this strip of the
     * screen edge: a thumb at the right edge sits exactly where that gesture starts.
     */
    fun getVerticalTouchBounds(out: Rect): Boolean {
        if (!vertical.isShown) {
            return false
        }
        vertical.touchBounds(out)
        return true
    }

    /**
     * Whether a thumb is being dragged. While it is, the view keeps its parent (a
     * DrawerLayout, a ViewPager, a scrolling container) from taking the gesture
     * over: a thumb drag runs along the same edges those like to listen at.
     */
    val isDragging: Boolean get() = verticalThumb.isPressed || horizontalThumb.isPressed

    /**
     * Hit-test only, no state change. For callers that just need to know whether a tap
     * landed on a thumb (onSingleTapUp / onLongPress) without pressing it.
     */
    fun hitTest(x: Int, y: Int): Boolean =
        vertical.containsTouch(x, y) || horizontal.containsTouch(x, y)

    /**
     * Call from onDown, after selection/cursor-handle hit-testing has already failed.
     * Returns true if a thumb was pressed — caller should treat the touch as consumed
     * and skip the rest of its own onDown branches.
     */
    fun onDown(x: Int, y: Int): Boolean = when {
        vertical.containsTouch(x, y) -> {
            press(vertical, x, y)
            host.requestDisallowInterceptTouchEvent(true)
            true
        }
        horizontal.containsTouch(x, y) -> {
            press(horizontal, x, y)
            host.requestDisallowInterceptTouchEvent(true)
            true
        }
        else -> false
    }

    // a thumb grabbed while fading out comes back fully and stays while it is held
    private fun press(thumb: Thumb, x: Int, y: Int) {
        host.removeCallbacks(thumb.fadeAction)
        thumb.stopFade()
        thumb.drawable.isPressed = true

        // Remember where on the thumb the finger is (after stopFade, which may just
        // have put the thumb back in place). The touch area is larger than the
        // thumb, so a touch beside it counts as a touch on its nearest edge.
        val bounds = thumb.drawable.bounds
        thumb.grabOffset = if (thumb.vertical) {
            (y - bounds.top).coerceIn(0, bounds.height())
        } else {
            (x - bounds.left).coerceIn(0, bounds.width())
        }
    }

    /**
     * Call from onScroll (drag), after selection-handle dragging has already been ruled
     * out. Returns true if a thumb was being dragged, in which case the scroll position
     * was already updated via host.scrollTo — caller should not also apply its own
     * scroll delta for this event. [eventX]/[eventY] is the finger position inside
     * the viewport, i.e. the touch position minus the left/top padding.
     */
    fun onDrag(eventX: Float, eventY: Float): Boolean = when {
        verticalThumb.isPressed -> {
            // how far the thumb can travel, and where the finger wants its top to be
            val travel = host.visibleHeight - verticalThumb.bounds.height()
            val thumbTop = eventY - vertical.grabOffset
            host.scrollTo(host.viewScrollX, scrollForThumbAt(thumbTop, travel, host.maxScrollY))
            true
        }
        horizontalThumb.isPressed -> {
            val travel = host.viewportWidth - horizontalThumb.bounds.width()
            val thumbLeft = eventX - horizontal.grabOffset
            host.scrollTo(scrollForThumbAt(thumbLeft, travel, host.maxScrollX), host.viewScrollY)
            true
        }
        else -> false
    }

    // The scroll position at which the thumb is [thumbPosition] px along its track.
    // The exact inverse of how Thumb.updateBounds places it: the thumb covers its
    // [travel] while the content covers [maxScroll].
    private fun scrollForThumbAt(thumbPosition: Float, travel: Int, maxScroll: Int): Int {
        if (travel <= 0 || maxScroll <= 0) {
            return 0
        }
        val fraction = (thumbPosition / travel).coerceIn(0f, 1f)
        // in double: a large file is tens of millions of pixels high, more than a
        // float can tell apart
        return Math.round(fraction.toDouble() * maxScroll).toInt()
    }

    /**
     * Call from onUp, after selection-handle release has already been ruled out.
     * Returns true if a thumb was released — caller should skip its remaining onUp
     * branches for this event.
     */
    fun onUp(): Boolean = when {
        verticalThumb.isPressed -> {
            release(vertical)
            true
        }
        horizontalThumb.isPressed -> {
            release(horizontal)
            true
        }
        else -> false
    }

    private fun release(thumb: Thumb) {
        thumb.drawable.isPressed = false
        host.postDelayed(thumb.fadeAction, FADE_DELAY_MS)
    }

    /**
     * Call from View.onScrollChanged. The caller must still guard this itself with
     * `if (scaleGestureDetector.isInProgress()) return` before calling in — pinch-zoom
     * shouldn't re-trigger the fade-in/fade-out cycle, and this class has no reason to
     * know about ScaleGestureDetector.
     */
    fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        lastScrollX = l
        lastScrollY = t
        // Each bar follows the scroll: in as far as its axis leads, out as far as
        // the other one does (see Thumb.follow). Turning from scrolling sideways to
        // scrolling down slides the horizontal bar out with the finger, and turning
        // back slides it in again; nothing jumps.
        val dx = Math.abs(l - oldl)
        val dy = Math.abs(t - oldt)
        vertical.follow(along = dy, across = dx)
        horizontal.follow(along = dx, across = dy)
        vertical.updateBounds()
        horizontal.updateBounds()
    }

    fun onViewportChanged() {
        lastScrollX = host.viewScrollX
        lastScrollY = host.viewScrollY
        vertical.updateBounds()
        horizontal.updateBounds()
    }

    /**
     * Call from onScaleBegin: pinch-zoom hides both bars immediately with no fade,
     * matching the original code's outright animator.cancel() + overlay removal
     * (as opposed to the gentle fade used everywhere else).
     */
    fun hideImmediately() {
        vertical.hide()
        horizontal.hide()
    }

    /**
     * Ported from setWordwrap's `horizontalScrollbarThumb.setVisible(value, false)` exactly
     * as it was (visible = wordwrap-enabled flag) — kept the original polarity rather
     * than guessing whether it was intentional; worth double-checking against
     * WordwrapLayout's actual behavior if it looks backwards during testing.
     */
    fun setHorizontalThumbVisible(visible: Boolean) {
        horizontalThumb.setVisible(visible, false)
    }
}
