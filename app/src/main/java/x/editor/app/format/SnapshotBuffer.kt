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

package x.editor.app.format

import x.github.module.editor.model.EditorBuffer
import x.github.module.piecetable.PieceTreeSnapshot
import x.github.module.piecetable.common.ApplyEditsResult
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * A text to look at, not to edit: a [PieceTreeSnapshot] of a document behind the
 * [EditorBuffer] the editor works with, so that another EditorView (the preview of the
 * formatting sheet) shows the document without copying it.
 *
 * A snapshot points at the chunks of the document's piece tree and copies only what was
 * typed: taking one costs next to nothing, also for a file of many megabytes. All this adds
 * is where each line starts ([of]: one pass over the text, four bytes a line), which a
 * snapshot does not keep. It never changes, and the snapshot can be read from any thread,
 * so the editor may lay it out on its own.
 *
 * The editor must not be editable ([applyEdits] throws): EditorView.setEditable(false).
 */
class SnapshotBuffer private constructor(
    /** The text. */
    val snapshot: PieceTreeSnapshot,
    // where each line starts (0-based offsets); the first is 0
    private val lineStarts: IntArray,
) : EditorBuffer {

    override val length: Int get() = snapshot.length

    override fun getEOL(): String = snapshot.eol

    override fun getLineCount(): Int = lineStarts.size

    private fun start(lineNumber: Int): Int = lineStarts[lineNumber.coerceIn(1, lineStarts.size) - 1]

    // the offset after the line and its line break
    private fun next(lineNumber: Int): Int =
        if (lineNumber >= lineStarts.size) snapshot.length else lineStarts[lineNumber.coerceAtLeast(1)]

    // the offset after the line, before its line break
    private fun end(lineNumber: Int): Int {
        val line = lineNumber.coerceIn(1, lineStarts.size)
        val next = next(line)
        if (line >= lineStarts.size) return next
        // \r\n, or a lone \n or \r
        return if (next >= 2 && snapshot[next - 1] == '\n' && snapshot[next - 2] == '\r' && next - 2 >= start(line)) next - 2 else next - 1
    }

    override fun getLineContent(lineNumber: Int): String = snapshot.substring(start(lineNumber), end(lineNumber))

    override fun getLineContentWithEOL(lineNumber: Int): String = snapshot.substring(start(lineNumber), next(lineNumber))

    override fun getLineLength(lineNumber: Int): Int = end(lineNumber) - start(lineNumber)

    override fun getLineMinColumn(lineNumber: Int): Int = 1

    override fun getLineMaxColumn(lineNumber: Int): Int = getLineLength(lineNumber) + 1

    override fun getLineCharCode(lineNumber: Int, index: Int): Int = snapshot[start(lineNumber) + index].code

    override fun getCharCode(offset: Int): Int = snapshot[offset].code

    override fun getOffsetAt(lineNumber: Int, column: Int): Int {
        val line = lineNumber.coerceIn(1, lineStarts.size)
        val start = start(line)
        return start + (column - 1).coerceIn(0, end(line) - start)
    }

    override fun getPositionAt(offset: Int): Position {
        val at = offset.coerceIn(0, snapshot.length)
        // the last line that starts at or before it
        var index = java.util.Arrays.binarySearch(lineStarts, at)
        if (index < 0) index = -index - 2
        val line = index + 1
        val start = lineStarts[index]
        // in a line break: at the end of the line
        return Position(line, (at - start).coerceAtMost(end(line) - start) + 1)
    }

    private fun offsetsOf(range: Range): Pair<Int, Int> {
        val a = getOffsetAt(range.startLine, range.startColumn)
        val b = getOffsetAt(range.endLine, range.endColumn)
        return if (a <= b) a to b else b to a
    }

    override fun getValueInRange(range: Range): String {
        val (start, end) = offsetsOf(range)
        return snapshot.substring(start, end)
    }

    override fun getValueLengthInRange(range: Range): Int {
        val (start, end) = offsetsOf(range)
        return end - start
    }

    override fun getText(): String = snapshot.toString()

    override fun find(regex: Regex, searchRange: Range, limitResultCount: Int, isCancelled: () -> Boolean): List<Range> {
        val (start, end) = offsetsOf(searchRange)
        val out = ArrayList<Range>()
        for (match in regex.findAll(snapshot.substring(start, end))) {
            if (out.size >= limitResultCount || isCancelled()) break
            if (match.range.isEmpty()) continue
            out += rangeOf(start + match.range.first, start + match.range.last + 1)
        }
        return out
    }

    override fun find(searchText: String, searchRange: Range, limitResultCount: Int, isCancelled: () -> Boolean): List<Range> {
        if (searchText.isEmpty()) return emptyList()
        val (start, end) = offsetsOf(searchRange)
        val text = snapshot.substring(start, end)
        val out = ArrayList<Range>()
        var at = text.indexOf(searchText)
        while (at >= 0 && out.size < limitResultCount && !isCancelled()) {
            out += rangeOf(start + at, start + at + searchText.length)
            at = text.indexOf(searchText, at + searchText.length)
        }
        return out
    }

    private fun rangeOf(start: Int, end: Int): Range {
        val a = getPositionAt(start)
        val b = getPositionAt(end)
        return Range(a.lineNumber, a.column, b.lineNumber, b.column)
    }

    /** Not editable: see the class. */
    override fun applyEdits(operations: List<SingleEditOperation>, computeUndoEdits: Boolean): ApplyEditsResult =
        throw UnsupportedOperationException("a snapshot cannot be edited")

    companion object {

        /**
         * [snapshot] as a buffer: where its lines start is found here, in one pass. Its line
         * breaks are those of a piece tree (\n, \r\n, or a lone \r). Not on the main thread
         * for a large text.
         */
        fun of(snapshot: PieceTreeSnapshot): SnapshotBuffer {
            var starts = IntArray(maxOf(16, snapshot.lineCount))
            var count = 0
            fun add(offset: Int) {
                if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                starts[count++] = offset
            }
            add(0)
            var offset = 0
            var afterCR = false
            snapshot.readPiecesContent { piece ->
                for (i in piece.indices) {
                    val c = piece[i]
                    if (afterCR) {
                        afterCR = false
                        if (c == '\n') {
                            add(offset + i + 1)
                            continue
                        }
                        // a lone \r ended the line before
                        add(offset + i)
                    }
                    when (c) {
                        '\r' -> afterCR = true
                        '\n' -> add(offset + i + 1)
                    }
                }
                offset += piece.length
            }
            if (afterCR) add(offset)
            return SnapshotBuffer(snapshot, starts.copyOf(count))
        }
    }
}
