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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.abs
import kotlin.math.max

/**
 * A field of a few lines (maxLines), as the ones of the AI assistant: the question at the
 * bottom of the sheet (in a TextInputLayout, so a TextInputEditText) and a message edited
 * where it is in the chat. Its own lines scroll as if nested in the list it is in, if any,
 * which a RecyclerView cannot do for a child (it is no nested scrolling parent), and they
 * fling, which a plain EditText does not.
 *
 * While it has more lines than it shows, a touch that starts on it is its own to the end:
 * - a drag scrolls its lines first; what they cannot take (at their top or bottom) scrolls
 *   the list, and the moment the finger turns back, the lines take it again, without the
 *   finger being lifted;
 * - let go fast, it flings: its lines glide on and stop at their end, the list staying
 *   where it is; only a drag that was scrolling the list when it was let go (the lines at
 *   that end already) flings the list;
 * - a tap, a long press (the cursor, a selection) are the field's as always; a touch that
 *   stops a fling only stops it.
 * With everything in sight it scrolls nothing itself: a drag on it is the list's (outside a
 * list, nobody's). What its lines cannot take of a drag outside a list is dropped.
 *
 * The keyboard comes only for a tap that leaves a cursor in it: not for a long press (a word
 * selected, the menu to paste) nor a double tap that selects, where a plain EditText brings
 * it too and it covers what is being selected. Asked for in code (showSoftInput), it comes
 * as always.
 */
class NestedScrollEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : TextInputEditText(context, attrs, defStyleAttr) {

    private val touchSlop: Int
    private val minFling: Int
    private val maxFling: Int

    init {
        val config = ViewConfiguration.get(context)
        touchSlop = config.scaledTouchSlop
        minFling = config.scaledMinimumFlingVelocity
        maxFling = config.scaledMaximumFlingVelocity
        // the keyboard for a tap only (onTouchEvent), not whenever the field takes the focus
        showSoftInputOnFocus = false
    }

    // the touch going on was a long press (performLongClick)
    private var longPressed = false

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null

    // the touch going on is ours (started while the lines can scroll); once past the slop it
    // is a drag; a touch that stopped a fling is only that
    private var tracking = false
    private var dragging = false
    private var swallow = false
    private var downY = 0f
    private var lastY = 0f

    // the last move of the drag going on scrolled the list (the lines at their end): let go,
    // it is the list that flings
    private var listTook = false

    // how far the lines can scroll down (0: all of them are in sight)
    private fun maxScroll(): Int {
        val text = layout ?: return 0
        return max(0, text.height + totalPaddingTop + totalPaddingBottom - height)
    }

    // the list this field is in (its nearest ancestor that is one)
    private fun outer(): RecyclerView? {
        var p = parent
        while (p != null) {
            if (p is RecyclerView) return p
            p = p.parent
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
        // a tap ends here (not a drag, not the touch that stopped a fling): known before
        // scrollTouch forgets the touch
        val tapUp = event.actionMasked == MotionEvent.ACTION_UP && !dragging && !swallow
        val handled = scrollTouch(event)
        // (the field takes the focus at the tap's end, in View.onTouchEvent: after it)
        if (tapUp && !longPressed && isFocused && !hasSelection() && isEnabled && onCheckIsTextEditor()) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(this, 0)
        }
        return handled
    }

    override fun performLongClick(): Boolean {
        longPressed = true
        return super.performLongClick()
    }

    private fun scrollTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val flinging = !scroller.isFinished
            scroller.forceFinished(true)
            tracking = flinging || maxScroll() > 0
            dragging = false
            swallow = flinging
            listTook = false
            if (!tracking) return super.onTouchEvent(event)
            // (on the screen, not in the field: the field itself moves while the list scrolls)
            downY = event.rawY
            lastY = downY
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
            track(event)
            parent?.requestDisallowInterceptTouchEvent(true)
            return if (swallow) true else super.onTouchEvent(event)
        }
        if (!tracking) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                track(event)
                val y = event.rawY
                if (!dragging && abs(y - downY) > touchSlop) {
                    dragging = true
                    lastY = y
                    // the field's own idea of the touch (a long press, a selection) ends here
                    if (!swallow) cancelSuper(event)
                }
                if (dragging) {
                    val dy = (lastY - y).toInt()
                    if (dy != 0) {
                        lastY -= dy
                        scrollNested(dy)
                    }
                    return true
                }
                return if (swallow) true else super.onTouchEvent(event)
            }

            MotionEvent.ACTION_UP -> {
                track(event)
                val wasDragging = dragging
                if (wasDragging) {
                    val tracker = velocity
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                        // finger up (negative): the text goes down
                        val vy = -tracker.yVelocity.toInt()
                        if (abs(vy) >= minFling) fling(vy)
                    }
                }
                endTouch()
                return if (wasDragging || swallow) true else super.onTouchEvent(event)
            }

            MotionEvent.ACTION_CANCEL -> {
                val passOn = !dragging && !swallow
                endTouch()
                return if (passOn) super.onTouchEvent(event) else true
            }
        }
        return if (dragging || swallow) true else super.onTouchEvent(event)
    }

    private fun endTouch() {
        tracking = false
        dragging = false
        swallow = false
        velocity?.recycle()
        velocity = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelSuper(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    // [event] to the velocity, where it is on the screen (the field itself moves while the
    // list scrolls under the finger); a copy, taken in at once (the tracker keeps its own)
    private fun track(event: MotionEvent) {
        val tracker = velocity ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    // [dy] px of a drag (+: the text goes down): the lines first, the rest to the list
    private fun scrollNested(dy: Int) {
        val target = (scrollY + dy).coerceIn(0, maxScroll())
        val used = target - scrollY
        if (used != 0) scrollTo(scrollX, target)
        val rest = dy - used
        val list = outer()
        listTook = rest != 0 && list != null
        if (rest != 0) list?.scrollBy(0, rest)
    }

    // A fling of [vy] px/s (+: the text goes down): the lines glide (computeScroll) and stop
    // at their end; nothing of it goes on in the list. Only when the lines are at that end
    // already and the drag was scrolling the list (listTook) does the list fling: it goes on
    // as it was going under the finger.
    private fun fling(vy: Int) {
        val most = maxScroll()
        val canGo = if (vy > 0) scrollY < most else scrollY > 0
        if (!canGo) {
            if (listTook) outer()?.fling(0, vy)
            return
        }
        scroller.fling(scrollX, scrollY, 0, vy, scrollX, scrollX, 0, most)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        super.computeScroll()
        if (!scroller.computeScrollOffset()) return
        val y = scroller.currY.coerceIn(0, maxScroll())
        if (y != scrollY) scrollTo(scrollX, y)
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }
}
