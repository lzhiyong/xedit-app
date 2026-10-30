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

package x.editor.app.filetree

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

import androidx.recyclerview.widget.RecyclerView

/**
 * Draws a divider below every row of the file tree. The divider height is part of
 * the row height given to TreeLayoutManager (see [FileTreeMetrics.rowHeight]).
 */
class FileTreeDividerDecoration(
    private val dividerHeight: Int,
    color: Int,
) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply { this.color = color }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        outRect.set(0, 0, 0, dividerHeight)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val left = child.left + child.translationX
            val right = child.right + child.translationX
            val top = child.bottom + child.translationY
            canvas.drawRect(left, top, right, top + dividerHeight, paint)
        }
    }
}
