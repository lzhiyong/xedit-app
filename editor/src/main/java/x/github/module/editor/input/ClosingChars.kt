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

package x.github.module.editor.input

/**
 * Whether a completion that ends with a closing char (`>`, `)`, `]`, `}`, a quote) goes
 * over the same char right after it in the text, instead of putting a second one before it.
 *
 * The char after the cursor was put there before the completion: by a snippet
 * (`#include <$1>`), by auto pairs (`""`), or typed. A completion that brings its own (clangd
 * completes `#include <st` with `stdlib.h>`) would leave two: `<stdlib.h>>`. It goes over
 * it when, with the completion in, the line would have one closing char too many: no
 * bracket left open for it (`<stdlib.h>` has none; `bar(foo()` has one, and the `)` after it
 * stays), or a quote that would open a new string. As editors step over a closing char
 * that is typed where it already is.
 */
object ClosingChars {

    private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')
    private const val QUOTES = "\"'`"

    /**
     * Whether a completion that puts [inserted] in, after [lineBefore] (the line before
     * what it replaces), goes over [next] (the char after what it replaces; null: the end
     * of the line).
     */
    fun goesOver(lineBefore: String, inserted: String, next: Char?): Boolean {
        val last = inserted.lastOrNull() ?: return false
        if (next == null || last != next) return false
        // the line as it will be up to the end of what is put in
        val line = if ('\n' in inserted) inserted.substringAfterLast('\n') else lineBefore + inserted
        BRACKETS[last]?.let { open -> return depth(line, open, last) <= 0 }
        if (last in QUOTES) return closesString(line, last)
        return false
    }

    /**
     * Whether a completion that puts [inserted] in, after [lineBefore], takes in the
     * opening char just before it: the item brings its own (`<stdio.h>` put in place of
     * `st` in `#include <st`, by a server that gives no range) and would leave two,
     * `<<stdio.h>`. Only a char that opens something still open there: a bracket, or a
     * quote that opens a string.
     */
    fun takesOpening(lineBefore: String, inserted: String): Boolean {
        val first = inserted.firstOrNull() ?: return false
        if (lineBefore.lastOrNull() != first) return false
        val close = BRACKETS.entries.firstOrNull { it.value == first }?.key
        return when {
            close != null -> depth(lineBefore, first, close) > 0 && inserted.contains(close)
            first in QUOTES -> !closesString(lineBefore, first) && inserted.count { it == first } >= 2
            else -> false
        }
    }

    // how many [open] are still open at the end of [line] (quoted parts not counted)
    private fun depth(line: String, open: Char, close: Char): Int {
        var depth = 0
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i++
                quote != null -> if (c == quote) quote = null
                // a quote is a string only for brackets other than <> (`#include "a<b"` is rare,
                // `'<'` in code common)
                c in QUOTES && c != open && c != close -> quote = c
                c == open -> depth++
                c == close -> depth--
            }
            i++
        }
        return depth
    }

    // whether [line] ends with [quote] closing a string (an even number of them, escaped ones not counted)
    private fun closesString(line: String, quote: Char): Boolean {
        var count = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\') i++ else if (c == quote) count++
            i++
        }
        return count > 0 && count % 2 == 0
    }
}
