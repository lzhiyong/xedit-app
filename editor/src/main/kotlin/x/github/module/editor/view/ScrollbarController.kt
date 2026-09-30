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
 * (EditorView today, GestureController once it exists).
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

    val vertThumb: Drawable
    val horizThumb: Drawable

    private val vertAnimator: ValueAnimator
    private val horizAnimator: ValueAnimator

    // Auto-fade-out runnables. Re-posted after every scroll/drag and cancelled again as
    // soon as a new one arrives (see onScrollChanged/onDown), so the bar only disappears
    // after FADE_DELAY_MS of real inactivity, not FADE_DELAY_MS after the first scroll.
    private val vertFadeAction = Runnable { if (!vertThumb.isPressed) vertAnimator.start() }
    private val horizFadeAction = Runnable { if (!horizThumb.isPressed) horizAnimator.start() }

    init {
        val colorStateList = ResourcesCompat.getColorStateList(
            context.resources,
            R.drawable.scrollbar_state_lists,
            context.theme
        )

        vertThumb = ResourcesCompat.getDrawable(
            context.resources, R.drawable.ic_vert_scrollbar_thumb, null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }

        horizThumb = ResourcesCompat.getDrawable(
            context.resources, R.drawable.ic_horiz_scrollbar_thumb, null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }

        // The fade animation slides the thumb sideways off-screen, then removes it from
        // the overlay entirely once finished (onAnimationEnd) so it stops being drawn
        // and stops being a target for verifyDrawable/invalidateDrawable callbacks.
        vertAnimator = ValueAnimator.ofInt(0, vertThumb.bounds.width()).apply {
            duration = FADE_DURATION_MS
            interpolator = FastOutLinearInInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.removeOverlay(vertThumb)
                }
            })
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                with(vertThumb) {
                    val bounds = copyBounds()
                    bounds.offset(value, 0)
                    setBounds(bounds)
                }
            }
        }

        horizAnimator = ValueAnimator.ofInt(0, horizThumb.bounds.height()).apply {
            duration = FADE_DURATION_MS
            interpolator = FastOutLinearInInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    host.removeOverlay(horizThumb)
                }
            })
            addUpdateListener { animator ->
                val value = animator.animatedValue as Int
                with(horizThumb) {
                    val bounds = copyBounds()
                    bounds.offset(0, value)
                    setBounds(bounds)
                }
            }
        }
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

    // Custom hit-test padding per thumb: each one is easier to grab than its visual
    // bounds suggest (extra width for the vertical bar, extra height for the horizontal
    // one). Ported as-is from EditorView's shared `Drawable.contains(x, y)` when-dispatch,
    // just narrowed to the two branches that used to belong to this class.
    private fun Drawable.containsTouch(x: Int, y: Int): Boolean = when (this) {
        vertThumb -> x >= bounds.left - bounds.width() && x <= bounds.right &&
                     y >= bounds.top && y <= bounds.bottom
        horizThumb -> x >= bounds.left && x <= bounds.right &&
                      y >= bounds.top - bounds.height() && y <= bounds.bottom
        else -> false
    }

    /**
     * Whether the vertical thumb is currently being dragged. dispatchTouchEvent uses
     * this alone (not the horizontal one) to stop a parent DrawerLayout from stealing
     * the gesture — matches the original code's "DrawerLayout conflict" comment, which
     * only guarded against the vertical bar.
     */
    val isDraggingVertically: Boolean get() = vertThumb.isPressed

    /**
     * Hit-test only, no state change. For callers that just need to know whether a tap
     * landed on a thumb (onSingleTapUp / onLongPress) without pressing it.
     */
    fun hitTest(x: Int, y: Int): Boolean =
        vertThumb.containsTouch(x, y) || horizThumb.containsTouch(x, y)

    /**
     * Call from onDown, after selection/cursor-handle hit-testing has already failed.
     * Returns true if a thumb was pressed — caller should treat the touch as consumed
     * and skip the rest of its own onDown branches.
     */
    fun onDown(x: Int, y: Int): Boolean = when {
        vertThumb.containsTouch(x, y) -> {
            vertThumb.isPressed = true
            host.removeCallbacks(vertFadeAction)
            // only the vertical bar requests this — see isDraggingVertically doc above
            host.requestDisallowInterceptTouchEvent(true)
            true
        }
        horizThumb.containsTouch(x, y) -> {
            horizThumb.isPressed = true
            host.removeCallbacks(horizFadeAction)
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
        vertThumb.isPressed -> {
            val deltaY = (eventY / (host.viewHeight - vertThumb.bounds.height()) * host.maxScrollY).toInt() -
                         vertThumb.bounds.height() / 2
            host.scrollTo(host.viewScrollX, deltaY.coerceIn(0, host.maxScrollY))
            true
        }
        horizThumb.isPressed -> {
            val deltaX = (eventX / (host.viewWidth - horizThumb.bounds.width()) * host.maxScrollX).toInt() -
                         horizThumb.bounds.width() / 2
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
        vertThumb.isPressed -> {
            vertThumb.isPressed = false
            host.postDelayed(vertFadeAction, FADE_DELAY_MS)
            true
        }
        horizThumb.isPressed -> {
            horizThumb.isPressed = false
            host.postDelayed(horizFadeAction, FADE_DELAY_MS)
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
        host.removeCallbacks(vertFadeAction)
        host.removeCallbacks(horizFadeAction)

        updateThumbBounds(l, t)

        // Whichever axis moved more decides which single bar shows right now — the two
        // are mutually exclusive, never shown together, matching the original behavior.
        if (Math.abs(t - oldt) >= Math.abs(l - oldl)) {
            host.removeOverlay(horizThumb)
            host.addOverlay(vertThumb)
            host.postDelayed(vertFadeAction, FADE_DELAY_MS)
        } else {
            host.removeOverlay(vertThumb)
            host.addOverlay(horizThumb)
            host.postDelayed(horizFadeAction, FADE_DELAY_MS)
        }
    }

    private fun updateThumbBounds(scrollX: Int, scrollY: Int) {
        with(vertThumb) {
            val deltaY = ((host.viewHeight - host.paddingVertical - bounds.height()).toFloat() *
                          scrollY / host.maxScrollY).toInt()
            setBounds(
                scrollX + host.viewWidth - host.paddingHorizontal - bounds.width(),
                scrollY + deltaY,
                scrollX + host.viewWidth - host.paddingHorizontal,
                scrollY + deltaY + bounds.height()
            )
        }
        with(horizThumb) {
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
        vertAnimator.cancel()
        horizAnimator.cancel()
        host.removeCallbacks(vertFadeAction)
        host.removeCallbacks(horizFadeAction)
        host.removeOverlay(vertThumb)
        host.removeOverlay(horizThumb)
    }

    /**
     * Ported from setWordwrap's `horizScrollbarThumb.setVisible(value, false)` exactly
     * as it was (visible = wordwrap-enabled flag) — kept the original polarity rather
     * than guessing whether it was intentional; worth double-checking against
     * WordwrapLayout's actual behavior if it looks backwards during testing.
     */
    fun setHorizontalThumbVisible(visible: Boolean) {
        horizThumb.setVisible(visible, false)
    }
}
