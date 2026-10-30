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

package x.editor.app.shortcut

import android.content.Context
import android.util.Log

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

import java.io.File

import x.editor.app.AppDirs
import x.editor.app.R
import x.editor.app.lsp.FriendlySnippets

/**
 * What a shortcut can run, by the name the keymap gives it (`"action": "save"`).
 * [EDITOR] commands act on the editor shown, and only while no other text field (the
 * search panel) has the focus, which has its own Ctrl+C and so on; [GLOBAL] ones
 * whatever has it.
 *
 * @property repeatable whether holding the keys repeats it (undo does, saving does not)
 */
enum class ShortcutCommand(
    val id: String,
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val group: ShortcutGroup,
    val scope: Scope,
    val repeatable: Boolean = false
) {
    COPY("copy", R.string.selection_copy, R.drawable.ic_content_copy, ShortcutGroup.EDIT, Scope.EDITOR),
    CUT("cut", R.string.selection_cut, R.drawable.ic_content_cut, ShortcutGroup.EDIT, Scope.EDITOR),
    PASTE("paste", R.string.selection_paste, R.drawable.ic_content_paste, ShortcutGroup.EDIT, Scope.EDITOR, repeatable = true),
    SELECT_ALL("select_all", R.string.selection_select_all, R.drawable.ic_select_all, ShortcutGroup.EDIT, Scope.EDITOR),
    UNDO("undo", R.string.action_undo, R.drawable.ic_undo, ShortcutGroup.EDIT, Scope.EDITOR, repeatable = true),
    REDO("redo", R.string.action_redo, R.drawable.ic_redo, ShortcutGroup.EDIT, Scope.EDITOR, repeatable = true),
    TOGGLE_COMMENT("toggle_comment", R.string.shortcut_toggle_comment, R.drawable.ic_toggle_comment, ShortcutGroup.CODE, Scope.EDITOR),
    FORMAT("format", R.string.lsp_action_format, R.drawable.ic_lsp_format, ShortcutGroup.CODE, Scope.EDITOR),
    OUTLINE("outline", R.string.lsp_action_outline, R.drawable.ic_lsp_outline, ShortcutGroup.CODE, Scope.EDITOR),
    RUN("run", R.string.action_run, R.drawable.ic_run, ShortcutGroup.CODE, Scope.GLOBAL),
    CONSOLE("console", R.string.action_console, R.drawable.ic_console, ShortcutGroup.CODE, Scope.GLOBAL),
    SAVE("save", R.string.action_save_file, R.drawable.ic_save, ShortcutGroup.FILE, Scope.GLOBAL),
    SEARCH("search", R.string.action_search, R.drawable.ic_search, ShortcutGroup.FILE, Scope.GLOBAL),
    GOTO_LINE("goto_line", R.string.action_goto_line, R.drawable.ic_goto_line, ShortcutGroup.FILE, Scope.GLOBAL),
    WORDWRAP("wordwrap", R.string.action_wordwrap, R.drawable.ic_wrap_text, ShortcutGroup.FILE, Scope.GLOBAL),
    READ_ONLY("read_only", R.string.shortcut_read_only, R.drawable.ic_read_only, ShortcutGroup.FILE, Scope.GLOBAL);

    enum class Scope { EDITOR, GLOBAL }

    companion object {
        fun byId(id: String): ShortcutCommand? = entries.firstOrNull { it.id == id }
    }
}

/** The groups the commands are listed in (ShortcutsSheet). */
enum class ShortcutGroup(@StringRes val title: Int) {
    EDIT(R.string.shortcut_group_edit),
    CODE(R.string.shortcut_group_code),
    FILE(R.string.shortcut_group_file)
}

/** What keys do: a command, or text put in at the cursor. */
sealed interface ShortcutAction {
    val scope: ShortcutCommand.Scope
    val repeatable: Boolean

    data class Run(val command: ShortcutCommand) : ShortcutAction {
        override val scope get() = command.scope
        override val repeatable get() = command.repeatable
    }

    /**
     * [body] in the syntax of snippets ($1, ${1:default}, $0, ${TM_SELECTED_TEXT}, ...),
     * in place of the selection or at the cursor (LspController.insertSnippet).
     */
    data class Insert(val body: String) : ShortcutAction {
        override val scope get() = ShortcutCommand.Scope.EDITOR
        override val repeatable get() = false
    }
}

/** [keys], one after the other, do [action]; [desc] says what for (as in Neovim). */
data class KeyMapping(val keys: List<KeyStroke>, val action: ShortcutAction, val desc: String = "")

/**
 * Every shortcut: the defaults, with the user's (keymap.json) over them. Keys may be a
 * sequence (`<C-k>c`): after a key that only begins some, the next is waited for, at
 * most [timeoutMillis] (Neovim's timeoutlen); see KeySequencer.
 */
class Keymap(
    mappings: List<KeyMapping>,
    val timeoutMillis: Long,
    /** What `<leader>` stands for. */
    val leader: List<KeyStroke> = DEFAULT_LEADER
) {

    private val byKeys: Map<List<KeyStroke>, KeyMapping> = mappings.associateBy { it.keys }

    val mappings: Collection<KeyMapping> get() = byKeys.values

    /** What exactly [keys] do. */
    fun exact(keys: List<KeyStroke>): KeyMapping? = byKeys[keys]

    /** Whether some keys begin with [keys] and go on. */
    fun hasLonger(keys: List<KeyStroke>): Boolean =
        byKeys.keys.any { it.size > keys.size && it.subList(0, keys.size) == keys }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 1000L
        const val DEFAULT_LEADER_TEXT = "<C-k>"
        val DEFAULT_LEADER: List<KeyStroke> = KeyStroke.parseSequence(DEFAULT_LEADER_TEXT, null).getOrThrow()

        /** The keys every keymap starts from, as written in the file. */
        val DEFAULTS: List<Pair<String, ShortcutCommand>> = listOf(
            "<C-c>" to ShortcutCommand.COPY,
            "<C-x>" to ShortcutCommand.CUT,
            "<C-v>" to ShortcutCommand.PASTE,
            "<C-a>" to ShortcutCommand.SELECT_ALL,
            "<C-z>" to ShortcutCommand.UNDO,
            "<C-y>" to ShortcutCommand.REDO,
            "<C-S-z>" to ShortcutCommand.REDO,
            "<C-/>" to ShortcutCommand.TOGGLE_COMMENT,
            "<C-S-f>" to ShortcutCommand.FORMAT,
            "<C-S-o>" to ShortcutCommand.OUTLINE,
            "<F5>" to ShortcutCommand.RUN,
            "<C-`>" to ShortcutCommand.CONSOLE,
            "<C-s>" to ShortcutCommand.SAVE,
            "<C-f>" to ShortcutCommand.SEARCH,
            "<C-g>" to ShortcutCommand.GOTO_LINE,
            "<A-z>" to ShortcutCommand.WORDWRAP,
        )

        val DEFAULT: Keymap = Keymap(defaultMappings(), DEFAULT_TIMEOUT_MILLIS)

        internal fun defaultMappings(): List<KeyMapping> = DEFAULTS.map { (keys, command) ->
            KeyMapping(KeyStroke.parseSequence(keys, null).getOrThrow(), ShortcutAction.Run(command))
        }
    }
}

/**
 * The user's keymap, a file edited in the editor as in Neovim (keymap.json, next to the
 * folder of the language servers), read again when it is saved:
 *
 * ```jsonc
 * {
 *   "leader": "<C-k>",          // what <leader> stands for
 *   "timeoutlen": 1000,         // ms to wait for the next key of a sequence
 *   "keymaps": [
 *     { "keys": "<C-s>", "action": "save" },
 *     { "keys": "<leader>f", "action": "format", "desc": "Format" },
 *     { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**" },
 *     { "keys": "<C-y>", "action": "nop" }      // a default taken away
 *   ]
 * }
 * ```
 *
 * The defaults ([Keymap.DEFAULTS]) come first; a mapping of the same keys replaces one,
 * `"nop"` takes it away. Comments and trailing commas are allowed. A hardware keyboard,
 * a soft keyboard's Ctrl or Alt keys, and Ctrl and Alt of the row of extra keys all
 * press the same keys (KeyStroke).
 */
object KeymapFile {

    private const val TAG = "KeymapFile"
    const val FILE = "keymap.json"
    private const val TEMPLATE = "keymap_template.jsonc"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    /** What reading it came to. */
    sealed interface Read {
        /** [count] mappings of the file taken; [problems]: those left out, and why. */
        data class Ok(val keymap: Keymap, val count: Int, val problems: List<String>) : Read
        /** Not JSON (at [line], 0 if not known): the defaults stay. */
        data class Failed(val line: Int, val message: String) : Read
    }

    fun file(context: Context): File = File(AppDirs.external(context), FILE)

    /** Whether [file] is the keymap. Not on the main thread. */
    fun isKeymapFile(context: Context, file: File): Boolean =
        runCatching { file.canonicalFile == file(context).canonicalFile }.getOrDefault(false)

    /** The keymap, written from a template that lists the defaults if it is not there. Not on the main thread. */
    fun open(context: Context): File {
        val file = file(context)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val template = context.assets.open(TEMPLATE).bufferedReader().use { it.readText() }
            file.writeText(template.replace("{{defaults}}", defaultsListing()).replace("{{actions}}", actionsListing()))
        }
        return file
    }

    /** The keymap as it is now: the defaults if there is no file, or it cannot be read. Not on the main thread. */
    fun load(context: Context): Keymap = when (val read = read(context)) {
        is Read.Ok -> read.keymap
        is Read.Failed -> {
            Log.w(TAG, "keymap: line ${read.line}: ${read.message}")
            Keymap.DEFAULT
        }
    }

    /** Reads the file, to tell the user who saved it what came of it. Not on the main thread. */
    fun read(context: Context): Read {
        val file = file(context)
        if (!file.isFile) return Read.Ok(Keymap.DEFAULT, 0, emptyList())
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return Read.Failed(0, e.message ?: e.toString())
        }
        return try {
            parse(text)
        } catch (e: Exception) {
            Read.Failed(FriendlySnippets.errorLine(text, e.message), FriendlySnippets.errorMessage(e))
        }
    }

    // @throws IllegalArgumentException (SerializationException is one) if it is not JSON
    internal fun parse(text: String): Read.Ok {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("the keymap is not an object { ... }")
        val problems = ArrayList<String>()

        val leaderText = (root["leader"] as? JsonPrimitive)?.contentOrNull ?: Keymap.DEFAULT_LEADER_TEXT
        val leader = KeyStroke.parseSequence(leaderText, null).getOrElse {
            problems += "leader: ${it.message}"
            Keymap.DEFAULT_LEADER
        }
        val timeout = (root["timeoutlen"] as? JsonPrimitive)?.intOrNull?.toLong()
            ?.coerceIn(100L, 10_000L) ?: Keymap.DEFAULT_TIMEOUT_MILLIS

        val mappings = LinkedHashMap<List<KeyStroke>, KeyMapping>()
        Keymap.defaultMappings().forEach { mappings[it.keys] = it }

        var count = 0
        val entries = root["keymaps"] as? JsonArray ?: JsonArray(emptyList())
        entries.forEachIndexed { index, element ->
            val entry = element as? JsonObject
            val where = "keymaps[$index]"
            if (entry == null) {
                problems += "$where: not an object { ... }"
                return@forEachIndexed
            }
            val keysText = (entry["keys"] as? JsonPrimitive)?.contentOrNull
            if (keysText.isNullOrEmpty()) {
                problems += "$where: no \"keys\""
                return@forEachIndexed
            }
            val keys = KeyStroke.parseSequence(keysText, leader).getOrElse {
                problems += "$where \"$keysText\": ${it.message}"
                return@forEachIndexed
            }
            if (!keys.first().canStart) {
                problems += "$where \"$keysText\": the first key needs Ctrl, Alt or Meta, or F1–F12 " +
                    "(a plain key would get in the way of typing)"
                return@forEachIndexed
            }
            val desc = (entry["desc"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val actionName = (entry["action"] as? JsonPrimitive)?.contentOrNull
            val insert = (entry["insert"] as? JsonPrimitive)?.contentOrNull
            val action: ShortcutAction? = when {
                actionName == "nop" -> null
                actionName != null -> ShortcutCommand.byId(actionName)?.let { ShortcutAction.Run(it) } ?: run {
                    problems += "$where \"$keysText\": unknown action \"$actionName\""
                    return@forEachIndexed
                }
                !insert.isNullOrEmpty() -> ShortcutAction.Insert(insert)
                else -> {
                    problems += "$where \"$keysText\": needs \"action\" or \"insert\""
                    return@forEachIndexed
                }
            }
            if (action == null) mappings.remove(keys) else mappings[keys] = KeyMapping(keys, action, desc)
            count++
        }
        return Read.Ok(Keymap(mappings.values.toList(), timeout, leader), count, problems)
    }

    // the defaults, as comment lines of the template
    private fun defaultsListing(): String = Keymap.DEFAULTS.joinToString("\n") { (keys, command) ->
        "//   ${keys.padEnd(10)} ${command.id}"
    }

    private fun actionsListing(): String =
        ShortcutCommand.entries.map { it.id }.plus("nop").chunked(6).joinToString("\n") { line ->
            "//   " + line.joinToString(", ")
        }
}
