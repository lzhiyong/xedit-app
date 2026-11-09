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

package x.github.module.treesitter

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The parts of the bindings that are plain Kotlin, on the JVM: what the native side hands
 * back (a match's pattern index and whether its predicates were decided natively), points
 * and ranges, query errors, encodings, predicates of a match without captures. Parsing and
 * queries need the native library and a grammar: they are tested on a device.
 */
class TreeSitterTypesTest {

    @Test
    fun aMatchDecodesItsPatternIndexAndTheNativeDecision() {
        val plain = TSQueryMatch(5, emptyList())
        assertEquals(5u, plain.patternIndex)
        assertFalse(plain.predicatesDecided)
        // the top bit: the predicates were all decided natively, and hold
        val decided = TSQueryMatch(5 or Int.MIN_VALUE, emptyList())
        assertEquals(5u, decided.patternIndex)
        assertTrue(decided.predicatesDecided)
        assertEquals(Int.MAX_VALUE.toUInt(), TSQueryMatch(-1, emptyList()).patternIndex)
    }

    @Test
    fun aMatchViewReadsItsRecordInABatch() {
        // two records of forEachMatch: [pattern, n, (capture, start, end) * n]
        val buffer = intArrayOf(3, 1, 7, 10, 20, 4, 2, 0, 30, 40, 1, 40, 44)
        val view = TSQueryMatchView()
        view.of(4, 2, buffer, 7)
        assertEquals(4, view.patternIndex)
        assertEquals(2, view.captureCount)
        assertEquals(0, view.captureIndex(0))
        assertEquals(30, view.startByte(0))
        assertEquals(40, view.endByte(0))
        assertEquals(1, view.captureIndex(1))
        assertEquals(40, view.startByte(1))
        assertEquals(44, view.endByte(1))
        // reused for the next match
        view.of(3, 1, buffer, 2)
        assertEquals(3, view.patternIndex)
        assertEquals(1, view.captureCount)
        assertEquals(7, view.captureIndex(0))
        assertEquals(20, view.endByte(0))
    }

    @Test
    fun aMatchViewReadsTheNodesOfItsRecord() {
        // [pattern, n, (capture, start, end, context 1..3, id low, id high) * n]
        val buffer = intArrayOf(
            5, 2,
            7, 10, 20, 1, 2, 3, 0x89ABCDEF.toInt(), 0x01234567,
            9, 30, 40, 4, 5, 6, -1, -2
        )
        val view = TSQueryMatchView()
        view.of(5, 2, buffer, 2, MATCH_NODE_CAPTURE_INTS)
        assertEquals(7, view.captureIndex(0))
        assertEquals(20, view.endByte(0))
        assertEquals(9, view.captureIndex(1))
        assertEquals(30, view.startByte(1))
        assertEquals(40, view.endByte(1))
        // context[0] of a node is its start byte
        assertContentEquals(intArrayOf(10, 1, 2, 3), view.nodeContext(0))
        assertContentEquals(intArrayOf(30, 4, 5, 6), view.nodeContext(1))
        // the low int is not sign-extended into the high one
        assertEquals(0x0123456789ABCDEFL, view.nodeId(0))
        assertEquals(-2L shl 32 or 0xFFFFFFFFL, view.nodeId(1))
    }

    @Test
    fun aMatchViewFindsACapture() {
        val view = TSQueryMatchView()
        view.of(0, 3, intArrayOf(4, 0, 1, 6, 1, 2, 4, 2, 3), 0)
        assertEquals(0, view.firstOf(4))
        assertEquals(1, view.firstOf(6))
        assertEquals(-1, view.firstOf(5))
        // a capture the query does not have
        assertEquals(-1, view.firstOf(-1))
    }

    @Test
    fun aMatchViewWithoutNodesHasNoNodes() {
        val view = TSQueryMatchView()
        view.of(0, 1, intArrayOf(4, 0, 1), 0)
        assertFailsWith<IllegalStateException> { view.node(0) }
    }

    @Test
    fun symbolNamesAreReadOnce() {
        val reads = ArrayList<Int>()
        val names = SymbolNames(3) { symbol ->
            reads += symbol
            if (symbol == 2) null else "name$symbol"
        }
        assertEquals("name1", names[1])
        // the same String, not read again
        assertSame(names[1], names[1])
        assertEquals(listOf(1), reads)
        assertEquals("name0", names[0])
        assertEquals(listOf(1, 0), reads)
    }

    @Test
    fun symbolNamesOutOfTheTableAreStillRead() {
        val reads = ArrayList<Int>()
        val names = SymbolNames(3) { symbol ->
            reads += symbol
            "ERROR"
        }
        // the symbol of an ERROR node
        assertEquals("ERROR", names[0xFFFF])
        assertEquals("ERROR", names[0xFFFF])
        assertEquals(listOf(0xFFFF, 0xFFFF), reads)
    }

    @Test
    fun aSymbolWithoutANameIsAskedForAgain() {
        var reads = 0
        val names = SymbolNames(3) { reads++; null }
        assertNull(names[2])
        assertNull(names[2])
        assertEquals(2, reads)
    }

    @Test
    fun pointsInOrder() {
        val points = listOf(TSPoint(2u, 0u), TSPoint(0u, 9u), TSPoint(2u, 3u), TSPoint(0u, 1u))
        assertEquals(listOf(TSPoint(0u, 1u), TSPoint(0u, 9u), TSPoint(2u, 0u), TSPoint(2u, 3u)), points.sorted())
        assertTrue(points.all { TSPoint.MIN <= it && it <= TSPoint.MAX })
    }

    @Test
    fun aRangeCannotGoBackwards() {
        val range = TSRange(TSPoint(0u, 0u), TSPoint(1u, 2u), 0u, 10u)
        assertEquals(10u, range.endByte)
        assertFailsWith<IllegalArgumentException> { TSRange(TSPoint(1u, 0u), TSPoint(0u, 0u), 0u, 1u) }
        assertFailsWith<IllegalArgumentException> { TSRange(TSPoint(0u, 0u), TSPoint(0u, 1u), 5u, 1u) }
    }

    @Test
    fun queryErrorsSayWhere() {
        assertEquals("Unexpected EOF", TSQueryError.Syntax(-1, -1).message)
        assertEquals("Invalid syntax at row 2, column 7", TSQueryError.Syntax(2, 7).message)
        assertEquals("Invalid node type at row 1, column 3: idenitfier", TSQueryError.NodeType(1u, 3u, "idenitfier").message)
        assertEquals("Invalid capture name at row 0, column 0: x", TSQueryError.Capture(0u, 0u, "x").message)
        assertTrue(TSQueryError.Predicate(4u, "#eq? takes two arguments").message.contains("row 4"))
    }

    @Test
    fun encodingsAndTheirCharsets() {
        assertEquals(Charsets.UTF_8, TSInputEncoding.UTF_8.charset)
        assertEquals(Charsets.UTF_16LE, TSInputEncoding.UTF_16LE.charset)
        assertEquals(Charsets.UTF_16BE, TSInputEncoding.UTF_16BE.charset)
    }

    @Test
    fun predicatesOfAMatchWithoutTheCapture() {
        // the capture is not in the match: eq? fails, not-eq? holds (nothing to differ)
        val match = TSQueryMatch(0, emptyList())
        assertFalse(TSQueryPredicate.EqString("eq?", "name", "main", isPositive = true, isAny = false)(match))
        assertTrue(TSQueryPredicate.EqString("not-eq?", "name", "main", isPositive = false, isAny = false)(match))
        val predicate = TSQueryPredicate.EqString("eq?", "name", "main", isPositive = true, isAny = false)
        assertEquals(listOf(TSQueryPredicateArgs.Capture("name"), TSQueryPredicateArgs.Literal("main")), predicate.args)
    }
}
