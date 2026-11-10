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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProvidersTest {

    private fun event(data: String, type: String? = null) = SseParser.Event(type, data)

    // #region Anthropic

    @Test
    fun anthropicTextAndAToolCallInPieces() {
        val seen = ArrayList<LlmEvent>()
        val assembler = AnthropicProvider.Assembler { seen += it }
        listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":12}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"look."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\": \"src/"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"a.c\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":30}}""",
            """{"type":"message_stop"}""",
        ).forEach { assembler.feed(event(it)) }
        val reply = assembler.reply()
        assertEquals(StopReason.TOOL_USE, reply.stopReason)
        assertEquals(Usage(12, 30), reply.usage)
        assertEquals("Let me look.", reply.message.text)
        val use = reply.message.toolUses.single()
        assertEquals("toolu_1", use.id)
        assertEquals("read_file", use.name)
        assertEquals("src/a.c", use.input["path"]?.jsonPrimitive?.content)
        assertEquals(listOf(LlmEvent.TextDelta("Let me "), LlmEvent.TextDelta("look."), LlmEvent.ToolStarted("read_file")), seen)
    }

    @Test
    fun anthropicSecondTextBlockStartsOnALineOfItsOwn() {
        val seen = ArrayList<LlmEvent>()
        val assembler = AnthropicProvider.Assembler { seen += it }
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Here it is."}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"```c\nint x;\n```"}}""",
            """{"type":"content_block_stop","index":1}""",
        ).forEach { assembler.feed(event(it)) }
        val shown = seen.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Here it is.\n\n```c\nint x;\n```", shown)
        // what goes back to the model keeps its blocks as they were
        assertEquals(listOf("Here it is.", "```c\nint x;\n```"), reply(assembler))
    }

    private fun reply(assembler: AnthropicProvider.Assembler): List<String> =
        assembler.reply().message.blocks.filterIsInstance<Block.Text>().map { it.text }

    @Test
    fun anthropicErrorEventFailsTheRequest() {
        val assembler = AnthropicProvider.Assembler {}
        val e = assertFailsWith<LlmException> {
            assembler.feed(event("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        }
        assertEquals("Overloaded", e.message)
    }

    @Test
    fun anthropicToolInputCutOffBecomesEmpty() {
        val assembler = AnthropicProvider.Assembler {}
        assembler.feed(event("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"write_file"}}"""))
        assembler.feed(event("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\": \"a"}}"""))
        assembler.feed(event("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""))
        val reply = assembler.reply()
        assertEquals(StopReason.MAX_TOKENS, reply.stopReason)
        assertEquals(JsonObject(emptyMap()), reply.message.toolUses.single().input)
    }

    @Test
    fun anthropicBodyHasToolsAndToolResults() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "claude-test")
        val request = LlmRequest(
            system = "sys",
            messages = listOf(
                Message.user("hi"),
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "read_file", buildJsonObject { put("path", "a") }))),
                Message(Role.USER, listOf(Block.ToolResult("t1", "no such file", isError = true))),
            ),
            tools = listOf(ToolSpec("read_file", "Read", schema(listOf("path"), Triple("path", "string", "p")))),
            maxTokens = 100,
        )
        val body = provider.bodyOf(request)
        assertEquals("claude-test", body["model"]?.jsonPrimitive?.content)
        // a block of its own, the end of what every request begins with (the tools before it)
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("sys", system["text"]?.jsonPrimitive?.content)
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", body["tools"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)
        val result = body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", result["type"]?.jsonPrimitive?.content)
        assertEquals("t1", result["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), result["is_error"])
    }

    @Test
    fun anthropicDefaultThinkingSendsNothing() {
        val body = AnthropicProvider("https://api.anthropic.com", "k", "m").bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100))
        assertEquals(null, body["thinking"])
        assertEquals(null, body["output_config"])
    }

    @Test
    fun anthropicThinkingLevelIsTheEffort() {
        val body = AnthropicProvider("https://api.anthropic.com", "k", "m", ThinkingLevel.HIGH)
            .bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100))
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicThinkingIsGatheredWithItsSignature() {
        val seen = ArrayList<LlmEvent>()
        val assembler = AnthropicProvider.Assembler { seen += it }
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me see"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig=="}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"enc"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Done."}}""",
            """{"type":"content_block_stop","index":2}""",
        ).forEach { assembler.feed(event(it)) }
        val blocks = assembler.reply().message.blocks
        assertEquals(listOf(Block.Thinking("Let me see", "sig=="), Block.RedactedThinking("enc"), Block.Text("Done.")), blocks)
        // the thinking is shown as thinking, not as text
        assertEquals(
            listOf(
                LlmEvent.ThinkingStarted,
                LlmEvent.ThinkingDelta("Let me see"),
                LlmEvent.ThinkingStarted,
                LlmEvent.TextDelta("Done."),
            ),
            seen,
        )
    }

    @Test
    fun anthropicEmptyThinkingIsStartedButSaysNothing() {
        val seen = ArrayList<LlmEvent>()
        val assembler = AnthropicProvider.Assembler { seen += it }
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"s"}}""",
            """{"type":"content_block_stop","index":0}""",
        ).forEach { assembler.feed(event(it)) }
        assertEquals(listOf<LlmEvent>(LlmEvent.ThinkingStarted), seen)
        assertEquals(listOf<Block>(Block.Thinking("", "s")), assembler.reply().message.blocks)
    }

    @Test
    fun anthropicModelsThatThinkByDefault() {
        for (model in listOf(
            "claude-opus-5-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-fable-5-1",
            "claude-mythos-5-1", "claude-mythos-preview", "anthropic/claude-opus-5", "anthropic.claude-sonnet-5-5-v1:0",
        )) assertTrue(AnthropicProvider.thinksByDefault(model), model)
        for (model in listOf(
            "claude-opus-4-8", "claude-sonnet-4-6", "claude-sonnet-4-5-20250929", "claude-3-5-sonnet-latest",
            "claude-haiku-4-5", "m", "glm-5",
        )) assertTrue(!AnthropicProvider.thinksByDefault(model), model)
    }

    @Test
    fun anthropicAsksForTheThinkingOfAModelThatThinksByDefault() {
        val body = AnthropicProvider("https://api.anthropic.com", "k", "claude-opus-5-5")
            .bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100))
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]?.jsonPrimitive?.content)
        assertEquals("summarized", thinking["display"]?.jsonPrimitive?.content)
        // the model's own effort
        assertEquals(null, body["output_config"])
    }

    @Test
    fun anthropicAsksForTheThinkingWithALevel() {
        val body = AnthropicProvider("https://api.anthropic.com", "k", "claude-opus-4-8", ThinkingLevel.HIGH)
            .bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100))
        assertEquals("summarized", body["thinking"]!!.jsonObject["display"]?.jsonPrimitive?.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicWithoutDisplay() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "claude-opus-5-5", ThinkingLevel.HIGH)
        val withLevel = provider.bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100), display = false)
        assertEquals(null, withLevel["thinking"]!!.jsonObject["display"])
        // by default, and the summary not wanted: nothing at all (adaptive is the model's own)
        val plain = AnthropicProvider("https://api.anthropic.com", "k", "claude-opus-5-5")
            .bodyOf(LlmRequest("", listOf(Message.user("hi")), emptyList(), 100), display = false)
        assertEquals(null, plain["thinking"])
    }

    @Test
    fun anthropicThinkingGoesBackOnlyWithinTheTurn() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "m")
        val request = LlmRequest(
            system = "",
            messages = listOf(
                Message.user("first"),
                Message(Role.ASSISTANT, listOf(Block.Thinking("old", "s0"), Block.Text("answer"))),
                Message.user("second"),
                Message(Role.ASSISTANT, listOf(Block.Thinking("", "s1"), Block.RedactedThinking("r1"), Block.ToolUse("t1", "read_file", JsonObject(emptyMap())))),
                Message(Role.USER, listOf(Block.ToolResult("t1", "text"))),
            ),
            tools = emptyList(),
            maxTokens = 100,
        )
        val messages = provider.bodyOf(request)["messages"]!!.jsonArray
        fun types(i: Int) = messages[i].jsonObject["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        // an earlier turn's: left out
        assertEquals(listOf("text"), types(1))
        // this turn's: as it came, in its order
        assertEquals(listOf("thinking", "redacted_thinking", "tool_use"), types(3))
        val thinking = messages[3].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("s1", thinking["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicThinkingWithoutSignatureIsLeftOutWhole() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "m")
        val request = LlmRequest(
            system = "",
            messages = listOf(
                Message.user("q"),
                Message(Role.ASSISTANT, listOf(Block.Thinking("a", "s"), Block.Thinking("b", ""), Block.ToolUse("t1", "x", JsonObject(emptyMap())))),
                Message(Role.USER, listOf(Block.ToolResult("t1", "r"))),
            ),
            tools = emptyList(),
            maxTokens = 100,
        )
        val content = provider.bodyOf(request)["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    // the indices of the messages of [body] whose content has a block marked for the cache,
    // and the type of the block marked in each
    private fun marked(body: JsonObject): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        body["messages"]!!.jsonArray.forEachIndexed { index, message ->
            message.jsonObject["content"]!!.jsonArray.forEach { block ->
                if (block.jsonObject["cache_control"] != null) out[index] = block.jsonObject["type"]!!.jsonPrimitive.content
            }
        }
        return out
    }

    @Test
    fun anthropicMarksTheLastTwoQuestionsAndTheLastMessage() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "m")
        val use = Block.ToolUse("t1", "read_file", JsonObject(emptyMap()))
        val request = LlmRequest(
            system = "sys",
            messages = listOf(
                Message.user("first"),
                Message(Role.ASSISTANT, listOf(Block.Text("one"))),
                Message.user("second"),
                Message(Role.ASSISTANT, listOf(Block.Text("two"))),
                Message(Role.USER, listOf(Block.Text("third"), Block.Context("<editor_state>x</editor_state>"))),
                Message(Role.ASSISTANT, listOf(Block.Thinking("t", "s"), use)),
                Message(Role.USER, listOf(Block.ToolResult("t1", "text"))),
            ),
            tools = listOf(ToolSpec("read_file", "Read", schema(listOf("path"), Triple("path", "string", "p")))),
            maxTokens = 100,
        )
        val body = provider.bodyOf(request)
        // the questions second and third (on its last block: what the editor showed), the last message
        assertEquals(mapOf(2 to "text", 4 to "text", 6 to "tool_result"), marked(body))
        val third = body["messages"]!!.jsonArray[4].jsonObject["content"]!!.jsonArray
        assertEquals("<editor_state>x</editor_state>", third[1].jsonObject["text"]?.jsonPrimitive?.content)
        // with the system prompt's: the 4 the API takes; the tools are not marked (the system prompt is)
        assertEquals(null, body["tools"]!!.jsonArray.single().jsonObject["cache_control"])
    }

    @Test
    fun anthropicNeverMarksThinking() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "m")
        val request = LlmRequest(
            system = "",
            messages = listOf(Message.user("q"), Message(Role.ASSISTANT, listOf(Block.Text("a"), Block.Thinking("t", "s")))),
            tools = emptyList(),
            maxTokens = 100,
        )
        // (an assistant's message last: only to see where its mark goes)
        assertEquals(mapOf(0 to "text", 1 to "text"), marked(provider.bodyOf(request)))
    }

    @Test
    fun anthropicWithoutSystemPromptMarksTheLastTool() {
        val tools = listOf(
            ToolSpec("a", "A", schema(emptyList())),
            ToolSpec("b", "B", schema(emptyList())),
        )
        val body = AnthropicProvider("https://api.anthropic.com", "k", "m").bodyOf(LlmRequest("", listOf(Message.user("q")), tools, 100))
        val sent = body["tools"]!!.jsonArray
        assertEquals(null, sent[0].jsonObject["cache_control"])
        assertTrue(sent[1].jsonObject["cache_control"] != null)
        assertEquals(null, body["system"])
    }

    @Test
    fun anthropicWithoutCacheMarksNothing() {
        val body = AnthropicProvider("https://api.anthropic.com", "k", "m", cache = false)
            .bodyOf(LlmRequest("sys", listOf(Message.user("q")), emptyList(), 100))
        assertEquals(emptyMap(), marked(body))
        assertEquals(null, body["system"]!!.jsonArray.single().jsonObject["cache_control"])
    }

    @Test
    fun anthropicTurnStartGivenKeepsTheThinkingOfTheTurn() {
        val provider = AnthropicProvider("https://api.anthropic.com", "k", "m")
        val request = LlmRequest(
            system = "",
            messages = listOf(
                Message.user("q"),
                Message(Role.ASSISTANT, listOf(Block.Thinking("t", "s1"), Block.ToolUse("t1", "x", JsonObject(emptyMap())))),
                // a question of its own after the results (a summary asked for): the turn still began at "q"
                Message(Role.USER, listOf(Block.ToolResult("t1", "r"), Block.Text("summarize"))),
            ),
            tools = emptyList(),
            maxTokens = 100,
            turnStart = 0,
        )
        val content = provider.bodyOf(request)["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("thinking", "tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun anthropicUsageCountsWhatTheCacheGaveAndTook() {
        val assembler = AnthropicProvider.Assembler {}
        assembler.feed(
            event(
                """{"type":"message_start","message":{"usage":{"input_tokens":50,"cache_read_input_tokens":100000,"cache_creation_input_tokens":2000,"output_tokens":1}}}"""
            )
        )
        assembler.feed(event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":300}}"""))
        assertEquals(Usage(102_050, 300, cachedTokens = 100_000, cacheWriteTokens = 2000), assembler.reply().usage)
    }

    @Test
    fun anthropicContextWindowExceededIsCutOff() {
        val assembler = AnthropicProvider.Assembler {}
        assembler.feed(event("""{"type":"message_delta","delta":{"stop_reason":"model_context_window_exceeded"}}"""))
        assertEquals(StopReason.MAX_TOKENS, assembler.reply().stopReason)
    }

    @Test
    fun thinkingAloneSaysNothing() {
        assertEquals(false, Message(Role.ASSISTANT, listOf(Block.Thinking("", "s"))).saysSomething)
        assertEquals(true, Message(Role.ASSISTANT, listOf(Block.Thinking("", "s"), Block.Text("x"))).saysSomething)
    }

    // #endregion

    // #region OpenAI-compatible

    @Test
    fun openAiTextAndToolCallsByIndex() {
        val seen = ArrayList<LlmEvent>()
        val assembler = OpenAiProvider.Assembler { seen += it }
        listOf(
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"list_files","arguments":""}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"src\"}"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"name":"read_file","arguments":"{\"path\":\"b\"}"}}]}}]}""",
            // some servers say "stop" though they called tools
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":7}}""",
            "[DONE]",
        ).forEach { assembler.feed(event(it)) }
        val reply = assembler.reply()
        assertEquals(StopReason.TOOL_USE, reply.stopReason)
        assertEquals(Usage(5, 7), reply.usage)
        assertEquals("Hi", reply.message.text)
        val (first, second) = reply.message.toolUses
        assertEquals("call_a", first.id)
        assertEquals("src", first.input["path"]?.jsonPrimitive?.content)
        assertEquals("read_file", second.name)
        // no id from the server: one of ours
        assertTrue(second.id.startsWith("call_") && second.id.length > 5)
        assertEquals(listOf(LlmEvent.TextDelta("Hi"), LlmEvent.ToolStarted("list_files"), LlmEvent.ToolStarted("read_file")), seen)
    }

    @Test
    fun openAiReasoningIsShownAsThinking() {
        val seen = ArrayList<LlmEvent>()
        val assembler = OpenAiProvider.Assembler { seen += it }
        listOf(
            """{"choices":[{"delta":{"role":"assistant","reasoning_content":"Look at "}}]}""",
            // OpenRouter's name for it; a null one beside it is let be
            """{"choices":[{"delta":{"reasoning_content":null,"reasoning":"the file."}}]}""",
            """{"choices":[{"delta":{"content":"Here."}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"Once more."}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        ).forEach { assembler.feed(event(it)) }
        assertEquals(
            listOf(
                LlmEvent.ThinkingStarted,
                LlmEvent.ThinkingDelta("Look at "),
                LlmEvent.ThinkingDelta("the file."),
                LlmEvent.TextDelta("Here."),
                LlmEvent.ThinkingStarted,
                LlmEvent.ThinkingDelta("Once more."),
            ),
            seen,
        )
        // only shown: not in the reply
        assertEquals(listOf<Block>(Block.Text("Here.")), assembler.reply().message.blocks)
    }

    @Test
    fun openAiLengthIsMaxTokens() {
        val assembler = OpenAiProvider.Assembler {}
        assembler.feed(event("""{"choices":[{"delta":{"content":"abc"},"finish_reason":"length"}]}"""))
        assertEquals(StopReason.MAX_TOKENS, assembler.reply().stopReason)
    }

    @Test
    fun openAiErrorInTheStreamFailsTheRequest() {
        val assembler = OpenAiProvider.Assembler {}
        assertFailsWith<LlmException> { assembler.feed(event("""{"error":{"message":"bad key"}}""")) }
    }

    @Test
    fun openAiBodySplitsToolResultsIntoToolMessages() {
        val provider = OpenAiProvider("https://example.com/v1", "k", "m")
        val request = LlmRequest(
            system = "sys",
            messages = listOf(
                Message.user("hi"),
                Message(Role.ASSISTANT, listOf(Block.Text("ok"), Block.ToolUse("c1", "list_files", JsonObject(emptyMap())))),
                Message(Role.USER, listOf(Block.ToolResult("c1", "a/\nb"), Block.Text("and then?"))),
            ),
            tools = emptyList(),
            maxTokens = 0,
        )
        val body = provider.bodyOf(request)
        assertEquals(null, body["max_tokens"])
        assertEquals(null, body["tools"])
        val messages = body["messages"] as JsonArray
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val call = messages[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", call["id"]?.jsonPrimitive?.content)
        assertEquals("{}", call["function"]!!.jsonObject["arguments"]?.jsonPrimitive?.content)
        assertEquals("c1", messages[3].jsonObject["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("and then?", messages[4].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiAsksForTheUsageUnlessTold() {
        val request = LlmRequest("", listOf(Message.user("hi")), emptyList(), 0)
        val provider = OpenAiProvider("https://e/v1", "k", "m")
        val options = provider.bodyOf(request)["stream_options"]!!.jsonObject
        assertEquals(JsonPrimitive(true), options["include_usage"])
        assertEquals(null, provider.bodyOf(request, withUsage = false)["stream_options"])
    }

    @Test
    fun openAiUsageWithWhatTheCacheGave() {
        val openAi = OpenAiProvider.Assembler {}
        openAi.feed(event("""{"choices":[{"delta":{"content":"a"},"finish_reason":"stop"}]}"""))
        // the last chunk: no choices, the usage
        openAi.feed(event("""{"choices":[],"usage":{"prompt_tokens":5000,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":4096}}}"""))
        assertEquals(Usage(5000, 7, cachedTokens = 4096), openAi.reply().usage)
        val deepSeek = OpenAiProvider.Assembler {}
        deepSeek.feed(event("""{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":3,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 3, cachedTokens = 640), deepSeek.reply().usage)
    }

    @Test
    fun openAiSendsWhatTheAppAddsAfterTheQuestion() {
        val request = LlmRequest(
            "",
            listOf(Message(Role.USER, listOf(Block.Text("why?"), Block.Context("<editor_state>a.c</editor_state>")))),
            emptyList(),
            0,
        )
        val user = OpenAiProvider("https://e/v1", "k", "m").bodyOf(request)["messages"]!!.jsonArray[0].jsonObject
        assertEquals("why?\n\n<editor_state>a.c</editor_state>", user["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiThinkingLevelIsTheReasoningEffort() {
        val request = LlmRequest("", listOf(Message.user("hi")), emptyList(), 0)
        assertEquals(null, OpenAiProvider("https://e/v1", "k", "m").bodyOf(request)["reasoning_effort"])
        assertEquals("none", OpenAiProvider("https://e/v1", "k", "m", thinking = ThinkingLevel.OFF).bodyOf(request)["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("medium", OpenAiProvider("https://e/v1", "k", "m", thinking = ThinkingLevel.MEDIUM).bodyOf(request)["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun openAiLeavesThinkingBlocksOut() {
        val request = LlmRequest(
            "",
            listOf(Message.user("q"), Message(Role.ASSISTANT, listOf(Block.Thinking("t", "s"), Block.Text("ok")))),
            emptyList(),
            0,
        )
        val assistant = OpenAiProvider("https://e/v1", "k", "m").bodyOf(request)["messages"]!!.jsonArray[1].jsonObject
        assertEquals("ok", assistant["content"]?.jsonPrimitive?.content)
    }

    // #endregion

    @Test
    fun errorMessagesSayWhatTheProviderSaid() {
        assertEquals("HTTP 401: invalid x-api-key", LlmHttp.errorMessage(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertEquals("HTTP 404: not here", LlmHttp.errorMessage(404, "not here"))
        assertEquals("HTTP 500", LlmHttp.errorMessage(500, ""))
    }
}
