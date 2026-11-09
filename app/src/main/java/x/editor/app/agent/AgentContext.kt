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
import kotlin.math.max
import kotlin.math.min

/**
 * How a conversation is cut down for the model, so that it stays within the model's context
 * window (AgentContext), as it stands: kept with the chat (AgentHistory). What was said is
 * all kept ([AgentSession]'s history, what the sheet shows); only what the model is sent of
 * it is cut down.
 *
 * - [resultsCleared]: in the messages before it, what a tool returned and what was given to
 *   a tool, where they are large (a file read, a command's output, a file written), are left
 *   out, a line saying what was there;
 * - [attachmentsCleared]: in the messages before it, so are the files the user attached
 *   (never those of the question being answered: the two are apart for that);
 * - [summary]: what stands for the messages before [summarized], which are not sent at all
 *   (the whole text sent, as AgentContext.summaryText makes it); null: none are left out;
 * - [thinkingDropped]: in the messages before it, the model's thinking is not handed back.
 *   It is set at the end of the history whenever one of the others goes on: the thinking
 *   of a block is good only after what it was thought after, as it was sent (the newer
 *   models of Anthropic check that, and may refuse a request where it changed: the 400 of
 *   https://platform.claude.com/docs/en/build-with-claude/preserved-thinking), and thinking
 *   left out from the oldest on is allowed. What is thought after it goes back again.
 *
 * Each only ever goes on (but when the chat is taken back: AgentSession.resend), and in large
 * steps: what is sent changes from the first thing it leaves out, and what the provider
 * cached of the requests before is of no more use from there (AnthropicProvider).
 */
data class ContextState(
    val resultsCleared: Int = 0,
    val attachmentsCleared: Int = 0,
    val summary: String? = null,
    val summarized: Int = 0,
    val thinkingDropped: Int = 0,
) {
    /**
     * This state for the history cut back to its first [kept] messages, as they were (a
     * question asked again: AgentSession.resend): a summary of more than those is dropped,
     * it tells of what is gone.
     */
    fun within(kept: Int): ContextState {
        val keepSummary = summary != null && summarized <= kept
        return ContextState(
            resultsCleared = min(resultsCleared, kept),
            attachmentsCleared = min(attachmentsCleared, kept),
            summary = if (keepSummary) summary else null,
            summarized = if (keepSummary) summarized else 0,
            thinkingDropped = min(thinkingDropped, kept),
        )
    }
}

/**
 * How much of a model's context a conversation may fill, for a model of [window] tokens
 * that writes up to [maxTokens] in a reply: what is left of the window once a reply has its
 * room ([usable], a little kept spare as well: the counts are estimates), and the marks in
 * it where AgentSession cuts what it sends down. Past [clearAt], large old tool results and
 * attachments are left out, down to [clearTo]; if that is not enough, past [compactAt], the
 * older part of the conversation is summarized.
 */
class ContextBudget(val window: Int, maxTokens: Int) {
    val usable: Int = max(window - min(maxTokens, window / 4) - window / 50, MIN_USABLE)
    val clearAt: Int = (usable * 0.75).toInt()
    val clearTo: Int = (usable * 0.5).toInt()
    val compactAt: Int = (usable * 0.85).toInt()

    /** The most a summary may be: room enough, but not so much that its request would not fit. */
    val summaryTokens: Int = min(SUMMARY_MAX_TOKENS, max(window / 4, 256))

    private companion object {
        const val MIN_USABLE = 1_000
        const val SUMMARY_MAX_TOKENS = 8_192
    }
}

/**
 * What of a conversation the model is sent ([view]), and how large that is ([tokens]): the
 * conversation as it was ([Message]s), cut down as [ContextState] says. Pure functions (the
 * session keeps the state, and asks the model for a summary: AgentSession.fit).
 *
 * The counts here are estimates, from the characters (code and English: about 3 a token;
 * other scripts, Chinese: about 1), images as a fixed count. The session corrects them by
 * what the provider said a request took.
 */
internal object AgentContext {

    // #region what the model is sent

    /**
     * The messages the model is sent of [history] (or of its first [end] messages), cut
     * down by [state]: the summary in place of what it stands for (in the first message,
     * itself the user's: before what that message says, the results of calls whose asking
     * the summary stands for left out), the large old results and attachments left out,
     * and the thinking from before the last of those changes. The same for the same
     * [history] and [state]: the provider keeps what it was sent.
     */
    fun view(history: List<Message>, state: ContextState, end: Int = history.size): List<Message> {
        val stop = end.coerceIn(0, history.size)
        val summary = state.summary
        val from = if (summary != null) state.summarized.coerceIn(0, stop) else 0
        val out = ArrayList<Message>(stop - from + 1)
        for (index in from until stop) {
            val message = cleared(history[index], index < state.resultsCleared, index < state.attachmentsCleared)
            out += if (index < state.thinkingDropped) withoutThinking(message) else message
        }
        if (summary == null) return out
        val head = Block.Context(summary)
        val first = out.firstOrNull()
        if (first != null && first.role == Role.USER) {
            out[0] = Message(Role.USER, listOf<Block>(head) + first.blocks.filter { it !is Block.ToolResult })
        } else {
            out.add(0, Message(Role.USER, listOf<Block>(head)))
        }
        return out
    }

    /**
     * [message] with what is large in it left out: with [results], what tools returned and
     * the long text given to them; with [attachments], the files attached (a line each says
     * where it is). [message] itself if nothing is.
     */
    fun cleared(message: Message, results: Boolean, attachments: Boolean): Message {
        if (!results && !attachments) return message
        var changed = false
        val blocks = message.blocks.map { block ->
            val out = when {
                results && block is Block.ToolResult && block.content.length > CLEAR_MIN_CHARS ->
                    Block.ToolResult(block.toolUseId, clearedResult(block.content), block.isError)
                results && block is Block.ToolUse -> clearedInput(block)
                attachments && block is Block.Attachment && isLarge(block.attachment) ->
                    Block.Context(clearedAttachment(block.attachment))
                else -> block
            }
            if (out !== block) changed = true
            out
        }
        return if (changed) Message(message.role, blocks) else message
    }

    /** [message] without the model's thinking; [message] itself if it has none. */
    fun withoutThinking(message: Message): Message {
        if (message.blocks.none { it is Block.Thinking || it is Block.RedactedThinking }) return message
        return Message(message.role, message.blocks.filter { it !is Block.Thinking && it !is Block.RedactedThinking })
    }

    // [use] with the long strings of its input left out; [use] itself if it has none
    private fun clearedInput(use: Block.ToolUse): Block.ToolUse {
        val long = use.input.filterValues { value -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().length > CLEAR_MIN_CHARS }
        if (long.isEmpty()) return use
        val input = use.input.mapValues { (name, value) ->
            if (name in long) JsonPrimitive("[left out to save context: ${(value as JsonPrimitive).content.length} characters]") else value
        }
        return Block.ToolUse(use.id, use.name, JsonObject(input))
    }

    private fun clearedResult(content: String) =
        "[Left out to save context: what this call returned (${content.length} characters). Call the tool again if you need it.]"

    // an attachment worth leaving out: an image, or what a text or an archive was made into
    private fun isLarge(attachment: AgentAttachment): Boolean =
        attachment.kind == AgentAttachment.Kind.IMAGE || AgentAttachments.textOf(attachment).length > CLEAR_MIN_CHARS

    private fun clearedAttachment(attachment: AgentAttachment): String {
        val what = if (attachment.kind == AgentAttachment.Kind.IMAGE) {
            "An image the user attached, left out here to save context."
        } else {
            "Left out here to save context: read_file can read it at this path."
        }
        return "<attachment name=\"${attribute(attachment.name)}\" path=\"${attribute(attachment.path)}\">$what</attachment>"
    }

    private fun attribute(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    // #endregion

    // #region how large it is

    /** About how many tokens [text] is. */
    fun tokens(text: String): Int {
        var ascii = 0
        var other = 0
        for (char in text) if (char.code < 128) ascii++ else other++
        return ascii / 3 + other
    }

    /** About how many tokens [message] is, as sent. */
    fun tokens(message: Message): Int {
        var total = MESSAGE_TOKENS
        for (block in message.blocks) {
            total += when (block) {
                is Block.Text -> tokens(block.text)
                is Block.Context -> tokens(block.text)
                is Block.Thinking -> tokens(block.text)
                is Block.RedactedThinking -> block.data.length / 4
                is Block.ToolUse -> tokens(block.name) + tokens(block.input.toString())
                is Block.ToolResult -> tokens(block.content)
                is Block.Attachment -> if (block.attachment.kind == AgentAttachment.Kind.IMAGE) {
                    IMAGE_TOKENS + tokens(AgentAttachments.imageLabel(block.attachment))
                } else {
                    tokens(AgentAttachments.textOf(block.attachment))
                }
            } + BLOCK_TOKENS
        }
        return total
    }

    fun tokens(messages: List<Message>): Int = messages.sumOf { tokens(it) }

    /** About how many tokens what every request begins with is: the system prompt, the tools. */
    fun tokens(system: String, tools: List<ToolSpec>): Int {
        val described = tools.sumOf { tokens(it.name) + tokens(it.description) + tokens(it.inputSchema.toString()) + BLOCK_TOKENS }
        return tokens(system) + described + (if (tools.isEmpty()) 0 else TOOLS_TOKENS)
    }

    // #endregion

    // #region leaving out what is large and old

    /**
     * Where in [history] the turn going on began: its last message of the user with text in
     * it (the question); -1 if there is none.
     */
    fun turnStart(history: List<Message>): Int =
        history.indexOfLast { message -> message.role == Role.USER && message.blocks.any { it is Block.Text } }

    /**
     * The first message of [history] whose tool results are kept whatever happens: the one
     * that holds the [KEEP_RESULTS]th result from the end (so that holds and those after it
     * are kept); 0 if there are not that many (all kept: there is little to gain).
     */
    fun resultsKeptFrom(history: List<Message>): Int {
        var results = 0
        for (index in history.indices.reversed()) {
            val message = history[index]
            if (message.role == Role.USER) results += message.blocks.count { it is Block.ToolResult }
            if (results >= KEEP_RESULTS) return index
        }
        return 0
    }

    /**
     * [state] with more of [history] left out, from its oldest messages on, until about
     * [needed] tokens fewer are sent (estimated: [tokens]): the results of tools and what
     * was given to them (not the last [KEEP_RESULTS] results), and the files attached (not
     * those of the question being answered). [state] itself if nothing more can be.
     */
    fun clearing(history: List<Message>, state: ContextState, needed: Int): ContextState {
        if (needed <= 0) return state
        val visible = if (state.summary != null) state.summarized else 0
        val resultsLimit = resultsKeptFrom(history)
        val attachmentsLimit = turnStart(history).coerceAtLeast(0)
        val results = max(state.resultsCleared, visible)
        val attachments = max(state.attachmentsCleared, visible)
        var newResults = state.resultsCleared
        var newAttachments = state.attachmentsCleared
        var saved = 0
        var index = min(results, attachments)
        val end = max(resultsLimit, attachmentsLimit)
        while (index < end && saved < needed) {
            val message = history[index]
            val before = cleared(message, index < results, index < attachments)
            val after = cleared(message, index < results || index < resultsLimit, index < attachments || index < attachmentsLimit)
            if (after !== before) saved += tokens(before) - tokens(after)
            if (index < resultsLimit) newResults = max(newResults, index + 1)
            if (index < attachmentsLimit) newAttachments = max(newAttachments, index + 1)
            index++
        }
        // nothing to gain: nothing changed (what is sent stays as it is, and its cache)
        if (saved <= 0) return state
        return state.copy(resultsCleared = newResults, attachmentsCleared = newAttachments)
    }

    // #endregion

    // #region summarizing what came before

    /**
     * What to summarize, and how: the messages before [cut] of the history (all of them, or
     * those before the turn going on: [everything]) are to give way to a summary, asked of
     * the model by [messages] (what is sent of them, and the question that asks for it),
     * with the thinking of [turnStart] on handed back as the requests before had it.
     */
    class Compaction(val cut: Int, val everything: Boolean, val messages: List<Message>, val turnStart: Int)

    /**
     * How to compact [history], as [state] has it, to fit [budget]: before the turn going on
     * if what that turn says leaves room for a summary, else all of it. What is asked for a
     * summary is no more than about [room] tokens ([tokens]): if what is sent of it is more,
     * all that is large in it is left out, and then its oldest messages. Null: nothing more
     * can be summarized (all of it is already).
     *
     * [ratio]: the real tokens of an estimated one, as the provider has counted ([tokens]
     * estimates); [base]: the tokens of the system prompt and the tools (estimated).
     */
    fun compaction(
        history: List<Message>,
        state: ContextState,
        budget: ContextBudget,
        ratio: Double,
        base: Int,
        room: Int,
    ): Compaction? {
        val from = if (state.summary != null) state.summarized else 0
        val turn = turnStart(history)
        // what the turn going on is (as sent), with what every request begins with
        val turnTokens = if (turn > from) ((base + tokensFrom(history, state, turn)) * ratio).toInt() else Int.MAX_VALUE
        val everything = turn <= from || turnTokens + budget.summaryTokens > budget.clearTo
        val cut = if (everything) history.size else turn
        if (cut <= from) return null
        val limit = (room / ratio).toInt() - base
        var messages = view(history, state, cut)
        if (tokens(messages) > limit) {
            val bare = state.copy(resultsCleared = max(state.resultsCleared, cut), attachmentsCleared = max(state.attachmentsCleared, cut))
            messages = view(history, bare, cut)
            if (tokens(messages) > limit) messages = newest(messages, limit)
        }
        // as the requests before had it: the thinking of the turn going on handed back (the
        // API wants that of a call whose result follows), none of that before it
        val thinkingFrom = if (everything) {
            messages.indexOfLast { message -> message.role == Role.USER && message.blocks.any { it is Block.Text } }.coerceAtLeast(0)
        } else {
            messages.size
        }
        val ask = Block.Text(summaryRequest(everything))
        val last = messages.lastOrNull()
        val asked = if (last != null && last.role == Role.USER) {
            messages.dropLast(1) + Message(Role.USER, last.blocks + ask)
        } else {
            messages + Message(Role.USER, listOf<Block>(ask))
        }
        return Compaction(cut, everything, asked, thinkingFrom)
    }

    // the tokens of what is sent of history[from until its end], as [state] has it
    private fun tokensFrom(history: List<Message>, state: ContextState, from: Int): Int {
        var total = 0
        for (index in from until history.size) {
            total += tokens(cleared(history[index], index < state.resultsCleared, index < state.attachmentsCleared))
        }
        return total
    }

    // the newest of [messages] that are about [limit] tokens at most, from a message of the
    // user on (said to be all there is), the results in it of calls left out with the rest
    private fun newest(messages: List<Message>, limit: Int): List<Message> {
        if (messages.isEmpty()) return messages
        val sizes = IntArray(messages.size + 1)
        for (index in messages.indices.reversed()) sizes[index] = sizes[index + 1] + tokens(messages[index])
        val note = Block.Context(LEFT_OUT_NOTE)
        for (index in 1 until messages.size) {
            val message = messages[index]
            if (message.role != Role.USER || sizes[index] > limit) continue
            return listOf(Message(Role.USER, listOf<Block>(note) + message.blocks.filter { it !is Block.ToolResult })) +
                messages.subList(index + 1, messages.size)
        }
        // not even the last of them (a question larger than the context): it alone
        val last = messages.last()
        return listOf(Message(Role.USER, listOf<Block>(note) + last.blocks.filter { it !is Block.ToolResult }))
    }

    /** What asks the model for a summary of the conversation ([everything]: the turn going on too). */
    fun summaryRequest(everything: Boolean): String = buildString {
        append(
            """
            Your context is nearly full: what has been said so far is about to be replaced by a summary, which you write now. Do not call any tools. Answer with the summary only, in the language the user writes in.

            Write it so that you can go on from it alone, as if nothing had been lost:
            1. What the user asked for: their requests, in their own words where it matters, and what they said about how (preferences, constraints, decisions).
            2. What has been done: the files read, created and changed (their paths, and what changed in them), the commands run and what came of them, what was found out.
            3. Where things stand: what is finished, what is under way, what is left to do, the errors not solved yet, the questions still open.
            4. The details that will be needed again: names, paths, line numbers, short pieces of code, exact error messages.
            Leave out what no longer matters. The files stay on disk: say which ones matter rather than copy them.
            """.trimIndent()
        )
        if (everything) append("\nThe user's last request is still being worked on: end with what you were doing, and what is to be done next.")
    }

    /**
     * What the model is sent in place of what [summary] (its own) stands for;
     * [everything]: the turn going on is in it too (it is to go on with it).
     */
    fun summaryText(summary: String, everything: Boolean): String = buildString {
        append("<conversation_summary>\n")
        append("The conversation grew too long for your context, so what came before this point was replaced by this summary, which you wrote. ")
        append("Large tool results from before may also be left out: read files again rather than guess what they held.\n\n")
        append(summary.trim()).append('\n')
        append("</conversation_summary>")
        if (everything) append("\nGo on with the task from where the summary leaves off.")
    }

    /**
     * What the model is sent in place of what could not be summarized (the request for it
     * failed), and [question], the last thing the user asked, if that is left out with it.
     */
    fun droppedText(question: String?): String = buildString {
        append("<conversation_summary>\n")
        append("What came before this point was left out: it no longer fit in your context, and could not be summarized. ")
        append("Ask the user if you need to know what was said in it.\n")
        append("</conversation_summary>")
        if (!question.isNullOrBlank()) {
            append("\nThe user's last request, still being worked on, was:\n").append(question.take(MAX_QUESTION_CHARS))
            append("\nGo on with it: look at the files again to see how far it got.")
        }
    }

    // #endregion

    // #region a request too large for the model

    /** What a provider said of a request too large for its model: its tokens, and the most it takes (if it said). */
    data class Overflow(val used: Int?, val limit: Int?)

    /**
     * Whether [e] says the request was too large for the model's context, and what it said
     * of it; null if it is not that. Not a rate limit (tokens per minute) either.
     */
    fun overflowOf(e: LlmException): Overflow? {
        val message = e.message.orEmpty()
        if (e.status == 429 || RATE_LIMIT.containsMatchIn(message)) return null
        if (!OVERFLOW.containsMatchIn(message)) return null
        var used: Int? = null
        var limit: Int? = null
        // Anthropic: "prompt is too long: 215000 tokens > 200000 maximum"; an older one:
        // "input length and `max_tokens` exceed context limit: 198000 + 8192 > 200000"
        SUM_OVER.find(message)?.let { match ->
            used = number(match.groupValues[1])
            limit = number(match.groupValues[3])
        }
        if (limit == null) TOKENS_OVER.find(message)?.let { match ->
            used = number(match.groupValues[1])
            limit = number(match.groupValues[2])
        }
        // OpenAI and those that answer as it does: "This model's maximum context length is
        // 128000 tokens. However, your messages resulted in 130000 tokens."
        if (limit == null) limit = MAXIMUM.find(message)?.let { number(it.groupValues[1]) }
        if (used == null) used = RESULTED.find(message)?.let { match -> number(match.groupValues[1].ifEmpty { match.groupValues[2] }) }
        return Overflow(used?.takeIf { it in SANE }, limit?.takeIf { it in SANE })
    }

    private fun number(text: String): Int? = text.replace(",", "").replace("_", "").toIntOrNull()

    // #endregion

    /** How many of the newest tool results are always sent (what the model works on now). */
    const val KEEP_RESULTS = 4

    /** What is shorter than this (characters) is not left out: there is little to gain. */
    const val CLEAR_MIN_CHARS = 1_200

    /** What an image is counted as (an attachment is at most 1568 pixels on its longer side). */
    const val IMAGE_TOKENS = 2_000

    private const val MESSAGE_TOKENS = 4
    private const val BLOCK_TOKENS = 3

    // what the API adds of its own for tools (their instructions)
    private const val TOOLS_TOKENS = 400

    private const val MAX_QUESTION_CHARS = 8_000

    private const val LEFT_OUT_NOTE =
        "(The oldest part of the conversation is left out here: it did not fit in your context.)"

    private val SANE = 1_000..100_000_000

    private val OVERFLOW = Regex(
        "prompt.{0,20}too long|input is too long|too many (input )?tokens|context[ _-]?(length|window|limit)|maximum context|" +
            "exceed(s|ed)?\\b.{0,30}?(context|input token|token limit|max(imum)? (length|number of tokens))|" +
            "reduce the length|request_too_large|request too large",
        RegexOption.IGNORE_CASE,
    )
    private val RATE_LIMIT = Regex("rate.?limit|per minute|per day|quota", RegexOption.IGNORE_CASE)
    private val SUM_OVER = Regex("(\\d[\\d,_]*)\\s*\\+\\s*(\\d[\\d,_]*)\\s*>\\s*(\\d[\\d,_]*)")
    private val TOKENS_OVER = Regex("(\\d[\\d,_]*)\\s*tokens?\\s*>\\s*(\\d[\\d,_]*)", RegexOption.IGNORE_CASE)
    private val MAXIMUM = Regex(
        "(?:maximum context length|context (?:length|window|limit)|token limit)\\D{0,40}?(\\d[\\d,_]{3,})",
        RegexOption.IGNORE_CASE,
    )
    private val RESULTED = Regex(
        "(?:resulted in|messages? (?:have|has|contain|of)|requested|prompt has|input has)\\s*(\\d[\\d,_]{3,})\\s*tokens|\\((\\d[\\d,_]{3,}) in the messages",
        RegexOption.IGNORE_CASE,
    )
}
