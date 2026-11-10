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
import java.io.IOException

/*
 * What is said to a model and what it says back, the same for every provider (Anthropic's
 * Messages API, the OpenAI-compatible chat completions): each provider turns these into its
 * own JSON and back (AnthropicProvider, OpenAiProvider).
 */

/** Who says a message. Tool results are said by the user (as in Anthropic's API). */
enum class Role { USER, ASSISTANT }

/** A part of a message. */
sealed interface Block {
    /** Text. */
    data class Text(val text: String) : Block

    /** The model asking for a tool to be run: its [id] (the result names it), the tool's [name], its [input]. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block

    /** What a tool came to, for the [ToolUse] of [toolUseId]; [isError]: it failed (or was refused). */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : Block

    /**
     * The model's thinking (Anthropic): what of it is shown ([text], a summary of it; nothing
     * if the API was not asked for that) and the [signature] that carries the whole of it,
     * encrypted. Shown only as it streams in (LlmEvent.ThinkingDelta: the status of the turn,
     * AgentSession), not kept in the history of chats; handed back as it came, within the
     * turn it was thought in (a tool's result goes on from it: AnthropicProvider), unless
     * what came before it was cut down since (ContextState.thinkingDropped).
     */
    data class Thinking(val text: String, val signature: String) : Block

    /** Thinking the provider gives only encrypted ([data]), handed back as [Thinking] is. */
    data class RedactedThinking(val data: String) : Block

    /**
     * A file the user attached ([attachment]), before the text of the question it goes with:
     * an image as an image, anything else as the text it is made into (AgentAttachments).
     */
    data class Attachment(val attachment: AgentAttachment) : Block

    /**
     * Text the app adds to a message of the user, not typed by them: what the editor showed
     * when they sent it (after the text of their question: AgentSession), or, in what the
     * model is sent only, what stands for something left out (AgentContext). Sent as text;
     * not a question (messagesBefore counts [Text] only), not shown.
     */
    data class Context(val text: String) : Block
}

/**
 * How much the model is to think before it answers, as the settings have it for a provider
 * (AgentSettings). [DEFAULT]: nothing is sent, the model does as it does by default. Each
 * provider offers only some of the levels ([ANTHROPIC], [OPENAI]) and sends them its own way:
 * Anthropic as the effort of the request, with adaptive thinking (AnthropicProvider), the
 * OpenAI-compatible ones as `reasoning_effort` (OpenAiProvider).
 */
enum class ThinkingLevel(val key: String) {
    DEFAULT("default"),
    OFF("off"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        /**
         * The levels Anthropic's models take (output_config.effort): no "off", as the newer
         * models do not all let thinking be turned off, nor in the same way (some refuse
         * `thinking: disabled`); the least is [LOW].
         */
        val ANTHROPIC = listOf(DEFAULT, LOW, MEDIUM, HIGH, XHIGH, MAX)

        /** The values of OpenAI's `reasoning_effort` ([OFF]: "none"). */
        val OPENAI = listOf(DEFAULT, OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH)

        fun of(key: String?): ThinkingLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class Message(val role: Role, val blocks: List<Block>) {
    val text: String get() = blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
    val toolUses: List<Block.ToolUse> get() = blocks.filterIsInstance<Block.ToolUse>()

    /** Whether it says something: text, or a call of a tool (thinking alone says nothing). */
    val saysSomething: Boolean get() = blocks.any { (it is Block.Text && it.text.isNotEmpty()) || it is Block.ToolUse }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
    }
}

/** A tool the model may ask for: its [name], what it does, and its input as a JSON schema. */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * One request: the [system] prompt, the conversation so far, the tools, at most [maxTokens]
 * in the reply. [turnStart]: where in [messages] the turn going on starts, the model's
 * thinking handed back from there on (AnthropicProvider); null: at the last message of the
 * user with text in it (a question), as it is for every request but a summary's
 * (AgentSession.compact), which adds a question of its own after the turn.
 */
data class LlmRequest(
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolSpec>,
    val maxTokens: Int,
    val turnStart: Int? = null,
)

/** Why the model stopped. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, OTHER }

/**
 * How many tokens a request took (null: the provider did not say). [inputTokens]: all of
 * what it was sent, what was read from the provider's cache of it ([cachedTokens]) and
 * written to that cache ([cacheWriteTokens], Anthropic) included.
 */
data class Usage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
)

/** A whole reply: the assistant's message, why it stopped, what it cost. */
data class LlmReply(val message: Message, val stopReason: StopReason, val usage: Usage?)

/** What streams in while a reply is being written, for the screen. */
sealed interface LlmEvent {
    /** More of the text. */
    data class TextDelta(val text: String) : LlmEvent

    /** The model began asking for the tool [name] (its input is still coming). */
    data class ToolStarted(val name: String) : LlmEvent

    /**
     * A part of the model's thinking began: a thinking block (Anthropic), the reasoning of a
     * reply (the OpenAI-compatible ones). What of it is shown follows ([ThinkingDelta]),
     * if any is (Anthropic's newer models say nothing of it unless asked to: AnthropicProvider).
     */
    data object ThinkingStarted : LlmEvent

    /**
     * More of what is shown of the thinking begun last ([ThinkingStarted]): a summary of it
     * (Anthropic), or the reasoning itself (DeepSeek, Qwen, OpenRouter, ...). For the
     * screen only: what the model is doing (AgentProgress), and what it thought (the turn's
     * status, unfolded).
     */
    data class ThinkingDelta(val text: String) : LlmEvent
}

/** A model, behind some provider's API. */
interface LlmProvider {
    /**
     * Sends [request] and streams the reply: [onEvent] as it comes (on the thread that
     * reads it, not the main one), the whole of it returned. Cancelling the coroutine
     * cancels the request.
     *
     * @throws LlmException if the provider refuses it, or it cannot be reached
     */
    suspend fun complete(request: LlmRequest, onEvent: (LlmEvent) -> Unit): LlmReply
}

/** A request that failed: the provider's [status] (null: not reached) and what it said. */
class LlmException(message: String, val status: Int? = null, cause: Throwable? = null) : IOException(message, cause)
