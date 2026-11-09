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

import x.github.module.piecetable.common.Range

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchResultsTest {

    // "ab" found three times on line 1 and once on line 3:
    //   line 1: ab_ab_ab     columns 1-3, 4-6, 7-9
    //   line 3: __ab         columns 3-5
    private fun fourMatches() = listOf(
        Range(1, 1, 1, 3), Range(1, 4, 1, 6), Range(1, 7, 1, 9), Range(3, 3, 3, 5)
    )

    private fun resultsOf(matches: List<Range>) = SearchResults().also { it.setMatches(matches) }

    // ranges as "line:column-line:column", so that a failure shows what moved where
    private fun describe(range: Range?) =
        range?.let { "${it.startLine}:${it.startColumn}-${it.endLine}:${it.endColumn}" }

    private fun SearchResults.described() = matches.map { describe(it) }

    // #region owning the matches

    @Test
    fun theMatchesAreCopiedIn() {
        val given = fourMatches().toMutableList()
        val results = resultsOf(given)

        // what the caller does with its list and its ranges afterwards ...
        given[0].startColumn = 99
        given.clear()

        // ... does not reach the results
        assertEquals(listOf("1:1-1:3", "1:4-1:6", "1:7-1:9", "3:3-3:5"), results.described())
    }

    @Test
    fun editsDoNotChangeTheCallersRanges() {
        val given = fourMatches()
        val results = resultsOf(given)

        // "xx" typed at the very start
        results.onTextChanged(Range(1, 1, 1, 1), 1, 3)

        assertEquals("1:1-1:3", describe(given[0]))
        assertEquals("1:3-1:5", results.described()[0])
    }

    @Test
    fun settingNewMatchesForgetsTheCurrentOne() {
        val results = resultsOf(fourMatches())
        results.setCurrent(Range(1, 4, 1, 6))

        results.setMatches(listOf(Range(2, 1, 2, 2)))

        assertNull(results.current)
        assertEquals(listOf("2:1-2:2"), results.described())
    }

    @Test
    fun nullOrEmptyClears() {
        val results = resultsOf(fourMatches())

        results.setMatches(null)

        assertTrue(results.isEmpty)
    }

    // #endregion

    // #region following the text

    @Test
    fun typingInFrontMovesTheRestOfTheLine() {
        val results = resultsOf(fourMatches())

        // "xx" typed between the first and the second match (column 4)
        results.onTextChanged(Range(1, 4, 1, 4), 1, 6)

        assertEquals(
            listOf("1:1-1:3", "1:6-1:8", "1:9-1:11", "3:3-3:5"), results.described(),
            "the first is in front and stays, the two after it move, the other line does not"
        )
    }

    @Test
    fun deletingInFrontMovesTheRestOfTheLineBack() {
        val results = resultsOf(fourMatches())

        // the char between the first and the second match (column 3) deleted
        results.onTextChanged(Range(1, 3, 1, 4), 1, 3)

        assertEquals(listOf("1:1-1:3", "1:3-1:5", "1:6-1:8", "3:3-3:5"), results.described())
    }

    @Test
    fun aNewLineInFrontMovesEverythingBehindIt() {
        val results = resultsOf(fourMatches())

        // enter pressed between the first and the second match (column 4)
        results.onTextChanged(Range(1, 4, 1, 4), 2, 1)

        assertEquals(
            listOf("1:1-1:3", "2:1-2:3", "2:4-2:6", "4:3-4:5"), results.described(),
            "the rest of line 1 is line 2 now and starts at column 1; line 3 is line 4"
        )
    }

    @Test
    fun joiningLinesMovesWhatWasOnTheSecondOne() {
        val results = resultsOf(listOf(Range(1, 1, 1, 3), Range(2, 3, 2, 5), Range(3, 1, 3, 2)))

        // the line break at the end of line 1 (column 6) deleted
        results.onTextChanged(Range(1, 6, 2, 1), 1, 6)

        assertEquals(listOf("1:1-1:3", "1:8-1:10", "2:1-2:2"), results.described())
    }

    @Test
    fun anEditInsideAMatchDropsIt() {
        val results = resultsOf(fourMatches())

        // a char typed in the middle of the second match (column 5)
        results.onTextChanged(Range(1, 5, 1, 5), 1, 6)

        assertEquals(listOf("1:1-1:3", "1:8-1:10", "3:3-3:5"), results.described())
    }

    @Test
    fun aDeletionAcrossSeveralMatchesDropsThemAll() {
        val results = resultsOf(fourMatches())

        // from inside the first match to inside the third
        results.onTextChanged(Range(1, 2, 1, 8), 1, 2)

        assertEquals(listOf("3:3-3:5"), results.described())
    }

    @Test
    fun typingRightAtTheEdgesOfAMatchKeepsIt() {
        val results = resultsOf(listOf(Range(1, 4, 1, 6)))

        // right after it: it stays as it is
        results.onTextChanged(Range(1, 6, 1, 6), 1, 7)
        assertEquals(listOf("1:4-1:6"), results.described())

        // right before it: it moves
        results.onTextChanged(Range(1, 4, 1, 4), 1, 5)
        assertEquals(listOf("1:5-1:7"), results.described())
    }

    @Test
    fun replacingAMatchDropsItAndMovesTheOnesBehind() {
        val results = resultsOf(fourMatches())

        // the second match "ab" replaced by "xyz!"
        results.onTextChanged(Range(1, 4, 1, 6), 1, 8)

        assertEquals(listOf("1:1-1:3", "1:9-1:11", "3:3-3:5"), results.described())
    }

    @Test
    fun anEditBehindTheLastMatchChangesNothing() {
        val results = resultsOf(fourMatches())

        results.onTextChanged(Range(5, 1, 5, 1), 6, 1)

        assertEquals(listOf("1:1-1:3", "1:4-1:6", "1:7-1:9", "3:3-3:5"), results.described())
    }

    @Test
    fun theCurrentMatchFollowsTheTextToo() {
        val results = resultsOf(fourMatches())
        results.setCurrent(Range(1, 7, 1, 9))

        results.onTextChanged(Range(1, 1, 1, 1), 1, 3)
        assertEquals("1:9-1:11", describe(results.current))

        // an edit inside it: it is no longer a match
        results.onTextChanged(Range(1, 10, 1, 10), 1, 11)
        assertNull(results.current)
    }

    // #endregion

    // #region stepping

    @Test
    fun nextIsTheFirstMatchAtOrAfterAPosition() {
        val results = resultsOf(fourMatches())

        assertEquals("1:1-1:3", describe(results.firstFrom(1, 1)))
        assertEquals("1:4-1:6", describe(results.firstFrom(1, 2)))
        assertEquals("1:4-1:6", describe(results.firstFrom(1, 4)))
        assertEquals("3:3-3:5", describe(results.firstFrom(2, 1)))
    }

    @Test
    fun nextAfterTheLastMatchIsTheFirst() {
        val results = resultsOf(fourMatches())

        assertEquals("1:1-1:3", describe(results.firstFrom(3, 4)))
        assertEquals("1:1-1:3", describe(results.firstFrom(9, 1)))
    }

    @Test
    fun previousIsTheLastMatchBeforeAPosition() {
        val results = resultsOf(fourMatches())

        assertEquals("1:7-1:9", describe(results.lastBefore(3, 3)))
        assertEquals("1:4-1:6", describe(results.lastBefore(1, 7)))
        assertEquals("1:1-1:3", describe(results.lastBefore(1, 4)))
    }

    @Test
    fun previousBeforeTheFirstMatchIsTheLast() {
        val results = resultsOf(fourMatches())

        assertEquals("3:3-3:5", describe(results.lastBefore(1, 1)))
    }

    @Test
    fun theIndexOfTheCurrentMatchFollowsEdits() {
        val results = resultsOf(fourMatches())
        assertEquals(-1, results.currentIndex, "no current match")

        results.setCurrent(Range(1, 7, 1, 9))
        assertEquals(2, results.currentIndex)

        // the first match is replaced: it is gone, and the current one is second now
        results.onTextChanged(Range(1, 1, 1, 3), 1, 2)
        assertEquals(1, results.currentIndex)

        // a range that is not one of the matches has no index
        results.setCurrent(Range(2, 1, 2, 2))
        assertEquals(-1, results.currentIndex)
    }

    @Test
    fun steppingWithoutMatchesGivesNothing() {
        val results = SearchResults()

        assertNull(results.firstFrom(1, 1))
        assertNull(results.lastBefore(1, 1))
    }

    // #endregion
}
