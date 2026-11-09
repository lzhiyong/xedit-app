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

import android.content.Context
import android.graphics.Canvas
import android.view.animation.AnimationUtils
import android.widget.OverScroller

import x.github.module.editor.EditorGeometry
import x.github.module.editor.render.OverscrollEffect
import x.github.module.editor.render.OverscrollEffect.Edge


/**
 * Owns everything about *moving* the viewport: the [OverScroller] (smooth scroll, fling),
 * the [OverscrollEffect] glow that goes with hitting an edge, and the "keep this point
 * visible" calculation. It decides where the viewport should be; the View only applies
 * the result through scrollTo/scrollBy.
 *
 * It knows nothing about text, the cursor or the scrollbars: callers pass plain pixel
 * coordinates in, and the scrollbars follow along through View.onScrollChanged.
 */
class ScrollController(
    context: Context,
    private val host: ScrollController.Host
) {

    /**
     * On top of the shared [EditorGeometry]: the three View methods needed to actually
     * move and redraw. All three are inherited from View as-is by EditorView.
     */
    interface Host : EditorGeometry {
        fun scrollTo(x: Int, y: Int)
        fun scrollBy(x: Int, y: Int)
        fun postInvalidateOnAnimation()
    }

    companion object {
        // Two scroll requests closer together than this are treated as one continuous
        // drag and applied immediately instead of being animated.
        private const val SMOOTH_SCROLL_THRESHOLD_MS = 250L
    }

    private val scroller = OverScroller(context)
    private val overscroll = OverscrollEffect(context)

    // when the previous smooth scroll was requested
    private var lastAnimationTimeMillis = 0L

    // the part of a drag that was less than a pixel and has not been scrolled yet
    private var dragRemainderX = 0f
    private var dragRemainderY = 0f

    /**
     * Like [android.view.View.scrollBy], but scroll smoothly instead of immediately.
     *
     * @param dx the number of pixels to scroll by on the X axis
     * @param dy the number of pixels to scroll by on the Y axis
     */
    fun smoothScrollBy(dx: Int, dy: Int) {
        if (host.viewHeight == 0) {
            // Nothing to do.
            return
        }
        val duration = AnimationUtils.currentAnimationTimeMillis() - lastAnimationTimeMillis
        if (duration > SMOOTH_SCROLL_THRESHOLD_MS) {
            scroller.startScroll(host.viewScrollX, host.viewScrollY, dx, dy)
            host.postInvalidateOnAnimation()
        } else {
            if (!scroller.isFinished) {
                scroller.abortAnimation()
            }
            host.scrollBy(dx, dy)
        }
        lastAnimationTimeMillis = AnimationUtils.currentAnimationTimeMillis()
    }

    /**
     * Like [android.view.View.scrollTo], but scroll smoothly instead of immediately.
     *
     * @param x the position where to scroll on the X axis
     * @param y the position where to scroll on the Y axis
     */
    fun smoothScrollTo(x: Int, y: Int) {
        smoothScrollBy(x - host.viewScrollX, y - host.viewScrollY)
    }

    /** Call from View.computeScroll: advances a running scroll/fling by one frame. */
    fun computeScroll() {
        if (!scroller.computeScrollOffset()) return

        val velocity = scroller.currVelocity.toInt()
        // Horizontal direction: It hits either the left side or the right side;
        // it cannot hit both simultaneously.
        when {
            scroller.currX < 0 -> overscroll.absorb(Edge.LEFT, velocity)
            scroller.currX > host.maxScrollX -> overscroll.absorb(Edge.RIGHT, velocity)
        }

        // The same applies to the vertical direction.
        when {
            scroller.currY < 0 -> overscroll.absorb(Edge.TOP, velocity)
            scroller.currY > host.maxScrollY -> overscroll.absorb(Edge.BOTTOM, velocity)
        }

        host.scrollTo(
            scroller.currX.coerceIn(0, host.maxScrollX),
            scroller.currY.coerceIn(0, host.maxScrollY)
        )

        host.postInvalidateOnAnimation()
    }

    /** Call on ACTION_DOWN: a new touch always stops whatever animation is running. */
    fun abortAnimation() {
        scroller.abortAnimation()
    }

    /** Call when a new gesture starts, so its first edge hit can play the glow again. */
    fun onDown() {
        overscroll.clearActive()
        dragRemainderX = 0f
        dragRemainderY = 0f
    }

    /** Call on ACTION_UP: lets a stretched edge animate back to rest. */
    fun onUp() {
        overscroll.release()
    }

    /**
     * A finger dragging the content itself (not a handle, not a scrollbar thumb).
     * The dominant axis wins, the other one is ignored. [touchX]/[touchY] are the
     * finger position inside the view, used only to place the overscroll stretch.
     */
    fun dragBy(distanceX: Float, distanceY: Float, touchX: Float, touchY: Float) {
        val width = host.viewWidth
        val height = host.viewHeight
        val scrollX = host.viewScrollX
        val scrollY = host.viewScrollY

        // The dominant axis wins. A slow finger moves less than a pixel per event;
        // the fractions are kept and added up, or it would not move the text at all.
        if (Math.abs(distanceX) > Math.abs(distanceY)) dragRemainderX += distanceX
        if (Math.abs(distanceY) > Math.abs(distanceX)) dragRemainderY += distanceY
        val dx = dragRemainderX.toInt()
        val dy = dragRemainderY.toInt()
        dragRemainderX -= dx
        dragRemainderY -= dy

        // here not use scroller.start() function to scroll the view
        smoothScrollTo(
            (scrollX + dx).coerceIn(0, host.maxScrollX),
            (scrollY + dy).coerceIn(0, host.maxScrollY)
        )

        // The stretch at an edge, when the finger pulls further than the text goes.
        // Judged by where the view is, not by where the scroller last was.

        // vertical stretch overscroll effect
        if (scrollY + distanceY < 0f)
            overscroll.pull(Edge.TOP, -distanceY / height, 1 - touchX / width)
        else if (scrollY + distanceY > host.maxScrollY.toFloat())
            overscroll.pull(Edge.BOTTOM, distanceY / height, touchX / width)

        // horizontal stretch overscroll effect
        if (scrollX + distanceX < 0f)
            overscroll.pull(Edge.LEFT, -distanceX / width, touchY / height)
        else if (scrollX + distanceX > host.maxScrollX.toFloat())
            overscroll.pull(Edge.RIGHT, distanceX / width, touchY / height)
    }

    /** Starts a fling along the dominant axis of the given velocity. */
    fun fling(velocityX: Float, velocityY: Float) {
        // Before flinging, stops the current animation.
        scroller.forceFinished(true)

        scroller.fling(
            // Current scroll position
            host.viewScrollX,
            host.viewScrollY,
            if (Math.abs(velocityX) > Math.abs(velocityY)) -velocityX.toInt() else 0,
            if (Math.abs(velocityY) > Math.abs(velocityX)) -velocityY.toInt() else 0,
            /*
             * Minimum and maximum scroll positions. The minimum scroll
             * position is generally 0 and the maximum scroll position
             * is generally the content size less the screen size. So if the
             * content width is 2000 pixels and the screen width is 1200
             * pixels, the maximum scroll offset is 800 pixels.
             */
            0, host.maxScrollX,
            0, host.maxScrollY,
            // The edges of the content. This comes into play when using
            // the EdgeEffect class to draw "glow" overlays.
            host.viewWidth / 10,
            host.viewHeight / 10
        )
        host.postInvalidateOnAnimation()
    }

    /**
     * Scrolls just enough to bring the content point ([x], [y]) inside the window
     * [left]..[right] / [top]..[bottom], which is given in view coordinates.
     *
     * The viewport never ends up outside the content: where the window would need it
     * to go past the last scroll position it stops there, and if it currently *is*
     * past it (the content just got smaller, e.g. the widest line was shortened) it
     * comes back, even when the point itself is already inside the window. This is
     * the same limit dragging and flinging stop at, so the room left after the end of
     * the text is the same however one got there.
     *
     * @return true if the viewport had to move
     */
    fun scrollIntoView(x: Int, y: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val scrollX = host.viewScrollX
        val scrollY = host.viewScrollY
        var dx = 0
        var dy = 0

        if (x - scrollX < left) {
            dx = x - scrollX - left
        } else if (x - scrollX > right) {
            dx = x - scrollX - right
        }

        if (y - scrollY < top) {
            dy = y - scrollY - top
        } else if (y - scrollY > bottom) {
            dy = y - scrollY - bottom
        }

        // keep the viewport inside the content
        dx = (scrollX + dx).coerceIn(0, host.maxScrollX) - scrollX
        dy = (scrollY + dy).coerceIn(0, host.maxScrollY) - scrollY

        smoothScrollBy(dx, dy)
        return (dx != 0 || dy != 0)
    }

    /**
     * Call after the text size changed during a pinch: re-anchors the viewport so the
     * content under the pinch focus stays under it. [widthFactor]/[heightFactor] are
     * how much the content grew on each axis.
     */
    fun rescale(focusX: Float, focusY: Float, widthFactor: Float, heightFactor: Float) {
        val startX = ((scroller.currX + focusX) * widthFactor - focusX).toInt()
        val startY = ((scroller.currY + focusY) * heightFactor - focusY).toInt()

        scroller.startScroll(
            startX.coerceIn(0, host.maxScrollX),
            startY.coerceIn(0, host.maxScrollY),
            0, 0, 0
        )
    }

    /**
     * Draws the overscroll glow along the edges of the viewport. The canvas must have
     * its origin at the top left corner of the viewport. Returns whether the caller
     * should postInvalidateOnAnimation() to keep the glow animation going.
     */
    fun drawOverscroll(canvas: Canvas): Boolean {
        // the bottom glow belongs at the bottom of what can be seen, above the keyboard
        return overscroll.draw(canvas, host.viewportWidth, host.visibleHeight)
    }
}
