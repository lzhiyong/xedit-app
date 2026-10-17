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

package x.github.module.editor.model

import kotlin.text.Regex

import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.SliceEditResult
import x.github.module.piecetable.common.ApplyEditsResult
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.SingleEditOperation

/**
 * The buffer the editor comes with: a piece tree (the structure of VS Code's text
 * buffer), which loads a large file without copying it around and edits it in
 * logarithmic time.
 *
 * This class adds nothing to it. It only puts the piece tree behind the [EditorBuffer]
 * interface the editor works with (including [applySliceEdits], which keeps what an edit
 * removed as references into the tree instead of copying it); [pieceTree] is there for whoever needs the tree
 * itself (saving the editor's state, a parser that reads it directly).
 */
class PieceTreeBuffer(val pieceTree: PieceTreeTextBuffer) : EditorBuffer {

    override val length: Int get() = pieceTree.length

    override fun getEOL(): String = pieceTree.getEOL()

    override fun getLineCount(): Int = pieceTree.getLineCount()

    override fun getLineContent(lineNumber: Int): String = pieceTree.getLineContent(lineNumber)

    override fun getLineContentWithEOL(lineNumber: Int): String = pieceTree.getLineContentWithEOL(lineNumber)

    override fun getLineLength(lineNumber: Int): Int = pieceTree.getLineLength(lineNumber)

    override fun getLineMinColumn(lineNumber: Int): Int = pieceTree.getLineMinColumn(lineNumber)

    override fun getLineMaxColumn(lineNumber: Int): Int = pieceTree.getLineMaxColumn(lineNumber)

    override fun getLineCharCode(lineNumber: Int, index: Int): Int = pieceTree.getLineCharCode(lineNumber, index)

    override fun getCharCode(offset: Int): Int = pieceTree.getCharCode(offset)

    override fun getOffsetAt(lineNumber: Int, column: Int): Int = pieceTree.getOffsetAt(lineNumber, column)

    override fun getPositionAt(offset: Int): Position = pieceTree.getPositionAt(offset)

    override fun getValueInRange(range: Range): String = pieceTree.getValueInRange(range)

    override fun getValueLengthInRange(range: Range): Int = pieceTree.getValueLengthInRange(range)

    override fun getText(): String = pieceTree.toString()

    override fun find(
        regex: Regex,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range> = pieceTree.find(regex, searchRange, limitResultCount, isCancelled)

    override fun find(
        searchText: String,
        searchRange: Range,
        limitResultCount: Int,
        isCancelled: () -> Boolean
    ): List<Range> = pieceTree.find(searchText, searchRange, limitResultCount, isCancelled)

    override fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean
    ): ApplyEditsResult = pieceTree.applyEdits(operations, false, computeUndoEdits)

    // removed content of 16K chars and more is kept as references into the tree's storage
    override fun applySliceEdits(edits: List<SliceEdit>): SliceEditResult = pieceTree.applySliceEdits(edits)

    override fun toString(): String = pieceTree.toString()
}
