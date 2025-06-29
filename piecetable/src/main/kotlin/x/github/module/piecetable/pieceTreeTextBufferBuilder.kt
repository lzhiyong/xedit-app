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

package x.github.module.piecetable

import x.github.module.piecetable.common.*

// piece tree builder
class PieceTreeTextBufferBuilder(
    squence: CharSequence? = null
) {
    private val chunks = mutableListOf<TextBuffer>()

    private var BOM = ""
    private var _hasPreviousChar = false
    private var _previousChar = 0

    private var cr = 0
    private var lf = 0
    private var crlf = 0
    private var containsRTL = false
    private var containsUnusualLineTerminators = false
    private var isBasicASCII = true

    init {
        squence?.let {
            acceptChunk(it.toString())
        }
    }
    
    // append text
    fun acceptChunk(text: String) {
        if (text.length == 0) {
            return // Nothing to do
        }
        
        var chunk = text
        if (this.chunks.size == 0) {
            if (Strings.startsWithUTF8BOM(chunk)) {
                this.BOM = Strings.UTF8_BOM_CHARACTER
                chunk = chunk.substring(1)
            }
        }

        val lastChar = chunk.codePointAt(chunk.length - 1)
        if (lastChar == CharCode.CarriageReturn || (lastChar >= 0xD800 && lastChar <= 0xDBFF)) {
            // last character is \r or a high surrogate => keep it back
            this._acceptChunk1(chunk.substring(0, chunk.length - 1), false)
            this._hasPreviousChar = true
            this._previousChar = lastChar
        } else {
            this._acceptChunk1(chunk, false)
            this._hasPreviousChar = false
            this._previousChar = lastChar
        }
    }

    private fun _acceptChunk1(chunk: String, allowEmptyStrings: Boolean) {
        if (!allowEmptyStrings && chunk.length == 0) {
            return // Nothing to do
        }

        if (this._hasPreviousChar) {
            this._acceptChunk2(Strings.toChars(this._previousChar).toString() + chunk)
        } else {
            this._acceptChunk2(chunk)
        }
    }

    private fun _acceptChunk2(chunk: String) {
        val lineStarts = createLineStarts(chunk)
   
        this.chunks.add(TextBuffer(StringBuffer(chunk), lineStarts.lineStarts))
        this.cr += lineStarts.cr
        this.lf += lineStarts.lf
        this.crlf += lineStarts.crlf
        
        if (!lineStarts.isBasicASCII) {
            // this chunk contains non basic ASCII characters
			this.isBasicASCII = false;
			if (!this.containsRTL) {
			    this.containsRTL = Strings.containsRTL(chunk);
            }
			if (!this.containsUnusualLineTerminators) {
               this.containsUnusualLineTerminators = Strings.containsUnusualLineTerminators(chunk);
            }
        }
    }
    
    private fun getFirstLineText(lengthLimit: Int): String {
        return this.chunks[0].buffer.substring(0, lengthLimit).lines()[0]
    }
    
    private fun _getEOL(eol: EndOfLine): String {
        val totalEOLCount = this.cr + this.lf + this.crlf
        val totalCRCount = this.cr + this.crlf
        if (totalEOLCount == 0) {
            // This is an empty file or a file with precisely one line
            return if (eol == EndOfLine.LF) "\n" else "\r\n"
        }
        if (totalCRCount > totalEOLCount / 2) {
            // More than half of the file contains \r\n ending lines
            return "\r\n"
        }
        // At least one line more ends in \n
        return "\n"
    }
    
    // finish accept the all text chunks
    fun build(
        eol: EndOfLine = EndOfLine.LF, 
        normalizeEOL: Boolean = true
    ): PieceTreeTextBuffer {
        if (this.chunks.size == 0) {
            this._acceptChunk1("", true)
        }
        
        if (this._hasPreviousChar) {
            this._hasPreviousChar = false
            // recreate last chunk
            val lastChunk = this.chunks[this.chunks.size - 1]
            lastChunk.buffer.append(Strings.toChars(this._previousChar))
            val newLineStarts = createLineStartsFast(lastChunk.buffer)
            lastChunk.lineStarts = newLineStarts
            if (this._previousChar == CharCode.CarriageReturn) {
                this.cr++
            }
        }
        
        val strEOL = this._getEOL(eol)

        if (normalizeEOL &&
            ((strEOL == "\r\n" && (this.cr > 0 || this.lf > 0)) ||
            (strEOL == "\n" && (this.cr > 0 || this.crlf > 0)))) {
            // Normalize pieces
            for (i in 0..chunks.size - 1) {
                val str = chunks[i].buffer.toString().replace(Strings.newLine, strEOL)
                val newLineStart = createLineStartsFast(str)
                chunks[i] = TextBuffer(StringBuffer(str), newLineStart)
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
