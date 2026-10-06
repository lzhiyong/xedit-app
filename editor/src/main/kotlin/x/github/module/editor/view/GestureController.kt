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
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector

import x.github.module.editor.view.SelectionController.Handle
import x.github.module.piecetable.common.Range

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
 */
class GestureController(
    context: Context,
    private val host: GestureController.Host,
    private val selection: SelectionController,
    private val scrollbars: ScrollbarController,
    private val scroll: ScrollController
) : GestureDetector.OnGestureListener, ScaleGestureDetector.OnScaleGestureListener {

    /**
     * What a gesture can ask the editor to do. Only invalidate/performHapticFeedback
     * are inherited from View as-is; the rest is implemented by EditorView.
     */
    interface Host : EditorGeometry {
        // left/top padding: touch coordinates are relative to the view, content is not
        val contentPaddingLeft: Int
        val contentPaddingTop: Int

        fun isEditable(): Boolean
        fun invalidate()
        fun performHapticFeedback(feedbackConstant: Int): Boolean

        fun focusEditor()
        fun showSoftKeyboard()
        fun showActionMode()
        fun dismissActionMode()
        /** Shows (or moves) the magnifier over the cursor. */
        fun showMagnifier()
        fun dismissMagnifier()

        /** The cursor was just placed by a tap: bring it into view. */
        fun onCursorTapped()
        /** A word was just selected by a long press. */
        fun onWordSelected(range: Range)
        /**
         * A handle is being dragged: keep the cursor inside the viewport, leaving
         * [extraLeft]/[extraRight] more room on the side the handle sticks out to.
         */
        fun scrollCursorIntoView(extraLeft: Int, extraRight: Int): Boolean

        // pinch zoom, i.e. changing the text size
        fun onZoomBegin()
        fun onZoom(scaleFactor: Float, focusX: Float, focusY: Float)
        fun onZoomEnd()
    }

    private val gestureDetector = GestureDetector(context, this).apply {
        setIsLongpressEnabled(true)
    }
    private val scaleGestureDetector = ScaleGestureDetector(context, this)

    // a long press was recognized and the finger is still down
    private var isLongPressed = false
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
        }

        gestureDetector.onTouchEvent(e)
        scaleGestureDetector.onTouchEvent(e)
        return true
    }

    // touch position -> content coordinates
    private fun contentX(e: MotionEvent) = e.x.toInt() + host.viewScrollX - host.contentPaddingLeft
    private fun contentY(e: MotionEvent) = e.y.toInt() + host.viewScrollY - host.contentPaddingTop

    // #region GestureDetectorListener

    override fun onDown(e: MotionEvent): Boolean {
        val x = contentX(e)
        val y = contentY(e)

        // handles first, then scrollbars
        if (!selection.onDown(x, y)) {
            scrollbars.onDown(x, y)
        }

        scroll.onDown()
        host.focusEditor()
        host.dismissActionMode()
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val x = contentX(e)
        val y = contentY(e)
        val handle = selection.hitTest(x, y)

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
        val x = contentX(e)
        val y = contentY(e)

        this.isLongPressed = true
        // perform haptic feedback
        host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        when {
            scrollbars.hitTest(x, y) -> {
                return
            }
            selection.hitTest(x, y) != null -> {
                host.showMagnifier()
                return
            }
            selection.range.containsPoint(x, y) -> {
                // here, requires set cusor position
                // for set the action mode location
                selection.cursor.setPositionFromPoint(x, y)
                host.showActionMode()
                return
            }
        }

        if (selection.selectWordAt(x, y)) {
            // has text selected
            isSelectedWord = true
            host.onWordSelected(selection.range)
            host.showMagnifier()
        } else if (host.isEditable()) {
            // no selected
            selection.cursor.stopBlink(true, false)
            // resume blink
            selection.cursor.startBlink()
            host.showActionMode()
        }

        host.invalidate()
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val dragged = selection.onDrag(contentX(e2), contentY(e2))

        when {
            dragged != null -> {
                // auto scroll when dragging a handle; the selection handles hang off to
                // one side of the cursor, so leave room for them on that side
                val halfWidth = selection.handleHalfWidth(dragged)
                host.scrollCursorIntoView(
                    extraLeft = if (dragged == Handle.START) halfWidth else 0,
                    extraRight = if (dragged == Handle.END) halfWidth else 0
                )
                // update the magnifier location
                host.showMagnifier()
            }
            // dragging a scrollbar thumb: it already scrolled the view itself
            scrollbars.onDrag(e2.x, e2.y) -> { }
            // dragging the content
            else -> scroll.dragBy(distanceX, distanceY, e2.x, e2.y)
        }

        host.invalidate()
        return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        scroll.fling(velocityX, velocityY)
        return true
    }

    // the finger was lifted
    private fun onUp() {
        scroll.onUp()
        // dismiss the magnifier
        host.dismissMagnifier()

        val released = selection.onUp()
        when {
            released == Handle.INSERTION -> { }
            released != null -> host.showActionMode()
            selection.isSelected() && isSelectedWord -> {
                isSelectedWord = false
                host.showActionMode()
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
