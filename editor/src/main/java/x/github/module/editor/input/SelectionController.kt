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
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range

import x.github.module.editor.EditorGeometry
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.layout.isWordChar
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.layout.TabStops
import x.github.module.editor.util.TextBoundaries

/**
 * Owns the caret and the selection: where they are in the document ([cursor], [range]),
 * what shows them (the cursor bar, the insertion handle, the two selection handles),
 * the cursor blink, and what happens when a handle is pressed, dragged and released.
 *
 * How the handles behave:
 *
 *  - **The insertion handle stays under the finger.** It goes wherever the finger
 *    goes, also where there is no text: past the end of a line, between two rows,
 *    below the last line. Meanwhile the cursor follows on the text, at the position
 *    nearest to it.
 *  - **A dragged selection handle stays on the text.** It stands on the character
 *    nearest to the finger and goes from character to character with it. Where
 *    there is no text (past the end of a line, in the gutter, below the last line)
 *    it does not follow the finger out into the blank: it stays at the start or the
 *    end of that row, where the selection ends. The magnifier still glides with the
 *    finger along the row (see [getMagnifierPosition]).
 *  - **Let go, it springs back** to that position, a little past it and back.
 *  - **It does not flicker between rows.** The finger has to go some way into the
 *    next row before the position changes rows (see [rowAdjustedForSlop]).
 *  - **The two selection handles can change places.** One dragged past the other
 *    becomes the other end of the selection. They never mark the same position:
 *    one character always stays selected.
 *  - **Handles pop in and shrink away** instead of appearing and vanishing. A
 *    selection made by a long press grows out from under the finger, and its
 *    handles pop up when the finger lifts.
 *  - **The selection handles sway** when the text is scrolled under them, sideways
 *    or up and down: they hang back a little and swing into place. This can be
 *    switched off ([isHandleSwayEnabled]) and made smaller or larger
 *    ([handleSwayAmplitude]). The insertion handle never sways.
 *
 * All coordinates passed in are *content* coordinates (already offset by scroll and
 * padding). This class never scrolls, never shows a menu or a magnifier and never looks
 * at MotionEvents: it reports what happened and lets the caller decide what follows.
 */
class SelectionController(
    context: Context,
    private val host: SelectionController.Host,
    private val document: EditorDocument,
    private val metrics: TextMetrics,
    // only used to convert between a pixel advance and a character offset within a line
    private val textPaint: TextPaint
) {

    /**
     * On top of geometry: the layout that maps positions to screen lines, redrawing,
     * haptics, and delayed callbacks for the blink. The text itself is read from the
     * document and measured with the metrics, both passed to the constructor.
     * invalidate/performHapticFeedback/postDelayed/removeCallbacks are inherited from
     * View as-is.
     */
    interface Host : EditorGeometry {
        val textLayout: TextLayout

        /**
         * The cursor was put on [lineNumber], by whatever moved it: an arrow key, a
         * search, an undo. The editor unfolds the line if it is folded away.
         */
        fun onCursorMoved(lineNumber: Int) {}

        /**
         * How much of the viewport on the left is not text: the width of a gutter
         * that stays put while the text scrolls under it, 0 if there is none. A
         * handle is not dragged under it.
         */
        val textInsetLeft: Int

        fun invalidate()
        fun performHapticFeedback(feedbackConstant: Int): Boolean
        fun postDelayed(action: Runnable, delayMillis: Long): Boolean
        fun removeCallbacks(action: Runnable): Boolean
    }

    /** The three draggable handles. */
    enum class Handle {
        /** the single handle under the cursor, shown after a tap */
        INSERTION,
        /** the handle at the start of the selection */
        START,
        /** the handle at the end of the selection */
        END
    }

    companion object {
        // the cursor is shown for this long, then hidden for this long
        private const val BLINK_INTERVAL_MS = 500L
        // how long the insertion handle stays after it was shown or let go
        private const val INSERTION_HANDLE_TIMEOUT_MS = 4000L

        // a handle appears with a little overshoot, and shrinks away faster
        private const val HANDLE_SHOW_DURATION_MS = 260L
        private const val HANDLE_HIDE_DURATION_MS = 120L
        // a handle that was let go springs back to its place on the text
        private const val HANDLE_RETURN_DURATION_MS = 340L
        /**
         * How far a selection handle sways at most, in dp, unless set otherwise
         * ([handleSwayAmplitude]).
         */
        const val DEFAULT_HANDLE_SWAY_AMPLITUDE_DP = 12f
        // With the default amplitude, a scrolled handle hangs back behind the text by
        // as far as the text moves in this many milliseconds (so: by its speed), up
        // to the amplitude, before it swings into place. Another amplitude scales
        // this along with it.
        private const val HANDLE_SWAY_LAG_MS = 16f
        // how long the swing back into place takes, overshoot included
        private const val HANDLE_SWAY_RETURN_DURATION_MS = 340L
        // how much of each new speed reading is taken on (the rest is the old value)
        private const val HANDLE_SWAY_SMOOTHING = 0.4f
        // scroll steps further apart than this are separate scrolls
        private const val HANDLE_SWAY_PAUSE_MS = 100L
        private const val FRAME_MS = 16f
        // a selection made by a long press grows out from under the finger
        private const val SELECTION_REVEAL_DURATION_MS = 220L

        // Changing rows while dragging takes this much of a line height into the
        // next row, within these limits. (The numbers of android.widget.Editor.)
        private const val LINE_SLOP_RATIO = 0.5f
        private const val LINE_CHANGE_SLOP_MIN_DP = 8
        private const val LINE_CHANGE_SLOP_MAX_DP = 45

        // the touch-up filter
        private const val TOUCH_UP_HISTORY_SIZE = 5
        private const val TOUCH_UP_FILTER_DELAY_AFTER_MS = 150L
        private const val TOUCH_UP_FILTER_DELAY_BEFORE_MS = 350L
    }

    private val density = context.resources.displayMetrics.density
    private val lineChangeSlopMin = (LINE_CHANGE_SLOP_MIN_DP * density).toInt()
    private val lineChangeSlopMax = (LINE_CHANGE_SLOP_MAX_DP * density).toInt()

    // the cursor the editor draws itself; kept for when an image is given up again
    private val drawnCursor = DrawnCursorDrawable(context.resources.displayMetrics.density)

    // what the cursor looks like right now: the drawn one, or an image
    private var cursorBar: Drawable = drawnCursor

    // how wide the cursor is, whatever it looks like
    private var cursorWidth = drawnCursor.width

    // where the cursor bar belongs: the x of the caret, and the bottom of its row
    private var cursorAnchorX = 0
    private var cursorAnchorBottom = 0

    // The color last set, for handles that are made later (see setHandleDrawables).
    // Declared before the handles: they read it when they are made.
    private var handleColor: Int? = null

    private val insertionHandle = TextHandle(Handle.INSERTION)
    private val startHandle = TextHandle(Handle.START)
    private val endHandle = TextHandle(Handle.END)

    /**
     * Whether the selection handles sway when the text is scrolled under them (see
     * [onViewportScrolled]). On by default. Switched off, a handle that is swaying
     * right now stops where it belongs.
     */
    var isHandleSwayEnabled = true
        set(value) {
            field = value
            if (!value) {
                startHandle.stopSway()
                endHandle.stopSway()
            }
        }

    /**
     * How far a selection handle sways at most, in pixels; 0 keeps it still. It
     * scales the sway at every speed, not only the limit: half the amplitude, half
     * the sway.
     */
    var handleSwayAmplitude: Float = DEFAULT_HANDLE_SWAY_AMPLITUDE_DP * density
        set(value) {
            field = Math.max(0f, value)
            if (field == 0f) {
                startHandle.stopSway()
                endHandle.stopSway()
            }
        }

    /** The caret position. Also drives the cursor bar drawable and its blinking. */
    val cursor = TextCursor()

    /** The selected range. Empty (1,1,1,1) when nothing is selected. */
    val range = TextSelector()

    fun isSelected() = !range.isEmpty()

    init {
        // the handles take the accent color of the theme, if it has one
        val accent = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, accent, true) &&
            accent.type >= TypedValue.TYPE_FIRST_COLOR_INT && accent.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            setHandleColor(accent.data)
        }
    }

    /**
     * The color of the handles the editor draws itself: their stem and the ring of
     * their knob; the inside of the knob is a very light tint of it. Handles that
     * are images (see [setHandleDrawables]) keep their own colors.
     */
    fun setHandleColor(color: Int) {
        handleColor = color
        for (handle in arrayOf(insertionHandle, startHandle, endHandle)) {
            handle.drawable.applyColor(color)
        }
        // the drawn cursor matches the handles
        drawnCursor.color = color
    }

    /**
     * What the cursor looks like: an image, stretched to the height of a row and
     * as wide as the image says (2dp if it does not say), or null for the bar the
     * editor draws itself, the default, in the handle color.
     *
     * The image is copied before use, so a resource can be handed in as it is.
     */
    fun setCursorDrawable(image: Drawable?) {
        val newBar: Drawable = if (image == null) {
            drawnCursor
        } else {
            image.constantState?.newDrawable()?.mutate() ?: image.mutate()
        }
        cursorWidth = if (newBar === drawnCursor) {
            drawnCursor.width
        } else {
            newBar.intrinsicWidth.takeIf { it > 0 } ?: drawnCursor.width
        }
        // in place of the old one, shown if that was shown
        val wasShown = host.hasOverlay(cursorBar)
        if (wasShown) {
            host.removeOverlay(cursorBar)
        }
        cursorBar = newBar
        insertionHandle.layout()
        if (wasShown) {
            host.addOverlay(newBar)
        }
    }

    /**
     * How wide the cursor the editor draws is, in pixels. An image keeps its own
     * width.
     */
    fun setCursorWidth(px: Int) {
        drawnCursor.width = Math.max(1, px)
        if (cursorBar === drawnCursor) {
            cursorWidth = drawnCursor.width
            insertionHandle.layout()
        }
    }

    fun getCursorWidth(): Int = cursorWidth

    /**
     * Whether [drawable] is the cursor or a handle: something that belongs to a
     * place in the text, and is drawn only where the text can be seen, not over a
     * gutter that stays put.
     */
    fun isTextOverlay(drawable: Drawable): Boolean =
        drawable === cursorBar || drawable === insertionHandle.drawable ||
            drawable === startHandle.drawable || drawable === endHandle.drawable

    /**
     * What the handles look like: an image for each, or null for the handle the
     * editor draws itself (a stem with a round knob), which is what all three are
     * by default.
     *
     * The images are taken to follow Android's convention: they hang below the row,
     * the start handle pointing up and to the right from its left part, the end
     * handle the other way round (see [ResourceTextHandle]). The handles the
     * editor draws itself differ: the start handle stands above its row. Either way
     * the rest of the editor goes by what the handle says about itself.
     *
     * Mixing the two looks works, but rarely looks good: a drawn start handle above
     * the row and an image below it, say.
     */
    fun setHandleDrawables(start: Drawable?, end: Drawable?, insertion: Drawable?) {
        startHandle.useDrawable(makeHandleDrawable(Handle.START, start))
        endHandle.useDrawable(makeHandleDrawable(Handle.END, end))
        insertionHandle.useDrawable(makeHandleDrawable(Handle.INSERTION, insertion))
    }

    private fun makeHandleDrawable(kind: Handle, image: Drawable?): TextHandleDrawable {
        val drawableKind = when (kind) {
            Handle.INSERTION -> TextHandleDrawable.Kind.INSERTION
            Handle.START -> TextHandleDrawable.Kind.START
            Handle.END -> TextHandleDrawable.Kind.END
        }
        if (image != null) {
            return ResourceTextHandle(drawableKind, image, density)
        }
        return DrawnTextHandle(drawableKind, density).also { drawn ->
            handleColor?.let { drawn.applyColor(it) }
        }
    }

    // #region position <-> pixels

    // where the text starts, right of the line numbers
    private fun gutterWidth() = metrics.gutterWidth(document.getLineCount())

    // the width of the text of one line between two columns
    private fun lineSegmentWidth(lineNumber: Int, startColumn: Int, endColumn: Int): Int {
        return metrics.measureText(document.getLineSegment(lineNumber, startColumn, endColumn))
    }

    // x of the end of the text of a visual line
    private fun lineEndX(visualLine: Int) = gutterWidth() + host.textLayout.getLineWidth(visualLine)

    /**
     * @return [x, y] in content coordinates, where y is the *bottom* of the line
     */
    fun getLocationForPosition(lineNumber: Int, column: Int): IntArray {
        val layout = host.textLayout
        // get index and line break result
        val index = layout.getIndexAt(lineNumber, column)
        val result = layout.getLineResult(index + 1)
        // visual line number
        var line = index + 1
        // start column of current line
        var start = result.start + 1

        // check the start boundary of line
        // and previous line end boundary
        if (result.start + 1 == column && result.start - 1 > 0) {
            if (!isSelected()) {
                if (!document.getLineCharAt(lineNumber, result.start - 1).isWordChar()) {
                    // previous line result
                    start = layout.getLineResult(--line).start + 1
                }
            } else {
                if (lineNumber == range.endLine && column == range.endColumn) {
                    // previous line result
                    start = layout.getLineResult(--line).start + 1
                }
            }
        }

        return intArrayOf(
            gutterWidth() + lineSegmentWidth(lineNumber, start, column),
            line * metrics.lineHeight
        )
    }

    fun getLocationForPosition(pos: Position): IntArray {
        return getLocationForPosition(pos.lineNumber, pos.column)
    }

    /**
     * Re-derives where everything is on screen from the document positions. Call
     * after anything that moves text on screen without moving the cursor (text size,
     * typeface, relayout).
     */
    fun updateDrawableBounds() {
        val (x, bottom) = getLocationForPosition(cursor)
        moveCursorBar(x, bottom)

        if (isSelected()) {
            val (startX, startBottom) = getLocationForPosition(range.getStartPosition())
            startHandle.moveTo(startX, startBottom)

            val (endX, endBottom) = getLocationForPosition(range.getEndPosition())
            endHandle.moveTo(endX, endBottom)
        }
    }

    /**
     * Call when the view was scrolled, by [dx] pixels sideways and [dy] pixels down.
     *
     * Nothing moves in the text. But the two selection handles are given a little
     * weight: scrolled sideways or up and down, they hang back behind the text for a
     * moment and then swing into place, a little past it and back (unless that is
     * switched off, see [isHandleSwayEnabled]). The insertion handle stays on the
     * cursor. And the knob of the start handle goes below its row when there is no
     * room left above it.
     */
    fun onViewportScrolled(dx: Int, dy: Int) {
        // A handle that is held beside the text belongs to the finger, and the finger
        // does not move when the text scrolls under it (dragging a handle to the edge
        // scrolls the view). Left where it was in the text, the handle would slide
        // away with the text and jump back at the next move of the finger. (One that
        // stands on a character goes with the character.)
        val held = pressedHandle()
        if (held != null && heldHandleFloats) {
            handleOf(held).floatTo(heldTipInViewX + host.viewScrollX, heldTipInViewY + host.viewScrollY)
        }

        if ((dx != 0 || dy != 0) && isHandleSwayEnabled && handleSwayAmplitude > 0f) {
            updateHangBack(dx, dy)
            startHandle.sway(hangBackX, hangBackY)
            endHandle.sway(hangBackX, hangBackY)
        }
        if (startHandle.isShown) {
            startHandle.layout()
        }
    }

    // How fast the text is being scrolled, in pixels per millisecond on each axis,
    // evened out over the last few scroll steps; and when the last step was.
    private var scrollVelocityX = 0f
    private var scrollVelocityY = 0f
    private var lastScrollMillis = 0L

    // how far a handle hangs back behind the text after the last scroll step
    private var hangBackX = 0f
    private var hangBackY = 0f

    // Works out how far a handle hangs back behind text that was just scrolled by
    // [dx], [dy], into hangBackX / hangBackY.
    //
    // It goes by the speed of the scrolling, not by the distance: the handle trails
    // the text by as far as the text moves in a few milliseconds. A slow drag barely
    // moves it, a fling throws it out to the limit, and as the fling slows down the
    // handle comes in with it. Each axis on its own: steps that only go down let a
    // handle that was still out sideways come in.
    private fun updateHangBack(dx: Int, dy: Int) {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastScrollMillis
        lastScrollMillis = now

        if (elapsed > HANDLE_SWAY_PAUSE_MS) {
            // The first step after a pause: there is no time to divide by yet, so
            // take it for one frame. (A scroll that begins with a jump, like a
            // programmatic scrollTo, would otherwise look like enormous speed.)
            scrollVelocityX = dx / FRAME_MS
            scrollVelocityY = dy / FRAME_MS
        } else {
            val time = Math.max(1L, elapsed).toFloat()
            // evened out, so that one uneven frame does not make the handle twitch
            scrollVelocityX += (dx / time - scrollVelocityX) * HANDLE_SWAY_SMOOTHING
            scrollVelocityY += (dy / time - scrollVelocityY) * HANDLE_SWAY_SMOOTHING
        }

        // The text moves by -dx, -dy on screen; staying behind it is +dx, +dy in
        // text coordinates. The amplitude scales the lag as well as the limit.
        val amplitude = handleSwayAmplitude
        val lag = HANDLE_SWAY_LAG_MS * amplitude / (DEFAULT_HANDLE_SWAY_AMPLITUDE_DP * density)
        hangBackX = (scrollVelocityX * lag).coerceIn(-amplitude, amplitude)
        hangBackY = (scrollVelocityY * lag).coerceIn(-amplitude, amplitude)
    }

    // Puts the cursor bar (and the insertion handle, which hangs from it) where the
    // caret is. Both may be drawn somewhere else for a while: under a finger, or on
    // their way back from there (see TextHandle.layout).
    private fun moveCursorBar(x: Int, bottom: Int) {
        cursorAnchorX = x
        cursorAnchorBottom = bottom
        insertionHandle.moveTo(x, bottom)
    }

    private fun layoutCursorBar(offsetX: Float, offsetY: Float) {
        val left = (cursorAnchorX + offsetX).toInt() - cursorWidth / 2
        val bottom = (cursorAnchorBottom + offsetY).toInt()
        cursorBar.setBounds(left, bottom - metrics.lineHeight, left + cursorWidth, bottom)
    }

    // #endregion

    // #region handles

    /**
     * One handle: where it belongs on the text (its anchor), how far from there it
     * is drawn right now, and how much of it is there (appearing, disappearing).
     *
     * How far from the anchor is made of two parts that do not get in each other's
     * way: the offset (under a finger, or springing back from there) and the sway
     * (hanging back behind scrolled text). Only a selection handle that nobody
     * holds sways.
     */
    private inner class TextHandle(val kind: Handle) {

        var drawable: TextHandleDrawable = makeHandleDrawable(kind, null)
            private set

        // the x of the position it marks, and the bottom of the row that is on
        var anchorX = 0
            private set
        var anchorBottom = 0
            private set

        // from the anchor to where the finger has it, or to where it springs back from
        private var offsetX = 0f
        private var offsetY = 0f
        // and on top of that, how far it hangs back behind scrolled text
        private var swayX = 0f
        private var swayY = 0f

        /** Where its tip is drawn: the anchor, unless it is held, on its way back, or swaying. */
        val tipX: Float get() = anchorX + offsetX + swayX
        val tipBottom: Float get() = anchorBottom + offsetY + swayY

        var isPressed = false
            set(value) {
                field = value
                drawable.isHeld = value
                if (value && isSwaying) {
                    // Taken hold of in the middle of a swing: it stays where it is
                    // drawn, under the finger, and from now on that is part of where
                    // the finger has it.
                    offsetX += swayX
                    offsetY += swayY
                    cancelSway()
                    layout()
                }
            }

        // on its way out: still drawn, no longer there to be touched
        private var isLeaving = false

        private var presenceAnimator: ValueAnimator? = null
        private var returnAnimator: ValueAnimator? = null
        // The swing back after a sway, and where it started from. One animator for
        // the life of the handle, made when it first sways: a fling sways the
        // handle anew at every frame, and should not make a new one every time.
        private var swayAnimator: ValueAnimator? = null
        private var swayFromX = 0f
        private var swayFromY = 0f

        val isShown: Boolean get() = host.hasOverlay(drawable)

        /**
         * Looks like [newDrawable] from now on. A handle that is on screen stays
         * there, as far out as it was, in its new look.
         */
        fun useDrawable(newDrawable: TextHandleDrawable) {
            val old = drawable
            val wasShown = isShown
            if (wasShown) {
                host.removeOverlay(old)
            }
            newDrawable.presence = old.presence
            newDrawable.isHeld = old.isHeld
            drawable = newDrawable
            layout()
            if (wasShown) {
                host.addOverlay(newDrawable)
            }
        }

        /** Puts it on the text at another place. Its tip stays where it is drawn. */
        fun moveTo(x: Int, bottom: Int) {
            if (isPressed) {
                // under a finger: the tip stays put, only the anchor moves under it
                val keepX = tipX
                val keepBottom = tipBottom
                anchorX = x
                anchorBottom = bottom
                offsetX = keepX - x
                offsetY = keepBottom - bottom
            } else {
                anchorX = x
                anchorBottom = bottom
            }
            layout()
        }

        /** Draws its tip at a point, wherever its anchor is. For a handle that is held. */
        fun floatTo(tipX: Float, tipBottom: Float) {
            returnAnimator?.cancel()
            cancelSway()
            offsetX = tipX - anchorX
            offsetY = tipBottom - anchorBottom
            layout()
        }

        /** Draws it away from its anchor by this much. For animations. */
        fun setOffset(x: Float, y: Float) {
            offsetX = x
            offsetY = y
            layout()
        }

        fun layout() {
            val lineHeight = metrics.lineHeight
            val rowTop = tipBottom - lineHeight
            // A handle that stands above its row (the start handle the editor draws
            // itself) goes below instead if there is no room above, on the top row on
            // screen. Decided by where the row is, not by where the handle swings to:
            // a handle swinging across the top edge for a moment must not flip its
            // knob back and forth.
            val restingRowTop = anchorBottom + offsetY - lineHeight
            val reach = drawable.heightAboveRow
            val flipped = reach > 0f && restingRowTop - reach < host.viewScrollY
            drawable.place(tipX, rowTop, tipBottom, flipped)
            if (kind == Handle.INSERTION) {
                // The cursor bar is the stem of the insertion handle: it goes along
                // with it when it is dragged, and springs back with it.
                layoutCursorBar(offsetX, offsetY)
            }
        }

        fun contains(x: Int, y: Int): Boolean = isShown && !isLeaving && drawable.isNear(x, y)

        fun isOnKnob(x: Int, y: Int): Boolean = isShown && !isLeaving && drawable.isOnKnob(x, y)

        /**
         * The text is being scrolled under a handle that nobody holds: it hangs back
         * behind the text by [hangBackX], [hangBackY] (see updateHangBack: the faster
         * the scrolling, the further) and swings into place when the scrolling eases
         * off, a little past it and back.
         *
         * Only for the selection handles. The insertion handle stays with the
         * cursor, which does not sway.
         */
        fun sway(hangBackX: Float, hangBackY: Float) {
            if (kind == Handle.INSERTION || !isShown || isLeaving || isPressed) {
                return
            }
            // Out to where the speed puts it, and from there back in. As long as the
            // scrolling goes on it is put out again at every step; when it stops,
            // the handle swings home.
            cancelSway()
            swayX = hangBackX
            swayY = hangBackY
            layout()
            if (hangBackX == 0f && hangBackY == 0f) {
                return
            }
            swayFromX = hangBackX
            swayFromY = hangBackY
            val animator = swayAnimator ?: ValueAnimator.ofFloat(1f, 0f).also {
                // from all of the sway to none of it; the overshoot takes it past none
                it.duration = HANDLE_SWAY_RETURN_DURATION_MS
                it.interpolator = OvershootInterpolator(1.6f)
                it.addUpdateListener { animation ->
                    val remaining = animation.animatedValue as Float
                    swayX = swayFromX * remaining
                    swayY = swayFromY * remaining
                    layout()
                }
                swayAnimator = it
            }
            animator.start()
        }

        // whether it is out of place, or on its way back, because of a sway
        private val isSwaying: Boolean
            get() = swayX != 0f || swayY != 0f || swayAnimator?.isRunning == true

        /** Stops a swing and puts it where it belongs, at once. */
        fun stopSway() {
            if (isSwaying) {
                cancelSway()
                layout()
            }
        }

        // Stops a swing and forgets it; laying out again is up to the caller.
        // (Cancelling does not run the update listener: the sway stays at zero.)
        private fun cancelSway() {
            swayAnimator?.cancel()
            swayX = 0f
            swayY = 0f
        }

        /**
         * Lets it appear anew, whether it is there already or not: from nothing to a
         * little too large, and settling. For a tap, which should always be answered.
         */
        fun pop() {
            if (!isShown) {
                host.addOverlay(drawable)
            }
            isLeaving = false
            drawable.presence = 0f
            animatePresence(1f, HANDLE_SHOW_DURATION_MS, overshoot = true) {}
        }

        /** Lets it appear: it grows from nothing to a little too large, and settles. */
        fun show() {
            if (isShown && !isLeaving && presenceAnimator == null) {
                return
            }
            if (!isShown) {
                drawable.presence = 0f
                host.addOverlay(drawable)
            }
            isLeaving = false
            animatePresence(1f, HANDLE_SHOW_DURATION_MS, overshoot = true) {}
        }

        /** Lets it shrink away. */
        fun hide() {
            if (!isShown || isLeaving) {
                return
            }
            isLeaving = true
            returnAnimator?.cancel()
            animatePresence(0f, HANDLE_HIDE_DURATION_MS, overshoot = false) {
                isLeaving = false
                host.removeOverlay(drawable)
                cancelSway()
                setOffset(0f, 0f)
            }
        }

        /** Takes it away at once, without the shrinking. */
        fun hideNow() {
            presenceAnimator?.cancel()
            presenceAnimator = null
            returnAnimator?.cancel()
            cancelSway()
            isLeaving = false
            if (isShown) {
                host.removeOverlay(drawable)
            }
            drawable.presence = 0f
            setOffset(0f, 0f)
        }

        private fun animatePresence(target: Float, duration: Long, overshoot: Boolean, onEnd: () -> Unit) {
            presenceAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(drawable.presence, target)
            animator.duration = duration
            animator.interpolator = if (overshoot) OvershootInterpolator(3f) else DecelerateInterpolator()
            animator.addUpdateListener { drawable.presence = it.animatedValue as Float }
            animator.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        presenceAnimator = null
                        drawable.presence = target
                        onEnd()
                    }
                }
            })
            presenceAnimator = animator
            animator.start()
        }

        /**
         * Lets it go back from wherever the finger left it to its place on the text:
         * quickly, a little too far, and back.
         */
        fun springBack() {
            returnAnimator?.cancel()
            if (offsetX == 0f && offsetY == 0f) {
                return
            }
            val fromX = offsetX
            val fromY = offsetY
            // from all of the offset to none of it; the overshoot takes it past none
            val animator = ValueAnimator.ofFloat(1f, 0f)
            animator.duration = HANDLE_RETURN_DURATION_MS
            animator.interpolator = OvershootInterpolator(1.6f)
            animator.addUpdateListener {
                val remaining = it.animatedValue as Float
                setOffset(fromX * remaining, fromY * remaining)
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (returnAnimator === animation) {
                        returnAnimator = null
                    }
                }
            })
            returnAnimator = animator
            animator.start()
        }
    }

    private fun handleOf(handle: Handle): TextHandle = when (handle) {
        Handle.INSERTION -> insertionHandle
        Handle.START -> startHandle
        Handle.END -> endHandle
    }

    private fun pressedHandle(): Handle? = when {
        insertionHandle.isPressed -> Handle.INSERTION
        startHandle.isPressed -> Handle.START
        endHandle.isPressed -> Handle.END
        else -> null
    }

    /**
     * Hit-test only, no state change. Returns the handle at that point, if any.
     *
     * A short selection puts its two handles close together, and their touch areas
     * may overlap: the one whose knob is nearer to the point is taken.
     */
    fun hitTest(x: Int, y: Int): Handle? {
        if (insertionHandle.contains(x, y)) {
            return Handle.INSERTION
        }
        val onStart = startHandle.contains(x, y)
        val onEnd = endHandle.contains(x, y)
        return when {
            onStart && onEnd ->
                if (startHandle.drawable.distanceToKnob(x, y) <= endHandle.drawable.distanceToKnob(x, y)) {
                    Handle.START
                } else {
                    Handle.END
                }
            onStart -> Handle.START
            onEnd -> Handle.END
            else -> null
        }
    }

    /**
     * Whether ([x], [y]) is on the knob of a handle, the part that is the handle to
     * the eye. [hitTest] is more generous: it also counts the line of text the
     * handle stands on, so that a drag can start there. A long press there, though,
     * is a long press on the text.
     */
    fun isOnKnob(x: Int, y: Int): Boolean {
        return insertionHandle.isOnKnob(x, y) || startHandle.isOnKnob(x, y) || endHandle.isOnKnob(x, y)
    }

    // #region what a drag needs to know for scrolling

    /**
     * The x where the text ends on the row a drag is on (a dragged handle, or a
     * long press that goes on selecting), in content coordinates. Past it there is
     * only blank: scrolling further that way would show nothing to select.
     */
    fun dragRowEndX(): Int = lineEndX(previousRowTouched)

    /** The y where the text ends: the bottom of its last row, in content coordinates. */
    fun textBottom(): Int = host.textLayout.count() * metrics.lineHeight

    /**
     * The x of the position a drag is at in the text, in content coordinates: where
     * the dragged handle belongs, or for a long press that goes on selecting, where
     * the cursor is (it follows the finger). It is not always where the handle is
     * drawn: beside the text the handle goes with the finger, and the position stays
     * at the end of the line.
     */
    fun dragAnchorX(): Int {
        val held = pressedHandle() ?: return cursorAnchorX
        return handleOf(held).anchorX
    }

    // #endregion

    /**
     * Where [handle] belongs on the text, in content coordinates: the x of the
     * position it marks and the bottom of that row. While it is held or springing
     * back it may be drawn elsewhere; this is where it ends up.
     */
    fun getHandleAnchor(handle: Handle): IntArray {
        val textHandle = handleOf(handle)
        return intArrayOf(textHandle.anchorX, textHandle.anchorBottom)
    }

    /**
     * Lets go of the handle that [onDown] took hold of, as if it had never been
     * touched: for a touch that turned out to be meant for the text.
     */
    fun cancelPress() {
        val kind = pressedHandle() ?: return
        handleOf(kind).isPressed = false
        if (kind == Handle.INSERTION) {
            scheduleInsertionHandleHide()
        }
    }

    // The insertion handle goes away by itself a while after it was shown or let go.
    private val insertionHandleHider = Runnable {
        if (!insertionHandle.isPressed) {
            insertionHandle.hide()
        }
    }

    private fun scheduleInsertionHandleHide() {
        host.removeCallbacks(insertionHandleHider)
        host.postDelayed(insertionHandleHider, INSERTION_HANDLE_TIMEOUT_MS)
    }

    private fun cancelInsertionHandleHide() {
        host.removeCallbacks(insertionHandleHider)
    }

    // #endregion

    // #region taps

    /**
     * A plain tap on the text: drops any selection, moves the cursor there and shows
     * it steadily together with the insertion handle (the caller resumes the blink).
     */
    fun placeCursorAt(x: Int, y: Int) {
        range.deselect()
        cursor.setPositionFromPoint(x, y)
        cursor.stopBlink(true, true)
        // every tap is answered by the handle popping up where it landed, also when
        // the handle was on screen already
        insertionHandle.pop()
    }

    /**
     * A long press on the text: moves the cursor there and selects the word under it.
     * The selection grows out from the point that was pressed. Its handles are not
     * shown yet: they pop up when the finger lifts (see [onUp]).
     *
     * @return true if a word was selected, false if there was no word at that point
     *         (in which case any previous selection is dropped)
     */
    fun selectWordAt(x: Int, y: Int): Boolean {
        cursor.setPositionFromPoint(x, y)
        val (startOffset, endOffset) = TextBoundaries.findWordBoundary(
            document.getLine(cursor.lineNumber),
            cursor.column - 1
        )

        if (startOffset == endOffset) {
            // no selected
            range.deselect()
            return false
        }

        // Has text selected. The handles stay away for as long as the finger that
        // made the selection is down (it is right where they would be); they come up
        // when it lifts, see onUp. If they are on screen from a selection that was
        // there before, they go now: every long press looks the same, whether it is
        // the first or not.
        startHandle.hideNow()
        endHandle.hideNow()
        handlesAwaitRelease = true
        range.select(
            cursor.lineNumber, startOffset + 1,
            cursor.lineNumber, endOffset + 1,
        )
        cursor.stopBlink(false, false)
        revealSelectionFrom(x)

        // in case the finger goes on from here, see onLongPressDrag
        pressedWordStart = pack(range.startLine, range.startColumn)
        pressedWordEnd = pack(range.endLine, range.endColumn)
        previousRowTouched = Math.max(1, cursorAnchorBottom / metrics.lineHeight)
        return true
    }

    // the word that was selected by the long press that is still going on
    private var pressedWordStart = 0L
    private var pressedWordEnd = 0L

    /**
     * Call from onScroll when the finger that selected a word by a long press moves
     * on without lifting. The selection follows it the way it follows a dragged
     * handle: character by character, on the row the finger is on, with the same
     * reluctance to change rows. The word that was pressed stays selected; the
     * selection grows from it to where the finger is, on either side.
     *
     * @return false if there is no such long press going on, and the move is for
     *         someone else
     */
    fun onLongPressDrag(x: Int, y: Int): Boolean {
        if (!handlesAwaitRelease || !isSelected()) {
            return false
        }

        val row = rowAdjustedForSlop(previousRowTouched, y.toFloat())
        previousRowTouched = row
        // (this also takes the cursor there: the point a magnifier looks at)
        val position = positionAtRow(row, x.toFloat())

        val start = Math.min(position, pressedWordStart)
        val end = Math.max(position, pressedWordEnd)
        if (start != pack(range.startLine, range.startColumn) || end != pack(range.endLine, range.endColumn)) {
            host.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            // (the handles are not shown by this: they still wait for the finger to lift)
            range.select(lineOf(start), columnOf(start), lineOf(end), columnOf(end))
        }
        return true
    }

    // a selection was just made by a long press, and the finger is still down
    private var handlesAwaitRelease = false

    // #endregion

    // #region the selection growing out from a point
    
    // a selection made by a long press grows out from the finger: see SelectionReveal
    private val reveal = SelectionReveal(SELECTION_REVEAL_DURATION_MS) { host.invalidate() }
    
    /** How far a new selection has grown, 0 to 1 (see SelectionReveal). */
    val selectionRevealFraction: Float get() = reveal.fraction
    
    /** Where a new selection grows out from, in content x. */
    val selectionRevealPivotX: Int get() = reveal.pivotX
    
    private fun revealSelectionFrom(x: Int) {
        reveal.start(x, startHandle.anchorX, endHandle.anchorX)
    }
    
    // #endregion
    
    // #region handle press / drag / release

    // from the finger to the tip of the handle it holds
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f
    // where the tip of the held handle is on screen: relative to the viewport, not
    // to the text, so that it can be kept there while the text scrolls
    private var heldTipInViewX = 0f
    private var heldTipInViewY = 0f
    // whether the held handle is beside the text, going with the finger, rather than
    // standing on a character (only the insertion handle ever is: see onDrag)
    private var heldHandleFloats = false
    // where the finger has the tip of a held selection handle, along its row: the
    // magnifier follows this, the handle itself stands on the text
    private var dragFingerTipX = 0f
    // the row the handle's position was on last, for the slop that keeps it from
    // flickering between two rows
    private var previousRowTouched = 1
    // where the dragged handle is in the text
    private var previousPosition = 0L

    /**
     * Call from onDown, before anything else gets a chance at the touch. Returns true
     * if a handle was pressed, in which case the touch belongs to it.
     */
    fun onDown(x: Int, y: Int): Boolean {
        val kind = hitTest(x, y) ?: return false
        val handle = handleOf(kind)
        handle.isPressed = true

        when (kind) {
            // shown steadily while it is held; it must not go away under the finger
            Handle.INSERTION -> cursor.stopBlink(true, true)
            else -> cursor.stopBlink(false, false)
        }

        // Where the finger took hold, relative to the tip. The handle is dragged by
        // that point: it does not jump under the finger when the drag starts. (And if
        // it was caught on its way back from the last drag, it is caught right there.)
        handle.floatTo(handle.tipX, handle.tipBottom)
        grabOffsetX = x - handle.tipX
        grabOffsetY = y - handle.tipBottom
        heldTipInViewX = handle.tipX - host.viewScrollX
        heldTipInViewY = handle.tipBottom - host.viewScrollY
        heldHandleFloats = false
        dragFingerTipX = handle.tipX

        previousRowTouched = Math.max(1, handle.anchorBottom / metrics.lineHeight)
        previousPosition = positionOf(kind)
        startTouchUpFilter(previousPosition)
        return true
    }

    /**
     * Call from onScroll (drag). The pressed handle goes with the finger at
     * ([x], [y]), wherever that is, and the cursor / selection follows on the text.
     *
     * @return the handle the finger is holding, or null if no handle is pressed and
     *         the drag is for someone else. Scrolling when the finger is held at an
     *         edge is up to the caller (see EdgeAutoScroller): it calls this again
     *         after every step, with the finger where it was.
     */
    fun onDrag(x: Int, y: Int): Handle? {
        val kind = pressedHandle() ?: return null
        val lineHeight = metrics.lineHeight

        // Where the finger wants the tip of the handle. Only the screen is the limit,
        // but beside the text the screen is all there is: the handle may go out into
        // the blank past the end of a short line, not off what can be seen, and not
        // under a gutter that stays put.
        val fingerTipX = (x - grabOffsetX).coerceIn(
            (host.viewScrollX + host.textInsetLeft).toFloat(),
            (host.viewScrollX + host.viewportWidth).toFloat()
        )
        val fingerTipBottom = (y - grabOffsetY).coerceIn(
            host.viewScrollY.toFloat(),
            (host.viewScrollY + host.visibleHeight).toFloat()
        )

        // The row: found from the middle of the row the handle would stand on, and it
        // stays the row it was until the finger is well into the next one.
        val row = rowAdjustedForSlop(previousRowTouched, fingerTipBottom - lineHeight / 2f)
        previousRowTouched = row
        // the position on that row nearest to the finger, character by character
        val position = positionAtRow(row, fingerTipX)

        // the selection handles may change places on the way: see moveSelectionHandle
        var held = kind
        if (kind == Handle.INSERTION) {
            noteHandleAt(position)
        } else {
            held = moveSelectionHandle(kind, position)
        }

        // Where the handle is drawn.
        //  - Over text it stands on the character it marks, and so moves from
        //    character to character like the selection itself.
        //  - Beside the text (past the end of the row, in the gutter, below the last
        //    line) there is no character to stand on. The insertion handle goes
        //    sideways with the finger there; a selection handle does not go out into
        //    the blank: it stays at the start or the end of the row, where the
        //    selection ends.
        //  - Up and down it never floats. It is on a row, the one worked out above,
        //    and changes rows only when the finger has crossed the threshold.
        val handle = handleOf(held)
        val overText = fingerTipX >= gutterWidth() && fingerTipX <= lineEndX(row)
        val floats = !overText && held == Handle.INSERTION
        if (floats) {
            handle.floatTo(fingerTipX, (row * lineHeight).toFloat())
        } else {
            handle.setOffset(0f, 0f)
        }
        heldHandleFloats = floats
        dragFingerTipX = fingerTipX
        heldTipInViewX = handle.tipX - host.viewScrollX
        heldTipInViewY = handle.tipBottom - host.viewScrollY
        return held
    }

    /**
     * Call from onUp. Releases whichever handle is pressed and returns it, or null if
     * no handle was pressed. The handle springs back to its place on the text.
     */
    fun onUp(): Handle? {
        if (handlesAwaitRelease) {
            // the finger that made the selection by a long press is gone: now the
            // handles come up
            handlesAwaitRelease = false
            if (isSelected()) {
                startHandle.show()
                endHandle.show()
            }
        }

        val kind = pressedHandle() ?: return null
        val handle = handleOf(kind)
        filterOnTouchUp(kind)
        handle.isPressed = false
        handle.springBack()
        if (kind == Handle.INSERTION) {
            // it stays for a while after being let go, then goes away
            scheduleInsertionHandleHide()
        }
        return kind
    }

    // A position as one number, ordered like the positions themselves.
    private fun pack(lineNumber: Int, column: Int): Long = (lineNumber.toLong() shl 32) or column.toLong()
    private fun lineOf(position: Long): Int = (position ushr 32).toInt()
    private fun columnOf(position: Long): Int = (position and 0xFFFFFFFFL).toInt()

    private fun positionOf(handle: Handle): Long = when (handle) {
        Handle.INSERTION -> pack(cursor.lineNumber, cursor.column)
        Handle.START -> pack(range.startLine, range.startColumn)
        Handle.END -> pack(range.endLine, range.endColumn)
    }

    private fun rowAt(y: Float): Int {
        val row = (Math.max(y, 0f) / metrics.lineHeight).toInt() + 1
        return row.coerceIn(1, Math.max(1, host.textLayout.count()))
    }

    /**
     * The row a dragged handle counts as being on, given the row it was on before
     * ([fromRow]) and the y it is at now.
     *
     * A finger is never still. Resting near the border between two rows it wanders
     * across by a pixel or two, and a handle that simply took "the row at y" would
     * jump back and forth between them. So next to the row it is on, the finger has
     * to go a little way *into* the neighbouring row before that one is taken: half
     * a line height, but no less than 8dp and no more than 45dp. Further away than
     * the neighbouring row, there is nothing to hesitate about.
     *
     * (After Editor.getCurrentLineAdjustedForSlop of the Android framework.)
     */
    private fun rowAdjustedForSlop(fromRow: Int, y: Float): Int {
        val trueRow = rowAt(y)
        if (fromRow < 1 || fromRow > host.textLayout.count()) {
            return trueRow
        }
        if (Math.abs(trueRow - fromRow) >= 2) {
            // only stick to a row when within one row of it
            return trueRow
        }

        val lineHeight = metrics.lineHeight
        var slop = (LINE_SLOP_RATIO * lineHeight).toInt()
        slop = Math.max(lineChangeSlopMin, Math.min(lineChangeSlopMax, lineHeight + slop)) - lineHeight
        slop = Math.max(0, slop)

        if (trueRow > fromRow && y >= fromRow * lineHeight + slop) {
            return trueRow
        }
        if (trueRow < fromRow && y <= (fromRow - 1) * lineHeight - slop) {
            return trueRow
        }
        return fromRow
    }

    // The position at x on a row. Asks the cursor, which knows how to turn a point
    // into a position; the cursor is left there.
    private fun positionAtRow(row: Int, x: Float): Long {
        val lineHeight = metrics.lineHeight
        cursor.setPositionFromPoint(x.toInt(), (row - 1) * lineHeight + lineHeight / 2)
        return pack(cursor.lineNumber, cursor.column)
    }

    // one character back / forward, across line breaks
    private fun positionBefore(position: Long): Long {
        val line = lineOf(position)
        val column = columnOf(position)
        return when {
            column > 1 -> pack(line, navigator.columnBefore(line, column))
            line > 1 -> pack(line - 1, document.getLineEndColumn(line - 1))
            else -> position
        }
    }

    private fun positionAfter(position: Long): Long {
        val line = lineOf(position)
        val column = columnOf(position)
        return when {
            column < document.getLineEndColumn(line) -> pack(line, navigator.columnAfter(line, column))
            line < document.getLineCount() -> pack(line + 1, 1)
            else -> position
        }
    }

    // Puts the selection handle that is being dragged at a position on the text.
    //
    //  - The two handles never mark the same position: one character always stays
    //    selected. A handle that is brought exactly onto the other one stops one
    //    character short of it.
    //  - They can pass each other. A start handle dragged beyond the end of the
    //    selection has become its end, and the other way round: the two change
    //    places, and the finger goes on holding "its" end of the selection, which
    //    is now the other handle.
    //
    // Returns the handle the finger holds afterwards.
    private fun moveSelectionHandle(kind: Handle, requestedPosition: Long): Handle {
        var isStart = kind == Handle.START
        val otherKind = if (isStart) Handle.END else Handle.START
        val otherPosition = positionOf(otherKind)

        var position = requestedPosition
        var held = kind
        if (position == otherPosition) {
            // on top of the other one: stay on this side of it
            position = if (isStart) positionBefore(otherPosition) else positionAfter(otherPosition)
        } else if (isStart && position > otherPosition || !isStart && position < otherPosition) {
            // past the other one: this is the other end of the selection now
            val dragged = handleOf(kind)
            val other = handleOf(otherKind)
            dragged.isPressed = false
            dragged.setOffset(0f, 0f)
            other.isPressed = true

            // the handle that stays behind marks what the other one marked
            val (otherX, otherBottom) = getLocationForPosition(lineOf(otherPosition), columnOf(otherPosition))
            if (isStart) {
                range.startLine = lineOf(otherPosition)
                range.startColumn = columnOf(otherPosition)
            } else {
                range.endLine = lineOf(otherPosition)
                range.endColumn = columnOf(otherPosition)
            }
            dragged.moveTo(otherX, otherBottom)

            held = otherKind
            isStart = !isStart
        }

        val line = lineOf(position)
        val column = columnOf(position)
        if (isStart) {
            range.startLine = line
            range.startColumn = column
        } else {
            range.endLine = line
            range.endColumn = column
        }

        val (x, bottom) = getLocationForPosition(line, column)
        handleOf(held).moveTo(x, bottom)
        // the cursor goes where the dragged handle is: that is the point the view
        // keeps in sight while dragging
        cursor.setPosition(line, column)

        noteHandleAt(position)
        return held
    }

    // The dragged handle marks [position] now: a tick of haptic feedback if that is
    // a new place, and it is remembered for the touch-up filter.
    // No isHapticFeedbackEnabled() check needed: performHapticFeedback does it itself.
    private fun noteHandleAt(position: Long) {
        if (position != previousPosition) {
            host.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            addPositionToTouchUpFilter(position)
        }
        previousPosition = position
    }

    // ---- touch-up filter ----

    // takes back a last step the finger made while lifting, see TouchUpFilter
    private val touchUpFilter = TouchUpFilter(
        TOUCH_UP_HISTORY_SIZE, TOUCH_UP_FILTER_DELAY_AFTER_MS, TOUCH_UP_FILTER_DELAY_BEFORE_MS
    )

    private fun startTouchUpFilter(position: Long) {
        touchUpFilter.start(position, SystemClock.uptimeMillis())
    }

    private fun addPositionToTouchUpFilter(position: Long) {
        touchUpFilter.add(position, SystemClock.uptimeMillis())
    }

    private fun filterOnTouchUp(kind: Handle) {
        val position = touchUpFilter.positionOnTouchUp(SystemClock.uptimeMillis()) ?: return
        if (kind == Handle.INSERTION) {
            cursor.setPosition(lineOf(position), columnOf(position))
        } else {
            moveSelectionHandle(kind, position)
        }
    }

    // ---- what popups keep clear of ----

    // the handles of what a popup is about: the two ends of a selection, or the cursor's
    private fun popupHandles(): Array<TextHandle> =
        if (isSelected()) arrayOf(startHandle, endHandle) else arrayOf(insertionHandle)

    /**
     * How far the handles reach above the top of the rows they stand on, in pixels:
     * the start handle the editor draws itself has its knob there. A popup above the
     * selection (the floating bar) stays at least this far from it.
     */
    fun handleReachAbove(): Int =
        popupHandles().maxOf { Math.ceil(it.drawable.reachAboveRow.toDouble()).toInt() }

    /**
     * How far the handles reach below the bottom of the rows they stand on, in pixels
     * (the end and the insertion handle, a start handle flipped below the first row
     * on screen). A popup under the selection stays at least this far from it.
     */
    fun handleReachBelow(): Int =
        popupHandles().maxOf { Math.ceil(it.drawable.reachBelowRow.toDouble()).toInt() }

    // ---- magnifier ----

    /**
     * Where a magnifier should look, in content coordinates.
     *
     * While a handle is dragged it looks at the row the handle's position is on, at
     * the x of the finger: it glides along the row instead of jumping from character
     * to character, and stops at the ends of the row (and at the other selection
     * handle, if that is on the same row). Otherwise it looks at the cursor.
     */
    fun getMagnifierPosition(out: PointF) {
        val lineHeight = metrics.lineHeight
        val kind = pressedHandle()
        if (kind == null) {
            out.set(cursorAnchorX.toFloat(), cursorAnchorBottom - lineHeight / 2f)
            return
        }

        val handle = handleOf(kind)
        val row = Math.max(1, handle.anchorBottom / lineHeight)
        var left = gutterWidth().toFloat()
        var right = lineEndX(row).toFloat()
        if (kind == Handle.END && startHandle.anchorBottom == handle.anchorBottom) {
            left = startHandle.anchorX.toFloat()
        }
        if (kind == Handle.START && endHandle.anchorBottom == handle.anchorBottom) {
            right = endHandle.anchorX.toFloat()
        }
        // a selection handle stands on the text: the magnifier glides with the finger
        // instead; the insertion handle is under the finger itself
        val x = if (kind == Handle.INSERTION) handle.tipX else dragFingerTipX
        out.set(x.coerceIn(left, Math.max(left, right)), handle.anchorBottom - lineHeight / 2f)
    }

    // #endregion

    // #region moving the caret with the keyboard

    // where the moves along the text go (CaretMove is next to it)
    private val navigator = CaretNavigator(object : CaretNavigator.Text {
        override fun getLineCount(): Int = document.getLineCount()
        override fun getLineLength(lineNumber: Int): Int = document.getLineLength(lineNumber)
        override fun getLine(lineNumber: Int): String = document.getLine(lineNumber)
        override fun getLineSegment(lineNumber: Int, startColumn: Int, endColumn: Int): String =
            document.getLineSegment(lineNumber, startColumn, endColumn)
    })

    // The end of a keyboard selection that stays put while the other end (the caret,
    // which is the cursor) moves. Only meaningful while the selection still runs
    // between it and the cursor, see caretOfSelection.
    private var anchorLine = 0
    private var anchorColumn = 0

    // The x the caret tries to keep while it moves up and down through lines of
    // different lengths, and the position it was remembered at: once the caret is
    // anywhere else, it no longer applies.
    private var stickyX = 0
    private var stickyLine = 0
    private var stickyColumn = 0

    /**
     * Moves the caret as a key asks for.
     *
     * @param extendSelection whether the selection follows the caret (Shift is held):
     *        the text between where the caret started and where it is now is selected
     * @param pageHeight how far PAGE_UP / PAGE_DOWN go, in pixels
     *
     * Afterwards the cursor is at the new position, also when it is hidden behind a
     * selection, so the caller can scroll to it. The caller resumes the blink.
     */
    fun moveCaret(move: CaretMove, extendSelection: Boolean, pageHeight: Int) {
        val wasSelected = isSelected()

        // where the caret is, and the fixed end of the selection if it is extended
        if (wasSelected) {
            if (!selectionRunsFromAnchorToCursor()) {
                // a selection made by touch: its start is the fixed end, its end the caret
                anchorLine = range.startLine
                anchorColumn = range.startColumn
                cursor.setPosition(range.endLine, range.endColumn)
            }
        } else {
            anchorLine = cursor.lineNumber
            anchorColumn = cursor.column
        }

        if (wasSelected && !extendSelection) {
            // A plain arrow key on a selection drops it. Left and right just go to
            // the end on that side; everything else moves on from there.
            val towardsStart = move.isTowardsStart
            val startLine = range.startLine
            val startColumn = range.startColumn
            val endLine = range.endLine
            val endColumn = range.endColumn
            range.deselect()
            if (towardsStart) cursor.setPosition(startLine, startColumn) else cursor.setPosition(endLine, endColumn)
            if (move != CaretMove.LEFT && move != CaretMove.RIGHT) {
                applyMove(move, pageHeight)
            }
        } else {
            applyMove(move, pageHeight)
        }

        if (extendSelection && !(cursor.lineNumber == anchorLine && cursor.column == anchorColumn)) {
            // select from the fixed end to the caret, whichever comes first
            val caretFirst = cursor.lineNumber < anchorLine ||
                cursor.lineNumber == anchorLine && cursor.column < anchorColumn
            cursor.stopBlink(false, false)
            if (caretFirst) {
                range.select(cursor.lineNumber, cursor.column, anchorLine, anchorColumn)
            } else {
                range.select(anchorLine, anchorColumn, cursor.lineNumber, cursor.column)
            }
            // which row a position at a wrap point belongs to depends on the selection
            cursor.setPosition(cursor.lineNumber, cursor.column)
        } else {
            range.deselect()
            cursor.setPosition(cursor.lineNumber, cursor.column)
            cursor.stopBlink(true, false)
        }
    }

    private fun selectionRunsFromAnchorToCursor(): Boolean {
        val anchorAtStart = range.startLine == anchorLine && range.startColumn == anchorColumn
        val anchorAtEnd = range.endLine == anchorLine && range.endColumn == anchorColumn
        val cursorAtStart = range.startLine == cursor.lineNumber && range.startColumn == cursor.column
        val cursorAtEnd = range.endLine == cursor.lineNumber && range.endColumn == cursor.column
        return anchorAtStart && cursorAtEnd || anchorAtEnd && cursorAtStart
    }

    // Moves the cursor, and nothing but the cursor: along the text as the navigator
    // says, or up and down by rows on screen.
    private fun applyMove(move: CaretMove, pageHeight: Int) {
        val rowsPerPage = Math.max(1, pageHeight / metrics.lineHeight - 1)
        when (move) {
            CaretMove.UP -> moveVertically(-1)
            CaretMove.DOWN -> moveVertically(1)
            CaretMove.PAGE_UP -> moveVertically(-rowsPerPage)
            CaretMove.PAGE_DOWN -> moveVertically(rowsPerPage)
            else -> navigator.target(move, cursor.lineNumber, cursor.column)?.let {
                cursor.setPosition(it.lineNumber, it.column)
            }
        }
    }

    // Up and down go by rows on screen, not by buffer lines, so that with word wrap
    // the caret moves through a wrapped line the way it looks.
    private fun moveVertically(rows: Int) {
        val lineHeight = metrics.lineHeight
        val (x, bottom) = getLocationForPosition(cursor.lineNumber, cursor.column)
        val row = bottom / lineHeight
        val rowCount = host.textLayout.count()

        // keep to the x the caret had when it started moving up or down
        if (stickyLine != cursor.lineNumber || stickyColumn != cursor.column) {
            stickyX = x
        }

        val targetRow = row + rows
        when {
            // above the first row: the start of the text
            targetRow < 1 -> cursor.setPosition(1, 1)
            // below the last row: the end of the text
            targetRow > rowCount -> {
                val lastLine = document.getLineCount()
                cursor.setPosition(lastLine, document.getLineEndColumn(lastLine))
            }
            // the middle of the target row, at the remembered x
            else -> cursor.setPositionFromPoint(stickyX, (targetRow - 1) * lineHeight + lineHeight / 2)
        }

        stickyLine = cursor.lineNumber
        stickyColumn = cursor.column
    }

    // #endregion

    // #region text cursor

    // Text Cursor
    inner class TextCursor internal constructor() : Position() {
        // Whether the cursor is blinking, and when it was last shown: the blink is a
        // function of the time since then (shown for one interval, hidden for the
        // next). One reusable Runnable (instead of a new lambda per tick) means
        // startBlink() can never stack up two blink loops.
        private var blinking = false
        private var shownAtMillis = 0L

        private val blinkAction = object : Runnable {
            override fun run() {
                val sinceShown = SystemClock.uptimeMillis() - shownAtMillis
                if (sinceShown % (2 * BLINK_INTERVAL_MS) < BLINK_INTERVAL_MS) {
                    host.addOverlay(cursorBar)
                } else {
                    host.removeOverlay(cursorBar)
                }
                host.postDelayed(this, BLINK_INTERVAL_MS)
            }
        }

        fun setPosition(lineNumber: Int, column: Int) {
            this.lineNumber = lineNumber
            this.column = column
            // update the cursor bounds
            val (x, bottom) = getLocationForPosition(lineNumber, column)
            moveCursorBar(x, bottom)
            host.onCursorMoved(lineNumber)
        }

        fun setPosition(pos: Position) {
            setPosition(pos.lineNumber, pos.column)
        }

        fun setPosition(index: Int) {
            setPosition(document.getPosition(index))
        }

        // set position with coordinates
        fun setPositionFromPoint(x: Int, y: Int) {
            val layout = host.textLayout
            val lineHeight = metrics.lineHeight
            // visual line number
            val line = Math.max(y, 0) / lineHeight + 1
            val result = layout.getLineResult(line)
            // here must be real line for current line text
            this.lineNumber = layout.getBufferLine(line)

            val text = document.getLine(lineNumber)
            val gutterWidth = gutterWidth()
            val rowEnd = Math.min(result.end, text.length)
            if (TabStops.hasTab(text, result.start, rowEnd)) {
                // the paint knows nothing of tab stops: go by the widths of the row
                val (offset, advance) = offsetForAdvance(text, result.start, rowEnd, x - gutterWidth.toFloat())
                this.column = offset + 1
                moveCursorBar(
                    x = gutterWidth + advance.toInt(),
                    bottom = Math.min(line, layout.count()) * lineHeight
                )
                return
            }
            val offset = textPaint.getOffsetForAdvance(
                text, result.start, result.end, result.start, result.end, false, x - gutterWidth.toFloat()
            )

            this.column = offset + 1
            moveCursorBar(
                x = gutterWidth + textPaint.getRunAdvance(
                    text, result.start, result.end, result.start, result.end, false, offset
                ).toInt(),
                bottom = Math.min(line, layout.count()) * lineHeight
            )
        }

        // The offset in text[start, end) nearest to [advance] (from the start of the
        // row), and its x: a char is taken once the point is past its middle. The
        // chars of a character after its first have no width of their own: the
        // offset goes past them, never into a character.
        private fun offsetForAdvance(text: String, start: Int, end: Int, advance: Float): Pair<Int, Float> {
            val widths = metrics.getTextWidths(text, start, end)
            var x = 0f
            var offset = start
            while (offset < end) {
                val width = widths[offset - start]
                if (advance < x + width / 2f) break
                x += width
                offset++
                while (offset < end && widths[offset - start] == 0f) offset++
            }
            return offset to x
        }

        /**
         * Shows the cursor and starts the blink from there. Calling it again while
         * blinking starts over: this is what keeps the cursor solid while typing or
         * moving it, and lets it blink only once it has come to rest.
         */
        fun startBlink(delay: Long = BLINK_INTERVAL_MS) {
            host.removeCallbacks(blinkAction)
            shownAtMillis = SystemClock.uptimeMillis()
            blinking = true
            host.addOverlay(cursorBar)
            host.postDelayed(blinkAction, delay)
        }

        /**
         * Stops blinking and leaves the cursor bar shown or hidden. The insertion
         * handle is let appear (it then goes away by itself after a while, unless it
         * is being held) or let go away.
         */
        fun stopBlink(showCursor: Boolean, showInsertionHandle: Boolean) {
            cancelBlinkCallbacks()
            when (showCursor) {
                true -> host.addOverlay(cursorBar)
                else -> host.removeOverlay(cursorBar)
            }
            if (showInsertionHandle) {
                insertionHandle.show()
                if (insertionHandle.isPressed) cancelInsertionHandleHide() else scheduleInsertionHandleHide()
            } else {
                cancelInsertionHandleHide()
                insertionHandle.hide()
            }
        }

        /** Stops blinking without touching what is currently shown (e.g. on detach). */
        fun cancelBlinkCallbacks() {
            host.removeCallbacks(blinkAction)
            blinking = false
        }

        fun isBlinking(): Boolean = blinking

        // get the current offset of cursor in document
        fun getOffset() = document.getOffset(lineNumber, column)

        /** The x of the caret, in content coordinates. */
        fun getX() = cursorAnchorX

        /** The top of the row the caret is on, in content coordinates. */
        fun getY() = cursorAnchorBottom - metrics.lineHeight

        fun updateBlinkForVisibility() {
            // check if the cursor in visible rect
            if (
                this.getX() >= host.viewScrollX &&
                this.getX() <= host.viewScrollX + host.viewportWidth &&
                this.getY() >= host.viewScrollY - metrics.lineHeight &&
                this.getY() <= host.viewScrollY + host.viewportHeight
            ) {
                // (a selection hides the cursor: it does not come back by being scrolled to)
                if (!isBlinking() && !isSelected()) {
                    // cursor start blink
                    startBlink()
                }
            } else {
                stopBlink(false, false)
            }
        }
    }

    // #endregion

    // #region text select handle

    // Text Selector
    inner class TextSelector internal constructor() : Range() {

        fun select(
            startLineNumber: Int,
            startColumn: Int,
            endLineNumber: Int,
            endColumn: Int
        ) {
            this.startLine = startLineNumber
            this.startColumn = startColumn
            this.endLine = endLineNumber
            this.endColumn = endColumn

            // check the select range
            if (startLine != endLine || startColumn != endColumn) {
                val (startX, startBottom) = getLocationForPosition(startLine, startColumn)
                startHandle.moveTo(startX, startBottom)

                val (endX, endBottom) = getLocationForPosition(endLine, endColumn)
                endHandle.moveTo(endX, endBottom)

                // (not yet, if the finger that is making the selection is still down)
                if (!handlesAwaitRelease) {
                    startHandle.show()
                    endHandle.show()
                }
            }
        }

        fun select(range: Range) {
            this.select(
                range.startLine,
                range.startColumn,
                range.endLine,
                range.endColumn
            )
        }

        /** Drops the selection and lets both handles go away. */
        fun deselect() {
            handlesAwaitRelease = false
            startHandle.hide()
            endHandle.hide()
            this.select(1, 1, 1, 1)
        }

        /** Swaps start and end, both in the document and on screen. */
        fun swapEnds() {
            val startX = startHandle.anchorX
            val startBottom = startHandle.anchorBottom
            startHandle.moveTo(endHandle.anchorX, endHandle.anchorBottom)
            endHandle.moveTo(startX, startBottom)

            // startLine <-> endLine
            val line = startLine
            startLine = endLine
            endLine = line

            // startColumn <-> endColumn
            val column = startColumn
            startColumn = endColumn
            endColumn = column
        }

        fun isOrdered() = !(startLine > endLine || startLine == endLine && startColumn > endColumn)

        /** Whether the content point ([x], [y]) lies on selected text. */
        fun containsPoint(x: Int, y: Int): Boolean {
            if (!isSelected()) {
                return false
            }

            // where the selection starts and ends on screen: the x, and the bottom of the row
            val lineHeight = metrics.lineHeight
            val startX = startHandle.anchorX
            val startBottom = startHandle.anchorBottom
            val endX = endHandle.anchorX
            val endBottom = endHandle.anchorBottom

            if (y < startBottom - lineHeight || y > endBottom) {
                return false
            }

            // on the same row
            if (startBottom == endBottom) {
                return x >= startX && x <= endX
            }

            // on several rows
            val row = y / lineHeight + 1
            val gutterWidth = gutterWidth()
            return when (row * lineHeight) {
                // the row the selection starts on
                startBottom -> x >= startX && x <= lineEndX(row)
                // the row it ends on
                endBottom -> x >= gutterWidth && x <= endX
                // a row in between
                else -> x >= gutterWidth && x <= lineEndX(row)
            }
        }
    }

    // #endregion
}
