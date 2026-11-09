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
package x.editor.app.config

import android.app.Activity
import android.content.Intent
import android.widget.Toast

import androidx.lifecycle.LifecycleCoroutineScope

import java.io.File

import x.editor.app.R
import x.editor.app.activity.SettingsActivity
import x.editor.app.completion.JsonSchema
import x.editor.app.completion.SheetSchemas
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.settings.EditorSettings
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView

/** The files of the user's snippets, and how many languages friendly-snippets has (null: it is not there). */
internal class SnippetFiles(val files: List<FriendlySnippets.UserFile>, val languages: Int?)

/**
 * The user's own snippets (menu → Developer tools → My snippets), a panel of the developer
 * tools (ConfigFilesSheet: its parts and gestures are every panel's): whether snippets are
 * offered (the setting: its next step opens the settings when they are not) and
 * friendly-snippets is there; each file (one per language, all.json for every file) with
 * how many snippets it has and their prefixes, or what is wrong with it; those of the
 * current file first, tagged.
 *
 * A tap on a file: its info (how many, its prefixes, or every problem); ⋮: Edit, Delete.
 * New: a draft from the template (the current file's language offered as its name),
 * written by Save; then it is read again for every document.
 *
 * @param fileName the name of the document shown (null: none)
 * @param onSaved a file was saved: read again (LspController.onUserSnippetsSaved), which tells the user
 */
internal class SnippetsSheet(
    activity: Activity,
    scope: LifecycleCoroutineScope,
    private val fileName: String?,
    styleEditor: (EditorView) -> Unit,
    highlight: suspend (CodeEditorView, String) -> Unit,
    private val onSaved: (File) -> Unit,
) : ConfigFilesSheet<SnippetFiles>(activity, scope, styleEditor, highlight) {

    // the ids of the files of the current file, most specific first
    private val currentIds: List<String> = fileName?.let { FriendlySnippets.userIdsOf(it) }.orEmpty()

    // what a tap on each file shows (render), by id
    private var infos: Map<String, EntryInfo> = emptyMap()

    fun show() {
        showFiles(
            title = activity.getString(R.string.snippets_sheet_title),
            helpId = "snippets",
            helpWhat = activity.getString(R.string.snippets_help_what),
            helpHow = activity.getString(R.string.help_how_common),
            where = { FriendlySnippets.userDirectory(activity).path },
            listTitle = activity.getString(R.string.snippets_sheet_files),
            listIcon = R.drawable.ic_snippets,
            newTitle = activity.getString(R.string.snippets_new_title),
            newHint = activity.getString(R.string.snippets_new_hint),
            help = activity.getString(R.string.snippets_help),
            emptyHeading = activity.getString(R.string.snippets_editor_title_none),
            emptyMessage = activity.getString(R.string.snippets_editor_none),
        )
    }

    // #region the files

    override fun read(): SnippetFiles =
        SnippetFiles(FriendlySnippets.userFiles(activity), FriendlySnippets.packageLanguages(activity))

    // the document's language, if it has no file of its own yet
    override fun newInitial(state: SnippetFiles): String {
        val id = FriendlySnippets.userIdFor(fileName)
        return if (state.files.none { it.id == id }) id else ""
    }

    override fun fileNameOf(key: String): String = "$key.json"

    override fun headingOf(key: String): CharSequence = activity.getString(R.string.snippets_editor_title, fileNameOf(key))

    override fun readFile(key: String): String? =
        FriendlySnippets.userFileOf(activity, key).takeIf { it.isFile }?.readText()

    override fun exists(key: String): Boolean = FriendlySnippets.userFileOf(activity, key).exists()

    override fun checkFile(key: String, text: String): List<String> =
        (FriendlySnippets.check(text) as? FriendlySnippets.Reload.Failed)?.let { failed ->
            listOf(if (failed.line > 0) activity.getString(R.string.config_error_line, failed.line, failed.message) else failed.message)
        } ?: emptyList()

    override fun afterSave(key: String, text: String, created: Boolean): CharSequence? {
        val read = FriendlySnippets.check(text) as? FriendlySnippets.Reload.Read ?: return null
        return activity.getString(R.string.snippets_after_save, read.count)
    }

    override fun templateOf(key: String): String = FriendlySnippets.templateFor(activity, key)

    override fun templateNote(key: String): CharSequence = activity.getString(
        if (key == "all" || key == "global") R.string.snippets_new_note_all else R.string.snippets_new_note, key
    )

    // what a file may say, as it is typed (SheetCompletion)
    override fun schemaOf(key: String): JsonSchema = SheetSchemas.snippets(activity)

    override fun writeFile(key: String, text: String) {
        FriendlySnippets.writeUserFile(activity, key, text)
    }

    override fun deleteFile(key: String): Boolean = FriendlySnippets.deleteUserFile(activity, key)

    override fun deleteMessage(key: String): CharSequence =
        activity.getString(R.string.snippets_delete_message, "snippets/" + fileNameOf(key))

    override fun onFileDeleted(key: String) {
        onSaved(FriendlySnippets.userFileOf(activity, key))
    }

    override suspend fun onFileSaved(key: String, created: Boolean) {
        onSaved(FriendlySnippets.userFileOf(activity, key))
    }

    override fun newProblem(key: String, state: SnippetFiles): String? = when {
        key.isEmpty() -> activity.getString(R.string.config_new_empty)
        !FriendlySnippets.isValidUserId(key) -> activity.getString(R.string.config_new_unsafe)
        state.files.any { it.id.equals(key, ignoreCase = true) } -> activity.getString(R.string.config_new_exists, fileNameOf(key))
        else -> null
    }

    // #endregion

    // #region the list

    override fun render(state: SnippetFiles) {
        val files = state.files
        val on = EditorSettings.isSnippetCompletion(activity)
        val total = files.sumOf { it.snippets.size }
        val broken = files.count { it.error != null }
        val text = buildString {
            append(
                if (on) activity.getString(R.string.snippets_sheet_on)
                else activity.getString(R.string.snippets_sheet_off)
            )
            append('\n')
            append(
                state.languages?.let { activity.getString(R.string.snippets_sheet_package, it) }
                    ?: activity.getString(R.string.snippets_sheet_no_package)
            )
        }
        setStatus(
            when {
                broken > 0 -> StateLevel.BAD
                !on -> StateLevel.WARN
                files.isEmpty() -> StateLevel.QUIET
                else -> StateLevel.GOOD
            },
            activity.getString(R.string.snippets_sheet_status_title, total, files.size),
            text,
            when {
                !on -> PopupAction(activity.getString(R.string.snippets_status_settings)) { openSettings() }
                currentIds.isNotEmpty() && files.none { it.id == currentIds.first() } ->
                    PopupAction(activity.getString(R.string.snippets_status_new, currentIds.first())) { startNew(currentIds.first()) }
                else -> null
            },
        )
        val infos = HashMap<String, EntryInfo>()
        val entries = files.map { entryOf(it, infos) }
            .sortedBy { entry -> currentIds.indexOf(entry.key).let { if (it < 0) Int.MAX_VALUE else it } }
        this.infos = infos
        val language = FriendlySnippets.userIdFor(fileName)
        showEntries(
            entries,
            Empty(R.drawable.ic_snippets, activity.getString(R.string.snippets_empty_title),
                PopupAction(activity.getString(R.string.snippets_status_new, language)) { startNew(language) }),
        ) { key -> this.infos[key] }
        setFooter(FriendlySnippets.userDirectory(activity).path)
    }

    // the settings, where snippets are offered or not
    private fun openSettings() {
        try {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    // A file: its state in a word, its prefixes (or where it is wrong), its info for a tap
    private fun entryOf(file: FriendlySnippets.UserFile, infos: MutableMap<String, EntryInfo>): Entry {
        val editing = isEditing(file.id)
        val error = file.error?.let { if (file.line > 0) activity.getString(R.string.config_error_line, file.line, it) else it }
        val (state, level) = when {
            error != null -> activity.getString(R.string.config_state_invalid) to StateLevel.BAD
            editing -> activity.getString(R.string.config_state_editing) to StateLevel.ACTIVE
            file.snippets.isEmpty() -> activity.getString(R.string.snippets_state_empty) to StateLevel.QUIET
            else -> activity.getString(R.string.snippets_state_count, file.snippets.size) to StateLevel.GOOD
        }
        val prefixes = file.snippets.map { it.prefix }.distinct()
        val detail = if (error != null) "" else prefixes.joinToString("  ").let {
            if (it.length > 120) it.take(120) + "…" else it
        }
        val name = if (file.id == "all" || file.id == "global") {
            activity.getString(R.string.snippets_sheet_global, file.file.name)
        } else {
            file.file.name
        }
        val edit = PopupAction(activity.getString(R.string.config_edit)) { edit(file.id) }
        infos[file.id] = when {
            error != null -> EntryInfo(
                name, state, StateLevel.BAD,
                now = activity.getString(R.string.snippets_info_invalid_now),
                steps = listOf(activity.getString(R.string.lsp_info_invalid_step)),
                errors = listOf(error),
                action = edit,
            )
            file.snippets.isEmpty() -> EntryInfo(
                name, state, StateLevel.QUIET,
                now = activity.getString(R.string.snippets_info_empty_now),
                steps = listOf(activity.getString(R.string.snippets_info_empty_step)),
                action = edit,
            )
            else -> EntryInfo(
                name, activity.getString(R.string.snippets_state_count, file.snippets.size), StateLevel.GOOD,
                now = activity.getString(R.string.snippets_info_now, file.snippets.size),
                details = listOf(activity.getString(R.string.snippets_info_prefixes, prefixes.joinToString("  "))),
                action = edit,
            )
        }
        return Entry(
            file.id, name, state, level, detail,
            problem = error.orEmpty(),
            selected = editing,
            current = file.id in currentIds && file.id != "all" && file.id != "global",
            onMore = { showActions(file.id, name, state, level) },
        ) { infos[file.id]?.let { showInfo(file.id, it) } ?: Unit }
    }

    // #endregion
}
