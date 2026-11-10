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

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a chat file of the AI assistant holds (AgentHistory): its two lines, and what comes back. */
class AgentHistoryTest {

    private fun chat(): AgentSession.Snapshot {
        val answer = ChatItem.Assistant().apply {
            text.append("Here is the fix:\n```kotlin\nval a = 1\n```")
            streaming = false
        }
        val call = ChatItem.Tool("edit_file", "src/Main.kt", ToolRisk.WRITE).apply {
            state = ToolState.DONE
            preview = "- val a = 0\n+ val a = 1"
            result = "edited"
            file = File("/p/src/Main.kt")
            viewFile = File("/p/src/Main.kt")
        }
        return AgentSession.Snapshot(
            items = listOf(ChatItem.User("fix the build"), answer, call, ChatItem.Notice("Stopped", isError = false)),
            messages = listOf(
                Message.user("fix the build"),
                Message(
                    Role.ASSISTANT,
                    listOf(
                        Block.Text("Here is the fix:"),
                        Block.ToolUse("call_1", "edit_file", buildJsonObject { put("path", "src/Main.kt") }),
                    ),
                ),
                Message(Role.USER, listOf(Block.ToolResult("call_1", "edited", isError = false))),
            ),
            workspace = File("/p"),
            model = "claude-sonnet-5-5",
        )
    }

    @Test
    fun aChatComesBackAsItWasKept() {
        val kept = chat()
        val entry = AgentHistory.entryFor("chat-1", kept)
        val back = assertNotNull(AgentHistory.decode(AgentHistory.encode(entry, kept)))

        assertEquals(kept.items.size, back.items.size)
        assertEquals("fix the build", (back.items[0] as ChatItem.User).text)
        val answer = back.items[1] as ChatItem.Assistant
        assertEquals((kept.items[1] as ChatItem.Assistant).text.toString(), answer.text.toString())
        // nothing is streaming in any more: it is shown whole
        assertTrue(!answer.streaming)
        val call = back.items[2] as ChatItem.Tool
        assertEquals("edit_file", call.name)
        assertEquals(ToolRisk.WRITE, call.risk)
        assertEquals(ToolState.DONE, call.state)
        assertEquals("edited", call.result)
        assertEquals(File("/p/src/Main.kt"), call.viewFile)

        // what the model is told of the chat: the same, so the next question goes on from here
        assertEquals(kept.messages.size, back.messages.size)
        val use = back.messages[1].toolUses.single()
        assertEquals("call_1", use.id)
        assertEquals(JsonPrimitive("src/Main.kt"), use.input["path"])
        val result = back.messages[2].blocks.single() as Block.ToolResult
        assertEquals("edited", result.content)
        assertEquals(File("/p"), back.workspace)
        assertEquals("claude-sonnet-5-5", back.model)
    }

    @Test
    fun theListLineHoldsWhatTheListShows() {
        val entry = AgentHistory.entryFor("chat-1", chat())
        val line = AgentHistory.encode(entry, chat()).substringBefore('\n')
        val back = assertNotNull(AgentHistory.entryOf(line))
        assertEquals("chat-1", back.id)
        assertEquals("fix the build", back.title)
        // the questions and the answers only: not the tool calls, not the notices
        assertEquals(2, back.messages)
        assertEquals("/p", back.workspace)
        assertEquals("claude-sonnet-5-5", back.model)
        assertEquals(entry.updatedAt, back.updatedAt)
    }

    @Test
    fun aTitleIsTheFirstLineOfTheFirstQuestion() {
        val long = "x".repeat(200)
        val snapshot = AgentSession.Snapshot(
            items = listOf(ChatItem.User("\n\n  $long  \nand more")),
            messages = emptyList(),
            workspace = null,
            model = null,
        )
        val title = AgentHistory.entryFor("chat-2", snapshot).title
        assertTrue(title.length < long.length)
        assertTrue(title.endsWith("…"))
        assertTrue(!title.contains('\n'))
    }

    @Test
    fun aCallStillWaitingIsKeptAsStopped() {
        val snapshot = AgentSession.Snapshot(
            items = listOf(ChatItem.Tool("run_command", "./gradlew", ToolRisk.COMMAND).apply { state = ToolState.WAITING }),
            messages = emptyList(),
            workspace = null,
            model = null,
        )
        val back = assertNotNull(
            AgentHistory.decode(AgentHistory.encode(AgentHistory.entryFor("chat-3", snapshot), snapshot))
        )
        // nobody can answer it once the chat is taken up again
        assertEquals(ToolState.STOPPED, (back.items.single() as ChatItem.Tool).state)
    }

    @Test
    fun aNameAndAPinOutliveTheNextSave() {
        val first = AgentHistory.entryFor("chat-1", chat())
        // the user renamed it and pinned it, and then said something else in it
        val kept = first.copy(name = "the build", pinned = true)
        val again = AgentHistory.entryFor("chat-1", chat(), kept)
        assertEquals("the build", again.name)
        assertTrue(again.pinned)
        // what the list shows is the name he gave it, over the question it began with
        assertEquals("the build", again.shown)
        assertEquals("fix the build", again.title)
        // and a chat never renamed is shown by its first question
        assertEquals("fix the build", first.shown)
    }

    @Test
    fun whatIsCutDownComesBackWithIt() {
        val kept = chat()
        val state = ContextState(resultsCleared = 2, attachmentsCleared = 1, summary = "<conversation_summary>S</conversation_summary>", summarized = 2, thinkingDropped = 3)
        val editor = Block.Context("<editor_state>\nThe user has src/Main.kt open\n</editor_state>")
        val snapshot = AgentSession.Snapshot(
            items = kept.items,
            messages = kept.messages + Message(Role.USER, listOf(Block.Text("next"), editor)),
            workspace = kept.workspace,
            model = kept.model,
            context = state,
        )
        val back = assertNotNull(AgentHistory.decode(AgentHistory.encode(AgentHistory.entryFor("chat-9", snapshot), snapshot)))
        assertEquals(state, back.context)
        // what the editor showed goes with the question it was sent with
        assertEquals(listOf(Block.Text("next"), editor), back.messages.last().blocks)
        // nothing cut down: nothing written of it, and nothing read back
        val plain = AgentHistory.encode(AgentHistory.entryFor("chat-1", kept), kept)
        assertTrue(!plain.contains("\"context\""))
        assertEquals(ContextState(), assertNotNull(AgentHistory.decode(plain)).context)
    }

    @Test
    fun theStatusOfATurnComesBackWithHowItEnded() {
        val kept = chat()
        val done = ChatItem.Progress(started = 1_000L).apply {
            state = ProgressState.DONE
            elapsed = 378_000L
            thinkingStarted()
            addThought("**Reading**\n\nthe build")
            expanded = true
        }
        // the sheet closed while a turn goes on: kept as it is then, stopped
        val running = ChatItem.Progress(started = System.currentTimeMillis() - 5_000L)
        val snapshot = AgentSession.Snapshot(kept.items + done + running, kept.messages, kept.workspace, kept.model)
        val back = assertNotNull(AgentHistory.decode(AgentHistory.encode(AgentHistory.entryFor("chat-3", snapshot), snapshot)))
        val first = back.items[kept.items.size] as ChatItem.Progress
        assertEquals(ProgressState.DONE, first.state)
        assertEquals(1_000L, first.started)
        assertEquals(378_000L, first.elapsedAt(99_999_999L))
        assertEquals("**Reading**\n\nthe build", first.thought.toString())
        // folded again
        assertTrue(!first.expanded)
        val second = back.items.last() as ChatItem.Progress
        assertEquals(ProgressState.STOPPED, second.state)
        assertTrue(second.elapsed >= 5_000L)
        // not counted among the questions and answers
        assertEquals(2, AgentHistory.entryFor("chat-3", snapshot).messages)
    }

    @Test
    fun whatIsCutDownOfMoreThanIsKeptIsNotTakenBack() {
        val kept = chat()
        // (a file edited by hand, or written by a version that kept more)
        val snapshot = AgentSession.Snapshot(kept.items, kept.messages, kept.workspace, kept.model, ContextState(summary = "S", summarized = 9))
        val back = assertNotNull(AgentHistory.decode(AgentHistory.encode(AgentHistory.entryFor("chat-9", snapshot), snapshot)))
        assertEquals(ContextState(), back.context)
    }

    @Test
    fun whatIsNotAChatFileIsNotRead() {
        assertNull(AgentHistory.decode(""))
        assertNull(AgentHistory.decode("not json\n{}"))
        assertNull(AgentHistory.entryOf("not json"))
    }
}
