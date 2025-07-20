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

class PieceTreeTextBufferTest {

    internal val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n"

    internal fun randomChar() = alphabet[randomInt(alphabet.length)]

    internal fun randomInt(bound: Int) = Math.floor(Math.random() * bound.toDouble()).toInt()

    internal fun randomString(len: Int): String {
        var j = 1
        val ref = if (len > 0) len else 10
        val results = StringBuffer()
        while (if (1 <= ref) j < ref else j > ref) {
            results.append(randomChar())
            if (1 <= ref) j++ else j--
        }
        return results.toString()
    }

    internal fun trimLineFeed(text: String): String {
        if (text.length == 0) {
            return text
        }

        if (text.length == 1) {
            if (text.codePointAt(text.length - 1) == 10 || text.codePointAt(text.length - 1) == 13
            ) {
                return ""
            }
            return text
        }

        if (text.codePointAt(text.length - 1) == 10) {
            if (text.codePointAt(text.length - 2) == 13) {
                return text.substring(0, text.length - 2)
            }
            return text.substring(0, text.length - 1)
        }

        if (text.codePointAt(text.length - 1) == 13) {
            return text.substring(0, text.length - 1)
        }
        return text
    }

    // #region Assertion
    internal fun testLinesContent(str: String, pieceTable: PieceTreeBase) {
        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertEquals(pieceTable.getLinesRawContent(), str)

        for (i in 0..lines.size - 1) {
            assertEquals(pieceTable.getLineContent(i + 1), lines[i])
            assertEquals(
                trimLineFeed(
                    pieceTable.getValueInRange(
                        Range(i + 1, 1, i + 1, lines[i].length + if (i == lines.size - 1) 1 else 2)
                    )
                ),
                lines[i]
            )
        }
    }

    internal fun testLineStarts(str: String, pieceTable: PieceTreeBase) {
        val lineStarts = mutableListOf(0)

        // Reset regex to search from the beginning
        val m = Regex("\r\n|\r|\n").toPattern().matcher(str)
        var prevMatchStartIndex = -1
        var prevMatchLength = 0

        while (m.find()) {
            if (prevMatchStartIndex + prevMatchLength == str.length) {
                // Reached the end of the line
                break
            }

            val matchStartIndex = m.start()
            val matchLength = m.end() - m.start()

            if (matchStartIndex == prevMatchStartIndex && matchLength == prevMatchLength) {
                // Exit early if the regex matches the same range twice
                break
            }

            prevMatchStartIndex = matchStartIndex
            prevMatchLength = matchLength

            lineStarts.add(matchStartIndex + matchLength)
        }

        for (i in 0..lineStarts.size - 1) {
            val p1 = pieceTable.getPositionAt(lineStarts[i])
            val p2 = Position(i + 1, 1)
            assertEquals(pieceTable.getPositionAt(lineStarts[i]), Position(i + 1, 1))
            assertEquals(pieceTable.getOffsetAt(i + 1, 1), lineStarts[i])
        }

        for (i in 1..lineStarts.size - 1) {
            val pos = pieceTable.getPositionAt(lineStarts[i] - 1)
            assertEquals(pieceTable.getOffsetAt(pos.lineNumber, pos.column), lineStarts[i] - 1)
        }
    }

    internal fun createTextBuffer(chunks: List<String>, eol: Boolean = true): PieceTreeTextBuffer {
        val pieceBuilder = PieceTreeTextBufferBuilder()
        for (chunk in chunks) {
            pieceBuilder.acceptChunk(chunk)
        }
        return pieceBuilder.build(normalizeEOL = eol)
    }

    internal fun createPieceTree(chunks: List<String>, eol: Boolean = true): PieceTreeBase {
        val pieceBuffer = createTextBuffer(chunks, eol)
        return pieceBuffer.getPieceTree()
    }

    internal fun assertTreeInvariants(T: PieceTreeBase) {
        assertSame(SENTINEL.parent, SENTINEL)
        assertSame(SENTINEL.left, SENTINEL)
        assertSame(SENTINEL.right, SENTINEL)

        assertEquals(SENTINEL.color, NodeColor.Black)
        assertEquals(SENTINEL.size_left, 0)
        assertEquals(SENTINEL.lf_left, 0)
        assertValidTree(T)
    }

    internal fun depth(n: TreeNode): Int {
        if (n === SENTINEL) {
            // The leafs are black
            return 1
        }
        assertSame(depth(n.left), depth(n.right))
        return (if (n.color == NodeColor.Black) 1 else 0) + depth(n.left)
    }

    internal fun assertValidNode(n: TreeNode): Pair<Int, Int> {
        if (n === SENTINEL) {
            return Pair(0, 0)
        }

        val l = n.left
        val r = n.right

        if (n.color == NodeColor.Red) {
            assertEquals(l.color, NodeColor.Black)
            assertEquals(r.color, NodeColor.Black)
        }

        val actualLeft = assertValidNode(l)
        assertEquals(actualLeft.first, n.size_left)
        assertEquals(actualLeft.second, n.lf_left)

        val actualRight = assertValidNode(r)

        return Pair(
            n.size_left + n.piece.length + actualRight.first,
            n.lf_left + n.piece.lineFeedCnt + actualRight.second
        )
    }

    internal fun assertValidTree(T: PieceTreeBase) {
        if (T.root === SENTINEL) {
            return
        }
        assertEquals(T.root.color, NodeColor.Black)
        assertSame(depth(T.root.left), depth(T.root.right))
        assertValidNode(T.root)
    }
    
    // #endregion

    // #region inserts and deletes
    @Test
    fun `basic insert and delete`() {
        val pieceTable = createPieceTree(listOf("This is a document with some text."))

        pieceTable.insert(34, "This is some more text to insert at offset 34.")

        assertEquals(
            pieceTable.getLinesRawContent(),
            "This is a document with some text.This is some more text to insert at offset 34."
        )

        pieceTable.delete(42, 5)
        assertEquals(
            pieceTable.getLinesRawContent(),
            "This is a document with some text.This is more text to insert at offset 34."
        )
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `more inserts`() {
        val pt = createPieceTree(listOf(""))
        pt.insert(0, "AAA")
        assertEquals(pt.getLinesRawContent(), "AAA")
        pt.insert(0, "BBB")
        assertEquals(pt.getLinesRawContent(), "BBBAAA")
        pt.insert(6, "CCC")
        assertEquals(pt.getLinesRawContent(), "BBBAAACCC")
        pt.insert(5, "DDD")
        assertEquals(pt.getLinesRawContent(), "BBBAADDDACCC")
        assertTreeInvariants(pt)
    }

    @Test
    fun `more deletes`() {
        val pt = createPieceTree(listOf("012345678"))
        pt.delete(8, 1)
        assertEquals(pt.getLinesRawContent(), "01234567")
        pt.delete(0, 1)
        assertEquals(pt.getLinesRawContent(), "1234567")
        pt.delete(5, 1)
        assertEquals(pt.getLinesRawContent(), "123457")
        pt.delete(5, 1)
        assertEquals(pt.getLinesRawContent(), "12345")
        pt.delete(0, 5)
        assertEquals(pt.getLinesRawContent(), "")
        assertTreeInvariants(pt)
    }

    @Test
    fun `random test 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "ceLPHmFzvCtFeHkCBej ")
        str = str.substring(0, 0) + "ceLPHmFzvCtFeHkCBej " + str.substring(0)
        assertEquals(pieceTable.getLinesRawContent(), str)
        pieceTable.insert(8, "gDCEfNYiBUNkSwtvB K ")
        str = str.substring(0, 8) + "gDCEfNYiBUNkSwtvB K " + str.substring(8)
        assertEquals(pieceTable.getLinesRawContent(), str)
        pieceTable.insert(38, "cyNcHxjNPPoehBJldLS ")
        str = str.substring(0, 38) + "cyNcHxjNPPoehBJldLS " + str.substring(38)
        assertEquals(pieceTable.getLinesRawContent(), str)
        pieceTable.insert(59, "ejMx\nOTgWlbpeDExjOk ")
        str = str.substring(0, 59) + "ejMx\nOTgWlbpeDExjOk " + str.substring(59)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "VgPG ")
        str = str.substring(0, 0) + "VgPG " + str.substring(0)
        pieceTable.insert(2, "DdWF ")
        str = str.substring(0, 2) + "DdWF " + str.substring(2)
        pieceTable.insert(0, "hUJc ")
        str = str.substring(0, 0) + "hUJc " + str.substring(0)
        pieceTable.insert(8, "lQEq ")
        str = str.substring(0, 8) + "lQEq " + str.substring(8)
        pieceTable.insert(10, "Gbtp ")
        str = str.substring(0, 10) + "Gbtp " + str.substring(10)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test 3`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "gYSz")
        str = str.substring(0, 0) + "gYSz" + str.substring(0)
        pieceTable.insert(1, "mDQe")
        str = str.substring(0, 1) + "mDQe" + str.substring(1)
        pieceTable.insert(1, "DTMQ")
        str = str.substring(0, 1) + "DTMQ" + str.substring(1)
        pieceTable.insert(2, "GGZB")
        str = str.substring(0, 2) + "GGZB" + str.substring(2)
        pieceTable.insert(12, "wXpq")
        str = str.substring(0, 12) + "wXpq" + str.substring(12)
        assertEquals(pieceTable.getLinesRawContent(), str)
    }

    @Test
    fun `random delete 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))

        pieceTable.insert(0, "vfb")
        str = str.substring(0, 0) + "vfb" + str.substring(0)
        assertEquals(pieceTable.getLinesRawContent(), str)
        pieceTable.insert(0, "zRq")
        str = str.substring(0, 0) + "zRq" + str.substring(0)
        assertEquals(pieceTable.getLinesRawContent(), str)

        pieceTable.delete(5, 1)
        str = str.substring(0, 5) + str.substring(5 + 1)
        assertEquals(pieceTable.getLinesRawContent(), str)

        pieceTable.insert(1, "UNw")
        str = str.substring(0, 1) + "UNw" + str.substring(1)
        assertEquals(pieceTable.getLinesRawContent(), str)

        pieceTable.delete(4, 3)
        str = str.substring(0, 4) + str.substring(4 + 3)
        assertEquals(pieceTable.getLinesRawContent(), str)

        pieceTable.delete(1, 4)
        str = str.substring(0, 1) + str.substring(1 + 4)
        assertEquals(pieceTable.getLinesRawContent(), str)

        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random delete 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))

        pieceTable.insert(0, "IDT")
        str = str.substring(0, 0) + "IDT" + str.substring(0)
        pieceTable.insert(3, "wwA")
        str = str.substring(0, 3) + "wwA" + str.substring(3)
        pieceTable.insert(3, "Gnr")
        str = str.substring(0, 3) + "Gnr" + str.substring(3)
        pieceTable.delete(6, 3)
        str = str.substring(0, 6) + str.substring(6 + 3)
        pieceTable.insert(4, "eHp")
        str = str.substring(0, 4) + "eHp" + str.substring(4)
        pieceTable.insert(1, "UAi")
        str = str.substring(0, 1) + "UAi" + str.substring(1)
        pieceTable.insert(2, "FrR")
        str = str.substring(0, 2) + "FrR" + str.substring(2)
        pieceTable.delete(6, 7)
        str = str.substring(0, 6) + str.substring(6 + 7)
        pieceTable.delete(3, 5)
        str = str.substring(0, 3) + str.substring(3 + 5)
        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random delete 3`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "PqM")
        str = str.substring(0, 0) + "PqM" + str.substring(0)
        pieceTable.delete(1, 2)
        str = str.substring(0, 1) + str.substring(1 + 2)
        pieceTable.insert(1, "zLc")
        str = str.substring(0, 1) + "zLc" + str.substring(1)
        pieceTable.insert(0, "MEX")
        str = str.substring(0, 0) + "MEX" + str.substring(0)
        pieceTable.insert(0, "jZh")
        str = str.substring(0, 0) + "jZh" + str.substring(0)
        pieceTable.insert(8, "GwQ")
        str = str.substring(0, 8) + "GwQ" + str.substring(8)
        pieceTable.delete(5, 6)
        str = str.substring(0, 5) + str.substring(5 + 6)
        pieceTable.insert(4, "ktw")
        str = str.substring(0, 4) + "ktw" + str.substring(4)
        pieceTable.insert(5, "GVu")
        str = str.substring(0, 5) + "GVu" + str.substring(5)
        pieceTable.insert(9, "jdm")
        str = str.substring(0, 9) + "jdm" + str.substring(9)
        pieceTable.insert(15, "na\n")
        str = str.substring(0, 15) + "na\n" + str.substring(15)
        pieceTable.delete(5, 8)
        str = str.substring(0, 5) + str.substring(5 + 8)
        pieceTable.delete(3, 4)
        str = str.substring(0, 3) + str.substring(3 + 4)
        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 1`() {
        var str = "a"
        val pieceTable = createPieceTree(listOf("a"))
        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        pieceTable.insert(0, "\r\r\n\n")
        str = str.substring(0, 0) + "\r\r\n\n" + str.substring(0)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(2, "\n\n\ra")
        str = str.substring(0, 2) + "\n\n\ra" + str.substring(2)
        pieceTable.delete(4, 3)
        str = str.substring(0, 4) + str.substring(4 + 3)
        pieceTable.insert(2, "\na\r\r")
        str = str.substring(0, 2) + "\na\r\r" + str.substring(2)
        pieceTable.insert(6, "\ra\n\n")
        str = str.substring(0, 6) + "\ra\n\n" + str.substring(6)
        pieceTable.insert(0, "aa\n\n")
        str = str.substring(0, 0) + "aa\n\n" + str.substring(0)
        pieceTable.insert(5, "\n\na\r")
        str = str.substring(0, 5) + "\n\na\r" + str.substring(5)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 2`() {
        var str = "a"
        val pieceTable = createPieceTree(listOf("a"))
        pieceTable.insert(1, "\naa\r")
        str = str.substring(0, 1) + "\naa\r" + str.substring(1)
        pieceTable.delete(0, 4)
        str = str.substring(0, 0) + str.substring(0 + 4)
        pieceTable.insert(1, "\r\r\na")
        str = str.substring(0, 1) + "\r\r\na" + str.substring(1)
        pieceTable.insert(2, "\n\r\ra")
        str = str.substring(0, 2) + "\n\r\ra" + str.substring(2)
        pieceTable.delete(4, 1)
        str = str.substring(0, 4) + str.substring(4 + 1)
        pieceTable.insert(8, "\r\n\r\r")
        str = str.substring(0, 8) + "\r\n\r\r" + str.substring(8)
        pieceTable.insert(7, "\n\n\na")
        str = str.substring(0, 7) + "\n\n\na" + str.substring(7)
        pieceTable.insert(13, "a\n\na")
        str = str.substring(0, 13) + "a\n\na" + str.substring(13)
        pieceTable.delete(17, 3)
        str = str.substring(0, 17) + str.substring(17 + 3)
        pieceTable.insert(2, "a\ra\n")
        str = str.substring(0, 2) + "a\ra\n" + str.substring(2)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 3`() {
        var str = "a"
        val pieceTable = createPieceTree(listOf("a"))
        pieceTable.insert(0, "\r\na\r")
        str = str.substring(0, 0) + "\r\na\r" + str.substring(0)
        pieceTable.delete(2, 3)
        str = str.substring(0, 2) + str.substring(2 + 3)
        pieceTable.insert(2, "a\r\n\r")
        str = str.substring(0, 2) + "a\r\n\r" + str.substring(2)
        pieceTable.delete(4, 2)
        str = str.substring(0, 4) + str.substring(4 + 2)
        pieceTable.insert(4, "a\n\r\n")
        str = str.substring(0, 4) + "a\n\r\n" + str.substring(4)
        pieceTable.insert(1, "aa\n\r")
        str = str.substring(0, 1) + "aa\n\r" + str.substring(1)
        pieceTable.insert(7, "\na\r\n")
        str = str.substring(0, 7) + "\na\r\n" + str.substring(7)
        pieceTable.insert(5, "\n\na\r")
        str = str.substring(0, 5) + "\n\na\r" + str.substring(5)
        pieceTable.insert(10, "\r\r\n\r")
        str = str.substring(0, 10) + "\r\r\n\r" + str.substring(10)
        assertEquals(pieceTable.getLinesRawContent(), str)
        pieceTable.delete(21, 3)
        str = str.substring(0, 21) + str.substring(21 + 3)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 4`() {
        var str = "a"
        val pieceTable = createPieceTree(listOf("a"))
        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        pieceTable.insert(0, "\naaa")
        str = str.substring(0, 0) + "\naaa" + str.substring(0)
        pieceTable.insert(2, "\n\naa")
        str = str.substring(0, 2) + "\n\naa" + str.substring(2)
        pieceTable.delete(1, 4)
        str = str.substring(0, 1) + str.substring(1 + 4)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.delete(1, 2)
        str = str.substring(0, 1) + str.substring(1 + 2)
        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        pieceTable.insert(0, "a\n\n\r")
        str = str.substring(0, 0) + "a\n\n\r" + str.substring(0)
        pieceTable.insert(2, "aa\r\n")
        str = str.substring(0, 2) + "aa\r\n" + str.substring(2)
        pieceTable.insert(3, "a\naa")
        str = str.substring(0, 3) + "a\naa" + str.substring(3)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 5`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "\n\n\n\r")
        str = str.substring(0, 0) + "\n\n\n\r" + str.substring(0)
        pieceTable.insert(1, "\n\n\n\r")
        str = str.substring(0, 1) + "\n\n\n\r" + str.substring(1)
        pieceTable.insert(2, "\n\r\r\r")
        str = str.substring(0, 2) + "\n\r\r\r" + str.substring(2)
        pieceTable.insert(8, "\n\r\n\r")
        str = str.substring(0, 8) + "\n\r\n\r" + str.substring(8)
        pieceTable.delete(5, 2)
        str = str.substring(0, 5) + str.substring(5 + 2)
        pieceTable.insert(4, "\n\r\r\r")
        str = str.substring(0, 4) + "\n\r\r\r" + str.substring(4)
        pieceTable.insert(8, "\n\n\n\r")
        str = str.substring(0, 8) + "\n\n\n\r" + str.substring(8)
        pieceTable.delete(0, 7)
        str = str.substring(0, 0) + str.substring(0 + 7)
        pieceTable.insert(1, "\r\n\r\r")
        str = str.substring(0, 1) + "\r\n\r\r" + str.substring(1)
        pieceTable.insert(15, "\n\r\r\r")
        str = str.substring(0, 15) + "\n\r\r\r" + str.substring(15)

        assertEquals(pieceTable.getLinesRawContent(), str)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region prefix sum for line feed
    @Test
    fun `basic`() {
        val pieceTable = createPieceTree(listOf("1\n2\n3\n4"))

        assertEquals(pieceTable.getLineCount(), 4)
        assertEquals(pieceTable.getPositionAt(0), Position(1, 1))
        assertEquals(pieceTable.getPositionAt(1), Position(1, 2))
        assertEquals(pieceTable.getPositionAt(2), Position(2, 1))
        assertEquals(pieceTable.getPositionAt(3), Position(2, 2))
        assertEquals(pieceTable.getPositionAt(4), Position(3, 1))
        assertEquals(pieceTable.getPositionAt(5), Position(3, 2))
        assertEquals(pieceTable.getPositionAt(6), Position(4, 1))

        assertEquals(pieceTable.getOffsetAt(1, 1), 0)
        assertEquals(pieceTable.getOffsetAt(1, 2), 1)
        assertEquals(pieceTable.getOffsetAt(2, 1), 2)
        assertEquals(pieceTable.getOffsetAt(2, 2), 3)
        assertEquals(pieceTable.getOffsetAt(3, 1), 4)
        assertEquals(pieceTable.getOffsetAt(3, 2), 5)
        assertEquals(pieceTable.getOffsetAt(4, 1), 6)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `append`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")

        assertEquals(pieceTable.getLineCount(), 6)
        assertEquals(pieceTable.getPositionAt(9), Position(4, 4))
        assertEquals(pieceTable.getOffsetAt(1, 1), 0)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(7, "fh\ni\njk")

        assertEquals(pieceTable.getLineCount(), 6)
        assertEquals(pieceTable.getPositionAt(6), Position(4, 1))
        assertEquals(pieceTable.getPositionAt(7), Position(4, 2))
        assertEquals(pieceTable.getPositionAt(8), Position(4, 3))
        assertEquals(pieceTable.getPositionAt(9), Position(4, 4))
        assertEquals(pieceTable.getPositionAt(12), Position(6, 1))
        assertEquals(pieceTable.getPositionAt(13), Position(6, 2))
        assertEquals(pieceTable.getPositionAt(14), Position(6, 3))

        assertEquals(pieceTable.getOffsetAt(4, 1), 6)
        assertEquals(pieceTable.getOffsetAt(4, 2), 7)
        assertEquals(pieceTable.getOffsetAt(4, 3), 8)
        assertEquals(pieceTable.getOffsetAt(4, 4), 9)
        assertEquals(pieceTable.getOffsetAt(6, 1), 12)
        assertEquals(pieceTable.getOffsetAt(6, 2), 13)
        assertEquals(pieceTable.getOffsetAt(6, 3), 14)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\ndefh\ni\njk"))
        pieceTable.delete(7, 2)

        assertEquals(pieceTable.getLinesRawContent(), "a\nb\nc\ndh\ni\njk")
        assertEquals(pieceTable.getLineCount(), 6)
        assertEquals(pieceTable.getPositionAt(6), Position(4, 1))
        assertEquals(pieceTable.getPositionAt(7), Position(4, 2))
        assertEquals(pieceTable.getPositionAt(8), Position(4, 3))
        assertEquals(pieceTable.getPositionAt(9), Position(5, 1))
        assertEquals(pieceTable.getPositionAt(11), Position(6, 1))
        assertEquals(pieceTable.getPositionAt(12), Position(6, 2))
        assertEquals(pieceTable.getPositionAt(13), Position(6, 3))

        assertEquals(pieceTable.getOffsetAt(4, 1), 6)
        assertEquals(pieceTable.getOffsetAt(4, 2), 7)
        assertEquals(pieceTable.getOffsetAt(4, 3), 8)
        assertEquals(pieceTable.getOffsetAt(5, 1), 9)
        assertEquals(pieceTable.getOffsetAt(6, 1), 11)
        assertEquals(pieceTable.getOffsetAt(6, 2), 12)
        assertEquals(pieceTable.getOffsetAt(6, 3), 13)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `add and delete 1`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)

        assertEquals(pieceTable.getLinesRawContent(), "a\nb\nc\ndh\ni\njk")
        assertEquals(pieceTable.getLineCount(), 6)
        assertEquals(pieceTable.getPositionAt(6), Position(4, 1))
        assertEquals(pieceTable.getPositionAt(7), Position(4, 2))
        assertEquals(pieceTable.getPositionAt(8), Position(4, 3))
        assertEquals(pieceTable.getPositionAt(9), Position(5, 1))
        assertEquals(pieceTable.getPositionAt(11), Position(6, 1))
        assertEquals(pieceTable.getPositionAt(12), Position(6, 2))
        assertEquals(pieceTable.getPositionAt(13), Position(6, 3))

        assertEquals(pieceTable.getOffsetAt(4, 1), 6)
        assertEquals(pieceTable.getOffsetAt(4, 2), 7)
        assertEquals(pieceTable.getOffsetAt(4, 3), 8)
        assertEquals(pieceTable.getOffsetAt(5, 1), 9)
        assertEquals(pieceTable.getOffsetAt(6, 1), 11)
        assertEquals(pieceTable.getOffsetAt(6, 2), 12)
        assertEquals(pieceTable.getOffsetAt(6, 3), 13)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert random bug 1 prefixSumComputer cnt is 1 based`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, " ZX \n Z\nZ\n YZ\nY\nZXX ")
        str = str.substring(0, 0) + " ZX \n Z\nZ\n YZ\nY\nZXX " + str.substring(0)
        pieceTable.insert(14, "X ZZ\nYZZYZXXY Y XY\n ")
        str = str.substring(0, 14) + "X ZZ\nYZZYZXXY Y XY\n " + str.substring(14)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert random bug 2 prefixSumComputer initialize does not do deep copy of UInt32Array`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "ZYZ\nYY XY\nX \nZ Y \nZ ")
        str = str.substring(0, 0) + "ZYZ\nYY XY\nX \nZ Y \nZ " + str.substring(0)
        pieceTable.insert(3, "XXY \n\nY Y YYY  ZYXY ")
        str = str.substring(0, 3) + "XXY \n\nY Y YYY  ZYXY " + str.substring(3)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug 1 forgot to update the lineFeedCnt when deletion is on one single piece`() {
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "ba\na\nca\nba\ncbab\ncaa ")
        pieceTable.insert(13, "cca\naabb\ncac\nccc\nab ")
        pieceTable.delete(5, 8)
        pieceTable.delete(30, 2)
        pieceTable.insert(24, "cbbacccbac\nbaaab\n\nc ")
        pieceTable.delete(29, 3)
        pieceTable.delete(23, 9)
        pieceTable.delete(21, 5)
        pieceTable.delete(30, 3)
        pieceTable.insert(3, "cb\nac\nc\n\nacc\nbb\nb\nc ")
        pieceTable.delete(19, 5)
        pieceTable.insert(18, "\nbb\n\nacbc\ncbb\nc\nbb\n ")
        pieceTable.insert(65, "cbccbac\nbc\n\nccabba\n ")
        pieceTable.insert(77, "a\ncacb\n\nac\n\n\n\n\nabab ")
        pieceTable.delete(30, 9)
        pieceTable.insert(45, "b\n\nc\nba\n\nbbbba\n\naa\n ")
        pieceTable.insert(82, "ab\nbb\ncabacab\ncbc\na ")
        pieceTable.delete(123, 9)
        pieceTable.delete(71, 2)
        pieceTable.insert(33, "acaa\nacb\n\naa\n\nc\n\n\n\n ")

        val str = pieceTable.getLinesRawContent()
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))
        pieceTable.insert(0, "YXXZ\n\nYY\n")
        str = str.substring(0, 0) + "YXXZ\n\nYY\n" + str.substring(0)
        pieceTable.delete(0, 5)
        str = str.substring(0, 0) + str.substring(0 + 5)
        pieceTable.insert(0, "ZXYY\nX\nZ\n")
        str = str.substring(0, 0) + "ZXYY\nX\nZ\n" + str.substring(0)
        pieceTable.insert(10, "\nXY\nYXYXY")
        str = str.substring(0, 10) + "\nXY\nYXYXY" + str.substring(10)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))
        pieceTable.insert(0, "YXXZ\n\nYY\n")
        str = str.substring(0, 0) + "YXXZ\n\nYY\n" + str.substring(0)
        pieceTable.insert(0, "ZXYY\nX\nZ\n")
        str = str.substring(0, 0) + "ZXYY\nX\nZ\n" + str.substring(0)
        pieceTable.insert(10, "\nXY\nYXYXY")
        str = str.substring(0, 10) + "\nXY\nYXYXY" + str.substring(10)
        pieceTable.insert(8, "YZXY\nZ\nYX")
        str = str.substring(0, 8) + "YZXY\nZ\nYX" + str.substring(8)
        pieceTable.insert(12, "XX\nXXYXYZ")
        str = str.substring(0, 12) + "XX\nXXYXYZ" + str.substring(12)
        pieceTable.delete(0, 4)
        str = str.substring(0, 0) + str.substring(0 + 4)

        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 3`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))
        pieceTable.insert(0, "YXXZ\n\nYY\n")
        str = str.substring(0, 0) + "YXXZ\n\nYY\n" + str.substring(0)
        pieceTable.delete(7, 2)
        str = str.substring(0, 7) + str.substring(7 + 2)
        pieceTable.delete(6, 1)
        str = str.substring(0, 6) + str.substring(6 + 1)
        pieceTable.delete(0, 5)
        str = str.substring(0, 0) + str.substring(0 + 5)
        pieceTable.insert(0, "ZXYY\nX\nZ\n")
        str = str.substring(0, 0) + "ZXYY\nX\nZ\n" + str.substring(0)
        pieceTable.insert(10, "\nXY\nYXYXY")
        str = str.substring(0, 10) + "\nXY\nYXYXY" + str.substring(10)
        pieceTable.insert(8, "YZXY\nZ\nYX")
        str = str.substring(0, 8) + "YZXY\nZ\nYX" + str.substring(8)
        pieceTable.insert(12, "XX\nXXYXYZ")
        str = str.substring(0, 12) + "XX\nXXYXYZ" + str.substring(12)
        pieceTable.delete(0, 4)
        str = str.substring(0, 0) + str.substring(0 + 4)
        pieceTable.delete(30, 3)
        str = str.substring(0, 30) + str.substring(30 + 3)

        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region offset to position
    @Test
    fun `random tests offset bug 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))
        pieceTable.insert(0, "huuyYzUfKOENwGgZLqn ")
        str = str.substring(0, 0) + "huuyYzUfKOENwGgZLqn " + str.substring(0)
        pieceTable.delete(18, 2)
        str = str.substring(0, 18) + str.substring(18 + 2)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.delete(12, 4)
        str = str.substring(0, 12) + str.substring(12 + 4)
        pieceTable.insert(3, "hMbnVEdTSdhLlPevXKF ")
        str = str.substring(0, 3) + "hMbnVEdTSdhLlPevXKF " + str.substring(3)
        pieceTable.delete(22, 8)
        str = str.substring(0, 22) + str.substring(22 + 8)
        pieceTable.insert(4, "S umSnYrqOmOAV\nEbZJ ")
        str = str.substring(0, 4) + "S umSnYrqOmOAV\nEbZJ " + str.substring(4)

        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region get text in range
    @Test
    fun `getContentInRange`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)
        // "a\nb\nc\ndh\ni\njk"

        assertEquals(pieceTable.getValueInRange(Range(1, 1, 1, 3)), "a\n")
        assertEquals(pieceTable.getValueInRange(Range(2, 1, 2, 3)), "b\n")
        assertEquals(pieceTable.getValueInRange(Range(3, 1, 3, 3)), "c\n")
        assertEquals(pieceTable.getValueInRange(Range(4, 1, 4, 4)), "dh\n")
        assertEquals(pieceTable.getValueInRange(Range(5, 1, 5, 3)), "i\n")
        assertEquals(pieceTable.getValueInRange(Range(6, 1, 6, 3)), "jk")
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test value in range`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))

        pieceTable.insert(0, "ZXXY")
        str = str.substring(0, 0) + "ZXXY" + str.substring(0)
        pieceTable.insert(1, "XZZY")
        str = str.substring(0, 1) + "XZZY" + str.substring(1)
        pieceTable.insert(5, "\nX\n\n")
        str = str.substring(0, 5) + "\nX\n\n" + str.substring(5)
        pieceTable.insert(3, "\nXX\n")
        str = str.substring(0, 3) + "\nXX\n" + str.substring(3)
        pieceTable.insert(12, "YYYX")
        str = str.substring(0, 12) + "YYYX" + str.substring(12)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test value in range exception`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(str))

        pieceTable.insert(0, "XZ\nZ")
        str = str.substring(0, 0) + "XZ\nZ" + str.substring(0)
        pieceTable.delete(0, 3)
        str = str.substring(0, 0) + str.substring(0 + 3)
        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        pieceTable.insert(0, "ZYX\n")
        str = str.substring(0, 0) + "ZYX\n" + str.substring(0)
        pieceTable.delete(0, 4)
        str = str.substring(0, 0) + str.substring(0 + 4)

        pieceTable.getValueInRange(Range(1, 1, 1, 1))
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random tests bug 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "huuyYzUfKOENwGgZLqn ")
        str = str.substring(0, 0) + "huuyYzUfKOENwGgZLqn " + str.substring(0)
        pieceTable.delete(18, 2)
        str = str.substring(0, 18) + str.substring(18 + 2)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.delete(12, 4)
        str = str.substring(0, 12) + str.substring(12 + 4)
        pieceTable.insert(3, "hMbnVEdTSdhLlPevXKF ")
        str = str.substring(0, 3) + "hMbnVEdTSdhLlPevXKF " + str.substring(3)
        pieceTable.delete(22, 8)
        str = str.substring(0, 22) + str.substring(22 + 8)
        pieceTable.insert(4, "S umSnYrqOmOAV\nEbZJ ")
        str = str.substring(0, 4) + "S umSnYrqOmOAV\nEbZJ " + str.substring(4)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random tests bug 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "xfouRDZwdAHjVXJAMV\n ")
        str = str.substring(0, 0) + "xfouRDZwdAHjVXJAMV\n " + str.substring(0)
        pieceTable.insert(16, "dBGndxpFZBEAIKykYYx ")
        str = str.substring(0, 16) + "dBGndxpFZBEAIKykYYx " + str.substring(16)
        pieceTable.delete(7, 6)
        str = str.substring(0, 7) + str.substring(7 + 6)
        pieceTable.delete(9, 7)
        str = str.substring(0, 9) + str.substring(9 + 7)
        pieceTable.delete(17, 6)
        str = str.substring(0, 17) + str.substring(17 + 6)
        pieceTable.delete(0, 4)
        str = str.substring(0, 0) + str.substring(0 + 4)
        pieceTable.insert(9, "qvEFXCNvVkWgvykahYt ")
        str = str.substring(0, 9) + "qvEFXCNvVkWgvykahYt " + str.substring(9)
        pieceTable.delete(4, 6)
        str = str.substring(0, 4) + str.substring(4 + 6)
        pieceTable.insert(11, "OcSChUYT\nzPEBOpsGmR ")
        str = str.substring(0, 11) + "OcSChUYT\nzPEBOpsGmR " + str.substring(11)
        pieceTable.insert(15, "KJCozaXTvkE\nxnqAeTz ")
        str = str.substring(0, 15) + "KJCozaXTvkE\nxnqAeTz " + str.substring(15)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content`() {
        val pieceTable = createPieceTree(listOf("1"))
        assertEquals(pieceTable.getLineRawContent(1), "1")
        pieceTable.insert(1, "2")
        assertEquals(pieceTable.getLineRawContent(1), "12")
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content basic`() {
        val pieceTable = createPieceTree(listOf("1\n2\n3\n4"))
        assertEquals(pieceTable.getLineRawContent(1), "1\n")
        assertEquals(pieceTable.getLineRawContent(2), "2\n")
        assertEquals(pieceTable.getLineRawContent(3), "3\n")
        assertEquals(pieceTable.getLineRawContent(4), "4")
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content after inserts and deletes`() {
        val pieceTable = createPieceTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)
        // "a\nb\nc\ndh\ni\njk"

        assertEquals(pieceTable.getLineRawContent(1), "a\n")
        assertEquals(pieceTable.getLineRawContent(2), "b\n")
        assertEquals(pieceTable.getLineRawContent(3), "c\n")
        assertEquals(pieceTable.getLineRawContent(4), "dh\n")
        assertEquals(pieceTable.getLineRawContent(5), "i\n")
        assertEquals(pieceTable.getLineRawContent(6), "jk")
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))

        pieceTable.insert(0, "J eNnDzQpnlWyjmUu\ny ")
        str = str.substring(0, 0) + "J eNnDzQpnlWyjmUu\ny " + str.substring(0)
        pieceTable.insert(0, "QPEeRAQmRwlJqtZSWhQ ")
        str = str.substring(0, 0) + "QPEeRAQmRwlJqtZSWhQ " + str.substring(0)
        pieceTable.delete(5, 1)
        str = str.substring(0, 5) + str.substring(5 + 1)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""))
        pieceTable.insert(0, "DZoQ tglPCRHMltejRI ")
        str = str.substring(0, 0) + "DZoQ tglPCRHMltejRI " + str.substring(0)
        pieceTable.insert(10, "JRXiyYqJ qqdcmbfkKX ")
        str = str.substring(0, 10) + "JRXiyYqJ qqdcmbfkKX " + str.substring(10)
        pieceTable.delete(16, 3)
        str = str.substring(0, 16) + str.substring(16 + 3)
        pieceTable.delete(25, 1)
        str = str.substring(0, 25) + str.substring(25 + 1)
        pieceTable.insert(18, "vH\nNlvfqQJPm\nSFkhMc ")
        str = str.substring(0, 18) + "vH\nNlvfqQJPm\nSFkhMc " + str.substring(18)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region CRLF
    @Test
    fun `delete CR in CRLF 1`() {
        val pieceTable = createPieceTree(listOf(""), false)
        pieceTable.insert(0, "a\r\nb")
        pieceTable.delete(0, 2)

        assertEquals(pieceTable.getLineCount(), 2)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete CR in CRLF 2`() {
        val pieceTable = createPieceTree(listOf(""), false)
        pieceTable.insert(0, "a\r\nb")
        pieceTable.delete(2, 2)

        assertEquals(pieceTable.getLineCount(), 2)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 1`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)
        pieceTable.insert(0, "\n\n\r\r")
        str = str.substring(0, 0) + "\n\n\r\r" + str.substring(0)
        pieceTable.insert(1, "\r\n\r\n")
        str = str.substring(0, 1) + "\r\n\r\n" + str.substring(1)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)
        pieceTable.delete(2, 3)
        str = str.substring(0, 2) + str.substring(2 + 3)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 2`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\r\n\r")
        str = str.substring(0, 0) + "\n\r\n\r" + str.substring(0)
        pieceTable.insert(2, "\n\r\r\r")
        str = str.substring(0, 2) + "\n\r\r\r" + str.substring(2)
        pieceTable.delete(4, 1)
        str = str.substring(0, 4) + str.substring(4 + 1)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 3`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\n\n\r")
        str = str.substring(0, 0) + "\n\n\n\r" + str.substring(0)
        pieceTable.delete(2, 2)
        str = str.substring(0, 2) + str.substring(2 + 2)
        pieceTable.delete(0, 2)
        str = str.substring(0, 0) + str.substring(0 + 2)
        pieceTable.insert(0, "\r\r\r\r")
        str = str.substring(0, 0) + "\r\r\r\r" + str.substring(0)
        pieceTable.insert(2, "\r\n\r\r")
        str = str.substring(0, 2) + "\r\n\r\r" + str.substring(2)
        pieceTable.insert(3, "\r\r\r\n")
        str = str.substring(0, 3) + "\r\r\r\n" + str.substring(3)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 4`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\n\n\n")
        str = str.substring(0, 0) + "\n\n\n\n" + str.substring(0)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(1, "\r\r\r\r")
        str = str.substring(0, 1) + "\r\r\r\r" + str.substring(1)
        pieceTable.insert(6, "\r\n\n\r")
        str = str.substring(0, 6) + "\r\n\n\r" + str.substring(6)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 5`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\n\n\n")
        str = str.substring(0, 0) + "\n\n\n\n" + str.substring(0)
        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(0, "\n\r\r\n")
        str = str.substring(0, 0) + "\n\r\r\n" + str.substring(0)
        pieceTable.insert(4, "\n\r\r\n")
        str = str.substring(0, 4) + "\n\r\r\n" + str.substring(4)
        pieceTable.delete(4, 3)
        str = str.substring(0, 4) + str.substring(4 + 3)
        pieceTable.insert(5, "\r\r\n\r")
        str = str.substring(0, 5) + "\r\r\n\r" + str.substring(5)
        pieceTable.insert(12, "\n\n\n\r")
        str = str.substring(0, 12) + "\n\n\n\r" + str.substring(12)
        pieceTable.insert(5, "\r\r\r\n")
        str = str.substring(0, 5) + "\r\r\r\n" + str.substring(5)
        pieceTable.insert(20, "\n\n\r\n")
        str = str.substring(0, 20) + "\n\n\r\n" + str.substring(20)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 6`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\r\r\n")
        str = str.substring(0, 0) + "\n\r\r\n" + str.substring(0)
        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(3, "\r\n\n\n")
        str = str.substring(0, 3) + "\r\n\n\n" + str.substring(3)
        pieceTable.delete(4, 8)
        str = str.substring(0, 4) + str.substring(4 + 8)
        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(0, "\r\n\n\r")
        str = str.substring(0, 0) + "\r\n\n\r" + str.substring(0)
        pieceTable.delete(4, 0)
        str = str.substring(0, 4) + str.substring(4 + 0)
        pieceTable.delete(8, 4)
        str = str.substring(0, 8) + str.substring(8 + 4)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 8`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\r\n\n\r")
        str = str.substring(0, 0) + "\r\n\n\r" + str.substring(0)
        pieceTable.delete(1, 0)
        str = str.substring(0, 1) + str.substring(1 + 0)
        pieceTable.insert(3, "\n\n\n\r")
        str = str.substring(0, 3) + "\n\n\n\r" + str.substring(3)
        pieceTable.insert(7, "\n\n\r\n")
        str = str.substring(0, 7) + "\n\n\r\n" + str.substring(7)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 7`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\r\r\n\n")
        str = str.substring(0, 0) + "\r\r\n\n" + str.substring(0)
        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(7, "\n\r\r\r")
        str = str.substring(0, 7) + "\n\r\r\r" + str.substring(7)
        pieceTable.insert(11, "\n\n\r\n")
        str = str.substring(0, 11) + "\n\n\r\n" + str.substring(11)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 10`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "qneW")
        str = str.substring(0, 0) + "qneW" + str.substring(0)
        pieceTable.insert(0, "YhIl")
        str = str.substring(0, 0) + "YhIl" + str.substring(0)
        pieceTable.insert(0, "qdsm")
        str = str.substring(0, 0) + "qdsm" + str.substring(0)
        pieceTable.delete(7, 0)
        str = str.substring(0, 7) + str.substring(7 + 0)
        pieceTable.insert(12, "iiPv")
        str = str.substring(0, 12) + "iiPv" + str.substring(12)
        pieceTable.insert(9, "V\rSA")
        str = str.substring(0, 9) + "V\rSA" + str.substring(9)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 9`() {
        var str = ""
        val pieceTable = createPieceTree(listOf(""), false)

        pieceTable.insert(0, "\n\n\n\n")
        str = str.substring(0, 0) + "\n\n\n\n" + str.substring(0)
        pieceTable.insert(3, "\n\r\n\r")
        str = str.substring(0, 3) + "\n\r\n\r" + str.substring(3)
        pieceTable.insert(2, "\n\r\n\n")
        str = str.substring(0, 2) + "\n\r\n\n" + str.substring(2)
        pieceTable.insert(0, "\n\n\r\r")
        str = str.substring(0, 0) + "\n\n\r\r" + str.substring(0)
        pieceTable.insert(3, "\r\r\r\r")
        str = str.substring(0, 3) + "\r\r\r\r" + str.substring(3)
        pieceTable.insert(3, "\n\n\r\r")
        str = str.substring(0, 3) + "\n\n\r\r" + str.substring(3)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region centralized lineStarts with CRLF
    @Test
    fun `delete centralized CR in CRLF 1`() {
        val pieceTable = createPieceTree(listOf("a\r\nb"), false)
        pieceTable.delete(2, 2)
        assertEquals(pieceTable.getLineCount(), 2)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete centralized CR in CRLF 2`() {
        val pieceTable = createPieceTree(listOf("a\r\nb"))
        pieceTable.delete(0, 2)

        assertEquals(pieceTable.getLineCount(), 2)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 1`() {
        var str = "\n\n\r\r"
        val pieceTable = createPieceTree(listOf("\n\n\r\r"), false)
        pieceTable.insert(1, "\r\n\r\n")
        str = str.substring(0, 1) + "\r\n\r\n" + str.substring(1)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)
        pieceTable.delete(2, 3)
        str = str.substring(0, 2) + str.substring(2 + 3)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 2`() {
        var str = "\n\r\n\r"
        val pieceTable = createPieceTree(listOf("\n\r\n\r"), false)

        pieceTable.insert(2, "\n\r\r\r")
        str = str.substring(0, 2) + "\n\r\r\r" + str.substring(2)
        pieceTable.delete(4, 1)
        str = str.substring(0, 4) + str.substring(4 + 1)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 3`() {
        var str = "\n\n\n\r"
        val pieceTable = createPieceTree(listOf("\n\n\n\r"), false)

        pieceTable.delete(2, 2)
        str = str.substring(0, 2) + str.substring(2 + 2)
        pieceTable.delete(0, 2)
        str = str.substring(0, 0) + str.substring(0 + 2)
        pieceTable.insert(0, "\r\r\r\r")
        str = str.substring(0, 0) + "\r\r\r\r" + str.substring(0)
        pieceTable.insert(2, "\r\n\r\r")
        str = str.substring(0, 2) + "\r\n\r\r" + str.substring(2)
        pieceTable.insert(3, "\r\r\r\n")
        str = str.substring(0, 3) + "\r\r\r\n" + str.substring(3)

        val lines = str.lines()
        assertEquals(pieceTable.getLineCount(), lines.size)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 4`() {
        var str = "\n\n\n\n"
        val pieceTable = createPieceTree(listOf("\n\n\n\n"), false)

        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(1, "\r\r\r\r")
        str = str.substring(0, 1) + "\r\r\r\r" + str.substring(1)
        pieceTable.insert(6, "\r\n\n\r")
        str = str.substring(0, 6) + "\r\n\n\r" + str.substring(6)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 5`() {
        var str = "\n\n\n\n"
        val pieceTable = createPieceTree(listOf("\n\n\n\n"), false)

        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(0, "\n\r\r\n")
        str = str.substring(0, 0) + "\n\r\r\n" + str.substring(0)
        pieceTable.insert(4, "\n\r\r\n")
        str = str.substring(0, 4) + "\n\r\r\n" + str.substring(4)
        pieceTable.delete(4, 3)
        str = str.substring(0, 4) + str.substring(4 + 3)
        pieceTable.insert(5, "\r\r\n\r")
        str = str.substring(0, 5) + "\r\r\n\r" + str.substring(5)
        pieceTable.insert(12, "\n\n\n\r")
        str = str.substring(0, 12) + "\n\n\n\r" + str.substring(12)
        pieceTable.insert(5, "\r\r\r\n")
        str = str.substring(0, 5) + "\r\r\r\n" + str.substring(5)
        pieceTable.insert(20, "\n\n\r\n")
        str = str.substring(0, 20) + "\n\n\r\n" + str.substring(20)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 6`() {
        var str = "\n\r\r\n"
        val pieceTable = createPieceTree(listOf("\n\r\r\n"), false)

        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(3, "\r\n\n\n")
        str = str.substring(0, 3) + "\r\n\n\n" + str.substring(3)
        pieceTable.delete(4, 8)
        str = str.substring(0, 4) + str.substring(4 + 8)
        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(0, "\r\n\n\r")
        str = str.substring(0, 0) + "\r\n\n\r" + str.substring(0)
        pieceTable.delete(4, 0)
        str = str.substring(0, 4) + str.substring(4 + 0)
        pieceTable.delete(8, 4)
        str = str.substring(0, 8) + str.substring(8 + 4)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 7`() {
        var str = "\r\n\n\r"
        val pieceTable = createPieceTree(listOf("\r\n\n\r"), false)

        pieceTable.delete(1, 0)
        str = str.substring(0, 1) + str.substring(1 + 0)
        pieceTable.insert(3, "\n\n\n\r")
        str = str.substring(0, 3) + "\n\n\n\r" + str.substring(3)
        pieceTable.insert(7, "\n\n\r\n")
        str = str.substring(0, 7) + "\n\n\r\n" + str.substring(7)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 8`() {
        var str = "\r\r\n\n"
        val pieceTable = createPieceTree(listOf("\r\r\n\n"), false)

        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(7, "\n\r\r\r")
        str = str.substring(0, 7) + "\n\r\r\r" + str.substring(7)
        pieceTable.insert(11, "\n\n\r\n")
        str = str.substring(0, 11) + "\n\n\r\n" + str.substring(11)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 9`() {
        var str = "qneW"
        val pieceTable = createPieceTree(listOf("qneW"), false)

        pieceTable.insert(0, "YhIl")
        str = str.substring(0, 0) + "YhIl" + str.substring(0)
        pieceTable.insert(0, "qdsm")
        str = str.substring(0, 0) + "qdsm" + str.substring(0)
        pieceTable.delete(7, 0)
        str = str.substring(0, 7) + str.substring(7 + 0)
        pieceTable.insert(12, "iiPv")
        str = str.substring(0, 12) + "iiPv" + str.substring(12)
        pieceTable.insert(9, "V\rSA")
        str = str.substring(0, 9) + "V\rSA" + str.substring(9)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 10`() {
        var str = "\n\n\n\n"
        val pieceTable = createPieceTree(listOf("\n\n\n\n"), false)

        pieceTable.insert(3, "\n\r\n\r")
        str = str.substring(0, 3) + "\n\r\n\r" + str.substring(3)
        pieceTable.insert(2, "\n\r\n\n")
        str = str.substring(0, 2) + "\n\r\n\n" + str.substring(2)
        pieceTable.insert(0, "\n\n\r\r")
        str = str.substring(0, 0) + "\n\n\r\r" + str.substring(0)
        pieceTable.insert(3, "\r\r\r\r")
        str = str.substring(0, 3) + "\r\r\r\r" + str.substring(3)
        pieceTable.insert(3, "\n\n\r\r")
        str = str.substring(0, 3) + "\n\n\r\r" + str.substring(3)

        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 1`() {
        val pieceTable = createPieceTree(listOf("\n\r\r\n\n\n\r\n\r"), false)
        var str = "\n\r\r\n\n\n\r\n\r"
        pieceTable.delete(0, 2)
        str = str.substring(0, 0) + str.substring(0 + 2)
        pieceTable.insert(1, "\r\r\n\n")
        str = str.substring(0, 1) + "\r\r\n\n" + str.substring(1)
        pieceTable.insert(7, "\r\r\r\r")
        str = str.substring(0, 7) + "\r\r\r\r" + str.substring(7)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 2`() {
        val pieceTable = createPieceTree(listOf("\n\r\n\n\n\r\n\r\n\r\r\n\n\n\r\r\n\r\n"), false)
        var str = "\n\r\n\n\n\r\n\r\n\r\r\n\n\n\r\r\n\r\n"
        pieceTable.insert(16, "\r\n\r\r")
        str = str.substring(0, 16) + "\r\n\r\r" + str.substring(16)
        pieceTable.insert(13, "\n\n\r\r")
        str = str.substring(0, 13) + "\n\n\r\r" + str.substring(13)
        pieceTable.insert(19, "\n\n\r\n")
        str = str.substring(0, 19) + "\n\n\r\n" + str.substring(19)
        pieceTable.delete(5, 0)
        str = str.substring(0, 5) + str.substring(5 + 0)
        pieceTable.delete(11, 2)
        str = str.substring(0, 11) + str.substring(11 + 2)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 3`() {
        val pieceTable = createPieceTree(listOf("\r\n\n\n\n\n\n\r\n"), false)
        var str = "\r\n\n\n\n\n\n\r\n"
        pieceTable.insert(4, "\n\n\r\n\r\r\n\n\r")
        str = str.substring(0, 4) + "\n\n\r\n\r\r\n\n\r" + str.substring(4)
        pieceTable.delete(4, 4)
        str = str.substring(0, 4) + str.substring(4 + 4)
        pieceTable.insert(11, "\r\n\r\n\n\r\r\n\n")
        str = str.substring(0, 11) + "\r\n\r\n\n\r\r\n\n" + str.substring(11)
        pieceTable.delete(1, 2)
        str = str.substring(0, 1) + str.substring(1 + 2)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 4`() {
        val pieceTable = createPieceTree(listOf("\n\r\n\r"), false)
        var str = "\n\r\n\r"
        pieceTable.insert(4, "\n\n\r\n")
        str = str.substring(0, 4) + "\n\n\r\n" + str.substring(4)
        pieceTable.insert(3, "\r\n\n\n")
        str = str.substring(0, 3) + "\r\n\n\n" + str.substring(3)

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region random is unsupervised
    @Test
    fun `splitting large change buffer`() {
        val pieceTable = createPieceTree(listOf(""), false)
        var str = ""

        pieceTable.insert(0, "WUZ\nXVZY\n")
        str = str.substring(0, 0) + "WUZ\nXVZY\n" + str.substring(0)
        pieceTable.insert(8, "\r\r\nZXUWVW")
        str = str.substring(0, 8) + "\r\r\nZXUWVW" + str.substring(8)
        pieceTable.delete(10, 7)
        str = str.substring(0, 10) + str.substring(10 + 7)
        pieceTable.delete(10, 1)
        str = str.substring(0, 10) + str.substring(10 + 1)
        pieceTable.insert(4, "VX\r\r\nWZVZ")
        str = str.substring(0, 4) + "VX\r\r\nWZVZ" + str.substring(4)
        pieceTable.delete(11, 3)
        str = str.substring(0, 11) + str.substring(11 + 3)
        pieceTable.delete(12, 4)
        str = str.substring(0, 12) + str.substring(12 + 4)
        pieceTable.delete(8, 0)
        str = str.substring(0, 8) + str.substring(8 + 0)
        pieceTable.delete(10, 2)
        str = str.substring(0, 10) + str.substring(10 + 2)
        pieceTable.insert(0, "VZXXZYZX\r")
        str = str.substring(0, 0) + "VZXXZYZX\r" + str.substring(0)

        assertEquals(pieceTable.getLinesRawContent(), str)

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random insert delete`() {
        Thread.sleep(500)
        var str = ""
        val pieceTable = createPieceTree(listOf(str), false)

        // var output = ""
        for (i in 0 until 1000) {
            if (Math.random() < 0.6) {
                // insert
                val text = randomString(100)
                val pos = randomInt(str.length + 1)
                pieceTable.insert(pos, text)
                str = str.substring(0, pos) + text + str.substring(pos)
                // output += `pieceTable.insert(${pos}, "${text.replace(/\n/g, "\\n").replace(/\r/g,
                // "\\r")}")\n`
                // output += `str = str.substring(0, ${pos}) + "${text.replace(/\n/g,
                // "\\n").replace(/\r/g, "\\r")}" + str.substring(${pos}\n`
            } else {
                // delete
                val pos = randomInt(str.length)
                val length =
                    Math.min(str.length - pos, Math.floor(Math.random() * 10.toDouble()).toInt())
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
                // output += `pieceTable.delete(${pos}, ${length}\n`
                // output += `str = str.substring(0, ${pos}) + str.substring(${pos} + ${length}\n`

            }
        }
        // println(output)

        assertEquals(pieceTable.getLinesRawContent(), str)

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunks`() {
        Thread.sleep(500)
        val chunks: MutableList<String> = mutableListOf()
        for (i in 0 until 5) {
            chunks.add(randomString(1000))
        }

        val pieceTable = createPieceTree(chunks, false)
        var str = chunks.joinToString("")

        for (i in 0 until 1000) {
            if (Math.random() < 0.6) {
                // insert
                val text = randomString(100)
                val pos = randomInt(str.length + 1)
                pieceTable.insert(pos, text)
                str = str.substring(0, pos) + text + str.substring(pos)
            } else {
                // delete
                val pos = randomInt(str.length)
                val length =
                    Math.min(str.length - pos, Math.floor(Math.random() * 10.toDouble()).toInt())
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
            }
        }

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunks 2`() {
        Thread.sleep(500)
        val chunks: MutableList<String> = mutableListOf()
        chunks.add(randomString(1000))

        val pieceTable = createPieceTree(chunks, false)
        var str = chunks.joinToString("")

        for (i in 0 until 50) {
            if (Math.random() < 0.6) {
                // insert
                val text = randomString(30)
                val pos = randomInt(str.length + 1)
                pieceTable.insert(pos, text)
                str = str.substring(0, pos) + text + str.substring(pos)
            } else {
                // delete
                val pos = randomInt(str.length)
                val length =
                    Math.min(str.length - pos, Math.floor(Math.random() * 10.toDouble()).toInt())
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
            }
            testLinesContent(str, pieceTable)
        }

        assertEquals(pieceTable.getLinesRawContent(), str)
        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region buffer api
    @Test
    fun `equal`() {
        val a = createPieceTree(listOf("abc"))
        val b = createPieceTree(listOf("ab", "c"))
        val c = createPieceTree(listOf("abd"))
        val d = createPieceTree(listOf("abcd"))

        assertTrue(a.equal(b))
        assertTrue(!a.equal(c))
        assertTrue(!a.equal(d))
    }

    @Test
    fun `equal 2 empty buffer`() {
        val a = createPieceTree(listOf(""))
        val b = createPieceTree(listOf(""))

        assertTrue(a.equal(b))
    }

    @Test
    fun `equal 3 empty buffer`() {
        val a = createPieceTree(listOf("a"))
        val b = createPieceTree(listOf(""))

        assertTrue(!a.equal(b))
    }

    @Test
    fun `getLineCharCode issue 45735`() {
        val pieceTable = createPieceTree(listOf("LINE1\nline2"))
        assertEquals(pieceTable.getLineCharCode(1, 0), "L".codePointAt(0), "L")
        assertEquals(pieceTable.getLineCharCode(1, 1), "I".codePointAt(0), "I")
        assertEquals(pieceTable.getLineCharCode(1, 2), "N".codePointAt(0), "N")
        assertEquals(pieceTable.getLineCharCode(1, 3), "E".codePointAt(0), "E")
        assertEquals(pieceTable.getLineCharCode(1, 4), "1".codePointAt(0), "1")
        assertEquals(pieceTable.getLineCharCode(1, 5), "\n".codePointAt(0), "\\n")
        assertEquals(pieceTable.getLineCharCode(2, 0), "l".codePointAt(0), "l")
        assertEquals(pieceTable.getLineCharCode(2, 1), "i".codePointAt(0), "i")
        assertEquals(pieceTable.getLineCharCode(2, 2), "n".codePointAt(0), "n")
        assertEquals(pieceTable.getLineCharCode(2, 3), "e".codePointAt(0), "e")
        assertEquals(pieceTable.getLineCharCode(2, 4), "2".codePointAt(0), "2")
    }

    @Test
    fun `getLineCharCode issue 47733`() {
        val pieceTable = createPieceTree(listOf("", "LINE1\n", "line2"))
        assertEquals(pieceTable.getLineCharCode(1, 0), "L".codePointAt(0), "L")
        assertEquals(pieceTable.getLineCharCode(1, 1), "I".codePointAt(0), "I")
        assertEquals(pieceTable.getLineCharCode(1, 2), "N".codePointAt(0), "N")
        assertEquals(pieceTable.getLineCharCode(1, 3), "E".codePointAt(0), "E")
        assertEquals(pieceTable.getLineCharCode(1, 4), "1".codePointAt(0), "1")
        assertEquals(pieceTable.getLineCharCode(1, 5), "\n".codePointAt(0), "\\n")
        assertEquals(pieceTable.getLineCharCode(2, 0), "l".codePointAt(0), "l")
        assertEquals(pieceTable.getLineCharCode(2, 1), "i".codePointAt(0), "i")
        assertEquals(pieceTable.getLineCharCode(2, 2), "n".codePointAt(0), "n")
        assertEquals(pieceTable.getLineCharCode(2, 3), "e".codePointAt(0), "e")
        assertEquals(pieceTable.getLineCharCode(2, 4), "2".codePointAt(0), "2")
    }
    
    @Test
    fun `getNearestChunk`() {
        val pieceTree = createTextBuffer(listOf("012345678"))
        val pt = pieceTree.getPieceTree()

        pt.insert(3, "ABC")
        assertEquals(pt.getLineContent(1), "012ABC345678")
        assertEquals(pt.getNearestChunk(3), "ABC")
        assertEquals(pt.getNearestChunk(6), "345678")

        pt.delete(9, 1)
        assertEquals(pt.getLineContent(1), "012ABC34578")
        assertEquals(pt.getNearestChunk(6), "345")
        assertEquals(pt.getNearestChunk(9), "78")
    } 

    // #endregion

    // #region search offset cache
    @Test
    fun `render white space exception`() {
        val pieceTable =
            createPieceTree(listOf("class Name{\n\t\n\t\t\tget() {\n\n\t\t\t}\n\t\t}"))
        var str = "class Name{\n\t\n\t\t\tget() {\n\n\t\t\t}\n\t\t}"

        pieceTable.insert(12, "s")
        str = str.substring(0, 12) + "s" + str.substring(12)

        pieceTable.insert(13, "e")
        str = str.substring(0, 13) + "e" + str.substring(13)

        pieceTable.insert(14, "t")
        str = str.substring(0, 14) + "t" + str.substring(14)

        pieceTable.insert(15, "()")
        str = str.substring(0, 15) + "()" + str.substring(15)

        pieceTable.delete(16, 1)
        str = str.substring(0, 16) + str.substring(16 + 1)

        pieceTable.insert(17, "()")
        str = str.substring(0, 17) + "()" + str.substring(17)

        pieceTable.delete(18, 1)
        str = str.substring(0, 18) + str.substring(18 + 1)

        pieceTable.insert(18, "}")
        str = str.substring(0, 18) + "}" + str.substring(18)

        pieceTable.insert(12, "\n")
        str = str.substring(0, 12) + "\n" + str.substring(12)

        pieceTable.delete(12, 1)
        str = str.substring(0, 12) + str.substring(12 + 1)

        pieceTable.delete(18, 1)
        str = str.substring(0, 18) + str.substring(18 + 1)

        pieceTable.insert(18, "}")
        str = str.substring(0, 18) + "}" + str.substring(18)

        pieceTable.delete(17, 2)
        str = str.substring(0, 17) + str.substring(17 + 2)

        pieceTable.delete(16, 1)
        str = str.substring(0, 16) + str.substring(16 + 1)

        pieceTable.insert(16, ")")
        str = str.substring(0, 16) + ")" + str.substring(16)

        pieceTable.delete(15, 2)
        str = str.substring(0, 15) + str.substring(15 + 2)

        val content = pieceTable.getLinesRawContent()
        assertEquals(content, str)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized`() {
        val pieceTable = createPieceTree(listOf("abc"))
        var str = "abc"

        pieceTable.insert(3, "def\nabc")
        str = str + "def\nabc"

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 2`() {
        val pieceTable = createPieceTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(4, "def\nabc")
        str = str + "def\nabc"

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 3`() {
        val pieceTable = createPieceTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(2, "def\nabc")
        str = str.substring(0, 2) + "def\nabc" + str.substring(2)

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 4`() {
        val pieceTable = createPieceTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(3, "def\nabc")
        str = str.substring(0, 3) + "def\nabc" + str.substring(3)

        testLineStarts(str, pieceTable)
        testLinesContent(str, pieceTable)
        assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region snapshot
    internal fun getValueInSnapshot(snapshot: PieceTreeSnapshot): String {
        val ret = StringBuffer()
        var tmp = snapshot.read()

        while (tmp != null) {
            ret.append(tmp)
            tmp = snapshot.read()
        }

        return ret.toString()
    }


    @Test
    fun `bug 45564 piece tree pieces should be immutable`() {
        val model = createTextBuffer(listOf("\n"))
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)
        val snapshot = model.createSnapshot(false)
        val snapshot1 = model.createSnapshot(false)
        assertEquals(model.getLinesContent().joinToString("\n"), getValueInSnapshot(snapshot))

        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 2), text = "")), false, false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)

        assertEquals(model.getLinesContent().joinToString("\n"), getValueInSnapshot(snapshot1))
    }


    @Test
    fun `immutable snapshot 1`() {
        val model = createTextBuffer(listOf("abc\ndef"))
        val snapshot = model.createSnapshot(false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 4), text = "")), false, false)

        model.applyEdits(listOf(SingleEditOperation(range = Range(1, 1, 2, 1), text = "abc\ndef")), false, false)

        assertEquals(model.getLinesContent().joinToString("\n"), getValueInSnapshot(snapshot))
    }


    @Test
    fun `immutable snapshot 2`() {
        val model = createTextBuffer(listOf("abc\ndef"))
        val snapshot = model.createSnapshot(false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)

        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 2), text = "")), false, false)

        assertEquals(model.getLinesContent().joinToString("\n"), getValueInSnapshot(snapshot))
    }

    @Test
    fun `immutable snapshot 3`() {
        val pieceBuffer = createTextBuffer(listOf("abc\ndef"))
        pieceBuffer.applyEdits(listOf(SingleEditOperation(range = Range(2, 4, 2, 4), text = "!")), false, false)
        val snapshot = pieceBuffer.createSnapshot(false)
        pieceBuffer.applyEdits(listOf(SingleEditOperation(range = Range(2, 5, 2, 5), text = "!")), false, false)

        assertNotEquals(pieceBuffer.getLinesContent().joinToString("\n"), getValueInSnapshot(snapshot))
    }

    // #endregion

    // #region chunk based search`() {
    @Test
    fun `bug 45892 For some cases the buffer is empty but we still try to search`() {
        val pieceTree = createPieceTree(listOf(""))
        pieceTree.delete(0, 1)
        val ret = pieceTree.findMatchesLineByLine(
            regex = Regex("abc"),
            searchRange = Range(1, 1, 1, 1),            
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(ret.size, 0)
    }

    @Test
    fun `bug 45770 FindInNode should not cross node boundary`() {
        val pieceTree = createPieceTree(listOf(
            arrayOf(
                "balabalababalabalababalabalaba",
                "balabalababalabalababalabalaba",
                "",
                "* [ ] task1",
                "* [x] task2 balabalaba",
                "* [ ] task 3"
            ).joinToString("\n")
        ))
        pieceTree.delete(0, 62)
        pieceTree.delete(16, 1)

        pieceTree.insert(16, " ")
        val ret = pieceTree.findMatchesLineByLine(
            regex = Regex("\\["),
            searchRange = Range(1, 1, 4, 13),           
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(ret.size, 3)

        assertEquals(ret[0], Range(2, 3, 2, 4))
        assertEquals(ret[1], Range(3, 3, 3, 4))
        assertEquals(ret[2], Range(4, 3, 4, 4))
    }

    @Test
    fun `search searching from the middle`() {
        val pieceTree = createPieceTree(listOf(arrayOf("def", "dbcabc").joinToString("\n")))
        pieceTree.delete(4, 1)
        var ret = pieceTree.findMatchesLineByLine(
            regex = Regex("a"),
            searchRange = Range(2, 3, 2, 6),           
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(ret.size, 1)
        assertEquals(ret[0], Range(2, 3, 2, 4))

        pieceTree.delete(4, 1)
        ret = pieceTree.findMatchesLineByLine(
            regex = Regex("a"),
            searchRange = Range(2, 2, 2, 5), 
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(ret.size, 1)
        assertEquals(ret[0], Range(2, 2, 2, 3))
    }
    
    // #endregion
}
