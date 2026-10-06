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
 * A node of a tree displayed by [TreeAdapter]. The payload type [T] is arbitrary: files,
 * organization units, comment threads, anything hierarchical.
 *
 * ## Design
 *
 * - **Structure only.** A node stores its [value], its [parent] / [children] links and
 *   whether it is expanded. Which nodes are currently visible, and at which adapter
 *   position, is owned by [TreeAdapter].
 * - **Mutation rules.** The structural methods on this class ([addChild], [insertChild],
 *   [removeChild]) only change the data structure; they don't notify any adapter. Use them to
 *   build a tree *before* handing it to [TreeAdapter.setRoots]. Once a tree is displayed,
 *   change it through [TreeAdapter.insertNode], [TreeAdapter.removeNode],
 *   [TreeAdapter.expand] and [TreeAdapter.collapse] so that RecyclerView receives precise
 *   notifications. For the same reason [isExpanded] can only be set at construction time or
 *   through the adapter.
 * - **Cached depth.** [depth] is stored rather than computed, because the adapter compares
 *   depths in tight loops (e.g. when collapsing). It is kept up to date whenever a node is
 *   attached to or detached from a parent.
 * - **Identity.** Nodes are compared by identity, never by [value]; two nodes may hold equal
 *   values (e.g. two folders named "res").
 *
 * Not thread-safe; use it from the main thread once it is attached to an adapter.
 */
public class TreeNode<T> @JvmOverloads public constructor(
    /** The payload of this node. After changing it on a displayed node, call [TreeAdapter.notifyNodeChanged]. */
    public var value: T,
    isExpanded: Boolean = false,
) {
    private val mutableChildren = ArrayList<TreeNode<T>>()

    /** The direct children of this node, in display order. */
    public val children: List<TreeNode<T>>
        get() = mutableChildren

    /** The parent of this node, or `null` for a root node. */
    public var parent: TreeNode<T>? = null
        private set

    /** Distance from the root: 0 for root nodes, 1 for their children, and so on. */
    public var depth: Int = 0
        private set

    /**
     * Whether the children of this node are shown. Only meaningful for nodes with children.
     * Change it through [TreeAdapter.expand], [TreeAdapter.collapse] or [TreeAdapter.toggle].
     */
    public var isExpanded: Boolean = isExpanded
        internal set

    /** `true` if this node has no children. */
    public val isLeaf: Boolean
        get() = mutableChildren.isEmpty()

    /** The topmost ancestor of this node, or the node itself if it is a root. */
    public val root: TreeNode<T>
        get() {
            var node = this
            while (true) node = node.parent ?: return node
        }

    // Row width cache maintained by TreeAdapter. A stamp that doesn't match the adapter's
    // current stamp means the cached width is stale; 0 never matches (adapter stamps start at 1).
    internal var cachedRowWidth: Int = 0
    internal var cachedRowWidthStamp: Int = 0

    /** Appends [child] to the children of this node and returns this node, for chaining. */
    public fun addChild(child: TreeNode<T>): TreeNode<T> {
        insertChild(mutableChildren.size, child)
        return this
    }

    /**
     * Creates a child holding [value], configures it with [block] and appends it.
     * Allows building trees declaratively:
     * ```
     * val root = treeNode(dir("src"), isExpanded = true) {
     *     child(dir("main")) { child(file("App.kt")) }
     * }
     * ```
     */
    public inline fun child(
        value: T,
        isExpanded: Boolean = false,
        block: TreeNode<T>.() -> Unit = {},
    ): TreeNode<T> {
        val node = TreeNode(value, isExpanded).apply(block)
        addChild(node)
        return node
    }

    /**
     * Inserts [child] at [index] among the children of this node.
     *
     * @throws IllegalArgumentException if [child] already has a parent or the insertion
     *   would create a cycle.
     * @throws IndexOutOfBoundsException if [index] is not in `0..children.size`.
     */
    public fun insertChild(index: Int, child: TreeNode<T>) {
        require(child.parent == null) {
            "The node already has a parent; remove it from its parent first."
        }
        require(child !== this && !isDescendantOf(child)) {
            "Inserting the node would create a cycle."
        }
        mutableChildren.add(index, child)
        child.parent = this
        child.updateDepth(depth + 1)
    }

    /** Removes [child] from the children of this node. Returns `false` if it wasn't a child. */
    public fun removeChild(child: TreeNode<T>): Boolean {
        if (!mutableChildren.remove(child)) return false
        child.parent = null
        child.updateDepth(0)
        return true
    }

    /** `true` if [other] is a (direct or indirect) ancestor of this node. */
    public fun isDescendantOf(other: TreeNode<T>): Boolean = ancestors().any { it === other }

    /** The ancestors of this node, starting with its parent and ending with its root. */
    public fun ancestors(): Sequence<TreeNode<T>> = generateSequence(parent) { it.parent }

    /**
     * All descendants of this node in pre-order (the order in which a fully expanded tree is
     * displayed), excluding the node itself. Iterative, so it is safe for very deep trees.
     */
    public fun descendants(): Sequence<TreeNode<T>> = sequence {
        val stack = ArrayDeque<TreeNode<T>>()
        for (i in mutableChildren.indices.reversed()) stack.addLast(mutableChildren[i])
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            yield(node)
            val children = node.mutableChildren
            for (i in children.indices.reversed()) stack.addLast(children[i])
        }
    }

    internal fun invalidateRowWidth() {
        cachedRowWidthStamp = 0
    }

    // Invariant: a child's depth is always its parent's depth + 1, so a subtree whose root
    // already has the requested depth is consistent and the walk can stop there.
    private fun updateDepth(newDepth: Int) {
        if (depth == newDepth) return
        depth = newDepth
        invalidateRowWidth() // Indentation, and therefore the row width, depends on depth.
        for (child in mutableChildren) child.updateDepth(newDepth + 1)
    }

    override fun toString(): String =
        "TreeNode(value=$value, depth=$depth, children=${mutableChildren.size}, isExpanded=$isExpanded)"
}

/**
 * Creates a [TreeNode] holding [value] and configures it with [block],
 * typically adding children with [TreeNode.child].
 */
public inline fun <T> treeNode(
    value: T,
    isExpanded: Boolean = false,
    block: TreeNode<T>.() -> Unit = {},
): TreeNode<T> = TreeNode(value, isExpanded).apply(block)
