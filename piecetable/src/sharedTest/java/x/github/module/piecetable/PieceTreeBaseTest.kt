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
 * src/vs/editor/test/common/model/pieceTreeTextBuffer/pieceTreeTextBuffer.test.ts, and changed since.
 */

package x.github.module.piecetable

import x.github.module.piecetable.common.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.text.Regex

/**
 * The suite ported from VSCode's pieceTreeTextBuffer.test.ts.
 *
 * Differences from the original port:
 *   - shared assertions moved to TestSupport, which also reconciles the tree metadata
 *   - random cases use a fixed seed so failures reproduce; the pointless Thread.sleep is gone
 *   - assertEquals arguments are in the (expected, actual) order
 */
class PieceTreeBaseTest {

    // SENTINEL / DETACHED are shared by every buffer in the process, keep the cases independent
    @BeforeTest
    fun resetGlobalState() = TestSupport.resetGlobalState()

    // fixed seed: a failure replays exactly by keeping this value
    private companion object {
        const val SEED = 20220613L
    }

    private val random = Random(SEED)

    private fun randomInt(bound: Int) = if (bound <= 0) 0 else random.nextInt(bound)

    private fun randomString(length: Int) = TestSupport.randomString(random, length)

    // #region inserts and deletes
    @Test
    fun `basic insert and delete`() {
        val pieceTable = TestSupport.buildTree(listOf("This is a document with some text."))

        pieceTable.insert(34, "This is some more text to insert at offset 34.")

        assertEquals("This is a document with some text.This is some more text to insert at offset 34.", pieceTable.getLinesRawContent())

        pieceTable.delete(42, 5)
        assertEquals("This is a document with some text.This is more text to insert at offset 34.", pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `more inserts`() {
        val pt = TestSupport.buildTree(listOf(""))
        pt.insert(0, "AAA")
        assertEquals("AAA", pt.getLinesRawContent())
        pt.insert(0, "BBB")
        assertEquals("BBBAAA", pt.getLinesRawContent())
        pt.insert(6, "CCC")
        assertEquals("BBBAAACCC", pt.getLinesRawContent())
        pt.insert(5, "DDD")
        assertEquals("BBBAADDDACCC", pt.getLinesRawContent())
        TestSupport.assertTreeInvariants(pt)
    }

    @Test
    fun `more deletes`() {
        val pt = TestSupport.buildTree(listOf("012345678"))
        pt.delete(8, 1)
        assertEquals("01234567", pt.getLinesRawContent())
        pt.delete(0, 1)
        assertEquals("1234567", pt.getLinesRawContent())
        pt.delete(5, 1)
        assertEquals("123457", pt.getLinesRawContent())
        pt.delete(5, 1)
        assertEquals("12345", pt.getLinesRawContent())
        pt.delete(0, 5)
        assertEquals("", pt.getLinesRawContent())
        TestSupport.assertTreeInvariants(pt)
    }

    @Test
    fun `random test 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
        pieceTable.insert(0, "ceLPHmFzvCtFeHkCBej ")
        str = str.substring(0, 0) + "ceLPHmFzvCtFeHkCBej " + str.substring(0)
        assertEquals(str, pieceTable.getLinesRawContent())
        pieceTable.insert(8, "gDCEfNYiBUNkSwtvB K ")
        str = str.substring(0, 8) + "gDCEfNYiBUNkSwtvB K " + str.substring(8)
        assertEquals(str, pieceTable.getLinesRawContent())
        pieceTable.insert(38, "cyNcHxjNPPoehBJldLS ")
        str = str.substring(0, 38) + "cyNcHxjNPPoehBJldLS " + str.substring(38)
        assertEquals(str, pieceTable.getLinesRawContent())
        pieceTable.insert(59, "ejMx\nOTgWlbpeDExjOk ")
        str = str.substring(0, 59) + "ejMx\nOTgWlbpeDExjOk " + str.substring(59)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test 3`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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
        assertEquals(str, pieceTable.getLinesRawContent())
    }

    @Test
    fun `random delete 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))

        pieceTable.insert(0, "vfb")
        str = str.substring(0, 0) + "vfb" + str.substring(0)
        assertEquals(str, pieceTable.getLinesRawContent())
        pieceTable.insert(0, "zRq")
        str = str.substring(0, 0) + "zRq" + str.substring(0)
        assertEquals(str, pieceTable.getLinesRawContent())

        pieceTable.delete(5, 1)
        str = str.substring(0, 5) + str.substring(5 + 1)
        assertEquals(str, pieceTable.getLinesRawContent())

        pieceTable.insert(1, "UNw")
        str = str.substring(0, 1) + "UNw" + str.substring(1)
        assertEquals(str, pieceTable.getLinesRawContent())

        pieceTable.delete(4, 3)
        str = str.substring(0, 4) + str.substring(4 + 3)
        assertEquals(str, pieceTable.getLinesRawContent())

        pieceTable.delete(1, 4)
        str = str.substring(0, 1) + str.substring(1 + 4)
        assertEquals(str, pieceTable.getLinesRawContent())

        pieceTable.delete(0, 1)
        str = str.substring(0, 0) + str.substring(0 + 1)
        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random delete 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))

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
        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random delete 3`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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
        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 1`() {
        var str = "a"
        val pieceTable = TestSupport.buildTree(listOf("a"))
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 2`() {
        var str = "a"
        val pieceTable = TestSupport.buildTree(listOf("a"))
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 3`() {
        var str = "a"
        val pieceTable = TestSupport.buildTree(listOf("a"))
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
        assertEquals(str, pieceTable.getLinesRawContent())
        pieceTable.delete(21, 3)
        str = str.substring(0, 21) + str.substring(21 + 3)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 4`() {
        var str = "a"
        val pieceTable = TestSupport.buildTree(listOf("a"))
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // \r bug
    @Test
    fun `random insert and delete bug 5`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region prefix sum for line feed
    @Test
    fun `basic`() {
        val pieceTable = TestSupport.buildTree(listOf("1\n2\n3\n4"))

        assertEquals(4, pieceTable.getLineCount())
        assertEquals(Position(1, 1), pieceTable.getPositionAt(0))
        assertEquals(Position(1, 2), pieceTable.getPositionAt(1))
        assertEquals(Position(2, 1), pieceTable.getPositionAt(2))
        assertEquals(Position(2, 2), pieceTable.getPositionAt(3))
        assertEquals(Position(3, 1), pieceTable.getPositionAt(4))
        assertEquals(Position(3, 2), pieceTable.getPositionAt(5))
        assertEquals(Position(4, 1), pieceTable.getPositionAt(6))

        assertEquals(0, pieceTable.getOffsetAt(1, 1))
        assertEquals(1, pieceTable.getOffsetAt(1, 2))
        assertEquals(2, pieceTable.getOffsetAt(2, 1))
        assertEquals(3, pieceTable.getOffsetAt(2, 2))
        assertEquals(4, pieceTable.getOffsetAt(3, 1))
        assertEquals(5, pieceTable.getOffsetAt(3, 2))
        assertEquals(6, pieceTable.getOffsetAt(4, 1))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `append`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")

        assertEquals(6, pieceTable.getLineCount())
        assertEquals(Position(4, 4), pieceTable.getPositionAt(9))
        assertEquals(0, pieceTable.getOffsetAt(1, 1))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(7, "fh\ni\njk")

        assertEquals(6, pieceTable.getLineCount())
        assertEquals(Position(4, 1), pieceTable.getPositionAt(6))
        assertEquals(Position(4, 2), pieceTable.getPositionAt(7))
        assertEquals(Position(4, 3), pieceTable.getPositionAt(8))
        assertEquals(Position(4, 4), pieceTable.getPositionAt(9))
        assertEquals(Position(6, 1), pieceTable.getPositionAt(12))
        assertEquals(Position(6, 2), pieceTable.getPositionAt(13))
        assertEquals(Position(6, 3), pieceTable.getPositionAt(14))

        assertEquals(6, pieceTable.getOffsetAt(4, 1))
        assertEquals(7, pieceTable.getOffsetAt(4, 2))
        assertEquals(8, pieceTable.getOffsetAt(4, 3))
        assertEquals(9, pieceTable.getOffsetAt(4, 4))
        assertEquals(12, pieceTable.getOffsetAt(6, 1))
        assertEquals(13, pieceTable.getOffsetAt(6, 2))
        assertEquals(14, pieceTable.getOffsetAt(6, 3))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\ndefh\ni\njk"))
        pieceTable.delete(7, 2)

        assertEquals("a\nb\nc\ndh\ni\njk", pieceTable.getLinesRawContent())
        assertEquals(6, pieceTable.getLineCount())
        assertEquals(Position(4, 1), pieceTable.getPositionAt(6))
        assertEquals(Position(4, 2), pieceTable.getPositionAt(7))
        assertEquals(Position(4, 3), pieceTable.getPositionAt(8))
        assertEquals(Position(5, 1), pieceTable.getPositionAt(9))
        assertEquals(Position(6, 1), pieceTable.getPositionAt(11))
        assertEquals(Position(6, 2), pieceTable.getPositionAt(12))
        assertEquals(Position(6, 3), pieceTable.getPositionAt(13))

        assertEquals(6, pieceTable.getOffsetAt(4, 1))
        assertEquals(7, pieceTable.getOffsetAt(4, 2))
        assertEquals(8, pieceTable.getOffsetAt(4, 3))
        assertEquals(9, pieceTable.getOffsetAt(5, 1))
        assertEquals(11, pieceTable.getOffsetAt(6, 1))
        assertEquals(12, pieceTable.getOffsetAt(6, 2))
        assertEquals(13, pieceTable.getOffsetAt(6, 3))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `add and delete 1`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)

        assertEquals("a\nb\nc\ndh\ni\njk", pieceTable.getLinesRawContent())
        assertEquals(6, pieceTable.getLineCount())
        assertEquals(Position(4, 1), pieceTable.getPositionAt(6))
        assertEquals(Position(4, 2), pieceTable.getPositionAt(7))
        assertEquals(Position(4, 3), pieceTable.getPositionAt(8))
        assertEquals(Position(5, 1), pieceTable.getPositionAt(9))
        assertEquals(Position(6, 1), pieceTable.getPositionAt(11))
        assertEquals(Position(6, 2), pieceTable.getPositionAt(12))
        assertEquals(Position(6, 3), pieceTable.getPositionAt(13))

        assertEquals(6, pieceTable.getOffsetAt(4, 1))
        assertEquals(7, pieceTable.getOffsetAt(4, 2))
        assertEquals(8, pieceTable.getOffsetAt(4, 3))
        assertEquals(9, pieceTable.getOffsetAt(5, 1))
        assertEquals(11, pieceTable.getOffsetAt(6, 1))
        assertEquals(12, pieceTable.getOffsetAt(6, 2))
        assertEquals(13, pieceTable.getOffsetAt(6, 3))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert random bug 1 prefixSumComputer cnt is 1 based`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
        pieceTable.insert(0, " ZX \n Z\nZ\n YZ\nY\nZXX ")
        str = str.substring(0, 0) + " ZX \n Z\nZ\n YZ\nY\nZXX " + str.substring(0)
        pieceTable.insert(14, "X ZZ\nYZZYZXXY Y XY\n ")
        str = str.substring(0, 14) + "X ZZ\nYZZYZXXY Y XY\n " + str.substring(14)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `insert random bug 2 prefixSumComputer initialize does not do deep copy of UInt32Array`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
        pieceTable.insert(0, "ZYZ\nYY XY\nX \nZ Y \nZ ")
        str = str.substring(0, 0) + "ZYZ\nYY XY\nX \nZ Y \nZ " + str.substring(0)
        pieceTable.insert(3, "XXY \n\nY Y YYY  ZYXY ")
        str = str.substring(0, 3) + "XXY \n\nY Y YYY  ZYXY " + str.substring(3)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug 1 forgot to update the lineFeedCnt when deletion is on one single piece`() {
        val pieceTable = TestSupport.buildTree(listOf(""))
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
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))
        pieceTable.insert(0, "YXXZ\n\nYY\n")
        str = str.substring(0, 0) + "YXXZ\n\nYY\n" + str.substring(0)
        pieceTable.delete(0, 5)
        str = str.substring(0, 0) + str.substring(0 + 5)
        pieceTable.insert(0, "ZXYY\nX\nZ\n")
        str = str.substring(0, 0) + "ZXYY\nX\nZ\n" + str.substring(0)
        pieceTable.insert(10, "\nXY\nYXYXY")
        str = str.substring(0, 10) + "\nXY\nYXYXY" + str.substring(10)
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))
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

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete random bug rb tree 3`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))
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

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region offset to position
    @Test
    fun `random tests offset bug 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))
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

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region get text in range
    @Test
    fun `getContentInRange`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)
        // "a\nb\nc\ndh\ni\njk"

        assertEquals("a\n", pieceTable.getValueInRange(Range(1, 1, 1, 3)))
        assertEquals("b\n", pieceTable.getValueInRange(Range(2, 1, 2, 3)))
        assertEquals("c\n", pieceTable.getValueInRange(Range(3, 1, 3, 3)))
        assertEquals("dh\n", pieceTable.getValueInRange(Range(4, 1, 4, 4)))
        assertEquals("i\n", pieceTable.getValueInRange(Range(5, 1, 5, 3)))
        assertEquals("jk", pieceTable.getValueInRange(Range(6, 1, 6, 3)))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test value in range`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random test value in range exception`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str))

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
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random tests bug 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random tests bug 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content`() {
        val pieceTable = TestSupport.buildTree(listOf("1"))
        assertEquals("1", pieceTable.getLineRawContent(1))
        pieceTable.insert(1, "2")
        assertEquals("12", pieceTable.getLineRawContent(1))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content basic`() {
        val pieceTable = TestSupport.buildTree(listOf("1\n2\n3\n4"))
        assertEquals("1\n", pieceTable.getLineRawContent(1))
        assertEquals("2\n", pieceTable.getLineRawContent(2))
        assertEquals("3\n", pieceTable.getLineRawContent(3))
        assertEquals("4", pieceTable.getLineRawContent(4))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `get line content after inserts and deletes`() {
        val pieceTable = TestSupport.buildTree(listOf("a\nb\nc\nde"))
        pieceTable.insert(8, "fh\ni\njk")
        pieceTable.delete(7, 2)
        // "a\nb\nc\ndh\ni\njk"

        assertEquals("a\n", pieceTable.getLineRawContent(1))
        assertEquals("b\n", pieceTable.getLineRawContent(2))
        assertEquals("c\n", pieceTable.getLineRawContent(3))
        assertEquals("dh\n", pieceTable.getLineRawContent(4))
        assertEquals("i\n", pieceTable.getLineRawContent(5))
        assertEquals("jk", pieceTable.getLineRawContent(6))
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))

        pieceTable.insert(0, "J eNnDzQpnlWyjmUu\ny ")
        str = str.substring(0, 0) + "J eNnDzQpnlWyjmUu\ny " + str.substring(0)
        pieceTable.insert(0, "QPEeRAQmRwlJqtZSWhQ ")
        str = str.substring(0, 0) + "QPEeRAQmRwlJqtZSWhQ " + str.substring(0)
        pieceTable.delete(5, 1)
        str = str.substring(0, 5) + str.substring(5 + 1)

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""))
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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region CRLF
    @Test
    fun `delete CR in CRLF 1`() {
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)
        pieceTable.insert(0, "a\r\nb")
        pieceTable.delete(0, 2)

        assertEquals(2, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete CR in CRLF 2`() {
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)
        pieceTable.insert(0, "a\r\nb")
        pieceTable.delete(2, 2)

        assertEquals(2, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 1`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)
        pieceTable.insert(0, "\n\n\r\r")
        str = str.substring(0, 0) + "\n\n\r\r" + str.substring(0)
        pieceTable.insert(1, "\r\n\r\n")
        str = str.substring(0, 1) + "\r\n\r\n" + str.substring(1)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)
        pieceTable.delete(2, 3)
        str = str.substring(0, 2) + str.substring(2 + 3)

        val lines = str.lines()
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 2`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

        pieceTable.insert(0, "\n\r\n\r")
        str = str.substring(0, 0) + "\n\r\n\r" + str.substring(0)
        pieceTable.insert(2, "\n\r\r\r")
        str = str.substring(0, 2) + "\n\r\r\r" + str.substring(2)
        pieceTable.delete(4, 1)
        str = str.substring(0, 4) + str.substring(4 + 1)

        val lines = str.lines()
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 3`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 4`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 5`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 6`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 8`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

        pieceTable.insert(0, "\r\n\n\r")
        str = str.substring(0, 0) + "\r\n\n\r" + str.substring(0)
        pieceTable.delete(1, 0)
        str = str.substring(0, 1) + str.substring(1 + 0)
        pieceTable.insert(3, "\n\n\n\r")
        str = str.substring(0, 3) + "\n\n\n\r" + str.substring(3)
        pieceTable.insert(7, "\n\n\r\n")
        str = str.substring(0, 7) + "\n\n\r\n" + str.substring(7)

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 7`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

        pieceTable.insert(0, "\r\r\n\n")
        str = str.substring(0, 0) + "\r\r\n\n" + str.substring(0)
        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(7, "\n\r\r\r")
        str = str.substring(0, 7) + "\n\r\r\r" + str.substring(7)
        pieceTable.insert(11, "\n\n\r\n")
        str = str.substring(0, 11) + "\n\n\r\n" + str.substring(11)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 10`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random bug 9`() {
        var str = ""
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region centralized lineStarts with CRLF
    @Test
    fun `delete centralized CR in CRLF 1`() {
        val pieceTable = TestSupport.buildTree(listOf("a\r\nb"), normalizeEOL = false)
        pieceTable.delete(2, 2)
        assertEquals(2, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `delete centralized CR in CRLF 2`() {
        val pieceTable = TestSupport.buildTree(listOf("a\r\nb"))
        pieceTable.delete(0, 2)

        assertEquals(2, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 1`() {
        var str = "\n\n\r\r"
        val pieceTable = TestSupport.buildTree(listOf("\n\n\r\r"), normalizeEOL = false)
        pieceTable.insert(1, "\r\n\r\n")
        str = str.substring(0, 1) + "\r\n\r\n" + str.substring(1)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)
        pieceTable.delete(2, 3)
        str = str.substring(0, 2) + str.substring(2 + 3)

        val lines = str.lines()
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 2`() {
        var str = "\n\r\n\r"
        val pieceTable = TestSupport.buildTree(listOf("\n\r\n\r"), normalizeEOL = false)

        pieceTable.insert(2, "\n\r\r\r")
        str = str.substring(0, 2) + "\n\r\r\r" + str.substring(2)
        pieceTable.delete(4, 1)
        str = str.substring(0, 4) + str.substring(4 + 1)

        val lines = str.lines()
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 3`() {
        var str = "\n\n\n\r"
        val pieceTable = TestSupport.buildTree(listOf("\n\n\n\r"), normalizeEOL = false)

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
        assertEquals(lines.size, pieceTable.getLineCount())
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 4`() {
        var str = "\n\n\n\n"
        val pieceTable = TestSupport.buildTree(listOf("\n\n\n\n"), normalizeEOL = false)

        pieceTable.delete(3, 1)
        str = str.substring(0, 3) + str.substring(3 + 1)
        pieceTable.insert(1, "\r\r\r\r")
        str = str.substring(0, 1) + "\r\r\r\r" + str.substring(1)
        pieceTable.insert(6, "\r\n\n\r")
        str = str.substring(0, 6) + "\r\n\n\r" + str.substring(6)
        pieceTable.delete(5, 3)
        str = str.substring(0, 5) + str.substring(5 + 3)

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 5`() {
        var str = "\n\n\n\n"
        val pieceTable = TestSupport.buildTree(listOf("\n\n\n\n"), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 6`() {
        var str = "\n\r\r\n"
        val pieceTable = TestSupport.buildTree(listOf("\n\r\r\n"), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 7`() {
        var str = "\r\n\n\r"
        val pieceTable = TestSupport.buildTree(listOf("\r\n\n\r"), normalizeEOL = false)

        pieceTable.delete(1, 0)
        str = str.substring(0, 1) + str.substring(1 + 0)
        pieceTable.insert(3, "\n\n\n\r")
        str = str.substring(0, 3) + "\n\n\n\r" + str.substring(3)
        pieceTable.insert(7, "\n\n\r\n")
        str = str.substring(0, 7) + "\n\n\r\n" + str.substring(7)

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 8`() {
        var str = "\r\r\n\n"
        val pieceTable = TestSupport.buildTree(listOf("\r\r\n\n"), normalizeEOL = false)

        pieceTable.insert(4, "\r\n\n\r")
        str = str.substring(0, 4) + "\r\n\n\r" + str.substring(4)
        pieceTable.insert(7, "\n\r\r\r")
        str = str.substring(0, 7) + "\n\r\r\r" + str.substring(7)
        pieceTable.insert(11, "\n\n\r\n")
        str = str.substring(0, 11) + "\n\n\r\n" + str.substring(11)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 9`() {
        var str = "qneW"
        val pieceTable = TestSupport.buildTree(listOf("qneW"), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random CRLF bug 10`() {
        var str = "\n\n\n\n"
        val pieceTable = TestSupport.buildTree(listOf("\n\n\n\n"), normalizeEOL = false)

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

        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 1`() {
        val pieceTable = TestSupport.buildTree(listOf("\n\r\r\n\n\n\r\n\r"), normalizeEOL = false)
        var str = "\n\r\r\n\n\n\r\n\r"
        pieceTable.delete(0, 2)
        str = str.substring(0, 0) + str.substring(0 + 2)
        pieceTable.insert(1, "\r\r\n\n")
        str = str.substring(0, 1) + "\r\r\n\n" + str.substring(1)
        pieceTable.insert(7, "\r\r\r\r")
        str = str.substring(0, 7) + "\r\r\r\r" + str.substring(7)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 2`() {
        val pieceTable = TestSupport.buildTree(listOf("\n\r\n\n\n\r\n\r\n\r\r\n\n\n\r\r\n\r\n"), normalizeEOL = false)
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

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 3`() {
        val pieceTable = TestSupport.buildTree(listOf("\r\n\n\n\n\n\n\r\n"), normalizeEOL = false)
        var str = "\r\n\n\n\n\n\n\r\n"
        pieceTable.insert(4, "\n\n\r\n\r\r\n\n\r")
        str = str.substring(0, 4) + "\n\n\r\n\r\r\n\n\r" + str.substring(4)
        pieceTable.delete(4, 4)
        str = str.substring(0, 4) + str.substring(4 + 4)
        pieceTable.insert(11, "\r\n\r\n\n\r\r\n\n")
        str = str.substring(0, 11) + "\r\n\r\n\n\r\r\n\n" + str.substring(11)
        pieceTable.delete(1, 2)
        str = str.substring(0, 1) + str.substring(1 + 2)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunk bug 4`() {
        val pieceTable = TestSupport.buildTree(listOf("\n\r\n\r"), normalizeEOL = false)
        var str = "\n\r\n\r"
        pieceTable.insert(4, "\n\n\r\n")
        str = str.substring(0, 4) + "\n\n\r\n" + str.substring(4)
        pieceTable.insert(3, "\r\n\n\n")
        str = str.substring(0, 3) + "\r\n\n\n" + str.substring(3)

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region random is unsupervised
    @Test
    fun `splitting large change buffer`() {
        val pieceTable = TestSupport.buildTree(listOf(""), normalizeEOL = false)
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

        assertEquals(str, pieceTable.getLinesRawContent())

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random insert delete`() {
                var str = ""
        val pieceTable = TestSupport.buildTree(listOf(str), normalizeEOL = false)

        // var output = ""
        for (i in 0 until 1000) {
            if (random.nextDouble() < 0.6) {
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
                    Math.min(str.length - pos, randomInt(10))
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
                // output += `pieceTable.delete(${pos}, ${length}\n`
                // output += `str = str.substring(0, ${pos}) + str.substring(${pos} + ${length}\n`

            }
        }
        // println(output)

        assertEquals(str, pieceTable.getLinesRawContent())

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunks`() {
                val chunks: MutableList<String> = mutableListOf()
        for (i in 0 until 5) {
            chunks.add(randomString(1000))
        }

        val pieceTable = TestSupport.buildTree(chunks, normalizeEOL = false)
        var str = chunks.joinToString("")

        for (i in 0 until 1000) {
            if (random.nextDouble() < 0.6) {
                // insert
                val text = randomString(100)
                val pos = randomInt(str.length + 1)
                pieceTable.insert(pos, text)
                str = str.substring(0, pos) + text + str.substring(pos)
            } else {
                // delete
                val pos = randomInt(str.length)
                val length =
                    Math.min(str.length - pos, randomInt(10))
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
            }
        }

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `random chunks 2`() {
                val chunks: MutableList<String> = mutableListOf()
        chunks.add(randomString(1000))

        val pieceTable = TestSupport.buildTree(chunks, normalizeEOL = false)
        var str = chunks.joinToString("")

        for (i in 0 until 50) {
            if (random.nextDouble() < 0.6) {
                // insert
                val text = randomString(30)
                val pos = randomInt(str.length + 1)
                pieceTable.insert(pos, text)
                str = str.substring(0, pos) + text + str.substring(pos)
            } else {
                // delete
                val pos = randomInt(str.length)
                val length =
                    Math.min(str.length - pos, randomInt(10))
                pieceTable.delete(pos, length)
                str = str.substring(0, pos) + str.substring(pos + length)
            }
            TestSupport.assertLinesContent(str, pieceTable)
        }

        assertEquals(str, pieceTable.getLinesRawContent())
        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region buffer api
    @Test
    fun `equal`() {
        val a = TestSupport.buildTree(listOf("abc"))
        val b = TestSupport.buildTree(listOf("ab", "c"))
        val c = TestSupport.buildTree(listOf("abd"))
        val d = TestSupport.buildTree(listOf("abcd"))

        assertTrue(a.equal(b))
        assertTrue(!a.equal(c))
        assertTrue(!a.equal(d))
    }

    @Test
    fun `equal 2 empty buffer`() {
        val a = TestSupport.buildTree(listOf(""))
        val b = TestSupport.buildTree(listOf(""))

        assertTrue(a.equal(b))
    }

    @Test
    fun `equal 3 empty buffer`() {
        val a = TestSupport.buildTree(listOf("a"))
        val b = TestSupport.buildTree(listOf(""))

        assertTrue(!a.equal(b))
    }

    @Test
    fun `getLineCharCode issue 45735`() {
        val pieceTable = TestSupport.buildTree(listOf("LINE1\nline2"))
        assertEquals("L".codePointAt(0), pieceTable.getLineCharCode(1, 0), "L")
        assertEquals("I".codePointAt(0), pieceTable.getLineCharCode(1, 1), "I")
        assertEquals("N".codePointAt(0), pieceTable.getLineCharCode(1, 2), "N")
        assertEquals("E".codePointAt(0), pieceTable.getLineCharCode(1, 3), "E")
        assertEquals("1".codePointAt(0), pieceTable.getLineCharCode(1, 4), "1")
        assertEquals("\n".codePointAt(0), pieceTable.getLineCharCode(1, 5), "\\n")
        assertEquals("l".codePointAt(0), pieceTable.getLineCharCode(2, 0), "l")
        assertEquals("i".codePointAt(0), pieceTable.getLineCharCode(2, 1), "i")
        assertEquals("n".codePointAt(0), pieceTable.getLineCharCode(2, 2), "n")
        assertEquals("e".codePointAt(0), pieceTable.getLineCharCode(2, 3), "e")
        assertEquals("2".codePointAt(0), pieceTable.getLineCharCode(2, 4), "2")
    }

    @Test
    fun `getLineCharCode issue 47733`() {
        val pieceTable = TestSupport.buildTree(listOf("", "LINE1\n", "line2"))
        assertEquals("L".codePointAt(0), pieceTable.getLineCharCode(1, 0), "L")
        assertEquals("I".codePointAt(0), pieceTable.getLineCharCode(1, 1), "I")
        assertEquals("N".codePointAt(0), pieceTable.getLineCharCode(1, 2), "N")
        assertEquals("E".codePointAt(0), pieceTable.getLineCharCode(1, 3), "E")
        assertEquals("1".codePointAt(0), pieceTable.getLineCharCode(1, 4), "1")
        assertEquals("\n".codePointAt(0), pieceTable.getLineCharCode(1, 5), "\\n")
        assertEquals("l".codePointAt(0), pieceTable.getLineCharCode(2, 0), "l")
        assertEquals("i".codePointAt(0), pieceTable.getLineCharCode(2, 1), "i")
        assertEquals("n".codePointAt(0), pieceTable.getLineCharCode(2, 2), "n")
        assertEquals("e".codePointAt(0), pieceTable.getLineCharCode(2, 3), "e")
        assertEquals("2".codePointAt(0), pieceTable.getLineCharCode(2, 4), "2")
    }
    
    @Test
    fun `getNearestChunk`() {
        val pieceTree = TestSupport.buildBuffer(listOf("012345678"))
        val pt = pieceTree.getPieceTree()

        pt.insert(3, "ABC")
        assertEquals("012ABC345678", pt.getLineContent(1))
        assertEquals("ABC", pt.getNearestChunk(3))
        assertEquals("345678", pt.getNearestChunk(6))

        pt.delete(9, 1)
        assertEquals("012ABC34578", pt.getLineContent(1))
        assertEquals("345", pt.getNearestChunk(6))
        assertEquals("78", pt.getNearestChunk(9))
    } 

    // #endregion

    // #region search offset cache
    @Test
    fun `render white space exception`() {
        val pieceTable =
            TestSupport.buildTree(listOf("class Name{\n\t\n\t\t\tget() {\n\n\t\t\t}\n\t\t}"))
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
        assertEquals(str, content)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized`() {
        val pieceTable = TestSupport.buildTree(listOf("abc"))
        var str = "abc"

        pieceTable.insert(3, "def\nabc")
        str = str + "def\nabc"

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 2`() {
        val pieceTable = TestSupport.buildTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(4, "def\nabc")
        str = str + "def\nabc"

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 3`() {
        val pieceTable = TestSupport.buildTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(2, "def\nabc")
        str = str.substring(0, 2) + "def\nabc" + str.substring(2)

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    @Test
    fun `Line breaks replacement is not necessary when EOL is normalized 4`() {
        val pieceTable = TestSupport.buildTree(listOf("abc\n"))
        var str = "abc\n"

        pieceTable.insert(3, "def\nabc")
        str = str.substring(0, 3) + "def\nabc" + str.substring(3)

        TestSupport.assertLineStarts(str, pieceTable)
        TestSupport.assertLinesContent(str, pieceTable)
        TestSupport.assertTreeInvariants(pieceTable)
    }

    // #endregion

    // #region snapshot
    internal fun getValueInSnapshot(snapshot: PieceTreeSnapshot): String {
        val ret = StringBuilder()
        var tmp = snapshot.read()

        while (tmp != null) {
            ret.append(tmp)
            tmp = snapshot.read()
        }

        return ret.toString()
    }


    @Test
    fun `bug 45564 piece tree pieces should be immutable`() {
        val model = TestSupport.buildBuffer(listOf("\n"))
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)
        val snapshot = model.createSnapshot(false)
        val snapshot1 = model.createSnapshot(false)
        assertEquals(getValueInSnapshot(snapshot), model.getLinesContent().joinToString("\n"))

        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 2), text = "")), false, false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)

        assertEquals(getValueInSnapshot(snapshot1), model.getLinesContent().joinToString("\n"))
    }


    @Test
    fun `immutable snapshot 1`() {
        val model = TestSupport.buildBuffer(listOf("abc\ndef"))
        val snapshot = model.createSnapshot(false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 4), text = "")), false, false)

        model.applyEdits(listOf(SingleEditOperation(range = Range(1, 1, 2, 1), text = "abc\ndef")), false, false)

        assertEquals(getValueInSnapshot(snapshot), model.getLinesContent().joinToString("\n"))
    }


    @Test
    fun `immutable snapshot 2`() {
        val model = TestSupport.buildBuffer(listOf("abc\ndef"))
        val snapshot = model.createSnapshot(false)
        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 1), text = "!")), false, false)

        model.applyEdits(listOf(SingleEditOperation(range = Range(2, 1, 2, 2), text = "")), false, false)

        assertEquals(getValueInSnapshot(snapshot), model.getLinesContent().joinToString("\n"))
    }

    @Test
    fun `immutable snapshot 3`() {
        val pieceBuffer = TestSupport.buildBuffer(listOf("abc\ndef"))
        pieceBuffer.applyEdits(listOf(SingleEditOperation(range = Range(2, 4, 2, 4), text = "!")), false, false)
        val snapshot = pieceBuffer.createSnapshot(false)
        pieceBuffer.applyEdits(listOf(SingleEditOperation(range = Range(2, 5, 2, 5), text = "!")), false, false)

        assertNotEquals(getValueInSnapshot(snapshot), pieceBuffer.getLinesContent().joinToString("\n"))
    }

    // #endregion

    // #region chunk based search`() {
    @Test
    fun `bug 45892 For some cases the buffer is empty but we still try to search`() {
        val pieceTree = TestSupport.buildTree(listOf(""))
        pieceTree.delete(0, 1)
        val ret = pieceTree.findMatchesLineByLine(
            regex = Regex("abc"),
            searchRange = Range(1, 1, 1, 1),            
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(0, ret.size)
    }

    @Test
    fun `bug 45770 FindInNode should not cross node boundary`() {
        val pieceTree = TestSupport.buildTree(listOf(
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
        assertEquals(3, ret.size)

        assertEquals(Range(2, 3, 2, 4), ret[0])
        assertEquals(Range(3, 3, 3, 4), ret[1])
        assertEquals(Range(4, 3, 4, 4), ret[2])
    }

    @Test
    fun `search searching from the middle`() {
        val pieceTree = TestSupport.buildTree(listOf(arrayOf("def", "dbcabc").joinToString("\n")))
        pieceTree.delete(4, 1)
        var ret = pieceTree.findMatchesLineByLine(
            regex = Regex("a"),
            searchRange = Range(2, 3, 2, 6),           
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(1, ret.size)
        assertEquals(Range(2, 3, 2, 4), ret[0])

        pieceTree.delete(4, 1)
        ret = pieceTree.findMatchesLineByLine(
            regex = Regex("a"),
            searchRange = Range(2, 2, 2, 5), 
            limitResultCount = 1000,
            isCancelled = { false }
        )
        assertEquals(1, ret.size)
        assertEquals(Range(2, 2, 2, 3), ret[0])
    }
    
    // #endregion
}
