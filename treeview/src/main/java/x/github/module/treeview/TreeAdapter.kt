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

import android.annotation.SuppressLint
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max

/**
 * Base adapter that displays a tree of [TreeNode]s as a flat list of rows.
 *
 * ## Design
 *
 * **Flattened projection.** The adapter keeps two structures:
 * - `roots`: the top-level nodes of the tree;
 * - `visibleNodes`: a pre-order traversal containing only nodes whose ancestors are all
 *   expanded. The adapter position of a node is its index in this list.
 *
 * Every operation (expand, collapse, insert, remove) edits `visibleNodes` in place and
 * dispatches the narrowest possible notification: a range insertion/removal for the affected
 * subtree plus a [PAYLOAD_EXPANSION_CHANGED] change for the node whose expansion indicator
 * changed. RecyclerView therefore keeps untouched rows, plays item animations, and
 * [TreeLayoutManager] learns about structural changes.
 *
 * **Row widths.** If a [rowWidthCalculator] is set, the adapter tracks the widest visible
 * row and exposes it through [ContentWidthProvider], which [TreeLayoutManager] picks up
 * automatically to get an exact horizontal scroll range. Widths are cached per node and
 * stamped with a generation number, so invalidating every cached width is O(1). The maximum
 * is updated incrementally when rows are added and recomputed lazily (once, on the next
 * read) when rows are removed.
 *
 * **Binding.** Subclasses implement [onBindNode] instead of `onBindViewHolder`. Partial
 * rebinds with payloads are supported; [PAYLOAD_EXPANSION_CHANGED] is sent when only the
 * expansion indicator of a row needs to be updated.
 *
 * **Complexity.** Expand/collapse/insert/remove are O(n) in the number of visible rows in
 * the worst case (list shifting and position lookup), which is fine for tens of thousands of
 * rows. Methods that take a position ([toggleAt]) avoid the position lookup.
 *
 * All methods must be called on the main thread.
 *
 * @param T the type of the values held by the nodes.
 * @param VH the view holder type.
 */
public abstract class TreeAdapter<T, VH : RecyclerView.ViewHolder> :
    RecyclerView.Adapter<VH>(), ContentWidthProvider {

    public companion object {
        /**
         * Payload sent with `notifyItemChanged` when a node is expanded or collapsed, or when
         * it gains its first / loses its last child. Only the expansion indicator needs to be
         * rebound for it.
         */
        @JvmField
        public val PAYLOAD_EXPANSION_CHANGED: Any = object : Any() {
            override fun toString(): String = "TreeAdapter.PAYLOAD_EXPANSION_CHANGED"
        }
    }

    private val roots = ArrayList<TreeNode<T>>()
    private val visibleNodes = ArrayList<TreeNode<T>>()
    private val attachedRecyclerViews = ArrayList<RecyclerView>(1)

    // Generation number of the cached row widths; see TreeNode.cachedRowWidthStamp.
    private var rowWidthStamp = 1
    private var cachedMaxRowWidth = 0
    private var isMaxRowWidthDirty = true

    /** The root nodes, in display order. */
    public val rootNodes: List<TreeNode<T>>
        get() = roots

    /**
     * Calculates row widths for an exact horizontal scroll range. When `null`, [maxRowWidth]
     * is [ContentWidthProvider.UNKNOWN] and [TreeLayoutManager] measures rows itself.
     */
    public var rowWidthCalculator: RowWidthCalculator<T>? = null
        set(value) {
            field = value
            invalidateRowWidths()
        }

    /**
     * Called after a node has been expanded or collapsed through this adapter, e.g. to
     * persist the expansion state. Not called by [expandAll], [collapseAll] or [setRoots].
     */
    public var onNodeExpansionChanged: ((node: TreeNode<T>, isExpanded: Boolean) -> Unit)? = null

    /**
     * Width in pixels of the widest visible row according to [rowWidthCalculator], or
     * [ContentWidthProvider.UNKNOWN] if no calculator is set.
     */
    public val maxRowWidth: Int
        get() {
            val calculator = rowWidthCalculator ?: return ContentWidthProvider.UNKNOWN
            if (isMaxRowWidthDirty) {
                var widest = 0
                for (node in visibleNodes) widest = max(widest, rowWidthOf(node, calculator))
                cachedMaxRowWidth = widest
                isMaxRowWidthDirty = false
            }
            return cachedMaxRowWidth
        }

    /** Binds [node] to [holder]. [payloads] is empty for a full bind. */
    public abstract fun onBindNode(holder: VH, node: TreeNode<T>, payloads: List<Any>)

    final override fun onBindViewHolder(holder: VH, position: Int) {
        onBindNode(holder, visibleNodes[position], emptyList())
    }

    final override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        onBindNode(holder, visibleNodes[position], payloads)
    }

    final override fun getItemCount(): Int = visibleNodes.size

    override fun getContentWidth(): Int = maxRowWidth

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerViews += recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerViews -= recyclerView
    }

    // region Queries

    /** Returns the node displayed at adapter [position]. */
    public fun getNode(position: Int): TreeNode<T> = visibleNodes[position]

    /** Returns the node displayed at adapter [position], or `null` if out of range. */
    public fun getNodeOrNull(position: Int): TreeNode<T>? = visibleNodes.getOrNull(position)

    /**
     * Returns the adapter position of [node], or [RecyclerView.NO_POSITION] if it isn't
     * displayed (not in this tree, or an ancestor is collapsed). O(visible rows).
     */
    public fun positionOf(node: TreeNode<T>): Int = visibleNodes.indexOf(node)

    /** `true` if [node] belongs to the tree held by this adapter. */
    public operator fun contains(node: TreeNode<T>): Boolean {
        val root = node.root
        return roots.any { it === root }
    }

    /** `true` if [node] belongs to this tree and all its ancestors are expanded. O(depth). */
    public fun isDisplayed(node: TreeNode<T>): Boolean =
        node.ancestors().all { it.isExpanded } && contains(node)

    // endregion

    // region Whole-tree operations

    /** Replaces the whole tree. Every node in [newRoots] must be a root (have no parent). */
    @SuppressLint("NotifyDataSetChanged")
    public fun setRoots(newRoots: List<TreeNode<T>>) {
        // Copy first: newRoots may be rootNodes itself.
        val copy = newRoots.toList()
        require(copy.all { it.parent == null }) { "Root nodes must not have a parent." }
        roots.clear()
        roots.addAll(copy)
        rebuildVisibleNodes()
        notifyDataSetChanged()
    }

    /** Expands every node that has children. */
    @SuppressLint("NotifyDataSetChanged")
    public fun expandAll() {
        forEachNode { if (!it.isLeaf) it.isExpanded = true }
        rebuildVisibleNodes()
        notifyDataSetChanged()
    }

    /** Collapses every node, leaving only the roots visible. */
    @SuppressLint("NotifyDataSetChanged")
    public fun collapseAll() {
        forEachNode { it.isExpanded = false }
        rebuildVisibleNodes()
        notifyDataSetChanged()
    }

    // endregion

    // region Expansion

    /**
     * Expands [node]. If the node itself is hidden because an ancestor is collapsed, only its
     * state changes; its children appear once the ancestors are expanded.
     *
     * @return `false` if the node is a leaf or already expanded.
     */
    public fun expand(node: TreeNode<T>): Boolean = expandInternal(node, positionOf(node))

    /**
     * Collapses [node], hiding all of its descendants.
     *
     * @return `false` if the node is already collapsed.
     */
    public fun collapse(node: TreeNode<T>): Boolean = collapseInternal(node, positionOf(node))

    /** Expands [node] if it is collapsed, collapses it otherwise. */
    public fun toggle(node: TreeNode<T>): Boolean =
        if (node.isExpanded) collapse(node) else expand(node)

    /**
     * Toggles the node at adapter [position]. Prefer this in click handlers (with
     * `holder.bindingAdapterPosition`): it skips the O(n) position lookup.
     */
    public fun toggleAt(position: Int): Boolean {
        val node = visibleNodes.getOrNull(position) ?: return false
        return if (node.isExpanded) collapseInternal(node, position) else expandInternal(node, position)
    }

    /**
     * Expands all ancestors of [node] so that it becomes visible.
     *
     * @return the adapter position of [node], or [RecyclerView.NO_POSITION] if it doesn't
     *   belong to this tree. Typically followed by `recyclerView.scrollToPosition(...)`.
     */
    public fun reveal(node: TreeNode<T>): Int {
        if (node !in this) return RecyclerView.NO_POSITION
        // Top-down, so that each expansion inserts rows under an already visible ancestor.
        for (ancestor in node.ancestors().toList().asReversed()) expand(ancestor)
        return positionOf(node)
    }

    // `position` is the node's adapter position, or NO_POSITION if it isn't displayed.
    private fun expandInternal(node: TreeNode<T>, position: Int): Boolean {
        if (node.isExpanded || node.isLeaf) return false
        node.isExpanded = true
        if (position != RecyclerView.NO_POSITION) {
            val rows = ArrayList<TreeNode<T>>()
            collectVisible(node.children, rows)
            visibleNodes.addAll(position + 1, rows)
            onRowsAdded(rows)
            notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
            notifyItemRangeInserted(position + 1, rows.size)
        }
        onNodeExpansionChanged?.invoke(node, true)
        return true
    }

    private fun collapseInternal(node: TreeNode<T>, position: Int): Boolean {
        if (!node.isExpanded) return false
        node.isExpanded = false
        if (position != RecyclerView.NO_POSITION) {
            val count = countVisibleDescendants(position)
            notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
            if (count > 0) {
                visibleNodes.subList(position + 1, position + 1 + count).clear()
                isMaxRowWidthDirty = true
                notifyItemRangeRemoved(position + 1, count)
            }
        }
        onNodeExpansionChanged?.invoke(node, false)
        return true
    }

    // endregion

    // region Structural changes

    /**
     * Inserts [node] (with its whole subtree) as a child of [parent] at [index], or as a root
     * when [parent] is `null`. Rows are inserted only if the new node is visible; if [parent]
     * is collapsed, the node appears when it is expanded.
     *
     * @throws IllegalArgumentException if [node] already belongs to a tree, or [parent] is not
     *   part of this adapter's tree.
     */
    @JvmOverloads
    public fun insertNode(
        parent: TreeNode<T>?,
        node: TreeNode<T>,
        index: Int = parent?.children?.size ?: roots.size,
    ) {
        require(node.parent == null && roots.none { it === node }) {
            "The node already belongs to a tree."
        }
        if (parent == null) {
            if (index !in 0..roots.size) throw IndexOutOfBoundsException("index=$index, roots=${roots.size}")
            // Roots are always visible: the new rows go right before the next root's rows.
            val rowPosition = if (index < roots.size) positionOf(roots[index]) else visibleNodes.size
            roots.add(index, node)
            insertSubtreeRows(rowPosition, node)
            return
        }

        require(parent in this) { "The parent node is not part of this adapter's tree." }
        val wasLeaf = parent.isLeaf
        val nextSibling = parent.children.getOrNull(index)
        parent.insertChild(index, node)

        val parentPosition = positionOf(parent)
        if (parentPosition == RecyclerView.NO_POSITION) return // Parent is hidden.
        if (parent.isExpanded) {
            // Rows go right before the next sibling's rows, or after the parent's last
            // visible descendant (visibleNodes doesn't contain the new node yet).
            val rowPosition = if (nextSibling != null) {
                positionOf(nextSibling)
            } else {
                parentPosition + 1 + countVisibleDescendants(parentPosition)
            }
            insertSubtreeRows(rowPosition, node)
        }
        // A leaf that gains its first child needs to show an expansion indicator.
        if (wasLeaf) notifyItemChanged(parentPosition, PAYLOAD_EXPANSION_CHANGED)
    }

    /**
     * Removes [node] and its whole subtree from the tree.
     *
     * @return `false` if [node] isn't part of this adapter's tree.
     */
    public fun removeNode(node: TreeNode<T>): Boolean {
        if (node !in this) return false
        val parent = node.parent
        val position = positionOf(node)
        if (position != RecyclerView.NO_POSITION) {
            val count = 1 + countVisibleDescendants(position)
            visibleNodes.subList(position, position + count).clear()
            isMaxRowWidthDirty = true
            notifyItemRangeRemoved(position, count)
        }
        if (parent == null) roots.remove(node) else parent.removeChild(node)

        // A parent that lost its last child no longer shows an expansion indicator.
        if (parent != null && parent.isLeaf) {
            val parentPosition = positionOf(parent)
            if (parentPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(parentPosition, PAYLOAD_EXPANSION_CHANGED)
            }
        }
        return true
    }

    /**
     * Notifies that the content of [node] (typically its [TreeNode.value]) changed: its cached
     * row width is dropped and its row is rebound with [payload].
     *
     * For purely visual changes that can't affect the row width (selection, highlighting),
     * call `notifyItemChanged(position, payload)` directly instead.
     */
    @JvmOverloads
    public fun notifyNodeChanged(node: TreeNode<T>, payload: Any? = null) {
        node.invalidateRowWidth()
        isMaxRowWidthDirty = true
        val position = positionOf(node)
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position, payload)
    }

    /**
     * Drops all cached row widths, e.g. after the text size or indentation changed, and asks
     * attached [TreeLayoutManager]s to recompute their content width.
     */
    public fun invalidateRowWidths() {
        rowWidthStamp++
        isMaxRowWidthDirty = true
        for (recyclerView in attachedRecyclerViews) {
            (recyclerView.layoutManager as? TreeLayoutManager)?.invalidateContentWidth()
        }
    }

    // endregion

    // region Internals

    private fun insertSubtreeRows(position: Int, node: TreeNode<T>) {
        val rows = arrayListOf(node)
        if (node.isExpanded) collectVisible(node.children, rows)
        visibleNodes.addAll(position, rows)
        onRowsAdded(rows)
        notifyItemRangeInserted(position, rows.size)
    }

    private fun rebuildVisibleNodes() {
        visibleNodes.clear()
        collectVisible(roots, visibleNodes)
        isMaxRowWidthDirty = true
    }

    // Appends `nodes` and, recursively, the children of expanded ones, in pre-order.
    private fun collectVisible(nodes: List<TreeNode<T>>, out: MutableList<TreeNode<T>>) {
        for (node in nodes) {
            out += node
            if (node.isExpanded) collectVisible(node.children, out)
        }
    }

    // Visible descendants form a contiguous block right after the node, made of the rows
    // that are deeper than the node itself.
    private fun countVisibleDescendants(position: Int): Int {
        val depth = visibleNodes[position].depth
        var end = position + 1
        while (end < visibleNodes.size && visibleNodes[end].depth > depth) end++
        return end - position - 1
    }

    private inline fun forEachNode(action: (TreeNode<T>) -> Unit) {
        for (root in roots) {
            action(root)
            for (node in root.descendants()) action(node)
        }
    }

    // Adding rows can only increase the maximum, so it is updated incrementally
    // unless a full recomputation is already pending.
    private fun onRowsAdded(rows: List<TreeNode<T>>) {
        val calculator = rowWidthCalculator ?: return
        if (isMaxRowWidthDirty) return
        for (node in rows) cachedMaxRowWidth = max(cachedMaxRowWidth, rowWidthOf(node, calculator))
    }

    private fun rowWidthOf(node: TreeNode<T>, calculator: RowWidthCalculator<T>): Int {
        if (node.cachedRowWidthStamp != rowWidthStamp) {
            node.cachedRowWidth = calculator.calculateRowWidth(node)
            node.cachedRowWidthStamp = rowWidthStamp
        }
        return node.cachedRowWidth
    }

    // endregion
}
