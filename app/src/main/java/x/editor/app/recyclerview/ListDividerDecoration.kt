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

package x.editor.app.recyclerview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Dividers between the items of a list.
 *
 * @param orientation of the list: [RecyclerView.VERTICAL] (horizontal lines under
 *   the items) or [RecyclerView.HORIZONTAL] (vertical lines after the items)
 * @param divider the line; the theme's `android:listDivider` if null
 * @param drawFirstDivider also draw a divider before the first item of the list
 * @param drawLastDivider also draw a divider after the last item of the list
 */
class ListDividerDecoration(
    context: Context,
    private val orientation: Int = RecyclerView.VERTICAL,
    divider: Drawable? = null,
    private val drawFirstDivider: Boolean = false,
    private val drawLastDivider: Boolean = true
) : RecyclerView.ItemDecoration() {

    // null only if the theme has no listDivider either: nothing is drawn then
    private val divider: Drawable? = divider ?: loadDefaultDivider(context)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val divider = this.divider ?: return
        if (orientation == RecyclerView.VERTICAL)
            drawVerticalList(c, parent, divider, state.itemCount)
        else
            drawHorizontalList(c, parent, divider, state.itemCount)
    }

    private fun drawVerticalList(c: Canvas, parent: RecyclerView, divider: Drawable, itemCount: Int) {
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val params = child.layoutParams as RecyclerView.LayoutParams

            if (hasDividerBefore(position, drawFirstDivider)) {
                val bottom = child.top - params.topMargin
                divider.setBounds(left, bottom - divider.intrinsicHeight, right, bottom)
                divider.draw(c)
            }

            if (hasDividerAfter(position, itemCount, drawLastDivider)) {
                val top = child.bottom + params.bottomMargin
                divider.setBounds(left, top, right, top + divider.intrinsicHeight)
                divider.draw(c)
            }
        }
    }

    private fun drawHorizontalList(c: Canvas, parent: RecyclerView, divider: Drawable, itemCount: Int) {
        val top = parent.paddingTop
        val bottom = parent.height - parent.paddingBottom

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val params = child.layoutParams as RecyclerView.LayoutParams

            if (hasDividerBefore(position, drawFirstDivider)) {
                val right = child.left - params.leftMargin
                divider.setBounds(right - divider.intrinsicWidth, top, right, bottom)
                divider.draw(c)
            }

            if (hasDividerAfter(position, itemCount, drawLastDivider)) {
                val left = child.right + params.rightMargin
                divider.setBounds(left, top, left + divider.intrinsicWidth, bottom)
                divider.draw(c)
            }
        }
    }

    // Leaves room for exactly the dividers that onDraw draws
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val divider = this.divider
        val position = parent.getChildAdapterPosition(view)
        if (divider == null || position == RecyclerView.NO_POSITION) {
            outRect.setEmpty()
            return
        }

        val before = hasDividerBefore(position, drawFirstDivider)
        val after = hasDividerAfter(position, state.itemCount, drawLastDivider)
        if (orientation == RecyclerView.VERTICAL) {
            val height = divider.intrinsicHeight
            outRect.set(0, if (before) height else 0, 0, if (after) height else 0)
        } else {
            val width = divider.intrinsicWidth
            outRect.set(if (before) width else 0, 0, if (after) width else 0, 0)
        }
    }

    companion object {

        /** Whether a divider belongs before the item at [position]: only before the first one, if wanted. */
        internal fun hasDividerBefore(position: Int, drawFirstDivider: Boolean): Boolean =
            drawFirstDivider && position == 0

        /** Whether a divider belongs after the item at [position]: after each, the last one only if wanted. */
        internal fun hasDividerAfter(position: Int, itemCount: Int, drawLastDivider: Boolean): Boolean =
            drawLastDivider || position < itemCount - 1

        private fun loadDefaultDivider(context: Context): Drawable? {
            val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
            try {
                return a.getDrawable(0)
            } finally {
                a.recycle()
            }
        }
    }
}
