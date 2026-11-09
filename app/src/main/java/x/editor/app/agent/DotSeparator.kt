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

package x.editor.app.agent

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * Parts of a line joined by " · ", with the dot as far from the ink of the part before it as
 * from the ink of the part after it. Two spaces around "·" do not do that: a glyph's advance
 * has room of its own on either side, and that room differs from glyph to glyph. A
 * full-width "）" (Anthropic（Claude）, in Chinese) leaves most of its em empty on its right,
 * so the dot after it seemed much farther from it than from the model's name after the dot.
 * [Span] measures the ink of the characters on either side and makes up for it.
 */
internal object DotSeparator {

    /** What stands in for each separator in the text (a span draws it). */
    private const val PLACEHOLDER = " · "

    /** [parts] (blank ones left out) joined; [plain] the same as text, to compare it with. */
    class Joined(val text: CharSequence, val plain: String)

    fun join(parts: List<String>): Joined {
        val kept = parts.map { it.trim() }.filter { it.isNotEmpty() }
        val builder = SpannableStringBuilder()
        kept.forEachIndexed { index, part ->
            if (index > 0) {
                val start = builder.length
                builder.append(PLACEHOLDER)
                builder.setSpan(Span(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builder.append(part)
        }
        return Joined(builder, kept.joinToString(PLACEHOLDER))
    }

    /**
     * The dot, a space's width (of the text's own font) from the ink of the character before
     * it and as far from the ink of the one after it. The room a glyph has beside its ink is
     * taken from its advance less its bounds; the dot may thus be drawn into the empty right
     * part of the glyph before it.
     */
    class Span : ReplacementSpan() {

        private val bounds = Rect()

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            // the line as high as the text's font makes it, as if the dot were text
            if (fm != null) paint.getFontMetricsInt(fm)
            return layoutOf(paint, text, start, end).width.roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val layout = layoutOf(paint, text, start, end)
            canvas.drawText(DOT, x + layout.dotX, y.toFloat(), paint)
        }

        // where the dot is drawn (from the span's start, as text: its advance's start) and how
        // wide the span is
        private class Layout(val dotX: Float, val width: Float)

        private fun layoutOf(paint: Paint, text: CharSequence, start: Int, end: Int): Layout {
            val gap = paint.measureText(" ")
            // the dot's ink, within its advance
            paint.getTextBounds(DOT, 0, DOT.length, bounds)
            val dotInkLeft = bounds.left.toFloat()
            val dotInk = bounds.width().toFloat()
            // the room on the right of the character before, and on the left of the one after
            val before = if (start > 0) {
                val at = Character.offsetByCodePoints(text, start, -1)
                roomRightOf(paint, text, at, start)
            } else 0f
            val after = if (end < text.length) {
                val next = Character.offsetByCodePoints(text, end, 1)
                roomLeftOf(paint, text, end, next)
            } else 0f
            val inkLeft = gap - before
            // Not held at the dot's ink: the dot is drawn into the room left by the
            // character before it (inkLeft is then negative), and the span's advance is only
            // what is left of it, which may well be less than the dot is wide. Held there,
            // the advance was too long by the difference and everything after the dot was
            // pushed that much further right: after a full-width "）" (Anthropic（Claude）)
            // the room after the dot came out half as wide again as the room before it.
            val width = (inkLeft + dotInk + gap - after).coerceAtLeast(0f)
            return Layout(inkLeft - dotInkLeft, width)
        }

        // the empty room after the ink of text[from, to) (a space: none, it is all room)
        private fun roomRightOf(paint: Paint, text: CharSequence, from: Int, to: Int): Float {
            val glyph = text.subSequence(from, to).toString()
            if (glyph.isBlank()) return 0f
            // (the String overload: the CharSequence one is from Android 10 on)
            paint.getTextBounds(glyph, 0, glyph.length, bounds)
            if (bounds.isEmpty) return 0f
            return (paint.measureText(glyph) - bounds.right).coerceAtLeast(0f)
        }

        // the empty room before the ink of text[from, to)
        private fun roomLeftOf(paint: Paint, text: CharSequence, from: Int, to: Int): Float {
            val glyph = text.subSequence(from, to).toString()
            if (glyph.isBlank()) return 0f
            paint.getTextBounds(glyph, 0, glyph.length, bounds)
            if (bounds.isEmpty) return 0f
            return bounds.left.toFloat().coerceAtLeast(0f)
        }

        private companion object {
            const val DOT = "·"
        }
    }
}
