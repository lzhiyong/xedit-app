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
import x.editor.app.config.JsoncSpans
import x.editor.app.config.TemplateText
import x.editor.app.config.Templates

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
    OPEN_FILE("open_file", R.string.action_open_file, R.drawable.ic_file, ShortcutGroup.FILE, Scope.GLOBAL),
    SEARCH("search", R.string.action_search, R.drawable.ic_search, ShortcutGroup.FILE, Scope.GLOBAL),
    GOTO_LINE("goto_line", R.string.action_goto_line, R.drawable.ic_goto_line, ShortcutGroup.FILE, Scope.GLOBAL),
    WORDWRAP("wordwrap", R.string.action_wordwrap, R.drawable.ic_wrap_text, ShortcutGroup.FILE, Scope.GLOBAL),
    FULLSCREEN("fullscreen", R.string.action_fullscreen, R.drawable.ic_fullscreen, ShortcutGroup.FILE, Scope.GLOBAL),
    READ_ONLY("read_only", R.string.shortcut_read_only, R.drawable.ic_read_only, ShortcutGroup.FILE, Scope.GLOBAL);

    enum class Scope { EDITOR, GLOBAL }

    companion object {
        fun byId(id: String): ShortcutCommand? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The groups the commands are listed in (the tabs of ShortcutsSheet); [id]: how a mapping
 * names one as its `"group"`.
 */
enum class ShortcutGroup(val id: String, @StringRes val title: Int) {
    EDIT("edit", R.string.shortcut_group_edit),
    CODE("code", R.string.shortcut_group_code),
    FILE("file", R.string.shortcut_group_file);

    companion object {
        /** How a mapping names the tab of the mappings that insert text. */
        const val INSERT_ID = "insert"

        fun byId(id: String): ShortcutGroup? = entries.firstOrNull { it.id == id }
    }
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

/**
 * [keys], one after the other, do [action]; [desc] says what for (as in Neovim); [group]:
 * the tab of ShortcutsSheet it is listed in (a ShortcutGroup's id, `insert`, or a tab of
 * the user's own), null: the one of its command (`insert` for text).
 */
data class KeyMapping(
    val keys: List<KeyStroke>,
    val action: ShortcutAction,
    val desc: String = "",
    val group: String? = null,
)

/**
 * Every shortcut: the defaults, with the user's (keymap.json) over them. Keys may be a
 * sequence (`<C-k>c`): after a key that only begins some, the next is waited for, at
 * most [timeoutMillis] (Neovim's timeoutlen); see KeySequencer.
 */
class Keymap(
    mappings: List<KeyMapping>,
    val timeoutMillis: Long,
    /** What `<leader>` stands for. */
    val leader: List<KeyStroke> = DEFAULT_LEADER,
    /** The tabs of the user's own (`"groups"`), listed even while no mapping is in them. */
    val groups: List<String> = emptyList(),
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

        /** No shortcuts: before keymap.json is read. */
        val EMPTY: Keymap = Keymap(emptyList(), DEFAULT_TIMEOUT_MILLIS)
    }
}

/**
 * The keymap: a file edited in the editor as in Neovim (keymap.json, next to the folder
 * of the language servers), read again when it is saved. Every shortcut is in it, the
 * ones it starts with too: there are no defaults in the code. It is written the first
 * time from a template (assets/templates/keymap/keymap.jsonc), which is also what is used while
 * the file cannot be read.
 *
 * ```jsonc
 * {
 *   "version": 3,
 *   "leader": "<C-k>",          // what <leader> stands for
 *   "timeoutlen": 1000,         // ms to wait for the next key of a sequence
 *   "groups": ["Markdown"],     // tabs of your own on the sheet
 *   "keymaps": [
 *     { "keys": "<C-s>", "action": "save" },
 *     { "keys": "<leader>f", "action": "format", "desc": "Format" },
 *     { "keys": "<C-b>", "insert": "**${TM_SELECTED_TEXT}$0**", "group": "Markdown" }
 *   ]
 * }
 * ```
 *
 * The file is edited on the shortcuts sheet (ShortcutsSheet: [check], [write], [withGroup]
 * for New), or in the editor like any file.
 *
 * A later mapping of the same keys replaces an earlier one; `"nop"` takes keys away.
 * Comments and trailing commas are allowed. A hardware keyboard, a soft keyboard's Ctrl
 * or Alt keys, and Ctrl and Alt of the row of extra keys all press the same keys
 * (KeyStroke).
 *
 * A file of version 1 (no "version"; the defaults were in the code then, the file held
 * only what was added) gets the mappings of the template put in front of its own the
 * first time it is read, and its version set, so that it goes on doing what it did. A file
 * of version 2 gets the mappings added since ([ADDED_IN_3]: Ctrl+O, F11) whose keys it does
 * not use yet.
 */
object KeymapFile {

    private const val TAG = "KeymapFile"
    const val FILE = "keymap.json"
    private const val VERSION = 3

    /**
     * The mappings of the template that came after version 2 (keys, action), given to a file
     * of version 2 when it is brought up to date, each only if its keys are not in the file
     * yet (mapped to something else, or taken away with "nop": the user's choice stays).
     */
    internal val ADDED_IN_3: List<Pair<String, String>> = listOf(
        "<C-o>" to "open_file",
        "<F11>" to "fullscreen",
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    /** What reading it came to. */
    sealed interface Read {
        /** [count] mappings of the file taken; [problems]: those left out, and why. */
        data class Ok(val keymap: Keymap, val count: Int, val problems: List<String>) : Read
        /** Not JSON (at [line], 0 if not known): the template's keymap is used meanwhile. */
        data class Failed(val line: Int, val message: String) : Read
    }

    fun file(context: Context): File = File(AppDirs.external(context), FILE)

    /** Whether [file] is the keymap. Not on the main thread. */
    fun isKeymapFile(context: Context, file: File): Boolean =
        runCatching { file.canonicalFile == file(context).canonicalFile }.getOrDefault(false)

    /** The keymap, written from the template if it is not there. Not on the main thread. */
    fun open(context: Context): File {
        val file = file(context)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(template(context))
        }
        return file
    }

    /**
     * The keymap of the template, as the app comes with it: what is used while the
     * user's file cannot be read. Not on the main thread.
     */
    fun builtIn(context: Context): Keymap = try {
        parse(template(context)).keymap
    } catch (e: Exception) {
        Log.e(TAG, "the template of the keymap cannot be read", e)
        Keymap.EMPTY
    }

    /**
     * Reads the file (written from the template first if it is not there; brought up to
     * the current version if it is older), to use it and to tell the user who saved it
     * what came of it. Not on the main thread.
     */
    fun read(context: Context): Read {
        val file = try {
            open(context)
        } catch (e: Exception) {
            return Read.Failed(0, e.message ?: e.toString())
        }
        var text = try {
            file.readText()
        } catch (e: Exception) {
            return Read.Failed(0, e.message ?: e.toString())
        }
        return try {
            if (versionOf(text) < VERSION) {
                text = upgrade(context, text)
                runCatching { file.writeText(text) }.onFailure { Log.w(TAG, "cannot write $file", it) }
            }
            parse(text)
        } catch (e: Exception) {
            Read.Failed(FriendlySnippets.errorLine(text, e.message), FriendlySnippets.errorMessage(e))
        }
    }

    /** The file as the app writes it the first time (Default on the sheet). Not on the main thread. */
    fun template(context: Context): String =
        TemplateText.fill(Templates.read(context, Templates.KEYMAP), text = mapOf("actions" to actionsListing()))

    // the "version" of the file; 1 if it has none (written before there was one)
    private fun versionOf(text: String): Int {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return VERSION
        return (root["version"] as? JsonPrimitive)?.intOrNull ?: 1
    }

    // A file of an older version, brought up to date. The text is changed in place, so that
    // its comments and layout stay.
    private fun upgrade(context: Context, text: String): String =
        if (versionOf(text) < 2) upgradeFrom1(context, text) else upgradeFrom2(text)

    /**
     * A file of version 2 brought up to version 3: the mappings of [ADDED_IN_3] whose keys it
     * does not have yet put in at the start of its "keymaps" (its own after them), and its
     * "version" set. Comments and layout stay.
     */
    internal fun upgradeFrom2(text: String): String {
        val read = parse(text)
        val leader = read.keymap.leader
        // the keys the file has, "nop" included (a later "nop" is the user taking them away)
        val taken = keysIn(text, leader)
        val missing = ADDED_IN_3.filter { (keys, _) ->
            val parsed = KeyStroke.parseSequence(keys, leader).getOrNull()
            parsed != null && parsed !in taken
        }
        var result = text
        if (missing.isNotEmpty()) {
            val lines = missing.joinToString("") { (keys, action) ->
                "\n    { \"keys\": ${JsonPrimitive(keys)}, \"action\": ${JsonPrimitive(action)} },"
            }
            val code = withoutComments(result)
            val keymaps = Regex("\"keymaps\"\\s*:\\s*\\[").find(code)
            result = if (keymaps != null) {
                val at = keymaps.range.last + 1
                result.substring(0, at) + "\n    // added by the app in version $VERSION" + lines + result.substring(at)
            } else {
                val brace = code.indexOf('{')
                if (brace < 0) return text
                result.substring(0, brace + 1) + "\n  \"keymaps\": [" + lines + "\n  ]," + result.substring(brace + 1)
            }
        }
        return withVersion(result)
    }

    // the keys of every mapping in [text], as written ("nop" ones too)
    private fun keysIn(text: String, leader: List<KeyStroke>): Set<List<KeyStroke>> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptySet()
        val entries = root["keymaps"] as? JsonArray ?: return emptySet()
        return entries.mapNotNull { element ->
            val keys = ((element as? JsonObject)?.get("keys") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            KeyStroke.parseSequence(keys, leader).getOrNull()
        }.toSet()
    }

    // [text] with its "version" set to [VERSION] (put in after the opening brace if it has none)
    private fun withVersion(text: String): String {
        val code = withoutComments(text)
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(code)
        if (version != null) {
            val number = version.groups[1]!!.range
            return text.substring(0, number.first) + VERSION + text.substring(number.last + 1)
        }
        val brace = code.indexOf('{')
        if (brace < 0) return text
        return text.substring(0, brace + 1) + "\n  \"version\": $VERSION," + text.substring(brace + 1)
    }

    // A file of version 1, brought up to date: the template's mappings put in at the start
    // of its "keymaps" (its own after them, so they still win), and its version set.
    private fun upgradeFrom1(context: Context, text: String): String {
        val defaults = (json.parseToJsonElement(template(context)) as JsonObject)["keymaps"] as? JsonArray
            ?: return text
        val lines = defaults.mapNotNull { it as? JsonObject }.joinToString("") { entry ->
            "\n    " + entry.entries.joinToString(", ", "{ ", " },") { (k, v) -> "\"$k\": $v" }
        }
        // looked for in the text without its comments (the same length), so that a "{" or
        // "keymaps" in a comment is not taken
        val code = withoutComments(text)
        val keymaps = Regex("\"keymaps\"\\s*:\\s*\\[").find(code)
        val withMappings = if (keymaps != null) {
            val at = keymaps.range.last + 1
            text.substring(0, at) +
                "\n    // the shortcuts the app started with (they were in its code until version 2)" +
                lines + text.substring(at)
        } else {
            // no "keymaps" at all: one, after the opening brace
            val brace = code.indexOf('{')
            if (brace < 0) return text
            text.substring(0, brace + 1) + "\n  \"keymaps\": [" + lines + "\n  ]," + text.substring(brace + 1)
        }
        val brace = withoutComments(withMappings).indexOf('{')
        if (brace < 0) return text
        return withMappings.substring(0, brace + 1) + "\n  \"version\": $VERSION," + withMappings.substring(brace + 1)
    }

    // [text] with its comments (// to the end of the line, /* to */) blanked out, its
    // strings left as they are: as long as [text], so an index in it is one in [text]
    private fun withoutComments(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    if (c == '\\') i++ else if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') out[i++] = ' '
                    continue
                }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) {
                        if (text[i] != '\n') out[i] = ' '
                        i++
                    }
                    continue
                }
            }
            i++
        }
        return out.toString()
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

        // in the order of the file: a later mapping of the same keys replaces an earlier one
        val mappings = LinkedHashMap<List<KeyStroke>, KeyMapping>()
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
            val group = (entry["group"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
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
            mappings.remove(keys)
            if (action != null) mappings[keys] = KeyMapping(keys, action, desc, group)
            count++
        }
        val groups = (root["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        return Read.Ok(Keymap(mappings.values.toList(), timeout, leader, groups), count, problems)
    }

    /**
     * What is wrong with [text] as the keymap, as ShortcutsSheet checks it before it writes
     * it: not JSON ([Check.Syntax], at a line), or the mappings that would be left out.
     */
    fun check(text: String): Check = try {
        val read = parse(text)
        if (read.problems.isEmpty()) Check.Right(read.count) else Check.LeftOut(read.problems)
    } catch (e: Exception) {
        Check.Syntax(FriendlySnippets.errorLine(text, e.message), FriendlySnippets.errorMessage(e))
    }

    sealed interface Check {
        data class Right(val count: Int) : Check
        data class LeftOut(val problems: List<String>) : Check
        data class Syntax(val line: Int, val message: String) : Check
    }

    /** Writes [text] as the keymap, whole or not at all (ShortcutsSheet). Not on the main thread. */
    fun write(context: Context, text: String) {
        val file = file(context)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(if (text.endsWith("\n")) text else text + "\n")
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(text)
            }
        }
    }

    /**
     * [text] with [name] added to its `"groups"` (a tab of the user's own): put in that list
     * if there is one, else a list of it after the opening brace. The text is changed in
     * place, so that its comments and layout stay.
     */
    fun withGroup(text: String, name: String): String {
        val quoted = JsonPrimitive(name).toString()
        val code = withoutComments(text)
        val groups = Regex("\"groups\"\\s*:\\s*\\[").find(code)
        if (groups != null) {
            val at = groups.range.last + 1
            val empty = code.substring(at).trimStart().startsWith("]")
            return text.substring(0, at) + quoted + (if (empty) "" else ", ") + text.substring(at)
        }
        val brace = code.indexOf('{')
        if (brace < 0) return text
        return text.substring(0, brace + 1) +
            "\n  // the tabs of your own on the shortcuts sheet\n  \"groups\": [$quoted]," +
            text.substring(brace + 1)
    }

    /**
     * The tabs renamed from the text [before] to [after], old name → new: `"groups"` as long
     * as it was, with a name in the place of one, which neither had the other. Empty if the
     * two are not lists of the same length, or not JSON.
     */
    fun renamedGroups(before: String, after: String): Map<String, String> {
        val old = groupsOf(before) ?: return emptyMap()
        val new = groupsOf(after) ?: return emptyMap()
        if (old.size != new.size) return emptyMap()
        return old.zip(new)
            .filter { (from, to) -> from != to && from !in new && to !in old }
            .toMap()
    }

    // the "groups" of [text], as written; null: it is not JSON
    private fun groupsOf(text: String): List<String>? = try {
        ((json.parseToJsonElement(text) as? JsonObject)?.get("groups") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .orEmpty()
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * [text] with the `"group"` of its mappings renamed as [renames] says (a tab renamed in
     * `"groups"`), in place: its comments, its layout and everything else stay.
     */
    fun withGroupsRenamed(text: String, renames: Map<String, String>): String {
        if (renames.isEmpty()) return text
        val out = StringBuilder(text)
        // from the last: the places of those before it stay right
        for (match in GROUP_VALUE.findAll(withoutComments(text)).toList().asReversed()) {
            val value = match.groups[1] ?: continue
            val name = runCatching { (json.parseToJsonElement(value.value) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: continue
            val renamed = renames[name.trim()] ?: continue
            out.replace(value.range.first, value.range.last + 1, JsonPrimitive(renamed).toString())
        }
        return out.toString()
    }

    /**
     * [text] without the tab [name]: out of its `"groups"`, and the `"group"` of its mappings
     * taken off (they are not deleted: they go back to the tab of their command, or that of
     * text). In place: its comments, its layout and everything else stay.
     */
    fun withoutGroup(text: String, name: String): String {
        val out = StringBuilder(text)
        val code = withoutComments(text)
        // from the last: the places of those before it stay right
        for (match in GROUP_VALUE.findAll(code).toList().asReversed()) {
            val value = match.groups[1] ?: continue
            val group = runCatching { (json.parseToJsonElement(value.value) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: continue
            if (group.trim() != name) continue
            val (from, to) = propertySpan(code, match.range.first, value.range.last + 1)
            out.delete(from, to)
        }
        var result = out.toString()
        while (true) {
            val spans = runCatching { JsoncSpans.elementsOf(result, GROUPS) }.getOrNull() ?: return result
            val index = spans.indexOfFirst { span ->
                runCatching { (json.parseToJsonElement(result.substring(span.start, span.end)) as? JsonPrimitive)?.contentOrNull }
                    .getOrNull()?.trim() == name
            }
            if (index < 0) return result
            result = JsoncSpans.removeAt(result, spans, index)
        }
    }

    // What takes a property from [start] to [end] out of its object, in [code] (the text with its
    // comments blanked): with the comma after it, else the one before it; with its line, if
    // nothing else is on it
    private fun propertySpan(code: String, start: Int, end: Int): Pair<Int, Int> {
        var from = start
        var to = end
        var next = to
        while (next < code.length && code[next].isWhitespace()) next++
        if (code.getOrNull(next) == ',') {
            to = next + 1
        } else {
            var before = from - 1
            while (before >= 0 && code[before].isWhitespace()) before--
            if (code.getOrNull(before) == ',') from = before
        }
        val lineStart = code.lastIndexOf('\n', from - 1) + 1
        val lineEnd = code.indexOf('\n', to).let { if (it < 0) code.length else it }
        if (code.substring(lineStart, from).isBlank() && code.substring(to, lineEnd).isBlank()) {
            return lineStart to minOf(lineEnd + 1, code.length)
        }
        // on a line with others: the spaces after it too, when it was not the last
        if (to > end) while (to < code.length && code[to] == ' ') to++
        return from to to
    }

    // the "group" of a mapping, its value (a string, with its quotes) the first group
    private val GROUP_VALUE = Regex("""\"group\"\s*:\s*(\"(?:[^\"\\]|\\.)*\")""")

    /** The list of mappings in keymap.json. */
    const val LIST = "keymaps"

    // the list of the user's own tabs
    private const val GROUPS = "groups"

    /**
     * Where the mapping of [keys] that is in effect (the last one of those keys: a later one
     * replaces an earlier) is in [text], its `{` to after its `}`; null if there is none, or
     * the text is not a keymap. [leader]: what `<leader>` stands for.
     */
    internal fun spanOf(text: String, keys: List<KeyStroke>, leader: List<KeyStroke>): JsoncSpans.Span? {
        val spans = runCatching { JsoncSpans.elementsOf(text, LIST) }.getOrNull() ?: return null
        return spans.lastOrNull { span ->
            val entry = runCatching { json.parseToJsonElement(text.substring(span.start, span.end)) as? JsonObject }.getOrNull()
            val keysText = (entry?.get("keys") as? JsonPrimitive)?.contentOrNull
            keysText != null && KeyStroke.parseSequence(keysText, leader).getOrNull() == keys
        }
    }

    /**
     * [text] without the mapping of [keys] in effect ([spanOf]); null if there is none.
     * IllegalArgumentException if the text is not a keymap.
     */
    fun withoutMapping(text: String, keys: List<KeyStroke>, leader: List<KeyStroke>): String? {
        val span = spanOf(text, keys, leader) ?: return null
        val spans = JsoncSpans.elementsOf(text, LIST)
        val index = spans.indexOfFirst { it.start == span.start }
        if (index < 0) return null
        return JsoncSpans.removeAt(text, spans, index)
    }

    /**
     * [text] with a new mapping at the end of its `"keymaps"` (New on the shortcuts panel),
     * from the template (assets/templates/keymap/entry.jsonc): its keys left to type, [action]
     * (a command's id; empty: to type) and [group] (the tab it is listed in; null: none) put
     * in; and where the value of its keys is, to put the cursor there. Not on the main thread
     * (reads the asset). IllegalArgumentException if the text is not a keymap.
     */
    fun withNewMapping(context: Context, text: String, action: String, group: String?): Pair<String, Int> {
        val entry = TemplateText.fill(
            Templates.read(context, Templates.KEYMAP_ENTRY).trim(),
            values = mapOf(
                "action" to TemplateText.jsonString(action),
                "group" to TemplateText.jsonString(group.orEmpty()),
            ),
        )
        val appended = JsoncSpans.append(text, LIST, entry)
        val keys = appended.text.indexOf(KEYS_VALUE, appended.start)
        val caret = if (keys < 0) appended.start else keys + KEYS_VALUE.length
        return appended.text to caret
    }

    // where the value of a new mapping's keys starts, in its template
    private const val KEYS_VALUE = "\"keys\": \""

    private fun actionsListing(): String =
        ShortcutCommand.entries.map { it.id }.plus("nop").chunked(6).joinToString("\n") { line ->
            "//   " + line.joinToString(", ")
        }
}
