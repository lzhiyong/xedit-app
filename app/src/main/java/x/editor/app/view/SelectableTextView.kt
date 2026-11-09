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

package x.editor.app.view

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.textclassifier.TextClassifier
import com.google.android.material.textview.MaterialTextView
import java.lang.ref.WeakReference

/**
 * A text (textIsSelectable) whose words are selected, copied, shared (a long press, the
 * handles, the floating bar) without its ever taking the focus: the field that has it (the
 * question at the bottom of the AI assistant) keeps it, its cursor, its keyboard.
 *
 * A plain TextView selects only once it has the focus (Editor.checkField asks for it): it
 * takes it from the field, and the window and the list it is in react to the focus moving.
 * Here that request is answered without the focus moving: the text only says it has it
 * ([isFocused], which is what draws the selection and what Copy copies from) for as long as
 * it holds a selection.
 *
 * Only a long press does anything. A tap and a double tap are nothing at all: they never
 * reach the text as taps (their going up is given to it as a cancel), so it puts no cursor,
 * selects no word on a double tap, clicks nothing. A tap on the text holding a selection lets
 * the selection go, and does nothing else.
 *
 * What a TextView does not get right in a list, made sure of here:
 * - A selection held made the row it is in one RecyclerView would not keep once it was
 *   scrolled off the screen (TextView's transient state): it never says it has one.
 * - A text set while the view was out of the window (a row bound to another message before
 *   it comes on screen) could not be selected by a long press until laid out anew: settled
 *   again once it is in the window ([onAttachedToWindow]).
 * - A long press whose touch ends in a cancel (taken by the system, the sheet, the list)
 *   would leave the text selecting by dragging for good (Editor's drag accelerator ends only
 *   on an up): every touch after that on it went to that drag, so a long press never selected
 *   again, and moving the finger stretched an old selection. The cancel of such a touch is
 *   given to the text as an up where the finger was.
 * - A selection left behind (made after it was let go) is taken away: a long press would
 *   keep it (Editor keeps a selection there is rather than select the word pressed), and it
 *   is not drawn. A long press starts with none.
 * - No smart selection (TextClassifier.NO_OP): it grew the word pressed into what it took
 *   for a name, an address, a few words, a moment after, away from the finger.
 * - The text never scrolls within itself (it is as large as its lines: a line taller by its
 *   spacing would scroll it by a little as it selects, the words moving under the finger),
 *   nor asks the list or the window to scroll to it (requestRectangleOnScreen).
 *
 * The selection also goes once it is down to nothing (copied, the floating bar gone), when
 * another text starts one, when the text is set to another, and when [releaseSelection] is
 * called (a tap elsewhere, Back: the screen that holds it knows those). Scrolled out of the
 * window and back it stays, with its handles and its floating bar.
 *
 * Outside touch mode (a keyboard, a D-pad) it takes the focus as any TextView does.
 */
class SelectableTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : MaterialTextView(context, attrs, defStyleAttr) {

    /** Told when this text starts (true) or stops (false) holding a selection. */
    var onSelectionHeld: ((SelectableTextView, Boolean) -> Unit)? = null

    /** Whether this text holds a selection now (and says it has the focus for it). */
    var isHoldingSelection = false
        private set

    // a long press going on (performLongClick): the one time the selection may start
    private var longPressing = false

    // a touch is going on (from its going down to its going up or cancel), and it became a
    // long press: its going up is the text's own then (the end of the selecting)
    private var touching = false
    private var longPressed = false

    // where the finger was last, in the text
    private var lastX = 0f
    private var lastY = 0f

    init {
        // (a tap does not take the focus: View.onTouchEvent asks only in touch mode)
        isFocusableInTouchMode = false
        setTextClassifier(TextClassifier.NO_OP)
    }

    override fun isFocused(): Boolean = isHoldingSelection || super.isFocused()

    // Asked by the selection as it starts (Editor.checkField), and again by Select all while
    // it holds: yes, without moving the focus. In touch mode nothing else asks (a tap does
    // not: not focusable in touch mode), so anything else is refused, as before.
    override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (!isInTouchMode || !isTextSelectable) return super.requestFocus(direction, previouslyFocusedRect)
        if (isHoldingSelection) return true
        if (!longPressing) return false
        hold()
        return true
    }

    override fun performLongClick(): Boolean {
        // Always the word pressed, from nothing: a selection held is let go of (a long press
        // in it would drag its text away, out of it would keep it), one left behind dropped.
        if (isHoldingSelection) releaseSelection() else dropSelection()
        longPressing = true
        if (touching) longPressed = true
        try {
            return super.performLongClick()
        } finally {
            longPressing = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastX = event.x
        lastY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                longPressed = false
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                if (!longPressed) {
                    // a tap, or a drag that was no long press (the list takes those): only a
                    // cancel for the text, and the selection held, if any, let go
                    deliver(event, MotionEvent.ACTION_CANCEL)
                    releaseSelection()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (longPressed) {
                    // the end of a long press's selecting as if the finger went up there
                    deliver(event, MotionEvent.ACTION_UP)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // [event] given to the TextView as [action]
    private fun deliver(event: MotionEvent, action: Int) {
        val copy = MotionEvent.obtain(event)
        copy.action = action
        super.onTouchEvent(copy)
        copy.recycle()
    }

    // Down to nothing while holding (Copy, the floating bar gone with its selection, the text
    // set again): let go, once whatever made it so is done. Something selected while not
    // holding (and not by a long press starting): taken away.
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (isHoldingSelection && selStart == selEnd) {
            post { if (isHoldingSelection && !hasSelection()) releaseSelection() }
        } else if (!isHoldingSelection && !longPressing && selStart != selEnd) {
            post { if (!isHoldingSelection && hasSelection()) dropSelection() }
        }
    }

    // as large as its lines: nothing to scroll within it
    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, 0)
    }

    // Never said to have a transient state. TextView says so while its floating bar is up
    // over a selection (Editor), for a list not to use the view for another item meanwhile;
    // RecyclerView then keeps no such row at all once it is scrolled off the screen, neither
    // as it is (its cache) nor to bind again: it lets it go, and the row that comes back is
    // another, with no selection. Said to have none, the row is kept as it is (the cache),
    // its selection with it; bound to another message, it lets its selection go (setText).
    override fun setHasTransientState(hasTransientState: Boolean) = Unit

    // a text in a list never asks the list (nor the window) to scroll to it
    override fun requestRectangleOnScreen(rectangle: Rect?, immediate: Boolean): Boolean = false

    // Whether a long press may select is settled by TextView whenever its text is set or laid
    // out anew (Editor.prepareCursorControllers), and only in a window: a text set while the
    // view is out of one (a row of a list bound to another message before it is put on the
    // screen, laid out again at the width it had) was left unable to select until its next
    // new layout, and a long press on it did nothing. Settled again here, in the window: the
    // cursor's visibility set to something else and back is what has TextView do that
    // (for a text that cannot be edited the cursor is never shown either way).
    // (before TextView's own: a selection kept while it was out of the window brings its
    // handles and its floating bar back then, which needs it settled)
    override fun onAttachedToWindow() {
        val cursor = isCursorVisible
        isCursorVisible = !cursor
        isCursorVisible = cursor
        super.onAttachedToWindow()
    }

    // A text set in place of the one holding a selection (a row of a list bound to another
    // message): the selection let go of first, it was in the text that goes.
    override fun setText(text: CharSequence?, type: BufferType?) {
        if (isHoldingSelection) releaseSelection()
        super.setText(text, type)
    }

    // Out of the window (the row scrolled off the screen) a selection held is kept: TextView
    // keeps it, and brings its handles and its floating bar back once the text is in the
    // window again (Editor). It goes only if the text is set to another (setText).
    override fun onDetachedFromWindow() {
        // A long press still selecting (the row scrolled away, the sheet closed under the
        // finger): its dragging ended, while there is still a parent to tell, and what it
        // selected let go of. Otherwise the text is still dragging when it is shown again,
        // and no long press selects in it.
        if (touching && longPressed && parent != null) {
            releaseSelection()
            val now = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, lastX, lastY, 0)
            super.onTouchEvent(up)
            up.recycle()
            dropSelection()
        }
        touching = false
        longPressed = false
        super.onDetachedFromWindow()
    }

    /**
     * The selection let go of: none left, the handles and the floating bar gone with it (as
     * when the focus goes elsewhere), and the text no longer says it has the focus. Nothing
     * if it holds none.
     */
    fun releaseSelection() {
        if (!isHoldingSelection) return
        isHoldingSelection = false
        if (held?.get() === this) held = null
        dropSelection()
        invalidate()
        onSelectionHeld?.invoke(this, false)
    }

    // No selection in the text: collapsed first, so the floating bar ends with one where it
    // can put one back (Editor's onDestroyActionMode), then none at all; the handles and the
    // floating bar go with it.
    private fun dropSelection() {
        val spannable = text as? Spannable ?: return
        if (Selection.getSelectionStart(spannable) < 0 && Selection.getSelectionEnd(spannable) < 0) return
        val end = selectionEnd
        if (end >= 0) Selection.setSelection(spannable, end)
        Selection.removeSelection(spannable)
    }

    private fun hold() {
        held?.get()?.takeIf { it !== this }?.releaseSelection()
        isHoldingSelection = true
        held = WeakReference(this)
        invalidate()
        onSelectionHeld?.invoke(this, true)
    }

    companion object {
        // the one text holding a selection, if any: another starting one lets it go
        private var held: WeakReference<SelectableTextView>? = null
    }
}
