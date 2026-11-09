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

package x.github.module.editor.structure

import x.github.module.editor.model.EditorBuffer

/**
 * A part of the text that can be folded: the lines after [startLine] up to and with
 * [endLine] (1-based) can be hidden behind [startLine], which stays visible. [kind] is
 * whatever the engine wants to tell apart ("comment", "imports", "region"); the editor
 * passes it on and does not look at it.
 */
data class FoldingRange(
    val startLine: Int,
    val endLine: Int,
    val kind: String? = null
)

/**
 * Says what can be folded (see EditorView.setFoldingRangeProvider). The editor marks
 * those places in the gutter and reports taps on the marks; which parts of the text
 * can be folded, an implementation works out: from the indentation like
 * [IndentationFoldingProvider], from a syntax tree, from a language server.
 *
 * Called on the main thread for the lines around the screen, and only when the text,
 * those lines, or the provider's answer have changed (EditorView.invalidateStructure).
 */
fun interface FoldingRangeProvider {

    /** The ranges that start on one of the lines [firstLine]..[lastLine]; at most one per line. */
    fun getFoldingRanges(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<FoldingRange>

    /**
     * As [getFoldingRanges], for fold markers that are about to be drawn: a provider that
     * works them out in the background answers null while it does, and calls
     * EditorView.invalidateStructure when they are there; the editor draws the markers it
     * had meanwhile. By default the ranges, at once. A tap on a marker, folding all, ...
     * ask [getFoldingRanges], which always answers.
     */
    fun getFoldingRangesToDraw(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<FoldingRange>? =
        getFoldingRanges(buffer, firstLine, lastLine)
}

/**
 * Folding by indentation: a line followed by lines indented further starts a range,
 * which ends at the last line before the indentation comes back to that of the start
 * line (blank lines at its end left out).
 *
 * @param tabSize how far a tab reaches
 * @param maxScanLines how far below the requested lines a range is followed; one that
 *        goes on further is cut off there
 */
open class IndentationFoldingProvider(
    private val tabSize: Int = 4,
    private val maxScanLines: Int = 5000
) : FoldingRangeProvider {

    override fun getFoldingRanges(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<FoldingRange> {
        val lineCount = buffer.getLineCount()
        val scanEnd = Math.min(lineCount, lastLine + maxScanLines)
        val ranges = ArrayList<FoldingRange>()
        // ranges started and not ended yet: start line and its indentation, innermost last
        val startLines = ArrayList<Int>()
        val startIndents = ArrayList<Int>()
        // the last line that was not blank, and its indentation
        var previousLine = 0
        var previousIndent = -1

        var line = Math.max(1, firstLine)
        while (line <= scanEnd) {
            // Past the lines asked for, only the ranges already started are of interest.
            // (The last line asked for is decided by the next line that is not blank.)
            if (previousLine > lastLine && startLines.isEmpty()) break
            val indent = Indentation.indentOf(buffer.getLineContent(line), tabSize)
            if (indent >= 0) {
                // back at (or before) the indentation of a start line: its range is over
                while (startIndents.isNotEmpty() && startIndents[startIndents.size - 1] >= indent) {
                    val start = startLines.removeAt(startLines.size - 1)
                    startIndents.removeAt(startIndents.size - 1)
                    if (previousLine > start) ranges.add(FoldingRange(start, previousLine))
                }
                // indented further than the line before: that line starts a range
                if (previousLine >= firstLine && previousLine <= lastLine && indent > previousIndent && previousIndent >= 0) {
                    startLines.add(previousLine)
                    startIndents.add(previousIndent)
                }
                previousLine = line
                previousIndent = indent
            }
            line++
        }
        // what is still open runs to the last line that was looked at
        for (i in startLines.indices) {
            if (previousLine > startLines[i]) ranges.add(FoldingRange(startLines[i], previousLine))
        }
        ranges.sortBy { it.startLine }
        return ranges
    }
}
