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

package x.editor.app.lsp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * The marks of HoverMarkdown as spans, in the colors of [Palette]: those of the editor's
 * highlight theme for the code (see LspController), the accent for links and `code`.
 */
object HoverSpans {

    data class Palette(
        val keyword: Int,
        val string: Int,
        val comment: Int,
        val number: Int,
        val type: Int,
        val function: Int,
        /** links, inline code */
        val accent: Int,
        /** behind code */
        val codeBackground: Int,
        /** rules */
        val dim: Int,
    )

    fun spans(rendered: HoverMarkdown.Rendered, palette: Palette): SpannableStringBuilder {
        val out = SpannableStringBuilder(rendered.text)
        fun set(span: Any, start: Int, end: Int) = out.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (mark in rendered.marks) {
            val (start, end) = mark.start to mark.end
            when (mark.style) {
                HoverMarkdown.Style.HEADING -> {
                    set(StyleSpan(Typeface.BOLD), start, end)
                    set(RelativeSizeSpan(1.1f), start, end)
                }
                HoverMarkdown.Style.BOLD -> set(StyleSpan(Typeface.BOLD), start, end)
                HoverMarkdown.Style.ITALIC -> set(StyleSpan(Typeface.ITALIC), start, end)
                HoverMarkdown.Style.CODE -> {
                    set(TypefaceSpan("monospace"), start, end)
                    set(ForegroundColorSpan(palette.accent), start, end)
                    set(BackgroundColorSpan(palette.codeBackground), start, end)
                }
                HoverMarkdown.Style.CODE_BLOCK -> {
                    set(TypefaceSpan("monospace"), start, end)
                    // a paragraph span: from the start of a line to after its line break
                    val to = if (end < out.length && out[end] == '\n') end + 1 else end
                    if (start == 0 || out[start - 1] == '\n') {
                        out.setSpan(CodeBlockBackground(palette.codeBackground), start, to, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    }
                }
                HoverMarkdown.Style.LINK -> {
                    set(ForegroundColorSpan(palette.accent), start, end)
                    set(UnderlineSpan(), start, end)
                }
                HoverMarkdown.Style.RULE -> set(ForegroundColorSpan(palette.dim), start, end)
                HoverMarkdown.Style.KEYWORD -> set(ForegroundColorSpan(palette.keyword), start, end)
                HoverMarkdown.Style.STRING -> set(ForegroundColorSpan(palette.string), start, end)
                HoverMarkdown.Style.COMMENT -> {
                    set(ForegroundColorSpan(palette.comment), start, end)
                    set(StyleSpan(Typeface.ITALIC), start, end)
                }
                HoverMarkdown.Style.NUMBER -> set(ForegroundColorSpan(palette.number), start, end)
                HoverMarkdown.Style.TYPE -> set(ForegroundColorSpan(palette.type), start, end)
                HoverMarkdown.Style.FUNCTION -> set(ForegroundColorSpan(palette.function), start, end)
            }
        }
        return out
    }

    /** Behind every line of a code block, the width of the text across. */
    class CodeBlockBackground(private val color: Int) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val old = paint.color
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            paint.color = old
        }
    }
}
