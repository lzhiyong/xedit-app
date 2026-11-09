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

package x.github.module.editor.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint

import x.github.module.editor.EditorGeometry
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.structure.FoldingRange

/**
 * The gutter: the strip left of the text with the line numbers, which **stays put**
 * while the text scrolls sideways under it.
 *
 * It is not a View of its own. A View beside the editor would have to follow its
 * scrolling frame by frame, its text size when zooming, its rows when lines are
 * wrapped, its padding and the keyboard covering it, and would always be one
 * frame behind. Drawn by the editor in the same pass as the text, from the same
 * layout, it cannot be out of step.
 *
 * It takes the room the text leaves for line numbers anyway, left of the text, less a
 * gap between it and the text (TextMetrics.gutterGap) where a handle at the start of
 * a line can hang out. When the view is scrolled sideways, the text that has scrolled
 * under it is hidden, like the text past the right edge.
 *
 * What it draws, from the bottom up:
 *  - its background, so that the text under it does not show through;
 *  - [Decoration]s of the app's, one call per line on screen: a breakpoint, a
 *    changed-line marker, a fold arrow;
 *  - the line numbers, on a grid of equal cells so that they line up in any font
 *    ([LineNumberText]), the cursor's line in its own color; with word wrap, only on
 *    the first row of a line;
 *  - with folding on, a fold marker right of the number of each line a
 *    [FoldingRange] starts on, open or folded, in a column of its own
 *    ([Host.foldMarkerWidth]);
 *  - a thin line between it and the text.
 *
 * Canvas in content coordinates, as for everything else the editor draws.
 */
class Gutter(
    private val host: Host,
    // the text's paint: the numbers take its typeface and size
    private val textPaint: TextPaint
) {

    /** What the gutter reads from the editor. */
    interface Host : EditorGeometry {
        val textLayout: TextLayout
        /** The width of the gutter: up to the gap before the text. */
        val gutterWidth: Int
        /** How wide the widest line number is. */
        val lineNumberWidth: Int
        /** The width of one cell of the line number grid, see [LineNumberText]. */
        val digitWidth: Int
        /** Whether the line numbers are shown; without them the gutter has decorations and fold markers only. */
        val lineNumbersVisible: Boolean
        fun getLineHeight(): Int
        /** The baseline of the first row, in content coordinates: how far below the top of a row its baseline is. */
        fun getLineBaseline(row: Int): Int
        /** The buffer line the cursor is on. */
        val cursorLineNumber: Int
        /** The color of the line numbers. */
        val lineNumberColor: Int
        /** The color the gutter is filled with when none was set: the editor's background. */
        val defaultGutterBackground: Int
        /** The width of the column for fold markers at the right of the gutter; 0 for none. */
        val foldMarkerWidth: Int
        /** The range that can be folded from [line], if any. Asked for every line on screen. */
        fun foldingRangeAt(line: Int): FoldingRange?
        /** Whether the range starting on [line] is folded. */
        fun isFolded(line: Int): Boolean
        /** Draws the fold marker of [range] into the rectangle given, see [FoldStyle]. */
        fun drawFoldMarker(
            canvas: Canvas, range: FoldingRange, folded: Boolean,
            left: Float, top: Float, right: Float, bottom: Float
        )
    }

    /**
     * Something drawn on the gutter for a line, under its number. Called for every
     * line that is on screen, with the rectangle of its first row, in content
     * coordinates. Keep it quick: it runs in every frame.
     */
    fun interface Decoration {
        fun draw(canvas: Canvas, line: Int, left: Float, top: Float, right: Float, bottom: Float)
    }

    /** The background; null takes the editor's ([Host.defaultGutterBackground]). */
    var backgroundColor: Int? = null

    /** The line between the gutter and the text; null is the line number color, faint. */
    var dividerColor: Int? = null

    /** The number of the cursor's line; null is the color of the text. */
    var currentLineNumberColor: Int? = null

    /** Drawn under the numbers, see [Decoration]; null for none. */
    var decoration: Decoration? = null

    /** The fold markers; null is the line number color. */
    var foldMarkerColor: Int? = null

    private val fillPaint = Paint()
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dividerWidth = 1f

    fun draw(canvas: Canvas) {
        val width = host.gutterWidth
        if (width <= 0) return
        val layout = host.textLayout
        val lineHeight = host.getLineHeight()
        if (lineHeight <= 0) return

        val left = host.viewScrollX.toFloat()
        val right = left + width
        val top = host.viewScrollY.toFloat()
        val bottom = top + host.viewportHeight

        fillPaint.color = backgroundColor ?: host.defaultGutterBackground
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // the rows on screen
        val rowCount = layout.count()
        val firstRow = Math.max(1, host.viewScrollY / lineHeight + 1)
        val lastRow = Math.min(rowCount, (host.viewScrollY + host.viewportHeight) / lineHeight + 1)

        numberPaint.typeface = textPaint.typeface
        numberPaint.textSize = textPaint.textSize
        val numberColor = host.lineNumberColor
        val currentColor = currentLineNumberColor ?: textPaint.color
        val cursorLine = host.cursorLineNumber
        // the fold markers have a column of their own at the right
        val markerWidth = host.foldMarkerWidth
        // the numbers, right aligned, in a column as wide as the widest number,
        // in the middle of what is left
        val numberRight = left + (width - markerWidth + host.lineNumberWidth) / 2f
        val cellWidth = host.digitWidth.toFloat()
        val numbersVisible = host.lineNumbersVisible
        val decoration = this.decoration
        // where in a row its baseline is
        val baseline = host.getLineBaseline(1).toFloat()

        for (row in firstRow..lastRow) {
            // with word wrap a line takes several rows: its number is on the first
            if (layout.getLineStart(row) != 0) continue
            val line = layout.getBufferLine(row)
            val rowTop = (row - 1) * lineHeight.toFloat()
            decoration?.draw(canvas, line, left, rowTop, right, rowTop + lineHeight)
            numberPaint.color = if (line == cursorLine) currentColor else numberColor
            if (numbersVisible) {
                LineNumberText.draw(canvas, line, numberRight, rowTop + baseline, numberPaint, cellWidth)
            }
            if (markerWidth > 0) {
                host.foldingRangeAt(line)?.let { range ->
                    host.drawFoldMarker(
                        canvas, range, host.isFolded(line), right - markerWidth, rowTop, right, rowTop + lineHeight
                    )
                }
            }
        }

        fillPaint.color = dividerColor ?: ((numberColor and 0x00FFFFFF) or (0x40 shl 24))
        canvas.drawRect(right - dividerWidth, top, right, bottom, fillPaint)
    }

}
