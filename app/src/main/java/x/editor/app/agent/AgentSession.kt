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

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.editor.app.R
import java.io.File
import kotlin.math.max

/** What the agent needs of the screen it works for (EditorActivity). */
interface AgentHost {
    /** What the editor shows: the file, the line of the cursor (from 1), the text selected. */
    data class EditorState(val file: File, val line: Int, val selection: String?)

    /** The root of the workspace: the project of the file shown (ProjectRoot); null if there is none. */
    suspend fun workspaceRoot(): File?

    fun editorState(): EditorState?

    /** The agent changed [file]: an open document of it is read again. */
    fun onFileChanged(file: File)

    /** [file] opened in the editor (a tap on a file the agent changed). */
    fun openFile(file: File)
}

/** What the conversation shows, item by item. */
sealed class ChatItem {
    val id: Long = nextId++

    /**
     * What the user asked, when it was sent (0: not known, a chat kept before it was noted),
     * and the files attached to it.
     */
    class User(
        val text: String,
        val time: Long = System.currentTimeMillis(),
        val attachments: List<AgentAttachment> = emptyList(),
    ) : ChatItem()

    /** The model's text, growing while it streams in. */
    class Assistant : ChatItem() {
        val text = StringBuilder()
        var streaming = true
    }

    /** A tool call: what, where, whether it is waiting for the user, and what it came to. */
    class Tool(val name: String, val summary: String, val risk: ToolRisk) : ChatItem() {
        var state = ToolState.RUNNING
        var preview: String? = null
        var result: String? = null
        var file: File? = null
        /** The file to View on the card (CodeViewer): the one it changed, or read. */
        var viewFile: File? = null
        var expanded = false
        internal var decision: CompletableDeferred<Decision>? = null
        /** While it runs: the last of what it printed so far (run_command), as it comes; not kept. */
        var live: String? = null
    }

    class Notice(val text: String, val isError: Boolean) : ChatItem()

    /**
     * The status of a turn, one a turn: while it goes on, the last item of the chat (what
     * the turn says and does comes before it: AgentSession.add), saying what the agent is
     * doing ([activity]) and for how long it has been at the turn; once over, where it was
     * left, saying how the turn ended ([state]) and how long it took ([elapsed]). [started]:
     * when it began (the clock's time, as kept). What the model thought in the turn
     * ([thought], what was shown of it, the parts a blank line apart) is shown under it,
     * unfolded ([expanded]).
     */
    class Progress(val started: Long = System.currentTimeMillis()) : ChatItem() {
        var state = ProgressState.RUNNING
        /** While it goes on; not kept. */
        var activity: TurnActivity = TurnActivity.Thinking
        /** How long the turn took, once over. */
        var elapsed = 0L
        val thought = StringBuilder()
        var expanded = false

        // where in [thought] the part of it begun last is; a part begun with nothing in it
        // yet ([thinkingStarted]: it may never have anything) is not set apart until it does
        private var partStart = 0
        private var partPending = false

        /** How long the turn has gone on at [now], or took. */
        fun elapsedAt(now: Long): Long = if (state == ProgressState.RUNNING) (now - started).coerceAtLeast(0L) else elapsed

        /** A new part of the thinking began (LlmEvent.ThinkingStarted). */
        fun thinkingStarted() {
            partPending = true
        }

        /** More of the thinking: of the part begun last. */
        fun addThought(text: String) {
            if (text.isEmpty()) return
            if (partPending || thought.isEmpty()) {
                if (thought.isNotEmpty()) thought.append("\n\n")
                partStart = thought.length
                partPending = false
            }
            thought.append(text)
        }

        /** What the part of the thinking begun last says so far (nothing if it says nothing yet). */
        val lastThought: String get() = if (partPending) "" else thought.substring(partStart)
    }

    private companion object {
        var nextId = 1L
    }
}

enum class ToolState { WAITING, RUNNING, DONE, FAILED, DENIED, STOPPED }

/** The user's answer to a call that asks: once, for every call of its kind in this chat, or no. */
enum class Decision { ALLOW, ALLOW_IN_CHAT, DENY }

/**
 * A conversation with the agent: the user writes; the model answers, and asks for tools
 * (AgentTools) on the workspace, which run (those that change files or run commands once
 * the user allows them, see AgentSettings.Approval) and whose results go back to it; so on
 * until it answers without a tool, or [MAX_ROUNDS] rounds. Each turn has a status
 * (ChatItem.Progress), the last item while it goes on: what the agent is doing now (what
 * the model thinks, as it says it; the tool running; waiting for the user), and how long
 * the turn has taken. Kept by the screen while it lives (EditorActivity), so that closing
 * the sheet loses nothing and a turn goes on behind it. Main thread.
 *
 * What the model is sent is kept within its context window ([fit]): as the conversation
 * grows, the large results of tools and files attached from earlier on are left out of
 * it, and then its older part gives way to a summary the model writes (AgentContext). And
 * it is kept the same from one request to the next, but for what is added at its end (the
 * system prompt says nothing that changes, what the editor shows goes with each question:
 * [addEditorState]), so that the provider can serve what it was sent before from its
 * cache (AnthropicProvider): far cheaper and quicker than reading it through again.
 */
class AgentSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: AgentHost,
) {

    /** Told of changes to [items] and to whether a turn goes on (main thread). */
    interface Listener {
        fun onItemInserted(index: Int)
        fun onItemChanged(index: Int)
        fun onItemRemoved(index: Int)
        fun onReset()
        fun onBusyChanged(busy: Boolean)
    }

    /** A whole conversation, to keep and to take up again (AgentHistory). */
    class Snapshot(
        /** What the sheet shows of it. */
        val items: List<ChatItem>,
        /** What the model is told of it, so that the next question goes on from here. */
        val messages: List<Message>,
        val workspace: File?,
        val model: String?,
        /** How what the model is sent of [messages] is cut down (AgentContext). */
        val context: ContextState = ContextState(),
    )

    val items = ArrayList<ChatItem>()
    var listener: Listener? = null

    /** The workspace of the last turn (shown in the sheet). */
    var workspaceRoot: File? = null
        private set

    /** The model of the last turn (what the history lists the chat by); null: no turn yet. */
    var model: String? = null
        private set

    /** Which chat of the history this one is (AgentHistory.Entry.id); null: not kept yet. */
    var historyId: String? = null

    private val history = ArrayList<Message>()
    private val allowedInChat = HashSet<ToolRisk>()
    private var job: Job? = null

    /** The status of the turn going on (the last item: [add] puts the others before it). */
    private var progress: ChatItem.Progress? = null
    private val main = Handler(Looper.getMainLooper())

    /** How what the model is sent of [history] is cut down (AgentContext.view). */
    private var contextState = ContextState()

    /**
     * The real tokens of one AgentContext estimates, as the provider counted the last
     * request it said the tokens of (the estimates are from characters: code, prose and
     * scripts differ).
     */
    private var tokenRatio = 1.0

    /**
     * What the last request took, as its provider said: the tokens of the first [count]
     * messages it was sent with [state] (its reply the last of them). The next requests with
     * the same state begin with those, and are counted from there.
     */
    private class Counted(val state: ContextState, val count: Int, val tokens: Int)

    private var counted: Counted? = null

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Sends [text] from the user, with the files [attachments] (before it, for the model), and
     * the agent goes to work on it. Files alone may be sent, with no text.
     */
    fun send(text: String, attachments: List<AgentAttachment> = emptyList()) {
        val message = text.trim()
        if ((message.isEmpty() && attachments.isEmpty()) || isBusy) return
        val config = AgentSettings.load(context)
        if (!config.isUsable) {
            add(ChatItem.Notice(context.getString(R.string.agent_not_configured), isError = true))
            return
        }
        add(ChatItem.User(message, attachments = attachments))
        // (the text even if empty: what the user asked is counted by it, messagesBefore)
        val blocks = attachments.map { Block.Attachment(it) } + Block.Text(message)
        // after a turn that ended early (a failed request, a stop) the last message may be
        // the user's still: this one goes with it (the API wants the turns to alternate)
        val last = history.lastOrNull()
        if (last != null && last.role == Role.USER) {
            history[history.lastIndex] = Message(Role.USER, last.blocks + blocks)
        } else {
            history += Message(Role.USER, blocks)
        }
        val status = ChatItem.Progress()
        add(status)
        progress = status
        // busy before the turn starts: on the main dispatcher it may run (and end) at once
        listener?.onBusyChanged(true)
        job = scope.launch {
            var ended = ProgressState.INCOMPLETE
            try {
                if (converse(config)) ended = ProgressState.DONE
            } catch (e: CancellationException) {
                // (the status says so: no word of its own)
                ended = ProgressState.STOPPED
            } finally {
                finish(status, ended)
                listener?.onBusyChanged(false)
                // a turn done: kept as it now stands, whether the sheet is open or not
                keep()
            }
        }
    }

    /**
     * [item] asked again as [text], in its place, with the files it had: it goes, with
     * everything said after it, both from what is shown and from what the model is told, and
     * [text] is sent. Not while a turn goes on. What the agent did to the files in what goes
     * stays done: only the chat goes back. Without settings to ask with nothing goes: [send]
     * says what is wrong.
     */
    fun resend(item: ChatItem.User, text: String) {
        val message = text.trim()
        if ((message.isEmpty() && item.attachments.isEmpty()) || isBusy) return
        val index = items.indexOf(item)
        if (index < 0) return
        if (AgentSettings.load(context).isUsable) {
            val question = items.subList(0, index).count { it is ChatItem.User }
            // (a copy: what it returns may be a view of the list cleared next)
            val before = messagesBefore(history, question).toList()
            // what is cut down of what stays as it was stays so (the last message kept may be
            // only a part of what it was)
            val same = before.indices.firstOrNull { before[it] !== history[it] } ?: before.size
            contextState = contextState.within(same)
            counted = null
            history.clear()
            history += before
            while (items.size > index) {
                items.removeAt(items.lastIndex)
                listener?.onItemRemoved(items.size)
            }
        }
        send(message, item.attachments)
    }

    /** Stops the turn going on (the request, the tool running, a question waiting). */
    fun stop() {
        job?.cancel()
    }

    /** A new chat: what was said is forgotten (not while a turn goes on). */
    fun clear() {
        if (isBusy) return
        items.clear()
        history.clear()
        allowedInChat.clear()
        contextState = ContextState()
        counted = null
        historyId = null
        model = null
        listener?.onReset()
    }

    /** The whole of this conversation, to keep (AgentHistory). */
    fun snapshot() = Snapshot(items.toList(), history.toList(), workspaceRoot, model, contextState)

    /**
     * This conversation kept in the history (AgentHistory), in place of what was kept of it
     * before: at the end of every turn, and whenever the sheet lets go of it (a new chat,
     * another chat taken up, the sheet closed). A chat with nothing in it is not kept.
     * What is to be kept is taken here, on the main thread; the file is written off it.
     */
    fun keep() {
        if (items.isEmpty()) return
        val id = historyId ?: AgentHistory.newId().also { historyId = it }
        val kept = snapshot()
        scope.launch(Dispatchers.IO) { AgentHistory.save(context, id, kept) }
    }

    /**
     * [snapshot] taken up in place of what is shown, as the chat [id] of the history: the
     * model is told of it again, so the next question goes on from its end. Not while a turn
     * goes on (the chat shown is to be kept first: AgentSheet).
     *
     * @return whether it was taken up
     */
    fun restore(snapshot: Snapshot, id: String?): Boolean {
        if (isBusy) return false
        items.clear()
        items += snapshot.items
        history.clear()
        history += snapshot.messages
        contextState = snapshot.context.within(snapshot.messages.size)
        counted = null
        // what was allowed in the chat is not kept: it is asked again
        allowedInChat.clear()
        workspaceRoot = snapshot.workspace
        model = snapshot.model
        historyId = id
        listener?.onReset()
        return true
    }

    /** The user's [decision] on [item], waiting. */
    fun decide(item: ChatItem.Tool, decision: Decision) {
        item.decision?.complete(decision)
    }

    // #region a turn

    // the turn of [status] over, as [state]: its clock stopped, where it is
    private fun finish(status: ChatItem.Progress, state: ProgressState) {
        status.state = state
        status.elapsed = (System.currentTimeMillis() - status.started).coerceAtLeast(0L)
        if (progress === status) progress = null
        changed(status)
    }

    // what the status of the turn going on says the agent is doing now
    private fun doing(activity: TurnActivity) {
        val status = progress ?: return
        if (status.activity == activity) return
        status.activity = activity
        changed(status)
    }

    /**
     * A turn: requests and the calls of tools they ask for, until the model answers.
     *
     * @return whether it answered: false if the turn ended before (a request failed, no
     *   answer, cut off, too many rounds)
     */
    private suspend fun converse(config: AgentSettings.Config): Boolean {
        val root = host.workspaceRoot()
        workspaceRoot = root
        model = config.model
        if (root == null) {
            // (the question stays, unanswered: it goes with the next one)
            add(ChatItem.Notice(context.getString(R.string.agent_no_workspace), isError = true))
            return false
        }
        val workspace = Workspace(context, root)
        val tools = AgentTools(context, workspace)
        val provider = config.provider()
        // the same for every request of the chat: what the provider caches begins with them
        val system = systemPrompt(workspace)
        val specs = tools.all.map { it.spec }
        addEditorState(workspace)
        val turn = Turn(provider, system, specs, ContextBudget(AgentSettings.contextWindow(context, config), config.maxTokens))
        var rounds = 0
        var overflows = 0
        while (rounds < MAX_ROUNDS) {
            fit(turn, force = false)
            val request = LlmRequest(system, AgentContext.view(history, contextState), specs, config.maxTokens)
            // a new request: the model is to think again (what it thought before stays)
            doing(TurnActivity.Thinking)
            val stream = Stream()
            var failure: LlmException? = null
            val reply = try {
                provider.complete(request) { event -> stream.onEvent(event) }
            } catch (e: CancellationException) {
                stream.flush()
                val said = stream.end()
                // what came before the stop stays said (the API wants turns to alternate)
                history += Message(Role.ASSISTANT, listOf(Block.Text(said.ifBlank { "(stopped)" })))
                throw e
            } catch (e: LlmException) {
                failure = e
                null
            }
            stream.flush()
            stream.end()
            if (reply == null) {
                val e = failure ?: return false
                // too long for the model after all (the estimates are estimates, the window
                // may not be what it was taken to be): cut down, and asked again
                val overflow = AgentContext.overflowOf(e)
                if (overflow != null && overflows < MAX_OVERFLOWS) {
                    overflows++
                    overflow.limit?.let { limit ->
                        AgentSettings.learnContextWindow(context, config, limit)
                        if (limit < turn.budget.window) turn.budget = ContextBudget(limit, config.maxTokens)
                    }
                    // what it was, if it said; else at least a little more than the window
                    val used = overflow.used
                    val window = turn.budget.window
                    withContext(Dispatchers.Default) {
                        if (used != null) calibrate(used, request) else calibrate(window + window / 10, request, raiseOnly = true)
                    }
                    if (fit(turn, force = true)) continue
                }
                add(ChatItem.Notice(context.getString(R.string.agent_request_failed, e.message.orEmpty()), isError = true))
                return false
            }
            overflows = 0
            rounds++
            withContext(Dispatchers.Default) { noteUsage(request, reply) }
            // (thinking alone, and nothing said: no answer either)
            if (!reply.message.saysSomething) {
                add(ChatItem.Notice(context.getString(R.string.agent_empty_reply), isError = true))
                history += Message(Role.ASSISTANT, listOf(Block.Text("(no answer)")))
                return false
            }
            history += reply.message
            val uses = reply.message.toolUses
            if (uses.isEmpty()) {
                if (reply.stopReason == StopReason.MAX_TOKENS) {
                    add(ChatItem.Notice(context.getString(R.string.agent_cut_off), isError = false))
                    return false
                }
                return true
            }
            val results = ArrayList<Block>()
            try {
                for (use in uses) results += runTool(use, tools, config)
            } finally {
                // stopped half way: a result for each call all the same (the API wants one)
                for (use in uses.drop(results.size)) {
                    results += Block.ToolResult(use.id, "Not run: the user stopped the agent", isError = true)
                }
                history += Message(Role.USER, results)
            }
        }
        add(ChatItem.Notice(context.getString(R.string.agent_too_many_rounds, MAX_ROUNDS), isError = false))
        return false
    }

    // What a reply streams in, gathered on the thread that reads it and shown on the main
    // one, a frame's worth at a time, in the order it came: the text, as the answer (an
    // item made once there is some: [item]); the thinking, and the tools asked for, as
    // what the status of the turn says (the thinking kept in it too, to be unfolded).
    private inner class Stream {
        // what came since the last flush, the pieces of text and of thinking that follow
        // one another joined
        private val pending = ArrayList<LlmEvent>()
        private var posted = false

        /** The answer of this reply as shown; null: no text yet. Main thread. */
        var item: ChatItem.Assistant? = null
            private set

        // whether what was shown last of the reply is thinking with something in it: a
        // tool asked for right after it, it says what that is for (the models of Anthropic
        // write a note before a call: what they found, what they do next)
        private var thoughtLast = false

        fun onEvent(event: LlmEvent) {
            synchronized(pending) {
                val last = pending.lastOrNull()
                when {
                    event is LlmEvent.TextDelta && last is LlmEvent.TextDelta ->
                        pending[pending.lastIndex] = LlmEvent.TextDelta(last.text + event.text)
                    event is LlmEvent.ThinkingDelta && last is LlmEvent.ThinkingDelta ->
                        pending[pending.lastIndex] = LlmEvent.ThinkingDelta(last.text + event.text)
                    else -> pending += event
                }
                if (!posted) {
                    posted = true
                    main.postDelayed({ flush() }, FLUSH_MS)
                }
            }
        }

        fun flush() {
            val events = synchronized(pending) {
                posted = false
                pending.toList().also { pending.clear() }
            }
            if (events.isEmpty()) return
            val status = progress
            var textChanged = false
            var statusChanged = false
            for (event in events) {
                when (event) {
                    is LlmEvent.TextDelta -> {
                        val shown = item ?: ChatItem.Assistant().also {
                            item = it
                            add(it)
                        }
                        shown.text.append(event.text)
                        textChanged = true
                        thoughtLast = false
                        if (status != null) {
                            status.activity = TurnActivity.Answering
                            statusChanged = true
                        }
                    }
                    is LlmEvent.ThinkingStarted -> {
                        thoughtLast = false
                        if (status != null) {
                            status.thinkingStarted()
                            status.activity = TurnActivity.Thinking
                            statusChanged = true
                        }
                    }
                    is LlmEvent.ThinkingDelta -> if (status != null) {
                        status.addThought(event.text)
                        val headline = AgentProgress.headline(status.lastThought)
                        status.activity = headline?.let { TurnActivity.Thought(it) } ?: TurnActivity.Thinking
                        thoughtLast = headline != null
                        statusChanged = true
                    }
                    is LlmEvent.ToolStarted -> {
                        if (status != null && !thoughtLast) {
                            status.activity = TurnActivity.Preparing(event.name)
                            statusChanged = true
                        }
                    }
                }
            }
            if (textChanged) item?.let { changed(it) }
            if (statusChanged && status != null) changed(status)
        }

        /**
         * The reply streamed all it will: its answer shown as done, or taken away if it said
         * nothing.
         *
         * @return what it said
         */
        fun end(): String {
            val shown = item ?: return ""
            shown.streaming = false
            if (shown.text.isBlank()) remove(shown) else changed(shown)
            return shown.text.toString()
        }
    }

    private suspend fun runTool(use: Block.ToolUse, tools: AgentTools, config: AgentSettings.Config): Block.ToolResult {
        val tool = tools.find(use.name)
        val item = ChatItem.Tool(use.name, tool?.let { runCatching { it.summary(use.input) }.getOrNull() }.orEmpty(), tool?.risk ?: ToolRisk.READ)
        add(item)
        doing(TurnActivity.Running(use.name, item.summary))
        if (tool == null) {
            item.state = ToolState.FAILED
            item.result = "unknown tool"
            changed(item)
            return Block.ToolResult(use.id, "There is no tool \"${use.name}\"", isError = true)
        }
        try {
            if (tool.risk != ToolRisk.READ) {
                item.preview = tool.preview(use.input)
                changed(item)
            }
            if (config.asks(tool.risk) && tool.risk !in allowedInChat) {
                val decision = CompletableDeferred<Decision>()
                item.decision = decision
                item.state = ToolState.WAITING
                changed(item)
                doing(TurnActivity.Waiting(use.name, item.summary))
                val answer = try {
                    decision.await()
                } finally {
                    item.decision = null
                }
                when (answer) {
                    Decision.DENY -> {
                        item.state = ToolState.DENIED
                        changed(item)
                        return Block.ToolResult(
                            use.id,
                            "The user did not allow this call. Do not try to get around it: ask them what they want instead.",
                            isError = true,
                        )
                    }
                    Decision.ALLOW_IN_CHAT -> allowedInChat += tool.risk
                    Decision.ALLOW -> Unit
                }
            }
            item.state = ToolState.RUNNING
            changed(item)
            doing(TurnActivity.Running(use.name, item.summary))
            val output = LiveOutput(item)
            val outcome = try {
                tool.run(use.input, output)
            } finally {
                output.close()
            }
            item.state = ToolState.DONE
            item.result = outcome.content
            item.viewFile = outcome.changedFile ?: outcome.shownFile
            outcome.changedFile?.let { file ->
                item.file = file
                host.onFileChanged(file)
            }
            changed(item)
            return Block.ToolResult(use.id, outcome.content)
        } catch (e: CancellationException) {
            item.state = ToolState.STOPPED
            changed(item)
            throw e
        } catch (e: ToolException) {
            return failed(item, use, e.message ?: e.toString())
        } catch (e: Exception) {
            return failed(item, use, e.toString())
        }
    }

    // What a call prints as it runs, onto its card ([item].live): the last LIVE_KEEP_CHARS
    // of it, the card told at most every LIVE_INTERVAL_MS (on the main thread); taken off
    // the card once the call is done ([close]), when what it came to is shown instead.
    private inner class LiveOutput(private val item: ChatItem.Tool) : ToolProgress {
        private val buffer = StringBuilder()
        private var posted = false
        @Volatile private var closed = false

        override fun output(text: String) {
            if (closed || text.isEmpty()) return
            synchronized(buffer) {
                buffer.append(text)
                if (buffer.length > LIVE_KEEP_CHARS * 2) buffer.delete(0, buffer.length - LIVE_KEEP_CHARS)
                if (posted) return
                posted = true
            }
            main.postDelayed({
                val shown = synchronized(buffer) {
                    posted = false
                    if (buffer.length > LIVE_KEEP_CHARS) buffer.delete(0, buffer.length - LIVE_KEEP_CHARS)
                    buffer.toString()
                }
                if (closed || item.state != ToolState.RUNNING) return@postDelayed
                item.live = shown
                changed(item)
            }, LIVE_INTERVAL_MS)
        }

        fun close() {
            closed = true
            item.live = null
        }
    }

    private fun failed(item: ChatItem.Tool, use: Block.ToolUse, message: String): Block.ToolResult {
        item.state = ToolState.FAILED
        item.result = message
        changed(item)
        return Block.ToolResult(use.id, message, isError = true)
    }

    // What the model is told of where it is and how to work: nothing that changes from one
    // request to the next (what the editor shows goes with the questions: addEditorState)
    private fun systemPrompt(workspace: Workspace): String {
        val out = StringBuilder()
        out.append(
            """
            You are the coding agent of X Editor, a code editor on Android. You work on the user's project through tools.

            Workspace root: ${workspace.root.path}
            All paths you give to tools are relative to this root.
            """.trimIndent()
        ).append("\n")
        out.append(
            """

            How to work:
            - Look before you change anything: list_files, search_files and read_file the parts you need. Never guess what a file holds.
            - Change files with edit_file (an exact replacement, a few lines of context to make it unique) or write_file (new files, or rewriting a small one). Keep changes small and in the style of the code around them.
            - run_command runs a shell command in the workspace root: in the app (/system/bin/sh, with the compilers and tools the user installed into the app) when the app has every program it runs, otherwise in Termux (bash, with what the user installed there with pkg), if Termux is installed and allowed. Its answer says where it ran. No root, nothing interactive, no network unless the tool itself fetches.
            - The user sees every tool call, and must allow some of them. If they refuse one, do not try to get around it: ask what they want.
            - Files the user attaches come with their message: images as images, the text of text files, what an archive holds (unpacked). They are kept outside the workspace, at the paths their <attachment> tags give: read_file, list_files and search_files take those paths (to read only), and run_command can copy them into the workspace.
            - A message of the user may end with an <editor_state>, which the app adds: what the editor showed when they sent it (the file open, the line of the cursor, the text selected). It holds until a later one says otherwise.
            - As the conversation grows long, large tool results and attachments from earlier on are left out of it (a line says what was there), and later what came before is replaced by a summary you write. Read files again rather than guess what they held.
            - When you are done, say briefly what you changed and why. Answer in the language the user writes in.
            """.trimIndent()
        )
        return out.toString()
    }

    // #endregion

    // #region what the model is sent

    /**
     * What every request of a turn has: the [provider] (with its model and its thinking),
     * the [system] prompt, the tools, and the [budget] of the model's context (made smaller
     * if a request finds it is: converse). [compactFailed]: a summary was asked for in this
     * turn and could not be had (not asked again, unless the model refuses what is sent).
     */
    private class Turn(val provider: LlmProvider, val system: String, val specs: List<ToolSpec>, var budget: ContextBudget) {
        var compactFailed = false
    }

    /**
     * What the editor shows, as the question just sent is to go with it ([Block.Context] at
     * the end of the last message, the user's): where the user is, for the model to know.
     * Not in the system prompt, which is to stay the same from one request to the next (it
     * is the start of what the provider caches); and only when it changed since the last
     * question said it.
     */
    private fun addEditorState(workspace: Workspace) {
        val last = history.lastOrNull() ?: return
        if (last.role != Role.USER) return
        val said = history.asReversed().firstNotNullOfOrNull { message ->
            message.blocks.lastOrNull { it is Block.Context }?.let { (it as Block.Context).text }
        }
        val now = editorStateText(workspace) ?: if (said != null && said != NO_FILE_OPEN) NO_FILE_OPEN else null
        if (now == null || now == said) return
        history[history.lastIndex] = Message(Role.USER, last.blocks + Block.Context(now))
    }

    // what the editor shows, in the workspace; null: no file of it is open
    private fun editorStateText(workspace: Workspace): String? {
        val editor = host.editorState() ?: return null
        if (!workspace.isInside(editor.file)) return null
        val out = StringBuilder("<editor_state>\n")
        out.append("The user has ").append(workspace.relative(editor.file))
            .append(" open in the editor, the cursor on line ").append(editor.line).append(".\n")
        editor.selection?.takeIf { it.isNotBlank() }?.let {
            out.append("They have selected this text in it:\n```\n").append(it.take(MAX_SELECTION)).append("\n```\n")
        }
        return out.append("</editor_state>").toString()
    }

    /**
     * What the next request of [turn] is to be sent brought within the model's context:
     * if it is past the mark where that is to be done (ContextBudget.clearAt), the large old
     * results of tools and the files attached before are left out; if that is not enough
     * (ContextBudget.compactAt), the older part of the conversation is summarized ([compact]).
     * [force]: the model refused what was sent as too long: whatever it takes.
     *
     * @return whether what is sent was cut down
     */
    private suspend fun fit(turn: Turn, force: Boolean): Boolean {
        val all = history.toList()
        val state = contextState
        val budget = turn.budget
        val size = withContext(Dispatchers.Default) { estimate(all, state, turn) }
        if (!force && size <= budget.clearAt) return false
        val needed = ((size - budget.clearTo) / tokenRatio).toInt()
        val cleared = withContext(Dispatchers.Default) { AgentContext.clearing(all, state, needed) }
        var after = size
        if (cleared != state) {
            // (what the thinking so far was thought after is no longer what is sent)
            contextState = cleared.copy(thinkingDropped = all.size)
            counted = null
            after = withContext(Dispatchers.Default) { estimate(all, contextState, turn) }
        }
        val changed = cleared != state
        // enough, as far as can be told; refused though nothing more could be left out: a summary
        if (after <= budget.compactAt && (changed || !force)) return changed
        if (turn.compactFailed && !force) return changed
        return compact(all, turn, force) || changed
    }

    /**
     * The older part of the conversation summarized by the model (AgentContext.compaction:
     * before the turn going on, or all of it), and sent in its place from now on. The status
     * of the turn says so as it happens. If the model gives no summary: when [force]d (what
     * is sent is too long for it), that part is left out all the same, and said to be; when
     * not, it stays (and is not asked again in this turn: [Turn.compactFailed]).
     *
     * @return whether what is sent was cut down
     */
    private suspend fun compact(all: List<Message>, turn: Turn, force: Boolean): Boolean {
        val state = contextState
        val budget = turn.budget
        val base = AgentContext.tokens(turn.system, turn.specs)
        // the request for it is to fit with its answer, a little kept spare
        val room = budget.window - budget.summaryTokens - budget.window / 50 - SUMMARY_REQUEST_TOKENS
        val plan = withContext(Dispatchers.Default) {
            AgentContext.compaction(all, state, budget, tokenRatio, base, room)
        } ?: return false
        doing(TurnActivity.Compacting)
        var reason: String? = null
        val summary = try {
            val request = LlmRequest(turn.system, plan.messages, turn.specs, budget.summaryTokens, turnStart = plan.turnStart)
            turn.provider.complete(request) {}.message.text.trim().ifEmpty { null }
        } catch (e: LlmException) {
            reason = e.message
            null
        } finally {
            doing(TurnActivity.Thinking)
        }
        if (summary == null && !force) {
            turn.compactFailed = true
            return false
        }
        val text = if (summary != null) {
            AgentContext.summaryText(summary, plan.everything)
        } else {
            // the question going on is left out with the rest: said, for the model to go on
            val question = if (plan.everything) all.lastOrNull { it.role == Role.USER && it.text.isNotBlank() }?.text else null
            AgentContext.droppedText(question)
        }
        // (what the thinking so far was thought after is no longer what is sent)
        contextState = state.copy(summary = text, summarized = plan.cut, thinkingDropped = all.size)
        counted = null
        add(
            if (summary != null) {
                ChatItem.Notice(context.getString(R.string.agent_compacted), isError = false)
            } else {
                ChatItem.Notice(context.getString(R.string.agent_compact_failed, reason ?: context.getString(R.string.agent_empty_reply)), isError = true)
            }
        )
        return true
    }

    // About how many tokens a request of [turn] would be, sent [all] as [state] has it: the
    // estimate of all of it, or what the provider counted of what it begins with and the
    // estimate of what came since, whichever is more (the estimates are corrected by
    // [tokenRatio] as they are).
    private fun estimate(all: List<Message>, state: ContextState, turn: Turn): Int {
        val view = AgentContext.view(all, state)
        val estimated = ((AgentContext.tokens(turn.system, turn.specs) + AgentContext.tokens(view)) * tokenRatio).toInt()
        val known = counted
        if (known == null || known.state != state || known.count > view.size) return estimated
        // a question since: the thinking of the turn counted is no longer sent, nor in the count
        if (AgentContext.turnStart(view) >= known.count) return estimated
        val since = (AgentContext.tokens(view.subList(known.count, view.size)) * tokenRatio).toInt()
        return max(estimated, known.tokens + since)
    }

    // What [reply] to [request] says it took: for the next requests to be counted from it,
    // and the estimates corrected by it.
    private fun noteUsage(request: LlmRequest, reply: LlmReply) {
        val usage = reply.usage ?: return
        val sent = usage.inputTokens ?: return
        calibrate(sent, request)
        val written = usage.outputTokens ?: AgentContext.tokens(reply.message)
        // (the reply is the next message: it is in what the next request begins with)
        counted = Counted(contextState, request.messages.size + 1, sent + written)
    }

    // the estimates corrected by what [request] was counted as, [tokens] (within reason: the
    // count may be of something else than was estimated, the thinking handed back say);
    // [raiseOnly]: [tokens] is only the least it was, a correction down is none
    private fun calibrate(tokens: Int, request: LlmRequest, raiseOnly: Boolean = false) {
        val estimated = AgentContext.tokens(request.system, request.tools) + AgentContext.tokens(request.messages)
        if (estimated < MIN_CALIBRATION_TOKENS || tokens <= 0) return
        val ratio = (tokens.toDouble() / estimated).coerceIn(MIN_RATIO, MAX_RATIO)
        tokenRatio = if (raiseOnly) max(tokenRatio, ratio) else ratio
    }

    // #endregion

    // #region the items

    // [item] at the end of the chat: before the status of the turn going on, which stays last
    private fun add(item: ChatItem) {
        val status = progress
        val at = if (status != null && item !== status) items.indexOf(status).takeIf { it >= 0 } ?: items.size else items.size
        items.add(at, item)
        listener?.onItemInserted(at)
    }

    private fun changed(item: ChatItem) {
        val index = items.indexOf(item)
        if (index >= 0) listener?.onItemChanged(index)
    }

    private fun remove(item: ChatItem) {
        val index = items.indexOf(item)
        if (index < 0) return
        items.removeAt(index)
        listener?.onItemRemoved(index)
    }

    // #endregion

    companion object {
        /** Requests in one turn at most (each answer with tools makes another). */
        const val MAX_ROUNDS = 40
        // how many times in a row a request refused as too long is cut down and sent again
        private const val MAX_OVERFLOWS = 2
        // what the question asking for a summary is, about (AgentContext.summaryRequest)
        private const val SUMMARY_REQUEST_TOKENS = 600
        // the estimates are corrected by requests of at least this many (estimated) tokens, within these
        private const val MIN_CALIBRATION_TOKENS = 1_000
        private const val MIN_RATIO = 0.4
        private const val MAX_RATIO = 2.5
        // what is said when the file that was open no longer is (addEditorState)
        private const val NO_FILE_OPEN = "<editor_state>\nNo file of the workspace is open in the editor.\n</editor_state>"
        // what a command prints as it runs, on its card: how much is kept, how often it is shown
        const val LIVE_KEEP_CHARS = 8_000
        const val LIVE_INTERVAL_MS = 150L
        private const val FLUSH_MS = 48L
        private const val MAX_SELECTION = 4000
    }
}
