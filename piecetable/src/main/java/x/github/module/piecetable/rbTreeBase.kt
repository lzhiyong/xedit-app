/*
 * Copyright © 2022 Github Lzhiyong
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

/*
 * Derived from Visual Studio Code (https://github.com/microsoft/vscode),
 * Copyright (c) 2015 - present Microsoft Corporation, under the MIT License: see
 * LICENSE-vscode at the root of this module. Ported from
 * src/vs/editor/common/model/pieceTreeTextBuffer/rbTreeBase.ts, and changed since.
 */

package x.github.module.piecetable

import x.github.module.piecetable.common.*


// node color
internal enum class NodeColor {
    Black, Red
}

/**
 * A red black tree node.
 *
 * Note: this is deliberately *not* a data class. Nodes are compared by reference everywhere in
 * the tree (`===`), and a generated structural equals/hashCode derived from `piece` + `color`
 * would make two unrelated nodes look like the same node.
 *
 * A node does not know its tree. The walking methods take the tree's sentinel ([nil]) as a
 * parameter: every tree has its own sentinel, see PieceTreeBase.sentinel.
 */
internal class TreeNode(var piece: Piece, var color: NodeColor) {
    // Augmentation: size_left / lf_left are the total length / line feed count of the LEFT
    // subtree only (not including this node). Together with the node's own piece they allow an
    // O(log n) descent by offset or by line. Every rotation, insert and delete below has to
    // keep them exact; nothing ever recomputes them from scratch.
    // size of the left subtree (not inorder)
    var size_left: Int = 0
    // line feeds cnt in the left subtree (not in order)
    var lf_left: Int = 0
    var parent: TreeNode = this
    var left: TreeNode = this
    var right: TreeNode = this
    
    // A removed node links to DETACHED, and DETACHED links to itself, so walking from a removed
    // node would never end. That is always a bug in the caller (e.g. a node kept across an edit):
    // fail loudly instead of hanging.
    private fun checkAttached() {
        if (this.parent === DETACHED) {
            throw IllegalStateException("the node was removed from the tree, it cannot be walked from")
        }
    }

    fun root(nil: TreeNode): TreeNode {
        checkAttached()
        var node: TreeNode = this
        while(node.parent !== nil) {
            node = node.parent
        }
        return node
    }
    
    // In-order successor / predecessor; [nil] (the tree's sentinel) when there is none. Throws
    // IllegalStateException for a removed node (see checkAttached).
    fun next(nil: TreeNode): TreeNode {
        checkAttached()
        if (this.right !== nil) {
            return leftest(this.right, nil)
        }

        var node: TreeNode = this
        while (node.parent !== nil) {
            if (node.parent.left === node) {
                break
            }
            node = node.parent
        }

        if (node.parent === nil) {
            return nil
        } else {
            return node.parent
        }
    }

    fun prev(nil: TreeNode): TreeNode {
        checkAttached()
        if (this.left !== nil) {
            return rightest(this.left, nil)
        }

        var node: TreeNode = this

        while (node.parent !== nil) {
            if (node.parent.right === node) {
                break
            }
            node = node.parent
        }

        if (node.parent === nil) {
            return nil
        } else {
            return node.parent
        }
    }

    // Called on a node removed from the tree. Its links are pointed at DETACHED (not the sentinel),
    // so PieceTreeSearchCache.validate() can recognise a removed node by `parent === DETACHED`,
    // and next() / prev() / root() refuse to walk from it.
    fun detach() {
        this.parent = DETACHED
        this.left = DETACHED
        this.right = DETACHED
    }
    
    // only the local state, printing the children would recurse over the whole tree
    override fun toString(): String {
        return "TreeNode(piece=${this.piece}, color=${this.color}, " +
            "size_left=${this.size_left}, lf_left=${this.lf_left})"
    }
}

// The piece of the marker nodes below: no text, no line breaks, so it adds nothing to any
// size_left / lf_left sum
internal val EMPTY_PIECE = Piece(0, BufferCursor(0, 0), BufferCursor(0, 0), 0, 0)

// Marker nodes:
//  - the SENTINEL ("nil" in CLRS) is every leaf and the root's parent: BLACK, links to itself.
//    rbDelete temporarily writes sentinel.parent (classic CLRS trick, x may be the sentinel)
//    and restores it with resetSentinel(). It is written to, so every tree owns one
//    (PieceTreeBase.sentinel, made by newSentinel()); trees share no mutable state and
//    different buffers can be edited on different threads at the same time.
//  - DETACHED is what a removed node links to (see TreeNode.detach). It has no other meaning:
//    "no node found" is a null NodePosition. Its own links point to itself. It is only ever
//    compared against, never written, so one instance is shared by all trees.
// (Formerly NPIE / NULL / NPOS and a process-wide SENTINEL.)
internal val DETACHED = TreeNode(EMPTY_PIECE, NodeColor.Red)

// A fresh sentinel: black, links to itself (TreeNode links to itself until it is linked in)
internal fun newSentinel(): TreeNode = TreeNode(EMPTY_PIECE, NodeColor.Black)

// Puts every field of a tree's sentinel back to its initial value
internal fun restoreSentinel(nil: TreeNode) {
    nil.parent = nil
    nil.left = nil
    nil.right = nil
    nil.color = NodeColor.Black
    nil.size_left = 0
    nil.lf_left = 0
    nil.piece = EMPTY_PIECE
}

internal fun leftest(node: TreeNode, nil: TreeNode): TreeNode {
    var x: TreeNode = node
    while (x.left !== nil) {
        x = x.left
    }
    return x
}

internal fun rightest(node: TreeNode, nil: TreeNode): TreeNode {
    var y: TreeNode = node
    while (y.right !== nil) {
        y = y.right
    }
    return y
}

// Total length / line feed count of a whole subtree: walk the right spine and add each node's
// left subtree (size_left / lf_left) plus its own piece. O(log n).
internal tailrec fun calculateSize(node: TreeNode, nil: TreeNode, size: Int = 0): Int {
    if (node === nil) {
        return size
    }
    return calculateSize(node.right, nil, size + node.size_left + node.piece.length)
}

internal tailrec fun calculateLF(node: TreeNode, nil: TreeNode, count: Int = 0): Int {
    if (node === nil) {
        return count
    }
    return calculateLF(node.right, nil, count + node.lf_left + node.piece.lineFeedCnt)
}

internal fun resetSentinel(tree: PieceTreeBase) {
    tree.sentinel.parent = tree.sentinel
}

/**
 * Restores every field of the shared DETACHED node. It is never written by correct code, so this
 * is a defensive no-op in practice (sentinels are per tree now, see restoreSentinel).
 */
internal fun resetSharedNodes() {
    DETACHED.parent = DETACHED
    DETACHED.left = DETACHED
    DETACHED.right = DETACHED
    DETACHED.color = NodeColor.Red
    DETACHED.size_left = 0
    DETACHED.lf_left = 0
    DETACHED.piece = EMPTY_PIECE
}

/**
 * Standard rotations plus metadata repair. Only the node that gains or loses a left subtree
 * needs fixing:
 *
 *        x                  y
 *       / \    left        / \
 *      a   y   ----->     x   c       y.size_left grows by x's whole left part + x's piece
 *         / \            / \
 *        b   c          a   b         x.size_left is unchanged (a stays its left subtree)
 *
 * rightRotate is the mirror image: y.size_left shrinks by x.size_left + x.piece.length.
 */
internal fun leftRotate(tree: PieceTreeBase, x: TreeNode) {
    val y = x.right
    // fix size_left
    y.size_left += x.size_left + x.piece.length
    y.lf_left += x.lf_left + x.piece.lineFeedCnt
    x.right = y.left

    if (y.left !== tree.sentinel) {
        y.left.parent = x
    }
    y.parent = x.parent
    if (x.parent === tree.sentinel) {
        tree.root = y
    } else if (x.parent.left === x) {
        x.parent.left = y
    } else {
        x.parent.right = y
    }
    y.left = x
    x.parent = y
}

internal fun rightRotate(tree: PieceTreeBase, y: TreeNode) {
    val x = y.left
    y.left = x.right
    if (x.right !== tree.sentinel) {
        x.right.parent = y
    }
    x.parent = y.parent

    // fix size_left
    y.size_left -= x.size_left + x.piece.length
    y.lf_left -= x.lf_left + x.piece.lineFeedCnt

    if (y.parent === tree.sentinel) {
        tree.root = x
    } else if (y === y.parent.right) {
        y.parent.right = x
    } else {
        y.parent.left = x
    }

    x.right = y
    y.parent = x
}

/**
 * CLRS red-black delete with size_left / lf_left maintenance.
 *
 * z is the node to delete, y the node that is physically removed (z itself, or z's in-order
 * successor when z has two children, which then takes z's place), and x the child moving into
 * y's old position (possibly the sentinel, whose parent is set temporarily). Metadata is repaired
 * in three steps: recompute along x's path after unlinking y, copy z's counters into y and
 * recompute from y, then fix x.parent once more before the colour fix-up rotations (which keep
 * the metadata right on their own).
 */
internal fun rbDelete(tree: PieceTreeBase, node: TreeNode) {
    var x: TreeNode
    var y: TreeNode
    val z: TreeNode = node

    if (z.left === tree.sentinel) {
        y = z
        x = y.right
    } else if (z.right === tree.sentinel) {
        y = z
        x = y.left
    } else {
        y = leftest(z.right, tree.sentinel)
        x = y.right
    }

    if (y === tree.root) {
        tree.root = x
        // if x is null, we are removing the only node
        x.color = NodeColor.Black
        z.detach()
        resetSentinel(tree)
        tree.root.parent = tree.sentinel
        return
    }

    val yWasRed = (y.color == NodeColor.Red)

    if (y === y.parent.left) {
        y.parent.left = x
    } else {
        y.parent.right = x
    }

    if (y === z) {
        x.parent = y.parent
        recomputeTreeMetadata(tree, x)
    } else {
        if (y.parent === z) {
            x.parent = y
        } else {
            x.parent = y.parent
        }

        // as we make changes to x's hierarchy, update size_left of subtree first
        recomputeTreeMetadata(tree, x)

        y.left = z.left
        y.right = z.right
        y.parent = z.parent
        y.color = z.color

        if (z === tree.root) {
            tree.root = y
        } else {
            if (z === z.parent.left) {
                z.parent.left = y
            } else {
                z.parent.right = y
            }
        }

        if (y.left !== tree.sentinel) {
            y.left.parent = y
        }
        if (y.right !== tree.sentinel) {
            y.right.parent = y
        }
        // update metadata
        // we replace z with y, so in this sub tree, the length change is z.item.length
        y.size_left = z.size_left
        y.lf_left = z.lf_left
        recomputeTreeMetadata(tree, y)
    }

    z.detach()

    if (x.parent.left === x) {
        val newSizeLeft = calculateSize(x, tree.sentinel)
        val newLFLeft = calculateLF(x, tree.sentinel)
        if (newSizeLeft != x.parent.size_left || newLFLeft != x.parent.lf_left) {
            val delta = newSizeLeft - x.parent.size_left
            val lf_delta = newLFLeft - x.parent.lf_left
            x.parent.size_left = newSizeLeft
            x.parent.lf_left = newLFLeft
            updateTreeMetadata(tree, x.parent, delta, lf_delta)
        }
    }

    recomputeTreeMetadata(tree, x.parent)

    if (yWasRed) {
        resetSentinel(tree)
        return
    }

    // RB-DELETE-FIXUP
    var w: TreeNode
    while (x !== tree.root && x.color == NodeColor.Black) {
        if (x === x.parent.left) {
            w = x.parent.right

            if (w.color == NodeColor.Red) {
                w.color = NodeColor.Black
                x.parent.color = NodeColor.Red
                leftRotate(tree, x.parent)
                w = x.parent.right
            }

            if (w.left.color == NodeColor.Black && w.right.color == NodeColor.Black) {
                w.color = NodeColor.Red
                x = x.parent
            } else {
                if (w.right.color == NodeColor.Black) {
                    w.left.color = NodeColor.Black
                    w.color = NodeColor.Red
                    rightRotate(tree, w)
                    w = x.parent.right
                }

                w.color = x.parent.color
                x.parent.color = NodeColor.Black
                w.right.color = NodeColor.Black
                leftRotate(tree, x.parent)
                x = tree.root
            }
        } else {
            w = x.parent.left

            if (w.color == NodeColor.Red) {
                w.color = NodeColor.Black
                x.parent.color = NodeColor.Red
                rightRotate(tree, x.parent)
                w = x.parent.left
            }

            if (w.left.color == NodeColor.Black && w.right.color == NodeColor.Black) {
                w.color = NodeColor.Red
                x = x.parent
            } else {
                if (w.left.color == NodeColor.Black) {
                    w.right.color = NodeColor.Black
                    w.color = NodeColor.Red
                    leftRotate(tree, w)
                    w = x.parent.left
                }

                w.color = x.parent.color
                x.parent.color = NodeColor.Black
                w.left.color = NodeColor.Black
                rightRotate(tree, x.parent)
                x = tree.root
            }
        }
    }
    x.color = NodeColor.Black
    resetSentinel(tree)
}

// CLRS insert fix-up. The new node is already linked in as a red leaf; its piece is first
// added to the ancestors' size_left / lf_left (recomputeTreeMetadata), then colours are fixed.
internal fun fixInsert(tree: PieceTreeBase, node: TreeNode) {
    var x: TreeNode = node
    recomputeTreeMetadata(tree, x)

    while (x !== tree.root && x.parent.color == NodeColor.Red) {
        if (x.parent === x.parent.parent.left) {
            val y = x.parent.parent.right

            if (y.color == NodeColor.Red) {
                x.parent.color = NodeColor.Black
                y.color = NodeColor.Black
                x.parent.parent.color = NodeColor.Red
                x = x.parent.parent
            } else {
                if (x === x.parent.right) {
                    x = x.parent
                    leftRotate(tree, x)
                }
                x.parent.color = NodeColor.Black
                x.parent.parent.color = NodeColor.Red
                rightRotate(tree, x.parent.parent)
            }
        } else {
            val y = x.parent.parent.left

            if (y.color == NodeColor.Red) {
                x.parent.color = NodeColor.Black
                y.color = NodeColor.Black
                x.parent.parent.color = NodeColor.Red
                x = x.parent.parent
            } else {
                if (x === x.parent.left) {
                    x = x.parent
                    rightRotate(tree, x)
                }
                x.parent.color = NodeColor.Black
                x.parent.parent.color = NodeColor.Red
                leftRotate(tree, x.parent.parent)
            }
        }
    }
    tree.root.color = NodeColor.Black
}

// A node's piece changed by (delta, lineFeedCntDelta): add that to size_left / lf_left of every
// ancestor that has the node in its LEFT subtree. O(log n).
internal fun updateTreeMetadata(
    tree: PieceTreeBase,
    node: TreeNode,
    delta: Int,
    lineFeedCntDelta: Int
) {
    var x = node
    // node length change or line feed count change
    while (x !== tree.root && x !== tree.sentinel && x !== DETACHED) {
        if (x.parent.left === x) {
            x.parent.size_left += delta
            x.parent.lf_left += lineFeedCntDelta
        }
        x = x.parent
    }
}

// After the subtree structure around `node` changed: climb to the first ancestor that has the
// change in its LEFT subtree, recompute that ancestor's size_left / lf_left from scratch and
// propagate the resulting delta up to the root. Nothing to do when the change only touched
// right spines (the counters only describe left subtrees).
internal fun recomputeTreeMetadata(tree: PieceTreeBase, node: TreeNode) {
    var delta = 0
    var lf_delta = 0
    var x: TreeNode = node

    if (x === tree.root) {
        return
    }

    if (delta == 0) {
        // go upwards till the node whose left subtree is changed.
        while (x !== tree.root && x === x.parent.right) {
            x = x.parent
        }

        if (x === tree.root) {
            // well, it means we add a node to the end (inorder)
            return
        }

        // x is the node whose right subtree is changed.
        x = x.parent

        delta = calculateSize(x.left, tree.sentinel) - x.size_left
        lf_delta = calculateLF(x.left, tree.sentinel) - x.lf_left
        x.size_left += delta
        x.lf_left += lf_delta
    }

    // go upwards till root. O(logN)
    while (x !== tree.root && (delta != 0 || lf_delta != 0)) {
        if (x.parent.left === x) {
            x.parent.size_left += delta
            x.parent.lf_left += lf_delta
        }
        x = x.parent
    }
}

internal fun traversalTree(node: TreeNode, nil: TreeNode) {
    if (node !== nil) {
        println(node)
        traversalTree(node.left, nil)
        traversalTree(node.right, nil)
    }
}
