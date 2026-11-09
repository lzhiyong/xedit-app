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

import androidx.annotation.WorkerThread

import x.github.module.editor.model.EditorTextSource


/**
 * Word wrap: a buffer line is cut into as many rows as it takes to fit the wrap
 * width, and each row is kept as a [LineBreakResult].
 */
public class WordwrapLayout(
    source: EditorTextSource,
    metrics: TextMeasurer,
    // the width available to a wrapped line (view width minus both text indents);
    // asked for on every (re)layout since it follows the view size and the gutter
    private val wrapWidth: () -> Int
) : TextLayout(source, metrics) {
    
    // one entry per row, in order; the rows of one buffer line are next to each other
    @Volatile
    private var lineBreaks = mutableListOf(
        // an empty document still has one (empty) line
        LineBreakResult(1, 0, 0, 0)
    )
    
    // the wrap width the rows were broken for
    private var desiredWidth: Int = 0
    
    override val isWrapping: Boolean get() = true

    // every row may be as wide as the wrap width
    override fun width(): Int = desiredWidth
    
    override fun height(): Int = lineBreaks.size * metrics.lineHeight
    
    override fun count(): Int = lineBreaks.size
    
    // A number past the end gives the last row, and a layout that holds no rows at
    // all (it was recycled) gives an empty one, so drawing a frame with a stale row
    // number never fails.
    override fun getLineResult(line: Int): LineBreakResult = lineBreaks.run {
        if (isEmpty()) LineBreakResult() else get(Math.max(0, Math.min(line, size) - 1))
    }
    
    override fun getBufferLine(line: Int): Int = getLineResult(line).line
    
    override fun getLineStart(line: Int): Int = getLineResult(line).start
    
    override fun getLineEnd(line: Int): Int = getLineResult(line).end
    
    override fun getLineWidth(line: Int): Int = getLineResult(line).width
    
    override fun getStartIndex(bufferLine: Int): Int = lineBreaks.firstRowOf(bufferLine)
    
    override fun getEndIndex(bufferLine: Int): Int = lineBreaks.lastRowOf(bufferLine)
    
    override fun getIndexAt(bufferLine: Int, column: Int): Int = lineBreaks.rowIndexAt(bufferLine, column)
    
    override fun getBreakResults(): List<LineBreakResult> = lineBreaks.toList()
    
    override fun setBreakResults(lineBreaks: List<LineBreakResult>) {
        this.lineBreaks = lineBreaks.toMutableList()
        rowsChanged()
    }
    
    override fun recycle() {
        lineBreaks.clear()
        rowsChanged()
    }
    
    private fun breakLine(
        lineNumber: Int,
        text: String, 
        maxWidth: Float
    ): List<LineBreakResult> {
        // measure per line width
        val widths = metrics.getTextWidths(text)
        val breakOffsets = computeLineBreaks(text, widths, maxWidth, metrics.tabWidth)
        val lineResults = mutableListOf<LineBreakResult>()
        var start: Int = 0
        var end: Int = 0
        var width: Int = 0
        for (i in 0..breakOffsets.size - 1) {
            end = breakOffsets[i]
            width = Math.ceil(widths.sumRange(start, end).toDouble()).toInt()
            lineResults.add(LineBreakResult(lineNumber, start, end, width))
            start = end
        }
              
        return lineResults
    }
    
    // Builds the rows in a list of its own and puts it in place when it is complete,
    // so a frame drawn meanwhile reads the old rows, never a mixture, and a cancelled
    // relayout leaves no trace.
    @WorkerThread
    override fun relayout(
        inProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        isCancelled: () -> Boolean
    ) {
        val maxWidth = wrapWidth().toFloat()
        val rows = ArrayList<LineBreakResult>(lineBreaks.size)

        var line: Int = 1
        while (line <= source.getLineCount() && !isCancelled()) {
            rows.addAll(breakLine(line, source.getLine(line), maxWidth))
            // continue to next line
            line++
        }

        // publish the result and call onCompleted only if it was not cancelled
        if (!isCancelled()) {
            desiredWidth = maxWidth.toInt()
            lineBreaks = rows
            rowsChanged()
            // finished callback
            onCompleted.invoke()
        }
    }
    
    // rescale() is not overridden: at another text size the lines break at other
    // places, which only a relayout can find out
    
    override fun update(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        applyUpdate(startLine, insertedLinesCnt, deletedLinesCnt, measureInserted = true)

    // the inserted lines get one row each, see TextLayout.updateRoughly
    override fun updateRoughly(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int) =
        applyUpdate(startLine, insertedLinesCnt, deletedLinesCnt, measureInserted = false)

    // The rows of the lines startLine..startLine + deletedLinesCnt (as they were) give
    // way to those of startLine..startLine + insertedLinesCnt (as they are now), in
    // one replacement: the list shifts once, however many lines come and go. (Row by
    // row it would shift once per row, which for a large edit takes minutes.)
    private fun applyUpdate(startLine: Int, insertedLinesCnt: Int, deletedLinesCnt: Int, measureInserted: Boolean) {
        val maxWidth = wrapWidth().toFloat()

        // another wrap width, or rows that have lost track of the text: start over
        if (maxWidth.toInt() != desiredWidth || lineBreaks.isEmpty()) {
            return relayout()
        }

        // the rows that go: from the first of startLine to the last of the last
        // line the edit took away
        val first = getStartIndex(startLine)
        val lastOldLine = startLine + deletedLinesCnt
        var end = first
        while (end < lineBreaks.size && lineBreaks[end].line <= lastOldLine) {
            end++
        }

        // the rows that come, in a list of their own
        val rows = ArrayList<LineBreakResult>(insertedLinesCnt + 1)
        rows.addAll(breakLine(startLine, source.getLine(startLine), maxWidth))
        for (line in startLine + 1..startLine + insertedLinesCnt) {
            if (measureInserted) {
                rows.addAll(breakLine(line, source.getLine(line), maxWidth))
            } else {
                // one row for the whole line until the relayout breaks it
                rows.add(LineBreakResult(line, 0, source.getLineLength(line), 0))
            }
        }

        // the rows after the edit move with their lines
        val changedLinesCnt = insertedLinesCnt - deletedLinesCnt
        if (changedLinesCnt != 0) {
            for (k in end until lineBreaks.size) {
                lineBreaks[k].line += changedLinesCnt
            }
        }

        lineBreaks.subList(first, end).clear()
        lineBreaks.addAll(first, rows)
        rowsChanged()
    }
}

