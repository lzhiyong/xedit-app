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
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import androidx.core.content.res.ResourcesCompat

import x.github.module.editor.R
import x.github.module.editor.util.isNonBreakingChar
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range

/**
 * Owns the caret and the selection: where they are in the document ([cursor], [range]),
 * the four drawables that show them (cursor bar, insertion handle, the two selection
 * handles), the cursor blink, and what happens when one of the handles is pressed,
 * dragged and released.
 *
 * All coordinates passed in are *content* coordinates (already offset by scroll and
 * padding). This class never scrolls, never shows a menu or a magnifier and never looks
 * at MotionEvents: it reports what happened and lets the caller decide what follows.
 */
class SelectionController(
    context: Context,
    private val host: SelectionController.Host,
    // only used to convert between a pixel advance and a character offset within a line
    private val textPaint: TextPaint
) {

    /**
     * On top of geometry and text: the layout that maps positions to screen lines, a
     * few line measurements, haptics, and delayed callbacks for the blink.
     * performHapticFeedback/postDelayed/removeCallbacks are inherited from View as-is.
     */
    interface Host : EditorGeometry, EditorTextSource {
        val textLayout: TextLayout

        fun getGutterWidth(): Int
        fun getLineEndX(lineNumber: Int): Int
        fun getLineRangeWidth(lineNumber: Int, startColumn: Int, endColumn: Int): Int
        fun getLineCharAt(lineNumber: Int, offset: Int): Char
        fun getLineStartColumn(lineNumber: Int): Int
        fun getLineEndColumn(lineNumber: Int = getLineCount()): Int
        fun getOffset(lineNumber: Int, column: Int): Int
        fun getPosition(offset: Int): Position
        fun findWordBoundary(text: String, offset: Int): IntArray

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
        private const val BLINK_INTERVAL_MS = 500L
        // how long the insertion handle stays under a blinking cursor before it hides
        private const val INSERTION_HANDLE_TIMEOUT_MS = 2500L
    }

    private val cursorBar = loadDrawable(context, R.drawable.ic_text_cursor_material)
    private val startHandle = loadDrawable(context, R.drawable.ic_text_select_handle_left)
    private val endHandle = loadDrawable(context, R.drawable.ic_text_select_handle_right)
    private val insertionHandle = loadDrawable(context, R.drawable.ic_text_select_handle_middle)

    /** The caret position. Also drives the cursor bar drawable and its blinking. */
    val cursor = TextCursor()

    /** The selected range. Empty (1,1,1,1) when nothing is selected. */
    val range = TextSelector()

    /** Bounds of the selection start handle, for painting the selection background. */
    val startHandleBounds: Rect get() = startHandle.bounds

    /** Bounds of the selection end handle, for painting the selection background. */
    val endHandleBounds: Rect get() = endHandle.bounds

    fun isSelected() = !range.isEmpty()

    // #region position <-> pixels

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
                if (!host.getLineCharAt(lineNumber, result.start - 1).isNonBreakingChar()) {
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
            host.getGutterWidth() + host.getLineRangeWidth(lineNumber, start, column),
            line * host.getLineHeight()
        )
    }

    fun getLocationForPosition(pos: Position): IntArray {
        return getLocationForPosition(pos.lineNumber, pos.column)
    }

    /**
     * Re-derives every drawable's bounds from the document positions. Call after
     * anything that moves text on screen without moving the cursor (text size,
     * typeface, relayout).
     */
    fun updateDrawableBounds() {
        // update the cursor drawable bounds
        val (x, y) = getLocationForPosition(cursor)
        cursorBar.updateLocation(x, y)
        placeInsertionHandle()

        // update the select handle bounds
        if (isSelected()) {
            val (startX, startY) = getLocationForPosition(range.getStartPosition())
            startHandle.updateLocation(startX, startY)

            val (endX, endY) = getLocationForPosition(range.getEndPosition())
            endHandle.updateLocation(endX, endY)
        }
    }

    // puts the insertion handle right below the cursor bar
    private fun placeInsertionHandle() {
        insertionHandle.updateLocation(cursor.getX(), cursor.getY() + host.getLineHeight())
    }

    // update drawable bounds
    private fun Drawable.updateLocation(x: Int, y: Int) {
        when (this) {
            cursorBar -> setBounds(
                x - bounds.width() / 2,
                y - host.getLineHeight(),
                x + bounds.width() / 2,
                y
            )
            insertionHandle -> setBounds(
                x - bounds.width() / 2,
                y,
                x + bounds.width() / 2,
                y + bounds.height()
            )
            startHandle -> setBounds(
                x - bounds.width(),
                y,
                x,
                y + bounds.height()
            )
            endHandle -> setBounds(
                x,
                y,
                x + bounds.width(),
                y + bounds.height()
            )
        }
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
        placeInsertionHandle()
    }

    /**
     * A long press on the text: moves the cursor there and selects the word under it.
     *
     * @return true if a word was selected, false if there was no word at that point
     *         (in which case any previous selection is dropped)
     */
    fun selectWordAt(x: Int, y: Int): Boolean {
        cursor.setPositionFromPoint(x, y)
        val (startOffset, endOffset) = host.findWordBoundary(
            host.getLine(cursor.lineNumber),
            cursor.column - 1
        )

        if (startOffset == endOffset) {
            // no selected
            range.deselect()
            return false
        }

        // has text selected
        range.select(
            cursor.lineNumber, startOffset + 1,
            cursor.lineNumber, endOffset + 1,
        )
        cursor.stopBlink(false, false)
        return true
    }

    // #endregion

    // #region handle press / drag / release

    // Only the state_pressed flag is added/removed; any other state the drawable's
    // selector might carry is preserved. Same helper as in ScrollbarController, kept
    // private to each so neither depends on the other.
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

    // Each handle is easier to grab than its visual bounds suggest, and the extra
    // margin is on the side(s) facing away from the text it is attached to.
    // A handle that is not currently shown can never be hit.
    private fun Drawable.containsTouch(x: Int, y: Int): Boolean {
        val inside = when (this) {
            insertionHandle -> {
                x >= bounds.left - bounds.width() / 2 &&
                x <= bounds.right + bounds.width() / 2 &&
                y >= bounds.top - bounds.height() / 2 &&
                y <= bounds.bottom + bounds.height() / 2
            }
            startHandle -> {
                x >= bounds.left - bounds.width() / 2 &&
                x <= bounds.right &&
                y >= bounds.top - bounds.height() / 2 &&
                y <= bounds.bottom + bounds.height() / 2
            }
            endHandle -> {
                x >= bounds.left &&
                x <= bounds.right + bounds.width() / 2 &&
                y >= bounds.top - bounds.height() / 2 &&
                y <= bounds.bottom + bounds.height() / 2
            }
            else -> false
        }
        return inside && host.hasOverlay(this)
    }

    private fun drawableOf(handle: Handle): Drawable = when (handle) {
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

    /** Hit-test only, no state change. Returns the handle at that point, if any. */
    fun hitTest(x: Int, y: Int): Handle? = when {
        insertionHandle.containsTouch(x, y) -> Handle.INSERTION
        startHandle.containsTouch(x, y) -> Handle.START
        endHandle.containsTouch(x, y) -> Handle.END
        else -> null
    }

    /** Half the width of [handle]: how far its tip is from the finger holding it. */
    fun handleHalfWidth(handle: Handle): Int = drawableOf(handle).bounds.width() / 2

    /**
     * Call from onDown, before anything else gets a chance at the touch. Returns true
     * if a handle was pressed, in which case the touch belongs to it.
     */
    fun onDown(x: Int, y: Int): Boolean {
        when (hitTest(x, y)) {
            Handle.INSERTION -> {
                insertionHandle.isPressed = true
                cursor.stopBlink(true, true)
            }
            Handle.START -> {
                startHandle.isPressed = true
                cursor.stopBlink(false, false)
            }
            Handle.END -> {
                endHandle.isPressed = true
                cursor.stopBlink(false, false)
            }
            null -> return false
        }
        return true
    }

    /**
     * Call from onScroll (drag). Moves whichever handle is pressed to follow the
     * finger at ([x], [y]) and updates the cursor / selection accordingly.
     *
     * @return the handle the finger was holding, or null if no handle is pressed and
     *         the drag is for someone else. Scrolling the dragged handle into view is
     *         up to the caller; the point to keep visible is the [cursor].
     */
    fun onDrag(x: Int, y: Int): Handle? {
        val dragged = pressedHandle() ?: return null

        when (dragged) {
            Handle.INSERTION -> dragInsertionHandle(x, y)
            Handle.START -> dragStartHandle(x, y)
            Handle.END -> dragEndHandle(x, y)
        }

        if (!range.isOrdered()) {
            // the handles crossed each other: swap them, and the finger now holds the other one
            range.swapEnds()
            startHandle.isPressed = !startHandle.isPressed
            endHandle.isPressed = !endHandle.isPressed
        }

        return dragged
    }

    /**
     * Call from onUp. Releases whichever handle is pressed and returns it, or null if
     * no handle was pressed.
     */
    fun onUp(): Handle? {
        val released = pressedHandle() ?: return null
        drawableOf(released).isPressed = false
        return released
    }

    private fun dragInsertionHandle(x: Int, y: Int) {
        val lineHeight = host.getLineHeight()
        // the cursor position changed before
        val prevLineNumber = cursor.lineNumber
        val prevColumn = cursor.column

        if (y < cursor.getY() + lineHeight)
            cursor.setPositionFromPoint(x, y - lineHeight)
        else if (y > cursor.getY() + lineHeight * 2)
            cursor.setPositionFromPoint(x, y - lineHeight * 2)
        else
            cursor.setPositionFromPoint(x, cursor.getY())

        // update the insertion handle bounds
        placeInsertionHandle()

        notifyIfCursorMoved(prevLineNumber, prevColumn)
    }

    private fun dragStartHandle(x: Int, y: Int) {
        val lineHeight = host.getLineHeight()
        val halfWidth = startHandle.bounds.width() / 2
        val halfHeight = startHandle.bounds.height() / 2

        // startColumn is smaller than endColumn 1
        if (!(
            range.startColumn + 1 >= range.endColumn &&
                range.startLine == range.endLine && x + halfWidth > startHandle.bounds.right &&
                Math.abs(y - startHandle.bounds.centerY()) < lineHeight
            )
        ) {
            // the cursor position changed before
            val prevLineNumber = cursor.lineNumber
            val prevColumn = cursor.column

            // check the start handle bounds
            if (y < startHandle.bounds.centerY() - lineHeight) {
                cursor.setPositionFromPoint(x + halfWidth, y - halfHeight)
            } else if (y > startHandle.bounds.centerY() + lineHeight) {
                cursor.setPositionFromPoint(x + halfWidth, y - halfHeight - lineHeight)
            } else {
                cursor.setPositionFromPoint(x + halfWidth, cursor.getY())
            }

            range.startLine = cursor.lineNumber
            range.startColumn = cursor.column

            // update the start handle bounds
            startHandle.updateLocation(cursor.getX(), cursor.getY() + lineHeight)

            notifyIfCursorMoved(prevLineNumber, prevColumn)

        } else if (x >= endHandle.bounds.left + halfWidth) {
            // on the same line, no need to check startLine == endLine
            startHandle.isPressed = false
            endHandle.isPressed = true
        }

        // not allow at the same point
        if (range.startLine == range.endLine && range.startColumn == range.endColumn) {
            val maxColumn = host.getLineEndColumn(range.endLine)
            if (maxColumn > 1) {
                if (range.endColumn != maxColumn) {
                    range.endColumn++
                } else {
                    range.endColumn--
                }
            } else {
                range.endColumn = host.getLineEndColumn(--range.endLine)
            }

            // update the end handle bounds
            endHandle.updateLocation(
                host.getGutterWidth() + host.getLineRangeWidth(range.endLine, 1, range.endColumn),
                range.endLine * lineHeight
            )
        }
    }

    private fun dragEndHandle(x: Int, y: Int) {
        val lineHeight = host.getLineHeight()
        val halfWidth = endHandle.bounds.width() / 2
        val halfHeight = endHandle.bounds.height() / 2

        // endColumn is bigger than startColumn 1
        if (!(
            range.endColumn - 1 <= range.startColumn &&
                range.startLine == range.endLine && x - halfWidth < endHandle.bounds.left &&
                Math.abs(y - endHandle.bounds.centerY()) < lineHeight
            )
        ) {
            // the cursor position changed before
            val prevLineNumber = cursor.lineNumber
            val prevColumn = cursor.column

            // check the end handle bounds
            if (y < endHandle.bounds.centerY() - lineHeight) {
                cursor.setPositionFromPoint(x - halfWidth, y - halfHeight)
            } else if (y > endHandle.bounds.centerY() + lineHeight) {
                cursor.setPositionFromPoint(x - halfWidth, y - halfHeight - lineHeight)
            } else {
                cursor.setPositionFromPoint(x - halfWidth, cursor.getY())
            }

            range.endLine = cursor.lineNumber
            range.endColumn = cursor.column

            // update the end handle bounds
            endHandle.updateLocation(cursor.getX(), cursor.getY() + lineHeight)

            notifyIfCursorMoved(prevLineNumber, prevColumn)

        } else if (x <= startHandle.bounds.right - halfWidth) {
            // on the same line, no need to check startLine == endLine
            startHandle.isPressed = true
            endHandle.isPressed = false
        }

        // not allow at the same point
        if (range.endLine == range.startLine && range.endColumn == range.startColumn) {
            val maxColumn = host.getLineEndColumn(range.startLine)
            if (maxColumn > 1) {
                if (range.startColumn != 1) {
                    range.startColumn--
                } else {
                    range.startColumn++
                }
            } else {
                range.startColumn = host.getLineStartColumn(++range.startLine)
            }

            // update the start handle bounds
            startHandle.updateLocation(
                host.getGutterWidth() + host.getLineRangeWidth(range.startLine, 1, range.startColumn),
                range.startLine * lineHeight
            )
        }
    }

    // A tick of haptic feedback each time a dragged handle snaps to a new character.
    // No isHapticFeedbackEnabled() check needed: performHapticFeedback does it itself.
    private fun notifyIfCursorMoved(prevLineNumber: Int, prevColumn: Int) {
        if (prevLineNumber != cursor.lineNumber || prevColumn != cursor.column) {
            host.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
    }

    // #endregion

    // #region text cursor

    // Text Cursor
    inner class TextCursor internal constructor() : Position() {

        // Whether a blink is scheduled. One reusable Runnable (instead of a new lambda
        // per tick) means startBlink() can never stack up two blink loops.
        private var blinking = false
        private var blinkStartMillis = 0L
        private var blinkDelayMillis = BLINK_INTERVAL_MS

        private val blinkAction = object : Runnable {
            override fun run() {
                // toggle the cursor visible state
                if (host.hasOverlay(cursorBar)) {
                    host.removeOverlay(cursorBar)

                    // check insertion handle state
                    if (SystemClock.uptimeMillis() - blinkStartMillis > INSERTION_HANDLE_TIMEOUT_MS) {
                        host.removeOverlay(insertionHandle)
                    }
                } else {
                    host.addOverlay(cursorBar)
                }

                // start continue blink
                host.postDelayed(this, blinkDelayMillis)
            }
        }

        fun setPosition(lineNumber: Int, column: Int) {
            this.lineNumber = lineNumber
            this.column = column
            // update the cursor bounds
            val (x, y) = getLocationForPosition(lineNumber, column)
            cursorBar.updateLocation(x, y)
        }

        fun setPosition(pos: Position) {
            setPosition(pos.lineNumber, pos.column)
        }

        fun setPosition(index: Int) {
            setPosition(host.getPosition(index))
        }

        // set position with coordinates
        fun setPositionFromPoint(x: Int, y: Int) {
            val layout = host.textLayout
            val lineHeight = host.getLineHeight()
            // visual line number
            val line = Math.max(y, 0) / lineHeight + 1
            val result = layout.getLineResult(line)
            // here must be real line for current line text
            this.lineNumber = layout.getBufferLine(line)
            val text = host.getLine(lineNumber)

            val gutterWidth = host.getGutterWidth()
            val offset = textPaint.getOffsetForAdvance(
                text, result.start, result.end, result.start, result.end, false, x - gutterWidth.toFloat()
            )
            this.column = offset + 1

            cursorBar.updateLocation(
                x = gutterWidth + textPaint.getRunAdvance(
                    text, result.start, result.end, result.start, result.end, false, offset
                ).toInt(),
                y = Math.min(line, layout.count()) * lineHeight
            )
        }

        fun startBlink(delay: Long = BLINK_INTERVAL_MS) {
            host.removeCallbacks(blinkAction)
            blinkStartMillis = SystemClock.uptimeMillis()
            blinkDelayMillis = delay
            blinking = true
            host.postDelayed(blinkAction, delay)
        }

        /** Stops blinking and leaves the cursor bar / insertion handle shown or hidden. */
        fun stopBlink(showCursor: Boolean, showInsertionHandle: Boolean) {
            cancelBlinkCallbacks()

            when (showCursor) {
                true -> host.addOverlay(cursorBar)
                else -> host.removeOverlay(cursorBar)
            }

            when (showInsertionHandle) {
                true -> host.addOverlay(insertionHandle)
                else -> host.removeOverlay(insertionHandle)
            }
        }

        /** Stops blinking without touching what is currently shown (e.g. on detach). */
        fun cancelBlinkCallbacks() {
            host.removeCallbacks(blinkAction)
            blinking = false
        }

        fun isBlinking(): Boolean = blinking

        // get the current offset of cursor in document
        fun getOffset() = host.getOffset(lineNumber, column)

        fun getX() = cursorBar.run {
            bounds.left + bounds.width() / 2
        }

        fun getY() = cursorBar.run {
            bounds.bottom - bounds.height()
        }

        fun updateBlinkForVisibility() {
            // check if the cursor in visible rect
            if (
                this.getX() >= host.viewScrollX &&
                this.getX() <= host.viewScrollX + host.viewWidth &&
                this.getY() >= host.viewScrollY - host.getLineHeight() &&
                this.getY() <= host.viewScrollY + host.viewHeight
            ) {
                if (!isBlinking()) {
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
                val (startX, startY) = getLocationForPosition(startLine, startColumn)
                startHandle.updateLocation(startX, startY)

                val (endX, endY) = getLocationForPosition(endLine, endColumn)
                endHandle.updateLocation(endX, endY)

                host.addOverlay(startHandle)
                host.addOverlay(endHandle)
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

        /** Drops the selection and hides both handles. */
        fun deselect() {
            host.removeOverlay(startHandle)
            host.removeOverlay(endHandle)
            this.select(1, 1, 1, 1)
        }

        /** Swaps start and end, both in the document and on screen. */
        fun swapEnds() {
            // copy the left bounds
            val oldStartBounds = startHandle.copyBounds()

            // left bounds = right bounds
            startHandle.updateLocation(
                endHandle.bounds.left, endHandle.bounds.top
            )

            // right bounds = left bounds
            endHandle.updateLocation(
                oldStartBounds.right, oldStartBounds.top
            )

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

            val lineHeight = host.getLineHeight()

            if (
                y < startHandle.bounds.top - lineHeight ||
                y > endHandle.bounds.top
            ) {
                return false
            }

            // on the same line
            if (startHandle.bounds.top == endHandle.bounds.top) {
                if (x < startHandle.bounds.right || x > endHandle.bounds.left) {
                    return false
                }
            } else {
                val gutterWidth = host.getGutterWidth()
                // not on the same line
                val line = y / lineHeight + 1
                // at select start line
                if (line == startHandle.bounds.top / lineHeight) {
                    if (x < startHandle.bounds.right || x > host.getLineEndX(line)) {
                        return false
                    }
                } else if (line == endHandle.bounds.top / lineHeight) {
                    // at select end line
                    if (x < gutterWidth || x > endHandle.bounds.left) {
                        return false
                    }
                } else {
                    // less left edge or more than right edge
                    if (x < gutterWidth || x > host.getLineEndX(line)) {
                        return false
                    }
                }
            }
            return true
        }
    }

    // #endregion

    private fun loadDrawable(context: Context, resId: Int): Drawable {
        return ResourcesCompat.getDrawable(context.resources, resId, null)!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
    }
}
