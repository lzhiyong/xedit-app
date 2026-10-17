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

import x.github.module.piecetable.SliceChange
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.SliceEditResult
import x.github.module.piecetable.TextSlice
import x.github.module.piecetable.common.ApplyEditsResult
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * The text itself, as the editor needs it: lines, offsets, ranges, search, and one
 * way to change it.
 *
 * The editor does not care how the text is stored. Everything it asks of a buffer is
 * in this interface, and [EditorDocument] works on nothing else, so another structure
 * (a rope, a gap buffer, a plain string for tests, a buffer that lives in another
 * process) can be put under the editor by implementing it. The one that comes with
 * the editor is [PieceTreeBuffer].
 *
 * (Not called TextBuffer: the piece tree module has a class of that name, one of
 * the chunks of text a piece tree is built from, and the two would be confused.)
 *
 * Conventions, the same as in the rest of the editor:
 *  - line numbers and columns are 1-based, offsets are 0-based;
 *  - a column is a position *between* characters: column 1 is before the first
 *    character of a line, [getLineMaxColumn] is after its last one;
 *  - a range includes its start and excludes its end.
 *
 * Reading must be safe from a thread other than the one that edits, as long as no
 * edit is going on at the same time: a relayout reads the whole text on a worker
 * thread. (The editor sees to it that the two never overlap.)
 */
interface EditorBuffer {

    /** The length of the whole text, line breaks included. */
    val length: Int

    /** The line break of this text: "\n" or "\r\n". */
    fun getEOL(): String

    fun getLineCount(): Int

    /** The text of a line, without its line break. */
    fun getLineContent(lineNumber: Int): String

    /** The text of a line, with its line break (the last line has none). */
    fun getLineContentWithEOL(lineNumber: Int): String

    /** The length of a line, without its line break. */
    fun getLineLength(lineNumber: Int): Int

    /** The first column of a line: 1. */
    fun getLineMinColumn(lineNumber: Int): Int

    /** The column after the last character of a line. */
    fun getLineMaxColumn(lineNumber: Int): Int

    /** The char at a 0-based index within a line, as its code. */
    fun getLineCharCode(lineNumber: Int, index: Int): Int

    /** The char at an offset, as its code. */
    fun getCharCode(offset: Int): Int

    fun getOffsetAt(lineNumber: Int, column: Int): Int

    fun getPositionAt(offset: Int): Position

    fun getValueInRange(range: Range): String

    /** The length of the text in a range, without building the string. */
    fun getValueLengthInRange(range: Range): Int

    /** The whole text. */
    fun getText(): String

    /** The matches of [regex] in [searchRange], in document order. */
    fun find(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range>

    /** The occurrences of [searchText] in [searchRange], in document order. */
    fun find(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range>

    /**
     * Applies edits, all at once: the ranges of [operations] are all in the
     * coordinates of the text as it is before any of them.
     *
     * The result says what changed, one [x.github.module.piecetable.common.ContentChange]
     * per edit, **the edit furthest down the text first**. The editor relies on that
     * order: it follows up each change in turn (layout, search matches), and a change
     * further up must not have moved the lines of one that is still to come. With
     * [computeUndoEdits] the result also carries what is needed to take the edits
     * back.
     */
    fun applyEdits(operations: List<SingleEditOperation>, computeUndoEdits: Boolean): ApplyEditsResult

    /**
     * Applies edits whose new content is given as [TextSlice]s: the way the editor edits.
     * Same rules as [applyEdits] (ranges in the coordinates of the text before any of
     * them, changes reported the edit furthest down first), and every change says what
     * it removed ([SliceChange.removed]), which is what the undo history keeps.
     *
     * A buffer that can refer to its own storage exports large removed content as
     * references instead of copying it, and inserts such references back without copying
     * them either; that is what makes undoing a huge deletion cheap. [PieceTreeBuffer] does.
     *
     * The default goes through [applyEdits] and Strings: correct for any buffer, but every
     * content is copied, as before slices existed.
     */
    fun applySliceEdits(edits: List<SliceEdit>): SliceEditResult {
        val result = applyEdits(
            edits.map { SingleEditOperation(it.range, it.content?.toText()) },
            computeUndoEdits = true
        )
        val reverse = result.reverseEdits.orEmpty()
        // what each change removed, found by where it was and how long it was
        val removed = HashMap<Long, TextSlice>()
        for (op in reverse) {
            val change = op.textChange
            if (change.oldText.isNotEmpty()) {
                removed[spanKey(change.oldPosition, change.oldLength)] = TextSlice.of(change.oldText)
            }
        }
        val changes = result.changes.map { change ->
            SliceChange(
                change.range,
                change.rangeOffset,
                change.rangeLength,
                change.text?.takeIf { it.isNotEmpty() }?.let { TextSlice.of(it) },
                removed[spanKey(change.rangeOffset, change.rangeLength)]
            )
        }
        val reverseEdits = reverse.map { op ->
            SliceEdit(op.range, op.text?.takeIf { it.isNotEmpty() }?.let { TextSlice.of(it) })
        }
        return SliceEditResult(changes, reverseEdits)
    }
}

// offset and length in one key
private fun spanKey(offset: Int, length: Int): Long = (offset.toLong() shl 32) or (length.toLong() and 0xFFFFFFFFL)
