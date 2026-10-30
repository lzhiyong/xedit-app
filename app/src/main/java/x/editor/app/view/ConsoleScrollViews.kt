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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView

import androidx.core.widget.NestedScrollView

import kotlin.math.abs

/**
 * The vertical scroll of the console's output: lines that are not wrapped, in a
 * [ConsoleHorizontalScrollView] inside this one.
 *
 * Two scroll views inside each other fought over every gesture: whichever saw its
 * axis pass the touch slop first took it, so a drag that set off a little sideways
 * was held by the horizontal one and the text would not go up or down ("it does not
 * scroll"). Here a gesture is given one axis, the one it moves along most, as soon as
 * it has moved beyond the slop: mostly sideways, and the text is wider than the view,
 * it is left to the horizontal scroll view; otherwise it is this one's.
 *
 * Use with `android:fillViewport="true"`, so that the horizontal scroll view is as
 * high as this one even with a line or two: then a drag anywhere on the page scrolls,
 * not only one on the text.
 *
 * It follows its end ([followsEnd]): as lines come in, laid out with their new height,
 * it goes down with them. Scrolled away from the end by hand it stops; scrolled back to
 * the end it follows again. While it follows, the text (selectable: it has a cursor, at
 * its start, and takes the focus) cannot scroll it to that cursor: that pulled it back
 * up now and then, and it stopped following.
 */
class ConsoleScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    // this gesture goes sideways: it is the horizontal scroll view's
    private var sideways = false

    // as close to the end as counts as at it (a drag or a fling stops right at it)
    private val slack = (2 * resources.displayMetrics.density).toInt()

    /** Whether it goes down with what is added at the end. */
    var followsEnd = true
        private set

    /** Follows the end again (another page of lines, a build started) and goes there. */
    fun followEnd() {
        followsEnd = true
        scrollToEnd()
        post { if (followsEnd) scrollToEnd() }
    }

    /** How far it goes down: its content's height less what it shows. */
    val maxScrollY: Int
        get() {
            val child = getChildAt(0) ?: return 0
            return (child.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
        }

    private fun scrollToEnd() {
        val end = maxScrollY
        if (scrollY != end) scrollTo(scrollX, end)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // the text laid out again (lines added), or this resized: at the new end
        if (followsEnd) scrollToEnd()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // only a hand (and scrollToEnd) moves it while it follows: see below
        followsEnd = t >= maxScrollY - slack
    }

    // The text bringing its cursor or itself into sight (setText, the focus, a resize):
    // not while following the end, it would be pulled away from it.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        if (followsEnd) 0 else super.computeScrollDeltaToGetChildRectOnScreen(rect)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                sideways = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (sideways) return false
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop && dx > dy && contentScrollsSideways()) {
                    sideways = true
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun contentScrollsSideways(): Boolean {
        val child = getChildAt(0) ?: return false
        return child.canScrollHorizontally(1) || child.canScrollHorizontally(-1)
    }
}

/**
 * The horizontal scroll of the console's output, inside a [ConsoleScrollView]. It takes
 * no part in a gesture while there is nothing to scroll sideways (every line fits): it
 * would hold on to it for nothing, and the text could not be scrolled up or down.
 */
class ConsoleHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val scrollsSideways: Boolean
        get() = canScrollHorizontally(1) || canScrollHorizontally(-1)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        scrollsSideways && super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean =
        scrollsSideways && super.onTouchEvent(ev)
}
