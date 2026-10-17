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
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint

import x.github.module.piecetable.common.Range

import x.github.module.editor.EditorGeometry
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.layout.digitCount
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.util.findOverlapping

/**
 * Paints everything on a line that is not the text itself: the current-line
 * background, the selection, the search matches, the matching brackets, the indent
 * guides and the line number. (The fold markers and placeholders: see [FoldStyle].)
 *
 * It owns its paints. The text paint is also what text is *measured* with, so it is
 * never modified here (it used to have its color and letter spacing changed and
 * restored around every rectangle and line number).
 *
 * The selection and the search matches are the same thing to this class, a [Range]
 * filled with a color, and go through the same code ([fillRange]), so the two can no
 * longer drift apart by a pixel.
 *
 * All drawing happens in content coordinates, on a canvas already translated by the
 * view's padding, one visual line at a time.
 */
class LineDecorationPainter(
    private val geometry: EditorGeometry,
    private val document: EditorDocument,
    private val metrics: TextMetrics,
    // read only: line numbers copy their size and typeface from it
    private val textPaint: TextPaint,
    // the layout can be swapped (word wrap on/off), so it is asked for on every use
    private val currentLayout: () -> TextLayout
) {

    var currentLineColor: Int = Color.argb(68, 204, 204, 204)
    var selectionColor: Int = Color.argb(136, 204, 204, 204)
    var searchMatchColor: Int = Color.CYAN
    /** The match the user is at, drawn over [searchMatchColor]. */
    var currentMatchColor: Int = Color.argb(255, 255, 152, 0)
    var lineNumberColor: Int = Color.GRAY
    /** The number of the cursor's line; null is the color of the text. */
    var currentLineNumberColor: Int? = null
    /** The underline of text being composed; null means the color of the text. */
    var composingUnderlineColor: Int? = null
    /** The background of a bracket and its partner. */
    var bracketMatchColor: Int = Color.argb(72, 128, 128, 128)
    /** The line under a bracket and its partner; null is the color of the text. */
    var bracketUnderlineColor: Int? = null
    /** Indent guides; null: the line number color, faint. */
    var indentGuideColor: Int? = null
    /** The indent guide of the block the cursor is in; null: the line number color. */
    var activeIndentGuideColor: Int? = null

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // the rectangle of a range on one visual line, see computeRangeRect
    private val rangeRect = android.graphics.RectF()
    private val lineNumberPaint = TextPaint()

    /** Highlights the whole width of the visual line the cursor is on. */
    fun drawCurrentLine(canvas: Canvas, visualLine: Int) {
        val lineHeight = metrics.lineHeight
        fillPaint.color = currentLineColor
        canvas.drawRect(
            geometry.viewScrollX.toFloat(),
            ((visualLine - 1) * lineHeight).toFloat(),
            (geometry.viewScrollX + geometry.viewWidth).toFloat(),
            (visualLine * lineHeight).toFloat(),
            fillPaint
        )
    }

    /**
     * Fills [visualLine] with [color] from a margin left of where the text starts
     * (right of the line numbers) to the right edge of the view: the background of
     * a block, see LineStyler.lineBackground. Scrolled sideways, it starts at the
     * left edge; a gutter that stays put is drawn over it.
     */
    fun drawLineFill(canvas: Canvas, visualLine: Int, color: Int) {
        val lineHeight = metrics.lineHeight
        val textLeft = gutterWidth() - metrics.gutterGap - metrics.spacingWidth
        val left = Math.max(geometry.viewScrollX, Math.max(0, textLeft))
        val right = geometry.viewScrollX + geometry.viewWidth
        if (right <= left) return
        fillPaint.color = color
        canvas.drawRect(
            left.toFloat(),
            ((visualLine - 1) * lineHeight).toFloat(),
            right.toFloat(),
            (visualLine * lineHeight).toFloat(),
            fillPaint
        )
    }

    /** Paints the part of [selection] that lies on [visualLine], if any. */
    fun drawSelection(
        canvas: Canvas, selection: Range, visualLine: Int,
        // A selection that is still growing out from a point: how much of it is
        // there (1 = all of it), and the x it grows out from. See
        // SelectionController.selectionRevealFraction.
        revealFraction: Float = 1f, revealPivotX: Int = 0
    ) {
        // a selected line break shows as a little extra width after the line
        fillRange(
            canvas, selection, visualLine, selectionColor, lineEndExtra = metrics.spacingWidth,
            revealFraction = revealFraction, revealPivotX = revealPivotX
        )
    }

    /**
     * Paints the search matches that lie on [visualLine].
     *
     * @param matches all matches of the document, sorted
     * @param bufferLine the buffer line [visualLine] belongs to
     * @param startColumn first column shown on the visual line
     * @param endColumn column after the last one shown on the visual line
     */
    fun drawSearchMatches(
        canvas: Canvas, matches: List<Range>, bufferLine: Int, visualLine: Int, startColumn: Int, endColumn: Int
    ) {
        if (matches.isEmpty()) return
        // here requests buffer line number
        matches.findOverlapping(bufferLine, startColumn, bufferLine, endColumn).forEach { match ->
            fillRange(canvas, match, visualLine, searchMatchColor, lineEndExtra = 0)
        }
    }

    /** Paints the part of the current match that lies on [visualLine], if any. */
    fun drawCurrentMatch(canvas: Canvas, match: Range, visualLine: Int) {
        fillRange(canvas, match, visualLine, currentMatchColor, lineEndExtra = 0)
    }

    /**
     * Underlines the part of [composing] that lies on [visualLine]: the text the
     * input method is still working on (pinyin not yet turned into characters, a
     * word being completed).
     */
    fun drawComposingUnderline(canvas: Canvas, composing: Range, visualLine: Int) {
        val thickness = Math.max(2, metrics.lineHeight / 14)
        fillRange(canvas, composing, visualLine, composingUnderlineColor ?: textPaint.color, 0, thickness)
    }

    /**
     * Fills the background of [bracket] (one char), where it lies on [visualLine]: a
     * bracket at the cursor, or its partner. (The bracket itself is drawn bold over
     * the text, see EditorView.drawBracketText.)
     */
    fun drawBracketMatch(canvas: Canvas, bracket: Range, visualLine: Int) {
        fillRange(canvas, bracket, visualLine, bracketMatchColor, 0)
    }

    /**
     * Draws a line under a bracket (or its partner) from [left] to [right], a little
     * below the [baseline], as thick as the underline of underlined text and a bit
     * more, so that it reads as a mark and not as a style.
     */
    fun drawBracketUnderline(canvas: Canvas, left: Float, right: Float, baseline: Float) {
        val offset = Math.max(1f, textPaint.descent() / 3f)
        val thickness = Math.max(1.5f, textPaint.textSize / 12f)
        fillPaint.color = bracketUnderlineColor ?: textPaint.color
        canvas.drawRect(left, baseline + offset, right, baseline + offset + thickness, fillPaint)
    }


    /**
     * Draws an indent guide from [top] to [bottom] at [x] (content coordinates); [active]
     * for the guide of the block the cursor is in.
     */
    fun drawIndentGuide(canvas: Canvas, x: Float, top: Float, bottom: Float, active: Boolean) {
        val faint = (lineNumberColor and 0x00FFFFFF) or (0x50 shl 24)
        fillPaint.color = if (active) activeIndentGuideColor ?: lineNumberColor else indentGuideColor ?: faint
        val width = Math.max(1f, metrics.lineHeight / 24f)
        canvas.drawRect(x, top, x + width, bottom, fillPaint)
    }

    /**
     * Draws the number of [line] right-aligned in the room for line numbers at the
     * start of its row (line numbers in the rows, no gutter), on the baseline [y].
     * Digits are put on a grid of equal cells, so numbers line up in proportional
     * fonts too (see LineNumberText). The number of the cursor's line ([current]) in
     * [currentLineNumberColor], the others in [lineNumberColor], as in the gutter.
     */
    fun drawLineNumber(canvas: Canvas, line: Int, y: Float, current: Boolean = false) {
        // right of the number: a margin, the fold markers (if any) and two margins
        val right = gutterWidth() - metrics.gutterGap - metrics.foldMarkerWidth - metrics.spacingWidth * 3f

        // same font as the text, in its own paint
        lineNumberPaint.set(textPaint)
        lineNumberPaint.color = if (current) currentLineNumberColor ?: textPaint.color else lineNumberColor

        LineNumberText.draw(canvas, line, right, y, lineNumberPaint, metrics.digitWidth.toFloat())
    }

    /**
     * Where the fold marker of a line goes when the line numbers are in the rows: its
     * left and right edge in content x. Right of the number, see [drawLineNumber].
     */
    fun rowFoldMarkerLeft(): Int =
        gutterWidth() - metrics.gutterGap - metrics.foldMarkerWidth - metrics.spacingWidth * 2

    private fun gutterWidth() = metrics.gutterWidth(document.getLineCount())

    /**
     * Fills the part of [range] that lies on [visualLine] with [color]; does nothing
     * when the range does not reach that line.
     *
     * A range spanning several visual lines is cut at their edges: it starts at its
     * own start column only on its first line, ends at its own end column only on its
     * last line, and covers the full line text (plus [lineEndExtra]) anywhere it
     * continues onto the next line.
     */
    private fun fillRange(
        canvas: Canvas, range: Range, visualLine: Int, color: Int, lineEndExtra: Int,
        // how much of the line is filled, measured up from its bottom; the whole line by default
        thickness: Int = metrics.lineHeight,
        // below 1, the rectangle is shrunk towards revealPivotX by that much
        revealFraction: Float = 1f, revealPivotX: Int = 0
    ) {
        if (!computeRangeRect(range, visualLine, lineEndExtra, thickness, revealFraction, revealPivotX)) return
        fillPaint.color = color
        canvas.drawRect(rangeRect, fillPaint)
    }

    // Puts the rectangle of the part of [range] on [visualLine] into rangeRect; false
    // when the range does not reach that line. See fillRange for the parameters.
    private fun computeRangeRect(
        range: Range, visualLine: Int, lineEndExtra: Int, thickness: Int,
        revealFraction: Float = 1f, revealPivotX: Int = 0
    ): Boolean {
        val layout = currentLayout()
        val firstLine = layout.getIndexAt(range.startLine, range.startColumn) + 1
        // the end column is exclusive: look up the last column that is actually inside,
        // so a range ending exactly at a wrap point stays on the line it ends on
        val lastLine = layout.getIndexAt(range.endLine, range.endColumn - 1) + 1

        if (visualLine < firstLine || visualLine > lastLine) return false

        val lineResult = layout.getLineResult(visualLine)
        val textLeft = gutterWidth()
        // first column shown on this visual line
        val lineStartColumn = lineResult.start + 1

        var left = when (visualLine) {
            firstLine -> textLeft + measureSegment(lineResult.line, lineStartColumn, range.startColumn)
            else -> textLeft
        }
        var right = when (visualLine) {
            lastLine -> textLeft + measureSegment(lineResult.line, lineStartColumn, range.endColumn)
            else -> textLeft + lineResult.width + lineEndExtra
        }

        if (revealFraction < 1f) {
            // growing out from the pivot: both edges are part of the way there
            val pivot = revealPivotX.coerceIn(left, Math.max(left, right))
            left = pivot - ((pivot - left) * revealFraction).toInt()
            right = pivot + ((right - pivot) * revealFraction).toInt()
        }

        val lineHeight = metrics.lineHeight
        rangeRect.set(
            left.toFloat(),
            (visualLine * lineHeight - thickness).toFloat(),
            right.toFloat(),
            (visualLine * lineHeight).toFloat()
        )
        return true
    }

    private fun measureSegment(lineNumber: Int, startColumn: Int, endColumn: Int): Int {
        return metrics.measureText(document.getLineSegment(lineNumber, startColumn, endColumn))
    }
}
