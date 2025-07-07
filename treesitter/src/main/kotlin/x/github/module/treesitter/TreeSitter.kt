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

package x.github.module.treesitter

/* Set the parser encoding */
enum class TSInputEncoding { UTF8, UTF16 }

/* Get the parser log type */
enum class TSLogType { PARSE, LEX }

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
    @get:JvmName("getPatternIndex") val patternIndex: UInt,
    val captures: List<TSQueryCapture>
) {
    var predicateResult: Boolean = false
    /** Get the nodes that are captured by the given [capture] name. */
    operator fun get(capture: String): List<TSNode> =
        captures.mapNotNull { if (it.name == capture) it.node else null }

    override fun toString() = "TSQueryMatch(patternIndex=$patternIndex, captures=$captures)"
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
