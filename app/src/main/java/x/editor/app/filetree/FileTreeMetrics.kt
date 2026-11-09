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

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue

import kotlin.math.ceil
import kotlin.math.max

import x.github.module.treeview.RowWidthCalculator
import x.github.module.treeview.TreeNode

/**
 * Row dimensions of the file tree, kept in sync with item_file_tree_node.xml.
 *
 * Also the [RowWidthCalculator]: the width of a row is computed from the indentation,
 * the fixed-size views and the label measured with a [TextPaint] set up like the
 * label TextView, so no view has to be inflated.
 */
class FileTreeMetrics(context: Context) : RowWidthCalculator<FileTreeEntry> {

    private val displayMetrics = context.resources.displayMetrics

    val itemHeight = dp(52f)
    val dividerHeight = max(1, dp(0.5f))

    /** Row height handed to TreeLayoutManager: the item plus the divider decoration. */
    val rowHeight = itemHeight + dividerHeight

    val paddingEnd = dp(12f)
    private val basePaddingStart = dp(8f)
    private val indent = dp(20f)
    private val arrowWidth = dp(24f)
    private val iconWidth = dp(24f)
    private val nameMarginStart = dp(8f)

    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, displayMetrics)
    }

    fun paddingStartOf(depth: Int): Int = basePaddingStart + depth * indent

    override fun calculateRowWidth(node: TreeNode<FileTreeEntry>): Int =
        paddingStartOf(node.depth) + arrowWidth + iconWidth + nameMarginStart +
            ceil(namePaint.measureText(node.value.name)).toInt() + paddingEnd

    private fun dp(value: Float): Int = (value * displayMetrics.density + 0.5f).toInt()
}
