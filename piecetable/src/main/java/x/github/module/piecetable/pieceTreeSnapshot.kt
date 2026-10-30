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
 * src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeBase.ts (PieceTreeSnapshot), and changed since.
 */

package x.github.module.piecetable

/**
 * The content of a document at one version, readable from any thread while the buffer keeps
 * being edited.
 *
 * Created with [PieceTreeTextBuffer.createSnapshot]. Creating one is a read of the buffer (same
 * rules as any other read: not at the same time as an edit of that buffer) and costs
 * O(number of pieces) plus a copy of the typed text that is still in the document. After that
 * the snapshot never touches the buffer again: any number of threads may read it at any time,
 * also while the buffer is being edited, compacted or switched to another EOL, without a lock.
 *
 * How it stays independent of the buffer: every piece was resolved, when the snapshot was
 * created, to (text, start offset, length). Pieces of the original chunks (the file as loaded,
 * text inserted in 64K+ blocks, the chunks compact() / setEOL() create) point at those chunks
 * directly, because a chunk is never written once the tree holds it. Pieces of the change
 * buffer, which keeps growing while the user types, were copied. Nothing is looked up through
 * the tree, its buffer list or its line starts later on, so no later edit can change what the
 * snapshot reads.
 *
 * Memory: the snapshot keeps the original chunks it points at alive, also after compact() or
 * setEOL() replaced them in the buffer. Drop snapshots once they are processed; usually one or
 * two exist at a time.
 *
 * Offsets are document offsets (0-based UTF-16 code units) without the BOM. Random access is
 * O(log(number of pieces)): fine for an incremental parser that reads chunk by chunk
 * ([getNearestChunk]), slow for a regular expression scanning [get] char by char.
 *
 * All fields are final, so a snapshot handed to another thread is completely visible there;
 * the usual ways of passing it on (an executor, a channel, a lock, a volatile field) all work.
 * The only mutable part is the read position of [read], see there.
 */
class PieceTreeSnapshot internal constructor(
    // per piece, in document order: the text it lies in and where it starts in that text
    private val sources: Array<CharSequence>,
    private val sourceStarts: IntArray,
    // document offset where each piece starts, plus the document length as the last element
    private val pieceStarts: IntArray,
    /** [PieceTreeTextBuffer.getVersionId] at the time the snapshot was created. */
    val versionId: Int,
    /** Number of lines of the document, at least 1. */
    val lineCount: Int,
    /** The EOL of the buffer, "\n" or "\r\n". */
    val eol: String,
    /** What [read] returns in front of the content: the buffer's BOM if it was preserved, else "". */
    val bom: String
) : CharSequence {

    override val length: Int = pieceStarts[pieceStarts.size - 1]

    // the piece holding [offset], 0 <= offset < length
    private fun pieceAt(offset: Int): Int {
        val index = java.util.Arrays.binarySearch(pieceStarts, 0, sources.size, offset)
        // not found: -(insertion point) - 1, the piece is the one before the insertion point
        return if (index >= 0) index else -index - 2
    }

    // text of the piece [piece], from document offset [from] (inside it) to its end
    private fun pieceText(piece: Int, from: Int = pieceStarts[piece]): String {
        val start = sourceStarts[piece] + from - pieceStarts[piece]
        val end = sourceStarts[piece] + pieceStarts[piece + 1] - pieceStarts[piece]
        return sources[piece].substring(start, end)
    }

    /** UTF-16 code unit at [index]; O(log n), see the class comment. */
    override operator fun get(index: Int): Char {
        if (index < 0 || index >= this.length) {
            throw IndexOutOfBoundsException("index: $index, length: ${this.length}")
        }
        val piece = this.pieceAt(index)
        return this.sources[piece][this.sourceStarts[piece] + index - this.pieceStarts[piece]]
    }

    /** The text from [startIndex] (inclusive) to [endIndex] (exclusive). */
    fun substring(startIndex: Int, endIndex: Int = this.length): String {
        if (startIndex < 0 || endIndex > this.length || startIndex > endIndex) {
            throw IndexOutOfBoundsException(
                "start: $startIndex, end: $endIndex, length: ${this.length}"
            )
        }
        if (startIndex == endIndex) {
            return ""
        }
        val result = StringBuilder(endIndex - startIndex)
        var piece = this.pieceAt(startIndex)
        var offset = startIndex
        while (offset < endIndex) {
            val to = Math.min(this.pieceStarts[piece + 1], endIndex)
            val start = this.sourceStarts[piece] + offset - this.pieceStarts[piece]
            result.append(this.sources[piece], start, start + (to - offset))
            offset = to
            piece++
        }
        return result.toString()
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        this.substring(startIndex, endIndex)

    /**
     * The rest of the piece that holds [offset]: a non-empty chunk of at most about 64K chars,
     * or "" at or past the end of the document (the end-of-input signal incremental parsers
     * such as tree-sitter expect). Same contract as [PieceTreeTextBuffer.getNearestChunk].
     */
    fun getNearestChunk(offset: Int, maxLength: Int = Int.MAX_VALUE): String {
        if (offset < 0) {
            throw IndexOutOfBoundsException("offset: $offset, length: ${this.length}")
        }
        if (offset >= this.length) {
            return ""
        }
        val piece = this.pieceAt(offset)
        // only what is asked for is copied
        val start = this.sourceStarts[piece] + offset - this.pieceStarts[piece]
        val end = this.sourceStarts[piece] + this.pieceStarts[piece + 1] - this.pieceStarts[piece]
        return this.sources[piece].substring(start, Math.min(end, start + Math.max(1, maxLength)))
    }

    /** Hands the whole content to [callback], one piece at a time, in order, without the BOM. */
    fun readPiecesContent(callback: (String) -> Unit) {
        for (piece in 0 until this.sources.size) {
            callback(this.pieceText(piece))
        }
    }

    // only used by read()
    private var readIndex = 0

    /**
     * Sequential reading, as in VS Code's ITextSnapshot: [bom] followed by the content, one
     * piece per call, then null.
     *
     * This is the one stateful method: the read position is kept in the snapshot, so a snapshot
     * must not be read() from two threads at once (every other method can be). For anything new
     * use [readPiecesContent] or [getNearestChunk].
     */
    fun read(): String? {
        val index = this.readIndex
        if (this.sources.size == 0) {
            if (index == 0) {
                this.readIndex = 1
                return this.bom
            }
            return null
        }
        if (index >= this.sources.size) {
            return null
        }
        this.readIndex = index + 1
        val text = this.pieceText(index)
        return if (index == 0) this.bom + text else text
    }

    /** The whole content without the BOM. */
    override fun toString(): String = this.substring(0, this.length)
}
