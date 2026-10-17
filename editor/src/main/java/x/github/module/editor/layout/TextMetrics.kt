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

package x.github.module.editor.layout

import android.graphics.Typeface
import android.text.TextPaint


/**
 * Everything that is measured from the text paint: line height, baseline, the width of
 * a piece of text, and the gutter / padding sizes derived from them.
 *
 * The values that only depend on the font (line height, baseline, the width of a digit
 * and of the spacing unit) are measured once and reused. They used to be re-measured on
 * every call, several times per visible line per frame.
 *
 * The cache checks itself: it is tagged with the text size and typeface it was measured
 * for and is thrown away as soon as the paint no longer matches, so changing
 * `paint.textSize` or `paint.typeface` needs no extra call. Any *other* paint property
 * that changes glyph sizes (letter spacing, scale, font features) needs [invalidate].
 *
 * Safe to read from a worker thread (the layouts do): the cache is one immutable
 * snapshot behind a volatile reference.
 */
class TextMetrics(private val paint: TextPaint) : TextMeasurer {

    private class FontSnapshot(
        // what the snapshot was measured for
        val textSize: Float,
        val typeface: Typeface?,
        val lineHeight: Int,
        // distance from the vertical middle of a line down to its baseline
        val baselineShift: Int,
        val digitWidth: Int,
        val spacingWidth: Int,
        // the width of a space, the unit of the tab stops
        val spaceWidth: Float
    )

    @Volatile
    private var snapshot: FontSnapshot? = null

    private fun font(): FontSnapshot {
        // read the tags first: if the paint changes while measuring, the snapshot is
        // tagged with the old values and simply gets replaced on the next call
        val textSize = paint.textSize
        val typeface = paint.typeface

        val cached = snapshot
        if (cached != null && cached.textSize == textSize && cached.typeface === typeface) {
            return cached
        }

        val fontMetrics = paint.fontMetricsInt
        return FontSnapshot(
            textSize = textSize,
            typeface = typeface,
            lineHeight = fontMetrics.bottom - fontMetrics.top,
            baselineShift = (fontMetrics.descent - fontMetrics.ascent) / 2 - fontMetrics.descent,
            digitWidth = widestDigit(),
            // the glyph of a tab as the font has it: the margins of the line numbers
            // (not measureText, which gives a tab its width up to the next stop)
            spacingWidth = paint.measureText("\t").toInt(),
            spaceWidth = paint.measureText(" ")
        ).also { snapshot = it }
    }

    // The width of the widest digit. In a proportional font the digits differ ("1"
    // is narrow), so line numbers are not laid out by their own widths but on a grid
    // of cells this wide, one digit in the middle of each: 20 and 21 take the same
    // room, and the ones, tens and hundreds stand in columns.
    private fun widestDigit(): Int {
        var widest = 0f
        for (digit in '0'..'9') {
            widest = Math.max(widest, paint.measureText(digit.toString()))
        }
        return Math.ceil(widest.toDouble()).toInt()
    }

    /** Forces a re-measure, for paint changes other than text size and typeface. */
    fun invalidate() {
        snapshot = null
    }

    /** The current text size of the paint, in pixels. */
    override val textSize: Float get() = paint.textSize

    /** The height of one visual line. */
    override val lineHeight: Int get() = font().lineHeight

    /** The width of the widest digit; line numbers are laid out on a grid of cells this wide. */
    val digitWidth: Int get() = font().digitWidth

    /** The unit of horizontal spacing (the width of a whitespace character). */
    val spacingWidth: Int get() = font().spacingWidth

    /** The empty room after the end of the text. */
    val endPadding: Int get() = spacingWidth * 4

    /**
     * The y of the baseline of the given visual line (1-based), from the top of the text.
     *
     * middle of the line = ((line * lineHeight) + (line - 1) * lineHeight) / 2
     * middle to baseline = (descent - ascent) / 2 - descent
     */
    fun baselineOf(visualLine: Int): Int = font().run {
        (visualLine * 2 - 1) * lineHeight / 2 + baselineShift
    }

    /**
     * Whether the gutter has room for fold markers right of the line numbers (see
     * [foldMarkerWidth]). Set by the editor when folding is on.
     */
    @Volatile
    var foldMarkerZone: Boolean = false

    /** The width of the room for fold markers in the gutter; 0 without it. Grows with the text size. */
    val foldMarkerWidth: Int get() = if (foldMarkerZone) font().lineHeight * 3 / 4 else 0

    /**
     * Empty room between the line numbers and the text, in pixels: set by the editor
     * while the line numbers are in a gutter that stays put, so that a handle at the
     * start of a line has room to hang out to the left of it. Part of [gutterWidth].
     */
    @Volatile
    var gutterGap: Int = 0

    /**
     * Whether the line numbers are shown. Without them their room goes, and what is
     * left of the start of a row is a margin, the fold markers and the [gutterGap].
     * Set by the editor.
     */
    @Volatile
    var lineNumbersVisible: Boolean = true

    /**
     * The line numbers get room for at least this many digits. The editor only lets
     * it grow while a text is edited (see EditorView), so that typing across 999 /
     * 1000 lines does not move the text back and forth, and with word wrap lays the
     * lines out again at most once per extra digit; it is reset when a text is loaded.
     */
    @Volatile
    var minLineNumberDigits: Int = 1

    /**
     * Where the text starts, for a document of [lineCount] lines:
     *
     *     [margin][line number][margin][fold marker][2 margins][gap]
     *
     * a margin being [spacingWidth]; without line numbers the number and the margin
     * after it are left out, without folding the fold marker.
     */
    fun gutterWidth(lineCount: Int): Int = font().run {
        if (lineNumbersVisible) {
            Math.max(lineCount.digitCount, minLineNumberDigits) * digitWidth + spacingWidth * 4
        } else {
            spacingWidth * 3
        }
    } + foldMarkerWidth + gutterGap

    /** The width of a line number: as many cells as it has digits, see [digitWidth]. */
    fun lineNumberWidth(lineNumber: Int): Int = Math.max(1, lineNumber).digitCount * digitWidth

    /**
     * How many spaces wide a tab stop is (4 by default): a tab reaches to the next
     * multiple of that, from the start of the row. Set by the editor (setTabSize).
     */
    @Volatile
    var tabSize: Int = 4

    override val tabWidth: Float get() = font().spaceWidth * tabSize

    /** The width of [text], measured from its start: tabs up to their stops (see TabStops). */
    override fun measureText(text: String): Int {
        if (text.indexOf('\t') < 0) return paint.measureText(text).toInt()
        var width = 0f
        for (advance in getTextWidths(text, 0, text.length)) {
            width += advance
        }
        return width.toInt()
    }

    /** The advance width of each char of text[start, end); a tab up to its stop, counted from [start]. */
    override fun getTextWidths(text: String, start: Int, end: Int): FloatArray {
        return FloatArray(end - start).also { widths ->
            paint.getTextWidths(text, start, end, widths)
            if (TabStops.hasTab(text, start, end)) {
                TabStops.applyTo(text, start, end, widths, tabWidth)
            }
        }
    }
}

// the number of decimal digits of a positive number
public val Int.digitCount: Int
    get() = Math.log10(this.toDouble()).toInt() + 1
