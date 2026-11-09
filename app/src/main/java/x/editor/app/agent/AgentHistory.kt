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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The conversations with the agent, kept on this device so that a chat can be taken up
 * again after the screen, or the app, is gone (AgentSheet: 🕘 in the head of the sheet).
 *
 * One file per chat, in the app's own files (`agent/chats`), and nothing of it leaves the
 * device. Each file is two lines: an [Entry] (what the list shows: the title, when it was
 * last written to, how long it is) and then the whole chat — both what the sheet shows
 * (ChatItem) and what the model is told of it ([Message]), so that the next question goes
 * on from where it stopped. The list reads the first line of each file only.
 *
 * The newest [MAX_CHATS] are kept; older ones go as new ones are saved. The files hold what
 * was said, so they are written and read off the main thread (AgentSheet).
 */
object AgentHistory {

    /** A chat in the list: enough of it to show without reading the whole file. */
    @Serializable
    data class Entry(
        val id: String,
        /** The first thing the user asked, cut short; empty if they asked nothing. */
        val title: String = "",
        /** The name the user gave it ([rename]); empty: the question above is what is shown. */
        val name: String = "",
        val updatedAt: Long = 0L,
        /** How many questions and answers are in it (the tool calls not counted). */
        val messages: Int = 0,
        val workspace: String? = null,
        val model: String? = null,
        /** Pinned ([setPinned]): kept at the top of the list, and never pruned. */
        val pinned: Boolean = false,
    ) {
        /** What the list shows: the name the user gave it, or the question it began with. */
        val shown: String get() = name.ifBlank { title }
    }

    /** The chats: the pinned ones first, and within each the one written to last first. */
    fun list(context: Context): List<Entry> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile || !file.name.endsWith(SUFFIX)) return@mapNotNull null
            // the first line only: the chat itself is read when it is taken up (load)
            runCatching { file.bufferedReader().use { it.readLine() }?.let(::entryOf) }.getOrNull()
        }.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.updatedAt })
    }

    /** The name a new chat is kept under (nothing is written yet). */
    fun newId(): String = "chat-" + System.currentTimeMillis() + "-" + (1000..9999).random()

    /**
     * [snapshot] kept as the chat [id], in place of what was there; the oldest chats beyond
     * [MAX_CHATS] are deleted. Nothing is written for a chat with nothing in it.
     *
     * @return what the list will show of it, null if it could not be written
     */
    fun save(context: Context, id: String, snapshot: AgentSession.Snapshot): Entry? {
        if (snapshot.items.isEmpty()) return null
        // what the user made of the chat himself is his, not the snapshot's: the name he
        // gave it and whether he pinned it are read from what is kept and written again
        val entry = entryFor(id, snapshot, kept(context, id))
        val written = runCatching {
            val file = File(dir(context), id + SUFFIX)
            // written whole and then put in place: a chat already kept is not lost half way
            val temporary = File(file.parentFile, file.name + TEMPORARY)
            temporary.writeText(encode(entry, snapshot))
            if (file.exists()) file.delete()
            temporary.renameTo(file)
        }.getOrDefault(false)
        if (!written) return null
        prune(context)
        return entry
    }

    /** The chat [id], whole; null if it is gone or cannot be read. */
    fun load(context: Context, id: String): AgentSession.Snapshot? = runCatching {
        val file = File(dir(context), id + SUFFIX)
        if (!file.isFile) null else decode(file.readText())
    }.getOrNull()

    /** The chat [id] deleted, and the files attached in it with it. */
    fun delete(context: Context, id: String) {
        runCatching { deleteFile(context, File(dir(context), id + SUFFIX)) }
    }

    // [file], a chat, deleted with the files attached in it (AgentAttachments)
    private fun deleteFile(context: Context, file: File) {
        runCatching { decode(file.readText()) }.getOrNull()?.let { chat ->
            attachmentsOf(chat).forEach { AgentAttachments.delete(context, it) }
        }
        file.delete()
    }

    /** The files attached in [chat]: to its messages as shown, and as the model is told of them. */
    internal fun attachmentsOf(chat: AgentSession.Snapshot): Set<AgentAttachment> {
        val out = LinkedHashSet<AgentAttachment>()
        chat.items.forEach { if (it is ChatItem.User) out += it.attachments }
        chat.messages.forEach { message -> message.blocks.forEach { if (it is Block.Attachment) out += it.attachment } }
        return out
    }

    /**
     * The chat [id] shown as [name] in the list from now on, in place of the question it
     * began with; blank: that question again. What was said in it is not touched.
     *
     * @return what the list will show of it, null if it is gone or could not be written
     */
    fun rename(context: Context, id: String, name: String): Entry? =
        update(context, id) { it.copy(name = name.trim().take(MAX_TITLE)) }

    /** The chat [id] pinned (at the top of the list, never pruned), or no longer. */
    fun setPinned(context: Context, id: String, pinned: Boolean): Entry? =
        update(context, id) { it.copy(pinned = pinned) }

    /** What is kept of the chat [id] in the list: its first line only. Null: none is. */
    private fun kept(context: Context, id: String): Entry? = runCatching {
        val file = File(dir(context), id + SUFFIX)
        if (!file.isFile) null else file.bufferedReader().use { it.readLine() }?.let(::entryOf)
    }.getOrNull()

    /**
     * The first line of the chat [id] put through [change] and written again, what was said
     * in it left exactly as it is (it is not decoded, only carried over).
     */
    private fun update(context: Context, id: String, change: (Entry) -> Entry): Entry? = runCatching {
        val file = File(dir(context), id + SUFFIX)
        if (!file.isFile) return null
        val text = file.readText()
        val entry = entryOf(text.substringBefore('\n')) ?: return null
        val changed = change(entry)
        // written whole and then put in place, as save does
        val temporary = File(file.parentFile, file.name + TEMPORARY)
        temporary.writeText(json.encodeToString(Entry.serializer(), changed) + "\n" + text.substringAfter('\n', ""))
        if (file.exists()) file.delete()
        if (temporary.renameTo(file)) changed else null
    }.getOrNull()

    /** Every chat deleted, and every file attached in them. */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
        runCatching { AgentAttachments.deleteAll(context) }
    }

    // #region what a chat file holds

    /**
     * What the list shows of [snapshot] kept as [id], as of now; [previous], what was kept
     * of it before: the name the user gave it and whether he pinned it are kept with it.
     */
    internal fun entryFor(id: String, snapshot: AgentSession.Snapshot, previous: Entry? = null) = Entry(
        id = id,
        title = titleOf(snapshot),
        name = previous?.name.orEmpty(),
        updatedAt = System.currentTimeMillis(),
        messages = snapshot.items.count { it is ChatItem.User || it is ChatItem.Assistant },
        workspace = snapshot.workspace?.path,
        model = snapshot.model,
        pinned = previous?.pinned ?: false,
    )

    /** A chat file: [entry] on its first line, the whole of [snapshot] on the second. */
    internal fun encode(entry: Entry, snapshot: AgentSession.Snapshot): String {
        val state = snapshot.context
        val body = Body(
            items = snapshot.items.map(::storedItem),
            messages = snapshot.messages.map(::storedMessage),
            // (nothing cut down: nothing written)
            context = if (state == ContextState()) null else StoredContext(state.resultsCleared, state.attachmentsCleared, state.summary, state.summarized, state.thinkingDropped),
        )
        return json.encodeToString(Entry.serializer(), entry) + "\n" + json.encodeToString(Body.serializer(), body)
    }

    /** The first line of a chat file; null if it is not one. */
    internal fun entryOf(line: String): Entry? =
        runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()

    /** The chat a file holds; null if it is not one. */
    internal fun decode(text: String): AgentSession.Snapshot? = runCatching {
        val head = text.substringBefore('\n')
        val rest = text.substringAfter('\n', "")
        val entry = json.decodeFromString(Entry.serializer(), head)
        val body = json.decodeFromString(Body.serializer(), rest)
        val messages = body.messages.map(::messageOf)
        AgentSession.Snapshot(
            items = body.items.mapNotNull(::itemOf),
            messages = messages,
            workspace = entry.workspace?.let(::File),
            model = entry.model,
            context = body.context?.let { stored ->
                ContextState(stored.resultsCleared, stored.attachmentsCleared, stored.summary, stored.summarized, stored.thinkingDropped)
                    .within(messages.size)
            } ?: ContextState(),
        )
    }.getOrNull()

    // #endregion

    // #region what is written

    @Serializable
    private data class Body(
        val items: List<StoredItem> = emptyList(),
        val messages: List<StoredMessage> = emptyList(),
        /** How what the model is sent of [messages] is cut down (ContextState); none: it is not. */
        val context: StoredContext? = null,
    )

    @Serializable
    private data class StoredContext(
        val resultsCleared: Int = 0,
        val attachmentsCleared: Int = 0,
        val summary: String? = null,
        val summarized: Int = 0,
        val thinkingDropped: Int = 0,
    )

    // a ChatItem of any kind: only what that kind uses is written (encodeDefaults is off)
    @Serializable
    private data class StoredItem(
        val type: String,
        val text: String = "",
        val name: String = "",
        val summary: String = "",
        val risk: String? = null,
        val state: String? = null,
        val preview: String? = null,
        val result: String? = null,
        val file: String? = null,
        val viewFile: String? = null,
        val error: Boolean = false,
        /** When the user sent it (ChatItem.User.time). */
        val time: Long = 0L,
        /** The files attached to it (ChatItem.User.attachments). */
        val attachments: List<StoredAttachment> = emptyList(),
        /** How long the turn took (ChatItem.Progress.elapsed). */
        val elapsed: Long = 0L,
    )

    @Serializable
    private data class StoredAttachment(
        val kind: String,
        val name: String = "",
        val size: Long = -1L,
        val mediaType: String = "",
        val path: String = "",
        val unpacked: String? = null,
        val text: String = "",
        val lines: Int = -1,
    )

    @Serializable
    private data class StoredMessage(val role: String, val blocks: List<StoredBlock> = emptyList())

    @Serializable
    private data class StoredBlock(
        val type: String,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
        val content: String? = null,
        val error: Boolean = false,
        val attachment: StoredAttachment? = null,
    )

    private fun storedItem(item: ChatItem): StoredItem = when (item) {
        is ChatItem.User -> StoredItem(type = USER, text = item.text, time = item.time, attachments = item.attachments.map(::storedAttachment))
        is ChatItem.Assistant -> StoredItem(type = ASSISTANT, text = item.text.toString())
        is ChatItem.Notice -> StoredItem(type = NOTICE, text = item.text, error = item.isError)
        // a turn still going on when kept (the sheet closed while it does) is kept as it is
        // now, stopped: a later keep has its end, unless the app is gone before
        is ChatItem.Progress -> StoredItem(
            type = PROGRESS,
            text = item.thought.toString().let { if (it.length > MAX_THOUGHT) "…" + it.takeLast(MAX_THOUGHT) else it },
            state = (if (item.state == ProgressState.RUNNING) ProgressState.STOPPED else item.state).name,
            time = item.started,
            elapsed = item.elapsedAt(System.currentTimeMillis()),
        )
        // a call that was waiting for the user has nobody to answer it any more: stopped
        is ChatItem.Tool -> StoredItem(
            type = TOOL,
            name = item.name,
            summary = item.summary,
            risk = item.risk.name,
            state = (if (item.state == ToolState.WAITING) ToolState.STOPPED else item.state).name,
            preview = item.preview,
            result = item.result,
            file = item.file?.path,
            viewFile = item.viewFile?.path,
        )
    }

    private fun itemOf(stored: StoredItem): ChatItem? = when (stored.type) {
        USER -> ChatItem.User(stored.text, stored.time, stored.attachments.mapNotNull(::attachmentOf))
        ASSISTANT -> ChatItem.Assistant().apply {
            text.append(stored.text)
            streaming = false
        }
        NOTICE -> ChatItem.Notice(stored.text, stored.error)
        PROGRESS -> ChatItem.Progress(stored.time).apply {
            state = ProgressState.entries.firstOrNull { it.name == stored.state && it != ProgressState.RUNNING } ?: ProgressState.STOPPED
            elapsed = stored.elapsed
            addThought(stored.text)
        }
        TOOL -> ChatItem.Tool(
            stored.name,
            stored.summary,
            ToolRisk.entries.firstOrNull { it.name == stored.risk } ?: ToolRisk.READ,
        ).apply {
            state = ToolState.entries.firstOrNull { it.name == stored.state } ?: ToolState.DONE
            preview = stored.preview
            result = stored.result
            file = stored.file?.let(::File)
            viewFile = stored.viewFile?.let(::File)
        }
        // written by a later version of the app: left out rather than shown wrongly
        else -> null
    }

    private fun storedMessage(message: Message) = StoredMessage(message.role.name, message.blocks.mapNotNull(::storedBlock))

    // the model's thinking is not kept: it is only handed back within the turn it was thought
    // in (AnthropicProvider), and a chat taken up again goes on with a new one
    private fun storedBlock(block: Block): StoredBlock? = when (block) {
        is Block.Text -> StoredBlock(type = TEXT, text = block.text)
        is Block.Context -> StoredBlock(type = CONTEXT, text = block.text)
        is Block.ToolUse -> StoredBlock(type = TOOL_USE, id = block.id, name = block.name, input = block.input)
        is Block.ToolResult -> StoredBlock(type = TOOL_RESULT, id = block.toolUseId, content = block.content, error = block.isError)
        is Block.Attachment -> StoredBlock(type = ATTACHMENT, attachment = storedAttachment(block.attachment))
        is Block.Thinking, is Block.RedactedThinking -> null
    }

    private fun storedAttachment(attachment: AgentAttachment) = StoredAttachment(
        kind = attachment.kind.name,
        name = attachment.name,
        size = attachment.size,
        mediaType = attachment.mediaType,
        path = attachment.path,
        unpacked = attachment.unpacked,
        text = attachment.text,
        lines = attachment.lines,
    )

    private fun attachmentOf(stored: StoredAttachment): AgentAttachment? {
        val kind = AgentAttachment.Kind.entries.firstOrNull { it.name == stored.kind } ?: return null
        return AgentAttachment(kind, stored.name, stored.size, stored.mediaType, stored.path, stored.unpacked, stored.text, stored.lines)
    }

    private fun messageOf(stored: StoredMessage) = Message(
        role = if (stored.role == Role.ASSISTANT.name) Role.ASSISTANT else Role.USER,
        blocks = stored.blocks.mapNotNull(::blockOf),
    )

    private fun blockOf(stored: StoredBlock): Block? = when (stored.type) {
        TEXT -> Block.Text(stored.text.orEmpty())
        CONTEXT -> Block.Context(stored.text.orEmpty())
        TOOL_USE -> Block.ToolUse(stored.id.orEmpty(), stored.name.orEmpty(), stored.input ?: JsonObject(emptyMap()))
        TOOL_RESULT -> Block.ToolResult(stored.id.orEmpty(), stored.content.orEmpty(), stored.error)
        ATTACHMENT -> stored.attachment?.let(::attachmentOf)?.let { Block.Attachment(it) }
        else -> null
    }

    // #endregion

    // the first question, its first line, cut short (the list shows one line of it); of
    // files sent alone, the name of the first
    private fun titleOf(snapshot: AgentSession.Snapshot): String {
        val first = snapshot.items.filterIsInstance<ChatItem.User>().firstOrNull()
        val asked = first?.text?.ifBlank { null } ?: first?.attachments?.firstOrNull()?.name.orEmpty()
        val line = asked.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= MAX_TITLE) line else line.take(MAX_TITLE).trimEnd() + "…"
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "agent/chats").apply { if (!isDirectory) mkdirs() }

    // The oldest beyond MAX_CHATS deleted, by the file's own time; a chat the user pinned is
    // kept whatever its age, and takes one of the places (its first line says it is pinned,
    // and only then is a line read at all).
    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: return
        if (files.size <= MAX_CHATS) return
        val (pinned, rest) = files.partition { file ->
            runCatching { file.bufferedReader().use { it.readLine() }?.let(::entryOf)?.pinned }.getOrNull() == true
        }
        val room = (MAX_CHATS - pinned.size).coerceAtLeast(0)
        rest.sortedByDescending { it.lastModified() }.drop(room).forEach { deleteFile(context, it) }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** How many chats are kept. */
    const val MAX_CHATS = 50

    private const val SUFFIX = ".json"
    private const val TEMPORARY = ".tmp"
    private const val MAX_TITLE = 80

    private const val USER = "user"
    private const val ASSISTANT = "assistant"
    private const val TOOL = "tool"
    private const val NOTICE = "notice"
    private const val PROGRESS = "progress"

    // of the thinking of a turn, at most the last this many characters are kept
    private const val MAX_THOUGHT = 100_000
    private const val TEXT = "text"
    private const val CONTEXT = "context"
    private const val TOOL_USE = "tool_use"
    private const val TOOL_RESULT = "tool_result"
    private const val ATTACHMENT = "attachment"
}
