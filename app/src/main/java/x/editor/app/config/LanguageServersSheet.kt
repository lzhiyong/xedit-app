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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.Toast

import androidx.lifecycle.LifecycleCoroutineScope


import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import x.editor.app.R
import x.editor.app.completion.JsonSchema
import x.editor.app.completion.SheetSchemas
import x.editor.app.controller.LspController
import x.editor.app.lsp.LanguageServerSpec
import x.editor.app.lsp.LanguageServers
import x.editor.app.toolchain.TermuxProbe
import x.editor.app.toolchain.ToolchainInstaller
import x.editor.app.view.CodeEditorView
import x.github.module.editor.view.EditorView

/**
 * The language servers (menu → Developer tools → Language servers), a panel of the
 * developer tools (ConfigFilesSheet: its parts and gestures are every panel's):
 *
 *  - the status card: how many run, how many are wrong, which server the current file goes
 *    to, and its next step: see that server, or make one for the file;
 *  - each server with its extensions and how it is reached, how it is doing in this session
 *    in a word of its level (StateLevel): running (green), starting (a ring), not running
 *    yet (yellow), failed, not installed while on, a wrong file (red), off (grey: the user's
 *    choice, not a problem; \"off · not installed\" too). 〔Current file〕 on those of the
 *    file in the editor, listed first;
 *  - a tap: its info ([infoOf]: how it is, what to do, its address, projects, features,
 *    a port another has too, extensions an earlier server takes), Edit or Open toolchain at
 *    its foot; ⋮: Edit, Delete;
 *  - New: a name, then a draft of it (its preset if the app ships one of that id, else the
 *    template, off), written by Save: it does not depend on the toolchain's packages.json.
 *    Saved, the server's connections are closed, then it is started again if its file says
 *    \"enabled\": true and a file of its is open (LspController.restartServer); the check
 *    line says beforehand what saving will do.
 *
 * How a server is reached is its own: auto (the app's usr/bin if it has the command, else
 * Termux, through socat), process (run by the app, locally), socket (a port; with \"termux\",
 * started by Termux through socat). The list is read again when the toolchain changes beneath
 * the panel (what is installed may have changed).
 *
 * How the servers are doing is shown again as it changes, while the panel is up (the window
 * of the one tapped with it). Termux is asked what it has when the panel opens, if a server of
 * type auto may come from it: one it says it has not is \"Not installed\", not left to fail.
 *
 * @param openToolchain the toolchain panel opened (a server not installed); null: not offered
 */
internal class LanguageServersSheet(
    activity: Activity,
    scope: LifecycleCoroutineScope,
    private val states: () -> Map<String, LspController.ServerState>,
    private val failures: () -> Map<String, LspController.Failure>,
    private val documents: () -> Map<String, Int>,
    private val running: (String) -> List<LspController.RunningServer>,
    private val fileName: String?,
    styleEditor: (EditorView) -> Unit,
    highlight: suspend (CodeEditorView, String) -> Unit,
    private val onSaved: suspend (String) -> LspController.Restart,
    private val onRemoved: (String) -> Unit,
    private val openToolchain: (() -> Unit)? = null,
) : ConfigFilesSheet<LanguageServers.Inspection>(activity, scope, styleEditor, highlight) {

    // the states shown last: the list is shown again when they change
    private var shownStates: Map<String, LspController.ServerState> = emptyMap()
    private var shownDocuments: Map<String, Int> = emptyMap()
    private var shownFailures: Map<String, LspController.Failure> = emptyMap()

    // what a tap on each server shows (render), by id
    private var infos: Map<String, EntryInfo> = emptyMap()

    // whether the program of each server is there (read, off the main thread), by id
    @Volatile
    private var installations: Map<String, LanguageServers.Installation> = emptyMap()

    // the changes of the toolchain shown last (ToolchainInstaller.changes)
    private var shownToolchainChanges = ToolchainInstaller.changes

    // the extension of the file in the editor; null: none
    private val extension: String? = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }

    fun show() {
        showFiles(
            title = activity.getString(R.string.lsp_sheet_title),
            helpId = "lsp",
            helpWhat = activity.getString(R.string.lsp_help_what),
            helpHow = activity.getString(R.string.lsp_help_how),
            where = { LanguageServers.directory(activity).path },
            listTitle = activity.getString(R.string.lsp_sheet_servers),
            listIcon = R.drawable.ic_lsp_config,
            newTitle = activity.getString(R.string.lsp_new_title),
            newHint = activity.getString(R.string.lsp_new_hint),
            help = activity.getString(R.string.lsp_config_help),
            emptyHeading = activity.getString(R.string.lsp_config_title_none),
            emptyMessage = activity.getString(R.string.lsp_config_none),
        )
        // how the servers are doing, shown again as it changes (a server starting, stopping)
        scope.launch {
            var asked = false
            while (dialog.isShowing) {
                delay(STATE_POLL_MILLIS)
                val inspection = state ?: continue
                if (!asked) {
                    asked = true
                    askTermux(inspection)
                }
                if (ToolchainInstaller.changes != shownToolchainChanges) {
                    shownToolchainChanges = ToolchainInstaller.changes
                    reload { recheckEditor() }
                    continue
                }
                if (states() != shownStates || documents() != shownDocuments || failures() != shownFailures) render(inspection)
            }
        }
    }

    // Termux asked what it has of what the servers of type auto run (the app's usr/bin first,
    // Termux's otherwise: auto-detected), and the list read again with it
    private fun askTermux(inspection: LanguageServers.Inspection) {
        val auto = (inspection.registered + inspection.files.mapNotNull { it.spec })
            .filter { it.connection is LanguageServerSpec.Connection.Auto }
        if (auto.isEmpty()) return
        val commands = auto.flatMap { LanguageServers.programsOf(it) }
        TermuxProbe.check(activity, commands.distinct()) { found ->
            if (found != null && dialog.isShowing) reload()
        }
    }

    // #region the files

    // the files, and whether the program of each server is there (its row says if it is not)
    override fun read(): LanguageServers.Inspection = LanguageServers.inspect(activity).also { inspection ->
        val specs = inspection.registered + inspection.files.mapNotNull { it.spec }
        installations = specs.associate { it.id to LanguageServers.installationOf(activity, it) }
    }

    // the document's extension, if no server has a file of that id yet
    override fun newInitial(state: LanguageServers.Inspection): String = ""

    override fun fileNameOf(key: String): String = LanguageServers.fileNameOf(key)

    override fun headingOf(key: String): CharSequence = activity.getString(R.string.lsp_config_title, fileNameOf(key))

    override fun readFile(key: String): String? = LanguageServers.read(activity, key)

    override fun exists(key: String): Boolean = LanguageServers.fileOf(activity, key).exists()

    override fun checkFile(key: String, text: String): List<String> = when (val check = LanguageServers.check(key, text)) {
        is LanguageServers.Check.Invalid -> listOf(describe(check))
        is LanguageServers.Check.Valid -> emptyList()
    }

    // what saving will do: restart it (with its files), stop it, start it with the first file
    override fun afterSave(key: String, text: String, created: Boolean): CharSequence? {
        val spec = (LanguageServers.check(key, text) as? LanguageServers.Check.Valid)?.spec ?: return null
        if (!spec.enabled) {
            return activity.getString(if (created) R.string.lsp_after_save_off_new else R.string.lsp_after_save_off, key)
        }
        val open = documents()[key] ?: 0
        return if (open > 0) activity.getString(R.string.lsp_after_save_restart, key, open)
        else activity.getString(R.string.lsp_after_save_idle, key)
    }

    override fun templateOf(key: String): String = LanguageServers.newFileText(activity, key, extension)

    override fun templateNote(key: String): CharSequence =
        if (key in LanguageServers.exampleIds(activity)) activity.getString(R.string.lsp_new_note_preset, key)
        else activity.getString(R.string.lsp_new_note_template)

    // what a file may say, as it is typed (SheetCompletion)
    override fun schemaOf(key: String): JsonSchema = SheetSchemas.languageServer(activity)

    override fun writeFile(key: String, text: String) {
        val check = LanguageServers.write(activity, key, text)
        if (check is LanguageServers.Check.Invalid) throw IllegalArgumentException(describe(check))
    }

    override fun deleteFile(key: String): Boolean = LanguageServers.delete(activity, key)

    override fun deleteMessage(key: String): CharSequence {
        val successor = state?.let { inspection ->
            val specs = inspection.registered + inspection.files.mapNotNull { it.spec }
            val mine = specs.firstOrNull { it.id == key }?.extensions.orEmpty()
            specs.firstOrNull { it.id != key && it.enabled && it.extensions.any { e -> e in mine } }?.id
        }
        val first = activity.getString(R.string.lsp_delete_what, LanguageServers.DIR + "/" + fileNameOf(key))
        val second = successor?.let { activity.getString(R.string.lsp_delete_successor, it) }
            ?: activity.getString(R.string.lsp_delete_alone)
        return "$first $second"
    }

    override fun onFileDeleted(key: String) = onRemoved(key)

    // Saved: its old connections closed, then started again if it is on and a file of its is
    // open (not otherwise: it starts when one is); how that goes shows on its row. The panel
    // stays locked till its old connections are closed: nothing to save over that.
    override suspend fun onFileSaved(key: String, created: Boolean) {
        gate.become(PanelOp.RESTART, key)
        val then = when (onSaved(key)) {
            LspController.Restart.STARTED -> R.string.lsp_saved_starting
            LspController.Restart.NOT_RUNNING -> R.string.lsp_saved_not_running
            LspController.Restart.OFF -> R.string.lsp_saved_stopped
        }
        if (!dialog.isShowing) return
        Toast.makeText(
            activity,
            activity.getString(R.string.config_saved_then, fileNameOf(key), activity.getString(then, key)),
            Toast.LENGTH_SHORT
        ).show()
        // read again: whether its program is there may have changed with its command
        reload()
    }

    override fun newProblem(key: String, state: LanguageServers.Inspection): String? = when {
        key.isBlank() -> activity.getString(R.string.config_new_empty)
        LanguageServers.fileNameOf(key) != "$key.json" -> activity.getString(R.string.config_new_unsafe_file)
        // (the shared storage does not tell upper case from lower: one file)
        state.files.any { it.id.equals(key, ignoreCase = true) } ||
            state.registered.any { it.id.equals(key, ignoreCase = true) } ->
            activity.getString(R.string.config_new_exists, key)
        else -> null
    }

    // what is wrong with a file, in a line
    private fun describe(check: LanguageServers.Check.Invalid): String = when {
        check.syntax -> if (check.line > 0) activity.getString(R.string.config_error_line, check.line, check.message) else check.message
        check.notObject -> activity.getString(R.string.config_check_not_object)
        else -> activity.getString(R.string.lsp_check_invalid, check.message)
    }

    // #endregion

    // #region the list

    // The card and the list: the servers of the current file first, then the others (those
    // registered in code before those of files)
    override fun render(state: LanguageServers.Inspection) {
        val states = states()
        val documents = documents()
        shownStates = states
        shownDocuments = documents
        shownFailures = failures()
        val specs = state.registered + state.files.mapNotNull { it.spec }
        shadowed = LanguageServers.shadowedExtensions(specs)
        sharedPorts = LanguageServers.sharedAddresses(specs)
        renderStatus(state, specs, states)
        val infos = HashMap<String, EntryInfo>()
        val entries = state.registered.map { registeredEntryOf(it, states, documents, infos) } +
            state.files.map { fileEntryOf(it, states, documents, infos) }
        this.infos = infos
        showEntries(
            entries.sortedBy { if (it.current) 0 else 1 },
            Empty(R.drawable.ic_lsp_config, activity.getString(R.string.lsp_empty_title),
                PopupAction(activity.getString(R.string.config_new)) { startNew() }),
        ) { key -> this.infos[key] }
        setFooter(state.directory.path)
    }

    // How many run and are wrong; the server of the current file, and the next step: see it,
    // or make one for the file
    private fun renderStatus(
        state: LanguageServers.Inspection,
        specs: List<LanguageServerSpec>,
        states: Map<String, LspController.ServerState>,
    ) {
        val invalid = state.files.count { it.spec == null }
        val problems = invalid + specs.count { statusOf(it, states).isProblem }
        val runningCount = specs.count { states[it.id] == LspController.ServerState.RUNNING }
        val on = specs.count { it.enabled }
        val level = when {
            problems > 0 -> StateLevel.BAD
            runningCount > 0 -> StateLevel.GOOD
            on > 0 -> StateLevel.WARN
            else -> StateLevel.QUIET
        }
        val title = when {
            specs.isEmpty() && invalid == 0 -> activity.getString(R.string.lsp_sheet_status_none)
            problems > 0 -> activity.getString(R.string.lsp_status_title_problems, runningCount, problems)
            else -> activity.getString(R.string.lsp_status_title, runningCount, on)
        }
        val name = fileName
        val serving = name?.let { n -> specs.firstOrNull { it.enabled && it.handles(n) } }
        val text: String
        var action: PopupAction? = null
        when {
            name == null -> text = if (on == 0 && specs.isNotEmpty()) activity.getString(R.string.lsp_sheet_status_all_off)
                else activity.getString(R.string.lsp_status_no_document)
            serving != null -> {
                text = activity.getString(R.string.lsp_status_current, name, serving.id)
                action = PopupAction(activity.getString(R.string.lsp_status_see, serving.id)) {
                    infos[serving.id]?.let { showInfo(serving.id, it) }
                }
            }
            else -> {
                text = activity.getString(R.string.lsp_status_current_none, name)
                action = PopupAction(activity.getString(R.string.lsp_status_new)) { startNew() }
            }
        }
        setStatus(level, title, text, action)
    }

    // whether [spec] is for the file in the editor
    private fun isCurrent(spec: LanguageServerSpec): Boolean = extension != null && extension in spec.extensions

    // a server registered in code: no file to edit
    private fun registeredEntryOf(
        spec: LanguageServerSpec,
        states: Map<String, LspController.ServerState>,
        documents: Map<String, Int>,
        infos: MutableMap<String, EntryInfo>,
    ): Entry {
        val (stateText, level) = stateOf(spec, states, documents)
        val name = activity.getString(R.string.lsp_sheet_registered, spec.id)
        infos[spec.id] = infoOf(name, spec, states, documents, registered = true)
        return Entry(
            spec.id, name, stateText, level, detailOf(spec),
            problem = failureOf(spec, states)?.let { describeFailure(it) } ?: "",
            dimmed = !spec.enabled,
            current = isCurrent(spec),
            busy = statusOf(spec, states) == Status.STARTING,
            onMore = { showServerActions(spec.id, name, stateText, level, registered = true) },
        ) { showStatus(spec.id) }
    }

    // A server of a file: tinted while its file is in the editor; a tap shows how it is doing
    private fun fileEntryOf(
        file: LanguageServers.ServerFile,
        states: Map<String, LspController.ServerState>,
        documents: Map<String, Int>,
        infos: MutableMap<String, EntryInfo>,
    ): Entry {
        val editing = isEditing(file.id)
        val spec = file.spec
        val check = file.check
        val (stateText, level) = when {
            // a problem is said over \"Editing\"
            spec != null && statusOf(spec, states).isProblem -> stateOf(spec, states, documents)
            spec == null -> activity.getString(R.string.config_state_invalid) to StateLevel.BAD
            editing -> activity.getString(R.string.config_state_editing) to StateLevel.ACTIVE
            else -> stateOf(spec, states, documents)
        }
        val detail = spec?.let(::detailOf) ?: ""
        val problem = when {
            spec != null -> failureOf(spec, states)?.let { describeFailure(it) } ?: ""
            check is LanguageServers.Check.Invalid -> describe(check)
            else -> ""
        }
        infos[file.id] = when {
            spec != null -> infoOf(file.id, spec, states, documents, registered = false)
            else -> EntryInfo(
                file.id, activity.getString(R.string.config_state_invalid), StateLevel.BAD,
                now = activity.getString(R.string.lsp_info_invalid_now),
                steps = listOf(activity.getString(R.string.lsp_info_invalid_step)),
                errors = (check as? LanguageServers.Check.Invalid)?.let { listOf(describe(it)) }.orEmpty(),
                action = editAction(file.id),
            )
        }
        return Entry(
            file.id, file.id, stateText, level, detail,
            problem = problem,
            dimmed = spec?.enabled == false,
            selected = editing,
            current = spec != null && isCurrent(spec),
            busy = spec != null && statusOf(spec, states) == Status.STARTING,
            onMore = { showServerActions(file.id, file.id, stateText, level, registered = false) },
        ) { showStatus(file.id) }
    }

    private fun editAction(id: String) = PopupAction(activity.getString(R.string.config_edit)) { edit(id) }

    // #region how a server is doing

    // How a server is doing, for its row and its info. [isProblem]: said over \"Editing\", red.
    private enum class Status(val isProblem: Boolean = false) {
        OFF, RUNNING, STARTING, NOT_INSTALLED(true), FAILED(true), NOT_RUNNING
    }

    // How [spec] is doing: running, starting; off (whatever else: the user's choice); its
    // program not there (on); its start failed; else on, with no file of its open
    private fun statusOf(spec: LanguageServerSpec, states: Map<String, LspController.ServerState>): Status {
        val state = if (spec.enabled) states[spec.id] else null
        return when {
            state == LspController.ServerState.RUNNING -> Status.RUNNING
            state == LspController.ServerState.STARTING -> Status.STARTING
            !spec.enabled -> Status.OFF
            installations[spec.id] is LanguageServers.Installation.Missing -> Status.NOT_INSTALLED
            state == LspController.ServerState.FAILED -> Status.FAILED
            else -> Status.NOT_RUNNING
        }
    }

    // The info of [spec]: how it is and what to do about it, then its address, what it is
    // for, what it does, and a port another has too, extensions an earlier server takes.
    // [registered]: it has no file (registered in code), \"enabled\" is not the user's to edit.
    private fun infoOf(
        name: CharSequence,
        spec: LanguageServerSpec,
        states: Map<String, LspController.ServerState>,
        documents: Map<String, Int>,
        registered: Boolean,
    ): EntryInfo {
        val status = statusOf(spec, states)
        val (stateText, level) = stateOf(spec, states, documents)
        val extensions = spec.extensions.sorted().joinToString(" ") { ".$it" }
        val noFile = shadowed[spec.id]?.let { taken -> spec.extensions.isNotEmpty() && spec.extensions.all { it in taken } } == true
        val steps = ArrayList<CharSequence>()
        var action: PopupAction? = if (registered) null else editAction(spec.id)
        val now: CharSequence = when (status) {
            Status.OFF -> {
                if (!registered) steps += activity.getString(R.string.lsp_info_off_step)
                activity.getString(if (registered) R.string.lsp_popup_stopped_registered else R.string.lsp_info_off_now)
            }
            Status.RUNNING -> activity.getString(R.string.lsp_info_running_now, documents[spec.id] ?: 0)
            Status.STARTING -> activity.getString(R.string.lsp_popup_starting)
            Status.NOT_INSTALLED -> {
                val command = (installations[spec.id] as? LanguageServers.Installation.Missing)?.command.orEmpty()
                    .ifEmpty { spec.id }
                steps += activity.getString(R.string.lsp_info_install_step, spec.id)
                // auto: Termux's is found too; process: only the app's own (local)
                if (spec.connection is LanguageServerSpec.Connection.Auto) {
                    steps += activity.getString(R.string.lsp_info_install_termux, command)
                }
                openToolchain?.let { open -> action = PopupAction(activity.getString(R.string.lsp_info_open_toolchain)) { open() } }
                activity.getString(R.string.lsp_info_not_installed_now, command)
            }
            Status.FAILED -> {
                failureOf(spec, states)?.hint?.let { steps += it }
                steps += activity.getString(R.string.lsp_popup_failed_retry)
                failureOf(spec, states)?.let { describeFailure(it, whole = true) } ?: activity.getString(R.string.lsp_state_failed)
            }
            Status.NOT_RUNNING -> if (noFile) activity.getString(R.string.lsp_info_shadowed_now)
                else activity.getString(R.string.lsp_popup_not_running, extensions)
        }
        val details = ArrayList<CharSequence>()
        if (status == Status.RUNNING) details += describeRunning(spec)
        else details += labelled(R.string.lsp_running_address, connectionOf(spec.connection))
        if (extensions.isNotEmpty()) details += labelled(R.string.lsp_info_extensions, extensions)
        val warnings = ArrayList<CharSequence>()
        sharedPorts[spec.id]?.takeIf { it.isNotEmpty() }?.let { others ->
            warnings += labelled(
                R.string.lsp_problem_port,
                activity.getString(R.string.lsp_problem_port_text, others.joinToString("、"), LanguageServers.addressOf(spec) ?: ""),
            )
        }
        shadowed[spec.id]?.takeIf { it.isNotEmpty() }?.let { taken ->
            val all = spec.extensions.all { it in taken }
            warnings += labelled(
                R.string.lsp_problem_extensions,
                activity.getString(R.string.lsp_problem_extensions_text, describeTaken(taken)) +
                    if (all) activity.getString(R.string.lsp_problem_extensions_all) else "",
            )
        }
        // a conflict is worth a look, not a failure: yellow over green or grey
        val shownLevel = if (warnings.isNotEmpty() && (level == StateLevel.GOOD || level == StateLevel.QUIET)) StateLevel.WARN else level
        return EntryInfo(
            name, stateText, shownLevel,
            now = now, steps = steps, details = details + warnings, action = action,
            progress = if (status == Status.STARTING) EntryProblemPopup.INDETERMINATE else EntryProblemPopup.NO_PROGRESS,
        )
    }

    // A server that runs: its address (what each of its connections is connected to, and how
    // many there are to each), its projects (the one with the most open files, and how many
    // more there are and their files in all), what it does; a line each
    private fun describeRunning(spec: LanguageServerSpec): CharSequence {
        val all = running(spec.id)
        val text = SpannableStringBuilder()
        fun line(label: Int, value: CharSequence) {
            if (text.isNotEmpty()) text.append('\n')
            text.append(labelled(label, value))
        }
        line(R.string.lsp_running_address, addressesOf(spec, all))
        val main = all.maxWithOrNull(compareBy<LspController.RunningServer> { it.documents }.thenByDescending { it.root.path })
        if (main != null) {
            val files = all.sumOf { it.documents }
            line(
                R.string.lsp_running_root,
                if (all.size == 1) activity.getString(R.string.lsp_running_root_files, main.root.path, main.documents)
                else activity.getString(R.string.lsp_running_roots, main.root.path, all.size, files),
            )
        }
        all.firstOrNull()?.features?.let { line(R.string.lsp_running_features, featuresOf(it)) }
        return text
    }

    // the addresses the connections of a server that runs are connected to, each once
    private fun addressesOf(spec: LanguageServerSpec, all: List<LspController.RunningServer>): String {
        val separator = activity.getString(R.string.lsp_list_separator)
        val parts = all.mapNotNull { it.address }.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .map { (address, count) ->
                if (count > 1) activity.getString(R.string.lsp_running_address_connections, address, count) else address
            }
            .toMutableList()
        val pipes = all.count { it.address == null }
        if (pipes > 0) {
            parts += if (pipes > 1) activity.getString(R.string.lsp_running_stdio_processes, pipes)
            else activity.getString(R.string.lsp_running_stdio)
        }
        return parts.joinToString(separator).ifEmpty {
            LanguageServers.addressOf(spec) ?: activity.getString(R.string.lsp_running_stdio)
        }
    }

    // what a server said it does, in a few words: \"completion, hover, formatting\"
    private fun featuresOf(features: x.github.module.lsp.ServerFeatures): String {
        val names = buildList {
            if (features.completion) add(R.string.lsp_feature_completion)
            if (features.hover) add(R.string.lsp_feature_hover)
            if (features.signatureHelp) add(R.string.lsp_feature_signature)
            if (features.definition) add(R.string.lsp_feature_definition)
            if (features.references) add(R.string.lsp_feature_references)
            if (features.formatting || features.rangeFormatting) add(R.string.lsp_feature_formatting)
            if (features.rename) add(R.string.lsp_feature_rename)
            if (features.codeAction) add(R.string.lsp_feature_code_action)
            if (features.documentSymbol) add(R.string.lsp_feature_symbols)
        }
        if (names.isEmpty()) return activity.getString(R.string.lsp_feature_none)
        return names.joinToString(activity.getString(R.string.lsp_list_separator)) { activity.getString(it) }
    }

    // #endregion

    // [text] after a label in bold (\"Same port: \")
    private fun labelled(label: Int, text: CharSequence): CharSequence = SpannableStringBuilder().apply {
        append(activity.getString(label))
        setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(text)
    }

    // A tap on a server: its info by its row, and the files and states read again (the
    // window brought up to date with them, in place)
    private fun showStatus(id: String) {
        infos[id]?.let { showInfo(id, it) }
        reload()
    }

    // #region a server's actions

    // ⋮ / a long press: Edit, Delete (a server registered in code has no file: neither). No
    // Start or Stop: \"enabled\" in its file says that, and saving it starts or stops it.
    private fun showServerActions(id: String, name: CharSequence, state: CharSequence, level: StateLevel, registered: Boolean) {
        showActions(
            id, name, state, level,
            deletable = !registered,
            deleteDisabledSummary = activity.getString(R.string.lsp_action_delete_registered),
        )
    }

    // #endregion

    // #region why a server failed

    // why [spec] failed to start, if it is failed now (not since saved again, or off)
    private fun failureOf(spec: LanguageServerSpec, states: Map<String, LspController.ServerState>): LspController.Failure? =
        if (spec.enabled && states[spec.id] == LspController.ServerState.FAILED) shownFailures[spec.id] else null

    // A failure as the panel says it: \"Failed: <what went wrong>\". On a row ([whole] false)
    // the message in a line or two; in the info, whole (what to do is in its steps).
    private fun describeFailure(failure: LspController.Failure, whole: Boolean = false): CharSequence {
        val message = if (whole || failure.message.length <= ROW_MESSAGE_CHARS) failure.message
        else failure.message.take(ROW_MESSAGE_CHARS).trimEnd() + "…"
        return SpannableStringBuilder().apply {
            val label = activity.getString(R.string.lsp_failure_reason)
            append(label)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(message)
        }
    }

    // #endregion

    // How a server is doing in this session, in the word of its level
    private fun stateOf(
        spec: LanguageServerSpec,
        states: Map<String, LspController.ServerState>,
        documents: Map<String, Int>,
    ): Pair<String, StateLevel> {
        val count = documents[spec.id] ?: 0
        return when (statusOf(spec, states)) {
            // off: the user's choice, grey (and grey too when its program is not there either)
            Status.OFF -> activity.getString(
                if (installations[spec.id] is LanguageServers.Installation.Missing) R.string.lsp_state_off_not_installed
                else R.string.lsp_state_stopped
            ) to StateLevel.QUIET
            Status.RUNNING -> (
                if (count > 0) activity.getString(R.string.lsp_state_running_documents, count)
                else activity.getString(R.string.lsp_state_running)
                ) to StateLevel.GOOD
            Status.STARTING -> activity.getString(R.string.lsp_state_starting) to StateLevel.WARN
            Status.NOT_INSTALLED -> activity.getString(R.string.lsp_state_not_installed) to StateLevel.BAD
            // reached at a port: it could not connect; else it could not start
            Status.FAILED -> activity.getString(
                if (spec.connection is LanguageServerSpec.Connection.Socket) R.string.lsp_state_connect_failed
                else R.string.lsp_state_failed
            ) to StateLevel.BAD
            // on, no file of its open: it starts with the first
            Status.NOT_RUNNING -> activity.getString(R.string.lsp_state_idle) to StateLevel.WARN
        }
    }

    // the extensions of enabled servers that an earlier one has (render): id → extension → that one
    private var shadowed: Map<String, Map<String, String>> = emptyMap()

    // the enabled servers reached at the same address as others (render): id → the others
    private var sharedPorts: Map<String, List<String>> = emptyMap()

    // its extensions, and how it is reached
    private fun detailOf(spec: LanguageServerSpec): String = buildString {
        append(spec.extensions.sorted().joinToString(" ") { ".$it" })
        append(" · ")
        append(connectionOf(spec.connection))
    }

    // extensions taken by other servers, in a few words: \".py → pyright\"
    private fun describeTaken(taken: Map<String, String>): String =
        taken.entries.joinToString("、") { (extension, owner) -> ".$extension → $owner" }

    // how it is reached, in a line
    private fun connectionOf(connection: LanguageServerSpec.Connection): String = when (connection) {
        is LanguageServerSpec.Connection.Auto ->
            activity.getString(R.string.lsp_connection_auto, connection.command.joinToString(" "), connection.port)
        is LanguageServerSpec.Connection.Process ->
            activity.getString(R.string.lsp_connection_process, connection.command.joinToString(" "))
        is LanguageServerSpec.Connection.Socket -> {
            val address = "${connection.host}:${connection.port}"
            connection.termux?.let { activity.getString(R.string.lsp_connection_termux, it.command, address) }
                ?: activity.getString(R.string.lsp_connection_socket, address)
        }
    }

    // #endregion

    private companion object {
        // how often the states of the servers are looked at while the panel is up
        const val STATE_POLL_MILLIS = 1_000L

        // how much of why a server failed its row shows (its info shows it whole)
        const val ROW_MESSAGE_CHARS = 160
    }
}
