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

package io.github.module.piecetable

// node color
internal enum class NodeColor {
    Black, Red
}

internal data class TreeNode(var piece: Piece, var color: NodeColor) {
    // size of the left subtree (not inorder)
    var size_left: Int = 0
    // line feeds cnt in the left subtree (not in order)
    var lf_left: Int = 0
    var parent: TreeNode = this
    var left: TreeNode = this
    var right: TreeNode = this

    fun next(): TreeNode {
        if (this.right !== SENTINEL) {
            return leftest(this.right)
        }

        var node: TreeNode = this
        while (node.parent !== SENTINEL) {
            if (node.parent.left === node) {
                break
            }
            node = node.parent
        }

        if (node.parent === SENTINEL) {
            return SENTINEL
        } else {
            return node.parent
        }
    }

    fun prev(): TreeNode {
        if (this.left !== SENTINEL) {
            return rightest(this.left)
        }

        var node: TreeNode = this

        while (node.parent !== SENTINEL) {
            if (node.parent.right === node) {
                break
            }
            node = node.parent
        }

        if (node.parent === SENTINEL) {
            return SENTINEL
        } else {
            return node.parent
        }
    }

    fun detach() {
        this.parent = NULL
        this.left = NULL
        this.right = NULL
    }
}

// null piece
internal val NPIE = Piece(0, BufferCursor(0, 0), BufferCursor(0, 0), 0, 0)
// null tree node
internal val NULL = TreeNode(NPIE, NodeColor.Red)
// sentinel tree node
internal val SENTINEL: TreeNode = TreeNode(NPIE, NodeColor.Black).apply {
    this.parent = this // parent = SENTINEL
    this.left = this // left = SENTINEL
    this.right = this // right = SENTINEL
    this.color = NodeColor.Black
}

internal fun leftest(node: TreeNode): TreeNode {
    var x: TreeNode = node
    while (x.left !== SENTINEL) {
        x = x.left
    }
    return x
}

internal fun rightest(node: TreeNode): TreeNode {
    var y: TreeNode = node
    while (y.right !== SENTINEL) {
        y = y.right
    }
    return y
}

internal tailrec fun calculateSize(node: TreeNode, size: Int = 0): Int {
    if (node === SENTINEL) {
        return size
    }
    return calculateSize(node.right, size + node.size_left + node.piece.length)
}

internal tailrec fun calculateLF(node: TreeNode, count: Int = 0): Int {
    if (node === SENTINEL) {
        return count
    }
    return calculateLF(node.right, count + node.lf_left + node.piece.lineFeedCnt)
}

internal fun resetSentinel() {
    SENTINEL.parent = SENTINEL
}

internal fun leftRotate(tree: PieceTreeBase, x: TreeNode) {
    val y = x.right
    // fix size_left
    y.size_left += x.size_left + x.piece.length
    y.lf_left += x.lf_left + x.piece.lineFeedCnt
    x.right = y.left

    if (y.left !== SENTINEL) {
        y.left.parent = x
    }
    y.parent = x.parent
    if (x.parent === SENTINEL) {
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
    if (x.right !== SENTINEL) {
        x.right.parent = y
    }
    x.parent = y.parent

    // fix size_left
    y.size_left -= x.size_left + x.piece.length
    y.lf_left -= x.lf_left + x.piece.lineFeedCnt

    if (y.parent === SENTINEL) {
        tree.root = x
    } else if (y === y.parent.right) {
        y.parent.right = x
    } else {
        y.parent.left = x
    }

    x.right = y
    y.parent = x
}

internal fun rbDelete(tree: PieceTreeBase, node: TreeNode) {
    var x: TreeNode
    var y: TreeNode
    val z: TreeNode = node

    if (z.left === SENTINEL) {
        y = z
        x = y.right
    } else if (z.right === SENTINEL) {
        y = z
        x = y.left
    } else {
        y = leftest(z.right)
        x = y.right
    }

    if (y === tree.root) {
        tree.root = x
        // if x is null, we are removing the only node
        x.color = NodeColor.Black
        z.detach()
        resetSentinel()
        tree.root.parent = SENTINEL
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

        if (y.left !== SENTINEL) {
            y.left.parent = y
        }
        if (y.right !== SENTINEL) {
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
        val newSizeLeft = calculateSize(x)
        val newLFLeft = calculateLF(x)
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
        resetSentinel()
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
    resetSentinel()
}

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

internal fun updateTreeMetadata(
    tree: PieceTreeBase,
    node: TreeNode,
    delta: Int,
    lineFeedCntDelta: Int
) {
    var x = node
    // node length change or line feed count change
    while (x !== tree.root && x !== SENTINEL) {
        if (x.parent.left === x) {
            x.parent.size_left += delta
            x.parent.lf_left += lineFeedCntDelta
        }
        x = x.parent
    }
}

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

        delta = calculateSize(x.left) - x.size_left
        lf_delta = calculateLF(x.left) - x.lf_left
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

internal fun traversalTree(node: TreeNode) {
    if (node !== SENTINEL) {
        println(node)
        traversalTree(node.left)
        traversalTree(node.right)
    }
}
