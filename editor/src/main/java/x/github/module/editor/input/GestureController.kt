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
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector

import x.github.module.piecetable.common.Range

import x.github.module.editor.input.SelectionController.Handle

/**
 * Turns raw touch events into editor actions. It owns the two gesture detectors and the
 * little per-gesture state they need, and is the one place that decides *who* a touch
 * belongs to, always in the same order:
 *
 *   1. a cursor / selection handle   -> [SelectionController]
 *   2. a scrollbar thumb             -> [ScrollbarController]
 *   3. the text itself               -> cursor placement, word selection, or scrolling
 *                                       through [ScrollController]
 *
 * It contains no geometry and no drawing of its own: every branch ends in a call to one
 * of the three controllers or to a [Host] method named after the intent (show the
 * keyboard, show the action mode, ...), which EditorView carries out.
 *
 * A finger that is selecting (dragging a handle, or extending what a long press
 * selected) scrolls the view when it is held near an edge: see [EdgeAutoScroller].
 */
class GestureController(
    context: Context,
    private val host: GestureController.Host,
    private val selection: SelectionController,
    private val scrollbars: ScrollbarController,
    private val scroll: ScrollController
) : GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener {

    /**
     * What a gesture can ask the editor to do. Only invalidate, performHapticFeedback
     * and scrollBy are inherited from View as-is; the rest is implemented by EditorView.
     */
    interface Host : EdgeAutoScroller.Host {
        // left/top padding: touch coordinates are relative to the view, content is not
        val contentPaddingLeft: Int
        val contentPaddingTop: Int

        fun isEditable(): Boolean
        fun invalidate()
        fun performHapticFeedback(feedbackConstant: Int): Boolean

        fun focusEditor()
        fun showSoftKeyboard()
        fun showSelectionMenu()
        fun dismissSelectionMenu()
        /** Shows (or moves) the magnifier over the cursor. */
        fun showMagnifier()
        fun dismissMagnifier()

        /** The cursor was just placed by a tap: bring it into view. */
        fun onCursorTapped()
        /**
         * A long press selected text and the finger lifted: bring it into view the
         * way a tap brings the cursor. The word may have been pressed at an edge of
         * the screen, half off it or under the keyboard.
         */
        fun revealSelection()
        /**
         * A dragged handle was let go and springs back to its place on the text:
         * bring that place into view. Held beside the text while the view scrolled
         * on at an edge, the handle may have left it far off screen.
         */
        fun revealHandle(handle: Handle)

        /**
         * How much of the viewport on the left is a gutter that stays put while the
         * text scrolls under it; 0 if there is none. Touches there are not on text.
         */
        val textInsetLeft: Int

        /**
         * Scrolls sideways at once (no animation) so that the content x [x] can be
         * seen, showing as much of what is left of it as fits. Returns whether the
         * view moved.
         */
        fun revealX(x: Int): Boolean

        /**
         * A tap on the gutter, on the row at content y [y], [x] pixels from the left
         * edge of the gutter (a fold marker, or a line number). Returns true if it was
         * taken care of (a listener of the app's); false lets it go on as a tap on
         * the text at the start of what can be seen of that row.
         */
        fun onGutterTapped(x: Int, y: Int): Boolean

        /**
         * A tap at content [x], [y], before anything else looks at it: on a fold
         * marker (in the gutter, or next to the line number in the rows) or on the
         * placeholder of folded lines, it folds or unfolds. Returns true if it did.
         */
        fun onFoldMarkerTapped(x: Int, y: Int): Boolean

        // pinch zoom, i.e. changing the text size
        fun onZoomBegin()
        fun onZoom(scaleFactor: Float, focusX: Float, focusY: Float)
        fun onZoomEnd()
    }

    private val gestureDetector = GestureDetector(context, this).apply {
        setIsLongpressEnabled(true)
        setOnDoubleTapListener(this@GestureController)
    }
    private val scaleGestureDetector = ScaleGestureDetector(context, this)

    // Scrolls while a finger that is selecting rests at an edge; towards text only:
    // not on past the end of the row being selected on, nor below the last line.
    private val autoScroller = EdgeAutoScroller(
        context,
        host,
        object : EdgeAutoScroller.Limits {
            override fun textEndX(): Int = selection.dragRowEndX()
            override fun textEndY(): Int = selection.textBottom()
            override val textInsetLeft: Int get() = host.textInsetLeft
        }
    ) { onAutoScrolled() }

    // where that finger is, in view coordinates, as of its last move
    private var selectingFingerX = 0f
    private var selectingFingerY = 0f

    /**
     * How fast the view scrolls when a finger that is selecting is held at an edge,
     * in lines per second. 0 switches it off.
     */
    var autoScrollSpeed: Float
        get() = autoScroller.speed
        set(value) {
            autoScroller.speed = value
        }

    // What the finger took hold of when it came down. It holds on to that for the
    // whole gesture: a drag that started on a scrollbar thumb stays a thumb drag.
    private enum class Grab { NOTHING, HANDLE, SCROLLBAR }
    private var grab = Grab.NOTHING

    // a long press was recognized and the finger is still down
    private var isLongPressed = false
    // This gesture turned into a long press on the text (not on a handle or a
    // scrollbar). From then on it is about what was pressed, a word, a menu; the
    // finger trembling or rolling off the screen must not scroll or fling the text.
    private var wasLongPressOnText = false
    // the current selection was made by that long press (action mode shows on release)
    private var isSelectedWord = false

    /** Whether a pinch is in progress. Scrolling caused by it should not show scrollbars. */
    val isScaling: Boolean get() = scaleGestureDetector.isInProgress

    /** Call from View.onTouchEvent. Always consumes the event. */
    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                scroll.abortAnimation()
            }
            MotionEvent.ACTION_MOVE -> {
                if (this.isLongPressed) {
                    // cancel the long press event
                    this.isLongPressed = false
                    e.setAction(MotionEvent.ACTION_CANCEL)
                }
            }
            MotionEvent.ACTION_UP -> { onUp() }
            // taken away by a parent: whatever was being dragged stays where it is
            MotionEvent.ACTION_CANCEL -> autoScroller.stop()
        }

        gestureDetector.onTouchEvent(e)
        scaleGestureDetector.onTouchEvent(e)
        return true
    }

    // touch position -> content coordinates
    private fun contentX(e: MotionEvent) = e.x.toInt() + host.viewScrollX - host.contentPaddingLeft

    // Whether the touch is on a gutter that stays put. The text that has scrolled
    // under it cannot be seen, so a touch there is not on that text.
    private fun isOnGutter(e: MotionEvent): Boolean {
        val inset = host.textInsetLeft
        return inset > 0 && e.x - host.contentPaddingLeft < inset
    }

    // the content x of the touch, moved out from under a gutter to the first x of
    // text that can be seen
    private fun textX(e: MotionEvent): Int =
        Math.max(contentX(e), host.viewScrollX + host.textInsetLeft)
    private fun contentY(e: MotionEvent) = e.y.toInt() + host.viewScrollY - host.contentPaddingTop

    // #region GestureDetectorListener

    // #region double tap: the floating menu

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

    override fun onDoubleTap(e: MotionEvent): Boolean = false

    // The floating menu comes when the second tap is lifted: its down went through onDown,
    // which takes the menu away (onDown comes after onDoubleTap), and onUp, which comes
    // before this, leaves it. Not on the gutter (fold markers) nor on a scrollbar.
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return false
        if (grab != Grab.NOTHING || isOnGutter(e) || scrollbars.hitTest(contentX(e), contentY(e))) return false
        host.showSelectionMenu()
        return true
    }

    // #endregion

    override fun onDown(e: MotionEvent): Boolean {
        val x = contentX(e)
        val y = contentY(e)

        // handles first, then scrollbars
        grab = when {
            selection.onDown(x, y) -> Grab.HANDLE
            scrollbars.onDown(x, y) -> Grab.SCROLLBAR
            else -> Grab.NOTHING
        }
        wasLongPressOnText = false
        autoScroller.stop()

        scroll.onDown()
        host.focusEditor()
        host.dismissSelectionMenu()
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        var x = contentX(e)
        val y = contentY(e)
        // A tap is on a handle only if it is on its knob. The line of text a handle
        // stands on is there to start a drag from; a tap on it is a tap on the text.
        val handle = if (selection.isOnKnob(x, y)) selection.hitTest(x, y) else null

        // A fold marker, or the placeholder of folded lines: fold or unfold.
        if (handle == null && !scrollbars.hitTest(x, y) && host.onFoldMarkerTapped(x, y)) {
            onUp()
            return true
        }

        // On the gutter: the app may want it (a breakpoint); if not, it is a tap on
        // the row, at the start of what can be seen of it.
        if (handle == null && !scrollbars.hitTest(x, y) && isOnGutter(e)) {
            // x from the left edge of the gutter, which stays put: the fold markers
            // are in a column of their own at its right
            if (host.onGutterTapped(e.x.toInt() - host.contentPaddingLeft, y)) {
                onUp()
                return true
            }
            x = textX(e)
        }

        when {
            handle == Handle.INSERTION || scrollbars.hitTest(x, y) -> {
                return true
            }
            handle != null || selection.range.containsPoint(x, y) -> {
                // here, requires set cusor position
                // the action mode location depend on cursor position
                selection.cursor.setPositionFromPoint(x, y)
                host.showSoftKeyboard()
                return true
            }
            !host.isEditable() -> return true
        }

        selection.placeCursorAt(x, y)

        // perform haptic feedback
        host.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        // update position
        host.onCursorTapped()
        // popup ime
        host.showSoftKeyboard()
        // call ACTION_UP event
        onUp()

        host.invalidate()
        return true
    }

    override fun onShowPress(e: MotionEvent) {
        // TODO
    }

    override fun onLongPress(e: MotionEvent) {
        // (the text under a gutter that stays put cannot be pressed)
        val x = if (grab == Grab.NOTHING) textX(e) else contentX(e)
        val y = contentY(e)

        this.isLongPressed = true

        // Holding a scrollbar thumb still for a moment is not a long press on the
        // text: no buzz, nothing to select. (isLongPressed is still set, because
        // that is what lets the drag go on once the finger moves again.)
        if (grab == Grab.SCROLLBAR) {
            return
        }
        // A handle can be taken hold of by the line of text it stands on, not only by
        // its knob, so that it is easy to drag. But a finger that stays put there is
        // not dragging anything: unless it is on the knob itself, it is pressing the
        // text, and gets what a long press on text gets.
        if (grab == Grab.HANDLE && !selection.isOnKnob(x, y)) {
            selection.cancelPress()
            grab = Grab.NOTHING
        }
        if (grab == Grab.NOTHING) {
            wasLongPressOnText = true
        }

        // perform haptic feedback
        host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        when {
            scrollbars.hitTest(x, y) -> {
                return
            }
            // held by its knob: the handle is being held, not the text
            grab == Grab.HANDLE -> {
                host.showMagnifier()
                return
            }
            selection.range.containsPoint(x, y) -> {
                // here, requires set cusor position
                // for set the action mode location
                selection.cursor.setPositionFromPoint(x, y)
                host.showSelectionMenu()
                return
            }
        }

        if (selection.selectWordAt(x, y)) {
            // has text selected
            isSelectedWord = true
            host.showMagnifier()
        } else if (host.isEditable()) {
            // no selected
            selection.cursor.stopBlink(true, false)
            // resume blink
            selection.cursor.startBlink()
            host.showSelectionMenu()
        }

        host.invalidate()
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val dragged = selection.onDrag(contentX(e2), contentY(e2))

        when {
            dragged != null -> {
                // held at an edge, the view scrolls on and the handle goes with it
                followAtEdges(e2)
                keepDragPositionInView()
                // update the magnifier location
                host.showMagnifier()
            }
            // dragging a scrollbar thumb: it already scrolled the view itself
            scrollbars.onDrag(e2.x - host.contentPaddingLeft, e2.y - host.contentPaddingTop) -> { }
            // After a long press on the text the finger does not drag the content.
            // If the long press selected a word, the selection goes with the finger
            // instead, as if it were dragging a handle; and like a dragged handle,
            // held at an edge it scrolls the view to go on selecting there.
            wasLongPressOnText -> {
                if (selection.onLongPressDrag(contentX(e2), contentY(e2))) {
                    followAtEdges(e2)
                    keepDragPositionInView()
                    host.showMagnifier()
                }
            }
            // dragging the content
            else -> scroll.dragBy(distanceX, distanceY, e2.x, e2.y)
        }

        host.invalidate()
        return true
    }

    // A finger that is selecting moved: near an edge, the view scrolls.
    private fun followAtEdges(e: MotionEvent) {
        selectingFingerX = e.x
        selectingFingerY = e.y
        autoScroller.update(e.x - host.contentPaddingLeft, e.y - host.contentPaddingTop)
    }

    // The view auto scrolled under a finger that holds still: the text under it is
    // other text now, and the selection goes on to it, as if the finger had moved.
    private fun onAutoScrolled() {
        if (dragAtFinger()) {
            keepDragPositionInView()
            host.showMagnifier()
        } else {
            // nothing is being selected any more
            autoScroller.stop()
        }
        host.invalidate()
    }

    // Carries the drag going on (a handle, or a long press selecting on) on at the
    // place on screen where the finger last was. Returns false if there is none.
    private fun dragAtFinger(): Boolean {
        val x = selectingFingerX.toInt() + host.viewScrollX - host.contentPaddingLeft
        val y = selectingFingerY.toInt() + host.viewScrollY - host.contentPaddingTop
        return selection.onDrag(x, y) != null || selection.onLongPressDrag(x, y)
    }

    // guards keepDragPositionInView against calling itself through dragAtFinger
    private var isRevealingDragPosition = false

    // A drag went onto a row whose text cannot be seen at all: a short line, after
    // the view was scrolled sideways for a long one. The position is at the end of
    // that line, off to the left, and the handle hangs in the blank under the
    // finger. The view jumps sideways to show the line, and the drag goes on at the
    // finger, which is over other text now.
    private fun keepDragPositionInView() {
        if (isRevealingDragPosition) return
        val anchorX = selection.dragAnchorX()
        if (anchorX >= host.viewScrollX + host.textInsetLeft) return
        isRevealingDragPosition = true
        try {
            if (host.revealX(anchorX)) {
                dragAtFinger()
            }
        } finally {
            isRevealingDragPosition = false
        }
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        // Letting go of a thumb or a handle while it is moving is the end of that
        // drag, not a fling of the text. (It would even go the wrong way: a thumb is
        // pulled down to move forward, the text is pushed up.)
        // Nor is lifting the finger after a long press on the text: it rolls a little
        // as it leaves, and that used to nudge the text.
        if (grab == Grab.NOTHING && !wasLongPressOnText) {
            scroll.fling(velocityX, velocityY)
        }
        return true
    }

    // the finger was lifted
    private fun onUp() {
        autoScroller.stop()
        scroll.onUp()
        // dismiss the magnifier
        host.dismissMagnifier()

        val released = selection.onUp()
        when {
            // the handle goes back to the text, and the view goes along to show it
            released == Handle.INSERTION -> host.revealHandle(released)
            released != null -> {
                host.revealHandle(released)
                host.showSelectionMenu()
            }
            selection.isSelected() && isSelectedWord -> {
                isSelectedWord = false
                // pressed at an edge, the selection comes into view, as the cursor
                // does after a tap; then the menu, at where it ended up
                host.revealSelection()
                host.showSelectionMenu()
            }
            else -> scrollbars.onUp()
        }

        if (host.isEditable() && !selection.isSelected() && !selection.cursor.isBlinking()) {
            // cursor resume blink
            selection.cursor.startBlink()
        }
    }

    // #endregion

    // #region ScaleGestureListener

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        autoScroller.stop()
        host.onZoomBegin()
        scrollbars.hideImmediately()
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        host.onZoom(detector.scaleFactor, detector.focusX, detector.focusY)
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        host.onZoomEnd()
    }

    // #endregion
}
