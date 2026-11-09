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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The OpenAI-compatible chat completions (`POST {baseUrl}/chat/completions`), streamed,
 * with tools as functions: OpenAI, and the many that speak the same (DeepSeek, Qwen's
 * DashScope, Moonshot, Zhipu, OpenRouter, a local Ollama or LM Studio, ...). [baseUrl] is
 * the part before `/chat/completions`, `/v1` included where the provider has it.
 *
 * Tool results are messages of their own here (role `tool`), one per call; the text a
 * user message has besides goes after them.
 *
 * Most of these servers keep what a request begins with for the next one by themselves
 * (OpenAI, DeepSeek, Qwen, Moonshot, ...: their prompt caches), as long as it is the same
 * from its first byte; the session sends it so (AgentSession, AgentContext). How many
 * tokens a request took is asked for (`stream_options.include_usage`), for the session to
 * know how full the model's context is; a server that does not take that is asked again
 * without it, and from then on not at all.
 *
 * What a model that reasons says of it as it streams (`reasoning_content`: DeepSeek, Qwen,
 * Moonshot, Zhipu, ...; `reasoning`: OpenRouter and others) is shown as thinking
 * (LlmEvent.ThinkingDelta): the status of the turn (AgentSession). It is not kept in the
 * reply, nor sent back.
 */
class OpenAiProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    /** Sent as `max_tokens` when above 0; some models want none (or another field). */
    private val sendMaxTokens: Boolean = true,
    /** Sent as `reasoning_effort`, unless [ThinkingLevel.DEFAULT] (servers that do not know it may refuse it). */
    private val thinking: ThinkingLevel = ThinkingLevel.DEFAULT,
) : LlmProvider {

    override suspend fun complete(request: LlmRequest, onEvent: (LlmEvent) -> Unit): LlmReply {
        val withUsage = baseUrl.trim() !in withoutUsage
        try {
            return send(request, onEvent, withUsage)
        } catch (e: LlmException) {
            // a server that does not know the field (it says so before streaming anything)
            if (!withUsage || e.message?.contains(STREAM_OPTIONS) != true) throw e
            withoutUsage += baseUrl.trim()
            return send(request, onEvent, withUsage = false)
        }
    }

    private suspend fun send(request: LlmRequest, onEvent: (LlmEvent) -> Unit, withUsage: Boolean): LlmReply {
        val assembler = Assembler(onEvent)
        val headers = if (apiKey.isNotEmpty()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
        // (off the main thread: the images attached are read from their files, as base64)
        val body = withContext(Dispatchers.Default) { bodyOf(request, withUsage) }
        LlmHttp.postStream(baseUrl.trimEnd('/') + "/chat/completions", headers, body) { event ->
            assembler.feed(event)
        }
        return assembler.reply()
    }

    /** The JSON of [request]; [withUsage]: the tokens it took asked for, at the end of the stream. */
    fun bodyOf(request: LlmRequest, withUsage: Boolean = true): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        if (withUsage) put(STREAM_OPTIONS, buildJsonObject { put("include_usage", true) })
        if (sendMaxTokens && request.maxTokens > 0) put("max_tokens", request.maxTokens)
        reasoningEffortOf(thinking)?.let { put("reasoning_effort", it) }
        put("messages", buildJsonArray {
            if (request.system.isNotEmpty()) add(buildJsonObject {
                put("role", "system")
                put("content", request.system)
            })
            for (message in request.messages) messagesOf(message).forEach { add(it) }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            })
        }
    }

    // A message as the chat completions have it: an assistant's text and tool calls in
    // one; a user's tool results as `tool` messages, then its text (with files attached:
    // parts, the text of each and images as data URLs, then the text)
    private fun messagesOf(message: Message): List<JsonObject> {
        if (message.role == Role.ASSISTANT) {
            val calls = message.toolUses
            return listOf(buildJsonObject {
                put("role", "assistant")
                val text = message.text
                if (text.isNotEmpty()) put("content", text) else put("content", JsonNull)
                if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    calls.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.input.toString())
                            })
                        })
                    }
                })
            })
        }
        val out = ArrayList<JsonObject>()
        message.blocks.filterIsInstance<Block.ToolResult>().forEach { result ->
            out += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", result.toolUseId)
                put("content", if (result.isError) "Error: ${result.content}" else result.content)
            }
        }
        if (message.blocks.none { it is Block.Attachment }) {
            // what the user asked, and what the app adds to it (Block.Context), one after the other
            val text = message.blocks.mapNotNull { block ->
                when (block) {
                    is Block.Text -> block.text
                    is Block.Context -> block.text
                    else -> null
                }
            }.filter { it.isNotEmpty() }.joinToString("\n\n")
            if (text.isNotEmpty()) out += buildJsonObject {
                put("role", "user")
                put("content", text)
            }
            return out
        }
        val parts = buildJsonArray {
            fun text(text: String) = add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            for (block in message.blocks) {
                when (block) {
                    is Block.Text -> if (block.text.isNotEmpty()) text(block.text)
                    is Block.Context -> if (block.text.isNotEmpty()) text(block.text)
                    is Block.Attachment -> {
                        val attachment = block.attachment
                        if (attachment.kind != AgentAttachment.Kind.IMAGE) {
                            text(AgentAttachments.textOf(attachment))
                        } else {
                            val data = AgentAttachments.base64Of(attachment)
                            if (data == null) {
                                text(AgentAttachments.missingImageText(attachment))
                            } else {
                                text(AgentAttachments.imageLabel(attachment))
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", "data:${attachment.mediaType};base64,$data") })
                                })
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (parts.isNotEmpty()) out += buildJsonObject {
            put("role", "user")
            put("content", parts)
        }
        return out
    }

    /**
     * A streamed reply put together: each chunk's `choices[0].delta` adds `content`, or a
     * piece of a tool call (by its `index`: the first piece has its id and name, the next
     * ones more of its `arguments`), or of the reasoning (`reasoning_content`, `reasoning`:
     * only shown); `finish_reason` says why it stopped. `[DONE]` ends it.
     */
    class Assembler(private val onEvent: (LlmEvent) -> Unit) {

        private class Call(var id: String, var name: String) {
            val arguments = StringBuilder()
        }

        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, Call>()
        private var stopReason = StopReason.OTHER
        private var inputTokens: Int? = null
        private var cachedTokens: Int? = null
        private var outputTokens: Int? = null

        // whether what streamed in last was reasoning (more of it goes on with it)
        private var reasoning = false

        fun feed(event: SseParser.Event) {
            if (event.data.trim() == "[DONE]") return
            val data = LlmHttp.objectOf(event.data) ?: return
            (data["error"] as? JsonObject)?.let { error ->
                throw LlmException(error["message"]?.jsonPrimitive?.contentOrNull ?: event.data.take(300))
            }
            (data["usage"] as? JsonObject)?.let { usage ->
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.let { inputTokens = it }
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.let { outputTokens = it }
                // of the prompt, what the server's cache gave: OpenAI's way, DeepSeek's
                ((usage["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.intOrNull
                    ?.let { cachedTokens = it }
                (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull?.let { cachedTokens = it }
            }
            val choice = (data["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
            val delta = choice["delta"] as? JsonObject
            if (delta != null) {
                // (one of the two: some servers send both, the same, or one of them null)
                val thought = REASONING.firstNotNullOfOrNull { field ->
                    (delta[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                }
                if (thought != null) {
                    if (!reasoning) onEvent(LlmEvent.ThinkingStarted)
                    reasoning = true
                    onEvent(LlmEvent.ThinkingDelta(thought))
                }
                val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
                if (!content.isNullOrEmpty()) {
                    reasoning = false
                    text.append(content)
                    onEvent(LlmEvent.TextDelta(content))
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                    val piece = element as? JsonObject ?: return@forEach
                    val index = piece["index"]?.jsonPrimitive?.intOrNull ?: calls.size
                    val function = piece["function"] as? JsonObject
                    val id = piece["id"]?.jsonPrimitive?.contentOrNull
                    val name = function?.get("name")?.jsonPrimitive?.contentOrNull
                    val call = calls.getOrPut(index) { Call("", "") }
                    if (!id.isNullOrEmpty()) call.id = id
                    if (!name.isNullOrEmpty() && call.name.isEmpty()) {
                        reasoning = false
                        call.name = name
                        onEvent(LlmEvent.ToolStarted(name))
                    }
                    function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { call.arguments.append(it) }
                }
            }
            when (choice["finish_reason"]?.jsonPrimitive?.contentOrNull) {
                "stop" -> stopReason = StopReason.END_TURN
                "tool_calls", "function_call" -> stopReason = StopReason.TOOL_USE
                "length" -> stopReason = StopReason.MAX_TOKENS
                null -> {}
                else -> stopReason = StopReason.OTHER
            }
        }

        fun reply(): LlmReply {
            val blocks = ArrayList<Block>()
            if (text.isNotEmpty()) blocks += Block.Text(text.toString())
            for (call in calls.values) {
                if (call.name.isEmpty()) continue
                // some servers give no id: one of ours, the result names it
                val id = call.id.ifEmpty { "call_" + UUID.randomUUID().toString().replace("-", "").take(24) }
                blocks += Block.ToolUse(id, call.name, LlmHttp.objectOf(call.arguments.toString()) ?: JsonObject(emptyMap()))
            }
            // calls made, whatever the finish reason said (some say "stop")
            val reason = if (blocks.any { it is Block.ToolUse }) StopReason.TOOL_USE else stopReason
            return LlmReply(Message(Role.ASSISTANT, blocks), reason, Usage(inputTokens, outputTokens, cachedTokens))
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        private const val STREAM_OPTIONS = "stream_options"

        // the fields of a delta the reasoning streams in, as the servers name them
        private val REASONING = listOf("reasoning_content", "reasoning")

        // the servers (by address) that refused STREAM_OPTIONS, for as long as the app runs
        private val withoutUsage: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        /** The `reasoning_effort` [level] is sent as; null: none (the model's own). */
        fun reasoningEffortOf(level: ThinkingLevel): String? = when (level) {
            ThinkingLevel.DEFAULT -> null
            ThinkingLevel.OFF -> "none"
            ThinkingLevel.MINIMAL -> "minimal"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            // not offered for these (ThinkingLevel.OPENAI): the most there is
            ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "xhigh"
        }
    }
}
