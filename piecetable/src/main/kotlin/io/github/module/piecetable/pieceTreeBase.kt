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

import io.github.module.piecetable.common.*
import kotlin.text.Regex

internal fun <T> MutableList<T>.concat(
    vararg collections: List<T>
): MutableList<T> {
    // add all sublist
    for(subList in collections) {
        this.addAll(subList)
    }
    // this => MutableList
    return this 
}

internal fun createLineStartsFast(str: CharSequence): MutableList<Int> {
    val r = mutableListOf(0)
    val len = str.length

    var i: Int = 0
    while (i < len) {
        val chr = Character.codePointAt(str, i)

        if (chr == CharCode.CarriageReturn) {
            if (i + 1 < len && Character.codePointAt(str, i + 1) == CharCode.LineFeed) {
                // \r\n... case
                r.add(i + 2)
                i++ // skip \n
            } else {
                // \r... case
                r.add(i + 1)
            }
        } else if (chr == CharCode.LineFeed) {
            r.add(i + 1)
        }
        ++i
    }
    return r
}

internal fun createLineStarts(str: String): LineStarts {
    var cr: Int = 0
    var lf: Int = 0
    var crlf: Int = 0
    var isBasicASCII = true
    val r = mutableListOf(0)

    var i: Int = 0
    val len: Int = str.length
    while (i < len) {
        val chr = Character.codePointAt(str, i)
        if (chr == CharCode.CarriageReturn) {
            if (i + 1 < len && str.codePointAt(i + 1) == CharCode.LineFeed) {
                // \r\n... case
                crlf++
                r.add(i + 2)
                i++ // skip \n
            } else {
                cr++
                // \r... case
                r.add(i + 1)
            }
        } else if (chr == CharCode.LineFeed) {
            lf++
            r.add(i + 1)
        } else {
            if (isBasicASCII) {
                if (chr != CharCode.Tab && (chr < 32 || chr > 126)) {
                    isBasicASCII = false
                }
            }
        }
        ++i
    }
    return LineStarts(r, cr, lf, crlf, isBasicASCII)
}

internal data class NodePosition(
    var node: TreeNode, // Piece Index
    var remainder: Int, // remainder in current piece
    var nodeStartOffset: Int // node start offset in document
)

// null node position
internal val NPOS = NodePosition(NULL, 0, 0)

internal data class BufferCursor(
    // Line number in current buffer line >= 0
    var line: Int, 
    // Column number in current buffer column >= 0
    var column: Int 
)

internal data class Piece(
    val bufferIndex: Int,
    val start: BufferCursor,
    val end: BufferCursor,
    val lineFeedCnt: Int,
    val length: Int
)

internal data class LineStarts(
    val lineStarts: MutableList<Int>,
    val cr: Int,
    val lf: Int,
    val crlf: Int,
    val isBasicASCII: Boolean
)

internal data class TextBuffer(
    var buffer: StringBuffer,
    var lineStarts: MutableList<Int>
)

internal data class CacheEntry(
    var node: TreeNode,
    var nodeStartOffset: Int,
    var nodeStartLineNumber: Int
)

internal data class VisitedLine(
    var lineNumber: Int,
    var value: String
)

/**
 * Readonly snapshot for piece tree. In a real multiple thread environment, to make snapshot reading
 * always work correctly, we need to
 * 1. Make TreeNode.piece immutable, then reading and writing can run in parallel.
 * 2. TreeNode/Buffers normalization should not happen during snapshot reading.
 */
internal class PieceTreeSnapshot(val tree: PieceTreeBase, val BOM: String) {
    private val pieces = mutableListOf<Piece>()
    private var index: Int = 0

    init {
        if (tree.root !== SENTINEL) {
            tree.iterate(tree.root) { node ->
                if (node !== SENTINEL) {
                    this.pieces.add(node.piece)
                }
                return@iterate true
            }
        }
    }

    fun read(): String? {
        if (this.pieces.size == 0) {
            if (this.index == 0) {
                this.index++
                return this.BOM
            } else {
                return null
            }
        }

        if (this.index > this.pieces.size - 1) {
            return null
        }

        if (this.index == 0) {
            return this.BOM + this.tree.getPieceContent(this.pieces[index++])
        }
        return this.tree.getPieceContent(this.pieces[index++])
    }
}

internal class PieceTreeSearchCache(val limit: Int) {
    val cache = mutableListOf<CacheEntry>()

    @Synchronized
    fun get(offset: Int): CacheEntry? {
        for (i in cache.size - 1 downTo 0) {
            val nodePos = this.cache[i]
            if (nodePos.nodeStartOffset <= offset &&
                nodePos.nodeStartOffset + nodePos.node.piece.length >= offset
            ) {
                return nodePos
            }
        }
        return null
    }

    // return { node: TreeNode, nodeStartOffset: number, nodeStartLineNumber: number } | null
    @Synchronized
    fun get2(lineNumber: Int): CacheEntry? {
        for (i in this.cache.size - 1 downTo 0) {
            val nodePos = this.cache[i]
            if (nodePos.nodeStartLineNumber > 0 &&
                nodePos.nodeStartLineNumber < lineNumber &&
                nodePos.nodeStartLineNumber + nodePos.node.piece.lineFeedCnt >= lineNumber
            ) {
                // <{ node: TreeNode, nodeStartOffset: number, nodeStartLineNumber: number }>
                return nodePos
            }
        }
        return null
    }

    @Synchronized
    fun set(nodePosition: CacheEntry) {
        if (this.cache.size >= this.limit) {
            // this.cache.shift() => remove the first element
            // removeFirst() requires android minSdk >= 35
            // this.cache.removeFirst()
            this.cache.removeAt(0)
        }
        this.cache.add(nodePosition)
    }

    @Synchronized
    fun validate(offset: Int) {
        val iter = this.cache.iterator()
        while (iter.hasNext()) {
            val nodePos = iter.next()
            if (nodePos.node.parent === NULL || nodePos.nodeStartOffset >= offset) {
                iter.remove()
            }
        }
    }
}

// #region primary class
// Piece Tree API
internal class PieceTreeBase(
    val chunks: MutableList<TextBuffer>,
    val strEOL: String,
    val eolNormalized: Boolean
) {
    private var _lineCnt: Int = 0
    private var _length: Int = 0
    private var _EOLLength: Int = 0
    private var _EOLNormalized: Boolean = true
    private lateinit var _EOL: String
    private lateinit var _lastChangeBufferPos: BufferCursor
    private lateinit var _lastVisitedLine: VisitedLine
    private lateinit var _searchCache: PieceTreeSearchCache
    private lateinit var _buffers: MutableList<TextBuffer>

    // root node
    public lateinit var root: TreeNode

    // the string chunk size 64k
    private val AverageBufferSize: Int = 65535

    init {
        this.create(chunks, strEOL, eolNormalized)
    }

    private fun create(chunks: List<TextBuffer>, strEOL: String, eolNormalized: Boolean) {
        this.root = SENTINEL
        this._buffers = mutableListOf(TextBuffer(StringBuffer(), mutableListOf(0)))
        this._lineCnt = 1
        this._length = 0
        this._EOL = strEOL
        this._EOLLength = strEOL.length
        this._EOLNormalized = eolNormalized
        this._lastChangeBufferPos = BufferCursor(0, 0)
        var lastNode: TreeNode = NULL
        for (i in 0..chunks.size - 1) {
            if (chunks[i].buffer.length > 0) {
                if (chunks[i].lineStarts.size == 0) {
                    chunks[i].lineStarts = createLineStartsFast(chunks[i].buffer)
                }
                val piece = Piece(
                    i + 1,
                    BufferCursor(0, 0),
                    BufferCursor(
                        chunks[i].lineStarts.size - 1,
                        chunks[i].buffer.length - chunks[i].lineStarts[chunks[i].lineStarts.size - 1]
                    ),
                    chunks[i].lineStarts.size - 1,
                    chunks[i].buffer.length
                )
                this._buffers.add(chunks[i])
                lastNode = this.rbInsertRight(lastNode, piece)
            }
        }

        this._searchCache = PieceTreeSearchCache(1)
        this._lastVisitedLine = VisitedLine(0, "")
        this.computeBufferMetadata()
    }

    private fun normalizeEOL(strEOL: String) {
        val min: Int = AverageBufferSize - Math.floor(AverageBufferSize.toDouble() / 3).toInt()
        val max = min * 2

        val tempChunk = StringBuffer()
        var tempChunkLen = 0
        val chunks = mutableListOf<TextBuffer>()

        this.iterate(this.root) {
            val str = this.getNodeContent(it)
            val len = str.length
            if (tempChunkLen <= min || tempChunkLen + len < max) {
                tempChunk.append(str)
                tempChunkLen += len
                return@iterate true
            }

            // flush anyways
            val text = tempChunk.toString().replace(Strings.newLine, strEOL)
            chunks.add(TextBuffer(StringBuffer(text), createLineStartsFast(text)))
            tempChunk.replace(0, tempChunk.length, str)
            tempChunkLen = len
            return@iterate true
        }

        if (tempChunkLen > 0) {
            val text = tempChunk.toString().replace(Strings.newLine, strEOL)
            chunks.add(TextBuffer(StringBuffer(text), createLineStartsFast(text)))
        }

        this.create(chunks, strEOL, true)
    }

    // #region Buffer API
    fun getEOL(): String {
        return this._EOL
    }

    fun setEOL(newEOL: String) {
        this._EOL = newEOL
        this._EOLLength = this._EOL.length
        this.normalizeEOL(newEOL)
    }

    fun createSnapshot(BOM: String): PieceTreeSnapshot {
        return PieceTreeSnapshot(this, BOM)
    }

    fun equal(other: PieceTreeBase): Boolean {
        if (this.getLength() != other.getLength()) {
            return false
        }
        if (this.getLineCount() != other.getLineCount()) {
            return false
        }

        var offset: Int = 0
        val ret = this.iterate(this.root) {
            if (it === SENTINEL) {
                return@iterate true
            }
            val str = this.getNodeContent(it)
            val len = str.length
            val startPosition = other.nodeAt(offset)
            val endPosition = other.nodeAt(offset + len)
            val value = other.getValueInRange2(startPosition, endPosition)
            
            offset += len
            return@iterate str == value
        }

        return ret
    }

    fun getOffsetAt(lineNumber: Int, column: Int): Int {
        var leftLen = 0 // inorder
        var line = lineNumber

        var x = this.root

        while (x !== SENTINEL) {
            if (x.left !== SENTINEL && x.lf_left + 1 >= line) {
                x = x.left
            } else if (x.lf_left + x.piece.lineFeedCnt + 1 >= line) {
                leftLen += x.size_left
                // lineNumber >= 2
                val accumualtedValInCurrentIndex = this.getAccumulatedValue(x, line - x.lf_left - 2)
                leftLen += accumualtedValInCurrentIndex + column - 1
                return leftLen
            } else {
                line -= x.lf_left + x.piece.lineFeedCnt
                leftLen += x.size_left + x.piece.length
                x = x.right
            }
        }

        return leftLen
    }

    fun getPositionAt(pos: Int): Position {
        var offset: Int = Math.floor(pos.toDouble()).toInt()
        offset = Math.max(0, offset)

        var x = this.root
        var lfCnt = 0
        val originalOffset = offset

        while (x !== SENTINEL) {
            if (x.size_left != 0 && x.size_left >= offset) {
                x = x.left
            } else if (x.size_left + x.piece.length >= offset) {
                val (index, remainder) = this.getIndexOf(x, offset - x.size_left)

                lfCnt += x.lf_left + index

                if (index == 0) {
                    val lineStartOffset = this.getOffsetAt(lfCnt + 1, 1)
                    val column = originalOffset - lineStartOffset
                    return Position(lfCnt + 1, column + 1)
                }

                return Position(lfCnt + 1, remainder + 1)
            } else {
                offset -= x.size_left + x.piece.length
                lfCnt += x.lf_left + x.piece.lineFeedCnt

                if (x.right === SENTINEL) {
                    // last node
                    val lineStartOffset = this.getOffsetAt(lfCnt + 1, 1)
                    val column = originalOffset - offset - lineStartOffset
                    return Position(lfCnt + 1, column + 1)
                } else {
                    x = x.right
                }
            }
        }

        return Position(1, 1)
    }

    fun getValueInRange(range: Range, strEOL: String? = null): String {
        if (range.startLine == range.endLine && range.startColumn == range.endColumn) {
            return ""
        }

        val startPosition = this.nodeAt2(range.startLine, range.startColumn)
        val endPosition = this.nodeAt2(range.endLine, range.endColumn)

        val value = this.getValueInRange2(startPosition, endPosition)
        if (strEOL != null) {
            if (strEOL !== this._EOL || !this._EOLNormalized) {
                return value.replace(Strings.newLine, strEOL)
            }

            if (strEOL == this.getEOL() && this._EOLNormalized) {
                if (strEOL == "\r\n") {
                    // nothing to do
                }
                return value
            }
            return value.replace(Strings.newLine, strEOL)
        }
        return value
    }

    fun getValueInRange2(startPosition: NodePosition, endPosition: NodePosition): String {
        if (startPosition.node === endPosition.node) {
            val node = startPosition.node
            val buffer = this._buffers[node.piece.bufferIndex].buffer
            val startOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start)
            return buffer.substring(
                startOffset + startPosition.remainder,
                startOffset + endPosition.remainder
            )
        }

        var x = startPosition.node
        var buffer = this._buffers[x.piece.bufferIndex].buffer
        var startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)
        val ret = StringBuffer(
            buffer.substring(
                startOffset + startPosition.remainder,
                startOffset + x.piece.length
            )
        )

        x = x.next()
        while (x !== SENTINEL) {
            buffer = this._buffers[x.piece.bufferIndex].buffer
            startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)

            if (x === endPosition.node) {
                ret.append(buffer.substring(startOffset, startOffset + endPosition.remainder))
                break
            } else {
                ret.append(buffer.substring(startOffset, startOffset + x.piece.length))
            }

            x = x.next()
        }

        return ret.toString()
    }

    fun getLinesContent(): List<String> {
        val lines = mutableListOf<String>()
        // var linesLength = 0
        val currentLine = StringBuffer()
        var danglingCR = false

        this.iterate(this.root) {
            if (it === SENTINEL) {
                return@iterate true
            }

            val piece = it.piece
            var pieceLength = piece.length
            if (pieceLength == 0) {
                return@iterate true
            }

            val buffer = this._buffers[piece.bufferIndex].buffer
            val lineStarts = this._buffers[piece.bufferIndex].lineStarts

            val pieceStartLine = piece.start.line
            val pieceEndLine = piece.end.line
            var pieceStartOffset = lineStarts[pieceStartLine] + piece.start.column

            if (danglingCR) {
                if (Character.codePointAt(buffer, pieceStartOffset) == CharCode.LineFeed) {
                    // pretend the \n was in the previous piece..
                    pieceStartOffset++
                    pieceLength--
                }
                lines.add(currentLine.toString())
                currentLine.delete(0, currentLine.length)
                danglingCR = false
                if (pieceLength == 0) {
                    return@iterate true
                }
            }

            if (pieceStartLine == pieceEndLine) {
                // this piece has no new lines
                if (!this._EOLNormalized &&
                    Character.codePointAt(buffer, pieceStartOffset + pieceLength - 1) ==
                    CharCode.CarriageReturn
                ) {
                    danglingCR = true
                    currentLine.append(
                        buffer.substring(pieceStartOffset, pieceStartOffset + pieceLength - 1)
                    )
                } else {
                    currentLine.append(
                        buffer.substring(pieceStartOffset, pieceStartOffset + pieceLength)
                    )
                }
                return@iterate true
            }

            // add the text before the first line start in this piece
            currentLine.append(
                if (this._EOLNormalized) {
                    buffer.substring(
                        pieceStartOffset,
                        Math.max(pieceStartOffset, lineStarts[pieceStartLine + 1] - this._EOLLength)
                    )
                } else {
                    buffer
                        .substring(pieceStartOffset, lineStarts[pieceStartLine + 1])
                        .replace(Strings.newLine, "")
                }
            )

            lines.add(currentLine.toString())

            for (line in pieceStartLine + 1 until pieceEndLine) {
                currentLine.append(
                    if (this._EOLNormalized) {
                        buffer.substring(lineStarts[line], lineStarts[line + 1] - this._EOLLength)
                    } else {
                        buffer
                            .substring(lineStarts[line], lineStarts[line + 1])
                            .replace(Strings.newLine, "")
                    }
                )
                lines.add(currentLine.toString())
            }

            if (!this._EOLNormalized &&
                Character.codePointAt(buffer, lineStarts[pieceEndLine] + piece.end.column - 1) == CharCode.CarriageReturn
            ) {
                danglingCR = true
                if (piece.end.column == 0) {
                    // The last line ended with a \r, let's undo the push, it will be pushed by next
                    // iteration
                    // linesLength--
                    lines.removeLast()
                } else {
                    currentLine.replace(
                        0,
                        currentLine.length,
                        buffer.substring(
                            lineStarts[pieceEndLine],
                            lineStarts[pieceEndLine] + piece.end.column - 1
                        )
                    )
                }
            } else {
                currentLine.replace(
                    0,
                    currentLine.length,
                    buffer.substring(
                        lineStarts[pieceEndLine],
                        lineStarts[pieceEndLine] + piece.end.column
                    )
                )
            }

            return@iterate true
        }

        if (danglingCR) {
            lines.add(currentLine.toString())
            currentLine.delete(0, currentLine.length)
        }

        lines.add(currentLine.toString())
        return lines
    }

    fun getLength() = this._length

    fun getLineCount() = this._lineCnt

    fun getLineContent(lineNumber: Int): String {
        if (this._lastVisitedLine.lineNumber == lineNumber) {
            return this._lastVisitedLine.value
        }

        this._lastVisitedLine.lineNumber = lineNumber

        if (lineNumber == this._lineCnt) {
            this._lastVisitedLine.value = this.getLineRawContent(lineNumber)
        } else if (this._EOLNormalized) {
            this._lastVisitedLine.value = this.getLineRawContent(lineNumber, this._EOLLength)
        } else {
            this._lastVisitedLine.value = this.getLineRawContent(lineNumber).replace(Strings.newLine, "")
        }

        return this._lastVisitedLine.value
    }
    
    fun getLineContentWithEOL(lineNumber: Int): String {
        return getLineContent(lineNumber) + this._EOL
    }

    private fun _getCharCode(nodePos: NodePosition): Int {
        if (nodePos.remainder == nodePos.node.piece.length) {
            // the char we want to fetch is at the head of next node.
            val matchingNode = nodePos.node.next()
            if (matchingNode === NULL) {
                return 0
            }

            val buffer = this._buffers[matchingNode.piece.bufferIndex]
            val startOffset =
                this.offsetInBuffer(matchingNode.piece.bufferIndex, matchingNode.piece.start)
            return Character.codePointAt(buffer.buffer, startOffset)
        } else {
            val buffer = this._buffers[nodePos.node.piece.bufferIndex]
            val startOffset =
                this.offsetInBuffer(nodePos.node.piece.bufferIndex, nodePos.node.piece.start)
            val targetOffset = startOffset + nodePos.remainder

            return Character.codePointAt(buffer.buffer, targetOffset)
        }
    }

    fun getLineCharCode(lineNumber: Int, index: Int): Int {
        val nodePos = this.nodeAt2(lineNumber, index + 1)
        return this._getCharCode(nodePos)
    }

    fun getLineLength(lineNumber: Int): Int {
        if (lineNumber == this.getLineCount()) {
            val startOffset = this.getOffsetAt(lineNumber, 1)
            return this.getLength() - startOffset
        }
        // return the length of line text 
        return this.getOffsetAt(lineNumber + 1, 1) -
            this.getOffsetAt(lineNumber, 1) - 
            this._EOLLength
    }

    fun getCharCode(offset: Int): Int {
        val nodePos = this.nodeAt(offset)
        return this._getCharCode(nodePos)
    }
    
    /**
	 * get nearest chunk of text after `offset` in the text buffer.
	 * this method is mainly used for treesitter parsing
	 */
    fun getNearestChunk(offset: Int): String {
        val nodePos = this.nodeAt(offset)
        if (nodePos.remainder == nodePos.node.piece.length) {
            // the offset is at the head of next node.
            val matchingNode = nodePos.node.next()
            if (matchingNode === NULL || matchingNode === SENTINEL) {
                return "" // nothing to do
            }

            val buffer = this._buffers[matchingNode.piece.bufferIndex]
            val startOffset = this.offsetInBuffer(matchingNode.piece.bufferIndex, matchingNode.piece.start)
            return buffer.buffer.substring(startOffset, startOffset + matchingNode.piece.length)
        } else {
            val buffer = this._buffers[nodePos.node.piece.bufferIndex]
            val startOffset = this.offsetInBuffer(nodePos.node.piece.bufferIndex, nodePos.node.piece.start)
            val targetOffset = startOffset + nodePos.remainder
            val targetEnd = startOffset + nodePos.node.piece.length
            return buffer.buffer.substring(targetOffset, targetEnd)
        }
    }

    // #endregion
    
    /**
     * Multiline search always executes on the lines concatenated with \n. We must therefore
     * compensate for the count of \n in case the model is CRLF
     */
    private fun _getMultilineMatchRange(
        text: String,
        deltaOffset: Int,
        lfCounter: LineFeedCounter?,
        matchIndex: Int,
        value: String
    ): Range {
        var startOffset: Int
        var endOffset: Int
        var lineFeedCountBeforeMatch: Int

        if (lfCounter != null) {
            lineFeedCountBeforeMatch = lfCounter.findLineFeedCountBeforeOffset(matchIndex)
            startOffset = deltaOffset + matchIndex + lineFeedCountBeforeMatch /* add as many \r as there were \n */

            val lineFeedCountBeforeEndOfMatch = lfCounter.findLineFeedCountBeforeOffset(matchIndex + value.length)
            val lineFeedCountInMatch = lineFeedCountBeforeEndOfMatch - lineFeedCountBeforeMatch
            endOffset = startOffset + value.length + lineFeedCountInMatch /* add as many \r as there were \n */
        } else {
            startOffset = deltaOffset + matchIndex
            endOffset = startOffset + value.length
        }

        val startPos = this.getPositionAt(startOffset)
        val endPos = this.getPositionAt(endOffset)
        return Range(
            startPos.lineNumber,
            startPos.column,
            endPos.lineNumber,
            endPos.column
        )
    }
    
    // search by regex supports multiline mode
    fun findMatchesByMultiline(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val pos = searchRange.getStartPosition()
        val deltaOffset = this.getOffsetAt(pos.lineNumber, pos.column)

        // We always execute multiline search over the lines joined with \n
        // This makes it that \n will match the EOL for both CRLF and LF models
        // We compensate for offset errors in `_getMultilineMatchRange`
        val text = this.getValueInRange(searchRange, "\n")
        val lfCounter: LineFeedCounter? =
            if (this.getEOL() == "\r\n") LineFeedCounter(text) else null
            
        val result = mutableListOf<Range>()
        var match = regex.find(text)
        while (match != null && result.size < limitResultCount && !isCancelled()) {
            result.add(
               this._getMultilineMatchRange(
                    text,
                    deltaOffset,
                    lfCounter,
                    match.range.start,
                    match.value
                )
            )
            // continue to find next
            match = match.next()
        }
        
        return result
    }
    
    private fun _findMatchesInNode(
        node: TreeNode,
        regex: Regex,
        startLineNumber: Int,
        startColumn: Int,
        startCursor: BufferCursor,
        endCursor: BufferCursor,
        limitResultCount: Int,
        result: MutableList<Range>,
        isCancelled: () -> Boolean
    ): Int {
        val buffer = this._buffers[node.piece.bufferIndex]
        val startOffsetInBuffer = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start)
        val start = this.offsetInBuffer(node.piece.bufferIndex, startCursor)
        val end = this.offsetInBuffer(node.piece.bufferIndex, endCursor)

        var startIndex = start
        // let m: RegExpExecArray | null
        // Reset regex to search from the beginning
        val ret = BufferCursor(0, 0)
        var searchText = buffer.buffer.toString()

        // regex matcher
        var match = regex.find(searchText, startIndex)

        while (match != null && !isCancelled()) {
            if (match.range.start >= end) {
                return result.size
            }
            this.positionInBuffer(
                node,
                match.range.start - startOffsetInBuffer,
                ret
            )
            val lineFeedCnt = this.getLineFeedCnt(node.piece.bufferIndex, startCursor, ret)
            val retStartColumn =
                if (ret.line == startCursor.line) {
                    ret.column - startCursor.column + startColumn
                } else {
                    ret.column + 1
                }
            val retEndColumn = retStartColumn + match.range.count()
            result.add(
                Range(
                    startLineNumber + lineFeedCnt,
                    retStartColumn,
                    startLineNumber + lineFeedCnt,
                    retEndColumn
                )
            )

            if (match.range.start + match.range.count() >= end) {
                return result.size
            }

            if (result.size >= limitResultCount) {
                return result.size
            }
            // search the next
            match = match.next()
        }

        return result.size
    }
    
    // search by regex, single line mode
    fun findMatchesLineByLine(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val result = mutableListOf<Range>()

        var startPosition = this.nodeAt2(searchRange.startLine, searchRange.startColumn)
        if (startPosition === NPOS) {
            return result
        }
        var endPosition = this.nodeAt2(searchRange.endLine, searchRange.endColumn)
        if (endPosition === NPOS) {
            return result
        }
        var start = this.positionInBuffer(startPosition.node, startPosition.remainder)
        var end = this.positionInBuffer(endPosition.node, endPosition.remainder)

        if (startPosition.node === endPosition.node) {
            this._findMatchesInNode(
                startPosition.node,
                regex,
                searchRange.startLine,
                searchRange.startColumn,
                start,
                end,
                limitResultCount,
                result,
                isCancelled
            )
            return result
        }

        var startLineNumber = searchRange.startLine

        var currentNode = startPosition.node
        while (currentNode !== endPosition.node) {
            val lineBreakCnt = this.getLineFeedCnt(currentNode.piece.bufferIndex, start, currentNode.piece.end)

            if (lineBreakCnt >= 1) {
                // last line break position
                val lineStarts = this._buffers[currentNode.piece.bufferIndex].lineStarts
                val startOffsetInBuffer = this.offsetInBuffer(currentNode.piece.bufferIndex, currentNode.piece.start)
                val nextLineStartOffset = lineStarts[start.line + lineBreakCnt]
                val startColumn = if (startLineNumber == searchRange.startLine) searchRange.startColumn else 1
                this._findMatchesInNode(
                    currentNode,
                    regex,
                    startLineNumber,
                    startColumn,
                    start,
                    this.positionInBuffer(currentNode, nextLineStartOffset - startOffsetInBuffer),
                    limitResultCount,
                    result,
                    isCancelled
                )

                if (result.size >= limitResultCount) {
                    return result
                }

                startLineNumber += lineBreakCnt
            }

            val startColumn = if (startLineNumber == searchRange.startLine) searchRange.startColumn - 1 else 0
            // search for the remaining content
            if (startLineNumber == searchRange.endLine) {
                val searchText = this
                    .getLineContent(startLineNumber)
                    .substring(startColumn, searchRange.endColumn - 1)

                this._findMatchesInLine(
                    searchText,
                    regex,
                    searchRange.endLine,
                    startColumn,
                    result,
                    limitResultCount
                )
                return result
            }

            this._findMatchesInLine(
                this.getLineContent(startLineNumber).substring(startColumn),
                regex,
                startLineNumber,
                startColumn,
                result,
                limitResultCount
            )

            if (result.size >= limitResultCount) {
                return result
            }

            startLineNumber++
            startPosition = this.nodeAt2(startLineNumber, 1)
            currentNode = startPosition.node
            start = this.positionInBuffer(startPosition.node, startPosition.remainder)
        }

        if (startLineNumber == searchRange.endLine) {
            val startColumn = if (startLineNumber == searchRange.startLine) searchRange.startColumn - 1 else 0
            val searchText = this.getLineContent(startLineNumber)
                .substring(startColumn, searchRange.endColumn - 1)
            this._findMatchesInLine(
                searchText,
                regex,
                searchRange.endLine,
                startColumn,
                result,
                limitResultCount
            )
            return result
        }

        val startColumn = if (startLineNumber == searchRange.startLine) searchRange.startColumn else 1
        this._findMatchesInNode(
            endPosition.node,
            regex,
            startLineNumber,
            startColumn,
            start,
            end,
            limitResultCount,
            result,
            isCancelled
        )
        // xxx
        return result
    }

    private fun _findMatchesInLine(
        text: String,
        regex: Regex,
        lineNumber: Int,
        deltaOffset: Int,
        result: MutableList<Range>,
        limitResultCount: Int
    ): Int {
        // Reset regex to search from the beginning
        var match = regex.find(text)
        while (match != null && result.size < limitResultCount) {
            result.add(
                Range(
                    lineNumber,
                    match.range.start + 1 + deltaOffset,
                    lineNumber,
                    match.range.start + 1 + match.range.count() + deltaOffset
                )
            )
            // search the next
            match = match.next()
        }
        return result.size
    }
    
    // search by word supports multiline mode
    fun findMatchesByWord(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val result = mutableListOf<Range>()
        // lineFeed count
        val (lines, _, lastLineLength, _) = Strings.countEOL(searchText)
        
        var deltaCount: Int
        var startOffset: Int
        var lineNumber = searchRange.startLine
        
        while(lineNumber + lines <= searchRange.endLine) {        
            // get text from the range
            val text = getValueInRange(Range(
                lineNumber, 
                1, 
                lineNumber + lines,
                getLineLength(lineNumber + lines) + 1
            ))
           
            var lastMatchIndex = text.indexOf(searchText, 0)
            deltaCount = if(lastMatchIndex != -1) lines + 1 else 1
            
            while (lastMatchIndex != -1 && result.size < limitResultCount && !isCancelled()) {
                // match found
                // lines > 0 indicates that contains line feed
                startOffset = if(lines > 0) 0 else lastMatchIndex
                result.add(Range(
                    lineNumber,
                    lastMatchIndex + 1,
                    lineNumber + lines,
                    startOffset + lastLineLength + 1
                ))
               
                // next match start index
                lastMatchIndex = text.indexOf(searchText, lastMatchIndex + lastLineLength)
            }
            
            // next start line number
            lineNumber += deltaCount
        }
        return result
    }
    
    // #endregion
    
    // #region Piece Table
    
    fun insert(offset: Int, text: String, eolNormalized: Boolean = false) {
        this._EOLNormalized = this._EOLNormalized && eolNormalized
        this._lastVisitedLine.lineNumber = 0
        this._lastVisitedLine.value = ""
        var value = text

        if (this.root !== SENTINEL) {
            val (node, remainder, nodeStartOffset) = this.nodeAt(offset)
            val piece = node.piece
            val bufferIndex = piece.bufferIndex
            val insertPosInBuffer = this.positionInBuffer(node, remainder)
            if (node.piece.bufferIndex == 0 &&
                piece.end.line == this._lastChangeBufferPos.line &&
                piece.end.column == this._lastChangeBufferPos.column &&
                (nodeStartOffset + piece.length == offset) &&
                value.length < AverageBufferSize
            ) {
                // changed buffer
                this.appendToNode(node, value)
                this.computeBufferMetadata()
                return@insert
            }

            if (nodeStartOffset == offset) {
                this.insertContentToNodeLeft(value, node)
                this._searchCache.validate(offset)
            } else if (nodeStartOffset + node.piece.length > offset) {
                // we are inserting into the middle of a node.
                val nodesToDel = mutableListOf<TreeNode>()
                var newRightPiece = Piece(
                    piece.bufferIndex,
                    insertPosInBuffer,
                    piece.end,
                    this.getLineFeedCnt(piece.bufferIndex, insertPosInBuffer, piece.end),
                    this.offsetInBuffer(bufferIndex, piece.end) - this.offsetInBuffer(bufferIndex, insertPosInBuffer)
                )

                if (this.shouldCheckCRLF() && this.endWithCR(value)) {
                    val headOfRight = this.nodeCharCodeAt(node, remainder)

                    if (headOfRight == 10 /** \n */
                    ) {
                        val newStart = BufferCursor(newRightPiece.start.line + 1, 0)
                        newRightPiece = Piece(
                            newRightPiece.bufferIndex,
                            newStart,
                            newRightPiece.end,
                            this.getLineFeedCnt(
                                newRightPiece.bufferIndex,
                                newStart,
                                newRightPiece.end
                            ),
                            newRightPiece.length - 1
                        )

                        value += '\n'
                    }
                }

                // reuse node for content before insertion point.
                if (this.shouldCheckCRLF() && this.startWithLF(value)) {
                    val tailOfLeft = this.nodeCharCodeAt(node, remainder - 1)
                    if (tailOfLeft == 13 /** \r */
                    ) {
                        val previousPos = this.positionInBuffer(node, remainder - 1)
                        this.deleteNodeTail(node, previousPos)
                        value = '\r' + value

                        if (node.piece.length == 0) {
                            nodesToDel.add(node)
                        }
                    } else {
                        this.deleteNodeTail(node, insertPosInBuffer)
                    }
                } else {
                    this.deleteNodeTail(node, insertPosInBuffer)
                }

                val newPieces = this.createNewPieces(value)

                if (newRightPiece.length > 0) {
                    this.rbInsertRight(node, newRightPiece)
                }

                var tmpNode = node
                for (k in 0..newPieces.size - 1) {
                    tmpNode = this.rbInsertRight(tmpNode, newPieces[k])
                }
                this.deleteNodes(nodesToDel)
            } else {
                this.insertContentToNodeRight(value, node)
            }
        } else {
            // insert new node
            val pieces = this.createNewPieces(value)
            var node = this.rbInsertLeft(NULL, pieces[0])

            for (k in 1..pieces.size - 1) {
                node = this.rbInsertRight(node, pieces[k])
            }
        }

        // todo, this is too brutal. Total line feed count should be updated the same way as
        // lf_left.
        this.computeBufferMetadata()
    }

    fun delete(offset: Int, cnt: Int) {
        this._lastVisitedLine.lineNumber = 0
        this._lastVisitedLine.value = ""

        if (cnt <= 0 || this.root === SENTINEL) {
            return@delete
        }

        val startPosition = this.nodeAt(offset)
        val endPosition = this.nodeAt(offset + cnt)
        val startNode = startPosition.node
        val endNode = endPosition.node

        if (startNode === endNode) {
            val startSplitPosInBuffer = this.positionInBuffer(startNode, startPosition.remainder)
            val endSplitPosInBuffer = this.positionInBuffer(startNode, endPosition.remainder)

            if (startPosition.nodeStartOffset == offset) {
                if (cnt == startNode.piece.length) { // delete node
                    val next = startNode.next()
                    rbDelete(this, startNode)
                    this.validateCRLFWithPrevNode(next)
                    this.computeBufferMetadata()
                    return@delete
                }
                this.deleteNodeHead(startNode, endSplitPosInBuffer)
                this._searchCache.validate(offset)
                this.validateCRLFWithPrevNode(startNode)
                this.computeBufferMetadata()
                return@delete
            }

            if (startPosition.nodeStartOffset + startNode.piece.length == offset + cnt) {
                this.deleteNodeTail(startNode, startSplitPosInBuffer)
                this.validateCRLFWithNextNode(startNode)
                this.computeBufferMetadata()
                return@delete
            }

            // delete content in the middle, this node will be splitted to nodes
            this.shrinkNode(startNode, startSplitPosInBuffer, endSplitPosInBuffer)
            this.computeBufferMetadata()
            return@delete
        }

        val nodesToDel = mutableListOf<TreeNode>()

        val startSplitPosInBuffer = this.positionInBuffer(startNode, startPosition.remainder)
        this.deleteNodeTail(startNode, startSplitPosInBuffer)
        this._searchCache.validate(offset)
        if (startNode.piece.length == 0) {
            nodesToDel.add(startNode)
        }

        // update last touched node
        val endSplitPosInBuffer = this.positionInBuffer(endNode, endPosition.remainder)
        this.deleteNodeHead(endNode, endSplitPosInBuffer)
        if (endNode.piece.length == 0) {
            nodesToDel.add(endNode)
        }

        // delete nodes in between
        var node = startNode.next()
        while (node !== SENTINEL && node !== endNode) {
            nodesToDel.add(node)
            node = node.next()
        }

        val prev = if (startNode.piece.length == 0) startNode.prev() else startNode
        this.deleteNodes(nodesToDel)
        this.validateCRLFWithNextNode(prev)
        this.computeBufferMetadata()
    }

    private fun insertContentToNodeLeft(text: String, node: TreeNode) {
        // we are inserting content to the beginning of node
        val nodesToDel = mutableListOf<TreeNode>()
        var value = text
        if (this.shouldCheckCRLF() && this.endWithCR(value) && this.startWithLF(node)) {
            // move `\n` to new node.
            val piece = node.piece
            val newStart = BufferCursor(piece.start.line + 1, 0)
            val nPiece = Piece(
                piece.bufferIndex,
                newStart,
                piece.end,
                this.getLineFeedCnt(piece.bufferIndex, newStart, piece.end),
                piece.length - 1
            )

            node.piece = nPiece

            value += '\n'
            updateTreeMetadata(this, node, -1, -1)

            if (node.piece.length == 0) {
                nodesToDel.add(node)
            }
        }

        var newPieces = this.createNewPieces(value)
        var newNode = this.rbInsertLeft(node, newPieces[newPieces.size - 1])
        for (k in newPieces.size - 2 downTo 0) {
            newNode = this.rbInsertLeft(newNode, newPieces[k])
        }
        this.validateCRLFWithPrevNode(newNode)
        this.deleteNodes(nodesToDel)
    }

    private fun insertContentToNodeRight(text: String, node: TreeNode) {
        // we are inserting to the right of this node.
        var value = text
        if (this.adjustCarriageReturnFromNext(value, node)) {
            // move \n to the new node.
            value += '\n'
        }

        val newPieces = this.createNewPieces(value)
        val newNode = this.rbInsertRight(node, newPieces[0])
        var tmpNode = newNode

        for (k in 1..newPieces.size - 1) {
            tmpNode = this.rbInsertRight(tmpNode, newPieces[k])
        }

        this.validateCRLFWithPrevNode(newNode)
    }

    private fun positionInBuffer(
        node: TreeNode,
        remainder: Int,
        ret: BufferCursor? = null
    ): BufferCursor {
        val piece = node.piece
        val bufferIndex = node.piece.bufferIndex
        val lineStarts = this._buffers[bufferIndex].lineStarts

        val startOffset = lineStarts[piece.start.line] + piece.start.column

        val offset = startOffset + remainder

        // binary search offset between startOffset and endOffset
        var low = piece.start.line
        var high = piece.end.line

        var mid: Int = 0
        var midStop: Int = 0
        var midStart: Int = 0

        while (low <= high) {
            // mid = low + ((high - low) / 2) or 0
            mid = (low + high) shr 1
            midStart = lineStarts[mid]

            if (mid == high) {
                break
            }

            midStop = lineStarts[mid + 1]

            if (offset < midStart) {
                high = mid - 1
            } else if (offset >= midStop) {
                low = mid + 1
            } else {
                break
            }
        }

        if (ret != null) {
            ret.line = mid
            ret.column = offset - midStart
            return ret
        }

        return BufferCursor(mid, offset - midStart)
    }

    private fun getLineFeedCnt(bufferIndex: Int, start: BufferCursor, end: BufferCursor): Int {
        // we don't need to worry about start: abc\r|\n, or abc|\r, or abc|\n, or abc|\r\n doesn't
        // change the fact that, there is one line break after start.
        // now let's take care of end: abc\r|\n, if end is in between \r and \n, we need to add line
        // feed count by 1
        if (end.column == 0) {
            return end.line - start.line
        }

        val lineStarts = this._buffers[bufferIndex].lineStarts
        if (end.line == lineStarts.size - 1
        ) { // it means, there is no \n after end, otherwise, there will be one more lineStart.
            return end.line - start.line
        }

        val nextLineStartOffset = lineStarts[end.line + 1]
        val endOffset = lineStarts[end.line] + end.column
        if (nextLineStartOffset > endOffset + 1
        ) { // there are more than 1 character after end, which means it can't be \n
            return end.line - start.line
        }
        // endOffset + 1 === nextLineStartOffset
        // character at endOffset is \n, so we check the character before first
        // if character at endOffset is \r, end.column is 0 and we can't get here.
        val previousCharOffset = endOffset - 1 // end.column > 0 so it's okay.
        val buffer = this._buffers[bufferIndex].buffer

        if (Character.codePointAt(buffer, previousCharOffset) == 13) {
            return end.line - start.line + 1
        } else {
            return end.line - start.line
        }
    }

    private fun offsetInBuffer(bufferIndex: Int, cursor: BufferCursor): Int {
        val lineStarts = this._buffers[bufferIndex].lineStarts
        return lineStarts[cursor.line] + cursor.column
    }

    private fun deleteNodes(nodes: List<TreeNode>) {
        for (node in nodes) {
            rbDelete(this, node)
        }
    }

    private fun createNewPieces(value: String): List<Piece> {
        var text = value
        if (text.length > AverageBufferSize) {
            // the content is large, operations like substring, charCode becomes slow
            // so here we split it into smaller chunks, just like what we did for CR/LF
            // normalization
            val newPieces = mutableListOf<Piece>()
            while (text.length > AverageBufferSize) {
                val lastChar = text.codePointAt(AverageBufferSize - 1)
                var splitText: String
                if (lastChar == CharCode.CarriageReturn ||
                    (lastChar >= 0xD800 && lastChar <= 0xDBFF)
                ) {
                    // last character is \r or a high surrogate => keep it back
                    splitText = text.substring(0, AverageBufferSize - 1)
                    text = text.substring(AverageBufferSize - 1)
                } else {
                    splitText = text.substring(0, AverageBufferSize)
                    text = text.substring(AverageBufferSize)
                }

                val lineStarts = createLineStartsFast(splitText)
                newPieces.add(
                    Piece(
                        this._buffers.size, /* buffer index */
                        BufferCursor(0, 0),
                        BufferCursor(
                            lineStarts.size - 1,
                            splitText.length - lineStarts[lineStarts.size - 1]
                        ),
                        lineStarts.size - 1,
                        splitText.length
                    )
                )
                this._buffers.add(TextBuffer(StringBuffer(splitText), lineStarts))
            }

            val lineStarts = createLineStartsFast(text)
            newPieces.add(
                Piece(
                    this._buffers.size, /* buffer index */
                    BufferCursor(0, 0),
                    BufferCursor(
                        lineStarts.size - 1,
                        text.length - lineStarts[lineStarts.size - 1]
                    ),
                    lineStarts.size - 1,
                    text.length
                )
            )
            this._buffers.add(TextBuffer(StringBuffer(text), lineStarts))

            return newPieces
        }

        var startOffset = this._buffers[0].buffer.length
        val lineStarts = createLineStartsFast(text)

        var start = this._lastChangeBufferPos
        if (this._buffers[0].lineStarts[this._buffers[0].lineStarts.size - 1] == startOffset &&
            startOffset != 0 &&
            this.startWithLF(text) &&
            this.endWithCR(this._buffers[0].buffer) // todo, we can check this._lastChangeBufferPos's column as it's the last one
        ) {
            this._lastChangeBufferPos.line = this._lastChangeBufferPos.line
            this._lastChangeBufferPos.column = this._lastChangeBufferPos.column + 1
            start = this._lastChangeBufferPos

            for (i in 0..lineStarts.size - 1) {
                lineStarts[i] += startOffset + 1
            }

            this._buffers[0].lineStarts = this._buffers[0].lineStarts.concat(lineStarts.slice(1..lineStarts.size - 1))
            this._buffers[0].buffer.append('_').append(text)
            startOffset += 1
        } else {
            if (startOffset != 0) {
                for (i in 0..lineStarts.size - 1) {
                    lineStarts[i] += startOffset
                }
            }
            this._buffers[0].lineStarts = this._buffers[0].lineStarts.concat(lineStarts.slice(1..lineStarts.size - 1))
            this._buffers[0].buffer.append(text)
        }

        val endOffset = this._buffers[0].buffer.length
        val endIndex = this._buffers[0].lineStarts.size - 1
        val endColumn = endOffset - this._buffers[0].lineStarts[endIndex]
        val endPos = BufferCursor(endIndex, endColumn)
        val newPiece = Piece(
            0, /** todo@peng */
            start,
            endPos,
            this.getLineFeedCnt(0, start, endPos),
            endOffset - startOffset
        )
        this._lastChangeBufferPos = endPos
        return listOf(newPiece)
    }

    fun getLinesRawContent(): String {
        return this.getContentOfSubTree(this.root)
    }

    fun getLineRawContent(line: Int, endOffset: Int = 0): String {
        var x = this.root
        var lineNumber = line

        var ret = StringBuffer()
        var cache = this._searchCache.get2(lineNumber)
        if (cache != null) {
            x = cache.node
            val prevAccumulatedValue =
                this.getAccumulatedValue(x, lineNumber - cache.nodeStartLineNumber - 1)
            val buffer = this._buffers[x.piece.bufferIndex].buffer
            val startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)
            if (cache.nodeStartLineNumber + x.piece.lineFeedCnt == lineNumber) {
                ret.append(
                    buffer.substring(
                        startOffset + prevAccumulatedValue,
                        startOffset + x.piece.length
                    )
                )
            } else {
                val accumulatedValue = this.getAccumulatedValue(x, lineNumber - cache.nodeStartLineNumber)
                return buffer.substring(
                    startOffset + prevAccumulatedValue,
                    startOffset + accumulatedValue - endOffset
                )
            }
        } else {
            var nodeStartOffset = 0
            val originalLineNumber = lineNumber
            while (x !== SENTINEL) {
                if (x.left !== SENTINEL && x.lf_left >= lineNumber - 1) {
                    x = x.left
                } else if (x.lf_left + x.piece.lineFeedCnt > lineNumber - 1) {
                    val prevAccumulatedValue =
                        this.getAccumulatedValue(x, lineNumber - x.lf_left - 2)
                    val accumulatedValue = this.getAccumulatedValue(x, lineNumber - x.lf_left - 1)
                    val buffer = this._buffers[x.piece.bufferIndex].buffer
                    val startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)
                    nodeStartOffset += x.size_left
                    this._searchCache.set(
                        CacheEntry(
                            node = x,
                            nodeStartOffset = nodeStartOffset,
                            nodeStartLineNumber = originalLineNumber - (lineNumber - 1 - x.lf_left)
                        )
                    )

                    return buffer.substring(
                        startOffset + prevAccumulatedValue,
                        startOffset + accumulatedValue - endOffset
                    )
                } else if (x.lf_left + x.piece.lineFeedCnt == lineNumber - 1) {
                    val prevAccumulatedValue =
                        this.getAccumulatedValue(x, lineNumber - x.lf_left - 2)
                    val buffer = this._buffers[x.piece.bufferIndex].buffer
                    val startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)
                    // check prevAccumulatedValue
                    if (x.piece.length > prevAccumulatedValue) {
                        ret.replace(
                            0,
                            ret.length,
                            buffer.substring(
                                startOffset + prevAccumulatedValue,
                                startOffset + x.piece.length
                            )
                        )
                    } else {
                        ret.delete(0, ret.length)
                    }
                    break
                } else {
                    lineNumber -= x.lf_left + x.piece.lineFeedCnt
                    nodeStartOffset += x.size_left + x.piece.length
                    x = x.right
                }
            }
        }

        // search in order, to find the node contains end column
        x = x.next()
        while (x !== SENTINEL) {
            val buffer = this._buffers[x.piece.bufferIndex].buffer

            if (x.piece.lineFeedCnt > 0) {
                val accumulatedValue = this.getAccumulatedValue(x, 0)
                val startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)

                ret.append(
                    buffer.substring(startOffset, startOffset + accumulatedValue - endOffset)
                )
                return ret.toString()
            } else {
                val startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)
                ret.append(buffer.substring(startOffset, startOffset + x.piece.length))
            }

            x = x.next()
        }

        return ret.toString()
    }

    private fun computeBufferMetadata() {
        var x = this.root

        var lfCnt = 1
        var len = 0

        while (x !== SENTINEL) {
            lfCnt += x.lf_left + x.piece.lineFeedCnt
            len += x.size_left + x.piece.length
            x = x.right
        }

        this._lineCnt = lfCnt
        this._length = len
        this._searchCache.validate(this._length)
    }

    // #endregion

    // #region node operations
    
    // return { index: number, remainder: number }
    private fun getIndexOf(node: TreeNode, accumulatedValue: Int): IntArray {
        val piece = node.piece
        val pos = this.positionInBuffer(node, accumulatedValue)
        val lineCnt = pos.line - piece.start.line

        if (this.offsetInBuffer(piece.bufferIndex, piece.end) -
            this.offsetInBuffer(piece.bufferIndex, piece.start) == accumulatedValue
        ) {
            // we are checking the end of this node, so a CRLF check is necessary.
            val realLineCnt = this.getLineFeedCnt(node.piece.bufferIndex, piece.start, pos)
            if (realLineCnt != lineCnt) {
                // aha yes, CRLF
                return intArrayOf(realLineCnt, 0)
            }
        }

        return intArrayOf(lineCnt, pos.column)
    }

    private fun getAccumulatedValue(node: TreeNode, index: Int): Int {
        if (index < 0) {
            return 0
        }
        val piece = node.piece
        val lineStarts = this._buffers[piece.bufferIndex].lineStarts
        val expectedLineStartIndex = piece.start.line + index + 1
        if (expectedLineStartIndex > piece.end.line) {
            return lineStarts[piece.end.line] + piece.end.column -
                lineStarts[piece.start.line] - piece.start.column
        } else {
            return lineStarts[expectedLineStartIndex] -
                lineStarts[piece.start.line] - piece.start.column
        }
    }

    private fun deleteNodeTail(node: TreeNode, pos: BufferCursor) {
        val piece = node.piece
        val originalLFCnt = piece.lineFeedCnt
        val originalEndOffset = this.offsetInBuffer(piece.bufferIndex, piece.end)

        val newEnd = pos
        val newEndOffset = this.offsetInBuffer(piece.bufferIndex, newEnd)
        val newLineFeedCnt = this.getLineFeedCnt(piece.bufferIndex, piece.start, newEnd)

        val lf_delta = newLineFeedCnt - originalLFCnt
        val size_delta = newEndOffset - originalEndOffset
        val newLength = piece.length + size_delta

        node.piece = Piece(piece.bufferIndex, piece.start, newEnd, newLineFeedCnt, newLength)

        updateTreeMetadata(this, node, size_delta, lf_delta)
    }

    private fun deleteNodeHead(node: TreeNode, pos: BufferCursor) {
        val piece = node.piece
        val originalLFCnt = piece.lineFeedCnt
        val originalStartOffset = this.offsetInBuffer(piece.bufferIndex, piece.start)

        val newStart = pos
        val newLineFeedCnt = this.getLineFeedCnt(piece.bufferIndex, newStart, piece.end)
        val newStartOffset = this.offsetInBuffer(piece.bufferIndex, newStart)
        val lf_delta = newLineFeedCnt - originalLFCnt
        val size_delta = originalStartOffset - newStartOffset
        val newLength = piece.length + size_delta
        node.piece = Piece(piece.bufferIndex, newStart, piece.end, newLineFeedCnt, newLength)

        updateTreeMetadata(this, node, size_delta, lf_delta)
    }

    private fun shrinkNode(node: TreeNode, start: BufferCursor, end: BufferCursor) {
        val piece = node.piece
        val originalStartPos = piece.start
        val originalEndPos = piece.end

        // old piece, originalStartPos, start
        val oldLength = piece.length
        val oldLFCnt = piece.lineFeedCnt
        val newEnd = start
        val newLineFeedCnt = this.getLineFeedCnt(piece.bufferIndex, piece.start, newEnd)
        val newLength = this.offsetInBuffer(piece.bufferIndex, start) -
            this.offsetInBuffer(piece.bufferIndex, originalStartPos)

        node.piece = Piece(piece.bufferIndex, piece.start, newEnd, newLineFeedCnt, newLength)

        updateTreeMetadata(this, node, newLength - oldLength, newLineFeedCnt - oldLFCnt)

        // new right piece, end, originalEndPos
        val newPiece = Piece(
            piece.bufferIndex,
            end,
            originalEndPos,
            this.getLineFeedCnt(piece.bufferIndex, end, originalEndPos),
            this.offsetInBuffer(piece.bufferIndex, originalEndPos) - this.offsetInBuffer(piece.bufferIndex, end)
        )

        val newNode = this.rbInsertRight(node, newPiece)
        this.validateCRLFWithPrevNode(newNode)
    }

    private fun appendToNode(node: TreeNode, text: String) {
        var value = text
        if (this.adjustCarriageReturnFromNext(value, node)) {
            value += '\n'
        }

        val hitCRLF = this.shouldCheckCRLF() && this.startWithLF(value) && this.endWithCR(node)
        val startOffset = this._buffers[0].buffer.length
        this._buffers[0].buffer.append(value)
        val lineStarts = createLineStartsFast(value)
        for (i in 0..lineStarts.size - 1) {
            lineStarts[i] += startOffset
        }
        if (hitCRLF) {
            val prevStartOffset = this._buffers[0].lineStarts[this._buffers[0].lineStarts.size - 2]
            // remove the last element
            this._buffers[0].lineStarts.removeLast()
            // _lastChangeBufferPos is already wrong
            this._lastChangeBufferPos.line = this._lastChangeBufferPos.line - 1
            this._lastChangeBufferPos.column = startOffset - prevStartOffset
        }

        this._buffers[0].lineStarts = this._buffers[0].lineStarts.concat(lineStarts.slice(1..lineStarts.size - 1))
        val endIndex = this._buffers[0].lineStarts.size - 1
        val endColumn = this._buffers[0].buffer.length - this._buffers[0].lineStarts[endIndex]
        val newEnd = BufferCursor(endIndex, endColumn)
        val newLength = node.piece.length + value.length
        val oldLineFeedCnt = node.piece.lineFeedCnt
        val newLineFeedCnt = this.getLineFeedCnt(0, node.piece.start, newEnd)
        val lf_delta = newLineFeedCnt - oldLineFeedCnt

        node.piece = Piece(node.piece.bufferIndex, node.piece.start, newEnd, newLineFeedCnt, newLength)

        this._lastChangeBufferPos = newEnd
        updateTreeMetadata(this, node, value.length, lf_delta)
    }

    private fun nodeAt(index: Int): NodePosition {
        var x = this.root
        var offset = index
        val cache = this._searchCache.get(offset)
        if (cache != null) {
            return NodePosition(
                node = cache.node,
                remainder = offset - cache.nodeStartOffset,
                nodeStartOffset = cache.nodeStartOffset
            )
        }

        var nodeStartOffset = 0

        while (x !== SENTINEL) {
            if (x.size_left > offset) {
                x = x.left
            } else if (x.size_left + x.piece.length >= offset) {
                nodeStartOffset += x.size_left
                val ret = NodePosition(
                    node = x,
                    remainder = offset - x.size_left,
                    nodeStartOffset = nodeStartOffset
                )
                this._searchCache.set(CacheEntry(x, nodeStartOffset, 0))
                return ret
            } else {
                offset -= x.size_left + x.piece.length
                nodeStartOffset += x.size_left + x.piece.length
                x = x.right
            }
        }
        return NPOS // null node position
    }

    private fun nodeAt2(row: Int, col: Int): NodePosition {
        var x = this.root
        var nodeStartOffset = 0
        var lineNumber = row
        var column = col

        while (x !== SENTINEL) {
            if (x.left !== SENTINEL && x.lf_left >= lineNumber - 1) {
                x = x.left
            } else if (x.lf_left + x.piece.lineFeedCnt > lineNumber - 1) {
                val prevAccumualtedValue = this.getAccumulatedValue(x, lineNumber - x.lf_left - 2)
                val accumulatedValue = this.getAccumulatedValue(x, lineNumber - x.lf_left - 1)
                nodeStartOffset += x.size_left

                return NodePosition(
                    node = x,
                    remainder = Math.min(prevAccumualtedValue + column - 1, accumulatedValue),
                    nodeStartOffset = nodeStartOffset
                )
            } else if (x.lf_left + x.piece.lineFeedCnt == lineNumber - 1) {
                val prevAccumualtedValue = this.getAccumulatedValue(x, lineNumber - x.lf_left - 2)
                if (prevAccumualtedValue + column - 1 <= x.piece.length) {
                    return NodePosition(
                        node = x,
                        remainder = prevAccumualtedValue + column - 1,
                        nodeStartOffset = nodeStartOffset
                    )
                } else {
                    column -= x.piece.length - prevAccumualtedValue
                    break
                }
            } else {
                lineNumber -= x.lf_left + x.piece.lineFeedCnt
                nodeStartOffset += x.size_left + x.piece.length
                x = x.right
            }
        }

        // search in order, to find the node contains position.column
        x = x.next()
        while (x !== SENTINEL) {
            if (x.piece.lineFeedCnt > 0) {
                val accumulatedValue = this.getAccumulatedValue(x, 0)
                nodeStartOffset = this.offsetOfNode(x)
                return NodePosition(
                    node = x,
                    remainder = Math.min(column - 1, accumulatedValue),
                    nodeStartOffset = nodeStartOffset
                )
            } else {
                if (x.piece.length >= column - 1) {
                    nodeStartOffset = this.offsetOfNode(x)
                    return NodePosition(
                        node = x,
                        remainder = column - 1,
                        nodeStartOffset = nodeStartOffset
                    )
                } else {
                    column -= x.piece.length
                }
            }

            x = x.next()
        }

        return NPOS // null node position
    }

    private fun nodeCharCodeAt(node: TreeNode, offset: Int): Int {
        if (node.piece.lineFeedCnt < 1) {
            return -1
        }
        val buffer = this._buffers[node.piece.bufferIndex]
        val newOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start) + offset
        return Character.codePointAt(buffer.buffer, newOffset)
    }

    private fun offsetOfNode(x: TreeNode): Int {
        var node = x
        if (node === NULL) {
            return 0
        }
        var pos = node.size_left
        while (node !== this.root) {
            if (node.parent.right === node) {
                pos += node.parent.size_left + node.parent.piece.length
            }
            node = node.parent
        }
        return pos
    }

    // #endregion

    // #region CRLF
    
    private fun shouldCheckCRLF(): Boolean {
        return !(this._EOLNormalized && this._EOL == "\n")
    }

    // param: string | TreeNode
    private fun startWithLF(chr: CharSequence): Boolean {
        return Character.codePointAt(chr, 0) == 10
    }

    private fun startWithLF(node: TreeNode): Boolean {
        if (node === SENTINEL || node.piece.lineFeedCnt == 0) {
            return false
        }

        val piece = node.piece
        val lineStarts = this._buffers[piece.bufferIndex].lineStarts
        val line = piece.start.line
        val startOffset = lineStarts[line] + piece.start.column
        if (line == lineStarts.size - 1) {
            // last line, so there is no line feed at the end of this line
            return false
        }
        val nextLineOffset = lineStarts[line + 1]
        if (nextLineOffset > startOffset + 1) {
            return false
        }

        return Character.codePointAt(this._buffers[piece.bufferIndex].buffer, startOffset) == 10
    }

    private fun endWithCR(chr: CharSequence): Boolean {
        return Character.codePointAt(chr, chr.length - 1) == 13
    }

    private fun endWithCR(node: TreeNode): Boolean {
        if (node === SENTINEL || node.piece.lineFeedCnt == 0) {
            return false
        }
        return this.nodeCharCodeAt(node, node.piece.length - 1) == 13
    }

    private fun validateCRLFWithPrevNode(nextNode: TreeNode) {
        if (this.shouldCheckCRLF() && this.startWithLF(nextNode)) {
            val node = nextNode.prev()
            if (this.endWithCR(node)) {
                this.fixCRLF(node, nextNode)
            }
        }
    }

    private fun validateCRLFWithNextNode(node: TreeNode) {
        if (this.shouldCheckCRLF() && this.endWithCR(node)) {
            val nextNode = node.next()
            if (this.startWithLF(nextNode)) {
                this.fixCRLF(node, nextNode)
            }
        }
    }

    private fun fixCRLF(prev: TreeNode, next: TreeNode) {
        val nodesToDel = mutableListOf<TreeNode>()
        // update node
        val lineStarts = this._buffers[prev.piece.bufferIndex].lineStarts
        var newEnd: BufferCursor
        if (prev.piece.end.column == 0) {
            // it means, last line ends with \r, not \r\n
            newEnd = BufferCursor(
                prev.piece.end.line - 1,
                lineStarts[prev.piece.end.line] - lineStarts[prev.piece.end.line - 1] - 1
            )
        } else {
            // \r\n
            newEnd = BufferCursor(prev.piece.end.line, prev.piece.end.column - 1)
        }

        val prevNewLength = prev.piece.length - 1
        val prevNewLFCnt = prev.piece.lineFeedCnt - 1
        prev.piece = Piece(prev.piece.bufferIndex, prev.piece.start, newEnd, prevNewLFCnt, prevNewLength)

        updateTreeMetadata(this, prev, -1, -1)
        if (prev.piece.length == 0) {
            nodesToDel.add(prev)
        }

        // update nextNode
        val newStart = BufferCursor(next.piece.start.line + 1, 0)
        val newLength = next.piece.length - 1
        val newLineFeedCnt = this.getLineFeedCnt(next.piece.bufferIndex, newStart, next.piece.end)
        next.piece = Piece(next.piece.bufferIndex, newStart, next.piece.end, newLineFeedCnt, newLength)

        updateTreeMetadata(this, next, -1, -1)
        if (next.piece.length == 0) {
            nodesToDel.add(next)
        }

        // create new piece which contains \r\n
        val pieces = this.createNewPieces("\r\n")
        this.rbInsertRight(prev, pieces[0])
        // delete empty nodes

        for (node in nodesToDel) {
            rbDelete(this, node)
        }
    }

    private fun adjustCarriageReturnFromNext(value: String, node: TreeNode): Boolean {
        if (this.shouldCheckCRLF() && this.endWithCR(value)) {
            val nextNode = node.next()
            if (this.startWithLF(nextNode)) {
                // move `\n` forward
                // value += '\n'

                if (nextNode.piece.length == 1) {
                    rbDelete(this, nextNode)
                } else {
                    val piece = nextNode.piece
                    val newStart = BufferCursor(piece.start.line + 1, 0)
                    val newLength = piece.length - 1
                    val newLineFeedCnt = this.getLineFeedCnt(piece.bufferIndex, newStart, piece.end)
                    nextNode.piece = Piece(piece.bufferIndex, newStart, piece.end, newLineFeedCnt, newLength)
                    updateTreeMetadata(this, nextNode, -1, -1)
                }
                return true
            }
        }

        return false
    }

    // #endregion

    // #region Tree operations
    
    fun iterate(node: TreeNode, callback: (TreeNode) -> Boolean): Boolean {
        if (node === SENTINEL) {
            return callback(node)
        }

        val leftRet = this.iterate(node.left, callback)
        if (!leftRet) {
            return leftRet
        }

        return callback(node) && this.iterate(node.right, callback)
    }

    private fun getNodeContent(node: TreeNode): String {
        if (node === SENTINEL) {
            return ""
        }

        val buffer = this._buffers[node.piece.bufferIndex]
        var currentContent: String
        val startOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start)
        val endOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.end)
        currentContent = buffer.buffer.substring(startOffset, endOffset)
        return currentContent
    }

    fun getPieceContent(piece: Piece): String {
        val buffer = this._buffers[piece.bufferIndex]
        val startOffset = this.offsetInBuffer(piece.bufferIndex, piece.start)
        val endOffset = this.offsetInBuffer(piece.bufferIndex, piece.end)
        val currentContent = buffer.buffer.substring(startOffset, endOffset)
        return currentContent
    }

    /**
     * ```
     *      node            node
     *     /  \              /  \
     *    a   b    <----   a    b
     *                         /
     *                        z
     * ```
     */
    private fun rbInsertRight(node: TreeNode, p: Piece): TreeNode {
        val z = TreeNode(p, NodeColor.Red)
        z.left = SENTINEL
        z.right = SENTINEL
        z.parent = SENTINEL
        z.size_left = 0
        z.lf_left = 0

        val x = this.root
        if (x === SENTINEL) {
            this.root = z
            z.color = NodeColor.Black
        } else if (node.right === SENTINEL) {
            node.right = z
            z.parent = node
        } else {
            val nextNode = leftest(node.right)
            nextNode.left = z
            z.parent = nextNode
        }

        fixInsert(this, z)
        return z
    }

    /**
     * ```
     *      node            node
     *     /  \              /  \
     *    a   b     ---->   a    b
     *                       \
     *                        z
     * ```
     */
    private fun rbInsertLeft(node: TreeNode, p: Piece): TreeNode {
        val z = TreeNode(p, NodeColor.Red)
        z.left = SENTINEL
        z.right = SENTINEL
        z.parent = SENTINEL
        z.size_left = 0
        z.lf_left = 0

        if (this.root === SENTINEL) {
            this.root = z
            z.color = NodeColor.Black
        } else if (node.left === SENTINEL) {
            node.left = z
            z.parent = node
        } else {
            val prevNode: TreeNode = rightest(node.left)
            prevNode.right = z
            z.parent = prevNode
        }

        fixInsert(this, z)
        return z
    }

    private fun getContentOfSubTree(node: TreeNode): String {
        val buf = StringBuffer()
        this.iterate(node) {
            buf.append(this.getNodeContent(it))
            return@iterate true
        }

        return buf.toString()
    }

    // #endregion
}

// #endregion class

