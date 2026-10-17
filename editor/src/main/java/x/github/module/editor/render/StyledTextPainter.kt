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

import x.github.module.editor.layout.TabStops
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * Paints a piece of a line with the styles a [LineStyler] gave it.
 *
 * The text is cut into runs wherever a style begins or ends, and each run is painted
 * in turn. This is much cheaper than handing the line to a StaticLayout, and all a
 * code editor needs.
 *
 * It paints with a paint of its own. The editor's text paint is also what text is
 * measured with, possibly on another thread at the same moment, so it is only read
 * here (copied once per call), never changed.
 */
class StyledTextPainter {

    private val paint = TextPaint()

    // the advance of each char that is painted; grows as needed, never shrinks
    private var widths = FloatArray(256)

    /**
     * Paints text[start, end) with [spans].
     *
     * @param x where the text starts
     * @param baseline the baseline of the row
     * @param rowTop the top of the row, for backgrounds
     * @param rowBottom the bottom of the row
     * @param basePaint the editor's text paint: size, typeface and the color of
     *        text that has no color of its own
     */
    /**
     * How far apart the tab stops are (TextMetrics.tabWidth); [draw]'s [start] is the
     * start of the row they count from. 0: tabs as wide as their glyph.
     */
    var tabWidth: Float = 0f

    fun draw(
        canvas: Canvas, text: String, spans: Spanned, start: Int, end: Int,
        x: Float, baseline: Float, rowTop: Float, rowBottom: Float, basePaint: TextPaint,
        // everything bold, whatever the spans say (a matching bracket)
        forceBold: Boolean = false
    ) {
        if (end <= start) return

        if (widths.size < end - start) {
            widths = FloatArray(Math.max(end - start, widths.size * 2))
        }
        // measured in the plain style: bold and italic here are drawn by thickening
        // and slanting the same glyphs, which leaves their advances as they are
        basePaint.getTextWidths(text, start, end, widths)
        val hasTabs = tabWidth > 0f && TabStops.hasTab(text, start, end)
        if (hasTabs) {
            TabStops.applyTo(text, start, end, widths, tabWidth)
        }

        paint.set(basePaint)
        val plainColor = basePaint.color
        val underlineOffset = Math.max(1f, paint.descent() / 3f)
        val underlineThickness = Math.max(1f, paint.textSize / 14f)

        var runStart = start
        var runX = x
        while (runStart < end) {
            // a run ends where any style begins or ends (or at the end of the piece)
            val runEnd = spans.nextSpanTransition(runStart, end, CharacterStyle::class.java)
                .coerceIn(runStart + 1, end)

            var runWidth = 0f
            for (index in runStart until runEnd) {
                runWidth += widths[index - start]
            }

            // background color
            spans.getSpans(runStart, runEnd, BackgroundColorSpan::class.java).firstOrNull()?.let {
                paint.color = it.backgroundColor
                canvas.drawRect(runX, rowTop, runX + runWidth, rowBottom, paint)
            }

            // foreground color
            paint.color = spans.getSpans(runStart, runEnd, ForegroundColorSpan::class.java)
                .firstOrNull()?.foregroundColor ?: plainColor

            // bold, italic
            val style = spans.getSpans(runStart, runEnd, StyleSpan::class.java).firstOrNull()?.style
                ?: Typeface.NORMAL
            paint.isFakeBoldText = forceBold || style == Typeface.BOLD || style == Typeface.BOLD_ITALIC
            paint.textSkewX = if (style == Typeface.ITALIC || style == Typeface.BOLD_ITALIC) -0.2f else 0f

            // the text; a tab is only room, up to its stop
            if (hasTabs && TabStops.hasTab(text, runStart, runEnd)) {
                drawAroundTabs(canvas, text, runStart, runEnd, runX, baseline, start)
            } else {
                canvas.drawText(text, runStart, runEnd, runX, baseline, paint)
            }

            // underline: a little below the baseline, in the color of the text
            if (spans.getSpans(runStart, runEnd, UnderlineSpan::class.java).isNotEmpty()) {
                val top = baseline + underlineOffset
                canvas.drawRect(runX, top, runX + runWidth, top + underlineThickness, paint)
            }

            runX += runWidth
            runStart = runEnd
        }
    }

    // A run with tabs: the pieces between them drawn where the widths put them.
    // [rowStart] is where the widths start.
    private fun drawAroundTabs(
        canvas: Canvas, text: String, runStart: Int, runEnd: Int, runX: Float, baseline: Float, rowStart: Int
    ) {
        var pieceStart = runStart
        var pieceX = runX
        var x = runX
        for (index in runStart until runEnd) {
            if (text[index] == '\t') {
                if (index > pieceStart) canvas.drawText(text, pieceStart, index, pieceX, baseline, paint)
                x += widths[index - rowStart]
                pieceStart = index + 1
                pieceX = x
            } else {
                x += widths[index - rowStart]
            }
        }
        if (pieceStart < runEnd) canvas.drawText(text, pieceStart, runEnd, pieceX, baseline, paint)
    }
}
