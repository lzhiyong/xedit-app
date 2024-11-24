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
 
package io.github.module.piecetable

import io.github.module.piecetable.common.*

import kotlin.text.Regex
import kotlin.text.RegexOption

class PieceTreeTextBuffer internal constructor(
    // temporary variable
    chunks: MutableList<TextBuffer> = mutableListOf(),
    // temporary variable
    strEOL: String = "\n",
    // temporary variable
    eolNormalized: Boolean = true,
    private var _BOM: String = "",
    private var _mightContainRTL: Boolean = false,
    private var _mightContainUnusualLineTerminators: Boolean = false,
    // mightContainNonBasicASCII = !isBasicASCII
    private var _mightContainNonBasicASCII: Boolean = false
): CharSequence {

    private val _pieceTree: PieceTreeBase
    
    init {
        this._pieceTree = PieceTreeBase(chunks, strEOL, eolNormalized)
    }
    
    override val length = this._pieceTree.getLength()
    
    override operator fun get(offset: Int): Char {
        return this._pieceTree.getCharCode(offset).toChar()
    }
    
    override fun subSequence(start: Int, end: Int): CharSequence {
        return getValueInRange(
            Range.fromPositions(getPositionAt(start), getPositionAt(end))
        )
    }
    
    override fun toString(): String {
        val snapshot = createSnapshot(false)
        val buffer = StringBuilder()
        // be careful with large text buffer
        // here will be throw OutOfMemoryError
        var text = snapshot.read()
        while (text != null) {
            buffer.append(text)
            text = snapshot.read()
        }
        return buffer.toString()
    }
    
    // #region TextBuffer API
    fun equals(other: PieceTreeTextBuffer): Boolean {
        if (this._BOM != other._BOM) {
            return false
        }
        if (this.getEOL() != other.getEOL()) {
            return false
        }
        return this._pieceTree.equal(other._pieceTree)
    }

    fun mightContainRTL() = this._mightContainRTL

    fun mightContainUnusualLineTerminators() = this._mightContainUnusualLineTerminators

    fun resetMightContainUnusualLineTerminators() {
        this._mightContainUnusualLineTerminators = false
    }

    fun mightContainNonBasicASCII() = this._mightContainNonBasicASCII

    fun getBOM() = this._BOM
    
    // the internal piece tree only for test 
    internal fun getPieceTree() = this._pieceTree
    
    internal fun createSnapshot(preserveBOM: Boolean): PieceTreeSnapshot {
        // bom string
        val bom = if (preserveBOM) this._BOM else ""
        return this._pieceTree.createSnapshot(bom)
    }
    
    fun getOffsetAt(lineNumber: Int, column: Int) = this._pieceTree.getOffsetAt(lineNumber, column)
    
    fun getOffsetAt(pos: Position) = this._pieceTree.getOffsetAt(pos.lineNumber, pos.column)

    fun getPositionAt(offset: Int) = this._pieceTree.getPositionAt(offset)

    fun getRangeAt(start: Int, length: Int): Range {
        val end = start + length
        val startPosition = this.getPositionAt(start)
        val endPosition = this.getPositionAt(end)
        return Range(startPosition.lineNumber, startPosition.column, endPosition.lineNumber, endPosition.column)
    }

    fun getValueInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): String {
        val lineEnding = this._getEndOfLine(eol)
        return this._pieceTree.getValueInRange(range, lineEnding)
    }
    
    fun getValueInRange(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int ): String {
        return getValueInRange(Range(startLine, startColumn, endLine, endColumn))
    }

    fun getValueLengthInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): Int {
        if (range.isEmpty()) {
            return 0
        }

        if (range.startLine == range.endLine) {
            return range.endColumn - range.startColumn
        }

        val startOffset = this.getOffsetAt(range.startLine, range.startColumn)
        val endOffset = this.getOffsetAt(range.endLine, range.endColumn)
        return endOffset - startOffset
    }

    fun getCharacterCountInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): Int {
        if (this._mightContainNonBasicASCII) {
            // we must count by iterating
            var result = 0

            val fromLine = range.startLine
            val toLine = range.endLine
            for (lineNumber in fromLine..toLine) {
                val lineContent = this.getLineContent(lineNumber)
                val fromOffset = (if (lineNumber == fromLine) range.startColumn - 1 else 0)
                val toOffset = (if (lineNumber == toLine) range.endColumn - 1 else lineContent.length)
                var offset = fromOffset

                while (offset < toOffset) {
                    if (Strings.isHighSurrogate(lineContent.codePointAt(offset))) {
                        result = result + 1
                        offset = offset + 1
                    } else {
                        result = result + 1
                    }
                    offset++
                }
            }

            result += this._getEndOfLine(eol).length * (toLine - fromLine)

            return result
        }

        return this.getValueLengthInRange(range, eol)
    }

    fun getLineCount() = this._pieceTree.getLineCount()

    fun getLinesContent() = this._pieceTree.getLinesContent()

    fun getLineContent(lineNumber: Int) = this._pieceTree.getLineContent(lineNumber)
    
    fun getLineContentWithEOL(lineNumber: Int) = this._pieceTree.getLineContentWithEOL(lineNumber)

    fun getLineCharCode(lineNumber: Int, index: Int) =
        this._pieceTree.getLineCharCode(lineNumber, index)

    fun getCharCode(offset: Int) = this._pieceTree.getCharCode(offset)

    fun getLineLength(lineNumber: Int) = this._pieceTree.getLineLength(lineNumber)

    fun getLineMinColumn(lineNumber: Int) = 1

    fun getLineMaxColumn(lineNumber: Int) = getLineLength(lineNumber) + 1

    fun getLineFirstNonWhitespaceColumn(lineNumber: Int): Int {
        val result = Strings.firstNonWhitespaceIndex(this.getLineContent(lineNumber))
        if (result == -1) {
            return 0
        }
        return result + 1
    }

    fun getLineLastNonWhitespaceColumn(lineNumber: Int): Int {
        val result = Strings.lastNonWhitespaceIndex(this.getLineContent(lineNumber))
        if (result == -1) {
            return 0
        }
        return result + 2
    }
    
    /**
	 * get nearest chunk of text after `offset` in the text buffer.
	 */
    fun getNearestChunk(offset: Int): String {
	    return this._pieceTree.getNearestChunk(offset)
	}

    private fun _getEndOfLine(eol: EndOfLine) = when (eol) {
        EndOfLine.LF -> "\n"
        EndOfLine.CRLF -> "\r\n"
        EndOfLine.TextDefined -> this.getEOL()
        else -> throw IllegalArgumentException("Unknown EOL preference")
    }

    fun setEOL(newEOL: String) = this._pieceTree.setEOL(newEOL)
    fun getEOL() = this._pieceTree.getEOL()
    
    // search text by regex
    fun find(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ) = if(regex.options.contains(RegexOption.MULTILINE)) {
        // multi line mode
        this._pieceTree.findMatchesByMultiline(regex, searchRange, limitResultCount, isCancelled)
    } else {
        // single line mode
        this._pieceTree.findMatchesLineByLine(regex, searchRange, limitResultCount, isCancelled)
    }
    
    // search text by word
    fun find(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ) = this._pieceTree.findMatchesByWord(searchText, searchRange, limitResultCount, isCancelled)
    
    // text edits
    fun applyEdits(
        rawOperations: List<SingleEditOperation>,
        recordTrimAutoWhitespace: Boolean = false,
        computeUndoEdits: Boolean = false
    ): ApplyEditsResult {
        var mightContainRTL = this._mightContainRTL
        var mightContainUnusualLineTerminators = this._mightContainUnusualLineTerminators
        var mightContainNonBasicASCII = this._mightContainNonBasicASCII
        var canReduceOperations = true

        var operations = mutableListOf<ValidatedEditOperation>()
        for (i in 0..rawOperations.size - 1) {
            val op = rawOperations[i]
            if (canReduceOperations && i < 1000/*op._isTracked*/) {
                canReduceOperations = false
            }

            var validText = ""
            // compute text eol
            val (eolCount, firstLineLength, lastLineLength, eol) = Strings.countEOL(op.text)

            val validatedRange = op.range
            op.text?.let {
                var textMightContainNonBasicASCII = true
                if (!mightContainNonBasicASCII) {
                    textMightContainNonBasicASCII = !Strings.isBasicASCII(it)
                    mightContainNonBasicASCII = textMightContainNonBasicASCII
                }
                if (!mightContainRTL && textMightContainNonBasicASCII) {
                    // check if the new inserted text contains RTL
                    mightContainRTL = Strings.containsRTL(it)
                }
                if (!mightContainUnusualLineTerminators && textMightContainNonBasicASCII) {
                    // check if the new inserted text contains unusual line terminators
                    mightContainUnusualLineTerminators = Strings.containsUnusualLineTerminators(it)
                }

                val bufferEOL = this.getEOL()
                val expectedStrEOL = if (bufferEOL == "\r\n") EndOfLine.CRLF else EndOfLine.LF
                if (eol == EndOfLine.TextDefined || eol == expectedStrEOL) {
                    validText = op.text
                } else {
                    validText = op.text.replace(Strings.newLine, bufferEOL)
                }
            }

            operations.add(ValidatedEditOperation(
                sortIndex = i,
                identifier = op.identifier,
                range = validatedRange,
                rangeOffset = this.getOffsetAt(validatedRange.startLine, validatedRange.startColumn),
                rangeLength = this.getValueLengthInRange(validatedRange),
                text = validText,
                eolCount = eolCount,
                firstLineLength = firstLineLength,
                lastLineLength = lastLineLength,
                forceMoveMarkers = op.forceMoveMarkers,
                isAutoWhitespaceEdit = op.isAutoWhitespaceEdit || false
            ))
        }

        // sort operations ascending
        operations.sortWith(::sortOpsAscending)

        var hasTouchingRanges = false
        // operations.size must be > 1
        for (i in 0 until operations.size - 1) {
            val rangeEnd = operations[i].range.getEndPosition()
            val nextRangeStart = operations[i + 1].range.getStartPosition()

            if (nextRangeStart.isBeforeOrEqual(rangeEnd)) {
                if (nextRangeStart.isBefore(rangeEnd)) {
                    // overlapping ranges
                    throw Error("Overlapping ranges are not allowed!")
                }
                hasTouchingRanges = true
            }
        }

        if (canReduceOperations) {
            operations = this._reduceOperations(operations)
        }

        // Delta encode operations
        val reverseRanges: List<Range> = when {
            (computeUndoEdits || recordTrimAutoWhitespace) -> getInverseEditRanges(operations)
            else -> mutableListOf()
        }
        
        // {lineNumber: number, oldContent: string}
        val newTrimAutoWhitespaceCandidates = mutableListOf<Pair<Int, String>>()
        if (recordTrimAutoWhitespace) {
            for (i in 0..operations.size - 1) {
                val op = operations[i]
                val reverseRange = reverseRanges[i]

                if (op.isAutoWhitespaceEdit && op.range.isEmpty()) {
                    // Record already the future line numbers that might be auto whitespace removal
                    // candidates on next edit
                    for (lineNumber in reverseRange.startLine..reverseRange.endLine) {
                        var currentLineContent = ""
                        if (lineNumber == reverseRange.startLine) {
                            currentLineContent = this.getLineContent(op.range.startLine)
                            if (Strings.firstNonWhitespaceIndex(currentLineContent) != -1) {
                                continue
                            }
                        }
                        newTrimAutoWhitespaceCandidates.add(Pair(lineNumber, currentLineContent))
                    }
                }
            }
        }

        var reverseOperations: MutableList<ReverseEditOperation>? = null
        if (computeUndoEdits) {
            var reverseRangeDeltaOffset = 0
            reverseOperations = mutableListOf()
            for (i in 0..operations.size - 1) {
                val op = operations[i]
                val reverseRange = reverseRanges[i]
                val bufferText = this.getValueInRange(op.range)
                val reverseRangeOffset = op.rangeOffset + reverseRangeDeltaOffset
                reverseRangeDeltaOffset += (op.text!!.length - bufferText.length)

                reverseOperations.add(
                    ReverseEditOperation(
                        sortIndex = op.sortIndex,
                        identifier = op.identifier,
                        range = reverseRange,
                        text = bufferText,
                        textChange = TextChange(op.rangeOffset, bufferText, reverseRangeOffset, op.text)
                    )
                )
            }

            // Can only sort reverse operations when the order is not significant
            if (!hasTouchingRanges) {
                // reverseOperations.sort((a, b) => a.sortIndex - b.sortIndex)
                reverseOperations.sortBy{ it.sortIndex }
            }
        }

        this._mightContainRTL = mightContainRTL
        this._mightContainUnusualLineTerminators = mightContainUnusualLineTerminators
        this._mightContainNonBasicASCII = mightContainNonBasicASCII

        val contentChanges = this._doApplyEdits(operations)

        var trimAutoWhitespaceLineNumbers: MutableList<Int>? = null
        if (recordTrimAutoWhitespace && newTrimAutoWhitespaceCandidates.size > 0) {
            // sort line numbers auto whitespace removal candidates for next edit descending
            // newTrimAutoWhitespaceCandidates.sort((a, b) => b.lineNumber - a.lineNumber)
            newTrimAutoWhitespaceCandidates.sortByDescending{ it.first } // first => lineNumber

            trimAutoWhitespaceLineNumbers = mutableListOf()
            for (i in 0..newTrimAutoWhitespaceCandidates.size - 1) {
                val (lineNumber, oldContent) = newTrimAutoWhitespaceCandidates[i]
                val (prevLineNumber, _) = newTrimAutoWhitespaceCandidates[i - 1]
                if (i > 0 && prevLineNumber == lineNumber) {
                    // Do not have the same line number twice
                    continue
                }

                val prevContent = oldContent
                val lineContent = this.getLineContent(lineNumber)

                if (lineContent.length == 0 ||
                   lineContent == prevContent ||
                   Strings.firstNonWhitespaceIndex(lineContent) != -1) {
                    continue
                }
                
                trimAutoWhitespaceLineNumbers.add(lineNumber)
            }
        }

        // this._onDidChangeContent.fire()

        return ApplyEditsResult(contentChanges, reverseOperations, trimAutoWhitespaceLineNumbers)
    }

    /**
     * Transform operations such that they represent the same logic edit, but that they also do not
     * cause OOM crashes.
     */
    private fun _reduceOperations(
        operations: MutableList<ValidatedEditOperation>
    ): MutableList<ValidatedEditOperation> {
        if (operations.size < 1000) {
            // We know from empirical testing that a thousand edits work fine regardless of their
            // shape.
            return operations
        }

        // At one point, due to how events are emitted and how each operation is handled,
        // some operations can trigger a high amount of temporary string allocations,
        // that will immediately get edited again.
        // e.g. a formatter inserting ridiculous ammounts of \n on a model with a single line
        // Therefore, the strategy is to collapse all the operations into a huge single edit
        // operation
        return mutableListOf(this.toSingleEditOperation(operations))
    }

    fun toSingleEditOperation(
        operations: List<ValidatedEditOperation>
    ): ValidatedEditOperation {
        var forceMoveMarkers = false
        val firstEditRange = operations[0].range
        val lastEditRange = operations[operations.size - 1].range
        val entireEditRange = Range(
                firstEditRange.startLine,
                firstEditRange.startColumn,
                lastEditRange.endLine,
                lastEditRange.endColumn
            )
        var lastEndLineNumber = firstEditRange.startLine
        var lastEndColumn = firstEditRange.startColumn
        val result = StringBuffer()

        for (i in 0..operations.size - 1) {
            val op = operations[i]
            val range = op.range

            forceMoveMarkers = forceMoveMarkers || op.forceMoveMarkers

            // (1) -- Push old text
            result.append(
                this.getValueInRange(
                    Range(lastEndLineNumber, lastEndColumn, range.startLine, range.startColumn)
                )
            )

            // (2) -- Push new text
            op.text?.let {
                result.append(op.text)
            }

            lastEndLineNumber = range.endLine
            lastEndColumn = range.endColumn
        }

        val text = result.toString()
        val (eolCount, firstLineLength, lastLineLength, _) = Strings.countEOL(text)

        return ValidatedEditOperation(
            sortIndex = 0,
            identifier = operations[0].identifier,
            range = entireEditRange,
            rangeOffset = this.getOffsetAt(entireEditRange.startLine, entireEditRange.startColumn),
            rangeLength = this.getValueLengthInRange(entireEditRange, EndOfLine.TextDefined),
            text = text,
            eolCount = eolCount,
            firstLineLength = firstLineLength,
            lastLineLength = lastLineLength,
            forceMoveMarkers = forceMoveMarkers,
            isAutoWhitespaceEdit = false
        )
    }

    private fun _doApplyEdits(operations: MutableList<ValidatedEditOperation>): List<ContentChange> {
        // Sort operations descending
        operations.sortWith(::sortOpsDescending)

        val contents = mutableListOf<ContentChange>()
        // operations are from bottom to top
        for (i in 0..operations.size - 1) {      
            val op = operations[i]
            val startLine = op.range.startLine
            val startColumn = op.range.startColumn
            val endLine = op.range.endLine
            val endColumn = op.range.endColumn
            
            // deletion
            this._pieceTree.delete(op.rangeOffset, op.rangeLength)
            
            // insertion
            if(op.text != null && op.text.length > 0) {
                this._pieceTree.insert(op.rangeOffset, op.text, true)
            }
            
            val contentChangeRange = Range(startLine, startColumn, endLine, endColumn)
            contents.add(
                ContentChange(
                    range = contentChangeRange,
                    rangeLength = op.rangeLength,
                    text = op.text,
                    rangeOffset = op.rangeOffset,
                    forceMoveMarkers = op.forceMoveMarkers
                )
            )
        }
        return contents
    }

    // #endregion

    // #region helper
    // testing purpose.
    companion object {
        // static methods
        fun getInverseEditRange(range: Range, text: String): Range {
            val startLine = range.startLine
            val startColumn = range.startColumn

            val (eolCount, firstLineLength, lastLineLength, _) = Strings.countEOL(text)
            val inverseRange: Range

            if (text.length > 0) {
                // the operation inserts something
                val lineCount = eolCount + 1

                if (lineCount == 1) {
                    // single line insert
                    inverseRange = Range(startLine, startColumn, startLine, startColumn + firstLineLength)
                } else {
                    // multi line insert
                    inverseRange = Range(startLine, startColumn, startLine + lineCount - 1, lastLineLength + 1)
                }
            } else {
                // There is nothing to insert
                inverseRange = Range(startLine, startColumn, startLine, startColumn)
            }

            return inverseRange
        }

        /** Assumes `operations` are validated and sorted ascending */
        fun getInverseEditRanges(operations: List<ValidatedEditOperation>): List<Range> {
            val result = mutableListOf<Range>()

            var prevOpEndLineNumber = 0
            var prevOpEndColumn = 0
            var prevOp: ValidatedEditOperation? = null
            for (i in 0..operations.size - 1) {
                val op = operations[i]
                val startLineNumber: Int
                val startColumn: Int

                if (prevOp != null) {
                    if (prevOp.range.endLine == op.range.startLine) {
                        startLineNumber = prevOpEndLineNumber
                        startColumn = prevOpEndColumn + (op.range.startColumn - prevOp.range.endColumn)
                    } else {
                        startLineNumber = prevOpEndLineNumber + (op.range.startLine - prevOp.range.endLine)
                        startColumn = op.range.startColumn
                    }
                } else {
                    startLineNumber = op.range.startLine
                    startColumn = op.range.startColumn
                }

                val resultRange: Range

                if (op.text!!.length > 0) {
                    // the operation inserts something
                    val lineCount = op.eolCount + 1

                    if (lineCount == 1) {
                        // single line insert
                        resultRange = Range(startLineNumber, startColumn, startLineNumber, startColumn + op.firstLineLength)
                    } else {
                        // multi line insert
                        resultRange = Range(startLineNumber, startColumn, startLineNumber + lineCount - 1, op.lastLineLength + 1)
                    }
                } else {
                    // There is nothing to insert
                    resultRange = Range(startLineNumber, startColumn, startLineNumber, startColumn)
                }

                prevOpEndLineNumber = resultRange.endLine
                prevOpEndColumn = resultRange.endColumn

                result.add(resultRange)
                prevOp = op
            }

            return result
        }

        fun sortOpsAscending(a: ValidatedEditOperation, b: ValidatedEditOperation): Int {
            val r = Range.compareRangesUsingEnds(a.range, b.range)
            return if (r == 0) a.sortIndex - b.sortIndex else r
        }

        fun sortOpsDescending(a: ValidatedEditOperation, b: ValidatedEditOperation): Int {
            val r = Range.compareRangesUsingEnds(a.range, b.range)
            return if (r == 0) b.sortIndex - a.sortIndex else -r
        }
    } // end companion
    
    // #endregion
}

