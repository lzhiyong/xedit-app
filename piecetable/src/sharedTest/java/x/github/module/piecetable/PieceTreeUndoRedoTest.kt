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
import kotlin.test.*
import kotlin.text.Regex
import kotlin.text.RegexOption

/**
 * Undo and redo.
 *
 * The original drove the "when does an edit land on the undo stack" decision with a 750 ms
 * coroutine debounce, so every case had to `delay(1000)` and wait for it: seconds of dead
 * time, flaky on a slow machine, and what it really tested was the debounce written in the
 * test itself. Here the merge window is explicit, see [commitUndoGroup]: no wall clock
 * involved, same behaviour as before, consecutive edits compress into one undo entry.
 */
class PieceTreeUndoRedoTest {

    private lateinit var buffer: PieceTreeTextBuffer
    private lateinit var tree: PieceTreeBase

    // SENTINEL / DETACHED are shared by every buffer in the process, so the singletons are reset
    // before the buffer of this case is built, not after
    @BeforeTest
    fun setUp() {
        TestSupport.resetGlobalState()
        buffer = PieceTreeTextBufferBuilder().build()
        tree = buffer.getPieceTree()
    }

    private val undoStack = mutableListOf<List<TextChange>>()
    private val redoStack = mutableListOf<List<TextChange>>()

    /** Edits that have not landed yet, what the 750 ms window used to accumulate */
    private var pendingChanges: List<TextChange>? = null

    private fun canUndo() = undoStack.isNotEmpty()

    private fun canRedo() = redoStack.isNotEmpty()

    /** Closes the current merge window and pushes the accumulated edits onto the undo stack */
    private fun commitUndoGroup() {
        pendingChanges?.let {
            undoStack.add(it)
            pendingChanges = null
        }
    }

    private fun pushEdits(reverseEdits: List<ReverseEditOperation>?) {
        reverseEdits?.let { reverses ->
            pendingChanges = TextChange.compressConsecutiveTextChanges(
                pendingChanges,
                reverses.map { it.textChange }
            )
        }
    }

    /**
     * Applies the edits to the buffer and returns the cursor position they leave behind.
     *
     * Returns a nullable Position so that the result lines up with [undo] and [redo], which
     * return null when their stack is empty.
     */
    private fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean = true
    ): Position? {
        val result = buffer.applyEdits(operations, false, computeUndoEdits)
        val cursor = Position()

        for (change in result.changes) {
            val (eolCount, _, lastLineLength, _) = Strings.countEOL(change.text!!)
            val startLine = change.range.startLine
            val endLine = change.range.endLine

            val deletingLinesCnt = endLine - startLine
            val insertingLinesCnt = eolCount
            val editingLinesCnt = Math.min(deletingLinesCnt, insertingLinesCnt)
            val changeLineCnt = insertingLinesCnt - deletingLinesCnt
            var lastLineChangeLength: Int

            if (change.text!!.length > 0) {
                // insert or replace
                lastLineChangeLength = lastLineLength + change.range.startColumn - change.range.endColumn
                cursor.column = change.range.endColumn + lastLineChangeLength
            } else {
                // delete
                lastLineChangeLength = change.range.startColumn - change.range.endColumn
                cursor.column = change.range.startColumn
            }

            if (editingLinesCnt < insertingLinesCnt) {
                // lines are being added
                cursor.column = lastLineLength + 1
                if (lastLineChangeLength == 0) {
                    lastLineChangeLength = -change.range.endColumn + 1
                }
            }

            cursor.lineNumber = endLine + changeLineCnt
        }

        if (computeUndoEdits) {
            pushEdits(result.reverseEdits)
            // a new edit invalidates the redo stack
            redoStack.clear()
        }
        return cursor
    }

    private fun undo(): Position? {
        if (!canUndo()) {
            return null
        }
        val changes = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(changes)

        val operations = changes.map {
            val start = buffer.getPositionAt(it.newPosition)
            val end = buffer.getPositionAt(it.newEnd)
            SingleEditOperation(
                Range(start.lineNumber, start.column, end.lineNumber, end.column),
                it.oldText
            )
        }
        return applyEdits(operations, computeUndoEdits = false)
    }

    private fun redo(): Position? {
        if (!canRedo()) {
            return null
        }
        val changes = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(changes)

        val operations = changes.map {
            val start = buffer.getPositionAt(it.oldPosition)
            val end = buffer.getPositionAt(it.oldEnd)
            SingleEditOperation(
                Range(start.lineNumber, start.column, end.lineNumber, end.column),
                it.newText
            )
        }
        return applyEdits(operations, computeUndoEdits = false)
    }

    // ----------------------------------------------------------- cases

    @Test
    fun `basic insert undo and redo`() {
        var pos: Position? = applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "hello")))
        assertEquals(Position(1, 6), pos)

        pos = applyEdits(listOf(SingleEditOperation(Range(1, 6, 1, 6), "world")))
        assertEquals(Position(1, 11), pos)

        // both insertions share one merge window, a single undo must revert them together
        commitUndoGroup()

        pos = undo()
        assertEquals("", tree.getLinesRawContent())
        assertEquals(Position(1, 1), pos)

        pos = redo()
        assertEquals("helloworld", tree.getLinesRawContent())
        assertEquals(Position(1, 11), pos)

        TestSupport.assertTreeInvariants(tree)
    }

    @Test
    fun `basic delete undo and redo`() {
        tree.insert(0, "hello\nworld\n")

        var pos: Position? = applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 6), null)))
        assertEquals(Position(1, 1), pos)

        applyEdits(listOf(SingleEditOperation(Range(1, 1, 2, 6), null)))
        applyEdits(listOf(SingleEditOperation(Range(1, 1, 2, 1), null)))
        assertEquals("", tree.getLinesRawContent())

        commitUndoGroup()

        pos = undo()
        assertEquals("hello\nworld\n", tree.getLinesRawContent())
        assertEquals(Position(3, 1), pos)

        pos = redo()
        assertEquals("", tree.getLinesRawContent())
        assertEquals(Position(1, 1), pos)
    }

    @Test
    fun `basic replace undo and redo`() {
        val str = "hello world hello\nworld hello world\nhellohello"
        tree.insert(0, str)

        val result = buffer.find(
            regex = Regex("hello"),
            searchRange = Range(1, 1, 3, 11),
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(5, result.size)

        var pos: Position? = applyEdits(result.map { SingleEditOperation(it, "hi") })
        assertEquals(str.replace("hello", "hi"), tree.getLinesRawContent())
        assertEquals(Position(1, 3), pos)

        commitUndoGroup()

        pos = undo()
        assertEquals(str, tree.getLinesRawContent())
        assertEquals(Position(1, 6), pos)

        pos = redo()
        assertEquals(str.replace("hello", "hi"), tree.getLinesRawContent())
        assertEquals(Position(1, 3), pos)
    }

    @Test
    fun `multiline insert undo and redo`() {
        tree.insert(0, "hello\nworld")

        var pos: Position? = applyEdits(
            listOf(SingleEditOperation(Range(1, 6, 1, 6), "😁\nthis is multi line insert test"))
        )
        assertEquals("hello😁\nthis is multi line insert test\nworld", tree.getLinesRawContent())
        assertEquals(Position(2, 31), pos)

        commitUndoGroup()

        pos = undo()
        assertEquals("hello\nworld", tree.getLinesRawContent())
        assertEquals(Position(1, 6), pos)

        pos = redo()
        assertEquals("hello😁\nthis is multi line insert test\nworld", tree.getLinesRawContent())
        assertEquals(Position(2, 31), pos)
    }

    @Test
    fun `multiline delete undo and redo`() {
        val str = "hi hello\nworld\nthis is multi line delete test\n😄"
        tree.insert(0, str)

        var pos: Position? = applyEdits(listOf(SingleEditOperation(Range(1, 4, 4, 1), null)))
        assertEquals("hi 😄", tree.getLinesRawContent())
        assertEquals(Position(1, 4), pos)

        commitUndoGroup()

        pos = undo()
        assertEquals(str, tree.getLinesRawContent())
        assertEquals(Position(4, 1), pos)

        pos = redo()
        assertEquals("hi 😄", tree.getLinesRawContent())
        assertEquals(Position(1, 4), pos)
    }

    @Test
    fun `multiline replace undo and redo`() {
        val str = "hi hello\nworld\nthis is multi line replace test, hello world hello\nworld hi kotlin"
        tree.insert(0, str)

        val result = buffer.find(
            regex = Regex("hello\nworld", RegexOption.MULTILINE),
            searchRange = Range(1, 1, 4, 16),
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(2, result.size)

        val replaced = "hi 😍\nthis is multi line replace test, hello world 😍 hi kotlin"
        var pos: Position? = applyEdits(result.map { SingleEditOperation(it, "😍") })
        assertEquals(replaced, tree.getLinesRawContent())
        assertEquals(Position(1, 6), pos)

        commitUndoGroup()

        pos = undo()
        assertEquals(str, tree.getLinesRawContent())
        assertEquals(Position(2, 6), pos)

        pos = redo()
        assertEquals(replaced, tree.getLinesRawContent())
        assertEquals(Position(1, 6), pos)
    }

    // ----------------------------------------------------------- additional cases

    @Test
    fun `each commit becomes its own undo entry`() {
        applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "aaa")))
        commitUndoGroup()
        applyEdits(listOf(SingleEditOperation(Range(1, 4, 1, 4), "bbb")))
        commitUndoGroup()
        assertEquals("aaabbb", tree.getLinesRawContent())

        undo()
        assertEquals("aaa", tree.getLinesRawContent())
        undo()
        assertEquals("", tree.getLinesRawContent())
        assertNull(undo())

        redo()
        assertEquals("aaa", tree.getLinesRawContent())
        redo()
        assertEquals("aaabbb", tree.getLinesRawContent())
        assertNull(redo())
    }

    @Test
    fun `a new edit clears the redo stack`() {
        applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "hello")))
        commitUndoGroup()
        undo()
        assertTrue(canRedo())

        applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), "world")))
        assertFalse(canRedo())
        assertEquals("world", tree.getLinesRawContent())
    }

    @Test
    fun `content and tree stay consistent after repeated undo and redo`() {
        val texts = listOf("alpha\n", "beta\n", "gamma\n", "delta\n")
        for (text in texts) {
            applyEdits(listOf(SingleEditOperation(Range(1, 1, 1, 1), text)))
            commitUndoGroup()
        }
        val full = tree.getLinesRawContent()

        repeat(texts.size) { undo() }
        assertEquals("", tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)

        repeat(texts.size) { redo() }
        assertEquals(full, tree.getLinesRawContent())
        TestSupport.assertTreeInvariants(tree)
    }
}
