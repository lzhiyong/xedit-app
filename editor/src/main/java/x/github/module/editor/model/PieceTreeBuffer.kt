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

package x.github.module.editor.model

import kotlin.text.Regex

import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.SliceEditResult
import x.github.module.piecetable.StorageStats
import x.github.module.piecetable.common.ApplyEditsResult
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * The buffer the editor comes with: a piece tree (the structure of VS Code's text
 * buffer), which loads a large file without copying it around and edits it in
 * logarithmic time.
 *
 * This class adds nothing to it. It only puts the piece tree behind the [EditorBuffer]
 * interface the editor works with (including [applySliceEdits], which keeps what an edit
 * removed as references into the tree instead of copying it); [pieceTree] is there for whoever needs the tree
 * itself (saving the editor's state, a parser that reads it directly).
 */
class PieceTreeBuffer(val pieceTree: PieceTreeTextBuffer) : EditorBuffer {

    override val length: Int get() = pieceTree.length

    override fun getEOL(): String = pieceTree.getEOL()

    override fun getLineCount(): Int = pieceTree.getLineCount()

    override fun getLineContent(lineNumber: Int): String = pieceTree.getLineContent(lineNumber)

    override fun getLineContentWithEOL(lineNumber: Int): String = pieceTree.getLineContentWithEOL(lineNumber)

    override fun getLineLength(lineNumber: Int): Int = pieceTree.getLineLength(lineNumber)

    override fun getLineMinColumn(lineNumber: Int): Int = pieceTree.getLineMinColumn(lineNumber)

    override fun getLineMaxColumn(lineNumber: Int): Int = pieceTree.getLineMaxColumn(lineNumber)

    override fun getLineCharCode(lineNumber: Int, index: Int): Int = pieceTree.getLineCharCode(lineNumber, index)

    override fun getCharCode(offset: Int): Int = pieceTree.getCharCode(offset)

    override fun getOffsetAt(lineNumber: Int, column: Int): Int = pieceTree.getOffsetAt(lineNumber, column)

    override fun getPositionAt(offset: Int): Position = pieceTree.getPositionAt(offset)

    override fun getValueInRange(range: Range): String = pieceTree.getValueInRange(range)

    override fun getValueLengthInRange(range: Range): Int = pieceTree.getValueLengthInRange(range)

    override fun getText(): String = pieceTree.toString()

    override fun find(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range> = pieceTree.find(regex, searchRange, limitResultCount, isCancelled)

    override fun find(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range> = pieceTree.find(searchText, searchRange, limitResultCount, isCancelled)

    override fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean
    ): ApplyEditsResult = pieceTree.applyEdits(operations, false, computeUndoEdits)

    // removed content of 16K chars and more is kept as references into the tree's storage
    override fun applySliceEdits(edits: List<SliceEdit>): SliceEditResult = pieceTree.applySliceEdits(edits)

    /**
     * [PieceTreeTextBuffer.compact] when it is worth it by [mode] (see [isWorthCompacting])
     * and the heap has room for it: the new storage is built before the old one can go,
     * so for a moment the text is held twice. Under memory pressure that moment is the
     * risk, and a compaction that does not fit is better left out than an OutOfMemoryError.
     *
     * Undo steps that hold a large deletion as references (see HistoryChange) keep the old
     * chunks they point into alive until they are forgotten; the rest is freed at once.
     */
    override fun compact(mode: EditorBuffer.CompactMode): Boolean {
        val stats = pieceTree.getStorageStats()
        if (!isWorthCompacting(stats, mode)) return false
        if (!hasHeapRoomFor(stats.length.toLong(), pieceTree.getLineCount().toLong())) return false
        pieceTree.compact()
        return true
    }

    override fun toString(): String = pieceTree.toString()

    companion object {
        // the size of the chunks a file is loaded into and compact() builds, in chars
        private const val CHUNK_CHARS = 64 * 1024

        // WORTHWHILE: half of the text or 1M chars (2 MB) of garbage, or that many pieces
        // more than the chunks of the text: typing at thousands of places
        private const val MIN_GARBAGE_CHARS = 1L shl 20
        private const val EXTRA_PIECES = 20_000

        // LOW_MEMORY: an eighth of the text, at least 64K chars (128 KB)
        private const val MIN_GARBAGE_CHARS_LOW_MEMORY = 64L * 1024
        private const val EXTRA_PIECES_LOW_MEMORY = 5_000

        /**
         * Whether rebuilding the storage of [stats] gives enough back for its O(n) cost:
         * the garbage (chars stored but no longer text) is large against the text, or
         * the tree is far more fragmented than a freshly loaded text is.
         */
        fun isWorthCompacting(stats: StorageStats, mode: EditorBuffer.CompactMode): Boolean {
            val length = stats.length.toLong()
            val garbage = stats.storedLength - length
            val basePieces = length / CHUNK_CHARS + 1
            val extraPieces = stats.pieceCount - basePieces
            return when (mode) {
                EditorBuffer.CompactMode.WORTHWHILE ->
                    garbage >= maxOf(length / 2, MIN_GARBAGE_CHARS) || extraPieces >= EXTRA_PIECES
                EditorBuffer.CompactMode.LOW_MEMORY ->
                    (garbage > 0 && garbage >= maxOf(length / 8, MIN_GARBAGE_CHARS_LOW_MEMORY)) ||
                        extraPieces >= EXTRA_PIECES_LOW_MEMORY
            }
        }

        // A second copy of the text (2 bytes a char), its line starts (4 bytes a line) and
        // the copies made on the way, twice over: what is free in the heap must cover that.
        private fun hasHeapRoomFor(length: Long, lines: Long): Boolean {
            val runtime = Runtime.getRuntime()
            val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            val needed = 2L * length + 4L * lines
            return free >= 2L * needed
        }
    }
}
