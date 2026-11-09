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
import android.widget.LinearLayout

/**
 * A LinearLayout as high as its content, up to [maxHeight].
 *
 * Vertical with wrap_content, a child of layout_height 0dp and a weight grows with its own
 * content too (LinearLayout measures it as wrap_content first); once the whole would be
 * higher than [maxHeight], that child gives up the difference and the others keep their
 * height. So a list between a header and a field: as high as what is in it, and scrolling
 * once there is more than fits, the field always shown (AgentSheet).
 *
 * The least (android:minHeight, setMinimumHeight) LinearLayout keeps on its own: below it,
 * that child takes the room left, the others keep their height.
 *
 * A least as high as the most (or higher) holds it at the most whatever is in it, measured
 * as of that height (exactly): the weighted child measured once, at the room left (not first
 * as wrap_content: a list measured at other heights before it gets its own may not come back
 * to where it was, RecyclerView laying itself out as it is measured). So does [pinnedHeight].
 */
class MaxHeightLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** The highest it gets, padding included; [Int.MAX_VALUE]: no more than its parent says. */
    var maxHeight: Int = Int.MAX_VALUE
        set(value) {
            val height = value.coerceAtLeast(0)
            if (height == field) return
            field = height
            requestLayout()
        }

    /**
     * A height it is held at whatever is in it (still no more than [maxHeight], nor than its
     * parent allows), the weighted child taking what is left: for an animation from one
     * height to another (AgentSheet: a new chat, the sheet going down to its least). -1: none.
     */
    var pinnedHeight: Int = -1
        set(value) {
            if (value == field) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        if (pinnedHeight >= 0) {
            var height = minOf(pinnedHeight, maxHeight)
            if (mode != MeasureSpec.UNSPECIFIED) height = minOf(height, size)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            return
        }
        // held at the most (see the class comment)
        if (maxHeight != Int.MAX_VALUE && minimumHeight >= maxHeight) {
            var height = maxHeight
            if (mode != MeasureSpec.UNSPECIFIED) height = minOf(height, size)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            return
        }
        val spec = when {
            maxHeight == Int.MAX_VALUE -> heightMeasureSpec
            mode == MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            size > maxHeight -> MeasureSpec.makeMeasureSpec(maxHeight, mode)
            else -> heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
