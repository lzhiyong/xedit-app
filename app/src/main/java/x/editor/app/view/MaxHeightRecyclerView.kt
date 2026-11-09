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
import android.util.AttributeSet
import android.view.MotionEvent

import androidx.recyclerview.widget.RecyclerView

/**
 * A list inside something that scrolls (a sheet): as tall as its rows up to [setMaxHeight],
 * scrolling its rows beyond it.
 *
 * It does not scroll nested: the sheet around it would take the scroll that the list
 * leaves (and a bottom sheet would be dragged down by a list scrolled back to its top).
 * Instead, while it has more rows than it shows, a touch that starts on it is its own to
 * the end (as EditorView.setKeepsTouches): a drag on it scrolls the rows, a drag elsewhere
 * the sheet. Without a limit it is as tall as all its rows and scrolls nothing itself.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var maxHeight = 0

    init {
        isNestedScrollingEnabled = false
    }

    /** @param maxHeight in pixels; 0 or less for no limit */
    fun setMaxHeight(maxHeight: Int) {
        if (this.maxHeight != maxHeight) {
            this.maxHeight = maxHeight
            requestLayout()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val spec = if (maxHeight > 0) MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) else heightSpec
        super.onMeasure(widthSpec, spec)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && maxHeight > 0 &&
            (canScrollVertically(1) || canScrollVertically(-1))
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(e)
    }
}
