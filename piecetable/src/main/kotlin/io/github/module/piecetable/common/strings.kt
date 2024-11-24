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

package io.github.module.piecetable.common

import kotlin.text.Regex
import kotlin.text.RegexOption

object Strings {
    
    // the line breaking
    val newLine = Regex("(\r\n|\r|\n)")
    
    fun isEmpty(text: CharSequence?) = (text == null || text.length == 0)
    
    fun indent(count: Int) = Character.toString(CharCode.Space.toChar()).repeat(count)
    
    fun toChars(codePoint: Int): String {
        return Character.toChars(codePoint).joinToString()
    }

    fun escapeNewLine(str: String): String {
        return str.replace("\r", "\\r").replace("\n", "\\n")
    }
    
    // escape for regular expression string
    fun escape(str: String): String {
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
    
    fun unescape(str: String) = str
        .replace("\\\\", "\\")
        .replace("\\r", "\r")
        .replace("\\n", "\n")

    // -- UTF-8 BOM

    val UTF8_BOM_CHARACTER = toChars(CharCode.UTF8_BOM)

    fun startsWithUTF8BOM(str: String): Boolean {
        return (str.length > 0 && str.codePointAt(0) == CharCode.UTF8_BOM)
    }

    fun stripUTF8BOM(str: String): String {
        return if (startsWithUTF8BOM(str)) str.substring(1) else str
    }

    /** Generated using https://github.com/alexdima/unicode-utils/blob/master/generate-rtl-test.js */
    val CONTAINS_RTL =
        "(?:[\u05BE\u05C0\u05C3\u05C6\u05D0-\u05F4\u0608\u060B\u060D\u061B-\u064A\u066D-\u066F\u0671-\u06D5\u06E5\u06E6\u06EE\u06EF\u06FA-\u0710\u0712-\u072F\u074D-\u07A5\u07B1-\u07EA\u07F4\u07F5\u07FA-\u0815\u081A\u0824\u0828\u0830-\u0858\u085E-\u08BD\u200F\uFB1D\uFB1F-\uFB28\uFB2A-\uFD3D\uFD50-\uFDFC\uFE70-\uFEFC]|\uD802[\uDC00-\uDD1B\uDD20-\uDE00\uDE10-\uDE33\uDE40-\uDEE4\uDEEB-\uDF35\uDF40-\uDFFF]|\uD803[\uDC00-\uDCFF]|\uD83A[\uDC00-\uDCCF\uDD00-\uDD43\uDD50-\uDFFF]|\uD83B[\uDC00-\uDEBB])"

    /** Returns true if `str` contains any Unicode character that is classified as "R" or "AL". */
    fun containsRTL(str: String): Boolean {
        return CONTAINS_RTL.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(str)
    }

    val UNUSUAL_LINE_TERMINATORS =
        "[\u2028\u2029]" // LINE SEPARATOR (LS) or PARAGRAPH SEPARATOR (PS)

    /** Returns true if `str` contains unusual line terminators, like LS or PS */
    fun containsUnusualLineTerminators(str: String): Boolean {
        return UNUSUAL_LINE_TERMINATORS.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(str)
    }

    val IS_BASIC_ASCII = "^[\t\n\r\\x20-\\x7E]*$"

    /**
     * Returns true if `str` contains only basic ASCII characters in the range 32 - 126 (including 32 and 126) or \n, \r, \t
     */
    fun isBasicASCII(str: String): Boolean {
        return IS_BASIC_ASCII.toRegex().matches(str)
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
            val chCode = str.codePointAt(i)
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
            val chCode = str.codePointAt(i)
            if (chCode != CharCode.Space && chCode != CharCode.Tab) {
                return i
            }
        }
        return -1
    }

    // compute text field
    fun countEOL(str: String?): TextCounter {
        // no need to check the length of text
        if(str == null || str.length == 0) {
            return TextCounter(0, 0, 0, EndOfLine.TextDefined)
        }
       
        var eolCount = 0
        var firstLineLength = 0
        var lastLineStart = 0
        var eol = EndOfLine.TextDefined

        var i = 0
        val length = str.length
        while (i < length) {
            val chr = str.codePointAt(i)

            if (chr == CharCode.CarriageReturn) {
                if (eolCount == 0) {
                    firstLineLength = i
                }
                eolCount++
                if (i + 1 < length && str.codePointAt(i + 1) == CharCode.LineFeed) {
                    // \r\n... case
                    eol = EndOfLine.CRLF
                    i++ // skip \n
                } else {
                    // \r... case
                    eol = EndOfLine.Invalid
                }
                lastLineStart = i + 1
            } else if (chr == CharCode.LineFeed) {
                // \n... case
                eol = EndOfLine.LF
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

        return TextCounter(eolCount, firstLineLength, length - lastLineStart, eol)
    }
}
