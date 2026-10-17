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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
