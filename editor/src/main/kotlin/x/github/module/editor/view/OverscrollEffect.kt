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

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.widget.EdgeEffect

/**
 * Encapsulates the four [EdgeEffect] instances (top/bottom/left/right) used by EditorView
 * to render the Android overscroll "glow" and manages their per-edge active/finished state.
 *
 * Callers are responsible for deciding *which* edge is being hit (that requires knowledge
 * of scroll position / bounds, which only the View owns). This class only decides *whether*
 * a given edge should play its effect right now, and how to draw/release/reset it.
 */
class OverscrollEffect(context: Context) {

    /** The four physical edges an EdgeEffect can be attached to. */
    enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    // Bundles an EdgeEffect with its "already active" flag so the two always travel together.
    // This replaces what used to be 4 separate EdgeEffect fields + 4 separate Boolean fields.
    private class EdgeState(context: Context) {
        val effect = EdgeEffect(context)
        var active = false
    }

    private val topState = EdgeState(context)
    private val bottomState = EdgeState(context)
    private val leftState = EdgeState(context)
    private val rightState = EdgeState(context)

    // A `when` on an enum compiles to a tableswitch (no boxing, no hashing), so this dispatch
    // is effectively as fast as inlining each case by hand, without repeating the 4 branches
    // in every public method below.
    private fun stateFor(edge: Edge): EdgeState = when (edge) {
        Edge.TOP -> topState
        Edge.BOTTOM -> bottomState
        Edge.LEFT -> leftState
        Edge.RIGHT -> rightState
    }

    /**
     * Called when a fling hits [edge] (e.g. scroller.currX < 0 for LEFT).
     * `isFinished() && !active` guards against re-triggering the absorb animation on every
     * frame while the fling keeps reporting the same out-of-bounds position — without it,
     * onAbsorb() would be called dozens of times per fling instead of once.
     */
    fun absorb(edge: Edge, velocity: Int) {
        val state = stateFor(edge)
        if (state.effect.isFinished() && !state.active) {
            state.effect.onAbsorb(velocity)
            state.active = true
        }
    }

    /** Called while the user drags past [edge] (rubber-band stretch effect). */
    fun pull(edge: Edge, delta: Float, displacement: Float) {
        val state = stateFor(edge)
        state.effect.pull(delta, displacement)
        state.active = true
    }

    /** Call on ACTION_UP: lets the currently stretched edge(s) animate back to rest. */
    fun release() {
        topState.effect.onRelease()
        bottomState.effect.onRelease()
        leftState.effect.onRelease()
        rightState.effect.onRelease()
    }

    /** Call on ACTION_DOWN: clears "active" so a new gesture can trigger absorb/pull again. */
    fun clearActive() {
        topState.active = false
        bottomState.active = false
        leftState.active = false
        rightState.active = false
    }

    /**
     * Draws all four edges and returns whether the caller should postInvalidateOnAnimation()
     * to keep the glow animation going.
     *
     * Uses the non-short-circuiting `or` (not `||`) on purpose: `||` would skip calling
     * drawAt() on the remaining edges as soon as one returns true, silently dropping their
     * draw calls. `or` always evaluates every operand, so all four edges are always drawn.
     */
    fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        return topState.effect.drawAt(canvas, width, height, 0f, 0f) or
            bottomState.effect.drawAt(
                canvas, width, height,
                // EdgeEffect always paints its glow as if it were the TOP edge at (0,0).
                // To reuse it for the bottom edge we flip the canvas 180° around the
                // bottom-right corner and translate it back into the visible area.
                translateX = -width.toFloat(), translateY = height.toFloat(),
                rotation = 180f, pivotX = width.toFloat(), pivotY = 0f
            ) or
            leftState.effect.drawAt(
                canvas, height, width,   
                // width/height swapped: after a 90° rotation the
                // canvas's local x/y axes are swapped relative to the view
                translateX = 0f, translateY = height.toFloat(),
                rotation = -90f, pivotX = 0f, pivotY = 0f
            ) or
            rightState.effect.drawAt(
                canvas, height, width,   
                // same axis swap as the left edge, opposite rotation
                translateX = width.toFloat(), translateY = 0f,
                rotation = 90f, pivotX = 0f, pivotY = 0f
            )
    }

    // API 31 (S) replaced the older onPull() with onPullDistance(), which reports pull
    // distance more accurately for stretch-based overscroll. Devices below S don't have it.
    private fun EdgeEffect.pull(delta: Float, displacement: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            onPullDistance(delta, displacement)
        else
            onPull(delta, displacement)
    }

    // Shared save/transform/draw/restore sequence for one edge. Returns false immediately
    // for a finished (invisible) edge so callers never need their own isFinished() check.
    private fun EdgeEffect.drawAt(
        canvas: Canvas,
        sizeWidth: Int,
        sizeHeight: Int,
        translateX: Float,
        translateY: Float,
        rotation: Float = 0f,
        pivotX: Float = 0f,
        pivotY: Float = 0f
    ): Boolean {
        if (isFinished()) return false
        val restoreCount = canvas.save()
        canvas.translate(translateX, translateY)
        if (rotation != 0f) {
            canvas.rotate(rotation, pivotX, pivotY)
        }
        setSize(sizeWidth, sizeHeight)
        val needsInvalidate = draw(canvas)
        canvas.restoreToCount(restoreCount)
        return needsInvalidate
    }
}

