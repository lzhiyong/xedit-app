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


// NOTE: don't forget to bump these when necessary

/**
 * The latest ABI version that is supported by the current version of the library.
 *
 * The Tree-sitter library is generally backwards-compatible with languages
 * generated using older CLI versions, but is not forwards-compatible.
 */
const val LANGUAGE_VERSION: UInt = 15U

/** The earliest ABI version that is supported by the current version of the library. */
const val MIN_COMPATIBLE_LANGUAGE_VERSION: UInt = 13U

/* Get the tree sitter language symbol type */
enum class TSSymbolType {
    REGULAR,
    ANONYMOUS,
    AUXILIARY
}

/**
 * A [Node] that was captured with a certain capture [name].
 *
 * @property node The captured node.
 * @property name The name of the capture.
 */
data class TSQueryCapture internal constructor(
    @get:JvmName("node") val node: TSNode,
    @get:JvmName("name") val name: String
) {
    override fun toString() = "TSQueryCapture(name=$name, node=$node)"
}

/**
 * A match that corresponds to a certain pattern in the query.
 *
 * @property patternIndex The index of the pattern.
 * @property captures The captures contained in the pattern.
 */
class TSQueryMatch internal constructor(
    // the pattern index, with the top bit set when the predicates were decided natively
    raw: Int,
    val captures: List<TSQueryCapture>
) {
    @get:JvmName("getPatternIndex") val patternIndex: UInt = (raw and Int.MAX_VALUE).toUInt()

    /**
     * Whether the predicates of the pattern were all decided natively and hold (see
     * TSQueryCursor.setText): they are not evaluated again.
     */
    val predicatesDecided: Boolean = raw < 0

    var predicateResult: Boolean = false
    /** Get the nodes that are captured by the given [capture] name. */
    operator fun get(capture: String): List<TSNode> =
        captures.mapNotNull { if (it.name == capture) it.node else null }

    override fun toString() = "TSQueryMatch(patternIndex=$patternIndex, captures=$captures)"
}

/**
 * A match as [TSQueryCursor.forEachMatch] hands it out: its pattern, and for each of its
 * captures the capture (an index into [TSQuery.captureNames]) and the bytes of its node.
 * Only valid during the call it is handed to: it is reused for the next match.
 */
class TSQueryMatchView @PublishedApi internal constructor(
    // the tree of the nodes of node(i)
    private val tree: TSTree? = null
) {
    /** The index of the pattern. */
    var patternIndex: Int = 0
        private set

    /** The number of captures. */
    var captureCount: Int = 0
        private set

    private var data: IntArray = EMPTY
    private var offset: Int = 0
    // the ints of a capture in [data]
    private var captureInts: Int = MATCH_CAPTURE_INTS
    // the match, when it came as an object
    private var match: TSQueryMatch? = null

    /** The capture [i] is of: an index into [TSQuery.captureNames]. */
    fun captureIndex(i: Int): Int = data[offset + captureInts * i]

    /** The start byte of the node of the capture [i]. */
    fun startByte(i: Int): Int = data[offset + captureInts * i + 1]

    /** The end byte of the node of the capture [i]. */
    fun endByte(i: Int): Int = data[offset + captureInts * i + 2]

    /** The first of the captures that is of [capture] (see [captureIndex]), or -1. */
    fun firstOf(capture: Int): Int {
        for (i in 0 until captureCount) {
            if (captureIndex(i) == capture) return i
        }
        return -1
    }

    /**
     * The node of the capture [i], built here without a call into native code. Needs
     * `forEachMatch(withNodes = true)`. The node, unlike the view, stays valid after the
     * call (for as long as its tree).
     *
     * @throws [IllegalStateException] If the records came without their nodes.
     */
    fun node(i: Int): TSNode {
        match?.let { return it.captures[i].node }
        check(captureInts == MATCH_NODE_CAPTURE_INTS && tree != null) {
            "forEachMatch was called without withNodes"
        }
        return TSNode(nodeContext(i), nodeId(i), tree)
    }

    // context[0] of a node is its start byte
    internal fun nodeContext(i: Int): IntArray {
        val at = offset + captureInts * i
        return intArrayOf(data[at + 1], data[at + 3], data[at + 4], data[at + 5])
    }

    internal fun nodeId(i: Int): Long {
        val at = offset + captureInts * i
        return (data[at + 7].toLong() shl 32) or (data[at + 6].toLong() and 0xFFFFFFFFL)
    }

    // a record of the batch [buffer]: the captures from [at] on
    @PublishedApi
    internal fun of(pattern: Int, count: Int, buffer: IntArray, at: Int, ints: Int = MATCH_CAPTURE_INTS) {
        patternIndex = pattern
        captureCount = count
        data = buffer
        offset = at
        captureInts = ints
        match = null
    }

    // a match that came as an object
    @PublishedApi
    internal fun of(match: TSQueryMatch, query: TSQuery) {
        val count = match.captures.size
        val own = IntArray(MATCH_CAPTURE_INTS * count)
        for (i in 0 until count) {
            val capture = match.captures[i]
            own[MATCH_CAPTURE_INTS * i] = query.captureIndexOf(capture.name)
            own[MATCH_CAPTURE_INTS * i + 1] = capture.node.startByte.toInt()
            own[MATCH_CAPTURE_INTS * i + 2] = capture.node.endByte.toInt()
        }
        of(match.patternIndex.toInt(), count, own, 0)
        this.match = match
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}

/**
 * An argument to a [TSQueryPredicate].
 *
 * @property value The value of the argument.
 */
sealed interface TSQueryPredicateArgs {
    val value: String

    /** A capture argument (`@value`). */
    @JvmInline
    value class Capture(override val value: String) : TSQueryPredicateArgs {
        override fun toString() = "@$value"
    }

    /** A literal string argument (`"value"`). */
    @JvmInline
    value class Literal(override val value: String) : TSQueryPredicateArgs {
        override fun toString() = "\"$value\""
    }
}

/**
 * A position in a text document in terms of rows and columns.
 *
 * @property row The zero-based row of the document.
 * @property column The zero-based column of the document.
 */
/**
 * A position in a text document in terms of rows and columns.
 *
 * @property row The zero-based row of the document.
 * @property column The zero-based column of the document.
 */
data class TSPoint(
    @get:JvmName("row") val row: UInt,
    @get:JvmName("column") val column: UInt
) : Comparable<TSPoint> {
    override operator fun compareTo(other: TSPoint): Int {
        val rowDiff = row.compareTo(other.row)
        if (rowDiff != 0) return rowDiff
        return column.compareTo(other.column)
    }

    companion object {
        /** The minimum value a [Point] can have. */
        @JvmField
        val MIN = TSPoint(UInt.MIN_VALUE, UInt.MIN_VALUE)

        /** The maximum value a [Point] can have. */
        @JvmField
        val MAX = TSPoint(UInt.MAX_VALUE, UInt.MAX_VALUE)
    }
}

/** An edit to a text document. */
data class TSInputEdit(
    @get:JvmName("startByte") val startByte: UInt,
    @get:JvmName("oldEndByte") val oldEndByte: UInt,
    @get:JvmName("newEndByte") val newEndByte: UInt,
    @get:JvmName("startPoint") val startPoint: TSPoint,
    @get:JvmName("oldEndPoint") val oldEndPoint: TSPoint,
    @get:JvmName("newEndPoint") val newEndPoint: TSPoint
)

/**
 * A range of positions in a text document,
 * both in terms of bytes and of row-column points.
 *
 * @constructor
 * @throws [IllegalArgumentException]
 *  If the end point is smaller than the start point,
 *  or the end byte is smaller than the start byte.
 */
data class TSRange @Throws(IllegalArgumentException::class) constructor(
    @get:JvmName("startPoint") val startPoint: TSPoint,
    @get:JvmName("endPoint") val endPoint: TSPoint,
    @get:JvmName("startByte") val startByte: UInt,
    @get:JvmName("endByte") val endByte: UInt
) {
    init {
        require(startPoint <= endPoint) { "Invalid point range: $startPoint to $endPoint" }
        require(startByte <= endByte) { "Invalid byte range: $startByte to $endByte" }
    }
}
