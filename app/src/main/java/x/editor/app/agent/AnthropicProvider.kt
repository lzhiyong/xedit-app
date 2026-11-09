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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Anthropic's Messages API (`POST {baseUrl}/v1/messages`), streamed, with tools.
 * See https://docs.claude.com/en/api/messages.
 *
 * [thinking], unless [ThinkingLevel.DEFAULT], is sent as the effort of the request
 * (`output_config.effort`), with `thinking: {type: adaptive}`: the models that think by
 * default (Claude 5 and later) do so anyway, the ones that do not (Claude Opus 4.6 to 4.8,
 * Sonnet 4.6) only with it. The thinking that comes back is handed back within the turn: a
 * tool's result goes on from it, and the API wants it as it came (see [bodyOf]).
 *
 * What the model thinks is shown as it goes (the status of the turn: AgentSession), so a
 * summary of it is asked for (`thinking.display: summarized`, which costs nothing more):
 * the newer models give none by default (`omitted`), not even the notes they write between
 * calls of tools on what they are doing (Claude Opus 5.5, Fable 5.1), which are thinking
 * blocks too. Asked of the models that think anyway ([thinksByDefault]) whatever the level,
 * of the others only with a level (`thinking` would make one that does not think, think; one
 * of Claude 4.5 or before refuses it). A service in the API's place that does not know the
 * field is asked again without it, and from then on not at all.
 *
 * With [cache], what each request begins with is kept by the API for the next one (prompt
 * caching, https://docs.claude.com/en/docs/build-with-claude/prompt-caching): read from
 * there it costs a tenth or less of what it costs sent anew, and is not read through again.
 * The cache holds a prefix: the tools, the system prompt, the messages, up to a block marked
 * `cache_control` (a breakpoint, at most 4). They are put where what was sent before still
 * stands as it was (see [breakpointsOf]), so that the next request finds it: what the
 * session sends for that keeps the same from one request to the next (AgentSession,
 * AgentContext). Explicit breakpoints rather than the API's automatic one: the services that
 * take Anthropic's API in its place (proxies, other vendors') know these.
 */
class AnthropicProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
    private val cache: Boolean = true,
) : LlmProvider {

    override suspend fun complete(request: LlmRequest, onEvent: (LlmEvent) -> Unit): LlmReply {
        val display = showsThinking()
        try {
            return send(request, onEvent, display)
        } catch (e: LlmException) {
            // a service that does not take the field (it says so before streaming anything)
            if (!display || e.status != 400 || e.message?.contains(DISPLAY) != true) throw e
            withoutDisplay += displayKey()
            return send(request, onEvent, display = false)
        }
    }

    private suspend fun send(request: LlmRequest, onEvent: (LlmEvent) -> Unit, display: Boolean): LlmReply {
        val assembler = Assembler(onEvent)
        // (off the main thread: the images attached are read from their files, as base64)
        val body = withContext(Dispatchers.Default) { bodyOf(request, display) }
        LlmHttp.postStream(
            url = baseUrl.trimEnd('/') + "/v1/messages",
            headers = mapOf("x-api-key" to apiKey, "anthropic-version" to API_VERSION),
            body = body,
        ) { event -> assembler.feed(event) }
        return assembler.reply()
    }

    /**
     * Whether a summary of the thinking is asked for (`display: summarized`): of a model that
     * thinks by default, or with a level of thinking; not of a service that refused it.
     */
    fun showsThinking(): Boolean =
        (thinksByDefault(model) || effortOf(thinking) != null) && displayKey() !in withoutDisplay

    private fun displayKey() = baseUrl.trim() + " " + model.trim()

    /** The JSON of [request]; [display]: a summary of the thinking asked for ([showsThinking]). */
    fun bodyOf(request: LlmRequest, display: Boolean = showsThinking()): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        val effort = effortOf(thinking)
        // (adaptive is what a model that thinks by default does anyway: only display is new to it)
        if (effort != null || display) {
            put("thinking", buildJsonObject {
                put("type", "adaptive")
                if (display) put(DISPLAY, "summarized")
            })
        }
        effort?.let { put("output_config", buildJsonObject { put("effort", it) }) }
        // (a block, to be marked: the tools before it and it are the start of every request)
        if (request.system.isNotEmpty()) put("system", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", request.system)
                if (cache) put("cache_control", EPHEMERAL)
            })
        })
        // The thinking of the turn going on (since the user last said something: the
        // model's calls of tools and their results) is handed back, the API wants it there;
        // that of earlier turns is left out (it may be another model's, picked since, whose
        // signature this one would not take, and the API drops most of it anyway).
        val turnStart = (request.turnStart ?: request.messages.indexOfLast { isQuestion(it) }).coerceAtLeast(0)
        val marked = if (cache) breakpointsOf(request.messages) else emptySet()
        put("messages", buildJsonArray {
            request.messages.forEachIndexed { index, message ->
                add(messageJson(message, withThinking = index >= turnStart, marked = index in marked))
            }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEachIndexed { index, tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.inputSchema)
                        // no system prompt to mark: the last tool, for the tools
                        if (cache && request.system.isEmpty() && index == request.tools.lastIndex) put("cache_control", EPHEMERAL)
                    })
                }
            })
        }
    }

    /**
     * The messages whose last block is a breakpoint (with the system prompt's, at most the 4
     * the API takes): the last message, so that all of this request is kept for the next
     * one; and the last two questions (the messages of the user with text in them), as what
     * stands of what was sent before. The API finds what an earlier request kept by going
     * back from each breakpoint, 20 blocks at most, so the last message's is enough within a
     * turn (a call of tools and its results is a few blocks); but when a new question comes,
     * the thinking of the turn before it is no longer sent (see [bodyOf]), and what was kept
     * of that turn with it no longer matches from its first thinking on. What the request
     * that turn began with kept, up to its question, still does: its breakpoint finds it.
     */
    fun breakpointsOf(messages: List<Message>): Set<Int> {
        if (messages.isEmpty()) return emptySet()
        val marked = LinkedHashSet<Int>()
        messages.indices.filter { isQuestion(messages[it]) }.takeLast(2).forEach { marked += it }
        marked += messages.lastIndex
        return marked
    }

    private fun messageJson(message: Message, withThinking: Boolean, marked: Boolean = false) = buildJsonObject {
        put("role", if (message.role == Role.USER) "user" else "assistant")
        // all of a message's thinking or none of it: the API refuses some of it left out (a
        // block without its signature, the stream cut short before it came, cannot go back)
        val thinkingBack = withThinking && message.blocks.none { it is Block.Thinking && it.signature.isEmpty() }
        val content = ArrayList<JsonObject>()
        for (block in message.blocks) {
            when (block) {
                is Block.Thinking -> if (thinkingBack) content += buildJsonObject {
                    put("type", "thinking")
                    put("thinking", block.text)
                    put("signature", block.signature)
                }
                is Block.RedactedThinking -> if (thinkingBack) content += buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", block.data)
                }
                is Block.Text -> if (block.text.isNotEmpty()) content += textJson(block.text)
                is Block.Context -> if (block.text.isNotEmpty()) content += textJson(block.text)
                is Block.ToolUse -> content += buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    put("input", block.input)
                }
                is Block.ToolResult -> content += buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", block.toolUseId)
                    put("content", block.content)
                    if (block.isError) put("is_error", true)
                }
                is Block.Attachment -> content += attachmentJson(block.attachment)
            }
        }
        // the breakpoint on the last block that can be one (not thinking: the API refuses it there)
        if (marked) {
            val at = content.indexOfLast { (it["type"]?.jsonPrimitive?.contentOrNull ?: "") !in UNMARKABLE }
            if (at >= 0) content[at] = JsonObject(content[at] + ("cache_control" to EPHEMERAL))
        }
        put("content", JsonArray(content))
    }

    private fun textJson(text: String) = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    // A file attached: an image as its name and an image block (base64); anything else as
    // the text it was made into (AgentAttachments)
    private fun attachmentJson(attachment: AgentAttachment): List<JsonObject> {
        fun text(text: String) = buildJsonObject {
            put("type", "text")
            put("text", text)
        }
        if (attachment.kind != AgentAttachment.Kind.IMAGE) return listOf(text(AgentAttachments.textOf(attachment)))
        val data = AgentAttachments.base64Of(attachment) ?: return listOf(text(AgentAttachments.missingImageText(attachment)))
        return listOf(
            text(AgentAttachments.imageLabel(attachment)),
            buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", attachment.mediaType)
                    put("data", data)
                })
            },
        )
    }

    /**
     * A streamed reply put together, event by event: `content_block_start` opens a text or
     * a tool_use block, `content_block_delta` adds text or a piece of the tool's input
     * JSON, `message_delta` says why it stopped, `error` fails it. Thinking blocks are
     * gathered too (`thinking_delta`, `signature_delta`; `redacted_thinking` comes whole),
     * kept to be handed back, and what is shown of them streamed as thinking
     * (LlmEvent.ThinkingStarted, ThinkingDelta), not as text. Other events (ping) are let
     * be. The text of a text block that follows another one is shown after a blank line: one block may end in the middle of a sentence and the next
     * open with a code fence (```c), which is only a fence at the start of a line.
     */
    class Assembler(private val onEvent: (LlmEvent) -> Unit) {

        private class Open(val type: String, val id: String, val name: String) {
            val text = StringBuilder()

            // of a thinking block: its signature; of a redacted one: its data
            val signature = StringBuilder()
        }

        // whether some text was shown already (of an earlier text block, or of this one)
        private var shownText = false

        // [text] of [open] shown: after a blank line if it opens a text block that follows another
        private fun showText(open: Open, text: String) {
            if (text.isEmpty()) return
            if (open.text.isEmpty() && shownText) onEvent(LlmEvent.TextDelta(BLOCK_SEPARATOR))
            open.text.append(text)
            shownText = true
            onEvent(LlmEvent.TextDelta(text))
        }

        // [text] of the thinking block [open], kept and shown
        private fun showThinking(open: Open, text: String) {
            if (text.isEmpty()) return
            open.text.append(text)
            onEvent(LlmEvent.ThinkingDelta(text))
        }

        private val blocks = sortedMapOf<Int, Open>()
        private var stopReason = StopReason.OTHER
        // as the API counts them: the input after the last breakpoint, what was read from
        // the cache, what was written to it (all three make up what was sent)
        private var inputTokens: Int? = null
        private var cacheReadTokens: Int? = null
        private var cacheWriteTokens: Int? = null
        private var outputTokens: Int? = null

        // the counts of [usage] (message_start's, or message_delta's: the last ones said hold;
        // but a count of what was sent said as 0 after one that was not, as some services
        // that stand in for the API say it at the end, does not undo it)
        private fun noteUsage(usage: JsonObject?) {
            if (usage == null) return
            fun count(name: String) = (usage[name] as? JsonPrimitive)?.intOrNull
            count("input_tokens")?.let { if (it > 0 || inputTokens == null) inputTokens = it }
            count("cache_read_input_tokens")?.let { if (it > 0 || cacheReadTokens == null) cacheReadTokens = it }
            count("cache_creation_input_tokens")?.let { if (it > 0 || cacheWriteTokens == null) cacheWriteTokens = it }
            count("output_tokens")?.let { outputTokens = it }
        }

        fun feed(event: SseParser.Event) {
            val data = LlmHttp.objectOf(event.data) ?: return
            when (data["type"]?.jsonPrimitive?.contentOrNull ?: event.type) {
                "message_start" -> noteUsage(data["message"]?.jsonObject?.get("usage") as? JsonObject)
                "content_block_start" -> {
                    val index = data["index"]?.jsonPrimitive?.intOrNull ?: return
                    val block = data["content_block"] as? JsonObject ?: return
                    val type = block["type"]?.jsonPrimitive?.contentOrNull ?: return
                    val open = Open(
                        type,
                        block["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        block["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                    blocks[index] = open
                    when (type) {
                        "text" -> showText(open, block["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "tool_use" -> onEvent(LlmEvent.ToolStarted(open.name))
                        "thinking" -> {
                            onEvent(LlmEvent.ThinkingStarted)
                            showThinking(open, block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                            open.signature.append(block["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                        "redacted_thinking" -> {
                            onEvent(LlmEvent.ThinkingStarted)
                            open.signature.append(block["data"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                    }
                }
                "content_block_delta" -> {
                    val index = data["index"]?.jsonPrimitive?.intOrNull ?: return
                    val open = blocks[index] ?: return
                    val delta = data["delta"] as? JsonObject ?: return
                    when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            showText(open, delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                        "input_json_delta" -> open.text.append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "thinking_delta" -> showThinking(open, delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "signature_delta" -> open.signature.append(delta["signature"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
                "message_delta" -> {
                    val delta = data["delta"] as? JsonObject
                    stopReason = when (delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull) {
                        "end_turn", "stop_sequence" -> StopReason.END_TURN
                        "tool_use" -> StopReason.TOOL_USE
                        // (the reply reached the end of the model's context: cut short as well)
                        "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
                        else -> StopReason.OTHER
                    }
                    noteUsage(data["usage"] as? JsonObject)
                }
                "error" -> {
                    val error = data["error"] as? JsonObject
                    throw LlmException(error?.get("message")?.jsonPrimitive?.contentOrNull ?: event.data.take(300))
                }
            }
        }

        /** The reply, as streamed so far (all of it, once the stream ended). */
        fun reply(): LlmReply {
            val out = ArrayList<Block>()
            for (open in blocks.values) {
                when (open.type) {
                    "text" -> if (open.text.isNotEmpty()) out += Block.Text(open.text.toString())
                    "thinking" -> out += Block.Thinking(open.text.toString(), open.signature.toString())
                    "redacted_thinking" -> if (open.signature.isNotEmpty()) out += Block.RedactedThinking(open.signature.toString())
                    // an input cut off (max_tokens): an empty one, and the tool says what is missing
                    "tool_use" -> out += Block.ToolUse(open.id, open.name, LlmHttp.objectOf(open.text.toString()) ?: JsonObject(emptyMap()))
                }
            }
            // all that was sent: what the cache gave and took counted in
            val sent = inputTokens?.let { it + (cacheReadTokens ?: 0) + (cacheWriteTokens ?: 0) }
            return LlmReply(Message(Role.ASSISTANT, out), stopReason, Usage(sent, outputTokens, cacheReadTokens, cacheWriteTokens))
        }
    }

    companion object {
        const val API_VERSION = "2023-06-01"

        /** The effort [level] is sent as; null: none (the model's own). */
        fun effortOf(level: ThinkingLevel): String? = when (level) {
            ThinkingLevel.DEFAULT -> null
            // not offered for Anthropic (ThinkingLevel.ANTHROPIC): the least there is
            ThinkingLevel.OFF, ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH -> "xhigh"
            ThinkingLevel.MAX -> "max"
        }
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        /**
         * Whether [model] thinks whatever it is sent (Claude 5 and later, Claude Mythos):
         * by its name, as Anthropic gives it (claude-opus-5-5, claude-fable-5-1), or as a
         * service in its place does (anthropic/claude-sonnet-5, anthropic.claude-haiku-5-5-v1).
         */
        fun thinksByDefault(model: String): Boolean {
            val id = model.lowercase()
            if ("claude-mythos" in id) return true
            val major = FAMILY_VERSION.find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return major >= 5
        }

        // a model's family and the first number of its version (claude-sonnet-4-6: 4)
        private val FAMILY_VERSION = Regex("claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)")

        private const val DISPLAY = "display"

        // the services (by address and model) that refused DISPLAY, for as long as the app runs
        private val withoutDisplay: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        // between the text of two text blocks of one reply, as shown
        private const val BLOCK_SEPARATOR = "\n\n"

        // a breakpoint: kept for 5 minutes, and 5 more each time it is read
        private val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }

        // the blocks a breakpoint cannot be put on
        private val UNMARKABLE = setOf("thinking", "redacted_thinking")

        // a message of the user with text in it: a question (what tools returned is not text)
        private fun isQuestion(message: Message) = message.role == Role.USER && message.blocks.any { it is Block.Text }
    }
}
