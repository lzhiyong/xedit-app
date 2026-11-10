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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What of a conversation the model is sent, and how it is cut down (AgentContext). */
class AgentContextTest {

    private fun big(length: Int = 5_000) = "x".repeat(length)

    private fun call(id: String, input: JsonObject = buildJsonObject { put("path", "a.kt") }) =
        Message(Role.ASSISTANT, listOf(Block.ToolUse(id, "read_file", input)))

    private fun result(id: String, content: String = big()) = Message(Role.USER, listOf(Block.ToolResult(id, content)))

    // a question, then [rounds] calls of a tool, each returning a large result
    private fun working(rounds: Int): List<Message> =
        listOf(Message.user("fix it")) + (1..rounds).flatMap { listOf(call("t$it"), result("t$it")) }

    private fun textFile(name: String, path: String) = AgentAttachment(
        AgentAttachment.Kind.TEXT, name, 9_000, "text/plain", path,
        text = "<attachment name=\"$name\" path=\"$path\">\n${big(9_000)}\n</attachment>",
    )

    private fun isLeftOut(block: Block) = block is Block.ToolResult && block.content.startsWith("[Left out to save context")

    // #region the view

    @Test
    fun nothingCutDownIsTheHistoryItself() {
        val history = working(3)
        val view = AgentContext.view(history, ContextState())
        assertEquals(history.size, view.size)
        history.indices.forEach { assertSame(history[it], view[it]) }
    }

    @Test
    fun largeResultsAndInputsAreLeftOutSmallOnesKept() {
        val message = Message(Role.USER, listOf(Block.ToolResult("1", big()), Block.ToolResult("2", "short")))
        val cleared = AgentContext.cleared(message, results = true, attachments = false)
        val (first, second) = cleared.blocks.map { it as Block.ToolResult }
        assertTrue(isLeftOut(first))
        assertTrue(first.content.contains("5000 characters"))
        assertEquals("1", first.toolUseId)
        assertEquals("short", second.content)
        // nothing large in it: the message itself (what is sent stays the same)
        val small = Message(Role.USER, listOf(Block.ToolResult("2", "short")))
        assertSame(small, AgentContext.cleared(small, results = true, attachments = true))

        val write = Block.ToolUse("w", "write_file", buildJsonObject {
            put("path", "a.kt")
            put("content", big())
        })
        val input = AgentContext.cleared(Message(Role.ASSISTANT, listOf(write)), results = true, attachments = false).toolUses.single().input
        assertEquals(JsonPrimitive("a.kt"), input["path"])
        assertTrue(input["content"]!!.jsonPrimitive.content.startsWith("[left out to save context"))
        // in the order it had
        assertEquals(listOf("path", "content"), input.keys.toList())
    }

    @Test
    fun theSameStateGivesTheSameView() {
        val history = working(6)
        val state = AgentContext.clearing(history, ContextState(), Int.MAX_VALUE)
        assertEquals(AgentContext.view(history, state), AgentContext.view(history, state))
    }

    @Test
    fun aSummaryStandsForWhatCameBefore() {
        val history = listOf(
            Message.user("q1"),
            call("t1"),
            // a question after a call stopped: its result, then the question
            Message(Role.USER, listOf(Block.ToolResult("t1", "r"), Block.Text("q2"))),
            Message(Role.ASSISTANT, listOf(Block.Text("a2"))),
        )
        // in the first message left, the user's: before what it says, without the result of a call it no longer has
        val merged = AgentContext.view(history, ContextState(summary = "S", summarized = 2))
        assertEquals(listOf(Message(Role.USER, listOf(Block.Context("S"), Block.Text("q2"))), history[3]), merged)
        // all of it: the summary alone
        assertEquals(listOf(Message(Role.USER, listOf(Block.Context("S")))), AgentContext.view(history, ContextState(summary = "S", summarized = 4)))
        // the model's message first: the summary before it, as the user's
        val before = AgentContext.view(history, ContextState(summary = "S", summarized = 1))
        assertEquals(Message(Role.USER, listOf(Block.Context("S"))), before[0])
        assertSame(history[1], before[1])
        assertEquals(4, before.size)
    }

    @Test
    fun aStateForAHistoryCutBack() {
        val state = ContextState(resultsCleared = 5, attachmentsCleared = 3, summary = "S", summarized = 4)
        assertEquals(ContextState(4, 3, "S", 4), state.within(4))
        // the summary tells of what is gone: dropped
        assertEquals(ContextState(3, 3, null, 0), state.within(3))
    }

    // #endregion

    // #region leaving out

    @Test
    fun theThinkingFromBeforeACutIsNotHandedBack() {
        val thought = Message(Role.ASSISTANT, listOf(Block.Thinking("", "s1"), Block.ToolUse("t1", "read_file", JsonObject(emptyMap()))))
        val later = Message(Role.ASSISTANT, listOf(Block.RedactedThinking("r2"), Block.ToolUse("t2", "read_file", JsonObject(emptyMap()))))
        val history = listOf(Message.user("fix it"), thought, result("t1", "small"), later, result("t2", "small"))
        val view = AgentContext.view(history, ContextState(thinkingDropped = 3))
        // before the mark: the call without its thinking
        assertEquals(listOf<Block>(Block.ToolUse("t1", "read_file", JsonObject(emptyMap()))), view[1].blocks)
        // after it (thought once what is sent was cut down): as it came
        assertSame(later, view[3])
        // the messages with no thinking in them are themselves
        assertSame(history[2], view[2])
        // and so for a history cut back before the mark
        assertEquals(2, ContextState(thinkingDropped = 3).within(2).thinkingDropped)
        assertEquals(3, ContextState(thinkingDropped = 3).within(5).thinkingDropped)
    }

    @Test
    fun theNewestResultsAreKept() {
        // 0: the question; 2, 4, 6, 8, 10, 12: the results
        val history = working(6)
        assertEquals(6, AgentContext.resultsKeptFrom(history))
        val state = AgentContext.clearing(history, ContextState(), Int.MAX_VALUE)
        assertEquals(6, state.resultsCleared)
        val view = AgentContext.view(history, state)
        assertTrue(isLeftOut(view[2].blocks.single()))
        assertTrue(isLeftOut(view[4].blocks.single()))
        for (index in listOf(6, 8, 10, 12)) assertSame(history[index], view[index])
    }

    @Test
    fun noMoreIsLeftOutThanNeeded() {
        // the first large result is enough
        assertEquals(3, AgentContext.clearing(working(6), ContextState(), 1).resultsCleared)
        // nothing needed, or nothing that can be: the state itself
        val state = ContextState()
        assertSame(state, AgentContext.clearing(working(6), state, 0))
        assertSame(state, AgentContext.clearing(working(3), state, Int.MAX_VALUE))
    }

    @Test
    fun theFilesOfTheQuestionBeingAnsweredAreKept() {
        val old = textFile("old.txt", "/att/1/old.txt")
        val image = AgentAttachment(AgentAttachment.Kind.IMAGE, "shot.png", 200_000, "image/png", "/att/2/shot.png")
        val now = textFile("now.txt", "/att/3/now.txt")
        val history = listOf(
            Message(Role.USER, listOf(Block.Attachment(old), Block.Attachment(image), Block.Text("read these"))),
            Message(Role.ASSISTANT, listOf(Block.Text("done"))),
            Message(Role.USER, listOf(Block.Attachment(now), Block.Text("and this"))),
        )
        val state = AgentContext.clearing(history, ContextState(), Int.MAX_VALUE)
        assertEquals(2, state.attachmentsCleared)
        val view = AgentContext.view(history, state)
        val (oldLeft, imageLeft) = view[0].blocks.take(2).map { assertIs<Block.Context>(it).text }
        assertTrue(oldLeft.contains("/att/1/old.txt") && oldLeft.contains("read_file"))
        assertTrue(imageLeft.contains("An image"))
        assertEquals(Block.Text("read these"), view[0].blocks[2])
        assertSame(history[2], view[2])
    }

    // #endregion

    // #region how large

    @Test
    fun tokensByScript() {
        assertEquals(2, AgentContext.tokens("abcdef"))
        assertEquals(2, AgentContext.tokens("中文"))
        assertEquals(1, AgentContext.tokens("ab中"))
    }

    @Test
    fun aBudgetLeavesRoomForTheAnswer() {
        val budget = ContextBudget(200_000, 8_192)
        assertEquals(200_000 - 8_192 - 4_000, budget.usable)
        assertTrue(budget.clearTo < budget.clearAt && budget.clearAt < budget.compactAt && budget.compactAt < budget.usable)
        assertEquals(8_192, budget.summaryTokens)
        // a small window: an answer as long as it is does not take all of it
        val small = ContextBudget(8_000, 8_192)
        assertEquals(8_000 - 2_000 - 160, small.usable)
        assertEquals(2_000, small.summaryTokens)
    }

    // #endregion

    // #region summarizing

    private val budget = ContextBudget(200_000, 8_192)

    // 0: q1; 1: a1; 2: q2 (the turn going on); 3: a call; 4: its result
    private fun twoTurns(resultLength: Int) = listOf(
        Message.user("q1"),
        Message(Role.ASSISTANT, listOf(Block.Text("a1"))),
        Message.user("q2"),
        call("t1"),
        result("t1", big(resultLength)),
    )

    @Test
    fun whatCameBeforeTheTurnIsSummarized() {
        val plan = assertNotNull(AgentContext.compaction(twoTurns(100), ContextState(), budget, 1.0, 0, 1_000_000))
        assertEquals(2, plan.cut)
        assertTrue(!plan.everything)
        // what is sent of q1 and a1, and a question of its own after them; none of their thinking
        assertEquals(3, plan.messages.size)
        assertEquals(2, plan.turnStart)
        assertEquals(AgentContext.summaryRequest(everything = false), plan.messages.last().text)
    }

    @Test
    fun aTurnTooLargeIsSummarizedWithTheRest() {
        val history = twoTurns(400_000)
        val plan = assertNotNull(AgentContext.compaction(history, ContextState(), budget, 1.0, 0, 1_000_000))
        assertEquals(history.size, plan.cut)
        assertTrue(plan.everything)
        // the question after the results, in their message; the thinking of the turn handed back (from q2)
        assertEquals(history.size, plan.messages.size)
        assertEquals(listOf(Block.ToolResult::class, Block.Text::class), plan.messages.last().blocks.map { it::class })
        assertEquals(2, plan.turnStart)
        // a request too large for the room: what is large in it left out
        val small = assertNotNull(AgentContext.compaction(history, ContextState(), budget, 1.0, 0, 50_000))
        assertTrue(isLeftOut(small.messages.last().blocks.first()))
    }

    @Test
    fun nothingLeftToSummarize() {
        val history = twoTurns(100)
        assertNull(AgentContext.compaction(history, ContextState(summary = "S", summarized = history.size), budget, 1.0, 0, 1_000_000))
    }

    @Test
    fun whatTheModelIsToldOfTheSummary() {
        val text = AgentContext.summaryText("  done: a, b  ", everything = true)
        assertTrue(text.startsWith("<conversation_summary>"))
        assertTrue(text.contains("done: a, b\n</conversation_summary>"))
        assertTrue(text.endsWith("Go on with the task from where the summary leaves off."))
        assertTrue(!AgentContext.summaryText("s", everything = false).contains("Go on"))
        assertTrue(AgentContext.droppedText("make it faster").contains("make it faster"))
    }

    // #endregion

    // #region too long for the model

    private fun overflow(message: String, status: Int? = 400) = AgentContext.overflowOf(LlmException(message, status))

    @Test
    fun whatProvidersSayOfARequestTooLong() {
        assertEquals(
            AgentContext.Overflow(215_000, 200_000),
            overflow("HTTP 400: prompt is too long: 215000 tokens > 200000 maximum"),
        )
        assertEquals(
            AgentContext.Overflow(198_000, 200_000),
            overflow("HTTP 400: input length and `max_tokens` exceed context limit: 198000 + 8192 > 200000, decrease input length or `max_tokens` and try again"),
        )
        assertEquals(
            AgentContext.Overflow(130_512, 128_000),
            overflow("HTTP 400: This model's maximum context length is 128000 tokens. However, your messages resulted in 130512 tokens. Please reduce the length of the messages."),
        )
        assertEquals(
            AgentContext.Overflow(70_000, 65_536),
            overflow("HTTP 400: This model's maximum context length is 65536 tokens. However, you requested 70000 tokens (60000 in the messages, 10000 in the completion)."),
        )
        assertEquals(AgentContext.Overflow(null, 131_072), overflow("HTTP 400: Invalid request: Your request exceeded model token limit: 131072"))
        // said, but not how much
        assertEquals(AgentContext.Overflow(null, null), overflow("HTTP 400: context_length_exceeded"))
        assertEquals(AgentContext.Overflow(null, null), overflow("HTTP 400: Prompt exceeds max length"))
        // in the stream (no status)
        assertNotNull(overflow("prompt is too long: 215000 tokens > 200000 maximum", null))
    }

    @Test
    fun whatIsNotARequestTooLong() {
        assertNull(overflow("HTTP 429: This request would exceed your organization's rate limit of 400,000 input tokens per minute", 429))
        assertNull(overflow("HTTP 400: max_tokens: 200000 > 128000, which is the maximum allowed number of output tokens for claude-opus-5-5"))
        assertNull(overflow("HTTP 401: invalid x-api-key", 401))
        assertNull(overflow("HTTP 400: messages.3.content.0: unexpected `tool_use_id` found in `tool_result` blocks"))
    }

    // #endregion
}
