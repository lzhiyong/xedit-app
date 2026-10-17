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

import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.SliceChange
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.TextSlice
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.Strings

import x.github.module.editor.util.TextBoundaries

/**
 * The text being edited: the buffer, every query on it, and the one way to change it.
 *
 * All edits go through one pipeline,
 *
 *     beforeTextChanged -> buffer -> onTextChanged (once per change) -> afterTextChanged
 *
 * which reports each stage to the [Callback]. Undo and redo are edits like any other:
 * they are replayed from the [EditHistory] through the same pipeline.
 *
 * Content travels as [TextSlice]s, not Strings, all the way: into the buffer, into the
 * undo history, and out to the callback. Typed text is a slice holding a String. What an
 * edit removes is exported by the buffer, and a piece tree keeps large removed content
 * as references into its storage; undoing puts the references back. So deleting a whole
 * 50 MB file and undoing it copies the file neither into the history nor into the
 * callbacks. Whoever needs the text of a change asks for it ([AppliedChange.insertedText])
 * and pays for the copy then.
 *
 * It knows nothing about views, pixels, the cursor or the selection, so the editing
 * logic can be exercised without putting a View on screen. Nor does it know how the
 * text is stored: the buffer is a [EditorBuffer], by default a [PieceTreeBuffer].
 *
 * The document object itself is stable for the lifetime of the editor; loading another
 * file swaps the buffer *inside* it ([replaceBuffer]), so collaborators can keep a
 * reference to the document and always read the current text.
 */
class EditorDocument(
    private val history: EditHistory,
    private val callback: Callback,
    buffer: EditorBuffer = PieceTreeBuffer(PieceTreeTextBufferBuilder().build())
) : EditorTextSource {

    /** The three stages of an edit, in the order they happen. */
    interface Callback {
        /**
         * The edit is about to be applied; the buffer still holds the old text. Each edit
         * replaces a range with a content, null for none (a deletion).
         */
        fun beforeTextChanged(edits: List<SliceEdit>)

        /** One change has been applied to the buffer. Called once per change of the edit. */
        fun onTextChanged(change: AppliedChange)

        /**
         * The whole edit is done; [changes] are the ones reported to [onTextChanged], in
         * the same order. [caretLineNumber]/[caretColumn] is where the caret belongs now:
         * right after the text of the first change.
         */
        fun afterTextChanged(changes: List<AppliedChange>, caretLineNumber: Int, caretColumn: Int)
    }

    /**
     * One change as it landed in the buffer, described in the terms a line-based
     * consumer (layout, tokenizer) needs.
     */
    data class AppliedChange(
        /** the range that was replaced, in pre-edit coordinates */
        val range: Range,
        /** the document offset of the start of [range] */
        val rangeOffset: Int,
        val insertedLinesCnt: Int,
        val insertedTextLength: Int,
        val deletedLinesCnt: Int,
        val deletedTextLength: Int,
        /** the position right after the inserted text (or where the deleted text was) */
        val finalLineNumber: Int,
        val finalColumn: Int,
        /** what was inserted; null for nothing (a deletion) */
        val content: TextSlice? = null
    ) {
        /**
         * The inserted text as a String; empty for a deletion. Free for typed text. For
         * a large undo or redo the content is held as references into the buffer, and
         * this copies it: do not call it without need, and only on the main thread.
         */
        fun insertedText(): String = content?.toText() ?: ""

        companion object {
            fun of(change: ContentChange): AppliedChange {
                // no text means nothing was inserted: a plain deletion
                val text = change.text
                return of(
                    change.range, change.rangeOffset, change.rangeLength,
                    if (text.isNullOrEmpty()) null else TextSlice.of(text)
                )
            }

            fun of(change: SliceChange): AppliedChange =
                of(change.range, change.rangeOffset, change.rangeLength, change.content)

            private fun of(range: Range, rangeOffset: Int, rangeLength: Int, inserted: TextSlice?): AppliedChange {
                val content = inserted?.takeIf { it.length > 0 }
                // the line shape of the inserted text, without reading it
                val insertedLinesCnt = content?.eolCount ?: 0
                val lastLineLength = content?.lastLineLength ?: 0

                val finalColumn = when {
                    // delete text
                    content == null -> range.startColumn
                    // inserted text stays on the start line
                    insertedLinesCnt == 0 -> range.startColumn + lastLineLength
                    // inserted text ends on a line of its own
                    else -> lastLineLength + 1
                }

                return AppliedChange(
                    range = range,
                    rangeOffset = rangeOffset,
                    insertedLinesCnt = insertedLinesCnt,
                    insertedTextLength = content?.length ?: 0,
                    deletedLinesCnt = range.endLine - range.startLine,
                    deletedTextLength = rangeLength,
                    finalLineNumber = range.startLine + insertedLinesCnt,
                    finalColumn = finalColumn,
                    content = content
                )
            }
        }
    }

    /** The current buffer. Read on worker threads too (layout), hence volatile. */
    @Volatile
    var buffer: EditorBuffer = buffer
        private set

    /**
     * Swaps in a whole new buffer (another file, a restored state). This is not an
     * edit: the callback is not called and the history is left alone, the caller decides
     * what to do with both.
     */
    fun replaceBuffer(newBuffer: EditorBuffer) {
        buffer = newBuffer
    }

    // #region queries

    // \n or \r\n
    fun getEOL() = buffer.getEOL()

    override fun getLineCount(): Int = buffer.getLineCount()

    /** The length of the whole text. */
    val length get() = buffer.length

    override fun getLineLength(lineNumber: Int): Int = buffer.getLineLength(lineNumber)

    fun getRangeLength(range: Range) = buffer.getValueLengthInRange(range)

    fun getPosition(offset: Int): Position = buffer.getPositionAt(offset)

    fun getOffset(lineNumber: Int, column: Int): Int = buffer.getOffsetAt(lineNumber, column)

    fun getCharAt(lineNumber: Int, column: Int): Char =
        buffer.run { getCharCode(getOffsetAt(lineNumber, column)).toChar() }

    /** [offset] is 0-based within the line. */
    fun getLineCharAt(lineNumber: Int, offset: Int): Char =
        buffer.getLineCharCode(lineNumber, offset).toChar()

    override fun getLine(lineNumber: Int): String = buffer.getLineContent(lineNumber)

    fun getLineWithEOL(lineNumber: Int) = buffer.getLineContentWithEOL(lineNumber)

    fun getLineStartOffset(lineNumber: Int): Int = getOffset(lineNumber, 1)

    fun getLineEndOffset(lineNumber: Int): Int = getOffset(lineNumber, getLineEndColumn(lineNumber))

    fun getLineStartColumn(lineNumber: Int): Int = buffer.getLineMinColumn(lineNumber)

    /** [lineNumber] defaults to the last line. */
    fun getLineEndColumn(lineNumber: Int = getLineCount()): Int = buffer.getLineMaxColumn(lineNumber)

    fun getValueInRange(range: Range) = buffer.getValueInRange(range)

    /** The text of one line between two columns, [startColumn] inclusive, [endColumn] exclusive. */
    fun getLineSegment(lineNumber: Int, startColumn: Int, endColumn: Int) =
        buffer.getValueInRange(Range(lineNumber, startColumn, lineNumber, endColumn))

    fun getText(): String = buffer.getText()

    /** The range covering the whole text. */
    fun getFullRange() = Range(1, 1, getLineCount(), getLineEndColumn())

    // find matches by regex
    fun find(
        regex: Regex,
        searchRange: Range = getFullRange(),
        limitResultCount: Int = Int.MAX_VALUE,
        isCancelled: () -> Boolean = { false }
    ): List<Range> = buffer.find(regex, searchRange, limitResultCount, isCancelled)

    // find matches by word
    fun find(
        searchText: String,
        searchRange: Range = getFullRange(),
        limitResultCount: Int = Int.MAX_VALUE,
        isCancelled: () -> Boolean = { false }
    ): List<Range> = buffer.find(searchText, searchRange, limitResultCount, isCancelled)

    // #endregion

    // #region edit pipeline

    // what becomes of an edit in the history
    private enum class Record {
        // an undo step (merged into the current burst if it is small)
        STEP,
        // nothing, and the save point is lost: the text changed outside the history
        UNTRACKED,
        // nothing: undo and redo (the history moves itself), loading a text
        NONE
    }

    /**
     * Applies [operations] to the buffer and walks the callback through the pipeline.
     *
     * @param computeUndoEdits whether the edit becomes an undo step. False for an edit
     *        that must not be undoable; the text then counts as modified until it is
     *        saved again, since the history no longer knows where the saved text is.
     */
    fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean = true
    ) {
        applySliceEdits(
            operations.map { SliceEdit(it.range, it.text?.takeIf { text -> text.isNotEmpty() }?.let { text -> TextSlice.of(text) }) },
            computeUndoEdits
        )
    }

    /**
     * [applyEdits] with the contents as slices. A slice exported from this document's
     * piece tree goes back in without being copied, however large: moving a block of
     * text, for example, is an export and an edit, not two copies of the block.
     */
    fun applySliceEdits(
        edits: List<SliceEdit>,
        computeUndoEdits: Boolean = true
    ) {
        apply(edits.map { it.withWholeCharacters() }, if (computeUndoEdits) Record.STEP else Record.UNTRACKED)
    }

    // An edit whose range starts or ends between the two halves of a character made
    // of a surrogate pair (an emoji, many CJK characters) would leave half of it
    // behind: the range is widened to the whole character. (Undo and redo replay
    // exact ranges and do not come through here.)
    private fun SliceEdit.withWholeCharacters(): SliceEdit {
        val start = range.getStartPosition()
        val end = range.getEndPosition()
        if (range.isEmpty()) {
            // an insertion goes in front of the character, not into it
            if (!splitsPair(start.lineNumber, start.column)) return this
            val column = start.column - 1
            return SliceEdit(Range(start.lineNumber, column, start.lineNumber, column), content)
        }
        val startColumn = if (splitsPair(start.lineNumber, start.column)) start.column - 1 else start.column
        val endColumn = if (splitsPair(end.lineNumber, end.column)) end.column + 1 else end.column
        if (startColumn == start.column && endColumn == end.column) return this
        return SliceEdit(Range(start.lineNumber, startColumn, end.lineNumber, endColumn), content)
    }

    // whether [column] of [line] lies between a high and a low surrogate
    private fun splitsPair(line: Int, column: Int): Boolean {
        if (line < 1 || line > buffer.getLineCount()) return false
        if (column <= 1 || column > buffer.getLineLength(line)) return false
        val around = buffer.getValueInRange(Range(line, column - 1, line, column + 1))
        return around.length == 2 && Character.isHighSurrogate(around[0]) && Character.isLowSurrogate(around[1])
    }

    // The pipeline. Returns the edit as history changes, forwards, in document order.
    private fun apply(edits: List<SliceEdit>, record: Record): List<HistoryChange> {
        // hold back the pending undo step, this edit may be merged into it
        history.cancelPendingCommit()

        // before text changed
        callback.beforeTextChanged(edits)
        // Real text edits, one at a time, the one furthest down first, each reported to
        // the callback as soon as it is in: what reads the text then (the layout measures
        // the lines a change put in, the syntax tree) finds it as the change's range says.
        // All applied first and reported after, a change below one that added or removed
        // lines read the wrong lines, or lines past the end (undoing a format). Applied
        // bottom up, each change's offsets are those of the text before the whole edit
        // (what changed is below it): the buffer reports a batch the same way.
        val sliceChanges = ArrayList<SliceChange>(edits.size)
        val applied = ArrayList<AppliedChange>(edits.size)
        for (edit in bottomUp(edits)) {
            for (sliceChange in buffer.applySliceEdits(listOf(edit)).changes) {
                sliceChanges.add(sliceChange)
                val change = AppliedChange.of(sliceChange)
                applied.add(change)
                // update the lines and syntax tree
                callback.onTextChanged(change)
            }
        }

        val changes = historyChanges(sliceChanges)

        // The caret goes right after the text of the first change reported, the one
        // furthest down: where it ends in the text as it is now. (Its finalLineNumber
        // is in the coordinates from before the edit, and would be off by the lines
        // the changes above it added or removed.)
        var caretLineNumber = 1
        var caretColumn = 1
        changes.lastOrNull()?.let {
            val caret = buffer.getPositionAt(it.newEnd)
            caretLineNumber = caret.lineNumber
            caretColumn = caret.column
        }
        when (record) {
            Record.STEP -> history.record(changes)
            Record.UNTRACKED -> history.forgetSavePoint()
            Record.NONE -> {}
        }

        // after text changed
        callback.afterTextChanged(applied, caretLineNumber, caretColumn)
        return changes
    }

    // [edits] in the order to apply them one by one: the one furthest down first (the
    // later of two at the same place first, so that inserts there end up in their order,
    // as a batch of the buffer has them). Overlapping ranges are refused, as a batch does,
    // before anything is applied.
    private fun bottomUp(edits: List<SliceEdit>): List<SliceEdit> {
        if (edits.size <= 1) return edits
        val order = edits.indices.sortedWith(
            compareByDescending<Int> { edits[it].range.startLine }
                .thenByDescending { edits[it].range.startColumn }
                .thenByDescending { edits[it].range.endLine }
                .thenByDescending { edits[it].range.endColumn }
                .thenByDescending { it }
        )
        for (k in 1 until order.size) {
            val below = edits[order[k - 1]].range
            val above = edits[order[k]].range
            val overlaps = above.endLine > below.startLine ||
                (above.endLine == below.startLine && above.endColumn > below.startColumn)
            require(!overlaps) { "Overlapping ranges are not allowed: $above and $below" }
        }
        return order.map { edits[it] }
    }

    // The changes of an edit as the history keeps them: forwards, top of the document
    // first, each with the offset it has after the edit. The buffer reports them bottom
    // up, all in the offsets of the text before the edit; walking them top down, each
    // one is shifted by how much the ones above it grew or shrank the text.
    private fun historyChanges(changes: List<SliceChange>): List<HistoryChange> {
        val result = ArrayList<HistoryChange>(changes.size)
        var delta = 0
        for (index in changes.indices.reversed()) {
            val change = changes[index]
            val inserted = change.content ?: HistoryChange.EMPTY
            result.add(
                HistoryChange(
                    oldPosition = change.rangeOffset,
                    oldContent = change.removed ?: HistoryChange.EMPTY,
                    newPosition = change.rangeOffset + delta,
                    newContent = inserted
                )
            )
            delta += inserted.length - change.rangeLength
        }
        return result
    }

    /**
     * Replaces the whole text. Not undoable, and nothing from before can be undone
     * afterwards either: the undo steps hold offsets into the text that is being
     * replaced, and applying them to the new text would scramble it. The new text
     * counts as saved.
     */
    fun setText(text: String) {
        apply(listOf(SliceEdit(getFullRange(), if (text.isEmpty()) null else TextSlice.of(text))), Record.NONE)
        history.clear()
    }

    /**
     * Takes back the last undo step.
     *
     * @return whether there is more to undo
     */
    fun undo(): Boolean {
        // Nothing to undo means nothing to apply: running an empty edit through the
        // pipeline would still report "caret at 1:1" and send the cursor to the top.
        val step = history.undo() ?: return history.canUndo()
        if (step.changes.isEmpty()) return history.canUndo()
        val done = apply(
            step.changes.map { SliceEdit(rangeBetween(it.newPosition, it.newEnd), it.oldContent) },
            Record.NONE
        )
        // Taking it back exported what the step had inserted: kept from now on, as
        // references if it is large, instead of the String it may have come in as.
        history.replayed(step, done.map { it.inverted() })
        return history.canUndo()
    }

    /**
     * Re-applies the last undone step.
     *
     * @return whether there is more to redo
     */
    fun redo(): Boolean {
        val step = history.redo() ?: return history.canRedo()
        if (step.changes.isEmpty()) return history.canRedo()
        val done = apply(
            step.changes.map { SliceEdit(rangeBetween(it.oldPosition, it.oldEnd), it.newContent) },
            Record.NONE
        )
        history.replayed(step, done)
        return history.canRedo()
    }

    private fun rangeBetween(startOffset: Int, endOffset: Int): Range {
        val start = getPosition(startOffset)
        val end = getPosition(endOffset)
        return Range(start.lineNumber, start.column, end.lineNumber, end.column)
    }

    /**
     * The range a backspace at [caret] deletes: the whole character before it (which
     * may be several chars long, e.g. an emoji), or the line break when the caret is at
     * the start of a line. Empty at the very start of the document.
     */
    fun backspaceRange(caret: Position): Range {
        val lineNumber = caret.lineNumber
        val column = caret.column

        return when {
            // in the middle of a line
            column > 1 -> Range(lineNumber, 1, lineNumber, column).apply {
                // reset range start column, may contains unicode characters
                startColumn = TextBoundaries.graphemeStart(getValueInRange(this), endColumn - 1) + 1
            }
            // at the start of a line: delete the line feed (\n)
            lineNumber > 1 -> Range(lineNumber - 1, getLineEndColumn(lineNumber - 1), lineNumber, column)
            // at the first position, index 0
            else -> Range.fromPositions(caret)
        }
    }

    /**
     * The range a backspace at [caret] deletes when it takes a whole word at a time
     * (the delete key held down): back to the start of the word before the caret,
     * any whitespace in between included. At the start of a line it is the line
     * break, like [backspaceRange]. Empty at the very start of the document.
     */
    fun wordBackspaceRange(caret: Position): Range {
        val lineNumber = caret.lineNumber
        val column = caret.column
        if (column <= 1) {
            return backspaceRange(caret)
        }
        val wordStart = TextBoundaries.previousWordStart(getLine(lineNumber), column - 1)
        return Range(lineNumber, wordStart + 1, lineNumber, column)
    }

    /**
     * The range a forward delete at [caret] removes: the whole character after it, or
     * the line break when the caret is at the end of a line. Empty at the very end of
     * the document.
     */
    fun forwardDeleteRange(caret: Position): Range {
        val lineNumber = caret.lineNumber
        val column = caret.column
        val endColumn = getLineEndColumn(lineNumber)

        return when {
            // in the middle of a line
            column < endColumn -> {
                val rest = getLineSegment(lineNumber, column, endColumn)
                Range(lineNumber, column, lineNumber, column + TextBoundaries.graphemeEnd(rest, 0))
            }
            // at the end of a line: delete the line feed (\n)
            lineNumber < getLineCount() -> Range(lineNumber, column, lineNumber + 1, 1)
            // at the last position
            else -> Range.fromPositions(caret)
        }
    }

    // #endregion
}
