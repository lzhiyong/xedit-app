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

package x.github.module.editor.util

import android.icu.text.BreakIterator

/**
 * Where characters and words begin and end inside a piece of text.
 *
 * "Character" here means a grapheme cluster, i.e. what the user sees as one character:
 * an emoji or a letter with combining marks is several chars long but must be stepped
 * over, selected and deleted as a whole.
 *
 * Pure functions of their arguments: no paint, no view, no buffer.
 */
object TextBoundaries {

    /** How many chars the character starting at [offset] takes up. */
    fun graphemeLength(text: String, offset: Int): Int = graphemeEnd(text, offset) - offset

    /** The start offset of the character that ends after [endOffset]. */
    fun graphemeStart(text: String, endOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // end boundary
            following(endOffset)
            // start boundary
            previous()
        }
    }

    /** The end offset of the character at [startOffset]. Requires startOffset < text.length. */
    fun graphemeEnd(text: String, startOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // end boundary
            following(startOffset)
        }
    }

    /**
     * The word around [offset].
     *
     * @return [start, end) offsets of the word; start == end when there is no word there
     */
    fun findWordBoundary(text: String, offset: Int): IntArray {
        return BreakIterator.getWordInstance().run {
            setText(text)
            // first to get the end boundary
            val end = following(offset)
            // then to get the start boundary
            val start = previous()
            if (end != BreakIterator.DONE)
                intArrayOf(start, end)
            else
                intArrayOf(start, start)
        }
    }

    // The three kinds of char that word-wise cursor movement tells apart: a run of
    // one kind is one stop.
    private fun kindOf(char: Char): Int = when {
        char.isWhitespace() -> 0
        char.isLetterOrDigit() || char == '_' -> 1
        else -> 2
    }

    /**
     * Where Ctrl+Left goes from [offset]: back over any whitespace, then to the start
     * of the word (or run of punctuation) before it. Needs no ICU, only the chars.
     */
    fun previousWordStart(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index > 0 && kindOf(text[index - 1]) == 0) {
            index--
        }
        if (index > 0) {
            val kind = kindOf(text[index - 1])
            while (index > 0 && kindOf(text[index - 1]) == kind) {
                index--
            }
        }
        return index
    }

    /**
     * Where Ctrl+Right goes from [offset]: over any whitespace, then to the end of
     * the word (or run of punctuation) after it.
     */
    fun nextWordEnd(text: String, offset: Int): Int {
        var index = offset.coerceIn(0, text.length)
        while (index < text.length && kindOf(text[index]) == 0) {
            index++
        }
        if (index < text.length) {
            val kind = kindOf(text[index])
            while (index < text.length && kindOf(text[index]) == kind) {
                index++
            }
        }
        return index
    }
}
