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
 
package x.github.module.treeview

/**
 * Calculates how wide the row of a node is without inflating or measuring a view.
 *
 * Used by [TreeAdapter] to keep track of the widest visible row, which [TreeLayoutManager]
 * uses as its horizontal scroll range. Results are cached per node and recalculated only
 * when the node changes ([TreeAdapter.notifyNodeChanged]), moves to a different depth, or
 * when [TreeAdapter.invalidateRowWidths] is called.
 *
 * A typical implementation adds the indentation for [TreeNode.depth], fixed-size parts of
 * the row (icons, paddings) and the label width measured with a [android.text.TextPaint]
 * configured like the label view.
 */
public fun interface RowWidthCalculator<T> {

    /**
     * Returns the full width in pixels the row of [node] needs: indentation, paddings,
     * horizontal margins and item decoration insets included. Underestimating clips nothing,
     * because the layout manager also takes the measured width of laid-out rows into account.
     */
    public fun calculateRowWidth(node: TreeNode<T>): Int
}
