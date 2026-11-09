/*
 * Copyright © 2022 Github Lzhiyong
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

/*
 * Derived from Visual Studio Code (https://github.com/microsoft/vscode),
 * Copyright (c) 2015 - present Microsoft Corporation, under the MIT License: see
 * LICENSE-vscode at the root of this module. Ported from
 * src/vs/base/common/strings.ts, and changed since.
 */

package x.github.module.piecetable.common

import kotlin.text.Regex

public object Strings {
    
    // the line breaking
    val newLine = Regex("(\r\n|\r|\n)")
    
    fun isEmpty(text: CharSequence?) = (text == null || text.length == 0)
    
    fun indent(count: Int) = Character.toString(CharCode.Space.toChar()).repeat(count)
    
    /**
     * Convert a code point to a string.
     *
     * Note: `CharArray.joinToString()` would insert ", " between the two halves of a surrogate
     * pair, so the array has to be wrapped with the String constructor instead.
     */
    fun toChars(codePoint: Int): String {
        return String(Character.toChars(codePoint))
    }
    
    /**
     * The ASCII POSIX punctuation set !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~ (32 chars): every
     * printable ASCII character that is neither a letter, a digit nor a space. The same on every
     * platform; character ranges, no regular expression, no allocation.
     *
     * This is what `\p{Punct}` means on the JVM. It includes the symbols $ + < = > ^ ` | ~,
     * which Unicode classifies as symbols, not punctuation, and nothing outside ASCII.
     */
    fun isAsciiPunctuation(char: Char): Boolean {
        return (char >= '!' && char <= '/') ||
            (char >= ':' && char <= '@') ||
            (char >= '[' && char <= '`') ||
            (char >= '{' && char <= '~')
    }

    /**
     * Unicode punctuation: the general categories Pc, Pd, Ps, Pe, Pi, Pf and Po, anywhere in the
     * BMP. Covers CJK punctuation such as ，。、！？；：（）《》「」…— and leaves out the ASCII
     * symbols $ + < = > ^ ` | ~ as well as currency / math symbols and emoji.
     *
     * This is what `\p{Punct}` means on Android (ICU), but computed from the character tables, so
     * it gives the same result on the JVM and on a device (apart from characters added in a newer
     * Unicode version than the platform knows). No regular expression, no allocation.
     */
    fun isUnicodePunctuation(char: Char): Boolean {
        return when (char.category) {
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    fun escapeLineFeed(str: String): String {
        return str.replace("\r", "\\r").replace("\n", "\\n")
    }
    
    // escape for regular expression string
    fun escapeCharacter(str: String): String {
        val table = arrayOf(
            "*", ".", "+", "?", "^", "$", "|", "[", "]", "{", "}", "(", ")"
        )
        var result = str.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
        table.forEach {
            result = result.replace(it, "\\".plus(it))
        }
        // escape result
        return result
    }
    
    // unescape for regular expression string
    fun unescapeCharacter(str: String) = str.replace("\\\\", "\\").replace("\\r", "\r").replace("\\n", "\n")

    // -- UTF-8 BOM

    val UTF8_BOM_CHARACTER = toChars(CharCode.UTF8_BOM)

    fun startsWithUTF8BOM(str: String): Boolean {
        return (str.length > 0 && str[0].code == CharCode.UTF8_BOM)
    }

    fun stripUTF8BOM(str: String): String {
        return if (startsWithUTF8BOM(str)) str.substring(1) else str
    }

    /** Generated using https://github.com/alexdima/unicode-utils/blob/master/generate-rtl-test.js */
    val CONTAINS_RTL =
        "(?:[\u05BE\u05C0\u05C3\u05C6\u05D0-\u05F4\u0608\u060B\u060D\u061B-\u064A\u066D-\u066F\u0671-\u06D5\u06E5\u06E6\u06EE\u06EF\u06FA-\u0710\u0712-\u072F\u074D-\u07A5\u07B1-\u07EA\u07F4\u07F5\u07FA-\u0815\u081A\u0824\u0828\u0830-\u0858\u085E-\u08BD\u200F\uFB1D\uFB1F-\uFB28\uFB2A-\uFD3D\uFD50-\uFDFC\uFE70-\uFEFC]|\uD802[\uDC00-\uDD1B\uDD20-\uDE00\uDE10-\uDE33\uDE40-\uDEE4\uDEEB-\uDF35\uDF40-\uDFFF]|\uD803[\uDC00-\uDCFF]|\uD83A[\uDC00-\uDCCF\uDD00-\uDD43\uDD50-\uDFFF]|\uD83B[\uDC00-\uDEBB])"

    // the regex is compiled once, compiling it on every call used to show up on every edit
    private val CONTAINS_RTL_REGEX = CONTAINS_RTL.toRegex()

    /** Returns true if `str` contains any Unicode character that is classified as "R" or "AL". */
    fun containsRTL(str: String): Boolean {
        return CONTAINS_RTL_REGEX.containsMatchIn(str)
    }

    val UNUSUAL_LINE_TERMINATORS =
        "[\u2028\u2029]" // LINE SEPARATOR (LS) or PARAGRAPH SEPARATOR (PS)

    private val UNUSUAL_LINE_TERMINATORS_REGEX = UNUSUAL_LINE_TERMINATORS.toRegex()

    /** Returns true if `str` contains unusual line terminators, like LS or PS */
    fun containsUnusualLineTerminators(str: String): Boolean {
        return UNUSUAL_LINE_TERMINATORS_REGEX.containsMatchIn(str)
    }

    /**
     * Returns true if `str` contains only basic ASCII characters in the range 32 - 126 (including 32 and 126) or \n, \r, \t
     *
     * A hand written loop, this is called for every applied edit and is roughly an order of
     * magnitude faster than matching a regular expression.
     */
    fun isBasicASCII(str: String): Boolean {
        for (i in 0 until str.length) {
            val code = str[i].code
            if (code < 32 || code > 126) {
                if (code != CharCode.Tab && code != CharCode.LineFeed && code != CharCode.CarriageReturn) {
                    return false
                }
            }
        }
        return true
    }

    /** See http://en.wikipedia.org/wiki/Surrogate_pair */
    fun isHighSurrogate(charCode: Int): Boolean {
        return (0xD800 <= charCode && charCode <= 0xDBFF)
    }

    /** See http://en.wikipedia.org/wiki/Surrogate_pair */
    fun isLowSurrogate(charCode: Int): Boolean {
        return (0xDC00 <= charCode && charCode <= 0xDFFF)
    }

    /**
     * Returns first index of the string that is not whitespace. If string is empty or contains only
     * whitespaces, returns -1
     */
    fun firstNonWhitespaceIndex(str: String): Int {
        for (i in 0..str.length - 1) {
            val chCode = str[i].code
            if (chCode != CharCode.Space && chCode != CharCode.Tab) {
                return i
            }
        }
        return -1
    }

    /**
     * Returns last index of the string that is not whitespace. If string is empty or contains only
     * whitespaces, returns -1
     */
    fun lastNonWhitespaceIndex(str: String, startIndex: Int = str.length - 1): Int {
        for (i in startIndex downTo 0) {
            val chCode = str[i].code
            if (chCode != CharCode.Space && chCode != CharCode.Tab) {
                return i
            }
        }
        return -1
    }

    /**
     * Counts the line breaks of [str] and measures its first and last line.
     *
     * [TextCounter.eol] tells which line break kind the text uses: LF or CRLF when every break
     * is of that kind, TextDefined when there is none, and Invalid for a lone "\r" or for a
     * MIX of kinds. applyEdits relies on this: anything but the buffer's own kind (or none) is
     * normalized before insertion. Reporting only the LAST kind seen, as before, let text such
     * as "a\r\nb\nc" into an LF buffer without normalization.
     */
    fun countEOL(str: String?): TextCounter {
        // no need to check the length of text
        if(str == null || str.length == 0) {
            return TextCounter(0, 0, 0, EndOfLine.TextDefined)
        }

        var eolCount = 0
        var firstLineLength = 0
        var lastLineStart = 0
        // bit mask of the line break kinds seen: 1 = \n, 2 = \r\n, 4 = lone \r
        var kinds = 0

        var i = 0
        val length = str.length
        while (i < length) {
            val chr = str[i].code

            if (chr == CharCode.CarriageReturn) {
                if (eolCount == 0) {
                    firstLineLength = i
                }
                eolCount++
                if (i + 1 < length && str[i + 1].code == CharCode.LineFeed) {
                    // \r\n... case
                    kinds = kinds or 2
                    i++ // skip \n
                } else {
                    // \r... case
                    kinds = kinds or 4
                }
                lastLineStart = i + 1
            } else if (chr == CharCode.LineFeed) {
                // \n... case
                kinds = kinds or 1
                if (eolCount == 0) {
                    firstLineLength = i
                }
                eolCount++
                lastLineStart = i + 1
            }
            i++
        }

        if (eolCount == 0) {
            firstLineLength = length
        }

        val eol = when (kinds) {
            0 -> EndOfLine.TextDefined
            1 -> EndOfLine.LF
            2 -> EndOfLine.CRLF
            else -> EndOfLine.Invalid // a lone \r, or more than one kind of line break
        }
        return TextCounter(eolCount, firstLineLength, length - lastLineStart, eol)
    }

    /**
     * Whether a regex source can match across a line break: it contains a literal line break or
     * one of the escapes \n, \r, \W. Same heuristic as VS Code's isMultilineRegexSource; such
     * patterns are searched over the joined text instead of line by line.
     */
    fun isMultilineRegexSource(source: String): Boolean {
        var i = 0
        val length = source.length
        while (i < length) {
            val chr = source[i]
            if (chr == '\n' || chr == '\r') {
                return true
            }
            if (chr == '\\') {
                i++ // look at the escaped char
                if (i >= length) {
                    break
                }
                val next = source[i]
                if (next == 'n' || next == 'r' || next == 'W') {
                    return true
                }
            }
            i++
        }
        return false
    }
}
