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
import android.view.Choreographer

import x.github.module.editor.EditorGeometry

/**
 * Scrolls the view while a finger that is selecting rests near an edge of the
 * viewport: a finger dragging a handle, or extending the selection a long press
 * made. Text that is off screen can be selected that way, by holding the finger at
 * the edge.
 *
 * It scrolls by itself, frame after frame, not only when the finger moves: a finger
 * held still at the edge goes on scrolling until it moves away, lifts, or the text
 * ends. After every step it calls [onScrolled], so that whoever drives the drag can
 * carry it on at the same place on screen, which is now over other text.
 *
 * How fast depends on how close to the edge the finger is: slowly where the edge
 * zone begins, at full [speed] at the edge itself or past it (a finger may go on
 * over the keyboard or off the view). It also starts gently, so that a finger that
 * merely passes through the zone on its way does not throw the text away.
 *
 * The edges are those of what can be seen: the bottom one is above the soft
 * keyboard, if it covers the view, and the left one right of a gutter that stays
 * put ([Limits.textInsetLeft]).
 *
 * It only scrolls towards text. Past the end of the row the finger is on there is
 * nothing to select, and below the last line neither: there it stops, and the
 * finger can go out into the blank as far as the screen goes, without the text
 * running away from under it ([Limits]).
 */
class EdgeAutoScroller(
    context: Context,
    private val host: Host,
    private val limits: Limits,
    private val onScrolled: () -> Unit
) : Choreographer.FrameCallback {

    /** What it needs besides the geometry: to scroll, and how high a line is. */
    interface Host : EditorGeometry {
        fun scrollBy(x: Int, y: Int)
        fun getLineHeight(): Int
    }

    /** Where the text ends, as far as the drag going on is concerned. */
    interface Limits {
        /** The x where the text of the row being selected on ends, in content coordinates. */
        fun textEndX(): Int

        /** The y where the text ends, the bottom of its last row, in content coordinates. */
        fun textEndY(): Int

        /** How much of the viewport on the left is a gutter that stays put, 0 if none. */
        val textInsetLeft: Int
    }

    companion object {
        /** The speed at the edge, in lines per second, unless set otherwise ([speed]). */
        const val DEFAULT_SPEED_LINES_PER_SECOND = 20f

        // how far from an edge scrolling begins: a line and a half, at least this
        private const val EDGE_ZONE_MIN_DP = 32f
        private const val EDGE_ZONE_LINES = 1.5f
        // where the zone begins, this much of the speed already
        private const val MIN_SPEED_FRACTION = 0.15f
        // from standing still to the speed the finger asks for
        private const val RAMP_UP_NANOS = 300_000_000L
        // the longest frame taken into account: after a hiccup it does not jump
        private const val MAX_FRAME_NANOS = 50_000_000L
    }

    /**
     * How fast it scrolls with the finger at the edge, in lines per second (across
     * as well: as many pixels as that many lines are high). Less where the finger is
     * not quite at the edge. 0 switches auto scrolling off.
     */
    var speed: Float = DEFAULT_SPEED_LINES_PER_SECOND
        set(value) {
            field = Math.max(0f, value)
            if (field == 0f) stop()
        }

    private val edgeZoneMin = EDGE_ZONE_MIN_DP * context.resources.displayMetrics.density
    private val choreographer = Choreographer.getInstance()

    // where the finger is, relative to the viewport (not to the text)
    private var fingerX = 0f
    private var fingerY = 0f
    // a drag is going on that wants scrolling at the edges
    private var isActive = false
    private var isFrameScheduled = false
    // when the scrolling going on started, and when the last frame was
    private var rampStartNanos = 0L
    private var lastFrameNanos = 0L
    // the part of a step that was less than a pixel, kept for the next one
    private var remainderX = 0f
    private var remainderY = 0f

    /**
     * Call at every move of the finger, with where it is relative to the viewport:
     * (0, 0) is the top left corner of the part of the view inside its padding.
     */
    fun update(viewportX: Float, viewportY: Float) {
        fingerX = viewportX
        fingerY = viewportY
        isActive = true
        if (!isFrameScheduled && speed > 0f && wantsToScroll()) {
            isFrameScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    /** The drag is over (the finger lifted, the gesture was cancelled). */
    fun stop() {
        isActive = false
        if (isFrameScheduled) {
            isFrameScheduled = false
            choreographer.removeFrameCallback(this)
        }
        settle()
    }

    override fun doFrame(frameTimeNanos: Long) {
        isFrameScheduled = false
        if (!isActive || speed <= 0f) {
            settle()
            return
        }

        val lineHeight = host.getLineHeight().toFloat()
        val velocityX = velocityX(lineHeight)
        val velocityY = velocityY(lineHeight)
        if (!canScroll(velocityX, velocityY)) {
            // out of the zone, or the text ends there: wait for the finger to move
            settle()
            return
        }

        if (rampStartNanos == 0L) {
            rampStartNanos = frameTimeNanos
            lastFrameNanos = frameTimeNanos
        }
        val frameNanos = (frameTimeNanos - lastFrameNanos).coerceIn(0L, MAX_FRAME_NANOS)
        lastFrameNanos = frameTimeNanos
        val ramp = ((frameTimeNanos - rampStartNanos).toFloat() / RAMP_UP_NANOS).coerceIn(0f, 1f)

        // pixels per second -> pixels this frame
        val pixelsPerSecond = speed * lineHeight * ramp
        val seconds = frameNanos / 1_000_000_000f
        remainderX += velocityX * pixelsPerSecond * seconds
        remainderY += velocityY * pixelsPerSecond * seconds
        var dx = remainderX.toInt()
        var dy = remainderY.toInt()
        remainderX -= dx
        remainderY -= dy

        // never past the ends of the text, nor on into blank (see Limits); a view
        // that is past that already is not pulled back, only not taken further
        val scrollX = host.viewScrollX
        val scrollY = host.viewScrollY
        dx = (scrollX + dx).coerceIn(0, Math.max(scrollX, forwardLimitX(lineHeight))) - scrollX
        dy = (scrollY + dy).coerceIn(0, Math.max(scrollY, forwardLimitY(lineHeight))) - scrollY
        if (dx != 0 || dy != 0) {
            host.scrollBy(dx, dy)
            onScrolled()
        }

        // onScrolled may have stopped it
        if (isActive) {
            isFrameScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    // how deep the edge zones of an axis this long are: a line and a half, at
    // least the minimum, at most a quarter of the axis
    private fun zoneOf(size: Float, lineHeight: Float): Float =
        Math.min(Math.max(edgeZoneMin, lineHeight * EDGE_ZONE_LINES), size / 4f)

    private fun velocityX(lineHeight: Float): Float {
        // the text area starts right of a gutter that stays put
        val inset = limits.textInsetLeft.toFloat()
        return axisVelocity(fingerX - inset, host.viewportWidth - inset, lineHeight)
    }

    private fun velocityY(lineHeight: Float): Float =
        axisVelocity(fingerY, host.visibleHeight.toFloat(), lineHeight)

    // Which way and how hard the finger asks to scroll along one axis: -1 (towards
    // the start, at full speed) to 1 (towards the end); 0 away from both edges.
    private fun axisVelocity(position: Float, size: Float, lineHeight: Float): Float {
        if (size <= 0f) return 0f
        val zone = zoneOf(size, lineHeight)
        if (zone <= 0f) return 0f
        return when {
            position < zone -> -strength((zone - position) / zone)
            position > size - zone -> strength((position - (size - zone)) / zone)
            else -> 0f
        }
    }

    // The furthest right worth scrolling: until the end of the row being selected
    // on has come out of the right edge zone. Past that it is blank.
    private fun forwardLimitX(lineHeight: Float): Int {
        val inset = limits.textInsetLeft
        val zone = zoneOf((host.viewportWidth - inset).toFloat(), lineHeight).toInt()
        val limit = limits.textEndX() + zone - host.viewportWidth
        return limit.coerceIn(0, host.maxScrollX)
    }

    // The furthest down worth scrolling: until the last row has come out of the
    // bottom edge zone. Below it there is nothing to select.
    private fun forwardLimitY(lineHeight: Float): Int {
        val zone = zoneOf(host.visibleHeight.toFloat(), lineHeight).toInt()
        val limit = limits.textEndY() + zone - host.visibleHeight
        return limit.coerceIn(0, host.maxScrollY)
    }

    // how deep into the zone (0 where it begins, 1 at the edge and beyond) -> speed
    private fun strength(depth: Float): Float {
        val d = depth.coerceIn(0f, 1f)
        return MIN_SPEED_FRACTION + (1f - MIN_SPEED_FRACTION) * d
    }

    private fun wantsToScroll(): Boolean {
        val lineHeight = host.getLineHeight().toFloat()
        return canScroll(velocityX(lineHeight), velocityY(lineHeight))
    }

    // whether there is text in the direction asked for
    private fun canScroll(velocityX: Float, velocityY: Float): Boolean {
        val lineHeight = host.getLineHeight().toFloat()
        val x = (velocityX < 0f && host.viewScrollX > 0) ||
            (velocityX > 0f && host.viewScrollX < forwardLimitX(lineHeight))
        val y = (velocityY < 0f && host.viewScrollY > 0) ||
            (velocityY > 0f && host.viewScrollY < forwardLimitY(lineHeight))
        return x || y
    }

    // back to standing still: the next scroll starts gently again
    private fun settle() {
        rampStartNanos = 0L
        lastFrameNanos = 0L
        remainderX = 0f
        remainderY = 0f
    }
}
