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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.interpolator.view.animation.FastOutLinearInInterpolator

import x.github.module.editor.R

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
        private const val FADE_DURATION_MS = 1000L
    }

    val verticalThumb: Drawable
    val horizontalThumb: Drawable

    private val verticalAnimator: ValueAnimator
    private val horizontalAnimator: ValueAnimator

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

        // The fade animation slides the thumb sideways off-screen, then removes it from
        // the overlay entirely once finished (onAnimationEnd) so it stops being drawn
        // and stops being a target for verifyDrawable/invalidateDrawable callbacks.
        verticalAnimator = ValueAnimator.ofInt(0, verticalThumb.bounds.width()).apply {
            duration = FADE_DURATION_MS
            interpolator = FastOutLinearInInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.removeOverlay(verticalThumb)
                }
            })
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                with(verticalThumb) {
                    val bounds = copyBounds()
                    bounds.offset(value, 0)
                    setBounds(bounds)
                }
            }
        }

        horizontalAnimator = ValueAnimator.ofInt(0, horizontalThumb.bounds.height()).apply {
            duration = FADE_DURATION_MS
            interpolator = FastOutLinearInInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.removeOverlay(horizontalThumb)
                }
            })
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                with(horizontalThumb) {
                    val bounds = copyBounds()
                    bounds.offset(0, value)
                    setBounds(bounds)
                }
            }
        }
    }

    // Auto-fade-out runnables. Re-posted after every scroll/drag and cancelled again as
    // soon as a new one arrives (see onScrollChanged/onDown), so the bar only disappears
    // after FADE_DELAY_MS of real inactivity, not FADE_DELAY_MS after the first scroll.
    // (Declared after init: they capture the thumbs and animators assigned there.)
    private val verticalFadeAction = Runnable { if (!verticalThumb.isPressed) verticalAnimator.start() }
    private val horizontalFadeAction = Runnable { if (!horizontalThumb.isPressed) horizontalAnimator.start() }

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

    // Custom hit-test padding per thumb: each one is easier to grab than its visual
    // bounds suggest (extra width for the vertical bar, extra height for the horizontal
    // one). Ported as-is from EditorView's shared `Drawable.contains(x, y)` when-dispatch,
    // just narrowed to the two branches that used to belong to this class.
    //
    // The hasOverlay check matters: only one thumb is shown at a time, but both keep
    // their bounds up to date, so without it the hidden one would still catch touches.
    private fun Drawable.containsTouch(x: Int, y: Int): Boolean {
        val inside = when (this) {
            verticalThumb -> x >= bounds.left - bounds.width() && x <= bounds.right &&
                         y >= bounds.top && y <= bounds.bottom
            horizontalThumb -> x >= bounds.left && x <= bounds.right &&
                          y >= bounds.top - bounds.height() && y <= bounds.bottom
            else -> false
        }
        return inside && host.hasOverlay(this)
    }

    /**
     * Whether the vertical thumb is currently being dragged. dispatchTouchEvent uses
     * this alone (not the horizontal one) to stop a parent DrawerLayout from stealing
     * the gesture — matches the original code's "DrawerLayout conflict" comment, which
     * only guarded against the vertical bar.
     */
    val isDraggingVertically: Boolean get() = verticalThumb.isPressed

    /**
     * Hit-test only, no state change. For callers that just need to know whether a tap
     * landed on a thumb (onSingleTapUp / onLongPress) without pressing it.
     */
    fun hitTest(x: Int, y: Int): Boolean =
        verticalThumb.containsTouch(x, y) || horizontalThumb.containsTouch(x, y)

    /**
     * Call from onDown, after selection/cursor-handle hit-testing has already failed.
     * Returns true if a thumb was pressed — caller should treat the touch as consumed
     * and skip the rest of its own onDown branches.
     */
    fun onDown(x: Int, y: Int): Boolean = when {
        verticalThumb.containsTouch(x, y) -> {
            verticalThumb.isPressed = true
            host.removeCallbacks(verticalFadeAction)
            // only the vertical bar requests this — see isDraggingVertically doc above
            host.requestDisallowInterceptTouchEvent(true)
            true
        }
        horizontalThumb.containsTouch(x, y) -> {
            horizontalThumb.isPressed = true
            host.removeCallbacks(horizontalFadeAction)
            true
        }
        else -> false
    }

    /**
     * Call from onScroll (drag), after selection-handle dragging has already been ruled
     * out. Returns true if a thumb was being dragged, in which case the scroll position
     * was already updated via host.scrollTo — caller should not also apply its own
     * scroll delta for this event.
     */
    fun onDrag(eventX: Float, eventY: Float): Boolean = when {
        verticalThumb.isPressed -> {
            val deltaY = (eventY / (host.viewHeight - verticalThumb.bounds.height()) * host.maxScrollY).toInt() -
                         verticalThumb.bounds.height() / 2
            host.scrollTo(host.viewScrollX, deltaY.coerceIn(0, host.maxScrollY))
            true
        }
        horizontalThumb.isPressed -> {
            val deltaX = (eventX / (host.viewWidth - horizontalThumb.bounds.width()) * host.maxScrollX).toInt() -
                         horizontalThumb.bounds.width() / 2
            host.scrollTo(deltaX.coerceIn(0, host.maxScrollX), host.viewScrollY)
            true
        }
        else -> false
    }

    /**
     * Call from onUp, after selection-handle release has already been ruled out.
     * Returns true if a thumb was released — caller should skip its remaining onUp
     * branches for this event.
     */
    fun onUp(): Boolean = when {
        verticalThumb.isPressed -> {
            verticalThumb.isPressed = false
            host.postDelayed(verticalFadeAction, FADE_DELAY_MS)
            true
        }
        horizontalThumb.isPressed -> {
            horizontalThumb.isPressed = false
            host.postDelayed(horizontalFadeAction, FADE_DELAY_MS)
            true
        }
        else -> false
    }

    /**
     * Call from View.onScrollChanged. The caller must still guard this itself with
     * `if (scaleGestureDetector.isInProgress()) return` before calling in — pinch-zoom
     * shouldn't re-trigger the fade-in/fade-out cycle, and this class has no reason to
     * know about ScaleGestureDetector.
     */
    fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        host.removeCallbacks(verticalFadeAction)
        host.removeCallbacks(horizontalFadeAction)

        updateThumbBounds(l, t)

        // Whichever axis moved more decides which single bar shows right now — the two
        // are mutually exclusive, never shown together, matching the original behavior.
        if (Math.abs(t - oldt) >= Math.abs(l - oldl)) {
            host.removeOverlay(horizontalThumb)
            host.addOverlay(verticalThumb)
            host.postDelayed(verticalFadeAction, FADE_DELAY_MS)
        } else {
            host.removeOverlay(verticalThumb)
            host.addOverlay(horizontalThumb)
            host.postDelayed(horizontalFadeAction, FADE_DELAY_MS)
        }
    }

    private fun updateThumbBounds(scrollX: Int, scrollY: Int) {
        with(verticalThumb) {
            val deltaY = ((host.viewHeight - host.paddingVertical - bounds.height()).toFloat() *
                          scrollY / host.maxScrollY).toInt()
            setBounds(
                scrollX + host.viewWidth - host.paddingHorizontal - bounds.width(),
                scrollY + deltaY,
                scrollX + host.viewWidth - host.paddingHorizontal,
                scrollY + deltaY + bounds.height()
            )
        }
        with(horizontalThumb) {
            val deltaX = ((host.viewWidth - host.paddingHorizontal - bounds.width()).toFloat() *
                          scrollX / host.maxScrollX).toInt()
            setBounds(
                scrollX + deltaX,
                scrollY + host.viewHeight - host.paddingVertical - bounds.height(),
                scrollX + deltaX + bounds.width(),
                scrollY + host.viewHeight - host.paddingVertical
            )
        }
    }

    /**
     * Call from onScaleBegin: pinch-zoom hides both bars immediately with no fade,
     * matching the original code's outright animator.cancel() + overlay removal
     * (as opposed to the gentle fade used everywhere else).
     */
    fun hideImmediately() {
        verticalAnimator.cancel()
        horizontalAnimator.cancel()
        host.removeCallbacks(verticalFadeAction)
        host.removeCallbacks(horizontalFadeAction)
        host.removeOverlay(verticalThumb)
        host.removeOverlay(horizontalThumb)
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
