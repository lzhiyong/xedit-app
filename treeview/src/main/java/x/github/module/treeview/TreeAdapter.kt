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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.atomic.AtomicBoolean
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
 * Every operation (expand, collapse, insert, remove, load) edits `visibleNodes` in place and
 * dispatches the narrowest possible notification: a range insertion/removal for the affected
 * subtree plus a [PAYLOAD_EXPANSION_CHANGED] change for the node whose indicator changed.
 * RecyclerView therefore keeps untouched rows, plays item animations, and [TreeLayoutManager]
 * learns about structural changes.
 *
 * **Expandability.** [isExpandable] decides whether a row can be expanded. By default that is
 * a node with children, or with children that aren't loaded yet. Override it to make other
 * nodes expandable, e.g. empty directories.
 *
 * **Lazy loading.** See [childLoader]. Loads are requested while the adapter state is being
 * updated but started only once it is consistent again (`startPendingLoads`), so a loader
 * that answers synchronously is handled correctly. Every request is identified by an object
 * stored on the node; results of any other (outdated) request are dropped.
 *
 * **Single-child chains.** With [autoExpandSingleChild], expanding a node keeps expanding
 * down a chain of only children (`x` > `github` > `module` > `treeview`). The chain's states
 * are updated first and its rows are inserted with a single notification. If the chain reaches
 * a node whose children aren't loaded, it continues once they are.
 *
 * **Row widths.** If a [rowWidthCalculator] is set, the adapter tracks the widest visible
 * row and exposes it through [ContentWidthProvider], which [TreeLayoutManager] picks up
 * automatically to get an exact horizontal scroll range. Widths are cached per node and
 * stamped with a generation number, so invalidating every cached width is O(1). The maximum
 * is updated incrementally when rows are added and recomputed lazily when rows are removed.
 *
 * **Clicks.** Subclasses implement [onCreateNodeViewHolder] instead of `onCreateViewHolder`;
 * the adapter then installs click and long-click handling on the item view once per view
 * holder. A click notifies [onNodeClickListener] and, with [toggleOnClick], toggles the row; a
 * long click notifies [onNodeLongClickListener]. The adapter position is resolved at event
 * time (`bindingAdapterPosition`), so listeners always receive the current node.
 *
 * **Binding.** Subclasses implement [onBindNode] instead of `onBindViewHolder`. Partial
 * rebinds with payloads are supported; [PAYLOAD_EXPANSION_CHANGED] is sent when only the
 * expansion indicator / load state of a row needs to be updated.
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
         * Payload sent with `notifyItemChanged` when only the state of a node's indicator
         * changed: it was expanded or collapsed, gained its first or lost its last child, or
         * its [TreeNode.loadState] changed.
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

    // Loads requested during the current operation, started once the state is consistent.
    private val loadsToStart = ArrayList<LoadRequest>()
    private var isStartingLoads = false
    private val mainHandler = Handler(Looper.getMainLooper())

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
     * Loads the children of lazy nodes (created with `childrenLoaded = false`).
     *
     * Life cycle of a lazy node:
     * 1. [TreeNode.LoadState.NOT_LOADED]: shown as expandable.
     * 2. Expanding it switches it to [TreeNode.LoadState.LOADING]; the row is rebound with
     *    [PAYLOAD_EXPANSION_CHANGED] and the loader is called.
     * 3. On success it becomes [TreeNode.LoadState.LOADED] and the children appear below it,
     *    if it is still expanded. If it turns out to be empty and [isExpandable] says no,
     *    it is collapsed again.
     * 4. On failure it becomes [TreeNode.LoadState.LOAD_FAILED] and is collapsed; expanding it
     *    again retries. [onChildrenLoadFailed] is called.
     *
     * Collapsing a node while it loads doesn't cancel the request: the children are still
     * added, just not shown. Without a loader, lazy nodes expand to nothing.
     */
    public var childLoader: ChildLoader<T>? = null

    /**
     * Called after a node has been expanded or collapsed through this adapter, including
     * nodes expanded as part of a single-child chain and nodes collapsed because loading
     * failed. Not called by [expandAll], [collapseAll] or [setRoots].
     */
    public var onNodeExpansionChanged: ((node: TreeNode<T>, isExpanded: Boolean) -> Unit)? = null

    /** Called on the main thread when [childLoader] reports a failure for a node. */
    public var onChildrenLoadFailed: ((node: TreeNode<T>, error: Throwable?) -> Unit)? = null

    /**
     * When `true`, [expand], [toggle] and [toggleAt] keep expanding while the expanded node has
     * exactly one child that is expandable and allowed by [canAutoExpand]. Expansion stops at
     * the first node with several children, or when the only child is a leaf or known to be
     * empty. A lazy node in the chain is loaded and the chain continues afterwards.
     *
     * Collapsing isn't affected: inner nodes of a chain keep their expanded state, so the same
     * chain reappears when its head is expanded again. [reveal] never auto-expands.
     */
    public var autoExpandSingleChild: Boolean = false

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

    /** Called when the row of a node is clicked; see [toggleOnClick]. */
    public var onNodeClickListener: OnNodeClickListener<T>? = null

    /**
     * Called when the row of a node is long-clicked. Rows are long-clickable only while a
     * listener is set (applied on the next bind), so accessibility services don't announce a
     * long-press action that does nothing.
     */
    public var onNodeLongClickListener: OnNodeLongClickListener<T>? = null

    /**
     * When `true` (default), clicking a row toggles it after [onNodeClickListener] has been
     * notified. Set it to `false` to decide yourself, e.g. to toggle only when the expansion
     * indicator is clicked, or on double click.
     */
    public var toggleOnClick: Boolean = true

    /**
     * Creates the view holder for [viewType], like `onCreateViewHolder`. Don't install click
     * or long-click listeners on the item view: the adapter does, see [onNodeClickListener]
     * and [onNodeLongClickListener]. Listeners on child views are fine.
     */
    public abstract fun onCreateNodeViewHolder(parent: ViewGroup, viewType: Int): VH

    /** Binds [node] to [holder]. [payloads] is empty for a full bind. */
    public abstract fun onBindNode(holder: VH, node: TreeNode<T>, payloads: List<Any>)

    /**
     * Whether the row of [node] can be expanded, i.e. should show an expansion indicator and
     * react to [expand] / [toggle]. The default is: the node has children, or its children
     * haven't been loaded yet.
     *
     * Override to make nodes without children expandable, e.g. empty directories:
     * `super.isExpandable(node) || node.value.isDirectory`. After a change in what this method
     * returns, rebind the affected rows (e.g. `notifyItemRangeChanged(0, itemCount,
     * PAYLOAD_EXPANSION_CHANGED)`).
     */
    public open fun isExpandable(node: TreeNode<T>): Boolean =
        !node.isLeaf || node.loadState != TreeNode.LoadState.LOADED

    /**
     * Decides whether [child], the only child of a node that was just expanded, may be
     * expanded automatically when [autoExpandSingleChild] is on. Only called for children
     * that are [isExpandable] and not known to be empty. Override to restrict chains to
     * certain node types, e.g. only directories.
     */
    protected open fun canAutoExpand(child: TreeNode<T>): Boolean = true

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val holder = onCreateNodeViewHolder(parent, viewType)
        // Installed once per holder; the node is looked up when the event happens, because
        // the holder is rebound to other nodes and rows move as the tree changes.
        holder.itemView.setOnClickListener { view -> dispatchClick(holder, view) }
        holder.itemView.setOnLongClickListener { view -> dispatchLongClick(holder, view) }
        return holder
    }

    final override fun onBindViewHolder(holder: VH, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    final override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        // setOnLongClickListener() made the view long-clickable; keep that in line with
        // whether a listener currently exists.
        holder.itemView.isLongClickable = onNodeLongClickListener != null
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

    /**
     * Replaces the whole tree. Every node in [newRoots] must be a root (have no parent).
     * Lazy nodes that are displayed expanded start loading right away.
     */
    @SuppressLint("NotifyDataSetChanged")
    public fun setRoots(newRoots: List<TreeNode<T>>) {
        // Copy first: newRoots may be rootNodes itself.
        val copy = newRoots.toList()
        require(copy.all { it.parent == null }) { "Root nodes must not have a parent." }
        roots.clear()
        roots.addAll(copy)
        rebuildVisibleNodes()
        notifyDataSetChanged()
        requestLoadsForExpandedRows(visibleNodes)
        startPendingLoads()
    }

    /** Expands every node that has children. Doesn't trigger lazy loading. */
    @SuppressLint("NotifyDataSetChanged")
    public fun expandAll() {
        forEachNode { if (!it.isLeaf) it.isExpanded = true }
        rebuildVisibleNodes()
        notifyDataSetChanged()
    }

    /** Collapses every node, leaving only the roots visible. */
    @SuppressLint("NotifyDataSetChanged")
    public fun collapseAll() {
        forEachNode {
            it.isExpanded = false
            it.resumeChainAfterLoad = false
        }
        rebuildVisibleNodes()
        notifyDataSetChanged()
    }

    // endregion

    // region Expansion

    /**
     * Expands [node] (and its single-child chain if [autoExpandSingleChild] is on), loading
     * its children first if needed. If the node itself is hidden because an ancestor is
     * collapsed, only its state changes.
     *
     * @return `false` if the node isn't [isExpandable] or is already expanded.
     */
    public fun expand(node: TreeNode<T>): Boolean =
        expandInternal(node, positionOf(node), autoExpandSingleChild)

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
        return if (node.isExpanded) {
            collapseInternal(node, position)
        } else {
            expandInternal(node, position, autoExpandSingleChild)
        }
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
        // No chain expansion: only the path to the node should open.
        for (ancestor in node.ancestors().toList().asReversed()) {
            expandInternal(ancestor, positionOf(ancestor), autoExpandChain = false)
        }
        return positionOf(node)
    }

    // `position` is the node's adapter position, or NO_POSITION if it isn't displayed.
    private fun expandInternal(node: TreeNode<T>, position: Int, autoExpandChain: Boolean): Boolean {
        if (node.isExpanded || !isExpandable(node)) return false

        // 1. Update states: the node, optionally its single-child chain, and load requests
        //    for nodes whose children are unknown. Rows are computed afterwards, so that the
        //    whole change becomes one inserted range.
        val newlyExpanded = arrayListOf(node)
        node.isExpanded = true
        if (autoExpandChain) {
            expandSingleChildChain(node, newlyExpanded)
        } else if (node.loadState != TreeNode.LoadState.LOADED) {
            requestLoad(node, continueChain = false)
        }

        // 2. Insert the rows that became visible. Chain nodes are new rows, so they are bound
        //    from scratch and need no separate notification.
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
            insertVisibleChildRows(node.children, position)
        }

        dispatchExpansionChanged(newlyExpanded, isExpanded = true)
        startPendingLoads()
        return true
    }

    private fun collapseInternal(node: TreeNode<T>, position: Int): Boolean {
        if (!node.isExpanded) return false
        node.isExpanded = false
        node.resumeChainAfterLoad = false
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

    /**
     * Walks down from [head] (already expanded) while each node has exactly one child that may
     * be auto-expanded, expanding those children and adding them to [out]. A node in the walk
     * whose children aren't loaded gets a load request that resumes the walk when it completes.
     */
    private fun expandSingleChildChain(head: TreeNode<T>, out: MutableList<TreeNode<T>>) {
        var current = head
        while (true) {
            if (current.loadState != TreeNode.LoadState.LOADED) {
                requestLoad(current, continueChain = true)
                return
            }
            val onlyChild = current.children.singleOrNull() ?: return // None or several: stop.
            if (!isExpandable(onlyChild) || onlyChild.isKnownEmpty) return // Leaf / empty: stop.
            if (!canAutoExpand(onlyChild)) return
            if (!onlyChild.isExpanded) {
                onlyChild.isExpanded = true
                out += onlyChild
            }
            current = onlyChild
        }
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
            insertRows(rowPosition, collectSubtreeRows(node))
            startPendingLoads()
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
            insertRows(rowPosition, collectSubtreeRows(node))
        }
        // A leaf that gains its first child may need a different indicator or icon.
        if (wasLeaf) notifyItemChanged(parentPosition, PAYLOAD_EXPANSION_CHANGED)
        startPendingLoads()
    }

    /**
     * Removes [node] and its whole subtree from the tree. A pending load of a removed node is
     * dropped when it completes.
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

        if (parent != null && parent.isLeaf) {
            // A parent that can't be expanded anymore must not stay expanded; otherwise a
            // child inserted later would show up although the parent looks collapsed.
            if (!isExpandable(parent)) parent.isExpanded = false
            val parentPosition = positionOf(parent)
            if (parentPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(parentPosition, PAYLOAD_EXPANSION_CHANGED)
            }
        }
        return true
    }

    /**
     * Discards the children of [node] and loads them again through [childLoader], e.g. to
     * pick up changes on disk. The node keeps its expansion state; if it is expanded, loading
     * starts immediately, otherwise on the next expansion. A load in progress is superseded.
     *
     * @throws IllegalArgumentException if [node] isn't part of this adapter's tree.
     */
    public fun reloadChildren(node: TreeNode<T>) {
        require(node in this) { "The node is not part of this adapter's tree." }
        val position = positionOf(node)
        if (position != RecyclerView.NO_POSITION) {
            val count = countVisibleDescendants(position)
            if (count > 0) {
                visibleNodes.subList(position + 1, position + 1 + count).clear()
                isMaxRowWidthDirty = true
                notifyItemRangeRemoved(position + 1, count)
            }
        }
        node.removeAllChildren()
        node.pendingLoadRequest = null // Any result still in flight is now outdated.
        node.resumeChainAfterLoad = false
        node.loadState = TreeNode.LoadState.NOT_LOADED
        if (node.isExpanded) requestLoad(node, continueChain = false)
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
        startPendingLoads()
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

    // region Lazy loading

    /**
     * One load of the children of [node]. Doubles as the request's identity: the node stores
     * the instance it waits for in `pendingLoadRequest`, and results of other instances are
     * outdated. Guarantees a single delivery, on the main thread.
     */
    private inner class LoadRequest(val node: TreeNode<T>) : ChildLoadCallback<T> {

        private val isDelivered = AtomicBoolean(false)

        override fun onLoaded(children: List<TreeNode<T>>) {
            val snapshot = children.toList() // The caller may reuse its list.
            deliver { completeLoad(this, snapshot) }
        }

        override fun onFailed(error: Throwable?) {
            deliver { failLoad(this, error) }
        }

        private fun deliver(action: () -> Unit) {
            if (!isDelivered.compareAndSet(false, true)) return
            if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
        }
    }

    // Marks `node` as loading and queues the request; see startPendingLoads().
    private fun requestLoad(node: TreeNode<T>, continueChain: Boolean) {
        if (childLoader == null) return
        if (node.loadState == TreeNode.LoadState.LOADING) {
            // Already loading (e.g. collapsed and expanded again): just keep the chain flag.
            if (continueChain) node.resumeChainAfterLoad = true
            return
        }
        val request = LoadRequest(node)
        node.pendingLoadRequest = request
        node.loadState = TreeNode.LoadState.LOADING
        node.resumeChainAfterLoad = continueChain
        loadsToStart += request
    }

    // Lazy nodes that are displayed expanded (e.g. created with isExpanded = true) need a load.
    private fun requestLoadsForExpandedRows(rows: List<TreeNode<T>>) {
        for (node in rows) {
            val needsLoad = node.loadState == TreeNode.LoadState.NOT_LOADED ||
                node.loadState == TreeNode.LoadState.LOAD_FAILED
            if (node.isExpanded && needsLoad) requestLoad(node, continueChain = false)
        }
    }

    /**
     * Calls the loader for every queued request. Called at the end of each public operation,
     * when the adapter state is consistent, because a loader may deliver synchronously and
     * thereby re-enter the adapter. Loads requested by such a nested delivery are picked up by
     * the same loop.
     */
    private fun startPendingLoads() {
        if (isStartingLoads) return
        val loader = childLoader
        if (loader == null) {
            loadsToStart.clear()
            return
        }
        isStartingLoads = true
        try {
            while (loadsToStart.isNotEmpty()) {
                val request = loadsToStart.removeAt(0)
                // Skip requests superseded before they even started (e.g. by reloadChildren).
                if (request.node.pendingLoadRequest === request) loader.loadChildren(request.node, request)
            }
        } finally {
            isStartingLoads = false
        }
    }

    private fun completeLoad(request: LoadRequest, children: List<TreeNode<T>>) {
        val node = request.node
        if (node.pendingLoadRequest !== request) return // Outdated.
        node.pendingLoadRequest = null
        val resumeChain = node.resumeChainAfterLoad
        node.resumeChainAfterLoad = false
        if (node !in this) {
            node.loadState = TreeNode.LoadState.NOT_LOADED // Removed while loading.
            return
        }

        val firstNewIndex = node.children.size
        for (child in children) node.insertChild(node.children.size, child)
        val newChildren = node.children.subList(firstNewIndex, node.children.size).toList()
        node.loadState = TreeNode.LoadState.LOADED

        val changedExpansion = ArrayList<TreeNode<T>>()
        if (node.isExpanded && node.isLeaf && !isExpandable(node)) {
            // Known to be empty and not expandable: don't leave it expanded.
            node.isExpanded = false
            changedExpansion += node
        } else if (resumeChain && node.isExpanded) {
            expandSingleChildChain(node, changedExpansion)
        }

        val position = positionOf(node)
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
            if (node.isExpanded) insertVisibleChildRows(newChildren, position)
        }

        for (changed in changedExpansion) onNodeExpansionChanged?.invoke(changed, changed.isExpanded)
        startPendingLoads()
    }

    private fun failLoad(request: LoadRequest, error: Throwable?) {
        val node = request.node
        if (node.pendingLoadRequest !== request) return // Outdated.
        node.pendingLoadRequest = null
        node.resumeChainAfterLoad = false
        if (node !in this) {
            node.loadState = TreeNode.LoadState.NOT_LOADED
            return
        }
        node.loadState = TreeNode.LoadState.LOAD_FAILED
        val position = positionOf(node)
        // Collapse so that the next expansion retries; also rebinds the row.
        if (!collapseInternal(node, position) && position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_EXPANSION_CHANGED)
        }
        onChildrenLoadFailed?.invoke(node, error)
    }

    // endregion

    // region Internals

    private fun dispatchClick(holder: VH, view: View) {
        val position = holder.bindingAdapterPosition
        val node = visibleNodes.getOrNull(position) ?: return // Being removed or not bound.
        onNodeClickListener?.onNodeClick(node, position, view)
        // The listener may have changed the tree; only toggle if the row is still that node.
        if (toggleOnClick && visibleNodes.getOrNull(position) === node) toggleAt(position)
    }

    private fun dispatchLongClick(holder: VH, view: View): Boolean {
        val listener = onNodeLongClickListener ?: return false
        val position = holder.bindingAdapterPosition
        val node = visibleNodes.getOrNull(position) ?: return false
        return listener.onNodeLongClick(node, position, view)
    }

    /**
     * Inserts the visible rows of [children] of the expanded node displayed at [parentPosition]:
     * either all of its children, or a tail of newly added ones. Rows of earlier children, if
     * any, are already displayed right below the parent, so the new rows go after them.
     */
    private fun insertVisibleChildRows(children: List<TreeNode<T>>, parentPosition: Int) {
        if (children.isEmpty()) return
        val rows = ArrayList<TreeNode<T>>()
        collectVisible(children, rows)
        insertRows(parentPosition + 1 + countVisibleDescendants(parentPosition), rows)
    }

    private fun insertRows(position: Int, rows: List<TreeNode<T>>) {
        if (rows.isEmpty()) return
        visibleNodes.addAll(position, rows)
        onRowsAdded(rows)
        notifyItemRangeInserted(position, rows.size)
        requestLoadsForExpandedRows(rows)
    }

    private fun collectSubtreeRows(node: TreeNode<T>): List<TreeNode<T>> {
        val rows = arrayListOf(node)
        if (node.isExpanded) collectVisible(node.children, rows)
        return rows
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

    private fun dispatchExpansionChanged(nodes: List<TreeNode<T>>, isExpanded: Boolean) {
        val listener = onNodeExpansionChanged ?: return
        for (node in nodes) listener(node, isExpanded)
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
