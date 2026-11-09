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
 * the helpers of src/vs/editor/test/common/model/pieceTreeTextBuffer/pieceTreeTextBuffer.test.ts, and changed since.
 */

package x.github.module.piecetable

import kotlin.test.*
import x.github.module.piecetable.common.EndOfLine
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import kotlin.random.Random

/**
 * Assertions shared by the JVM unit tests and the instrumented device tests.
 *
 * This file lives in src/sharedTest, which is attached to both the test and the androidTest
 * source sets, so it must only depend on kotlin.test: no android.* or androidx.* in here.
 *
 * Note: PieceTreeBase, TreeNode and the sentinel are internal. AGP compiles both test and
 * androidTest as friends of the main variant, so tests in the same module can reach them.
 */
internal object TestSupport {

    /** Alphabet for random text, deliberately containing \r and \n to hit the CRLF branches */
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n"

    // ------------------------------------------------------------------ global state

    /**
     * Nothing in the process is shared and written any more: every tree owns its sentinel, and
     * DETACHED is only compared against. This resets DETACHED defensively and is kept so the
     * existing @BeforeTest hooks of every test class keep compiling; test classes may now also
     * run in parallel.
     */
    fun resetGlobalState() = resetSharedNodes()

    // ------------------------------------------------------------------ builders

    fun buildBuffer(
        chunks: List<String>,
        normalizeEOL: Boolean = true,
        eol: EndOfLine = EndOfLine.LF
    ): PieceTreeTextBuffer {
        val builder = PieceTreeTextBufferBuilder()
        for (chunk in chunks) {
            builder.acceptChunk(chunk)
        }
        return builder.build(eol, normalizeEOL)
    }

    fun buildBuffer(
        text: String,
        normalizeEOL: Boolean = true,
        eol: EndOfLine = EndOfLine.LF
    ): PieceTreeTextBuffer = buildBuffer(listOf(text), normalizeEOL, eol)

    fun buildTree(
        chunks: List<String>,
        normalizeEOL: Boolean = true,
        eol: EndOfLine = EndOfLine.LF
    ): PieceTreeBase = buildBuffer(chunks, normalizeEOL, eol).getPieceTree()

    fun buildTree(
        text: String,
        normalizeEOL: Boolean = true,
        eol: EndOfLine = EndOfLine.LF
    ): PieceTreeBase = buildTree(listOf(text), normalizeEOL, eol)

    // ------------------------------------------------------------------ randomness

    fun randomString(random: Random, length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** Random text without any line break, for cases that do not want EOL noise */
    fun randomPlainString(random: Random, length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length - 2)]) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ assertions

    /** Line start offsets split on \r\n | \r | \n, including the trailing empty line */
    fun lineStartsOf(text: String): List<Int> {
        val result = mutableListOf(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                if (i + 1 < text.length && text[i + 1] == '\n') {
                    result.add(i + 2)
                    i++
                } else {
                    result.add(i + 1)
                }
            } else if (c == '\n') {
                result.add(i + 1)
            }
            i++
        }
        return result
    }

    private fun trimEOL(text: String): String = when {
        text.isEmpty() -> text
        text.endsWith("\r\n") -> text.dropLast(2)
        text.endsWith("\n") || text.endsWith("\r") -> text.dropLast(1)
        else -> text
    }

    /**
     * Checks line count, whole content, every line, every getValueInRange and getLinesContent.
     *
     * The checks are ordered from the most basic to the most derived, so the first failure
     * tells you how deep the problem is.
     */
    fun assertLinesContent(expect: String, tree: PieceTreeBase, hint: String = "") {
        val lines = expect.lines()
        assertEquals(expect, tree.getLinesRawContent(), "$hint raw content")
        assertEquals(lines.size, tree.getLineCount(), "$hint line count")

        for (i in lines.indices) {
            assertEquals(lines[i], tree.getLineContent(i + 1), "$hint getLineContent(${i + 1})")
            val endColumn = lines[i].length + if (i == lines.size - 1) 1 else 2
            assertEquals(lines[i],
                trimEOL(tree.getValueInRange(Range(i + 1, 1, i + 1, endColumn))), "$hint getValueInRange on line ${i + 1}")
        }

        // checked last: it is derived from everything above, so a failure here and nowhere
        // else points straight at getLinesContent itself
        assertEquals(lines, tree.getLinesContent(), "$hint getLinesContent")
    }

    /** Round trip check between offsets and positions */
    fun assertLineStarts(expect: String, tree: PieceTreeBase, hint: String = "") {
        val starts = lineStartsOf(expect)
        for (i in starts.indices) {
            assertEquals(Position(i + 1, 1),
                tree.getPositionAt(starts[i]), "$hint getPositionAt(${starts[i]})")
            assertEquals(starts[i], tree.getOffsetAt(i + 1, 1), "$hint getOffsetAt(${i + 1}, 1)")
        }
        for (i in 1 until starts.size) {
            val pos = tree.getPositionAt(starts[i] - 1)
            assertEquals(starts[i] - 1,
                tree.getOffsetAt(pos.lineNumber, pos.column), "$hint offset/position round trip at ${starts[i] - 1}")
        }
    }

    // ------------------------------------------------------------- red-black tree invariants

    private fun blackHeight(node: TreeNode, nil: TreeNode): Int {
        if (node === nil) {
            return 1
        }
        val left = blackHeight(node.left, nil)
        val right = blackHeight(node.right, nil)
        assertEquals(left, right, "black height mismatch")
        return (if (node.color == NodeColor.Black) 1 else 0) + left
    }

    /** Returns (size, lineFeedCnt) and verifies the size_left / lf_left metadata on the way */
    private fun assertValidNode(node: TreeNode, nil: TreeNode): Pair<Int, Int> {
        if (node === nil) {
            return Pair(0, 0)
        }

        if (node.color == NodeColor.Red) {
            assertEquals(NodeColor.Black, node.left.color, "left child of a red node must be black")
            assertEquals(NodeColor.Black, node.right.color, "right child of a red node must be black")
        }

        val left = assertValidNode(node.left, nil)
        assertEquals(left.first, node.size_left, "size_left metadata")
        assertEquals(left.second, node.lf_left, "lf_left metadata")

        val right = assertValidNode(node.right, nil)
        return Pair(
            node.size_left + node.piece.length + right.first,
            node.lf_left + node.piece.lineFeedCnt + right.second
        )
    }

    fun assertTreeInvariants(tree: PieceTreeBase) {
        // the tree's sentinel must stay self referencing and black, otherwise some rbDelete
        // forgot to call resetSentinel
        val nil = tree.sentinel
        assertSame(nil, nil.parent, "sentinel.parent was clobbered")
        assertSame(nil, nil.left, "sentinel.left was clobbered")
        assertSame(nil, nil.right, "sentinel.right was clobbered")
        assertEquals(NodeColor.Black, nil.color)
        assertEquals(0, nil.size_left)
        assertEquals(0, nil.lf_left)

        if (tree.root === nil) {
            return
        }
        assertSame(nil, tree.root.parent, "the root's parent must be the sentinel")
        assertEquals(NodeColor.Black, tree.root.color, "the root must be black")
        assertEquals(blackHeight(tree.root.left, nil), blackHeight(tree.root.right, nil))

        val total = assertValidNode(tree.root, nil)
        assertEquals(total.first, tree.getLength(), "getLength() does not match the content length in the tree")
        assertEquals(total.second + 1, tree.getLineCount(), "getLineCount() does not match the line feeds in the tree")
    }

    // ------------------------------------------------------------------ misc

    /**
     * Runs [block] on a worker thread and fails if it does not finish within [timeoutMs].
     *
     * For regression tests of former endless loops: without it a reintroduced bug hangs the
     * whole test run instead of failing one case. The worker is a daemon thread, so a block
     * that really loops forever does not keep the JVM alive. The calling thread only waits,
     * the tree is never touched by two threads at the same time.
     */
    fun <T> runWithTimeout(timeoutMs: Long = 10_000, block: () -> T): T {
        var outcome: Result<T>? = null
        val worker = Thread { outcome = runCatching { block() } }
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            fail("did not finish within $timeoutMs ms, most likely an endless loop")
        }
        return outcome!!.getOrThrow()
    }

    fun readSnapshot(snapshot: PieceTreeSnapshot): String {
        val sb = StringBuilder()
        var text = snapshot.read()
        while (text != null) {
            sb.append(text)
            text = snapshot.read()
        }
        return sb.toString()
    }
}
