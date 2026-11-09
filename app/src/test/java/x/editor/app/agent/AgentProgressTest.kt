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

package x.editor.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentProgressTest {

    @Test
    fun theLastHeadingIsWhatItIsDoing() {
        val thinking = "**Reading the build**\n\nThe build file says...\n\n**Fixing the import**\n\nThe import is wrong, so"
        assertEquals("Fixing the import", AgentProgress.headline(thinking))
    }

    @Test
    fun hashHeadingsAndUnderscoresCountToo() {
        assertEquals("Planning", AgentProgress.headline("## Planning ##\n\nfirst this"))
        assertEquals("Checking", AgentProgress.headline("__Checking__:\nmore"))
    }

    @Test
    fun withoutAHeadingTheLastParagraph() {
        val note = "Confirmed the retry path never refreshes the token.\nEditing `auth.py` to add the call."
        assertEquals(
            "Confirmed the retry path never refreshes the token. Editing auth.py to add the call.",
            AgentProgress.headline("Earlier thoughts.\n\n$note"),
        )
    }

    @Test
    fun boldInsideASentenceIsNoHeading() {
        assertEquals("Use foo and bar here", AgentProgress.headline("**Use** foo and **bar** here"))
    }

    @Test
    fun aHeadingHalfStreamedIsShownAsItIs() {
        assertEquals("Analyz", AgentProgress.headline("**Analyz"))
    }

    @Test
    fun listMarksAndColonsGo() {
        assertEquals("check the tests", AgentProgress.headline("- check the tests:"))
        assertEquals("正在检查", AgentProgress.headline("**正在检查**："))
    }

    @Test
    fun nothingSaysNothing() {
        assertNull(AgentProgress.headline(""))
        assertNull(AgentProgress.headline("  \n\n "))
        assertNull(AgentProgress.headline("****"))
    }

    @Test
    fun aLongHeadlineIsCutShort() {
        val headline = AgentProgress.headline("x".repeat(500))!!
        assertEquals(AgentProgress.MAX_HEADLINE, headline.length)
        assertTrue(headline.endsWith("…"))
    }

    @Test
    fun elapsedText() {
        assertEquals("0s", AgentProgress.elapsedText(-5))
        assertEquals("0s", AgentProgress.elapsedText(999))
        assertEquals("42s", AgentProgress.elapsedText(42_400))
        assertEquals("1m 05s", AgentProgress.elapsedText(65_000))
        assertEquals("6m 18s", AgentProgress.elapsedText(378_000))
        assertEquals("59m 59s", AgentProgress.elapsedText(3_599_999))
        assertEquals("1h 00m", AgentProgress.elapsedText(3_600_000))
        assertEquals("2h 05m", AgentProgress.elapsedText(7_500_000))
    }

    @Test
    fun untilNextSecond() {
        assertEquals(1000L, AgentProgress.untilNextSecond(0))
        assertEquals(1L, AgentProgress.untilNextSecond(1_999))
        assertEquals(750L, AgentProgress.untilNextSecond(5_250))
    }

    @Test
    fun thePartsOfTheThinkingAreABlankLineApart() {
        val progress = ChatItem.Progress(started = 0L)
        progress.thinkingStarted()
        progress.addThought("**First**")
        progress.addThought("\n\nmore")
        assertEquals("**First**\n\nmore", progress.lastThought)
        // a part that says nothing (its thinking omitted) sets nothing apart
        progress.thinkingStarted()
        assertEquals("", progress.lastThought)
        progress.thinkingStarted()
        progress.addThought("Second")
        assertEquals("Second", progress.lastThought)
        assertEquals("**First**\n\nmore\n\nSecond", progress.thought.toString())
    }

    @Test
    fun theClockStopsWhenTheTurnIsOver() {
        val progress = ChatItem.Progress(started = 1_000L)
        assertEquals(4_000L, progress.elapsedAt(5_000L))
        progress.state = ProgressState.DONE
        progress.elapsed = 2_000L
        assertEquals(2_000L, progress.elapsedAt(99_000L))
    }
}
