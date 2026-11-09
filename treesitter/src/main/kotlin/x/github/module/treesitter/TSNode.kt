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

/*
 * Derived from kotlin-tree-sitter (https://github.com/tree-sitter/kotlin-tree-sitter),
 * Copyright (c) 2024 tree-sitter contributors, under the MIT License: see
 * LICENSE-kotlin-tree-sitter at the root of this module. Reworked from it, and changed since.
 */

package x.github.module.treesitter

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative

/** A single node within a [syntax tree][TSTree]. */
@Suppress("unused")
class TSNode internal constructor(
    private var context: IntArray,
    id: Long, /* tempory id */
    @JvmField internal val tree: TSTree
) {
    
    /**
     * The numeric ID of the node.
     *
     * Within any given syntax tree, no two nodes have the same ID.
     * However, if a new tree is created based on an older tree,
     * and a node from the old tree is reused in the process,
     * then that node will have the same ID in both trees.
     */
    @get:JvmName("getId")
    val id: ULong = id.toULong()

    // the id as it is natively, for the @CriticalNative calls (see Companion)
    private val rawId: Long = id
    
    @get:JvmName("getString")
    val string: String
        @FastNative external get
    
    /** The numerical ID of the node's type. */
    @get:JvmName("getSymbol")
    val symbol: UShort
        get() = nSymbol(context[0], context[1], context[2], context[3], rawId, tree.self).toUShort()

    /**
     * The numerical ID of the node's type,
     * as it appears in the grammar ignoring aliases.
     */
    @get:JvmName("getGrammarSymbol")
    val grammarSymbol: UShort
        get() = nGrammarSymbol(context[0], context[1], context[2], context[3], rawId, tree.self).toUShort()

    /** The type of the node. The same String each time (see [TSLanguage.nodeTypeOf]). */
    @get:JvmName("getType")
    val type: String
        get() = tree.language.nodeTypeOf(
            nSymbol(context[0], context[1], context[2], context[3], rawId, tree.self)
        )

    /**
     * The type of the node,
     * as it appears in the grammar ignoring aliases.
     */
    @get:JvmName("getGrammarType")
    val grammarType: String
        get() = tree.language.nodeTypeOf(
            nGrammarSymbol(context[0], context[1], context[2], context[3], rawId, tree.self)
        )

    /**
     * Check if the node is _named_.
     *
     * Named nodes correspond to named rules in the grammar,
     * whereas _anonymous_ nodes correspond to string literals.
     */
    @get:JvmName("isNamed")
    val isNamed: Boolean
        get() = nIsNamed(context[0], context[1], context[2], context[3], rawId, tree.self)

    /**
     * Check if the node is _extra_.
     *
     * Extra nodes represent things which are not required
     * by the grammar but can appear anywhere (e.g. whitespace).
     */
    @get:JvmName("isExtra")
    val isExtra: Boolean
        get() = nIsExtra(context[0], context[1], context[2], context[3], rawId, tree.self)

    /** Check if the node is a syntax error. */
    @get:JvmName("isError")
    val isError: Boolean
        get() = nIsError(context[0], context[1], context[2], context[3], rawId, tree.self)

    /**
     * Check if the node is _missing_.
     *
     * Missing nodes are inserted by the parser in order
     * to recover from certain kinds of syntax errors.
     */
    @get:JvmName("isMissing")
    val isMissing: Boolean
        get() = nIsMissing(context[0], context[1], context[2], context[3], rawId, tree.self)

    /** Check if the node has been edited. */
    @get:JvmName("hasChanges")
    val hasChanges: Boolean
        get() = nHasChanges(context[0], context[1], context[2], context[3], rawId, tree.self)

    /**
     * Check if the node is a syntax error,
     * or contains any syntax errors.
     */
    @get:JvmName("hasError")
    val hasError: Boolean
        get() = nHasError(context[0], context[1], context[2], context[3], rawId, tree.self)

    /** The parse state of this node. */
    @get:JvmName("getParseState")
    val parseState: UShort
        get() = nParseState(context[0], context[1], context[2], context[3], rawId, tree.self).toUShort()

    /** The parse state after this node. */
    @get:JvmName("getNextParseState")
    val nextParseState: UShort
        get() = nNextParseState(context[0], context[1], context[2], context[3], rawId, tree.self).toUShort()

    /** 
      * The start byte of the node. 
      * for UTF-16 encoding requires startByte / 2
      */
    @get:JvmName("getStartByte")
    val startByte: UInt
        get() = context[0].toUInt()

    /** 
     * The end byte of the node.
     * for UTF-16 encoding requires startByte / 2
    */
    @get:JvmName("getEndByte")
    val endByte: UInt
        get() = nEndByte(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt()

    /**
     * The range of the node in terms of bytes.
     * for UTF-16 encoding requires start and end byte * 2
     */
    val byteRange: UIntRange
        get() = startByte..endByte

    /** The range of the node in terms of bytes and points. */
    val range: TSRange
        get() = TSRange(startPoint, endPoint, startByte, endByte)

    /** 
     * The start point of the node.
     * for UTF-16 encoding requires point.column / 2
     */
    @get:JvmName("getStartPoint")
    val startPoint: TSPoint
        get() = TSPoint(context[1].toUInt(), context[2].toUInt())

    /** 
     * The end point of the node.
     * for UTF-16 encoding requires point.column / 2
     */
    @get:JvmName("getEndPoint")
    val endPoint: TSPoint
        get() = TSPoint(nEndRow(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt(), nEndColumn(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt())

    /** The number of this node's children. */
    @get:JvmName("getChildCount")
    val childCount: UInt
        get() = nChildCount(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt()

    /** The number of this node's _named_ children. */
    @get:JvmName("getNamedChildCount")
    val namedChildCount: UInt
        get() = nNamedChildCount(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt()

    /**
     * The number of this node's descendants,
     * including one for the node itself.
     */
    @get:JvmName("getDescendantCount")
    val descendantCount: UInt
        get() = nDescendantCount(context[0], context[1], context[2], context[3], rawId, tree.self).toUInt()

    /** The node's immediate parent, if any. */
    @get:JvmName("getParent")
    val parent: TSNode?
        @FastNative external get

    /** The node's next sibling, if any. */
    @get:JvmName("getNextSibling")
    val nextSibling: TSNode?
        @FastNative external get

    /** The node's previous sibling, if any. */
    @get:JvmName("getPrevSibling")
    val prevSibling: TSNode?
        @FastNative external get

    /** The node's next _named_ sibling, if any. */
    @get:JvmName("getNextNamedSibling")
    val nextNamedSibling: TSNode?
        @FastNative external get

    /** The node's previous _named_ sibling, if any. */
    @get:JvmName("getPrevNamedSibling")
    val prevNamedSibling: TSNode?
        @FastNative external get
   
    /**
     * This node's children.
     *
     * If you're walking the tree recursively,
     * you may want to use [walk] instead.
     */
    @get:JvmName("getChildren")
    val children: List<TSNode>
        external get

    /** This node's _named_ children. */
    val namedChildren: List<TSNode>
        get() = children.filter(TSNode::isNamed)
    
    /** Create a new tree cursor starting from this node. */
    fun walk() = TSTreeCursor(this)
    
    /**
     * Get the source code of the node, if available: from the text the tree was
     * parsed from, or else through its [TSTree.textReader].
     */
    fun text(): CharSequence? {
        val start = (startByte / 2U).toInt()
        val end = (endByte / 2U).toInt()
        tree.text()?.let { return it.subSequence(start, minOf(end, it.length)) }
        return tree.textReader?.read(start, end)
    }
    
    /**
     * The node's child at the given index, if any.
     *
     * This method is fairly fast, but its cost is technically
     * `log(i)`, so if you might be iterating over a long list of
     * children, you should use [children] or [Tree.walk] instead.
     *
     * @throws [IndexOutOfBoundsException]
     *  If the index exceeds the [child count][childCount].
     */
    @JvmName("child")
    @Throws(IndexOutOfBoundsException::class)
    external fun child(index: UInt): TSNode?

    /**
     * Get the node's _named_ child at the given index, if any.
     *
     * This method is fairly fast, but its cost is technically `log(i)`,
     * so if you might be iterating over a long list of children,
     * you should use [namedChildren] or [walk][TSNode.walk] instead.
     *
     * @throws [IndexOutOfBoundsException]
     *  If the index exceeds the [named child count][namedChildCount].
     */
    @FastNative
    @JvmName("namedChild")
    @Throws(IndexOutOfBoundsException::class)
    external fun namedChild(index: UInt): TSNode?
    
    /**
     * Get the node's first child that contains
     * or starts after the given byte offset.
     *
     * @since 0.25.0
     */
    @FastNative
    @JvmName("firstChildForByte")
    external fun firstChildForByte(byte: UInt): TSNode?

    /**
     * Get the node's first _named_ child that contains
     * or starts after the given byte offset.
     *
     * @since 0.25.0
     */
    @FastNative
    @JvmName("firstNamedChildForByte")
    external fun firstNamedChildForByte(byte: UInt): TSNode?
    
    /**
     * Get the node's child with the given field ID, if any.
     *
     * @see [Language.fieldIdForName]
     */
    @FastNative
    @JvmName("childByFieldId")
    external fun childByFieldId(id: UShort): TSNode?

    /** Get the node's child with the given field name, if any. */
    @JvmName("childByFieldName")
    external fun childByFieldName(name: String): TSNode?

    /** Get a list of children with the given field ID. */
    @FastNative
    @JvmName("childrenByFieldId")
    external fun childrenByFieldId(id: UShort): List<TSNode>

    /** Get a list of children with the given field name. */
    fun childrenByFieldName(name: String) =
        childrenByFieldId(tree.language.fieldIdForName(name))

    /**
     * Get the field name of this node’s child at the given index, if available.
     *
     * @throws [IndexOutOfBoundsException] If the index exceeds the [child count][childCount].
     */
    @FastNative
    @JvmName("fieldNameForChild")
    @Throws(IndexOutOfBoundsException::class)
    external fun fieldNameForChild(index: UInt): String?
    
    /**
     * Get the field name of this node’s _named_ child at the given index, if available.
     *
     * @throws [IndexOutOfBoundsException] If the index exceeds the [child count][childCount].
     * @since 0.24.0
     */
    @FastNative
    @JvmName("fieldNameForNamedChild")
    @Throws(IndexOutOfBoundsException::class)
    external fun fieldNameForNamedChild(index: UInt): String?
    
    /**
     * Get the node that contains the given descendant, if any.
     *
     * @since 0.24.0
     */
    @FastNative
    external fun childWithDescendant(descendant: TSNode): TSNode?
    
    /**
     * Get the smallest node within this node
     * that spans the given byte range, if any.
     */
    @FastNative
    @JvmName("descendant")
    external fun descendant(start: UInt, end: UInt): TSNode?

    /**
     * Get the smallest node within this node
     * that spans the given point range, if any.
     */
    @FastNative
    @JvmName("descendant")
    external fun descendant(start: TSPoint, end: TSPoint): TSNode?

    /**
     * Get the smallest _named_ node within this node
     * that spans the given byte range, if any.
     */
    @FastNative
    @JvmName("namedDescendant")
    external fun namedDescendant(start: UInt, end: UInt): TSNode?

    /**
     * Get the smallest _named_ node within this node
     * that spans the given point range, if any.
     */
    @FastNative
    @JvmName("namedDescendant")
    external fun namedDescendant(start: TSPoint, end: TSPoint): TSNode?

    /**
     * Edit this node to keep it in-sync with source code that has been edited.
     *
     * This method is only rarely needed. When you edit a syntax tree via
     * [Tree.edit], all of the nodes that you retrieve from the tree afterward
     * will already reflect the edit. You only need to use this when you have a
     * specific TSNode instance that you want to keep and continue to use after an edit.
     */
    @FastNative
    external fun edit(edit: TSInputEdit)

    /** Get the S-expression of the node. */
    external fun sexp(): String
    
    // as ts_node_eq: the same node of the same tree (no JNI call)
    override fun hashCode(): Int {
        val treeSelf = tree.self
        return (if (rawId == treeSelf) rawId else rawId xor treeSelf).toInt()
    }

    override operator fun equals(other: Any?) =
        this === other || (other is TSNode && other.rawId == rawId && other.tree.self == tree.self)
    
    override fun toString() = "TSNode(type=$type, startByte=$startByte, endByte=$endByte)"

    /*
     * The scalars of a node: @CriticalNative, with the node as its fields (its context, id
     * and tree). Before, each getter was a call that read the node back through JNI, five
     * calls (the context array, the id, the tree, its pointer) for one addition (endByte).
     * The start byte and point are in the context itself, and need no call at all.
     */
    private companion object {
        @JvmStatic @CriticalNative
        private external fun nEndByte(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nEndRow(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nEndColumn(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nSymbol(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nGrammarSymbol(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nIsNamed(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nIsExtra(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nIsError(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nIsMissing(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nHasError(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nHasChanges(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Boolean
        @JvmStatic @CriticalNative
        private external fun nParseState(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nNextParseState(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nChildCount(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nNamedChildCount(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
        @JvmStatic @CriticalNative
        private external fun nDescendantCount(c0: Int, c1: Int, c2: Int, c3: Int, id: Long, tree: Long): Int
    }
}

