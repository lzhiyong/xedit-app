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

package x.editor.app.highlight

import android.graphics.Color

import androidx.core.graphics.ColorUtils

/**
 * Hexadecimal colors in text (`#D4D4D4`, `#fff`, `#80FF0000`), to be shown in their
 * own color, as nvim-highlight-colors does: the text on a background of the color,
 * in black or white, whichever reads better on it.
 *
 * What counts as one: `#` and 3, 6 or 8 hex digits, not glued to a word on either
 * side (`a#fff`, `#fffe`, `&#123;` are not). Three digits only with a letter among
 * them, so that `#123` (an issue, a heading number) is not taken for a color.
 *
 * Eight digits are `#AARRGGBB` as on Android (XML, Kotlin, Java), or `#RRGGBBAA` as
 * in CSS ([alphaLast]). The background is drawn opaque, without the alpha: a
 * see-through swatch over the editor would show neither the color nor the text.
 */
object HexColors {

    /** A color in text: its chars [start, end) and the color, opaque. */
    class Match(val start: Int, val end: Int, val color: Int) {
        /** Black or white, whichever reads better on [color]. */
        val textColor: Int
            get() = if (ColorUtils.calculateLuminance(color) > 0.4) Color.BLACK else Color.WHITE
    }

    // the grammars whose eight digit colors are #RRGGBBAA
    private val CSS_LIKE = setOf(
        "css", "scss", "sass", "less", "html", "svelte", "vue", "astro",
        "javascript", "typescript", "tsx", "jsx", "svg"
    )

    /** Whether the eight digit colors of the grammar [language] have the alpha last. */
    fun isAlphaLast(language: String?): Boolean = language != null && language in CSS_LIKE

    /**
     * The colors in [text] that reach into [start, end) (a part of a line that is
     * drawn), whole even where they stick out of it. Looks only a little way beyond
     * the part, never at the whole of a long line.
     */
    fun find(text: CharSequence, start: Int, end: Int, alphaLast: Boolean): List<Match> {
        val from = (start - LONGEST).coerceAtLeast(0)
        val to = minOf(text.length, end + LONGEST)
        var result: ArrayList<Match>? = null
        var index = text.indexOf('#', from)
        while (index in 0 until to) {
            val match = matchAt(text, index, alphaLast)
            if (match != null) {
                if (match.end > start && match.start < end) {
                    (result ?: ArrayList<Match>().also { result = it }) += match
                }
                index = text.indexOf('#', match.end)
            } else {
                index = text.indexOf('#', index + 1)
            }
        }
        return result ?: emptyList()
    }

    // the longest color: # and 8 digits
    private const val LONGEST = 9

    // The color whose # is at [hash]; null if there is none there.
    private fun matchAt(text: CharSequence, hash: Int, alphaLast: Boolean): Match? {
        if (hash > 0 && isGlued(text[hash - 1])) return null
        var end = hash + 1
        while (end < text.length && end - hash <= 8 && isHex(text[end])) end++
        val digits = end - hash - 1
        if (digits != 3 && digits != 6 && digits != 8) return null
        // more hex digits, or a word going on
        if (end < text.length && (isHex(text[end]) || isWordChar(text[end]))) return null

        var hasLetter = false
        var value = 0L
        for (i in hash + 1 until end) {
            val c = text[i]
            if (!c.isDigit()) hasLetter = true
            value = (value shl 4) or Character.digit(c, 16).toLong()
        }
        val rgb = when (digits) {
            3 -> {
                if (!hasLetter) return null
                val r = (value shr 8 and 0xFL).toInt()
                val g = (value shr 4 and 0xFL).toInt()
                val b = (value and 0xFL).toInt()
                (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            6 -> value.toInt()
            // RRGGBBAA, or AARRGGBB: the color without the alpha
            else -> if (alphaLast) (value shr 8).toInt() else (value and 0xFFFFFFL).toInt()
        }
        return Match(hash, end, rgb or (0xFF shl 24))
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    // what may not come right before the #: part of a word, or of an HTML entity (&#123;)
    private fun isGlued(c: Char): Boolean = isWordChar(c) || c == '&'
}
