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

package x.github.module.editor.input

import x.github.module.piecetable.common.Position

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The moves along the text. (A step by one character within a line goes by
 * graphemes, with android.icu, which a JVM test does not have: that is covered on a
 * device, by EditorViewTest, like the grapheme functions of TextBoundaries.)
 */
class CaretNavigatorTest {

    private fun navigator(vararg lines: String) = CaretNavigator(object : CaretNavigator.Text {
        override fun getLineCount(): Int = lines.size
        override fun getLineLength(lineNumber: Int): Int = lines[lineNumber - 1].length
        override fun getLine(lineNumber: Int): String = lines[lineNumber - 1]
        override fun getLineSegment(lineNumber: Int, startColumn: Int, endColumn: Int): String =
            lines[lineNumber - 1].substring(startColumn - 1, endColumn - 1)
    })

    @Test
    fun leftAndRightGoAcrossLines() {
        val text = navigator("ab", "cd")

        assertEquals(Position(2, 1), text.target(CaretMove.RIGHT, 1, 3), "past the end of a line: the next one")
        assertEquals(Position(1, 3), text.target(CaretMove.LEFT, 2, 1), "before the start: the end of the one above")
        assertEquals(Position(1, 1), text.target(CaretMove.LEFT, 1, 1), "nowhere to go")
        assertEquals(Position(2, 3), text.target(CaretMove.RIGHT, 2, 3), "nowhere to go")
    }

    @Test
    fun wordsAndLineEnds() {
        val text = navigator("    val x = 1")

        assertEquals(Position(1, 8), text.target(CaretMove.WORD_RIGHT, 1, 5))
        assertEquals(Position(1, 5), text.target(CaretMove.WORD_LEFT, 1, 8))
        assertEquals(Position(1, 14), text.target(CaretMove.LINE_END, 1, 1))
    }

    @Test
    fun homeGoesToWhereTheTextStartsThenToTheVeryStart() {
        val text = navigator("    val x")

        assertEquals(Position(1, 5), text.target(CaretMove.LINE_START, 1, 9))
        assertEquals(Position(1, 1), text.target(CaretMove.LINE_START, 1, 5))
    }

    @Test
    fun theStartAndEndOfTheText() {
        val text = navigator("ab", "cde")

        assertEquals(Position(1, 1), text.target(CaretMove.DOCUMENT_START, 2, 2))
        assertEquals(Position(2, 4), text.target(CaretMove.DOCUMENT_END, 1, 1))
    }

    @Test
    fun movesByRowsAreNotItsBusiness() {
        val text = navigator("ab")

        for (move in listOf(CaretMove.UP, CaretMove.DOWN, CaretMove.PAGE_UP, CaretMove.PAGE_DOWN)) {
            assertNull(text.target(move, 1, 1))
            assertTrue(move.isVertical)
        }
        assertTrue(CaretMove.WORD_LEFT.isTowardsStart)
    }
}
