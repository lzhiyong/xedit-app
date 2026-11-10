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
 * src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeTextBufferBuilder.ts, and changed since.
 */

package x.github.module.piecetable

import x.github.module.piecetable.common.*

/**
 * Collects the initial text in chunks (as read from a file or stream) and creates a
 * [PieceTreeTextBuffer] from them. Input is stored in buffers of at most about
 * AverageBufferSize (64K) chars, each becoming one original buffer and one piece; nothing is
 * copied again later.
 *
 * Usage: acceptChunk() as many times as needed (chunks of any size), then build(). build() may
 * be called again (e.g. with another EOL), but no chunk can be added after the first build():
 * the buffers built share the stored chunks, and an appended chunk would change their text.
 */
class PieceTreeTextBufferBuilder(
    sequence: CharSequence? = null
) {
    private val chunks = mutableListOf<TextBuffer>()

    private var BOM = ""
    // true until the first non-empty chunk arrived: only the very start of the input may hold a BOM
    private var _atStart = true
    // Text held back from the end of the previous slice: a trailing "\r" (its "\n" may come with
    // the next chunk) or a high surrogate (its low half may come with the next chunk). Empty
    // when nothing is held back.
    private var _pending = ""

    private var cr = 0
    private var lf = 0
    private var crlf = 0
    private var containsRTL = false
    private var containsUnusualLineTerminators = false
    private var isBasicASCII = true
    // set by build(); see the class comment
    private var _built = false

    init {
        sequence?.let {
            acceptChunk(it.toString())
        }
    }

    /**
     * Appends [text] to the document being built. Chunks may have any size and may be cut
     * anywhere, also between "\r" and "\n" or between the two halves of a surrogate pair.
     *
     * Large chunks are sliced into pieces of at most AverageBufferSize chars, so a whole file
     * passed as ONE string (also through the constructor) still ends up in 64K buffers. A
     * single huge buffer would make getNearestChunk() return the entire rest of the file on
     * every call (quadratic for an incremental parser such as tree-sitter).
     */
    fun acceptChunk(text: String) {
        check(!this._built) { "acceptChunk() after build(): the built buffer shares the stored chunks" }
        if (text.length == 0) {
            return // Nothing to do
        }

        var chunk = text
        if (this._atStart) {
            this._atStart = false
            if (Strings.startsWithUTF8BOM(chunk)) {
                this.BOM = Strings.UTF8_BOM_CHARACTER
                chunk = chunk.substring(1)
                if (chunk.length == 0) {
                    // the whole chunk was just the BOM
                    return
                }
            }
        }

        var start = 0
        while (chunk.length - start > AverageBufferSize) {
            this._acceptSlice(chunk.substring(start, start + AverageBufferSize))
            start += AverageBufferSize
        }
        this._acceptSlice(if (start == 0) chunk else chunk.substring(start))
    }

    /**
     * Stores one slice, after the text held back from the previous slice and minus a trailing
     * "\r" / high surrogate, which is held back in turn. This keeps a "\r\n" pair and a
     * surrogate pair inside one buffer however the input was cut.
     *
     * The held back text is a String, not a single char: with a slice that consists of nothing
     * but a held back char (e.g. chunks "a\r", "\r", "b") the previously held "\r" must survive.
     * The VS Code original keeps one char and silently dropped the first "\r" in that case.
     */
    private fun _acceptSlice(slice: String) {
        var text = this._pending + slice
        this._pending = ""

        // the UTF-16 code unit, not the code point: a trailing high surrogate has to be
        // detected on its own so that it can be joined with the next chunk
        val lastChar = text[text.length - 1].code
        if (lastChar == CharCode.CarriageReturn || (lastChar >= 0xD800 && lastChar <= 0xDBFF)) {
            this._pending = text.substring(text.length - 1)
            text = text.substring(0, text.length - 1)
        }

        if (text.length > 0) {
            this._acceptChunk2(text)
        }
    }

    private fun _acceptChunk2(chunk: String) {
        val lineStarts = createLineStarts(chunk)
   
        this.chunks.add(TextBuffer(StringBuilder(chunk), lineStarts.lineStarts))
        this.cr += lineStarts.cr
        this.lf += lineStarts.lf
        this.crlf += lineStarts.crlf
        
        if (!lineStarts.isBasicASCII) {
            // this chunk contains non basic ASCII characters
            this.isBasicASCII = false
            if (!this.containsRTL) {
                this.containsRTL = Strings.containsRTL(chunk)
            }
            if (!this.containsUnusualLineTerminators) {
                this.containsUnusualLineTerminators = Strings.containsUnusualLineTerminators(chunk)
            }
        }
    }
    
    // Majority vote over the line breaks seen: CRLF when \r\n (plus lone \r) are more than half
    // of them, otherwise LF. A text without any line break uses the requested default [eol]:
    // "\r\n" for CRLF, "\n" for anything else.
    private fun _getEOL(eol: EndOfLine): String {
        val totalEOLCount = this.cr + this.lf + this.crlf
        val totalCRCount = this.cr + this.crlf
        if (totalEOLCount == 0) {
            // This is an empty file or a file with precisely one line
            return if (eol == EndOfLine.CRLF) "\r\n" else "\n"
        }
        if (totalCRCount > totalEOLCount / 2) {
            // More than half of the file contains \r\n ending lines
            return "\r\n"
        }
        // At least one line more ends in \n
        return "\n"
    }
    
    /**
     * Creates the buffer from the chunks accepted so far.
     *
     * @param eol EOL to use when the text contains no line break at all: CRLF gives "\r\n",
     *   every other value "\n".
     * @param normalizeEOL rewrite every line break to the detected EOL. Keep this true for an
     *   editor: several read paths (getLineLength, getLineContent) are only exact on an
     *   EOL-normalized buffer.
     */
    fun build(
        eol: EndOfLine = EndOfLine.LF, 
        normalizeEOL: Boolean = true
    ): PieceTreeTextBuffer {
        this._built = true
        if (this.chunks.size == 0) {
            // no chunk stored yet: the document is empty or consists of the held back text only
            this._acceptChunk2(this._pending)
            this._pending = ""
        }

        if (this._pending.length > 0) {
            // a trailing "\r" or lone high surrogate at the very end of the input: nothing can
            // follow any more, append it to the last buffer
            val lastChunk = this.chunks[this.chunks.size - 1]
            lastChunk.append(this._pending)
            lastChunk.lineStarts = createLineStartsFast(lastChunk.buffer)
            if (this._pending[0].code == CharCode.CarriageReturn) {
                this.cr++
            }
            this._pending = ""
        }

        val strEOL = this._getEOL(eol)

        if (normalizeEOL &&
            ((strEOL == "\r\n" && (this.cr > 0 || this.lf > 0)) ||
            (strEOL == "\n" && (this.cr > 0 || this.crlf > 0)))) {
            // Normalize pieces
            for (i in 0..chunks.size - 1) {
                val str = chunks[i].buffer.toString().replace(Strings.newLine, strEOL)
                val newLineStart = createLineStartsFast(str)
                chunks[i] = TextBuffer(StringBuilder(str), newLineStart)
            }
        }
        
        // create the piece tree buffer
        return PieceTreeTextBuffer(
            chunks,
            strEOL,
            normalizeEOL,
            this.BOM,
            this.containsRTL,
            this.containsUnusualLineTerminators,
            !this.isBasicASCII) // mightContainNonBasicASCII = !_isBasicASCII
    }
}
