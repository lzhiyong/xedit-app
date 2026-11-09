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

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** A question taken back (retry, edit), and the time on a message of the user. */
class AgentMessagesTest {

    private val answer = Message(Role.ASSISTANT, listOf(Block.Text("answer")))
    private val results = Message(Role.USER, listOf(Block.ToolResult("1", "done", false)))

    @Test
    fun everythingFromTheQuestionOnGoes() {
        val first = Message.user("first")
        val second = Message.user("second")
        val messages = listOf(first, answer, results, answer, second, answer)
        assertEquals(emptyList(), messagesBefore(messages, 0))
        assertEquals(listOf(first, answer, results, answer), messagesBefore(messages, 1))
    }

    @Test
    fun whatToolsReturnedIsNotAQuestion() {
        val messages = listOf(Message.user("first"), answer, results, Message.user("second"))
        assertEquals(messages.subList(0, 3), messagesBefore(messages, 1))
    }

    @Test
    fun aQuestionSharingAMessageLeavesWhatCameBeforeIt() {
        val merged = Message(Role.USER, listOf(Block.Text("first"), Block.Text("second")))
        assertEquals(listOf(Message.user("first")), messagesBefore(listOf(merged), 1))
        assertEquals(emptyList(), messagesBefore(listOf(merged), 0))
    }

    @Test
    fun aQuestionAfterAStoppedToolKeepsItsResult() {
        val merged = Message(Role.USER, results.blocks + Block.Text("second"))
        val messages = listOf(Message.user("first"), answer, merged)
        assertEquals(listOf(Message.user("first"), answer, results), messagesBefore(messages, 1))
    }

    @Test
    fun whatTheEditorShowedGoesWithItsQuestion() {
        val first = Block.Context("<editor_state>a</editor_state>")
        val merged = Message(Role.USER, listOf(Block.Text("first"), first, Block.Text("second"), Block.Context("<editor_state>b</editor_state>")))
        // not a question of its own; the first's stays with it when the second is taken back
        assertEquals(listOf(Message(Role.USER, listOf(Block.Text("first"), first))), messagesBefore(listOf(merged), 1))
        assertEquals(emptyList(), messagesBefore(listOf(merged), 0))
        assertEquals(listOf(merged), messagesBefore(listOf(merged), 2))
    }

    @Test
    fun aQuestionNotFoundLeavesAllOfIt() {
        val messages = listOf(Message.user("first"), answer)
        assertEquals(messages, messagesBefore(messages, 3))
    }

    @Test
    fun theTimeSaysHowLongAgo() {
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = at(2026, 11, 6, 10, 39)
        fun ago(time: Long) = sentAgo(time, now, ZoneOffset.UTC)
        assertEquals(SentAgo.JustNow, ago(now - 59_000))
        assertEquals(SentAgo.JustNow, ago(now + 5_000))
        assertEquals(SentAgo.Minutes(1), ago(now - 60_000))
        assertEquals(SentAgo.Minutes(10), ago(at(2026, 11, 6, 10, 29)))
        assertEquals(SentAgo.Minutes(59), ago(at(2026, 11, 6, 9, 40)))
        assertEquals(SentAgo.Hours(1), ago(at(2026, 11, 6, 9, 39)))
        assertEquals(SentAgo.Hours(23), ago(at(2026, 11, 5, 10, 40)))
        assertEquals(SentAgo.Days(1), ago(at(2026, 11, 5, 10, 39)))
        assertEquals(SentAgo.Days(6), ago(at(2026, 10, 30, 10, 40)))
        assertEquals(SentAgo.Date("10-30"), ago(at(2026, 10, 30, 10, 39)))
        assertEquals(SentAgo.Date("2025-12-31"), ago(at(2025, 12, 31, 8, 0)))
    }
}
