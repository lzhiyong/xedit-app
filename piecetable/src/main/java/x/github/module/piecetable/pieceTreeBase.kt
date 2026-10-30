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
 * src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeBase.ts, and changed since.
 */

package x.github.module.piecetable

import x.github.module.piecetable.common.*


/*
 * HOW THE PIECE TREE WORKS (read this first)
 *
 * Text storage
 *   _buffers[0]      the "change buffer". Append-only: every inserted string is appended here
 *                    and is never modified or removed afterwards (deleted text stays too).
 *   _buffers[1..n]   the original file content, one TextBuffer per chunk handed to the builder.
 *                    Never modified after construction.
 *   Every buffer keeps `lineStarts`: lineStarts[i] is the buffer offset where line i begins.
 *
 * Document = ordered list of pieces
 *   A Piece is a slice [start, end) of ONE buffer. start/end are BufferCursors (line, column)
 *   relative to that buffer's lineStarts, so `lineStarts[c.line] + c.column` is the offset.
 *   Concatenating all pieces in order gives the document. Edits never move existing text,
 *   they only split, shrink, add or remove pieces.
 *
 * Index = augmented red-black tree (see rbTreeBase.kt)
 *   Pieces are the nodes, in-order traversal is document order. Every node also stores
 *   size_left / lf_left: total length and total line feed count of its LEFT subtree. That is
 *   enough to find the piece containing a document offset or a line number in O(log n).
 *
 * CRLF invariant
 *   When CRLF checking is on (see shouldCheckCRLF), a "\r\n" pair is never split across two
 *   pieces; otherwise line feed counts would be wrong. All the validateCRLF / fixCRLF /
 *   adjustCarriageReturnFromNext code exists only to keep this invariant after an edit.
 *
 * Threading
 *   Per tree: one edit at a time, and never together with a read of the same tree. Different
 *   trees share no mutable state (each owns its sentinel), so they can be used on different
 *   threads at the same time without any coordination.
 *   Reading from several threads while nobody edits is safe: the two things a read
 *   updates are the search cache, whose methods are synchronized, and
 *   _lastVisitedLine, which is replaced as a whole and never changed in place.
 *   The readers must see the finished edit, i.e. the last edit has to happen-before the
 *   reads (a lock released by the editing thread, a task submitted to an executor after
 *   the edit, ...); the tree's own fields are plain, not volatile.
 *   To read while the tree is being edited, take a PieceTreeSnapshot (createSnapshot, itself
 *   a read): it copies what may still change and never looks at the tree again.
 */

// Upper bound for the size of a buffer: createNewPieces cuts inserted text into chunks of this
// size, PieceTreeTextBufferBuilder.acceptChunk does the same with the initial text, and
// normalizeEOL re-chunks around it. Keeps substring / getNearestChunk on one buffer cheap.
internal const val AverageBufferSize: Int = 65535

/**
 * Offsets where each line of [str] begins. Element 0 is always 0, and "\r\n" counts as ONE
 * line break (its line start is after the "\n"), as do a lone "\r" and a lone "\n".
 * "abc\r\ndef\nx" -> [0, 5, 9]
 */
internal fun createLineStartsFast(str: CharSequence): IntList {
    val r = IntList(16)
    r.add(0)
    val len = str.length

    var i: Int = 0
    while (i < len) {
        val chr = str[i].code

        if (chr == CharCode.CarriageReturn) {
            if (i + 1 < len && str[i + 1].code == CharCode.LineFeed) {
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

/** Same as [createLineStartsFast], also counting each line break kind for EOL detection. */
internal fun createLineStarts(str: String): LineStarts {
    var cr: Int = 0
    var lf: Int = 0
    var crlf: Int = 0
    var isBasicASCII = true
    val r = IntList(16)
    r.add(0)

    var i: Int = 0
    val len: Int = str.length
    while (i < len) {
        val chr = str[i].code
        if (chr == CharCode.CarriageReturn) {
            if (i + 1 < len && str[i + 1].code == CharCode.LineFeed) {
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

/**
 * Result of locating a document position in the tree: the piece [node] that holds it, the
 * [remainder] (offset inside that piece) and the document offset where the piece begins.
 */
internal data class NodePosition(
    val node: TreeNode, // Piece Index
    val remainder: Int, // remainder in current piece
    val nodeStartOffset: Int // node start offset in document
)

// Lookups that find nothing (empty document, offset / line / column past the end) return null,
// so the compiler makes every caller decide what "not found" means. This replaced a shared
// NPOS object whose node linked to itself: forgetting to check for it walked that node forever.

/**
 * A position inside one buffer.
 *
 * Immutable on purpose: pieces share cursor instances (a new piece starts at the very object
 * `_lastChangeBufferPos` points to, the right half of a split keeps the original `end`, ...).
 * The TypeScript original always creates a new object literal instead of assigning fields, so
 * mutating a cursor in place silently moves the boundary of every other piece holding it.
 */
internal data class BufferCursor(
    // Line number in current buffer line >= 0
    val line: Int,
    // Column number in current buffer column >= 0
    val column: Int
)

/**
 * An immutable slice of `_buffers[bufferIndex]`, from [start] (inclusive) to [end] (exclusive).
 * [lineFeedCnt] and [length] are cached so that the tree metadata can be maintained without
 * touching the text. Pieces are replaced, never edited, when a node changes.
 */
internal data class Piece(
    val bufferIndex: Int,
    val start: BufferCursor,
    val end: BufferCursor,
    val lineFeedCnt: Int,
    val length: Int
)

internal data class LineStarts(
    val lineStarts: IntList,
    val cr: Int,
    val lf: Int,
    val crlf: Int,
    val isBasicASCII: Boolean
)

/**
 * A text buffer together with its line starts. The two must always change together; every
 * append to the change buffer updates `lineStarts` in the same operation.
 */
internal data class TextBuffer(
    var buffer: StringBuilder,
    var lineStarts: IntList
) {
    // every write to a buffer goes through these two, so a derived cache can be added here later
    fun append(text: CharSequence) {
        this.buffer.append(text)
    }

    fun append(char: Char) {
        this.buffer.append(char)
    }
}

/**
 * A remembered node position. [nodeStartLineNumber] is 0 when the entry was created by an
 * offset lookup (nodeAt), in which case it is only usable for offset queries (get), not for
 * line queries (get2).
 */
internal data class CacheEntry(
    var node: TreeNode,
    var nodeStartOffset: Int,
    var nodeStartLineNumber: Int
)

// Immutable: _lastVisitedLine is shared by concurrent readers and only ever replaced as a whole
internal data class VisitedLine(
    val lineNumber: Int,
    val value: String
)

/**
 * A tiny most-recently-used cache of node positions, so that sequential access (typing at the
 * same place, rendering consecutive lines) does not walk the tree from the root every time.
 * The tree uses a limit of 1. Entries become stale when text before them changes; validate()
 * drops entries whose node was deleted (parent === DETACHED after detach) or that start at or
 * after the changed offset.
 */
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
            // removeAt(0), not removeFirst(): that one may resolve to Java 21's
            // List.removeFirst and crash below API 35
            this.cache.removeAt(0)
        }
        this.cache.add(nodePosition)
    }

    @Synchronized
    fun validate(offset: Int) {
        val iter = this.cache.iterator()
        while (iter.hasNext()) {
            val nodePos = iter.next()
            if (nodePos.node.parent === DETACHED || nodePos.nodeStartOffset >= offset) {
                iter.remove()
            }
        }
    }
}

// #region primary class
// Piece Tree API
internal class PieceTreeBase {
    private var _lineCnt: Int = 0
    private var _length: Int = 0
    // Bumped by every change of the content (insert, delete, setEOL). Not reset by create(), so
    // compact() and setEOL() keep counting up; snapshots record it.
    private var _versionId: Int = 0
    private var _EOLLength: Int = 0
    // True when every line break in the document is _EOL. Several read paths take a shortcut
    // based on it (cut _EOLLength chars instead of scanning for \r / \n), so it must only be
    // true when that really holds. Any insert with eolNormalized = false clears it until the
    // next setEOL() rebuilds the tree.
    private var _EOLNormalized: Boolean = true
    private lateinit var _EOL: String
    // End of the text most recently appended to the change buffer (_buffers[0]). If the piece
    // being typed into ends exactly here, new text can simply be appended to that buffer and
    // the piece extended (appendToNode), which is the fast path for normal typing.
    private lateinit var _lastChangeBufferPos: BufferCursor
    // One-line cache for getLineContent(); reset by every insert/delete.
    //
    // A VisitedLine that has been stored here is never changed again: a new line is
    // cached by storing a new object. A reader therefore always sees a line number
    // together with the text that belongs to it, also when another thread is reading
    // other lines at the same moment. (It used to be updated in place, number first
    // and text later, and a second reader could pick up the number of one line with
    // the text of another.)
    @Volatile
    private lateinit var _lastVisitedLine: VisitedLine
    private lateinit var _searchCache: PieceTreeSearchCache
    private lateinit var _buffers: MutableList<TextBuffer>

    // This tree's sentinel ("nil"): every leaf and the root's parent. Owned by the tree because
    // rbDelete writes to it; with one shared sentinel, edits of two different trees on two
    // threads corrupted each other. Kept for the lifetime of the tree, create() resets it.
    internal val sentinel: TreeNode = newSentinel()

    // the root node of red-black tree
    public lateinit var root: TreeNode

    
    constructor(
        chunks: MutableList<TextBuffer>,
        strEOL: String,
        eolNormalized: Boolean
    ) {
        this.create(chunks, strEOL, eolNormalized)
    }

    private fun create(chunks: List<TextBuffer>, strEOL: String, eolNormalized: Boolean) {
        restoreSentinel(this.sentinel)
        this.root = this.sentinel
        this._buffers = mutableListOf(TextBuffer(StringBuilder(), IntList.of(0)))
        this._lineCnt = 1
        this._length = 0
        this._EOL = strEOL
        this._EOLLength = strEOL.length
        this._EOLNormalized = eolNormalized
        this._lastChangeBufferPos = BufferCursor(0, 0)
        // no previous node yet: rbInsertRight ignores its node argument while the tree is empty
        var lastNode: TreeNode = sentinel
        for (i in 0..chunks.size - 1) {
            if (chunks[i].buffer.length > 0) {
                if (chunks[i].lineStarts.size == 0) {
                    chunks[i].lineStarts = createLineStartsFast(chunks[i].buffer)
                }
                val piece = Piece(
                    // the index this chunk gets below. `i + 1` (as in VS Code) is only right when
                    // no empty chunk was skipped before it
                    this._buffers.size,
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

    /**
     * Rebuilds the whole tree with every line break replaced by [strEOL]. The text is re-chunked
     * into buffers of roughly [min, max) chars and the change buffer starts empty again, so this
     * is also the only operation that releases text deleted from the change buffer. O(n).
     */
    private fun normalizeEOL(strEOL: String) {
        val min: Int = AverageBufferSize - AverageBufferSize / 3
        val max = min * 2

        val tempChunk = StringBuilder()
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
            chunks.add(TextBuffer(StringBuilder(text), createLineStartsFast(text)))
            tempChunk.replace(0, tempChunk.length, str)
            tempChunkLen = len
            return@iterate true
        }

        if (tempChunkLen > 0) {
            val text = tempChunk.toString().replace(Strings.newLine, strEOL)
            chunks.add(TextBuffer(StringBuilder(text), createLineStartsFast(text)))
        }

        this.create(chunks, strEOL, true)
    }

    // #region Buffer API
    fun getEOL(): String {
        return this._EOL
    }

    /**
     * Rebuilds the tree from its current content, keeping the text, the EOL and the
     * EOL-normalized state exactly as they are (setEOL, the other rebuild, rewrites line breaks).
     *
     * Afterwards every buffer is a fresh chunk of at most AverageBufferSize chars with one piece,
     * and the change buffer is empty: deleted text that it still held and the fragmentation many
     * edits leave behind are gone. Offsets, positions and line numbers do not change, so undo
     * data (texts and offsets, never references into buffers) stays valid.
     *
     * O(n) time; memory peaks at the old storage plus one copy of the document. This is an edit:
     * same threading rules as insert / delete.
     */
    fun compact() {
        val chunks = mutableListOf<TextBuffer>()
        val pending = StringBuilder()

        this.iterate(this.root) { node ->
            if (node !== sentinel) {
                pending.append(this.getNodeContent(node))
                while (pending.length > AverageBufferSize) {
                    // never end a chunk between "\r" and "\n" or inside a surrogate pair; the
                    // char after the cut is already in `pending`, so holding one back is enough
                    var cut = AverageBufferSize
                    val last = pending[cut - 1].code
                    if (last == CharCode.CarriageReturn || (last >= 0xD800 && last <= 0xDBFF)) {
                        cut--
                    }
                    val text = pending.substring(0, cut)
                    chunks.add(TextBuffer(StringBuilder(text), createLineStartsFast(text)))
                    pending.delete(0, cut)
                }
            }
            true
        }
        if (pending.length > 0) {
            val text = pending.toString()
            chunks.add(TextBuffer(StringBuilder(text), createLineStartsFast(text)))
        }

        this.create(chunks, this._EOL, this._EOLNormalized)
    }

    /** Memory figures, to decide when compact() is worth it. O(number of pieces). */
    fun getStorageStats(): StorageStats {
        var pieceCount = 0
        this.iterate(this.root) { node ->
            if (node !== sentinel) {
                pieceCount++
            }
            true
        }
        var storedLength = 0L
        for (buffer in this._buffers) {
            storedLength += buffer.buffer.length
        }
        return StorageStats(
            length = this._length,
            storedLength = storedLength,
            changeBufferLength = this._buffers[0].buffer.length,
            pieceCount = pieceCount
        )
    }

    fun setEOL(newEOL: String) {
        this._versionId++
        this._EOL = newEOL
        this._EOLLength = this._EOL.length
        this.normalizeEOL(newEOL)
    }

    fun getVersionId() = this._versionId

    /**
     * An immutable copy of the current content, see [PieceTreeSnapshot]. A read: same threading
     * rules as getLineContent. O(number of pieces) plus a copy of the change buffer text that
     * is still part of the document.
     *
     * Original chunks (_buffers[1..n]) are referenced, not copied: once the tree holds a chunk
     * nobody writes to it again (createNewPieces, compact and normalizeEOL only ever add new
     * ones). The change buffer is appended to by every edit and its line starts lose their last
     * entry when a "\r\n" is joined, so its text is copied, piece by piece.
     */
    fun createSnapshot(BOM: String): PieceTreeSnapshot {
        val changeBuffer = this._buffers[0].buffer
        val typed = StringBuilder()
        // null: the piece lies in `typed`, filled in once `typed` is complete
        val sources = ArrayList<CharSequence?>()
        val sourceStarts = IntList()
        val pieceStarts = IntList()
        var length = 0

        this.iterate(this.root) { node ->
            val piece = node.piece
            if (node !== sentinel && piece.length > 0) {
                val start = this.offsetInBuffer(piece.bufferIndex, piece.start)
                pieceStarts.add(length)
                if (piece.bufferIndex == 0) {
                    sources.add(null)
                    sourceStarts.add(typed.length)
                    typed.append(changeBuffer, start, start + piece.length)
                } else {
                    sources.add(this._buffers[piece.bufferIndex].buffer)
                    sourceStarts.add(start)
                }
                length += piece.length
            }
            true
        }
        pieceStarts.add(length)

        val typedText: CharSequence = typed.toString()
        return PieceTreeSnapshot(
            Array(sources.size) { sources[it] ?: typedText },
            sourceStarts.toIntArray(),
            pieceStarts.toIntArray(),
            this._versionId,
            this._lineCnt,
            this._EOL,
            BOM
        )
    }

    /** The whole content, one piece at a time and in order, without copying it as a whole. */
    fun readPiecesContent(callback: (String) -> Unit) {
        this.iterate(this.root) { node ->
            if (node !== sentinel && node.piece.length > 0) {
                callback(this.getNodeContent(node))
            }
            true
        }
    }

    // #region slices

    internal fun isEOLNormalized() = this._EOLNormalized

    /**
     * The content of [range] (valid: inside the document, start <= end) as a TextSlice. A read.
     *
     * Content shorter than [textThreshold] is copied into a String. Longer content is described
     * by (buffer, absolute start, length) runs of the pieces it covers: absolute offsets, not
     * BufferCursors, because the change buffer's lineStarts lose their last entry when a "\r\n"
     * is joined, and the buffer objects themselves, not indices, because compact() and setEOL()
     * replace the buffer list. Stored text is never rewritten, so the runs stay valid.
     *
     * The line shape (eolCount, first / last line length) is taken from the document positions
     * of [range], which costs no scan of the content.
     */
    fun exportSlice(
        range: Range,
        textThreshold: Int,
        mightContainRTL: Boolean,
        mightContainUnusualLineTerminators: Boolean,
        mightContainNonBasicASCII: Boolean
    ): TextSlice {
        val start = this.getOffsetAt(range.startLine, range.startColumn)
        val end = this.getOffsetAt(range.endLine, range.endColumn)
        val length = end - start
        val eolCount = range.endLine - range.startLine
        val firstLineLength =
            if (eolCount == 0) length else this.getLineLength(range.startLine) - (range.startColumn - 1)
        val lastLineLength = if (eolCount == 0) length else range.endColumn - 1

        var text: String? = null
        val segments = ArrayList<SliceSegment>()
        if (length == 0 || length < textThreshold) {
            text = this.getValueInRange(range)
        } else {
            // start < end <= length, so the start is always found
            val position = this.nodeAt(start)!!
            var node = position.node
            var nodeStart = position.nodeStartOffset
            while (node !== sentinel && nodeStart < end) {
                val piece = node.piece
                val from = Math.max(start, nodeStart)
                val to = Math.min(end, nodeStart + piece.length)
                if (to > from) {
                    val buffer = this._buffers[piece.bufferIndex]
                    val bufferStart = this.offsetInBuffer(piece.bufferIndex, piece.start) + (from - nodeStart)
                    val last = if (segments.isEmpty()) null else segments[segments.size - 1]
                    if (last != null && last.buffer === buffer && last.start + last.length == bufferStart) {
                        // contiguous in the same buffer, e.g. text typed in one go
                        segments[segments.size - 1] = SliceSegment(buffer, last.start, last.length + (to - from))
                    } else {
                        segments.add(SliceSegment(buffer, bufferStart, to - from))
                    }
                }
                nodeStart += piece.length
                node = node.next(sentinel)
            }
        }

        return TextSlice(
            this,
            text,
            segments.toTypedArray(),
            this._EOL,
            this._EOLNormalized,
            mightContainRTL,
            mightContainUnusualLineTerminators,
            mightContainNonBasicASCII,
            length,
            eolCount,
            firstLineLength,
            lastLineLength
        )
    }

    /**
     * Inserts the content of [slice] at a 0-based document offset, without copying it when it
     * is held as references: every run becomes a piece of the buffer it lies in. A buffer that
     * is no longer in _buffers (replaced by compact() or setEOL()) is taken back in; nothing
     * writes to such a buffer any more. A slice in text form goes through insert().
     *
     * The caller (PieceTreeTextBuffer.applySliceEdits) has checked that the content can go in
     * unchanged, see TextSlice.canInsertAsIs; [eolNormalized] has the meaning it has for insert.
     * A "\r" | "\n" pair formed with the neighbouring pieces is repaired afterwards, the way
     * delete() does it (validateCRLF*).
     */
    fun insertSlice(offset: Int, slice: TextSlice, eolNormalized: Boolean) {
        val text = slice.text
        if (text != null) {
            this.insert(offset, text, eolNormalized)
            return
        }
        if (slice.length == 0) {
            return
        }
        require(slice.owner === this) { "the slice refers to the storage of another buffer" }
        if (offset < 0 || offset > this._length) {
            throw IndexOutOfBoundsException("offset: $offset, length: ${this._length}")
        }

        this._versionId++
        this._EOLNormalized = this._EOLNormalized && eolNormalized
        this._lastVisitedLine = VisitedLine(0, "")

        // identity, not equals: TextBuffer is a data class
        val indices = java.util.IdentityHashMap<TextBuffer, Int>()
        for (i in 0 until this._buffers.size) {
            indices[this._buffers[i]] = i
        }
        val pieces = ArrayList<Piece>(slice.segments.size)
        for (segment in slice.segments) {
            val index = indices.getOrPut(segment.buffer) {
                this._buffers.add(segment.buffer)
                this._buffers.size - 1
            }
            val lineStarts = segment.buffer.lineStarts
            val start = this.cursorAt(lineStarts, segment.start)
            val end = this.cursorAt(lineStarts, segment.start + segment.length)
            pieces.add(Piece(index, start, end, this.getLineFeedCnt(index, start, end), segment.length))
        }

        val first: TreeNode
        var last: TreeNode
        if (this.root === sentinel) {
            // the tree is empty: rbInsertLeft ignores its node argument
            first = this.rbInsertLeft(sentinel, pieces[0])
            last = first
            for (k in 1 until pieces.size) {
                last = this.rbInsertRight(last, pieces[k])
            }
        } else {
            // the offset was validated above and the tree is not empty, so it is always found
            val (node, remainder, _) = this.nodeAt(offset)!!
            if (remainder == 0) {
                // in front of node
                last = this.rbInsertLeft(node, pieces[pieces.size - 1])
                var tmpNode = last
                for (k in pieces.size - 2 downTo 0) {
                    tmpNode = this.rbInsertLeft(tmpNode, pieces[k])
                }
                first = tmpNode
            } else {
                if (remainder < node.piece.length) {
                    // inside node: node keeps the part before the offset, a new node the rest
                    val piece = node.piece
                    val splitPos = this.positionInBuffer(node, remainder)
                    val rightPiece = Piece(
                        piece.bufferIndex,
                        splitPos,
                        piece.end,
                        this.getLineFeedCnt(piece.bufferIndex, splitPos, piece.end),
                        this.offsetInBuffer(piece.bufferIndex, piece.end) -
                            this.offsetInBuffer(piece.bufferIndex, splitPos)
                    )
                    this.deleteNodeTail(node, splitPos)
                    this.rbInsertRight(node, rightPiece)
                }
                // behind node (rbInsertRight always makes the new node node's direct successor)
                first = this.rbInsertRight(node, pieces[0])
                last = first
                for (k in 1 until pieces.size) {
                    last = this.rbInsertRight(last, pieces[k])
                }
            }
        }

        this.validateCRLFWithPrevNode(first)
        // fixCRLF may have removed `first`; when it was the only new node, `last` is gone too
        if (last.parent !== DETACHED) {
            this.validateCRLFWithNextNode(last)
        }
        this._searchCache.validate(offset)
        this.computeBufferMetadata()
    }

    // The cursor of an absolute offset in a buffer: the last line starting at or before it.
    private fun cursorAt(lineStarts: IntList, offset: Int): BufferCursor {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return BufferCursor(low, offset - lineStarts[low])
    }

    // #endregion

    fun equal(other: PieceTreeBase): Boolean {         
        if (this.getLength() != other.getLength()) {
            return false
        }
        
        if (this.getLineCount() != other.getLineCount()) {
            return false
        }
               
        var offset: Int = 0
        val result = this.iterate(this.root) {
            if (it === sentinel) {
                return@iterate true
            }
            val str = this.getNodeContent(it)
            val len = str.length
            // both trees have the same length, so these offsets exist in `other` as well
            val startPosition = other.nodeAt(offset) ?: return@iterate false
            val endPosition = other.nodeAt(offset + len)
            val value = other.getValueInRange2(startPosition, endPosition)            
            offset += len
            return@iterate str == value
        }

        return result
    }

    /**
     * Converts a 1-based (line, column) to a 0-based document offset.
     *
     * Walks down the tree using lf_left: a node whose left subtree already holds enough line
     * feeds means the line starts on the left; otherwise the line starts inside this node or
     * on the right. `line - x.lf_left - 2` is the 0-based index (inside this piece) of the line
     * break that ENDS the previous line, -1 meaning the line starts at the piece start.
     */
    fun getOffsetAt(lineNumber: Int, column: Int): Int {
        var leftLen = 0 // inorder
        var line = lineNumber

        var x = this.root

        while (x !== sentinel) {
            if (x.left !== sentinel && x.lf_left + 1 >= line) {
                x = x.left
            } else if (x.lf_left + x.piece.lineFeedCnt + 1 >= line) {
                leftLen += x.size_left
                // lineNumber >= 2
                val accumualtedValInCurrentIndex = this.getAccumulatedValue(x, line - x.lf_left - 2)
                leftLen += accumualtedValInCurrentIndex + column - 1
                // a column past the end of the line must not produce an offset past the
                // end of the document, callers would turn it into an invalid edit
                return Math.min(Math.max(leftLen, 0), this._length)
            } else {
                line -= x.lf_left + x.piece.lineFeedCnt
                leftLen += x.size_left + x.piece.length
                x = x.right
            }
        }

        return leftLen
    }

    /**
     * Converts a 0-based document offset to a 1-based (line, column). The offset is clamped to
     * the document. When the offset lands right at the start of a line inside a piece
     * (index == 0 from getIndexOf), the column is computed against the line start offset,
     * because the line may have begun in an earlier piece.
     */
    fun getPositionAt(pos: Int): Position {
        var offset: Int = Math.min(Math.max(0, pos), this._length)

        var x = this.root
        var lfCnt = 0
        val originalOffset = offset

        while (x !== sentinel) {
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

                if (x.right === sentinel) {
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

        // a start past the end of the document: there is nothing to read
        val startPosition = this.nodeAt2(range.startLine, range.startColumn) ?: return ""
        // an end past the end of the document: read to the end
        val endPosition = this.nodeAt2(range.endLine, range.endColumn)

        val value = this.getValueInRange2(startPosition, endPosition)
        // `!=` compares the values, like JavaScript's `!==` on strings. Kotlin's `!==` would
        // compare references and copy needlessly for an equal but distinct string.
        if (strEOL != null && (strEOL != this._EOL || !this._EOLNormalized)) {
            return value.replace(Strings.newLine, strEOL)
        }
        return value
    }

    // Text from [startPosition] to [endPosition]; a null end means "to the end of the document"
    fun getValueInRange2(startPosition: NodePosition, endPosition: NodePosition?): String {
        if (endPosition != null && startPosition.node === endPosition.node) {
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
        val ret = StringBuilder(
            buffer.substring(
                startOffset + startPosition.remainder,
                startOffset + x.piece.length
            )
        )

        x = x.next(sentinel)
        while (x !== sentinel) {
            buffer = this._buffers[x.piece.bufferIndex].buffer
            startOffset = this.offsetInBuffer(x.piece.bufferIndex, x.piece.start)

            if (endPosition != null && x === endPosition.node) {
                ret.append(buffer.substring(startOffset, startOffset + endPosition.remainder))
                break
            } else {
                ret.append(buffer.substring(startOffset, startOffset + x.piece.length))
            }

            x = x.next(sentinel)
        }

        return ret.toString()
    }

    /**
     * All lines without their line breaks, in one in-order pass over the pieces.
     *
     * The tricky part is a piece that ends with "\r": the line break might really be "\r\n"
     * with the "\n" at the head of the NEXT piece (possible when the document is not
     * EOL-normalized). The line is therefore held back (danglingCR) and only emitted once the
     * next piece shows whether a "\n" must be swallowed.
     */
    fun getLinesContent(): List<String> {
        val lines = mutableListOf<String>()
        val currentLine = StringBuilder()
        var danglingCR = false

        this.iterate(this.root) {
            if (it === sentinel) {
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
                if (buffer[pieceStartOffset].code == CharCode.LineFeed) {
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
                    buffer[pieceStartOffset + pieceLength - 1].code ==
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
                // every one of these lines starts fresh, the content must replace what is
                // currently buffered instead of being appended to it
                currentLine.setLength(0)
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
                buffer[lineStarts[pieceEndLine] + piece.end.column - 1].code == CharCode.CarriageReturn
            ) {
                danglingCR = true
                if (piece.end.column == 0) {
                    // The last line ended with a \r, let's undo the push, it will be pushed by next
                    // iteration
                    lines.removeAt(lines.size - 1)
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

    /**
     * Content of a 1-based line without its line break. When the document is EOL-normalized the
     * break is known to be exactly _EOLLength chars and is cut by length; otherwise the raw line
     * is cleaned with a regex. The last line has no line break at all.
     */
    fun getLineContent(lineNumber: Int): String {
        // read once: the number and the text of one and the same cached line
        val lastVisited = this._lastVisitedLine
        if (lastVisited.lineNumber == lineNumber) {
            return lastVisited.value
        }

        val value = if (lineNumber == this._lineCnt) {
            this.getLineRawContent(lineNumber)
        } else if (this._EOLNormalized) {
            this.getLineRawContent(lineNumber, this._EOLLength)
        } else {
            this.getLineRawContent(lineNumber).replace(Strings.newLine, "")
        }

        // a new object, complete before anyone can see it; see _lastVisitedLine
        this._lastVisitedLine = VisitedLine(lineNumber, value)
        return value
    }
    
    fun getLineContentWithEOL(lineNumber: Int): String {
        return getLineContent(lineNumber) + this._EOL
    }

    // Returns the UTF-16 code unit at a node position, 0 when there is none (no position: empty
    // document or a line / column past the end; or the very end of the document).
    private fun _getCharCode(nodePos: NodePosition?): Int {
        if (nodePos == null) {
            return 0
        }
        if (nodePos.remainder == nodePos.node.piece.length) {
            // the char we want to fetch is at the head of next node.
            val matchingNode = nodePos.node.next(sentinel)
            // next() returns sentinel at the end of the document
            if (matchingNode === sentinel) {
                return 0
            }

            val buffer = this._buffers[matchingNode.piece.bufferIndex]
            val startOffset =
                this.offsetInBuffer(matchingNode.piece.bufferIndex, matchingNode.piece.start)
            return buffer.buffer[startOffset].code
        } else {
            val buffer = this._buffers[nodePos.node.piece.bufferIndex]
            val startOffset =
                this.offsetInBuffer(nodePos.node.piece.bufferIndex, nodePos.node.piece.start)
            val targetOffset = startOffset + nodePos.remainder

            return buffer.buffer[targetOffset].code
        }
    }

    fun getLineCharCode(lineNumber: Int, index: Int): Int {
        val nodePos = this.nodeAt2(lineNumber, index + 1)
        return this._getCharCode(nodePos)
    }

    // Length of a line without its line break. On an EOL-normalized document every break is
    // _EOLLength chars, so the length follows from two offset lookups. Otherwise breaks can
    // differ in length ("\n" lines in a "\r\n" document), so the line itself is measured.
    fun getLineLength(lineNumber: Int): Int {
        if (lineNumber == this.getLineCount()) {
            val startOffset = this.getOffsetAt(lineNumber, 1)
            return this.getLength() - startOffset
        }
        if (!this._EOLNormalized) {
            return this.getLineContent(lineNumber).length
        }
        // return the length of line text 
        return this.getOffsetAt(lineNumber + 1, 1) -
            this.getOffsetAt(lineNumber, 1) - 
            this._EOLLength
    }

    // UTF-16 code unit at a document offset; offset == length (and an empty document) gives 0
    fun getCharCode(offset: Int): Int {
        if (offset < 0 || offset > this._length) {
            throw IndexOutOfBoundsException("offset: $offset, length: ${this._length}")
        }
        val nodePos = this.nodeAt(offset)
        return this._getCharCode(nodePos)
    }
    
    /**
	 * get nearest chunk of text after `offset` in the text buffer.
	 * this method is mainly used for treesitter parsing
	 *
	 * The chunk is the rest of the piece containing `offset`, at most AverageBufferSize chars
	 * (the builder and createNewPieces never create bigger buffers). An offset at or past the
	 * end of the document gives "", the end-of-input signal incremental parsers expect.
	 *
	 * At most [maxLength] chars of it: only those are copied (a parser that reads a few
	 * chars around an edit should not have 64K copied for them).
	 */
    fun getNearestChunk(offset: Int, maxLength: Int = Int.MAX_VALUE): String {
        if (offset < 0) {
            throw IndexOutOfBoundsException("offset: $offset, length: ${this._length}")
        }
        if (offset >= this._length) {
            return ""
        }
        // offset < length here, so the document is not empty and the offset exists
        val nodePos = this.nodeAt(offset) ?: return ""
        if (nodePos.remainder == nodePos.node.piece.length) {
            // the offset is at the head of next node.
            val matchingNode = nodePos.node.next(sentinel)
            if (matchingNode === sentinel) {
                return "" // nothing to do
            }

            val buffer = this._buffers[matchingNode.piece.bufferIndex]
            val startOffset = this.offsetInBuffer(matchingNode.piece.bufferIndex, matchingNode.piece.start)
            val length = Math.min(matchingNode.piece.length, Math.max(1, maxLength))
            return buffer.buffer.substring(startOffset, startOffset + length)
        } else {
            val buffer = this._buffers[nodePos.node.piece.bufferIndex]
            val startOffset = this.offsetInBuffer(nodePos.node.piece.bufferIndex, nodePos.node.piece.start)
            val targetOffset = startOffset + nodePos.remainder
            val targetEnd = Math.min(startOffset + nodePos.node.piece.length, targetOffset + Math.max(1, maxLength))
            return buffer.buffer.substring(targetOffset, targetEnd)
        }
    }

    // #endregion
    
    /**
     * Maps a match [matchStart, matchEnd) in text read with getValueInRange(..., "\n") back to
     * a document range. [deltaOffset] is the document offset where that text starts. Every "\n"
     * of the text stands for one line break of the document; in a CRLF document that break is
     * two chars, which [lfCounter] (null for LF documents) corrects for.
     */
    private fun _getMultilineMatchRange(
        deltaOffset: Int,
        lfCounter: LineFeedCounter?,
        matchStart: Int,
        matchEnd: Int
    ): Range {
        var startOffset = deltaOffset + matchStart
        var endOffset = deltaOffset + matchEnd
        if (lfCounter != null) {
            // add as many \r as there were \n before each end of the match
            startOffset += lfCounter.findLineFeedCountBeforeOffset(matchStart)
            endOffset += lfCounter.findLineFeedCountBeforeOffset(matchEnd)
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

    /**
     * Regex search over the lines of [searchRange] joined with "\n", so a match may span lines
     * and `\n` matches every line break kind (the caller adds MULTILINE: `^` / `$` match at
     * line starts / ends).
     *
     * The text read is the WHOLE first and last line, and the regex runs on it through a Matcher
     * whose region is the search range, with transparent, non-anchoring bounds, exactly like
     * findMatchesLineByLine: the start / end of the search range are not a line start / end
     * for `^` / `$` unless they really are one, and lookarounds and `\b` see the text around
     * them. (Running the regex on the range text alone, as VS Code does, let `^` match wherever
     * the range happened to start.)
     */
    fun findMatchesByMultiline(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val result = mutableListOf<Range>()
        if (limitResultCount <= 0) {
            return result
        }

        val lastLineLength = this.getLineLength(searchRange.endLine)
        // We always execute multiline search over the lines joined with \n
        // This makes it that \n will match the EOL for both CRLF and LF models
        // We compensate for offset errors in `_getMultilineMatchRange`
        val text = this.getValueInRange(
            Range(searchRange.startLine, 1, searchRange.endLine, lastLineLength + 1),
            "\n"
        )
        val deltaOffset = this.getOffsetAt(searchRange.startLine, 1)
        val lfCounter: LineFeedCounter? =
            if (this.getEOL() == "\r\n") LineFeedCounter(text) else null

        // the search range inside `text`: it starts startColumn - 1 chars into the first line
        // and ends (last line length - (endColumn - 1)) chars before the end of the last line
        val regionStart = (searchRange.startColumn - 1).coerceIn(0, text.length)
        val regionEnd = (text.length - (lastLineLength + 1 - searchRange.endColumn))
            .coerceIn(regionStart, text.length)

        val matcher = regex.toPattern().matcher(text)
            .region(regionStart, regionEnd)
            .useTransparentBounds(true)
            .useAnchoringBounds(false)
        // Matcher.find() steps over an empty match by itself, so `x*` cannot loop forever
        while (result.size < limitResultCount && !isCancelled() && matcher.find()) {
            result.add(this._getMultilineMatchRange(deltaOffset, lfCounter, matcher.start(), matcher.end()))
        }
        return result
    }
    
    // Columns [from, to) of a line of [lineLength] chars covered by [searchRange], 0-based and
    // clamped into the line. Only the first and the last line of the range are cut.
    private fun _searchColumns(searchRange: Range, lineNumber: Int, lineLength: Int): IntArray {
        val from = if (lineNumber == searchRange.startLine) {
            (searchRange.startColumn - 1).coerceIn(0, lineLength)
        } else {
            0
        }
        val to = if (lineNumber == searchRange.endLine) {
            (searchRange.endColumn - 1).coerceIn(from, lineLength)
        } else {
            lineLength
        }
        return intArrayOf(from, to)
    }

    /**
     * Regex search one document line at a time; a match never spans a line break.
     *
     * Each line of [searchRange] is fetched with getLineContent (consecutive lines are served
     * by the search cache) and the regex runs on it through a Matcher whose region is limited to
     * the searched columns. The region bounds are transparent and non-anchoring: `^` and `$` only
     * match at the real start / end of the line, not where the search range happens to begin or
     * end, and lookarounds see the whole line.
     *
     * The VS Code original ran the regex over whole buffer strings instead. Lines of a buffer
     * are not lines of the document (a piece may start in the middle of a document line), so
     * `^` / `$` matched at wrong places or not at all, and a pattern such as `\s+` could run
     * across a line break while being reported as a single-line range.
     */
    fun findMatchesLineByLine(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val result = mutableListOf<Range>()
        if (limitResultCount <= 0) {
            return result
        }

        val pattern = regex.toPattern()
        val lastLine = Math.min(searchRange.endLine, this._lineCnt)
        var lineNumber = Math.max(searchRange.startLine, 1)
        while (lineNumber <= lastLine && result.size < limitResultCount && !isCancelled()) {
            val line = this.getLineContent(lineNumber)
            val (from, to) = this._searchColumns(searchRange, lineNumber, line.length)

            val matcher = pattern.matcher(line)
                .region(from, to)
                .useTransparentBounds(true)
                .useAnchoringBounds(false)
            // Matcher.find() steps over an empty match by itself, so `x*` cannot loop forever
            while (result.size < limitResultCount && matcher.find()) {
                result.add(Range(lineNumber, matcher.start() + 1, lineNumber, matcher.end() + 1))
            }
            lineNumber++
        }
        return result
    }

    /**
     * Plain-text, case-sensitive search (not part of the VS Code original).
     *
     * A search text without line breaks is looked for line by line, inside the columns of
     * [searchRange]. A search text with line breaks is looked for in the text of [searchRange]
     * joined with "\n"; its own line breaks are normalized the same way, so "\r\n", "\r" and
     * "\n" in it all match any line break. Hits are mapped back to ranges by
     * _getMultilineMatchRange. Matches do not overlap; an empty search text finds nothing.
     */
    fun findMatchesByWord(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val result = mutableListOf<Range>()
        if (searchText.length == 0 || limitResultCount <= 0) {
            return result
        }

        if (Strings.countEOL(searchText).eolCount == 0) {
            val lastLine = Math.min(searchRange.endLine, this._lineCnt)
            var lineNumber = Math.max(searchRange.startLine, 1)
            while (lineNumber <= lastLine && result.size < limitResultCount && !isCancelled()) {
                val line = this.getLineContent(lineNumber)
                val (from, to) = this._searchColumns(searchRange, lineNumber, line.length)

                var index = line.indexOf(searchText, from)
                while (index != -1 && index + searchText.length <= to &&
                    result.size < limitResultCount
                ) {
                    result.add(Range(lineNumber, index + 1, lineNumber, index + 1 + searchText.length))
                    index = line.indexOf(searchText, index + searchText.length)
                }
                lineNumber++
            }
            return result
        }

        val needle = searchText.replace(Strings.newLine, "\n")
        val start = searchRange.getStartPosition()
        val deltaOffset = this.getOffsetAt(start.lineNumber, start.column)
        val text = this.getValueInRange(searchRange, "\n")
        val lfCounter: LineFeedCounter? =
            if (this.getEOL() == "\r\n") LineFeedCounter(text) else null

        var index = text.indexOf(needle)
        while (index != -1 && result.size < limitResultCount && !isCancelled()) {
            result.add(this._getMultilineMatchRange(deltaOffset, lfCounter, index, index + needle.length))
            index = text.indexOf(needle, index + needle.length)
        }
        return result
    }

    // #endregion
    
    // #region Piece Table
    
    /**
     * Inserts [text] at a 0-based document offset.
     *
     * After locating the piece that holds [offset], one of four cases applies:
     *  1. the piece is the one being typed into (it ends at _lastChangeBufferPos, in the change
     *     buffer) and the offset is its end: append to the change buffer and extend the piece;
     *  2. offset is at the piece start: insertContentToNodeLeft;
     *  3. offset is inside the piece: split it into left / new / right pieces;
     *  4. offset is at the piece end: insertContentToNodeRight.
     * Each case also repairs a "\r" | "\n" pair that the insertion would split or create.
     *
     * [eolNormalized] tells whether [text] only uses the document EOL; passing false (the
     * default) permanently turns off the normalized fast paths.
     */
    fun insert(offset: Int, text: String, eolNormalized: Boolean = false) {
        if (text.length == 0) {
            return // nothing to do
        }
        // reject an out of range offset up front, before anything is changed
        if (offset < 0 || offset > this._length) {
            throw IndexOutOfBoundsException("offset: $offset, length: ${this._length}")
        }

        this._versionId++
        this._EOLNormalized = this._EOLNormalized && eolNormalized
        this._lastVisitedLine = VisitedLine(0, "")
        var value = text

        if (this.root !== sentinel) {
            // the offset was validated above and the tree is not empty, so it is always found
            val (node, remainder, nodeStartOffset) = this.nodeAt(offset)!!
            val piece = node.piece
            val bufferIndex = piece.bufferIndex
            val insertPosInBuffer = this.positionInBuffer(node, remainder)
            // case 1: continue typing at the end of the last insertion
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

                // case 3, CRLF repair: if the inserted text ends with "\r" and the right half
                // starts with "\n", move that "\n" into the new text so the pair stays in one
                // piece. Symmetrically below, a "\r" at the end of the left half is moved into
                // the new text when the new text starts with "\n".
                if (this.shouldCheckCRLF() && this.endWithCR(value)) {
                    val headOfRight = this.nodeCharCodeAt(node, remainder)
                    /** \n */
                    if (headOfRight == CharCode.LineFeed) { 
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
                    /** \r */
                    if (tailOfLeft == CharCode.CarriageReturn) {
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
            // the tree is empty: rbInsertLeft ignores its node argument
            var node = this.rbInsertLeft(sentinel, pieces[0])

            for (k in 1..pieces.size - 1) {
                node = this.rbInsertRight(node, pieces[k])
            }
        }

        this.computeBufferMetadata()
    }

    /**
     * Deletes [cnt] chars starting at a 0-based document offset.
     *
     * Within a single piece: remove the whole node, cut its head, cut its tail, or split it
     * around the hole (shrinkNode). Across pieces: cut the tail of the first, the head of the
     * last and delete everything in between. Afterwards the pieces that became neighbours are
     * checked for a "\r" | "\n" pair (validateCRLF*).
     */
    fun delete(offset: Int, cnt: Int) {
        if (cnt <= 0 || this.root === sentinel) {
            return@delete
        }
        // see insert(): reject an out of range span before anything is changed
        if (offset < 0 || offset + cnt > this._length) {
            throw IndexOutOfBoundsException(
                "offset: $offset, count: $cnt, length: ${this._length}"
            )
        }

        this._versionId++
        this._lastVisitedLine = VisitedLine(0, "")

        // validated above and the tree is not empty, so both offsets are always found
        val startPosition = this.nodeAt(offset)!!
        val endPosition = this.nodeAt(offset + cnt)!!
        val startNode = startPosition.node
        val endNode = endPosition.node

        if (startNode === endNode) {
            val startSplitPosInBuffer = this.positionInBuffer(startNode, startPosition.remainder)
            val endSplitPosInBuffer = this.positionInBuffer(startNode, endPosition.remainder)

            if (startPosition.nodeStartOffset == offset) {
                if (cnt == startNode.piece.length) { // delete node
                    val next = startNode.next(sentinel)
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
        var node = startNode.next(sentinel)
        while (node !== sentinel && node !== endNode) {
            nodesToDel.add(node)
            node = node.next(sentinel)
        }

        val prev = if (startNode.piece.length == 0) startNode.prev(sentinel) else startNode
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

    /**
     * Converts an offset inside a piece ([remainder]) to a cursor in that piece's buffer, by
     * binary searching the buffer's lineStarts between the piece's start and end lines.
     */
    private fun positionInBuffer(node: TreeNode, remainder: Int): BufferCursor {
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

        return BufferCursor(mid, offset - midStart)
    }

    // Number of line breaks in the buffer range [start, end). Mostly `end.line - start.line`,
    // except when end points between the "\r" and "\n" of a CRLF pair (see below).
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

        if (buffer[previousCharOffset].code == CharCode.CarriageReturn) {
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

    /**
     * Stores [value] in the buffers and returns the pieces describing it.
     *
     * Large text (> AverageBufferSize) gets brand new buffers of at most that size, never
     * cutting between "\r" and "\n" or inside a surrogate pair. Anything else is appended to the
     * change buffer and described by one piece.
     */
    private fun createNewPieces(value: String): List<Piece> {
        var text = value
        if (text.length > AverageBufferSize) {
            // the content is large, operations like substring, charCode becomes slow
            // so here we split it into smaller chunks, just like what we did for CR/LF
            // normalization
            val newPieces = mutableListOf<Piece>()
            while (text.length > AverageBufferSize) {
                val lastChar = text[AverageBufferSize - 1].code
                var splitText: String
                if (
                    lastChar == CharCode.CarriageReturn ||
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
                this._buffers.add(TextBuffer(StringBuilder(splitText), lineStarts))
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
            this._buffers.add(TextBuffer(StringBuilder(text), lineStarts))

            return newPieces
        }

        // The '_' placeholder: if the change buffer ends with "\r" and [value] starts with
        // "\n", appending directly would put "\r\n" into the buffer while lineStarts records two
        // separate line breaks. Everything that inspects the raw characters (getLineFeedCnt,
        // startWithLF, ...) assumes "\r\n" is ONE break, so the two unrelated insertions would
        // corrupt each other's line counts. A dummy '_' keeps them apart; no piece covers it.
        var startOffset = this._buffers[0].buffer.length
        val lineStarts = createLineStartsFast(text)

        var start = this._lastChangeBufferPos
        if (
            this._buffers[0].lineStarts[this._buffers[0].lineStarts.size - 1] == startOffset &&
            startOffset != 0 &&
            this.startWithLF(text) &&
            this.endWithCR(this._buffers[0].buffer)
        ) {
            // a new cursor, never an in-place update: the old object is still the `end` of the
            // piece created by the previous edit, bumping its column would pull the '_'
            // placeholder into that piece
            this._lastChangeBufferPos = BufferCursor(
                this._lastChangeBufferPos.line,
                this._lastChangeBufferPos.column + 1
            )
            start = this._lastChangeBufferPos

            lineStarts.shiftAll(startOffset + 1)

            this._buffers[0].lineStarts.addAll(lineStarts, 1)
            this._buffers[0].append('_')
            this._buffers[0].append(text)
            startOffset += 1
        } else {
            if (startOffset != 0) {
                lineStarts.shiftAll(startOffset)
            }
            this._buffers[0].lineStarts.addAll(lineStarts, 1)
            this._buffers[0].append(text)
        }

        val endOffset = this._buffers[0].buffer.length
        val endIndex = this._buffers[0].lineStarts.size - 1
        val endColumn = endOffset - this._buffers[0].lineStarts[endIndex]
        val endPos = BufferCursor(endIndex, endColumn)
        val newPiece = Piece(
            0, // the change buffer
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

    /**
     * Content of a 1-based line INCLUDING its line break, minus the last [endOffset] chars
     * (callers pass _EOLLength to drop a normalized line break). A line can span many pieces:
     * find the piece where it starts, then append following pieces until one contains a break.
     */
    fun getLineRawContent(line: Int, endOffset: Int = 0): String {
        var x = this.root
        var lineNumber = line

        val ret = StringBuilder()
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
            while (x !== sentinel) {
                if (x.left !== sentinel && x.lf_left >= lineNumber - 1) {
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
        x = x.next(sentinel)
        while (x !== sentinel) {
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

            x = x.next(sentinel)
        }

        return ret.toString()
    }

    // Total length and line count of the document. Walking the right spine and adding
    // size_left/lf_left + the node's own piece covers every node exactly once: O(log n).
    private fun computeBufferMetadata() {
        var lfCnt = 1
        var len = 0
        var x = this.root
        while (x !== sentinel) {
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
    
    // For an offset inside a piece: [line index relative to the piece start, column]. At the
    // very end of a piece that ends between "\r" and "\n" the line count must include that
    // line break, hence the extra getLineFeedCnt check.
    // return { index: number, remainder: number }
    private fun getIndexOf(node: TreeNode, accumulatedValue: Int): IntArray {
        val piece = node.piece
        val pos = this.positionInBuffer(node, accumulatedValue)
        val lineCnt = pos.line - piece.start.line

        if (
            this.offsetInBuffer(piece.bufferIndex, piece.end) -
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

    // Length from the piece start up to and including its [index]-th line break (0-based), or
    // the whole piece length if it has fewer line breaks. index < 0 means "the piece start".
    private fun getAccumulatedValue(node: TreeNode, index: Int): Int {
        // check the index
        if (index < 0) return 0
            
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

    // deleteNodeTail / deleteNodeHead / shrinkNode replace node.piece with a narrower piece and
    // push the size / line feed delta up the tree. shrinkNode keeps the left part in place and
    // inserts the right part as a new node.
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

    /**
     * Fast path for typing: appends [text] to the change buffer and extends [node], whose piece
     * ends at the current end of that buffer. When the node ends with "\r" and [text] starts with
     * "\n" (hitCRLF), the two now form one "\r\n" in the buffer, so the line start that followed
     * the "\r" is removed from lineStarts before the new ones are added.
     */
    private fun appendToNode(node: TreeNode, text: String) {
        var value = text
        if (this.adjustCarriageReturnFromNext(value, node)) {
            value += '\n'
        }

        val hitCRLF = this.shouldCheckCRLF() && this.startWithLF(value) && this.endWithCR(node)
        val startOffset = this._buffers[0].buffer.length
        this._buffers[0].append(value)
        val lineStarts = createLineStartsFast(value)
        lineStarts.shiftAll(startOffset)
        if (hitCRLF) {
            val prevStartOffset = this._buffers[0].lineStarts[this._buffers[0].lineStarts.size - 2]
            // remove the last element
            this._buffers[0].lineStarts.removeLast()
            // _lastChangeBufferPos is already wrong. Replace it, do not mutate it: the old
            // object may also be the `start` of a piece created after it (see createNewPieces)
            this._lastChangeBufferPos = BufferCursor(
                this._lastChangeBufferPos.line - 1,
                startOffset - prevStartOffset
            )
        }

        this._buffers[0].lineStarts.addAll(lineStarts, 1)
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

    // Finds the node holding a 0-based document offset. At a boundary between two pieces either
    // neighbour may come back (remainder == length of the left one, or 0 in the right one),
    // depending on the tree shape; callers handle both. Returns null when nothing matches.
    private fun nodeAt(index: Int): NodePosition? {
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

        while (x !== sentinel) {
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
        return null // not found
    }

    // Same as nodeAt, for a 1-based (line, column). If the column runs past the end of the
    // piece where the line starts, the following pieces are scanned in order.
    private fun nodeAt2(row: Int, col: Int): NodePosition? {
        var x = this.root
        var nodeStartOffset = 0
        var lineNumber = row
        var column = col

        while (x !== sentinel) {
            if (x.left !== sentinel && x.lf_left >= lineNumber - 1) {
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
        x = x.next(sentinel)
        while (x !== sentinel) {
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

            x = x.next(sentinel)
        }

        return null // not found
    }

    // Code unit at an offset inside a piece. Only used to look for "\r" / "\n", so pieces without
    // any line break short-circuit to -1.
    private fun nodeCharCodeAt(node: TreeNode, offset: Int): Int {
        if (node.piece.lineFeedCnt < 1) {
            return -1
        }
        val buffer = this._buffers[node.piece.bufferIndex]
        val newOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start) + offset
        return buffer.buffer[newOffset].code
    }

    private fun offsetOfNode(x: TreeNode): Int {
        var node = x
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
    
    // CRLF repair is needed unless the document is known to contain only "\n" line breaks, in
    // which case no piece can end with "\r" in the first place.
    private fun shouldCheckCRLF(): Boolean {
        return !(this._EOLNormalized && this._EOL == "\n")
    }

    // param: string | TreeNode
    // note: javascript charCodeAt() returns NaN when out of bounds, kotlin throws,
    // so empty input has to be handled explicitly here
    private fun startWithLF(chr: CharSequence): Boolean {
        return chr.length > 0 && chr[0].code == CharCode.LineFeed
    }

    private fun startWithLF(node: TreeNode): Boolean {
        if (node === sentinel || node.piece.lineFeedCnt == 0) {
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

        return this._buffers[piece.bufferIndex].buffer[startOffset].code == CharCode.LineFeed
    }

    private fun endWithCR(chr: CharSequence): Boolean {
        return chr.length > 0 && chr[chr.length - 1].code == CharCode.CarriageReturn
    }

    private fun endWithCR(node: TreeNode): Boolean {
        if (node === sentinel || node.piece.lineFeedCnt == 0) {
            return false
        }
        return this.nodeCharCodeAt(node, node.piece.length - 1) == 13
    }

    private fun validateCRLFWithPrevNode(nextNode: TreeNode) {
        if (this.shouldCheckCRLF() && this.startWithLF(nextNode)) {
            val node = nextNode.prev(sentinel)
            if (this.endWithCR(node)) {
                this.fixCRLF(node, nextNode)
            }
        }
    }

    private fun validateCRLFWithNextNode(node: TreeNode) {
        if (this.shouldCheckCRLF() && this.endWithCR(node)) {
            val nextNode = node.next(sentinel)
            if (this.startWithLF(nextNode)) {
                this.fixCRLF(node, nextNode)
            }
        }
    }

    /**
     * [prev] ends with "\r" and [next] starts with "\n". Removes both characters from their
     * pieces and inserts a new piece holding "\r\n" between them, deleting a piece that became
     * empty:  [..."\r"] ["\n"...]  ->  [...] ["\r\n"] [...]
     */
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

    // Before inserting [value] after [node]: if [value] ends with "\r" and the next piece starts
    // with "\n", take that "\n" away from the next piece. Returns true when the caller has to
    // append "\n" to [value].
    private fun adjustCarriageReturnFromNext(value: String, node: TreeNode): Boolean {
        if (this.shouldCheckCRLF() && this.endWithCR(value)) {
            val nextNode = node.next(sentinel)
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
    
    // In-order traversal; stops as soon as the callback returns false. Note that the callback
    // is also invoked for sentinel leaves, callers have to skip them.
    fun iterate(node: TreeNode, callback: (TreeNode) -> Boolean): Boolean {
        if (node === sentinel) {
            return callback(node)
        }

        val leftRet = this.iterate(node.left, callback)
        if (!leftRet) {
            return leftRet
        }

        return callback(node) && this.iterate(node.right, callback)
    }

    private fun getNodeContent(node: TreeNode): String {
        if (node === sentinel) {
            return ""
        }

        val buffer = this._buffers[node.piece.bufferIndex]
        var currentContent: String
        val startOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.start)
        val endOffset = this.offsetInBuffer(node.piece.bufferIndex, node.piece.end)
        currentContent = buffer.buffer.substring(startOffset, endOffset)
        return currentContent
    }

    /**
     * ```
     *     node            node
     *     /  \              /  \
     *    a   b    <----   a    b
     *                         /
     *                        z
     * ```
     */
    private fun rbInsertRight(node: TreeNode, p: Piece): TreeNode {
        val z = TreeNode(p, NodeColor.Red)
        z.left = sentinel
        z.right = sentinel
        z.parent = sentinel
        z.size_left = 0
        z.lf_left = 0

        val x = this.root
        if (x === sentinel) {
            this.root = z
            z.color = NodeColor.Black
        } else if (node.right === sentinel) {
            node.right = z
            z.parent = node
        } else {
            val nextNode = leftest(node.right, sentinel)
            nextNode.left = z
            z.parent = nextNode
        }

        fixInsert(this, z)
        return z
    }

    /**
     * ```
     *     node            node
     *     /  \              /  \
     *    a   b     ---->   a    b
     *                       \
     *                        z
     * ```
     */
    private fun rbInsertLeft(node: TreeNode, p: Piece): TreeNode {
        val z = TreeNode(p, NodeColor.Red)
        z.left = sentinel
        z.right = sentinel
        z.parent = sentinel
        z.size_left = 0
        z.lf_left = 0

        if (this.root === sentinel) {
            this.root = z
            z.color = NodeColor.Black
        } else if (node.left === sentinel) {
            node.left = z
            z.parent = node
        } else {
            val prevNode: TreeNode = rightest(node.left, sentinel)
            prevNode.right = z
            z.parent = prevNode
        }

        fixInsert(this, z)
        return z
    }

    private fun getContentOfSubTree(node: TreeNode): String {
        val buf = StringBuilder(this.getLength())
        this.iterate(node) {
            buf.append(this.getNodeContent(it))
            return@iterate true
        }

        return buf.toString()
    }

    // #endregion
}

// #endregion class

