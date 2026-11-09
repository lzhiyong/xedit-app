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

package x.github.module.editor.layout

import androidx.annotation.MainThread
import androidx.annotation.WorkerThread

import x.github.module.editor.model.EditorTextSource

/**
 * No wrapping: every buffer line is exactly one row, as wide as its text.
 *
 * So a row number is a line number, a row starts at offset 0 and ends at the length
 * of its line, and the one thing left to remember per line is its width. That is all
 * this class stores: one Int per line in a plain array (4 bytes a line, where an
 * object per line cost about ten times that), and nothing that has to be renumbered
 * when a line is inserted or removed above.
 */
public class UnwrappedLayout(
    source: EditorTextSource,
    metrics: TextMeasurer
) : TextLayout(source, metrics) {

    // The widths of the first [size] lines, and the largest of them. [values] has
    // room to spare so a new line does not mean a new array.
    private class LineWidths(var values: IntArray, var size: Int, var max: Int) {

        fun ensureCapacity(capacity: Int) {
            if (capacity > values.size) {
                // grow by half, like a list does
                values = values.copyOf(Math.max(capacity, values.size + (values.size shr 1)))
            }
        }

        // looks at every line; only for when the widest one cannot be known otherwise
        fun findMax() {
            var maxWidth = 0
            for (index in 0 until size) {
                if (values[index] > maxWidth) {
                    maxWidth = values[index]
                }
            }
            max = maxWidth
        }
    }

    // Edits change it in place, on the main thread. A relayout never does: it fills a
    // fresh one on its worker thread and swaps it in here when it is complete, so a
    // frame drawn meanwhile reads the old widths, never a mixture, and a cancelled
    // relayout leaves no trace.
    @Volatile
    private var widths = LineWidths(
        // an empty document still has one (empty) line
        IntArray(INITIAL_CAPACITY), 1, 0
    )

    private companion object {
        const val INITIAL_CAPACITY = 64
    }

    private fun measureLine(bufferLine: Int): Int = metrics.measureText(source.getLine(bufferLine))

    override fun width(): Int = widths.max

    override fun height(): Int = source.getLineCount() * metrics.lineHeight

    override fun count(): Int = widths.size

    // #region one row

    // a row is a buffer line; past the end means the last one
    override fun getBufferLine(line: Int): Int = Math.max(1, Math.min(line, widths.size))

    override fun getLineStart(line: Int): Int = 0

    override fun getLineEnd(line: Int): Int {
        // the text can be ahead of the layout for a moment (a new buffer was set and
        // its relayout is still running), so stay inside the text as well
        val bufferLine = Math.min(getBufferLine(line), source.getLineCount())
        return source.getLineLength(bufferLine)
    }

    override fun getLineWidth(line: Int): Int = widths.run {
        if (size == 0) 0 else values[Math.max(1, Math.min(line, size)) - 1]
    }

    // #endregion

    // #region buffer line -> rows

    // a buffer line has exactly one row, the one with its own number
    private fun rowIndexOf(bufferLine: Int): Int = getBufferLine(bufferLine) - 1

    override fun getStartIndex(bufferLine: Int): Int = rowIndexOf(bufferLine)

    override fun getEndIndex(bufferLine: Int): Int = rowIndexOf(bufferLine)

    override fun getIndexAt(bufferLine: Int, column: Int): Int = rowIndexOf(bufferLine)

    // #endregion

    @WorkerThread
    override fun relayout(
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        isCancelled: () -> Boolean
    ) {
        val measured = LineWidths(IntArray(Math.max(INITIAL_CAPACITY, source.getLineCount())), 0, 0)

        var line = 1
        while (line <= source.getLineCount() && !isCancelled()) {
            val width = measureLine(line)
            measured.ensureCapacity(line)
            measured.values[line - 1] = width
            measured.size = line
            if (width > measured.max) {
                measured.max = width
            }
            // continue to next line
            line++
        }

        // publish the result and call onCompleted only if it was not cancelled
        if (!isCancelled()) {
            widths = measured
            rowsChanged()
            // finished callback
            onCompleted.invoke()
        }
    }

    // Without wrapping, a zoom changes nothing but the widths, and those grow in
    // proportion to the text size: scaling the cached numbers is as good as measuring
    // every line again, and takes milliseconds instead of seconds on a large text.
    override fun rescale(factor: Float): Boolean {
        widths.run {
            var maxWidth = 0
            for (index in 0 until size) {
                val width = Math.round(values[index] * factor)
                values[index] = width
                if (width > maxWidth) {
                    maxWidth = width
                }
            }
            max = maxWidth
        }
        rowsChanged()
        return true
    }

    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        applyUpdate(startLine, insertedLinesCnt, deletedLinesCnt, measureInserted = true)

    // the inserted lines get a width from their length, see TextLayout.updateRoughly
    override fun updateRoughly(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        applyUpdate(startLine, insertedLinesCnt, deletedLinesCnt, measureInserted = false)

    private fun applyUpdate(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int, measureInserted: Boolean) {
        val widths = this.widths

        // The edit does not fit the lines known here: the layout has lost track of
        // the text (it was recycled, or a relayout was cancelled). Start over.
        if (startLine < 1 || startLine + deletedLinesCnt > widths.size) {
            return relayout()
        }

        // the widest of the lines that go away, and of the ones that come in
        var removedMaxWidth = widths.values[startLine - 1]
        var addedMaxWidth = measureLine(startLine)

        // modify current line
        widths.values[startLine - 1] = addedMaxWidth

        // In the array, the lines after the modified one start at index startLine.

        // remove some lines: close the gap
        if (deletedLinesCnt > 0) {
            for (index in startLine until startLine + deletedLinesCnt) {
                removedMaxWidth = Math.max(removedMaxWidth, widths.values[index])
            }
            System.arraycopy(
                widths.values, startLine + deletedLinesCnt,
                widths.values, startLine,
                widths.size - (startLine + deletedLinesCnt)
            )
            widths.size -= deletedLinesCnt
        }

        // insert some lines: open a gap and fill it
        if (insertedLinesCnt > 0) {
            widths.ensureCapacity(widths.size + insertedLinesCnt)
            System.arraycopy(
                widths.values, startLine,
                widths.values, startLine + insertedLinesCnt,
                widths.size - startLine
            )
            // a rough width: as many chars as the line has, each as wide as a digit
            val charWidth = if (measureInserted) 0 else Math.max(1, metrics.measureText("0"))
            for (index in startLine until startLine + insertedLinesCnt) {
                val width = if (measureInserted) measureLine(index + 1) else source.getLineLength(index + 1) * charWidth
                widths.values[index] = width
                addedMaxWidth = Math.max(addedMaxWidth, width)
            }
            widths.size += insertedLinesCnt
        }

        rowsChanged()

        when {
            // a new widest line
            addedMaxWidth >= widths.max -> widths.max = addedMaxWidth
            // the widest line was among those that changed or went away, and nothing
            // as wide came in: only now is there no way around looking at them all
            removedMaxWidth >= widths.max -> widths.findMax()
            // otherwise the widest line was not touched
        }
    }

    override fun getBreakResults(): List<LineBreakResult> = widths.run {
        val lineCount = source.getLineCount()
        List(size) { index ->
            val bufferLine = index + 1
            val length = if (bufferLine <= lineCount) source.getLineLength(bufferLine) else 0
            LineBreakResult(bufferLine, 0, length, values[index])
        }
    }

    override fun setBreakResults(lineBreaks: List<LineBreakResult>) {
        val restored = LineWidths(IntArray(Math.max(INITIAL_CAPACITY, lineBreaks.size)), lineBreaks.size, 0)
        for (index in 0 until lineBreaks.size) {
            restored.values[index] = lineBreaks[index].width
        }
        restored.findMax()
        widths = restored
        rowsChanged()
    }

    override fun recycle() {
        widths = LineWidths(IntArray(0), 0, 0)
        rowsChanged()
    }
}
