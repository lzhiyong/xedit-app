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

import x.github.module.editor.input.BackspaceRepeat.Step

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackspaceRepeatTest {

    // presses within 100 ms of each other are one hold; words after 500 ms of holding,
    // ten a second by default: one every 100 ms
    private val backspace = BackspaceRepeat(repeatGapMillis = 100L, wordAfterMillis = 500L)

    // what a key held from [start] does at each repeat, every 50 ms, from [from] to
    // [to] ms after it went down
    private fun hold(start: Long, from: Long, to: Long): List<Step> =
        (from..to step 50L).map { backspace.next(start + it) }

    @Test
    fun aSingleTapDeletesACharacter() {
        assertEquals(Step.CHARACTER, backspace.next(1_000L))
    }

    @Test
    fun tapsWithPausesBetweenThemStayCharacters() {
        // one tap every 300 ms, for a long time
        var now = 1_000L
        repeat(20) {
            assertEquals(Step.CHARACTER, backspace.next(now), "tap at $now")
            now += 300L
        }
    }

    @Test
    fun holdingTheKeyGoesFromCharactersToWords() {
        val start = 1_000L
        val beforeWords = hold(start, 0L, 450L)
        assertTrue(beforeWords.all { it == Step.CHARACTER }, "before 500 ms it is characters: $beforeWords")

        // the first word goes at once
        assertEquals(Step.WORD, backspace.next(start + 500L))
    }

    // A repeat every 50 ms, a word every 100 ms: the repeats in between delete nothing,
    // so that words go at a pace that can be followed and stopped.
    @Test
    fun wordsGoAtTheirOwnPaceNotAtEveryRepeat() {
        val start = 1_000L
        hold(start, 0L, 450L)

        val steps = hold(start, 500L, 1_450L)

        assertTrue(Step.CHARACTER !in steps, "no characters once it is words: $steps")
        // at 500, 600, ..., 1400
        assertEquals(10, steps.count { it == Step.WORD }, "words: $steps")
        assertEquals(listOf(Step.WORD, Step.NONE, Step.WORD), steps.take(3))
    }

    @Test
    fun aFasterWordPaceDeletesAWordAtEveryRepeat() {
        // 20 a second: one every 50 ms, as often as the key repeats
        backspace.wordsPerSecond = 20f
        val start = 1_000L
        hold(start, 0L, 450L)

        assertTrue(hold(start, 500L, 1_000L).all { it == Step.WORD })
    }

    @Test
    fun withoutAWordPaceItStaysCharacters() {
        backspace.wordsPerSecond = 0f

        assertTrue(hold(1_000L, 0L, 3_000L).all { it == Step.CHARACTER })
    }

    @Test
    fun lettingGoStartsOverWithCharacters() {
        val start = 1_000L
        hold(start, 0L, 850L)
        assertEquals(Step.WORD, backspace.next(start + 900L))

        // the key is let go and pressed again half a second later
        assertEquals(Step.CHARACTER, backspace.next(start + 1_400L))
        assertEquals(Step.CHARACTER, backspace.next(start + 1_450L))
    }

    @Test
    fun resetEndsTheHold() {
        val start = 1_000L
        hold(start, 0L, 600L)

        backspace.reset()

        assertEquals(Step.CHARACTER, backspace.next(start + 650L), "the very next press is a character again")
    }
}
