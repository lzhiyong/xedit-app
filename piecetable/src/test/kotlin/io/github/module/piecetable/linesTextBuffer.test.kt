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
import java.util.ArrayDeque

import kotlin.text.Regex
import kotlin.text.RegexOption
import kotlin.test.*
import kotlinx.coroutines.*

class PieceTextBufferTest {

    // #region undo and redo
    internal val _pieceBuffer = PieceTreeTextBufferBuilder().build()
    internal val _pieceTree = _pieceBuffer.getPieceTree()
    internal val _undoStack = ArrayDeque<List<TextChange>?>()
    internal val _redoStack = ArrayDeque<List<TextChange>?>()
    
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var job: Job? = null
    
    internal var previousEditChanges: List<TextChange>? = null
    
    internal fun canUndo() = this._undoStack.size > 0
    internal fun canRedo() = this._redoStack.size > 0
    
    internal fun undo(): Position? {
        if(!canUndo()) {
            return null // nothing to do
        }
        
        // pop the top element from undo stack
        val changes = this._undoStack.poll()
        // then add the element to redo stack
        this._redoStack.push(changes)
        
        val operations = mutableListOf<SingleEditOperation>()
        changes?.forEach {
            val posStart = this._pieceBuffer.getPositionAt(it.newPosition)
            val posEnd = this._pieceBuffer.getPositionAt(it.newEnd)
            val range = Range(
                posStart.lineNumber, 
                posStart.column,
                posEnd.lineNumber, 
                posEnd.column
            )
            operations.add(SingleEditOperation(range, it.oldText))
        }
        
        return this.applyEdits(operations, false, false)
    }
    
    internal fun redo(): Position? {
        if(!canRedo()) {
            return null // nothing to do
        }
        
        // pop the top element from undo stack
        val changes = this._redoStack.poll()
        // then add the element to redo stack
        this._undoStack.push(changes)
        
        val operations = mutableListOf<SingleEditOperation>()
        changes?.forEach {
            val posStart = this._pieceBuffer.getPositionAt(it.oldPosition)
            val posEnd = this._pieceBuffer.getPositionAt(it.oldEnd)
            val range = Range(
                posStart.lineNumber, 
                posStart.column,
                posEnd.lineNumber, 
                posEnd.column
            )
            operations.add(SingleEditOperation(range, it.newText))
        }
        
        return this.applyEdits(operations, false, false)
    }
    
    // real edits and return the cursor position
    internal fun applyEdits(
        operations: List<SingleEditOperation>,
        recordTrimAutoWhitespace: Boolean = false,
        computeUndoEdits: Boolean = true
    ): Position? {
        // cancel the job
        job?.cancel()
        // computeUndoEdits = true for undo and redo
        val result = this._pieceBuffer.applyEdits(operations, recordTrimAutoWhitespace, computeUndoEdits)
        // you may need to setup the cursor position
        val cursor = Position()
        //println(result.changes)
        for(change in result.changes) {
            val (eolCount, _, lastLineLength, _) = countEOL(change.text!!)
            val startLine = change.range.startLine
            val endLine = change.range.endLine

            val deletingLinesCnt = endLine - startLine
            val insertingLinesCnt = eolCount
            val editingLinesCnt = Math.min(deletingLinesCnt, insertingLinesCnt)
            val changeLineCnt = insertingLinesCnt - deletingLinesCnt
            var lastLineChangeLength = 0 // text changed length
            
            if(change.text!!.length > 0) {
                // insert or replace text
                lastLineChangeLength = lastLineLength + change.range.startColumn - change.range.endColumn
                cursor.column = change.range.endColumn + lastLineChangeLength
            } else {
                // delete text
                lastLineChangeLength = change.range.startColumn - change.range.endColumn
                cursor.column = change.range.startColumn
            }
            
            if(editingLinesCnt < deletingLinesCnt) {
                // must delete some lines
                // int splicestartLine = startLine + editingLinesCnt
            }

            if(editingLinesCnt < insertingLinesCnt) {
                // must insert some lines
                cursor.column = lastLineLength + 1
                // insert last character is '\n'
                if(lastLineChangeLength == 0) {
                    lastLineChangeLength = -change.range.endColumn + 1
                }
            }
            
            cursor.lineNumber = endLine + changeLineCnt
        }
        
        if(computeUndoEdits) {
            pushEdits(result.reverseEdits?.toMutableList())
            // clear the redo stack when computeUndoEdits = true
            // note that if the computeUndoEdits is false no need to clear
            this._redoStack.clear()
        }
        
        return cursor
    }
    
    internal fun pushEdits(reverseEdits: MutableList<ReverseEditOperation>?) {
        reverseEdits?.let {reverses ->
            /*
            reverses.sortWith(Comparator{a, b -> 
                if(a.textChange.oldPosition == b.textChange.oldPosition) {
                    a.sortIndex - b.sortIndex
                }
                a.textChange.oldPosition - b.textChange.oldPosition
            })
            */
            previousEditChanges = TextChange.compressConsecutiveTextChanges(
                previousEditChanges,
                reverses.map{it.textChange}
            )
            
            // launch a coroutine to push edits
            job = scope.launch {
                // delay 750ms then merge the edits
                delay(750L)
                _undoStack.push(previousEditChanges)
                // reset previousEditChanges
                previousEditChanges = null
            }
        }
    }
    
    @Test
    fun `test basic insert undo and redo`() = runBlocking<Unit> {
        var pos = applyEdits(listOf(SingleEditOperation(
            Range(1, 1, 1, 1), "hello"
        )))
        assertEquals(pos, Position(1, 6))
        
        pos = applyEdits(listOf(SingleEditOperation(
            Range(1, 6, 1, 6), "world"
        )))
        assertEquals(pos, Position(1, 11))
    
        delay(1000L)
       
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), "")
        assertEquals(pos, Position(1, 1))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), "helloworld")
        assertEquals(pos, Position(1, 11))
        
        job?.cancelAndJoin()
    }
    
    @Test
    fun `test basic delete undo and redo`() = runBlocking<Unit> {
    
        _pieceTree.insert(0, "hello\nworld\n")
        
        var pos = applyEdits(listOf(SingleEditOperation(
            Range(1, 1, 1, 6), null
        )))
        assertEquals(pos, Position(1, 1))
        
        applyEdits(listOf(SingleEditOperation(
            Range(1, 1, 2, 6), null
        )))
        assertEquals(pos, Position(1, 1))
        
        applyEdits(listOf(SingleEditOperation(
            Range(1, 1, 2, 1), null
        )))
        assertEquals(pos, Position(1, 1))
        
        delay(1000L)
        
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), "hello\nworld\n")
        assertEquals(pos, Position(3, 1))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), "")
        assertEquals(pos, Position(1, 1))
        
        job?.cancelAndJoin()
    }
    
    @Test
    fun `test basic replace undo and redo`() = runBlocking<Unit> {
        val str = "hello world hello\nworld hello world\nhellohello"
        _pieceTree.insert(0, str)
        val result = _pieceBuffer.find(
            Range(1, 1, 3, 11),
            SearchData("hello".toRegex(), null, null),
            1000
        )
        
        assertEquals(result.size, 5)
        
        var pos = applyEdits(result.map{ SingleEditOperation(it, "hi") })
        assertEquals(_pieceTree.getLinesRawContent(), str.replace("hello", "hi"))
        assertEquals(pos, Position(1, 3))
        
        delay(1000L)
        
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), str)
        assertEquals(pos, Position(1, 6))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), str.replace("hello", "hi"))
        assertEquals(pos, Position(1, 3))
        
        job?.cancelAndJoin()
    }
    
    @Test
    fun `test multiline insert undo and redo`() = runBlocking<Unit> {
        _pieceTree.insert(0, "hello\nworld")
        
        var pos = applyEdits(listOf(SingleEditOperation(
            Range(1, 6, 1, 6), "😁\nthis is multi line insert test"
        )))
        
        assertEquals(_pieceTree.getLinesRawContent(), "hello😁\nthis is multi line insert test\nworld")
        assertEquals(pos, Position(2, 31))
        
        delay(1000L)
        
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), "hello\nworld")
        assertEquals(pos, Position(1, 6))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), "hello😁\nthis is multi line insert test\nworld")
        assertEquals(pos, Position(2, 31))
        
        job?.cancelAndJoin()
    }
    
     @Test
    fun `test multiline delete undo and redo`() = runBlocking<Unit> {
        _pieceTree.insert(0, "hi hello\nworld\nthis is multi line delete test\n😄")
        
        var pos = applyEdits(listOf(SingleEditOperation(
            Range(1, 4, 4, 1), null
        )))
        
        assertEquals(_pieceTree.getLinesRawContent(), "hi 😄")
        assertEquals(pos, Position(1, 4))
        
        delay(1000L)
        
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), "hi hello\nworld\nthis is multi line delete test\n😄")
        assertEquals(pos, Position(4, 1))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), "hi 😄")
        assertEquals(pos, Position(1, 4))
        
        job?.cancelAndJoin()
    }
    
     @Test
    fun `test multiline replace undo and redo`() = runBlocking<Unit> {
        _pieceTree.insert(0, "hi hello\nworld\nthis is multi line replace test, hello world hello\nworld hi kotlin")
        val result = _pieceBuffer.find(
            Range(1, 1, 4, 16),
            SearchData("hello\nworld".toRegex(RegexOption.MULTILINE), null, null),
            1000
        )
        assertEquals(result.size, 2)
        
        var pos = applyEdits(result.map{ SingleEditOperation(it, "😍") })     
        assertEquals(_pieceTree.getLinesRawContent(), "hi 😍\nthis is multi line replace test, hello world 😍 hi kotlin")
        assertEquals(pos, Position(1, 6))
        
        delay(1000L)
        
        pos = undo()
        assertEquals(_pieceTree.getLinesRawContent(), "hi hello\nworld\nthis is multi line replace test, hello world hello\nworld hi kotlin")
        assertEquals(pos, Position(2, 6))
        
        pos = redo()
        assertEquals(_pieceTree.getLinesRawContent(), "hi 😍\nthis is multi line replace test, hello world 😍 hi kotlin")
        assertEquals(pos, Position(1, 6))
        
        job?.cancelAndJoin()
    }
    
    // #endregion

    // #region PieceTreeTextBuffer getInverseEdits
    internal fun editOp(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        text: Array<String>? 
    ): ValidatedEditOperation {
        return ValidatedEditOperation(
            sortIndex = 0,
            identifier = null,
            range = Range(startLine, startColumn, endLine, endColumn),
            rangeOffset = 0,
            rangeLength = 0,
            text = if (text != null) text.joinToString("\n") else "",
            eolCount = if (text != null) text.size - 1 else 0,
            firstLineLength = if (text != null) text[0].length else 0,
            lastLineLength = if (text != null) text[text.size - 1].length else 0,
            forceMoveMarkers = false,
            isAutoWhitespaceEdit = false
        )
    }

    fun inverseEditOp(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Range {
        return Range(startLine, startColumn, endLine, endColumn)
    }

    fun assertInverseEdits(ops: List<ValidatedEditOperation>, expected: List<Range>) {
        val act = PieceTreeTextBuffer.getInverseEditRanges(ops)
        assertContentEquals(act, expected)
    }

    @Test
    fun `single insert`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 1, arrayOf("hello"))),
            listOf(inverseEditOp(1, 1, 1, 6))
        )
    }

    @Test
    fun `Bug 19872 Undo is funky`() {
        assertInverseEdits(
            listOf(editOp(2, 1, 2, 2, arrayOf("")), editOp(3, 1, 4, 2, arrayOf(""))),
            listOf(inverseEditOp(2, 1, 2, 1), inverseEditOp(3, 1, 3, 1))
        )
    }

    @Test
    fun `two single unrelated inserts`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 1, arrayOf("hello")), editOp(2, 1, 2, 1, arrayOf("world"))),
            listOf(inverseEditOp(1, 1, 1, 6), inverseEditOp(2, 1, 2, 6))
        )
    }

    @Test
    fun `two single inserts 1`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 1, arrayOf("hello")), editOp(1, 2, 1, 2, arrayOf("world"))),
            listOf(inverseEditOp(1, 1, 1, 6), inverseEditOp(1, 7, 1, 12))
        )
    }

    @Test
    fun `two single inserts 2`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 1, arrayOf("hello")), editOp(1, 4, 1, 4, arrayOf("world"))),
            listOf(inverseEditOp(1, 1, 1, 6), inverseEditOp(1, 9, 1, 14))
        )
    }

    @Test
    fun `multiline insert`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 1, arrayOf("hello", "world"))),
            listOf(inverseEditOp(1, 1, 2, 6))
        )
    }

    @Test
    fun `two unrelated multiline inserts`() {
        assertInverseEdits(
            listOf(
                editOp(1, 1, 1, 1, arrayOf("hello", "world")),
                editOp(2, 1, 2, 1, arrayOf("how", "are", "you?")),
            ),
            listOf(
                inverseEditOp(1, 1, 2, 6),
                inverseEditOp(3, 1, 5, 5),
            )
        )
    }

    @Test
    fun `two multiline inserts 1`() {
        assertInverseEdits(
            listOf(
                editOp(1, 1, 1, 1, arrayOf("hello", "world")),
                editOp(1, 2, 1, 2, arrayOf("how", "are", "you?")),
            ),
            listOf(
                inverseEditOp(1, 1, 2, 6),
                inverseEditOp(2, 7, 4, 5),
            )
        )
    }

    @Test
    fun `single delete`() {
        assertInverseEdits(listOf(editOp(1, 1, 1, 6, null)), listOf(inverseEditOp(1, 1, 1, 1)))
    }

    @Test
    fun `two single unrelated deletes`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 6, null), editOp(2, 1, 2, 6, null)),
            listOf(inverseEditOp(1, 1, 1, 1), inverseEditOp(2, 1, 2, 1))
        )
    }

    @Test
    fun `two single deletes 1`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 6, null), editOp(1, 7, 1, 12, null)),
            listOf(inverseEditOp(1, 1, 1, 1), inverseEditOp(1, 2, 1, 2))
        )
    }

    @Test
    fun `two single deletes 2`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 6, null), editOp(1, 9, 1, 14, null)),
            listOf(inverseEditOp(1, 1, 1, 1), inverseEditOp(1, 4, 1, 4))
        )
    }

    @Test
    fun `multiline delete`() {
        assertInverseEdits(listOf(editOp(1, 1, 2, 6, null)), listOf(inverseEditOp(1, 1, 1, 1)))
    }

    @Test
    fun `two unrelated multiline deletes`() {
        assertInverseEdits(
            listOf(
                editOp(1, 1, 2, 6, null),
                editOp(3, 1, 5, 5, null),
            ),
            listOf(
                inverseEditOp(1, 1, 1, 1),
                inverseEditOp(2, 1, 2, 1),
            )
        )
    }

    @Test
    fun `two multiline deletes 1`() {
        assertInverseEdits(
            listOf(
                editOp(1, 1, 2, 6, null),
                editOp(2, 7, 4, 5, null),
            ),
            listOf(
                inverseEditOp(1, 1, 1, 1),
                inverseEditOp(1, 2, 1, 2),
            )
        )
    }

    @Test
    fun `single replace`() {
        assertInverseEdits(
            listOf(editOp(1, 1, 1, 6, arrayOf("Hello world"))),
            listOf(inverseEditOp(1, 1, 1, 12))
        )
    }

    @Test
    fun `two replaces`() {
        assertInverseEdits(
            listOf(
                editOp(1, 1, 1, 6, arrayOf("Hello world")),
                editOp(1, 7, 1, 8, arrayOf("How are you?")),
            ),
            listOf(inverseEditOp(1, 1, 1, 12), inverseEditOp(1, 13, 1, 25))
        )
    }

    @Test
    fun `many edits 1`() {
        assertInverseEdits(
            listOf(
                editOp(1, 2, 1, 2, arrayOf("", "  ")),
                editOp(1, 5, 1, 6, arrayOf("")),
                editOp(1, 9, 1, 9, arrayOf("", ""))
            ),
            listOf(inverseEditOp(1, 2, 2, 3), inverseEditOp(2, 6, 2, 6), inverseEditOp(2, 9, 3, 1))
        )
    }

    // #endregion

    // #region PieceTreeTextBuffer toSingleEditOperation
    internal fun editOp(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        rangeOffset: Int, 
        rangeLength: Int,
        text: Array<String>? 
    ): ValidatedEditOperation {
        return ValidatedEditOperation(
            sortIndex = 0,
            identifier = null,
            range = Range(startLine, startColumn, endLine, endColumn),
            rangeOffset = rangeOffset,
            rangeLength = rangeLength,
            text = if (text != null) text.joinToString("\n") else "",
            eolCount = if (text != null) text.size - 1 else 0,
            firstLineLength = if (text != null) text[0].length else 0,
            lastLineLength = if (text != null) text[text.size - 1].length else 0,
            forceMoveMarkers = false,
            isAutoWhitespaceEdit = false
        )
    }
    
    fun testToSingleEditOperation(
        original: Array<String>,
        edits: List<ValidatedEditOperation>,
        expected: ValidatedEditOperation
    ) {
        val pieceBuilder = PieceTreeTextBufferBuilder()
        pieceBuilder.acceptChunk(original.joinToString("\n"))
        val textBuffer = pieceBuilder.build(LineBreaking.LF)
        val act = textBuffer.toSingleEditOperation(edits)
        assertEquals(act, expected)
    }

    @Test
    fun `one edit op is unchanged`() {
        testToSingleEditOperation(
            arrayOf("My First Line", "\t\tMy Second Line", "    Third Line", "", "1"),
            listOf(editOp(1, 3, 1, 3, 2, 0, arrayOf(" new line", "No longer"))),
            editOp(1, 3, 1, 3, 2, 0, arrayOf(" new line", "No longer"))
        )
    }

    @Test
    fun `two edits on one line`() {
        testToSingleEditOperation(
            arrayOf("My First Line", "\t\tMy Second Line", "    Third Line", "", "1"),
            listOf(
                editOp(1, 1, 1, 3, 0, 2, arrayOf("Your")),
                editOp(1, 4, 1, 4, 3, 0, arrayOf("Interesting ")),
                editOp(2, 3, 2, 6, 16, 3, null)
            ),
            editOp(1, 1, 2, 6, 0, 19, arrayOf("Your Interesting First Line", "\t\t"))
        )
    }

    @Test
    fun `insert multiple newlines`() {
        testToSingleEditOperation(
            arrayOf("My First Line", "\t\tMy Second Line", "    Third Line", "", "1"),
            listOf(
                editOp(1, 3, 1, 3, 2, 0, arrayOf("", "", "", "", "")),
                editOp(3, 15, 3, 15, 45, 0, arrayOf("a", "b"))
            ),
            editOp(1, 3, 3, 15, 2, 43, arrayOf("", "", "", "", " First Line", "\t\tMy Second Line", "    Third Linea", "b"))
        )
    }

    @Test
    fun `delete empty text`() {
        testToSingleEditOperation(
            arrayOf("My First Line", "\t\tMy Second Line", "    Third Line", "", "1"),
            listOf(editOp(1, 1, 1, 1, 0, 0, arrayOf(""))),
            editOp(1, 1, 1, 1, 0, 0, arrayOf(""))
        )
    }

    @Test
    fun `two unrelated edits`() {
        testToSingleEditOperation(
            arrayOf("My First Line", "\t\tMy Second Line", "    Third Line", "", "123"),
            listOf(
                editOp(2, 1, 2, 3, 14, 2, arrayOf("\t")),
                editOp(3, 1, 3, 5, 31, 4, arrayOf(""))
            ),
            editOp(2, 1, 3, 5, 14, 21, arrayOf("\tMy Second Line", ""))
        )
    }

    @Test
    fun `many edits 2`() {
        testToSingleEditOperation(
            arrayOf("{\"x\" : 1}"),
            listOf(
                editOp(1, 2, 1, 2, 1, 0, arrayOf("\n  ")),
                editOp(1, 5, 1, 6, 4, 1, arrayOf("")),
                editOp(1, 9, 1, 9, 8, 0, arrayOf("\n"))
            ),
            editOp(1, 2, 1, 9, 1, 7, arrayOf("", "  \"x\": 1", ""))
        )
    }

    @Test
    fun `many edits reversed`() {
        testToSingleEditOperation(
            arrayOf("{", "  \"x\": 1", "}"),
            listOf(
                editOp(1, 2, 2, 3, 1, 3, arrayOf("")),
                editOp(2, 6, 2, 6, 7, 0, arrayOf(" ")),
                editOp(2, 9, 3, 1, 10, 1, arrayOf(""))
            ),
            editOp(1, 2, 3, 1, 1, 10, arrayOf("\"x\" : 1"))
        )
    }

    @Test
    fun `replacing newlines 1`() {
        testToSingleEditOperation(
            arrayOf("{", "\"a\": true,", "", "\"b\": true", "}"),
            listOf(
                editOp(1, 2, 2, 1, 1, 1, arrayOf("", "\t")),
                editOp(2, 11, 4, 1, 12, 2, arrayOf("", "\t"))
            ),
            editOp(1, 2, 4, 1, 1, 13, arrayOf("", "\t\"a\": true,", "\t"))
        )
    }

    @Test
    fun `replacing newlines 2`() {
        testToSingleEditOperation(
            arrayOf(
                "some text",
                "some more text",
                "now comes an empty line",
                "",
                "after empty line",
                "and the last line"
            ),
            listOf(
                editOp(1, 5, 3, 1, 4, 21, arrayOf(" text", "some more text", "some more text")),
                editOp(3, 2, 4, 1, 26, 23, arrayOf("o more lines", "asd", "asd", "asd")),
                editOp(5, 1, 5, 6, 50, 5, arrayOf("zzzzzzzz")),
                editOp(5, 11, 6, 16, 60, 22, arrayOf("1", "2", "3", "4"))
            ),
            editOp(1, 5, 6, 16, 4, 78,
                arrayOf(
                    " text",
                    "some more text",
                    "some more textno more lines",
                    "asd",
                    "asd",
                    "asd",
                    "zzzzzzzz empt1",
                    "2",
                    "3",
                    "4"
                )
            )
        )
    }

    @Test
    fun `advanced`() {
        testToSingleEditOperation(
            arrayOf(
                " {       \"d\": [",
                "             null",
                "        ] /*comment*/",
                "        ,\"e\": /*comment*/ [null] }",
            ),
            listOf(
                editOp(1, 1, 1, 2, 0, 1, arrayOf("")),
                editOp(1, 3, 1, 10, 2, 7, arrayOf("", "  ")),
                editOp(1, 16, 2, 14, 15, 14, arrayOf("", "    ")),
                editOp(2, 18, 3, 9, 33, 9, arrayOf("", "  ")),
                editOp(3, 22, 4, 9, 55, 9, arrayOf("")),
                editOp(4, 10, 4, 10, 65, 0, arrayOf("", "  ")),
                editOp(4, 28, 4, 28, 83, 0, arrayOf("", "    ")),
                editOp(4, 32, 4, 32, 87, 0, arrayOf("", "  ")),
                editOp(4, 33, 4, 34, 88, 1, arrayOf("", ""))
            ),
            editOp(1, 1, 4, 34, 0, 89,
                arrayOf(
                    "{",
                    "  \"d\": [",
                    "    null",
                    "  ] /*comment*/,",
                    "  \"e\": /*comment*/ [",
                    "    null",
                    "  ]",
                    ""
                )
            )
        )
    }

    @Test
    fun `advanced simplified`() {
        testToSingleEditOperation(
            arrayOf("   abc", " ,def"),
            listOf(
                editOp(1, 1, 1, 4, 0, 3, arrayOf("")),
                editOp(1, 7, 2, 2, 6, 2, arrayOf("")),
                editOp(2, 3, 2, 3, 9, 0, arrayOf("", ""))
            ),
            editOp(1, 1, 2, 3, 0, 9, arrayOf("abc,", ""))
        )
    }
    
    // #endregion
}

