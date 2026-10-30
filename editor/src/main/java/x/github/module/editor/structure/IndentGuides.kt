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
 * One indent guide: a vertical line through the lines [startLine]..[endLine]
 * (1-based, inclusive), at the x where [column] (1-based) starts on [anchorLine].
 *
 * The x is taken from one line and kept for the whole guide, so that it is straight
 * whatever the other lines are indented with. By default the anchor is the first line
 * of the guide. An engine that knows the block (tree-sitter) can anchor it on the
 * line that opens the block instead, at the column of its first char: the guide then
 * stands right under the start of that line.
 */
data class IndentGuide(
    val startLine: Int,
    val endLine: Int,
    val column: Int,
    val anchorLine: Int = startLine
)

/**
 * Says where the indent guides are (see EditorView.setIndentGuideProvider). The editor
 * draws them, and highlights the innermost one around the cursor's line; which blocks
 * there are, an implementation works out: from the indentation like
 * [IndentationGuideProvider], or from a syntax tree.
 *
 * Called on the main thread for the lines around the screen, and only when the text,
 * those lines, or the provider's answer have changed (EditorView.invalidateStructure):
 * the editor keeps the answer otherwise. An engine that is still parsing answers with
 * what it has, and calls invalidateStructure when it knows more.
 */
fun interface IndentGuideProvider {

    /**
     * The guides that cross any of the lines [firstLine]..[lastLine]. A guide may reach
     * beyond them; the editor only draws what is on screen.
     */
    fun getIndentGuides(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<IndentGuide>

    /**
     * As [getIndentGuides], for guides that are about to be drawn: a provider that works
     * them out in the background answers null while it does, and calls
     * EditorView.invalidateStructure when they are there; the editor draws the guides it
     * had meanwhile. By default the guides, at once.
     */
    fun getIndentGuidesToDraw(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<IndentGuide>? =
        getIndentGuides(buffer, firstLine, lastLine)
}

/**
 * Indent guides from the indentation alone: a line indented by n levels has n guides,
 * one at the start of each level, and the guide of a level runs through all the lines
 * in a row indented that far. A blank line takes the smaller indentation of its neighbours,
 * so a block does not fall apart at an empty line in it.
 *
 * @param tabSize the columns of one level, and how far a tab reaches
 * @param maxScanLines how far beyond the requested lines a guide is followed up and
 *        down; one that goes further is cut off there
 */
open class IndentationGuideProvider(
    private val tabSize: Int = 4,
    private val maxScanLines: Int = 200
) : IndentGuideProvider {

    override fun getIndentGuides(buffer: EditorBuffer, firstLine: Int, lastLine: Int): List<IndentGuide> {
        val lineCount = buffer.getLineCount()
        val from = Math.max(1, firstLine - maxScanLines)
        val to = Math.min(lineCount, lastLine + maxScanLines)
        if (from > to) return emptyList()

        // the indentation of every line, blank lines filled in from their neighbours
        val indents = IntArray(to - from + 1) { Indentation.indentOf(buffer.getLineContent(from + it), tabSize) }
        val blank = BooleanArray(indents.size) { indents[it] < 0 }
        fillBlankLines(indents)

        val guides = ArrayList<IndentGuide>()
        // the line each level's guide started on, while it runs; 0: not running
        var open = IntArray(8)
        for (i in 0..indents.size) {
            val levels = if (i < indents.size) Math.max(0, indents[i]) / tabSize else 0
            if (levels > open.size) open = open.copyOf(Math.max(levels, open.size * 2))
            for (level in 1..open.size) {
                val running = open[level - 1] != 0
                // the guide of level n stands in the whitespace of the lines with n
                // levels or more, at the start of level n
                val here = level <= levels
                if (here && !running) {
                    open[level - 1] = from + i
                } else if (!here && running) {
                    addGuide(guides, buffer, blank, from, open[level - 1], from + i - 1, level, firstLine, lastLine)
                    open[level - 1] = 0
                }
            }
        }
        return guides
    }

    // a guide on [level] through [startLine]..[endLine], if it reaches the lines asked for
    private fun addGuide(
        guides: MutableList<IndentGuide>, buffer: EditorBuffer, blank: BooleanArray, from: Int,
        startLine: Int, endLine: Int, level: Int, firstLine: Int, lastLine: Int
    ) {
        if (endLine < firstLine || startLine > lastLine) return
        // measured on a line that has indentation of its own, not on a blank one
        var anchor = startLine
        while (anchor < endLine && blank[anchor - from]) anchor++
        val column = Indentation.columnAt(buffer.getLineContent(anchor), (level - 1) * tabSize, tabSize)
        guides.add(IndentGuide(startLine, endLine, column, anchor))
    }

    // A blank line (-1) takes the smaller indentation of the lines around it that are
    // not blank; at the edges of the text, that of the one neighbour there is.
    private fun fillBlankLines(indents: IntArray) {
        var i = 0
        while (i < indents.size) {
            if (indents[i] >= 0) {
                i++
                continue
            }
            var end = i
            while (end < indents.size && indents[end] < 0) end++
            val above = if (i > 0) indents[i - 1] else -1
            val below = if (end < indents.size) indents[end] else -1
            val fill = when {
                above < 0 -> Math.max(0, below)
                below < 0 -> above
                else -> Math.min(above, below)
            }
            for (j in i until end) indents[j] = fill
            i = end
        }
    }
}
