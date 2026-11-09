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

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * The little of Markdown the model's answers mostly use, as spans: fenced code blocks and
 * `inline code` in the console's monospace on a tinted ground, **bold**, # headings (bold,
 * a little larger), - and * list items as bullets. The rest stays as it is written. A code
 * block not yet closed (still streaming in) is shown as code already.
 */
object AgentMarkdown {

    fun render(text: String, mono: Typeface, codeBackground: Int): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.split('\n')
        var codeStart = -1
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (codeStart < 0) {
                    codeStart = out.length
                } else {
                    code(out, codeStart, mono, codeBackground)
                    codeStart = -1
                }
                continue
            }
            if (codeStart >= 0) {
                out.append(line).append('\n')
                continue
            }
            val start = out.length
            when {
                trimmed.startsWith("#") && trimmed.trimStart('#').startsWith(" ") -> {
                    inline(out, trimmed.trimStart('#').trim(), mono, codeBackground)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(1.08f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    out.append(line.substring(0, line.length - trimmed.length)).append("• ")
                    inline(out, trimmed.substring(2), mono, codeBackground)
                }
                else -> inline(out, line, mono, codeBackground)
            }
            if (index < lines.lastIndex) out.append('\n')
        }
        if (codeStart >= 0) code(out, codeStart, mono, codeBackground)
        // no blank line left at the end of a code block that ends the text
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun code(out: SpannableStringBuilder, start: Int, mono: Typeface, background: Int) {
        if (out.length <= start) return
        out.setSpan(MonoSpan(mono), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(background), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // `code` and **bold** in a line
    private fun inline(out: SpannableStringBuilder, line: String, mono: Typeface, background: Int) {
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '`') {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(line, i + 1, end)
                    out.setSpan(MonoSpan(mono), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(background), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            if (c == '*' && line.startsWith("**", i)) {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    val start = out.length
                    out.append(line, i + 2, end)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    /** A typeface of its own for a span (TypefaceSpan takes one only from Android 9). */
    class MonoSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) {
            paint.typeface = typeface
        }

        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
        }
    }
}
