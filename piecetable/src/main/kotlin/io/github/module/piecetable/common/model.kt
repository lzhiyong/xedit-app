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

// line separator
enum class EndOfLine {
    /** Use the end of line character identified in the text buffer. */
    TextDefined,
    /** Use line feed (\n) as the end of line character. */
    LF,
    /** Use carriage return and line feed (\r\n) as the end of line character. */
    CRLF,
    /** Invalid end of line */
    Invalid
}

// statistical text information
data class TextCounter(
    val eolCount: Int,
    val firstLineLength: Int,
    val lastLineLength: Int,
    val eol: EndOfLine
)

/**
 * An identifier for a single edit operation.
 */
data class Identifier(
    /** Identifier major */
    val major: Int = 0,
    /** Identifier minor */
    val minor: Int = 0
)

data class ValidatedEditOperation(
    val sortIndex: Int = 0,
    val identifier: Identifier? = null,
    /** The range to replace. This can be empty to emulate a simple insert/delete. */
    val range: Range,
    /** The offset of the range that got replaced. */
    val rangeOffset: Int = 0,
    /** The length of the range that got replaced. */
    val rangeLength: Int = 0,
    /** The text to replace with. This can be null to emulate a simple insert/delete. */
    val text: String? = null,
    /** The total number of lines of text, This can be 0 to emulate a simple insert/delete */
    val eolCount: Int = 0,
    /** The length of the first line of text */
    val firstLineLength: Int = 0,
    /** The length of the last line of text */
    val lastLineLength: Int = 0,
    /**
     * This indicates that this operation has "insert" semantics. i.e. forceMoveMarkers = true => if
     * `range` is collapsed, all markers at the position will be moved.
     */
    val forceMoveMarkers: Boolean = false,
    /**
     * This indicates that this operation is inserting automatic whitespace that can be removed on
     * next model edit operation if `config.trimAutoWhitespace` is true.
     */
    val isAutoWhitespaceEdit: Boolean = false
)

data class SingleEditOperation(
    val range: Range,
    val text: String?,
    val identifier :Identifier? = null,
    val forceMoveMarkers: Boolean = false,
    val isAutoWhitespaceEdit: Boolean = false
)

data class ReverseEditOperation(
    val sortIndex: Int,
    val identifier: Identifier?,
    val range: Range,
    val text: String?,
    val textChange: TextChange
)

data class ContentChange(
    /** The range to replace. This can be empty to emulate a simple insert/delete. */
    val range: Range,
    /** The offset of the range that got replaced. */
    val rangeOffset: Int,
    /** The length of the range that got replaced. */
    val rangeLength: Int,
    /** The text to replace with. This can be null to emulate a simple insert/delete. */
    val text: String?,
    /**
     * This indicates that this operation has "insert" semantics. i.e. forceMoveMarkers = true => if
     * `range` is collapsed, all markers at the position will be moved.
     */
    val forceMoveMarkers: Boolean
)

data class ApplyEditsResult(
    val changes: List<ContentChange>,
    val reverseEdits: List<ReverseEditOperation>?,
    val trimAutoWhitespaceLineNumbers: List<Int>?
)

data class TextChange(
    val oldPosition: Int,
    val oldText: String,
    val newPosition: Int,
    val newText: String
) {
    val oldLength: Int
    val oldEnd: Int
    val newLength: Int
    val newEnd: Int

    init {
        this.oldLength = this.oldText.length
        this.oldEnd = this.oldPosition + this.oldText.length
        this.newLength = this.newText.length
        this.newEnd = this.newPosition + this.newText.length
    }

    companion object {
        fun compressConsecutiveTextChanges(
            prevEdits: List<TextChange>?,
            currEdits: List<TextChange>
        ): List<TextChange> {
            if (prevEdits == null || prevEdits.size == 0) {
                return currEdits
            }
            return TextChangeCompressor(prevEdits, currEdits).compress()
        }
    }
    
    override fun toString(): String {
        if(this.oldText.length == 0) {
            return "TextChange(insert@${this.oldPosition} ${Strings.escapeNewLine(this.newText)})"
        }
        if(this.newText.length == 0) {
            return "TextChange(delete@${this.oldPosition} ${Strings.escapeNewLine(this.oldText)})"
        }
        return "TextChange(replace@${this.oldPosition} ${Strings.escapeNewLine(this.oldText)} -> ${Strings.escapeNewLine(this.newText)})"
    }
}

class TextChangeCompressor(
    private val _prevEdits: List<TextChange>,
    private val _currEdits: List<TextChange>
) {
    private val _result: MutableList<TextChange>
    private var _resultLen: Int

    private var _prevLen: Int
    private var _prevDeltaOffset: Int

    private var _currLen: Int
    private var _currDeltaOffset: Int

    init {
        this._result = mutableListOf()
        this._resultLen = 0

        this._prevLen = this._prevEdits.size
        this._prevDeltaOffset = 0

        this._currLen = this._currEdits.size
        this._currDeltaOffset = 0
    }

    fun compress(): List<TextChange> {
        var prevIndex = 0
        var currIndex = 0

        var prevEdit = this._getPrev(prevIndex)
        var currEdit = this._getCurr(currIndex)

        while (prevIndex < this._prevLen || currIndex < this._currLen) {

            if (prevEdit == null) {
                this._acceptCurr(currEdit!!)
                currEdit = this._getCurr(++currIndex)
                continue
            }

            if (currEdit == null) {
                this._acceptPrev(prevEdit)
                prevEdit = this._getPrev(++prevIndex)
                continue
            }

            if (currEdit.oldEnd <= prevEdit.newPosition) {
                this._acceptCurr(currEdit)
                currEdit = this._getCurr(++currIndex)
                continue
            }

            if (prevEdit.newEnd <= currEdit.oldPosition) {
                this._acceptPrev(prevEdit)
                prevEdit = this._getPrev(++prevIndex)
                continue
            }

            if (currEdit.oldPosition < prevEdit.newPosition) {
                val e = this._splitCurr(currEdit, prevEdit.newPosition - currEdit.oldPosition)
                this._acceptCurr(e[0])
                currEdit = e[1]
                continue
            }

            if (prevEdit.newPosition < currEdit.oldPosition) {
                val e = this._splitPrev(prevEdit, currEdit.oldPosition - prevEdit.newPosition)
                this._acceptPrev(e[0])
                prevEdit = e[1]
                continue
            }

            // At this point, currEdit.oldPosition == prevEdit.newPosition
            val mergePrev: TextChange
            val mergeCurr: TextChange

            if (currEdit.oldEnd == prevEdit.newEnd) {
                mergePrev = prevEdit
                mergeCurr = currEdit
                prevEdit = this._getPrev(++prevIndex)
                currEdit = this._getCurr(++currIndex)
            } else if (currEdit.oldEnd < prevEdit.newEnd) {
                val e = this._splitPrev(prevEdit, currEdit.oldLength)
                mergePrev = e[0]
                mergeCurr = currEdit
                prevEdit = e[1]
                currEdit = this._getCurr(++currIndex)
            } else {
                val e = this._splitCurr(currEdit, prevEdit.newLength)
                mergePrev = prevEdit
                mergeCurr = e[0]
                prevEdit = this._getPrev(++prevIndex)
                currEdit = e[1]
            }

            this._result.add(
                TextChange(
                    mergePrev.oldPosition,
                    mergePrev.oldText,
                    mergeCurr.newPosition,
                    mergeCurr.newText
                )
            )
            this._prevDeltaOffset += mergePrev.newLength - mergePrev.oldLength
            this._currDeltaOffset += mergeCurr.newLength - mergeCurr.oldLength
        }

        val merged = this._merge(this._result)
        val cleaned = this._removeNoOps(merged)
        return cleaned
    }

    private fun _acceptCurr(currEdit: TextChange) {
        this._result.add(this._rebaseCurr(this._prevDeltaOffset, currEdit))
        this._currDeltaOffset += currEdit.newLength - currEdit.oldLength
    }

    private fun _getCurr(currIndex: Int): TextChange? {
        return if (currIndex < this._currLen) this._currEdits.get(currIndex) else null
    }

    private fun _acceptPrev(prevEdit: TextChange) {
        this._result.add(this._rebasePrev(this._currDeltaOffset, prevEdit))
        this._prevDeltaOffset += prevEdit.newLength - prevEdit.oldLength
    }

    private fun _getPrev(prevIndex: Int): TextChange? {
        return if (prevIndex < this._prevLen) this._prevEdits.get(prevIndex) else null
    }

    private fun _rebaseCurr(prevDeltaOffset: Int, currEdit: TextChange): TextChange {
        return TextChange(
            currEdit.oldPosition - prevDeltaOffset,
            currEdit.oldText,
            currEdit.newPosition,
            currEdit.newText
        )
    }

    private fun _rebasePrev(currDeltaOffset: Int, prevEdit: TextChange): TextChange {
        return TextChange(
            prevEdit.oldPosition,
            prevEdit.oldText,
            prevEdit.newPosition + currDeltaOffset,
            prevEdit.newText
        )
    }

    private fun _splitPrev(edit: TextChange, offset: Int): Array<TextChange> {
        val preText = edit.newText.substring(0, offset)
        val postText = edit.newText.substring(offset)

        return arrayOf(
            TextChange(edit.oldPosition, edit.oldText, edit.newPosition, preText),
            TextChange(edit.oldEnd, "", edit.newPosition + offset, postText)
        )
    }

    private fun _splitCurr(edit: TextChange, offset: Int): Array<TextChange> {
        val preText = edit.oldText.substring(0, offset)
        val postText = edit.oldText.substring(offset)

        return arrayOf(
            TextChange(edit.oldPosition, preText, edit.newPosition, edit.newText),
            TextChange(edit.oldPosition + offset, postText, edit.newEnd, "")
        )
    }

    private fun _merge(edits: List<TextChange>): List<TextChange> {
        if (edits.size == 0) {
            return edits
        }

        val result: MutableList<TextChange> = mutableListOf()
        var prev = edits[0]
        for (i in 1..edits.size - 1) {
            val curr = edits[i]

            if (prev.oldEnd == curr.oldPosition) {
                // Merge into `prev`
                prev =
                    TextChange(
                        prev.oldPosition,
                        prev.oldText + curr.oldText,
                        prev.newPosition,
                        prev.newText + curr.newText
                    )
            } else {
                result.add(prev)
                prev = curr
            }
        }
        result.add(prev)
        return result
    }

    private fun _removeNoOps(edits: List<TextChange>): List<TextChange> {
        if (edits.size == 0) {
            return edits
        }

        val result: MutableList<TextChange> = mutableListOf()
        for (i in 0..edits.size - 1) {
            val edit = edits[i]

            if (edit.oldText == edit.newText) {
                continue
            }
            result.add(edit)
        }
        return result
    }
}


class LineFeedCounter(text: String) {
    // line feeds
    private val _lineFeedsOffsets = mutableListOf<Int>()

    init {
        for (i in 0..text.length - 1) {
            if (text.codePointAt(i) == CharCode.LineFeed) {
                this._lineFeedsOffsets.add(i)
            }
        }
    }

    fun findLineFeedCountBeforeOffset(offset: Int): Int {
        var min = 0
        var max = this._lineFeedsOffsets.size - 1

        if (max == -1) {
            // no line feeds
            return 0
        }

        if (offset <= this._lineFeedsOffsets[0]) {
            // before first line feed
            return 0
        }

        while (min < max) {
            val mid = (min + max) shr 1

            if (this._lineFeedsOffsets[mid] >= offset) {
                max = mid - 1
            } else {
                if (this._lineFeedsOffsets[mid + 1] >= offset) {
                    // bingo!
                    min = mid
                    max = mid
                } else {
                    min = mid + 1
                }
            }
        }
        return min + 1
    }
}

