/*
 * Copyright © 2023 Github Lzhiyong
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
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ScrollView

/**
 * A ScrollView that grows with its content up to a maximum height, and scrolls beyond it.
 *
 * It can follow its end ([setFollowsEnd]), as the console's output does (ConsoleScrollView):
 * as text is added, laid out with its new height, it goes down with it. Scrolled away from
 * the end by hand it stops; scrolled back to the end it follows again. While it follows, the
 * text (selectable: it has a cursor and takes the focus) cannot scroll it back to that cursor.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    
    private var maxHeight: Int = 0

    // following the end: asked for ([setFollowsEnd]), and not scrolled away from it by hand
    private var followEndEnabled = false
    private var following = false

    // as close to the end as counts as at it (a drag or a fling stops right at it)
    private val slack = (2 * resources.displayMetrics.density).toInt()

    /**
     * Whether it goes down with what is added at the end. Turned on: it follows again and
     * goes to the end; off: it stays where it is, as a plain ScrollView.
     */
    fun setFollowsEnd(enabled: Boolean) {
        if (followEndEnabled == enabled) return
        followEndEnabled = enabled
        following = enabled
        if (enabled) {
            scrollToEnd()
            post { if (following) scrollToEnd() }
        }
    }

    /** Whether it follows its end now (asked to, and not scrolled away from it). */
    val followsEnd: Boolean get() = followEndEnabled && following

    // how far it goes down: its content's height less what it shows
    private val maxScrollY: Int
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
        if (followEndEnabled) following = t >= maxScrollY - slack
    }

    // The text bringing its cursor or itself into sight (setText, the focus, a resize):
    // not while following the end, it would be pulled away from it.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int =
        if (followsEnd) 0 else super.computeScrollDeltaToGetChildRectOnScreen(rect)

    /** @param maxHeight in pixels; 0 or less for no limit */
    fun setMaxHeight(maxHeight: Int) {
        if (this.maxHeight != maxHeight) {
            this.maxHeight = maxHeight
            // measure again with the new limit
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightSpec = heightMeasureSpec
        if (maxHeight > 0) {
            // no taller than the parent allows either (a popup near the edge of the screen):
            // else the bottom of the content is cut off and cannot be scrolled to
            val limit = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> maxHeight
                else -> minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
            }
            heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }
}
