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
 * src/vs/editor/common/model/pieceTreeTextBuffer/pieceTreeTextBuffer.ts, and changed since.
 */
 
package x.github.module.piecetable

import x.github.module.piecetable.common.*


/**
 * Memory figures of a buffer, see [PieceTreeTextBuffer.getStorageStats].
 *
 * @property length document length in chars
 * @property storedLength chars held by all buffers: the original chunks (whole, also the parts
 *   that were deleted) plus the change buffer. Equals [length] right after loading or compact()
 * @property changeBufferLength chars in the change buffer: everything ever inserted, deleted or not
 * @property pieceCount number of pieces (tree nodes); grows with edits at different places
 */
data class StorageStats(
    val length: Int,
    val storedLength: Long,
    val changeBufferLength: Int,
    val pieceCount: Int
)

/**
 * Public text buffer API on top of [PieceTreeBase] (the port of VS Code's PieceTreeTextBuffer).
 *
 * Coordinates: offsets are 0-based UTF-16 code unit indices; positions and ranges are 1-based
 * (line, column), column 1 being before the first character of the line.
 *
 * Edits should go through [applyEdits]: it validates ranges, normalizes the EOL of inserted
 * text, applies several edits atomically and can compute the inverse edits for undo.
 *
 * Arguments: line based queries (getLineContent, getLineLength, getLineCharCode, ...) need a line
 * that exists, 1..getLineCount(), and throw IndexOutOfBoundsException otherwise. Position and
 * range based methods (getOffsetAt, getValueInRange, applyEdits, find, ...) clamp instead.
 *
 * Threading: per buffer, any number of threads may read at the same time as long as nobody
 * edits, and the last edit happens-before the reads. An edit (applyEdits, setEOL, compact) must
 * run alone on its buffer. Different buffers share no mutable state and need no coordination
 * with each other, so one ReentrantReadWriteLock per buffer (edits under the write lock, reads
 * under the read lock) is enough; see the Threading section of the README. Work that has to
 * read while the user keeps typing (background highlighting, saving) takes a [createSnapshot]
 * instead: creating it is a read, reading it needs no lock at all.
 *
 * Undo / redo: [applyEdits] returns the removed text as Strings. [applyEditsWithSliceUndo] and
 * [applySliceEdits] return it as [TextSlice]s, which refer to the storage instead of copying
 * large content.
 */
class PieceTreeTextBuffer : CharSequence {
    // the piece tree base
    private lateinit var _pieceTree: PieceTreeBase
    // the bom string
    private lateinit var _BOM: String
    
    private var _mightContainRTL = false
    private var _mightContainUnusualLineTerminators = false
    // mightContainNonBasicASCII = !isBasicASCII
    private var _mightContainNonBasicASCII = false
    
    internal constructor(
        chunks: MutableList<TextBuffer>,
        strEOL: String,
        eolNormalized: Boolean,
        bom: String,
        isRTL: Boolean,
        isLineTerminators: Boolean,
        isBasicASCII: Boolean
    ) {
        this._pieceTree = PieceTreeBase(chunks, strEOL, eolNormalized)
        this._BOM= bom
        this._mightContainRTL = isRTL
        this._mightContainUnusualLineTerminators = isLineTerminators
        this._mightContainNonBasicASCII = isBasicASCII
    }
    
    override val length: Int 
        get() = if(::_pieceTree.isInitialized) _pieceTree.getLength() else 0
        
    // CharSequence view: one UTF-16 code unit per index, each access is an O(log n) tree lookup.
    // Fine for occasional access; to scan the whole text, use readPiecesContent() instead.
    override operator fun get(index: Int): Char {
        if (index < 0 || index >= this.length) {
            throw IndexOutOfBoundsException("index: $index, length: ${this.length}")
        }
        return this._pieceTree.getCharCode(index).toChar()
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex < 0 || endIndex > this.length || startIndex > endIndex) {
            throw IndexOutOfBoundsException(
                "start: $startIndex, end: $endIndex, length: ${this.length}"
            )
        }
        return getValueInRange(
            Range.fromPositions(getPositionAt(startIndex), getPositionAt(endIndex))
        )
    }
    
    /**
     * The whole document as one String, without the BOM. Needs about twice the document size in
     * memory while it is built (a large file can run out of memory): to save, use
     * [readPiecesContent].
     */
    override fun toString(): String {
        val text = StringBuilder(this.length)
        this._pieceTree.readPiecesContent { text.append(it) }
        return text.toString()
    }
    
    
    // #region TextBuffer API
    /**
     * Compares BOM, EOL and the whole content of two buffers.
     *
     * Deliberately *not* named equals(): that would be an overload of Any.equals() rather than
     * an override, so `a == b` and `a.equals(b)` would mean different things.
     */
    fun contentEquals(other: PieceTreeTextBuffer): Boolean {
        if (this === other) {
            return true
        }
        if (this._BOM != other._BOM) {
            return false
        }
        if (this.getEOL() != other.getEOL()) {
            return false
        }
        return this._pieceTree.equal(other._pieceTree)
    }

    @Deprecated(
        "Confusing overload of Any.equals(), use contentEquals()",
        ReplaceWith("contentEquals(other)")
    )
    fun equals(other: PieceTreeTextBuffer): Boolean = contentEquals(other)

    fun mightContainRTL() = this._mightContainRTL

    fun mightContainUnusualLineTerminators() = this._mightContainUnusualLineTerminators

    fun resetMightContainUnusualLineTerminators() {
        this._mightContainUnusualLineTerminators = false
    }

    fun mightContainNonBasicASCII() = this._mightContainNonBasicASCII

    fun getBOM() = this._BOM
    
    // the internal piece tree only for test 
    internal fun getPieceTree() = this._pieceTree
    
    /**
     * An immutable copy of the current content that any thread can read at any time, also while
     * this buffer is being edited; see [PieceTreeSnapshot].
     *
     * Creating it is a read of this buffer (under the read lock, or on the editing thread) and
     * costs O(number of pieces) plus a copy of the typed text still in the document; it does not
     * copy the file content. Its [PieceTreeSnapshot.versionId] is [getVersionId] of this moment.
     *
     * @param preserveBOM let [PieceTreeSnapshot.read] start with the BOM
     */
    fun createSnapshot(preserveBOM: Boolean = false): PieceTreeSnapshot {
        val bom = if (preserveBOM) this._BOM else ""
        return this._pieceTree.createSnapshot(bom)
    }

    /**
     * A number that changes whenever the content changes (applyEdits, setEOL); compact() leaves
     * it alone. Tag work done on a version with it, e.g. a highlighting pass on a snapshot, and
     * compare for equality only. A buffer built anew starts again from 0.
     */
    fun getVersionId() = this._pieceTree.getVersionId()

    /** Hands the whole content to [callback], one piece at a time, in order, without the BOM. */
    fun readPiecesContent(callback: (String) -> Unit) {
        this._pieceTree.readPiecesContent(callback)
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

    // The range is clamped into the document first (see _validateRange), so a stale range reads
    // what is left of it instead of failing, and a reversed one reads nothing.
    fun getValueInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): String {
        val lineEnding = this._getEndOfLine(eol)
        return this._pieceTree.getValueInRange(this._validateRange(range), lineEnding)
    }
    
    fun getValueInRange(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int ): String {
        return getValueInRange(Range(startLine, startColumn, endLine, endColumn))
    }

    // Length of the text in [range] measured in the buffer's own EOL; [eol] is currently ignored
    // (newer VS Code versions add (desired EOL length - actual EOL length) per line break).
    fun getValueLengthInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): Int {
        return this._getValueLengthInRange(this._validateRange(range))
    }

    // [range] is already valid
    private fun _getValueLengthInRange(range: Range): Int {
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

    // The range is clamped into the document first, like every range based method
    fun getCharacterCountInRange(range: Range, eol: EndOfLine = EndOfLine.TextDefined): Int {
        val valid = this._validateRange(range)
        if (this._mightContainNonBasicASCII) {
            // we must count by iterating
            var result = 0

            val fromLine = valid.startLine
            val toLine = valid.endLine
            for (lineNumber in fromLine..toLine) {
                val lineContent = this.getLineContent(lineNumber)
                val fromOffset = (if (lineNumber == fromLine) valid.startColumn - 1 else 0)
                val toOffset = (if (lineNumber == toLine) valid.endColumn - 1 else lineContent.length)
                var offset = fromOffset

                while (offset < toOffset) {
                    // charCodeAt semantics: codePointAt would return the combined code point
                    // at a high surrogate, so the test below would never be true
                    if (Strings.isHighSurrogate(lineContent[offset].code)) {
                        // a surrogate pair is one character, skip its low half
                        offset = offset + 1
                    }
                    result = result + 1
                    offset++
                }
            }

            result += this._getEndOfLine(eol).length * (toLine - fromLine)

            return result
        }

        return this._getValueLengthInRange(valid)
    }

    fun getLineCount() = this._pieceTree.getLineCount()

    // Line based queries need a line that exists. The tree itself does not check: line 0 used to
    // throw StringIndexOutOfBoundsException deep inside it and lineCount + 1 silently gave "".
    private fun checkLineNumber(lineNumber: Int) {
        val lineCount = this._pieceTree.getLineCount()
        if (lineNumber < 1 || lineNumber > lineCount) {
            throw IndexOutOfBoundsException("lineNumber: $lineNumber, lineCount: $lineCount")
        }
    }

    fun getLinesContent() = this._pieceTree.getLinesContent()

    /** Content of a line without its line break. Throws IndexOutOfBoundsException for a line that does not exist. */
    fun getLineContent(lineNumber: Int): String {
        this.checkLineNumber(lineNumber)
        return this._pieceTree.getLineContent(lineNumber)
    }

    /** Content of a line with its line break. Throws IndexOutOfBoundsException for a line that does not exist. */
    fun getLineContentWithEOL(lineNumber: Int): String {
        this.checkLineNumber(lineNumber)
        return this._pieceTree.getLineContentWithEOL(lineNumber)
    }

    /**
     * UTF-16 code unit at [index] (0-based) of a line; index == line length gives the first char
     * of the line break. Throws IndexOutOfBoundsException for a line that does not exist.
     */
    fun getLineCharCode(lineNumber: Int, index: Int): Int {
        this.checkLineNumber(lineNumber)
        return this._pieceTree.getLineCharCode(lineNumber, index)
    }

    fun getCharCode(offset: Int) = this._pieceTree.getCharCode(offset)

    /** Length of a line without its line break. Throws IndexOutOfBoundsException for a line that does not exist. */
    fun getLineLength(lineNumber: Int): Int {
        this.checkLineNumber(lineNumber)
        return this._pieceTree.getLineLength(lineNumber)
    }

    fun getLineMinColumn(lineNumber: Int): Int {
        this.checkLineNumber(lineNumber)
        return 1
    }

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
    fun getNearestChunk(offset: Int, maxLength: Int = Int.MAX_VALUE): String {
	    return this._pieceTree.getNearestChunk(offset, maxLength)
	}

    /**
     * Clamps a range into the document.
     *
     * The piece tree itself rejects out of range offsets, and an editor can easily hand over a
     * stale range (async formatter, undo stack, a diff computed on an older revision). Clamping
     * here keeps a stale range from turning into an exception deeper down, and also detaches the
     * caller's mutable Range object from the recorded edit.
     */
    // NOTE: unlike VS Code's validateRange this does not move a column that falls between the
    // two halves of a surrogate pair, so an edit can still split an emoji.
    private fun _validateRange(range: Range): Range {
        val lineCount = this.getLineCount()

        val startLine = range.startLine.coerceIn(1, lineCount)
        val startColumn = range.startColumn.coerceIn(1, this.getLineMaxColumn(startLine))
        val endLine = range.endLine.coerceIn(1, lineCount)
        val endColumn = range.endColumn.coerceIn(1, this.getLineMaxColumn(endLine))

        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            // reversed after clamping, collapse it to the start position
            return Range(startLine, startColumn, startLine, startColumn)
        }
        return Range(startLine, startColumn, endLine, endColumn)
    }

    private fun _getEndOfLine(eol: EndOfLine) = when (eol) {
        EndOfLine.LF -> "\n"
        EndOfLine.CRLF -> "\r\n"
        EndOfLine.TextDefined -> this.getEOL()
        else -> throw IllegalArgumentException("Unknown EOL preference")
    }

    fun setEOL(newEOL: String) = this._pieceTree.setEOL(newEOL)

    /**
     * Rebuilds the storage from the current text: releases deleted text kept in the change buffer
     * and merges the pieces into fresh 64K chunks. Text, EOL, positions and undo data are
     * unchanged. O(n), an edit (needs the write lock). See the README for when to call it.
     */
    fun compact() = this._pieceTree.compact()

    /** Memory figures, to decide when [compact] is worth it. O(number of pieces). */
    fun getStorageStats(): StorageStats = this._pieceTree.getStorageStats()
    fun getEOL() = this._pieceTree.getEOL()
    
    /**
     * Regex search inside [searchRange] (clamped into the document first).
     *
     * Two engines, as in VS Code:
     *  - multiline: when the regex has RegexOption.MULTILINE or its source can match a line
     *    break (a literal line break, `\n`, `\r` or `\W`, see Strings.isMultilineRegexSource).
     *    The whole range is joined with "\n" and searched at once, so a match may span lines.
     *    MULTILINE is added if missing, so `^` / `$` match at every line start / end;
     *  - line by line: everything else. Each line is searched on its own, a match never spans a
     *    line break, `^` / `$` match at the line start / end.
     * In both, `^` and `$` therefore mean "start / end of a line", like VS Code's `m` flag.
     */
    fun find(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> {
        val range = this._validateRange(searchRange)
        val hasMultilineFlag = regex.options.contains(RegexOption.MULTILINE)
        if (hasMultilineFlag || Strings.isMultilineRegexSource(regex.pattern)) {
            val multilineRegex =
                if (hasMultilineFlag) regex else Regex(regex.pattern, regex.options + RegexOption.MULTILINE)
            return this._pieceTree.findMatchesByMultiline(multilineRegex, range, limitResultCount, isCancelled)
        }
        return this._pieceTree.findMatchesLineByLine(regex, range, limitResultCount, isCancelled)
    }

    // Plain-text search inside [searchRange] (clamped into the document first). A search text
    // with line breaks matches across lines, whichever line break kind it uses.
    fun find(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): MutableList<Range> = this._pieceTree.findMatchesByWord(
        searchText,
        this._validateRange(searchRange),
        limitResultCount,
        isCancelled
    )

    // text edits
    /**
     * Applies a batch of edits as one atomic change.
     *
     * All ranges refer to the document BEFORE the batch. The pipeline:
     *  1. validate each op: clamp its range into the document, compute its offset/length and
     *     normalize line breaks in its text to the buffer EOL;
     *  2. sort ascending by range and reject overlapping ranges (touching ones are allowed);
     *  3. optionally collapse 1000+ ops into one (_reduceOperations);
     *  4. if requested, compute the inverse edits: the ranges the new texts will occupy AFTER
     *     the batch (getInverseEditRanges) together with the old texts. Applying them as
     *     another batch undoes this one;
     *  5. apply the ops bottom-up (_doApplyEdits).
     *
     * @param recordTrimAutoWhitespace collect lines that only received auto-inserted
     *   whitespace, so the caller can trim them on the next edit (VS Code's trimAutoWhitespace).
     * @param computeUndoEdits fill [ApplyEditsResult.reverseEdits].
     */
    fun applyEdits(
        rawOperations: List<SingleEditOperation>,
        recordTrimAutoWhitespace: Boolean = false,
        computeUndoEdits: Boolean = false
    ): ApplyEditsResult {
        var mightContainRTL = this._mightContainRTL
        var mightContainUnusualLineTerminators = this._mightContainUnusualLineTerminators
        var mightContainNonBasicASCII = this._mightContainNonBasicASCII

        var operations = mutableListOf<ValidatedEditOperation>()
        for (i in 0..rawOperations.size - 1) {
            val op = rawOperations[i]
            var validText = ""
            // compute text eol
            val (eolCount, firstLineLength, lastLineLength, eol) = Strings.countEOL(op.text)

            val validatedRange = this._validateRange(op.range)
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

                // The text is inserted with eolNormalized = true (_doApplyEdits), so it must not
                // keep any line break other than the buffer's. countEOL reports Invalid for a lone
                // "\r" and for MIXED kinds, so e.g. "a\r\nb\nc" pasted into an LF buffer is
                // normalized as a whole instead of slipping through as "LF".
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
                isAutoWhitespaceEdit = op.isAutoWhitespaceEdit
            ))
        }

        // sort operations ascending
        // sortIndex keeps the caller's order for ops with identical ranges (e.g. two inserts at
        // the same position), so their texts end up in the order they were given
        operations.sortWith(::sortOpsAscending)

        var hasTouchingRanges = false
        // operations.size must be > 1
        for (i in 0 until operations.size - 1) {
            val rangeEnd = operations[i].range.getEndPosition()
            val nextRangeStart = operations[i + 1].range.getStartPosition()

            if (nextRangeStart.isBeforeOrEqual(rangeEnd)) {
                if (nextRangeStart.isBefore(rangeEnd)) {
                    // overlapping ranges
                    // IllegalArgumentException, not kotlin.Error (= java.lang.Error), so that a
                    // caller's `catch (e: Exception)` sees it
                    throw IllegalArgumentException("Overlapping ranges are not allowed!")
                }
                hasTouchingRanges = true
            }
        }

        // VS Code skips this for ops on tracked ranges (decorations), which this port does not have
        operations = this._reduceOperations(operations)

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

        // Inverse edits: op i's inverse replaces reverseRanges[i] (where its new text will be)
        // with the text currently in op.range. reverseRangeDeltaOffset accumulates how much the
        // ops before op i grow or shrink the document, to place its TextChange in the new text.
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
                reverseOperations.sortBy { it.sortIndex }
            }
        }

        this._mightContainRTL = mightContainRTL
        this._mightContainUnusualLineTerminators = mightContainUnusualLineTerminators
        this._mightContainNonBasicASCII = mightContainNonBasicASCII

        val contentChanges = this._doApplyEdits(operations)

        var trimAutoWhitespaceLineNumbers: MutableList<Int>? = null
        if (recordTrimAutoWhitespace && newTrimAutoWhitespaceCandidates.size > 0) {
            // sort line numbers auto whitespace removal candidates for next edit descending
            newTrimAutoWhitespaceCandidates.sortByDescending { it.first } // first => lineNumber

            trimAutoWhitespaceLineNumbers = mutableListOf()
            for (i in 0..newTrimAutoWhitespaceCandidates.size - 1) {
                val (lineNumber, oldContent) = newTrimAutoWhitespaceCandidates[i]
                if (i > 0 && newTrimAutoWhitespaceCandidates[i - 1].first == lineNumber) {
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

        return ApplyEditsResult(contentChanges, reverseOperations, trimAutoWhitespaceLineNumbers)
    }
    
    // #region slices: undo / redo without copying large content

    /**
     * Content shorter than this many chars is exported as text, longer content as references
     * into the storage; see [TextSlice]. Internal so that tests can force either form.
     */
    internal var sliceTextThreshold: Int = TextSlice.TEXT_THRESHOLD

    /**
     * The content of [range] (clamped into the document) as a [TextSlice]. A read: it changes
     * nothing. Large content is not copied, the slice refers to the storage it lies in.
     */
    fun exportSlice(range: Range): TextSlice = this._pieceTree.exportSlice(
        this._validateRange(range),
        this.sliceTextThreshold,
        this._mightContainRTL,
        this._mightContainUnusualLineTerminators,
        this._mightContainNonBasicASCII
    )

    // one validated edit of applySliceEdits
    private class SliceOperation(
        val sortIndex: Int,
        val range: Range,
        val rangeOffset: Int,
        val rangeLength: Int,
        // the content to insert: unchanged, or (null) as the normalized `text`
        val asIs: TextSlice?,
        val text: String?,
        // the content as given, for its line shape
        val content: TextSlice?
    ) {
        // what the edit removes, exported before anything changes
        var removed: TextSlice? = null

        val length: Int
            get() = this.text?.length ?: (this.asIs?.length ?: 0)
    }

    /**
     * Applies a batch of edits whose new content is given as [TextSlice]s, and returns the batch
     * that undoes it, also as slices: undo = applySliceEdits(result.reverseEdits), redo =
     * applySliceEdits(undoResult.reverseEdits). Large removed content is kept as references, not
     * copied, so undoing a huge delete or replace costs almost no memory.
     *
     * Same rules as [applyEdits]: ranges refer to the document before the batch and are clamped
     * into it, overlapping ranges throw IllegalArgumentException (touching ones are fine), the
     * edits are applied from the bottom up. Content is normalized to the buffer's EOL unless it
     * can go in unchanged, see [TextSlice]. A slice that refers to the storage of another buffer
     * throws IllegalArgumentException. Every check happens before the first change.
     *
     * An edit (write lock). Not covered here, unlike applyEdits: trimming of auto-inserted
     * whitespace, forceMoveMarkers, collapsing of 1000+ edits.
     */
    fun applySliceEdits(edits: List<SliceEdit>): SliceEditResult {
        val tree = this._pieceTree
        val bufferEOL = this.getEOL()
        val expectedEOL = if (bufferEOL == "\r\n") EndOfLine.CRLF else EndOfLine.LF
        var mightContainRTL = this._mightContainRTL
        var mightContainUnusualLineTerminators = this._mightContainUnusualLineTerminators
        var mightContainNonBasicASCII = this._mightContainNonBasicASCII

        // 1. validate, and decide how each content goes in
        val operations = ArrayList<SliceOperation>(edits.size)
        for (i in edits.indices) {
            val edit = edits[i]
            val range = this._validateRange(edit.range)
            val content = edit.content?.takeIf { it.length > 0 }
            var asIs: TextSlice? = null
            var text: String? = null
            if (content != null) {
                if (content.canInsertAsIs(tree)) {
                    asIs = content
                    mightContainRTL = mightContainRTL || content.mightContainRTL
                    mightContainUnusualLineTerminators =
                        mightContainUnusualLineTerminators || content.mightContainUnusualLineTerminators
                    mightContainNonBasicASCII = mightContainNonBasicASCII || content.mightContainNonBasicASCII
                } else {
                    require(content.isText || content.owner === tree) {
                        "the TextSlice refers to the storage of another buffer; " +
                            "move content between buffers with TextSlice.of(slice.toText())"
                    }
                    // normalized like the text of applyEdits
                    val raw = content.toText()
                    val eol = Strings.countEOL(raw).eol
                    val valid = if (eol == EndOfLine.TextDefined || eol == expectedEOL) {
                        raw
                    } else {
                        raw.replace(Strings.newLine, bufferEOL)
                    }
                    var textMightContainNonBasicASCII = true
                    if (!mightContainNonBasicASCII) {
                        textMightContainNonBasicASCII = !Strings.isBasicASCII(valid)
                        mightContainNonBasicASCII = textMightContainNonBasicASCII
                    }
                    if (!mightContainRTL && textMightContainNonBasicASCII) {
                        mightContainRTL = Strings.containsRTL(valid)
                    }
                    if (!mightContainUnusualLineTerminators && textMightContainNonBasicASCII) {
                        mightContainUnusualLineTerminators = Strings.containsUnusualLineTerminators(valid)
                    }
                    text = valid
                }
            }
            operations.add(
                SliceOperation(
                    i,
                    range,
                    this.getOffsetAt(range.startLine, range.startColumn),
                    this.getValueLengthInRange(range),
                    asIs,
                    text,
                    content
                )
            )
        }

        // 2. sort ascending, reject overlaps
        operations.sortWith { a, b ->
            val r = Range.compareRangesUsingEnds(a.range, b.range)
            if (r == 0) a.sortIndex - b.sortIndex else r
        }
        var hasTouchingRanges = false
        for (i in 0 until operations.size - 1) {
            val rangeEnd = operations[i].range.getEndPosition()
            val nextRangeStart = operations[i + 1].range.getStartPosition()
            if (nextRangeStart.isBeforeOrEqual(rangeEnd)) {
                if (nextRangeStart.isBefore(rangeEnd)) {
                    throw IllegalArgumentException("Overlapping ranges are not allowed!")
                }
                hasTouchingRanges = true
            }
        }

        // 3. the reverse batch: where each new content will be, and what it replaces, exported
        // now, before anything changes
        val inverseRanges = computeInverseRanges(operations.map {
            InverseSource(
                it.range,
                it.length,
                it.content?.eolCount ?: 0,
                it.content?.firstLineLength ?: 0,
                it.content?.lastLineLength ?: 0
            )
        })
        val reverse = ArrayList<Pair<Int, SliceEdit>>(operations.size)
        for (i in operations.indices) {
            val op = operations[i]
            val removed = if (op.range.isEmpty()) null else this.exportSlice(op.range)
            op.removed = removed
            reverse.add(Pair(op.sortIndex, SliceEdit(inverseRanges[i], removed)))
        }
        // the caller's order, unless touching ranges make the order significant (as applyEdits)
        if (!hasTouchingRanges) {
            reverse.sortBy { it.first }
        }

        // 4. apply, from the bottom of the document to the top
        this._mightContainRTL = mightContainRTL
        this._mightContainUnusualLineTerminators = mightContainUnusualLineTerminators
        this._mightContainNonBasicASCII = mightContainNonBasicASCII

        operations.sortWith { a, b ->
            val r = Range.compareRangesUsingEnds(a.range, b.range)
            if (r == 0) b.sortIndex - a.sortIndex else -r
        }
        val changes = ArrayList<SliceChange>(operations.size)
        for (op in operations) {
            tree.delete(op.rangeOffset, op.rangeLength)
            val inserted: TextSlice? = when {
                op.asIs != null -> {
                    tree.insertSlice(op.rangeOffset, op.asIs, op.asIs.eolNormalized)
                    op.asIs
                }
                op.text != null && op.text.length > 0 -> {
                    tree.insert(op.rangeOffset, op.text, true)
                    if (op.text === op.content?.text) op.content else TextSlice.of(op.text)
                }
                else -> null
            }
            changes.add(SliceChange(op.range, op.rangeOffset, op.rangeLength, inserted, op.removed))
        }

        return SliceEditResult(changes, reverse.map { it.second })
    }

    /**
     * [applyEdits] for text edits, with the undo batch as slices: the text an edit removes is
     * kept as references into the storage when it is large (see [TextSlice]). Use the result
     * like the one of [applySliceEdits].
     */
    fun applyEditsWithSliceUndo(operations: List<SingleEditOperation>): SliceEditResult =
        this.applySliceEdits(operations.map { op ->
            val text = op.text
            SliceEdit(op.range, if (text.isNullOrEmpty()) null else TextSlice.of(text))
        })

    // #endregion

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
        val result = StringBuilder()

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

    // Applies the ops from the bottom of the document to the top. Every op's rangeOffset was
    // computed against the ORIGINAL document; editing later ranges first never moves the text
    // before them, so those offsets stay valid without any rebasing.
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
    companion object {
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
        fun getInverseEditRanges(operations: List<ValidatedEditOperation>): List<Range> =
            computeInverseRanges(operations.map {
                InverseSource(it.range, it.text!!.length, it.eolCount, it.firstLineLength, it.lastLineLength)
            })

        // An op's range and the shape of what it inserts: all computeInverseRanges needs
        internal class InverseSource(
            val range: Range,
            val length: Int,
            val eolCount: Int,
            val firstLineLength: Int,
            val lastLineLength: Int
        )

        // For ops sorted ascending: where each op's new content will sit once ALL ops are applied.
        // Each range is derived from the previous op's resulting end: on the same line the
        // column distance is kept, otherwise the line distance is kept and the column copied.
        internal fun computeInverseRanges(operations: List<InverseSource>): List<Range> {
            val result = mutableListOf<Range>()

            var prevOpEndLineNumber = 0
            var prevOpEndColumn = 0
            var prevOp: InverseSource? = null
            for (op in operations) {
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

                if (op.length > 0) {
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

