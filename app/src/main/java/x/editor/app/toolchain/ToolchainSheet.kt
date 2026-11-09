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

package x.editor.app.toolchain

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import x.editor.app.R
import x.editor.app.completion.SheetSchemas
import x.editor.app.config.ConfigDialogs
import x.editor.app.config.EntryActionsDialog
import x.editor.app.config.EntryProblemPopup
import x.editor.app.config.OperationGate
import x.editor.app.config.PanelHelp
import x.editor.app.config.PanelOp
import x.editor.app.config.PopupAction
import x.editor.app.config.askNewName
import x.editor.app.config.describe
import x.editor.app.config.SheetFileEditor
import x.editor.app.config.toastBusy
import x.editor.app.view.CodeEditorView
import x.editor.app.view.CollapseAnimator
import x.github.module.editor.view.EditorView
import x.editor.app.view.MaxHeightRecyclerView
import x.editor.app.view.HandleDragSheetDialog
import x.editor.app.view.showSettled
import x.editor.app.view.BusyDotsDrawable
import x.editor.app.view.setBusyDots
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import com.google.android.material.R as MaterialR

/**
 * The toolchain (menu → Toolchain), all on one sheet: the toolchains of packages.json,
 * installed into X Editor's own usr. Termux is not looked at here: what the user wants of it
 * is installed by the user, in Termux (`pkg install clang`).
 *
 *  - **Show**: all the toolchains of the list, or those installed into X Editor only.
 *  - **The toolchains of packages.json**, each with what it is and where it is: installed
 *    into X Editor (the installed mark: a menu to reinstall or uninstall), to update (a
 *    newer version in the list), to install (its source, version and SHA-256 come from the
 *    list, nothing is typed in). An install shows its progress in its row. What is
 *    installed but no longer in the list is shown too, to be uninstalled.
 *
 * The toolchains are in a list of a height of its own, so that many do not make the sheet
 * tall. A tap on a row (a ripple) shows by the row what it says of its version
 * (EntryProblemPopup): `java -version`, `clang --version`, run where it is installed, in X
 * Editor (ToolVersions; the package's "version_command", if it has one); while it is being
 * installed or updated, how far that is instead (a bar, what is being done, the version,
 * where from and where to), its version once it is done (showRowProgress).
 * packages.json is edited on the sheet (SheetFileEditor), hidden at first: Edit of a
 * toolchain shows it at that toolchain, New puts a toolchain at its end, Hide hides it (it closes upwards and fades, CollapseAnimator, as the
 * editor of the language servers does); Restore template puts the list the app ships back
 * (assets/templates/toolchain/packages.jsonc), Save is on only while it is right (JSON, and
 * every toolchain right), and the check line says beforehand what saving will install or
 * update. Saved, what is new or changed in it is installed into X Editor then (what can be,
 * and is not there or is of another version: onCatalogSaved). Update reads it all again and
 * updates what is installed into X Editor to the versions of the list (refreshAndUpdate); the
 * list showing nothing, Update is not enabled. How those go is shown in their rows (the ring
 * in place of the icon, a word under the name) and by Update's dots going round. A window of
 * them all (EntryProblemPopup, sticky: Close, Back; a bar, what each is doing, what failed and
 * why: refreshTaskPopup) comes up by Save as soon as a save installs something, by the list
 * when Update is tapped again before it is done, and by itself when one fails; it says so
 * when all is done, else a short word does.
 * The icon on the right of a row only says how it stands (to install, to update,
 * installed); ⋮ by it, or a long press, opens its dialog (EntryActionsDialog, showActions),
 * the one place for every action on it: Edit, Install, Update / Reinstall, Uninstall, and
 * Remove (last, red: one installed into X Editor loses its files and its object in
 * packages.json), Remove off while packages.json has changes not saved in the editor. A tap
 * on a row shows its version by it, with the next step at its foot (Update, Install, Edit).
 * New (by Update) puts a toolchain at the end of packages.json in the editor. The buttons of
 * Show choose what the list shows: all, or what is installed into X Editor; the list keeps
 * the height of the whole list, and when it has nothing to show, an icon and a few words in
 * its middle say so; a tap on the icon shows why and what to do, in a window under it
 * (EntryProblemPopup, as the footer's). The footer is a line of it, ⋯ by it: all of it in a
 * window (EntryProblemPopup), a point a line.
 *
 * One thing at a time ([gate], [isWorking]): while packages.json is written, a toolchain is
 * uninstalled or removed, or what a save or Update started is installing, the editor is locked
 * (it can be read: EditorLockView), nothing else can be begun, and of an entry only its info and
 * Stop are on. Stop is the way out of a long one: the install is cancelled (what was
 * downloaded is kept, to go on with). What a set started goes on side by side, as one
 * operation. The sheet can be closed while it goes on: opened again it is this one
 * (EditorActivity.showToolchain), which knows what goes on; what was typed goes with the closing.
 * Closed, how it goes is shown in a small window under the editor's toolbar, at its end
 * (EntryProblemPopup, refreshEditorPopup): what is being installed and how far, what failed,
 * what got done, and Open toolchain.
 *
 * @param styleEditor gives the editor of the sheet the look of the editors and their floating bar
 * @param highlight sets the highlighting of the editor of the sheet up, for its text as a file of the name given
 * @param onToolchainChanged the list, or what is installed of it, changed: language servers look
 *   for their programs again (LspController.onToolchainChanged), also after the sheet is closed
 * @param statusAnchor what the small window of what goes on is shown under while the sheet is
 *   closed (the editor's toolbar: at its end, top right); null: none shown
 * @param openSheet the sheet opened from that window (EditorActivity.showToolchain: this one
 *   while it has something to show, else a new one)
 *
 * The installs themselves run in the process (ToolchainJobs), not in [scope]: the screen
 * recreated (another theme, the night mode), they go on, and the sheet of the new screen takes
 * them over ([adopt]); this one is let go of ([detach]).
 */
internal class ToolchainSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    styleEditor: (EditorView) -> Unit,
    highlight: suspend (CodeEditorView, String) -> Unit,
    private val onToolchainChanged: () -> Unit = {},
    private val statusAnchor: () -> View? = { null },
    private val openSheet: () -> Unit = {},
    // an install or update started (the screen asks for the permission to notify, the first time)
    private val onInstallStarted: () -> Unit = {},
) {
    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_toolchain, null)
    // dragged by its handle only, as every sheet of the developer tools
    private val dialog = HandleDragSheetDialog(activity).apply {
        setDragHandle(view.findViewById(R.id.toolchain_drag_handle))
    }
    private val list = view.findViewById<MaxHeightRecyclerView>(R.id.toolchain_packages)
    private val adapter = PackageAdapter()
    // the list showing nothing: an icon (a tap: why, in emptyPopup), a few large words
    private val empty = view.findViewById<View>(R.id.toolchain_empty)
    private val emptyIcon = view.findViewById<View>(R.id.toolchain_empty_icon)
    private val emptyImage = view.findViewById<ImageView>(R.id.toolchain_empty_image)
    private val emptyProgress = view.findViewById<CircularProgressIndicator>(R.id.toolchain_empty_progress)
    private val emptyTitle = view.findViewById<TextView>(R.id.toolchain_empty_title)
    private val listFrame = view.findViewById<FrameLayout>(R.id.toolchain_list_frame)
    private val editorFrame = view.findViewById<FrameLayout>(R.id.toolchain_editor)
    // the editor hidden: it closes upwards and fades, as the language servers' does
    private val editorCollapse = CollapseAnimator(editorFrame)
    private val listHeader = view.findViewById<View>(R.id.toolchain_list_header)

    // Update (by the list): its icon the three dots going round while an update goes on
    // (the list read again, and what it started not yet done)
    private val updateButton = view.findViewById<MaterialButton>(R.id.toolchain_refresh)
    private val updateDots = BusyDotsDrawable.ofDp(activity)

    // the version of a toolchain tapped, by its row; and its id (null: none shown)
    private val rowPopup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)
    private var rowPopupId: String? = null

    // What a toolchain said of its version, by id (null in it: being asked); forgotten when
    // the list is read again (what is installed may have changed), and what is asked then
    // not kept ([versionsGeneration])
    private val versions = HashMap<String, List<ToolVersions.Section>?>()
    private var versionsGeneration = 0

    // What a save of packages.json, or Update, started (in order, into X Editor), whether
    // they are updates (Update), what Update found up to date, and whether the set is still
    // being made ([batchPreparing]: the list read again) or its end not yet told
    // ([batchOpen]). A word when all of it is done, if none failed.
    private data class Task(val id: String, val name: String)
    private val tasks = ArrayList<Task>()
    private var tasksAreUpdates = false
    private var upToDateCount = 0
    private var batchPreparing = false
        set(value) {
            field = value
            syncLock()
        }
    private var batchOpen = false
    // which set is being made: Stop all (or a new set) makes what an older one was about to
    // start (after the list is read) not started
    private var batchSerial = 0

    // What goes on (the installs and updates into X Editor), how far, and what failed, with
    // why: shown by Save for a save that installs something (with the editor shown), by the
    // list when Update is tapped again ([taskPopupByList]), and when something fails, till the
    // user closes it; shown again when another fails. The failures it was shown for (the
    // Progress, by identity: one tried again and failing again is another).
    private val taskPopup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)
    private var taskPopupByList = false
    private val announced: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    // why the list shows nothing, and what to do, by its icon (closed once it shows something)
    private val emptyPopup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)

    // The footer: a line of it on the sheet, all of it in this window (by ⋯, or a tap on the line)
    private val footerRow = view.findViewById<View>(R.id.toolchain_footer_row)
    private val footerPopup = EntryProblemPopup(activity, dialog.onBackPressedDispatcher)

    private val warn = 0xFFE5C07B.toInt()
    private val bad = 0xFFE06C75.toInt()
    // the mark of an installed toolchain
    private val installedColor = 0xFF4CAF50.toInt()
    private val muted = MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0xFF9E9E9E.toInt())
    private val primary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())

    private val abis = Build.SUPPORTED_ABIS.toList()

    // what is shown: the list, and what is installed of it
    private var catalog: List<ToolPackage> = emptyList()
    private var installed: List<InstalledPackage> = emptyList()
    // the ids of the list, those switched off too (a new one has none of these)
    private var listedIds: Set<String> = emptySet()

    // which toolchains the list shows: all, or those installed into X Editor; the buttons of Show
    private enum class Filter { ALL, INSTALLED }
    private var filter: Filter = Filter.ALL

    // how many rows the whole list has, and its height when it was last shown whole (0: not
    // yet): the list keeps that height whatever it shows, so the sheet does not jump
    private var allCount = -1
    private var fullListHeight = 0

    // the installs going on (or failed): their state, by id; [pkg] as it was started (a save
    // that changes it, or takes it out of the list, stops it: stopStale); [hadBefore]: it was
    // installed already (an update)
    private class Progress(
        val pkg: ToolPackage,
        val hadBefore: Boolean,
        var text: String,
        var percent: Int? = null,
        var error: String? = null,
    ) {
        // what waits for it here (cancelled: only no longer waited for), and the install
        // itself, in the process (ToolchainJobs: it goes on without this sheet)
        var job: Job? = null
        var run: ToolchainJobs.LocalRun? = null
    }
    private val installing = LinkedHashMap<String, Progress>()

    // what is posted to the main thread: the end of the small window in the editor (not
    // view.post: closed, the sheet's view is not in a window, and what is posted to it may not run)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // the rows: a toolchain of the list (and what is installed of it), or one installed but not listed
    private var entries: List<Pair<ToolPackage?, InstalledPackage?>> = emptyList()

    // what goes on on the sheet that is not an install: one thing at a time (and not beside an install: isWorking)
    private val gate = OperationGate().also { gate -> gate.onChanged = { syncLock() } }

    // packages.json, edited on the sheet; whether it is shown
    private val fileEditor = SheetFileEditor(
        activity, scope, gate, { dialog.isShowing }, styleEditor, highlight, rowPopup,
        check = { _, text -> ToolchainInstaller.checkCatalog(text)?.let { describeCatalog(it) } ?: emptyList() },
        afterSave = { _, text -> describeSave(text) },
        defaultOf = { ToolchainInstaller.exampleCatalog(activity) },
        write = { _, text -> ToolchainInstaller.writeCatalog(activity, text) },
        onSaved = { _, text -> onCatalogSaved(text) },
        // what a toolchain may say, as it is typed
        schemaOf = { SheetSchemas.toolchain(activity) },
        // As soon as the user edits the config file, the popup shown before is closed (the
        // progress is still in the rows; tapping "Update" shows it again)
        onEdited = { if (taskPopup.isShowing) taskPopup.dismiss() },
    )
    private var editorShown = false

    // show() was called: what it sets up is set up
    private var shownBefore = false
    // what the editor holds was let go of (the sheet closed, and nothing going on)
    private var released = false

    private val newButton = view.findViewById<View>(R.id.toolchain_new)
    private val filterGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.toolchain_filter)

    // #region one thing at a time

    // What goes on, in a few words; null: nothing
    private fun busyMessage(): String? {
        gate.current?.let { return it.describe(activity) }
        if (!isWorking()) return null
        val names = installing.values.filter { it.error == null }.map { it.pkg.name }
        return when (names.size) {
            0 -> activity.getString(R.string.toolchain_busy_preparing)
            1 -> activity.getString(R.string.toolchain_busy_one, names.first())
            else -> activity.getString(R.string.toolchain_busy_many, names.size)
        }
    }

    // Whether something goes on, and then what was asked for is not done: says so
    private fun refusedWhileBusy(): Boolean {
        toastBusy(activity, busyMessage() ?: return false)
        return true
    }

    // [op] on [subject] begun; something else going on: said, and null
    private fun beginOrRefuse(op: PanelOp, subject: String): OperationGate.Ticket? {
        if (refusedWhileBusy()) return null
        return gate.begin(op, subject)
    }

    // The editor locked while something goes on (saying what), New off. What the list shows
    // can be chosen at any time.
    private fun syncLock() {
        val busy = busyMessage()
        fileEditor.setLocked(busy)
        newButton.isEnabled = busy == null
    }

    // #endregion

    // Shown again after it was closed while it worked: as it is now
    private fun showAgain() {
        if (dialog.isShowing) return
        // (it opens all the way again: closed by a drag, it was left hidden)
        dialog.setDraggedByHandleOnly(true)
        listRead = false
        dialog.showSettled { listRead }
        syncUpdateButton()
        reload()
    }

    // what the editor holds let go of: the sheet is closed, nothing goes on that brings it back
    private fun releaseEditor() {
        if (released) return
        released = true
        fileEditor.release()
    }

    fun show() {
        // shown: what goes on is on the sheet, not in the editor's window
        hideEditorPopup()
        editorPopupMuted = false
        doneWhileClosed.clear()
        if (shownBefore) {
            showAgain()
            return
        }
        shownBefore = true
        // Update: read again, then what is installed into X Editor updated, how it goes by the
        // list; tapped again before all of it is done, how far it is
        updateButton.setOnClickListener {
            when {
                isWorking() -> showTaskPopup(byList = true)
                refusedWhileBusy() -> Unit
                entries.isNotEmpty() -> refreshAndUpdate()
            }
        }
        emptyIcon.setOnClickListener { toggleEmptyDetails() }
        view.findViewById<View>(R.id.toolchain_new).setOnClickListener { askNew() }
        fileEditor.setOnCollapse { hideEditor() }
        view.findViewById<View>(R.id.toolchain_footer_more).setOnClickListener { toggleFooter() }
        view.findViewById<View>(R.id.toolchain_footer_text).setOnClickListener { toggleFooter() }
        fileEditor.setHelp(activity.getString(R.string.toolchain_help))
        fileEditor.showNone(activity.getString(R.string.toolchain_editor_title, ToolchainInstaller.CATALOG), "")
        editorFrame.addView(fileEditor.view)
        list.layoutManager = LinearLayoutManager(activity)
        // a row is bound again in place (an install's progress): nothing to animate
        list.itemAnimator = null
        list.adapter = adapter
        list.setMaxHeight((LIST_MAX_HEIGHT_DP * activity.resources.displayMetrics.density).toInt())
        // the height of the whole list, kept for the other filters
        list.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (filter == Filter.ALL && list.visibility == View.VISIBLE && height > 0 && height != fullListHeight) {
                fullListHeight = height
                listFrame.post { keepHeight() }
            }
        }
        setUpFilter()
        dialog.setOnDismissListener {
            updateDots.stop()
            rowPopup.dismiss()
            taskPopup.dismiss()
            footerPopup.dismiss()
            emptyPopup.dismiss()
            // a set being made goes no further
            batchPreparing = false
            if (wantsReopen()) {
                // it goes on (or what failed is to be seen), and this sheet is shown again for
                // it: only what was typed goes
                fileEditor.hideKeyboard()
                fileEditor.showNone(activity.getString(R.string.toolchain_editor_title, ToolchainInstaller.CATALOG), "")
                editorShown = false
                editorCollapse.setVisible(false)
                // how it goes, in a small window in the editor (once the sheet is gone)
                if (isWorking() && !detached) mainHandler.post { onClosedWhileWorking() }
            } else {
                releaseEditor()
            }
        }
        dialog.setContentView(view)
        // opens all the way: a scroll of the sheet never moves it
        dialog.setDraggedByHandleOnly(true)
        // packages.json with changes not saved: closing asks first
        dialog.onCloseRequest = { close ->
            val running = gate.current
            if (running != null && !running.op.closable) {
                // a write going on: not to be closed on, nor asked about
                toastBusy(activity, running.describe(activity))
                true
            } else if (!fileEditor.hasUnsavedChanges()) {
                false
            } else {
                ConfigDialogs.confirmDiscard(activity, activity.getString(R.string.config_discard_close, ToolchainInstaller.CATALOG)) { close() }
                true
            }
        }
        // not shown before the list is read (reload): it starts empty, and its height is only
        // known once it has been laid out with its rows (keepHeight)
        dialog.showSettled { listRead }
        PanelHelp(
            activity, scope, footerPopup, "toolchain",
            activity.getString(R.string.toolchain_title),
            activity.getString(R.string.toolchain_help_what),
            activity.getString(R.string.toolchain_help_how),
        ) { ToolchainInstaller.catalogFile(activity).path }.attach(
            view.findViewById(R.id.toolchain_help), view.findViewById(R.id.toolchain_title_row)
        ) { dialog.isShowing }
        reload()
    }

    // what the list shows (all, or what is installed into X Editor), with a line about it
    private fun setUpFilter() {
        val group = filterGroup
        val hint = view.findViewById<TextView>(R.id.toolchain_filter_hint)
        val ids = mapOf(
            Filter.ALL to R.id.toolchain_filter_all,
            Filter.INSTALLED to R.id.toolchain_filter_installed,
        )
        fun describe(chosen: Filter) {
            hint.setText(when (chosen) {
                Filter.ALL -> R.string.toolchain_filter_all_hint
                Filter.INSTALLED -> R.string.toolchain_filter_installed_hint
            })
        }
        group.check(ids.getValue(filter))
        describe(filter)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = ids.entries.firstOrNull { it.value == id }?.key ?: return@addOnButtonCheckedListener
            describe(chosen)
            filter = chosen
            render()
        }
    }

    // #region the list

    // the list and what is installed, read again; then [then]. One reading at a time, in the
    // order asked for: an older one ending last left what it read.
    private fun reload(then: (() -> Unit)? = null) {
        scope.launch {
            readLock.withLock {
                // what packages.json no longer uses (changed in a tab, or left by an app killed
                // mid-download), cleared as the list is read
                sweepCache(null)
                val problems = ArrayList<String>()
                var ids: Set<String> = emptySet()
                val (packages, have) = withContext(Dispatchers.IO) {
                    val packages = runCatching { ToolchainInstaller.catalog(activity, problems) }.getOrElse { e ->
                        problems += e.message ?: e.toString()
                        emptyList()
                    }
                    ids = ToolchainInstaller.catalogIds(activity)
                    packages to runCatching { ToolchainInstaller.installed(activity) }.getOrDefault(emptyList())
                }
                if (problems.isNotEmpty()) Toast.makeText(activity, "packages.json: " + problems.joinToString("\n"), Toast.LENGTH_LONG).show()
                catalog = packages
                installed = have
                listedIds = ids
                forgetVersions()
                render()
                listRead = true
            }
            then?.invoke()
        }
    }

    private val readLock = Mutex()

    // whether the list has been read since the sheet was shown
    private var listRead = false

    // The download cache swept by [text] (packages.json as saved; null: as on disk): what no
    // toolchain of it downloads any more goes, what is still to be gone on with stays, and so
    // does what an install going on uses (ToolchainInstaller.sweepCache)
    private suspend fun sweepCache(text: String?) {
        val inUse = ToolchainJobs.cacheInUse()
        withContext(Dispatchers.IO) { runCatching { ToolchainInstaller.sweepCache(activity, text, inUse) } }
    }

    // a row for each toolchain of the list, then for what is installed but not listed; of
    // these, what the filter shows: all, or what is installed into X Editor
    @SuppressLint("NotifyDataSetChanged")
    private fun render() {
        val listed = catalog.map { it.id }.toSet()
        val all = catalog.map { pkg -> pkg to installed.firstOrNull { it.pkg.id == pkg.id } } +
            installed.filter { it.pkg.id !in listed }.map { null to it }
        if (all.size != allCount) {
            // more or fewer rows: the height of the whole list is to be measured again
            allCount = all.size
            fullListHeight = 0
        }
        entries = when (filter) {
            Filter.ALL -> all
            Filter.INSTALLED -> all.filter { it.second != null }
        }
        val nothing = entries.isEmpty()
        if (nothing) showEmpty(emptyState())
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
        list.visibility = if (nothing) View.GONE else View.VISIBLE
        // nothing shown, nothing to update (but how far what goes on is)
        syncUpdateButton()
        adapter.notifyDataSetChanged()
        keepHeight()
        refreshRowPopup()
        // why it is empty: gone once it is not, else saying why it is now (another filter
        // chosen), in place
        if (nothing) {
            if (emptyPopup.isShowing) showEmptyDetails()
        } else {
            emptyPopup.dismiss()
        }
    }

    // Why the list shows nothing, for the filter: its icon and colour (a ring in its place
    // while it is busy), a few large words, and all of why and what to do (by the icon)
    private class EmptyState(val icon: Int, val color: Int, val title: Int, val detail: Int, val busy: Boolean = false)

    private fun emptyState(): EmptyState = when (filter) {
        Filter.ALL -> EmptyState(R.drawable.ic_data_object, primary, R.string.toolchain_empty_title_catalog, R.string.toolchain_catalog_empty)
        Filter.INSTALLED -> EmptyState(R.drawable.ic_download, primary, R.string.toolchain_empty_title_local, R.string.toolchain_empty_local)
    }

    // the middle of the empty list as [state] says: the icon on a round tile of its colour
    // (a ripple on it: it is tapped), or a ring of that colour; the words under it
    private fun showEmpty(state: EmptyState) {
        val density = activity.resources.displayMetrics.density
        val tile = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(state.color, 0x24))
            setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(state.color, 0x55))
        }
        emptyIcon.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(state.color, 0x40)), tile, null)
        emptyImage.setImageResource(state.icon)
        emptyImage.imageTintList = ColorStateList.valueOf(state.color)
        emptyProgress.setIndicatorColor(state.color)
        emptyImage.visibility = if (state.busy) View.INVISIBLE else View.VISIBLE
        emptyProgress.visibility = if (state.busy) View.VISIBLE else View.GONE
        emptyTitle.setText(state.title)
    }

    // Why the list shows nothing, all of it, under its icon, as the footer's ⋯ shows the
    // footer (its colour, a point a line, a tap outside closes it); the icon again closes it
    private fun toggleEmptyDetails() {
        if (emptyPopup.isShowing) {
            emptyPopup.dismiss()
            return
        }
        showEmptyDetails()
    }

    // (shown already: what it says changed in place)
    private fun showEmptyDetails() {
        if (!dialog.isShowing || entries.isNotEmpty() || !emptyIcon.isAttachedToWindow) return
        val state = emptyState()
        emptyPopup.show(
            emptyIcon, activity.getString(state.title), bulletText(activity.getString(state.detail)), primary,
            progress = if (state.busy) EntryProblemPopup.INDETERMINATE else EntryProblemPopup.NO_PROGRESS,
            centered = true,
        )
    }

    // The list as tall as the whole list (as measured when shown whole, else about a row each,
    // no taller than its own limit), whatever it shows: switching keeps the sheet's height.
    private fun keepHeight() {
        val density = activity.resources.displayMetrics.density
        val estimate = (allCount.coerceAtLeast(1) * ROW_HEIGHT_DP * density).toInt()
            .coerceAtMost((LIST_MAX_HEIGHT_DP * density).toInt())
        val height = if (fullListHeight > 0) fullListHeight else estimate
        if (listFrame.minimumHeight != height) listFrame.minimumHeight = height
    }

    private class PackageHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class PackageAdapter : RecyclerView.Adapter<PackageHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            PackageHolder(LayoutInflater.from(activity).inflate(R.layout.item_toolchain_package, parent, false))

        override fun onBindViewHolder(holder: PackageHolder, position: Int) {
            val (pkg, have) = entries[position]
            bind(holder.itemView, pkg, have)
        }
    }

    private fun bind(row: View, pkg: ToolPackage?, have: InstalledPackage?) {
        val id = pkg?.id ?: have!!.pkg.id
        // a tap: all of it, by the row (the ripple is in the row's foreground)
        row.setOnClickListener { showRow(row, id) }
        // ⋮, or a long press: every action on it
        row.setOnLongClickListener {
            showActions(id)
            true
        }
        row.findViewById<View>(R.id.package_more).setOnClickListener { showActions(id) }
        val kind = pkg?.kind ?: have!!.pkg.kind
        val (letter, color) = badge(kind)
        row.findViewById<TextView>(R.id.package_kind).apply {
            text = letter
            setTextColor(color)
            background = GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(ColorUtils.setAlphaComponent(color, 0x33))
            }
        }
        row.findViewById<TextView>(R.id.package_name).text = pkg?.name ?: have!!.pkg.name
        val installable = pkg?.isInstallable(abis) == true
        val newVersion = pkg?.version
        val update = have != null && installable && newVersion != null && newVersion != have.pkg.version
        row.findViewById<TextView>(R.id.package_version).text = when {
            update -> "${have?.pkg?.version ?: "?"} → $newVersion"
            have != null -> have.pkg.version.orEmpty()
            installable -> newVersion.orEmpty()
            else -> ""
        }

        val detail = row.findViewById<TextView>(R.id.package_detail)
        val bar = row.findViewById<CircularProgressIndicator>(R.id.package_progress)
        val action = row.findViewById<MaterialButton>(R.id.package_action)
        val progress = installing[id]
        if (progress != null && progress.error == null) {
            detail.text = progress.text
            detail.setTextColor(muted)
            showRing(bar, progress.percent)
            action.visibility = View.INVISIBLE
            return
        }
        bar.visibility = View.GONE
        val error = progress?.error
        detail.text = error ?: describe(pkg, have, installable)
        detail.setTextColor(if (error != null) bad else muted)

        // how it stands, as an icon only (what can be done is under ⋮ and in its info)
        action.visibility = View.VISIBLE
        action.isEnabled = true
        action.isClickable = false
        action.isFocusable = false
        when {
            update -> setAction(action, R.drawable.ic_update, warn, R.string.toolchain_update)
            have != null -> setAction(action, R.drawable.ic_check_circle, installedColor, R.string.toolchain_installed)
            installable -> setAction(action, R.drawable.ic_download, primary, R.string.toolchain_install)
            else -> action.visibility = View.INVISIBLE
        }
    }

    // The ring in the action's place: how far ([percent]), or turning (null: not known). Its
    // mode is changed only while it is not shown, as the indicator wants.
    private fun showRing(bar: CircularProgressIndicator, percent: Int?) {
        val indeterminate = percent == null
        if (bar.visibility != View.VISIBLE) {
            if (bar.isIndeterminate != indeterminate) bar.isIndeterminate = indeterminate
            if (percent != null) bar.setProgressCompat(percent, false)
            bar.visibility = View.VISIBLE
            return
        }
        when {
            percent == null -> if (!bar.isIndeterminate) {
                bar.visibility = View.INVISIBLE
                bar.isIndeterminate = true
                bar.visibility = View.VISIBLE
            }
            else -> {
                if (bar.isIndeterminate) bar.isIndeterminate = false
                bar.setProgressCompat(percent, true)
            }
        }
    }

    private fun setAction(button: MaterialButton, icon: Int, color: Int, description: Int) {
        button.setIconResource(icon)
        button.iconTint = ColorStateList.valueOf(color)
        button.contentDescription = activity.getString(description)
        button.setOnClickListener(null)
    }

    // where it is: installed into X Editor (its programs), or installable; else its description
    private fun describe(pkg: ToolPackage?, have: InstalledPackage?, installable: Boolean): String {
        val parts = ArrayList<String>()
        when {
            have != null -> parts += activity.getString(R.string.toolchain_in_local) +
                have.programs().takeIf { it.isNotEmpty() }?.let { "  " + it.joinToString(" ") }.orEmpty()
            installable -> parts += activity.getString(R.string.toolchain_installable)
        }
        if (pkg == null) {
            parts += activity.getString(R.string.toolchain_not_listed)
            return parts.joinToString(" · ")
        }
        if (parts.isEmpty()) {
            parts += pkg.description?.takeIf { it.isNotBlank() }
                ?: activity.getString(R.string.toolchain_no_source)
        }
        return parts.joinToString(" · ")
    }

    // Uninstall, once asked: its files in X Editor go, its object in packages.json stays
    private fun confirmUninstall(have: InstalledPackage) {
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.toolchain_uninstall_title, have.pkg.name),
            activity.getString(R.string.toolchain_uninstall_message, have.files.size),
            activity.getString(R.string.toolchain_uninstall),
        ) {
            val ticket = beginOrRefuse(PanelOp.UNINSTALL, have.pkg.name) ?: return@confirmDelete
            scope.launch {
                try {
                    val id = have.pkg.id
                    val failure = withContext(Dispatchers.IO) {
                        runCatching { ToolchainInstaller.uninstall(activity, id) }.exceptionOrNull()
                    }
                    if (failure != null) {
                        Toast.makeText(activity, activity.getString(R.string.toolchain_remove_failed, have.pkg.name, failure.message ?: failure.toString()), Toast.LENGTH_LONG).show()
                    } else {
                        // what failed of it before (a row still saying so) goes with it
                        installing[id]?.takeIf { it.error != null }?.let { installing.remove(id) }
                        tasks.removeAll { it.id == id && !isBusy(it) }
                        toolchainChanged()
                    }
                    if (!dialog.isShowing) return@launch
                    refreshTaskPopup()
                    reload()
                } finally {
                    gate.end(ticket)
                }
            }
        }
    }

    // [id] no longer installed: cancelled, and waited for (what it downloaded is kept, to go on
    // with the next time the same version is installed: DownloadCache; a save that changes it
    // clears it; unpacking is not cut short: ToolchainInstaller.install). What it had of the
    // set goes; the rest goes on.
    private fun stop(id: String) {
        val ticket = gate.begin(PanelOp.STOP, nameOf(id))
        if (ticket == null) {
            refusedWhileBusy()
            return
        }
        val progress = installing[id]?.takeIf { it.error == null }
        if (progress != null) installing.remove(id)
        tasks.removeAll { it.id == id }
        // the install itself (in the process), then the waiting for it here
        val stopping = progress?.run?.let { ToolchainJobs.cancel(it) }
        progress?.job?.cancel()
        // nothing left of the set: no word that it is all done, the word is that it was stopped
        if (tasks.none { isBusy(it) }) batchOpen = false
        updateRow(id)
        taskChanged()
        scope.launch {
            try {
                stopping?.join()
                progress?.job?.join()
                // (stopped while it was unpacked it is installed all the same)
                toolchainChanged()
                if (!dialog.isShowing) return@launch
                Toast.makeText(activity, activity.getString(R.string.toolchain_stopped, nameOf(id)), Toast.LENGTH_SHORT).show()
                reload()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // Stop all (the window of what goes on): every install and update at once, once asked
    // (what it does to each kind is said: a slow download of hours can be given up in one go)
    private fun askStopAll() {
        val local = installing.values.count { it.error == null }
        if (local == 0) {
            // only a set being made (the list read): nothing to ask about
            stopAll()
            return
        }
        val message = activity.getString(R.string.toolchain_stop_all_message, local)
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.toolchain_stop_all_title),
            message,
            activity.getString(R.string.toolchain_stop_all),
        ) { stopAll() }
    }

    // Every install and update stopped, as stop does one: cancelled (what was downloaded
    // kept, to go on with); a set being made goes no further
    private fun stopAll() {
        val ticket = gate.begin(PanelOp.STOP, activity.getString(R.string.toolchain_stop_all_subject))
        if (ticket == null) {
            refusedWhileBusy()
            return
        }
        batchSerial++
        batchPreparing = false
        val locals = installing.filterValues { it.error == null }
        locals.keys.forEach { installing.remove(it) }
        val ids = locals.keys
        tasks.removeAll { it.id in ids || !isBusy(it) }
        batchOpen = false
        val stopping = locals.values.mapNotNull { p -> p.run?.let { ToolchainJobs.cancel(it) } }
        locals.values.forEach { it.job?.cancel() }
        ids.forEach { updateRow(it) }
        taskChanged()
        taskPopup.dismiss()
        scope.launch {
            try {
                stopping.forEach { it.join() }
                locals.values.forEach { it.job?.join() }
                // (one stopped while it was unpacked is installed all the same)
                if (locals.isNotEmpty()) toolchainChanged()
                if (!dialog.isShowing) return@launch
                Toast.makeText(activity, activity.getString(R.string.toolchain_stopped_all, ids.size), Toast.LENGTH_SHORT).show()
                reload()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #region ⋮ / a long press: every action on one toolchain

    // The dialog of the toolchain [id] (⋮, a long press), in the order of every panel: Edit
    // (packages.json in the editor, at it), Install (into X Editor), Update / Reinstall,
    // Uninstall (its files in X Editor), Remove (red, last: its files and its object in
    // packages.json; off while packages.json has changes not saved in the editor). What
    // cannot be done is dimmed, its line saying why; nothing may be done with one being
    // installed or updated.
    private fun showActions(id: String) {
        val pkg = catalog.firstOrNull { it.id == id }
        val have = installed.firstOrNull { it.pkg.id == id }
        if (pkg == null && have == null) return
        // the windows by the row closed: the dialog is over them
        if (rowPopupId != null) {
            rowPopup.dismiss()
            rowPopupId = null
        }
        val name = pkg?.name ?: have!!.pkg.name
        val (letter, color) = badge(pkg?.kind ?: have!!.pkg.kind)
        val installable = pkg?.isInstallable(abis) == true
        // something goes on on the sheet: nothing else can be begun, this one's or another's
        val busyNow = busyMessage()
        val busy = busyNow != null
        val busyText = busyNow?.let { activity.getString(R.string.config_busy_wait, it) }.orEmpty()
        val actions = EntryActionsDialog(activity, name)
            .tile(letter, color, ColorUtils.setAlphaComponent(color, TILE_ALPHA))
        // installed into X Editor, or not yet
        if (have != null) {
            actions.badge(activity.getString(R.string.toolchain_installed), installedColor, ColorUtils.setAlphaComponent(installedColor, BADGE_ALPHA))
        } else {
            actions.badge(activity.getString(R.string.toolchain_not_installed), muted, ColorUtils.setAlphaComponent(muted, BADGE_ALPHA))
        }

        // Edit: its object in packages.json
        actions.action(
            R.drawable.ic_edit, R.string.config_edit,
            activity.getString(if (pkg != null) R.string.toolchain_action_edit_summary else R.string.toolchain_action_edit_unlisted),
            enabled = pkg != null,
        ) { editPackage(id) }

        // Stop: it is being installed or updated
        if (isInstalling(id)) {
            actions.action(
                R.drawable.ic_stop, R.string.toolchain_stop,
                activity.getString(if (gate.isBusy) R.string.toolchain_stop_wait else R.string.toolchain_stop_summary),
                enabled = !gate.isBusy,
            ) { stop(id) }
        }

        // Install: into X Editor, from the list
        if (pkg != null && have == null && installable) {
            actions.action(
                R.drawable.ic_download, R.string.toolchain_install,
                if (busy) busyText else pkg.version?.let { activity.getString(R.string.toolchain_action_install_summary, it) }
                    ?: activity.getString(R.string.toolchain_action_install_summary_no_version),
                enabled = !busy,
            ) { requestInstall(pkg) }
        }

        // update: into X Editor (what is installed there, from the list)
        var canUpdate = pkg != null && have == null && installable
        if (pkg != null && have != null && installable) {
            val newVersion = pkg.version
            val newer = newVersion != null && newVersion != have.pkg.version
            actions.action(
                if (newer) R.drawable.ic_update else R.drawable.ic_restart,
                if (newer) R.string.toolchain_update else R.string.toolchain_reinstall,
                when {
                    busy -> busyText
                    newer -> activity.getString(R.string.toolchain_action_update_local, have.pkg.version ?: "?", newVersion)
                    newVersion.isNullOrEmpty() -> activity.getString(R.string.toolchain_action_reinstall_detail_no_version)
                    else -> activity.getString(R.string.toolchain_action_reinstall_detail, newVersion)
                },
                enabled = !busy,
            ) { requestInstall(pkg) }
            canUpdate = true
        }
        if (!canUpdate) {
            // why there is nothing to update it with
            val why = when {
                pkg == null -> R.string.toolchain_action_update_unlisted
                else -> R.string.toolchain_action_update_no_source
            }
            actions.action(R.drawable.ic_update, R.string.toolchain_update, activity.getString(why), enabled = false) {}
        }

        // Uninstall: its files in X Editor only (it stays in the list)
        if (have != null && pkg != null) {
            actions.action(
                R.drawable.ic_delete, R.string.toolchain_uninstall,
                if (busy) busyText else activity.getString(R.string.toolchain_action_uninstall_detail, have.files.size),
                enabled = !busy,
            ) { confirmUninstall(have) }
        }

        // Remove: what it does depends on where it is; not over changes not saved in packages.json
        val unsaved = pkg != null && fileEditor.hasUnsavedChanges()
        val removeText = when {
            busy -> busyText
            unsaved -> activity.getString(R.string.config_action_unsaved)
            have != null && pkg != null -> activity.getString(R.string.toolchain_remove_summary_local, have.files.size, id)
            have != null -> activity.getString(R.string.toolchain_remove_summary_unlisted, have.files.size)
            else -> activity.getString(R.string.toolchain_remove_summary_listed, id)
        }
        actions.action(R.drawable.ic_delete, R.string.toolchain_remove, removeText, enabled = !busy && !unsaved, danger = true) {
            confirmRemove(id)
        }
        actions.show()
    }

    // Remove, once asked: what will go
    private fun confirmRemove(id: String) {
        val pkg = catalog.firstOrNull { it.id == id }
        val have = installed.firstOrNull { it.pkg.id == id }
        if (pkg == null && have == null) return
        val name = pkg?.name ?: have!!.pkg.name
        val lines = ArrayList<String>()
        lines += when {
            have != null && pkg != null -> activity.getString(R.string.toolchain_remove_message_local, have.files.size, id)
            have != null -> activity.getString(R.string.toolchain_remove_message_unlisted, have.files.size)
            else -> activity.getString(R.string.toolchain_remove_message_listed, id)
        }
        ConfigDialogs.confirmDelete(
            activity,
            activity.getString(R.string.toolchain_remove_title, name),
            lines.joinToString(" "),
            activity.getString(R.string.toolchain_remove),
        ) { removeOne(id, name, listed = pkg != null, local = have != null) }
    }

    // the list, or what is installed of it, changed: what goes by it told
    private fun toolchainChanged() {
        ToolchainInstaller.noteChanged()
        onToolchainChanged()
    }

    // Removes the toolchain [id]: in packages.json ([listed]), its object there, the rest of the
    // file as it is; installed into X Editor ([local]), its files and links (as its .list says),
    // after that: a list that cannot be written (another of its toolchains not right) leaves
    // it all as it was, rather than a toolchain listed without its files. Then the list read
    // again, and a word of what was done (or what failed).
    private fun removeOne(id: String, name: String, listed: Boolean, local: Boolean) {
        val ticket = beginOrRefuse(PanelOp.REMOVE, name) ?: return
        scope.launch {
            try {
                var uninstalled = false
                var unlisted = false
                var failure: Throwable? = null
                withContext(Dispatchers.IO) {
                    try {
                        if (listed) unlisted = ToolchainInstaller.removeFromCatalog(activity, id)
                        if (local) uninstalled = ToolchainInstaller.uninstall(activity, id)
                    } catch (e: IOException) {
                        failure = e
                    } catch (e: RuntimeException) {
                        failure = e
                    }
                }
                // what failed of it before (its row, the window) goes with it
                installing[id]?.takeIf { it.error != null }?.let { installing.remove(id) }
                tasks.removeAll { it.id == id && !isBusy(it) }
                if (unlisted) catalogChangedOnDisk()
                if (unlisted || uninstalled) toolchainChanged()
                val error = failure?.let { it.message ?: it.toString() }
                val word = when {
                    error != null && uninstalled -> activity.getString(R.string.toolchain_remove_catalog_failed, name, ToolchainInstaller.CATALOG, error)
                    error != null -> activity.getString(R.string.toolchain_remove_failed, name, error)
                    unlisted && local -> activity.getString(R.string.toolchain_removed_local, name, ToolchainInstaller.CATALOG, id)
                    unlisted -> activity.getString(R.string.toolchain_removed_listed, name, ToolchainInstaller.CATALOG, id)
                    else -> activity.getString(R.string.toolchain_removed_unlisted, name, ToolchainInstaller.CATALOG)
                }
                Toast.makeText(activity, word, Toast.LENGTH_LONG).show()
                if (!dialog.isShowing) return@launch
                refreshTaskPopup()
                reload()
            } finally {
                gate.end(ticket)
            }
        }
    }

    // #endregion

    // An install or update of one toolchain asked for (⋮, its info): not beside what goes on
    private fun requestInstall(pkg: ToolPackage) {
        if (!refusedWhileBusy()) install(pkg)
    }

    // Installs [pkg] into X Editor, its progress in its row; the download goes once it is in.
    // The install runs in the process (ToolchainJobs): this sheet only waits for it (trackLocal).
    private fun install(pkg: ToolPackage) {
        val going = installing[pkg.id]
        if (going != null && going.error == null) return
        val hadBefore = installed.any { it.pkg.id == pkg.id }
        val progress = Progress(pkg, hadBefore, activity.getString(R.string.toolchain_installing))
        progress.run = ToolchainJobs.startLocal(activity, pkg, hadBefore)
        installing[pkg.id] = progress
        onInstallStarted()
        updateRow(pkg.id)
        // what failed of it before is gone from the window
        taskChanged()
        trackLocal(progress)
    }

    // what an install into X Editor is doing, in a few words: [step], and how far
    private fun stepText(step: String, percent: Int?): String = activity.getString(when (step) {
        "download" -> R.string.toolchain_step_download
        "verify" -> R.string.toolchain_step_verify
        "unpack" -> R.string.toolchain_step_unpack
        else -> R.string.toolchain_step_copy
    }) + (percent?.let { "  $it%" } ?: "")

    // [progress], its install going on in the process, waited for: how far, in its row; its end
    // told (a word, or in the window of what goes on; closed, in the editor's window)
    private fun trackLocal(progress: Progress) {
        val pkg = progress.pkg
        val run = progress.run ?: return
        progress.job = scope.launch {
            val result = try {
                Result.success(ToolchainJobs.await(run) { step, percent ->
                    // stopped by a save meanwhile (stopStale): nothing of it shown any more
                    if (installing[pkg.id] !== progress) return@await
                    progress.text = stepText(step, percent)
                    progress.percent = percent
                    updateRow(pkg.id)
                })
            } catch (e: CancellationException) {
                // Stopped (stop, stopStale), or the screen is gone (the install goes on and a new
                // screen takes over): not a failure (must come before RuntimeException)
                throw e
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }
            // replaced meanwhile (stopped by a save, started again): not this one's to tell
            if (installing[pkg.id] !== progress) return@launch
            ToolchainJobs.forget(run)
            result.onSuccess {
                installing.remove(pkg.id)
                toolchainChanged()
                when {
                    // closed: the editor's window says so
                    !dialog.isShowing -> doneWhileClosed += doneLine(pkg.name)
                    // one alone says so; one of a save or of Update, the word at the end does
                    tasks.none { it.id == pkg.id } ->
                        Toast.makeText(activity, activity.getString(R.string.toolchain_install_done, pkg.name), Toast.LENGTH_SHORT).show()
                }
                taskChanged()
                reload()
            }.onFailure { e ->
                progress.error = activity.getString(R.string.toolchain_install_failed, e.message ?: e.toString())
                updateRow(pkg.id)
                taskChanged()
            }
        }
    }

    // the row of [pkg], bound again
    private fun updateRow(id: String) {
        val index = entries.indexOfFirst { idOf(it) == id }
        if (index >= 0) adapter.notifyItemChanged(index)
        if (rowPopupId == id) refreshRowPopup()
        if (taskPopup.isShowing) refreshTaskPopup()
        refreshEditorPopup()
    }

    // #region packages.json

    // packages.json in the editor (read the first time; as it was left after)
    private fun showEditor() {
        rowPopup.dismiss()
        rowPopupId = null
        editorShown = true
        editorCollapse.setVisible(true)
        if (fileEditor.key == null || fileEditor.loadFailed) openCatalogInEditor()
    }

    // Edit of a toolchain: packages.json in the editor, at its object (selected, its line lit up)
    private fun editPackage(id: String) {
        showEditor()
        // (locked, it is shown all the same: to be read)
        fileEditor.whenOpened {
            val span = CatalogText.spanOf(fileEditor.text(), id)
            if (span == null) Toast.makeText(activity, R.string.toolchain_edit_not_found, Toast.LENGTH_SHORT).show()
            else fileEditor.select(span.start, span.end)
        }
    }

    // New: an id, then a toolchain of it put at the end of "packages" in the editor (from
    // the template), the cursor in its description; written (and installed into X Editor)
    // when packages.json is saved
    private fun askNew() {
        if (refusedWhileBusy()) return
        askNewName(
            activity,
            activity.getString(R.string.toolchain_new_title),
            activity.getString(R.string.toolchain_new_hint),
            problemOf = { name ->
                when {
                    name.isEmpty() -> activity.getString(R.string.config_new_empty)
                    !NEW_ID.matches(name) -> activity.getString(R.string.config_new_unsafe)
                    // of the list, those switched off too, and of what is typed and not saved yet
                    name in idsInEditorOrList() || installed.any { it.pkg.id == name } ->
                        activity.getString(R.string.config_new_exists, name)
                    else -> null
                }
            },
            noteOf = { name -> if (name.isEmpty()) null else activity.getString(R.string.toolchain_new_note) },
        ) { name ->
            if (refusedWhileBusy()) return@askNewName
            showEditor()
            fileEditor.whenReady {
                val made = runCatching {
                    CatalogText.withPackage(fileEditor.text(), ToolchainInstaller.newEntry(activity, name))
                }
                made.onSuccess { appended ->
                    val description = appended.text.indexOf(DESCRIPTION_VALUE, appended.start)
                    val caret = if (description < 0) appended.start else description + DESCRIPTION_VALUE.length
                    fileEditor.replaceText(appended.text, caret)
                }.onFailure {
                    Toast.makeText(activity, R.string.toolchain_new_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // the ids of the list as it will be saved: of the editor if it has it, else of the file
    private fun idsInEditorOrList(): Set<String> {
        if (!fileEditor.isReady()) return listedIds
        val typed = ToolPackage.parseCatalog(fileEditor.text()).map { it.id }.toSet()
        // (what is typed is not right just now: what the file says)
        return if (typed.isEmpty()) listedIds else typed
    }

    // What saving [text] will do, for the check line: install, update (into X Editor), or
    // only read the list again
    private fun describeSave(text: String): CharSequence {
        val packages = ToolPackage.parseCatalog(text, ArrayList()).filter { it.enabled }
        val changed = packages.filter { pkg -> catalog.firstOrNull { it.id == pkg.id }?.sameInstall(pkg) != true }
        val local = changed.filter { needsInstall(it) }
        val (updates, installs) = local.partition { pkg -> installed.any { it.pkg.id == pkg.id } }
        val parts = ArrayList<String>()
        if (installs.isNotEmpty()) parts += activity.getString(R.string.toolchain_after_save_install, installs.joinToString("、") { it.name })
        if (updates.isNotEmpty()) parts += activity.getString(R.string.toolchain_after_save_update, updates.joinToString("、") { it.name })
        return if (parts.isEmpty()) activity.getString(R.string.toolchain_after_save_nothing) else parts.joinToString("；")
    }

    // packages.json read into the editor, as it is on disk
    private fun openCatalogInEditor() {
        fileEditor.open(KEY, ToolchainInstaller.CATALOG, activity.getString(R.string.toolchain_editor_title, ToolchainInstaller.CATALOG)) {
            ToolchainInstaller.catalogFile(activity).readText()
        }
    }

    // packages.json written by the sheet itself (a toolchain removed): the editor, if it has
    // read it, reads it again: now if it is shown and nothing is typed in it, else when it is
    // shown next (what was typed and not saved is lost, as the removal said)
    private fun catalogChangedOnDisk() {
        if (fileEditor.key == null) return
        if (editorShown) {
            if (!fileEditor.hasUnsavedChanges()) openCatalogInEditor()
        } else {
            fileEditor.showNone(activity.getString(R.string.toolchain_editor_title, ToolchainInstaller.CATALOG), "")
        }
    }

    // the editor hidden (what is in it stays), and the keyboard with it; it closes upwards,
    // fading, the sheet shrinking with it (as the language servers' does). What failed, if
    // it is shown by Save, moves by the list, Save going with the editor.
    private fun hideEditor() {
        editorShown = false
        fileEditor.hideKeyboard()
        editorCollapse.setVisible(false, animate = true)
        if (taskPopup.isShowing) refreshTaskPopup(force = true)
    }

    // packages.json saved: the list read again, then what is new or changed in it installed
    // into X Editor (what can be, and is not there, or of another version). How it goes is in
    // the rows; a word that it was saved (nothing to install), or that all was installed;
    // what fails, in the window. A save that only
    // renames a toolchain ([ToolPackage.sameInstall]) installs nothing; one that changes the id
    // of a toolchain installed takes what is installed along (ToolchainInstaller.followRenames),
    // or its old name would stay in the list, as installed and not listed. Something to install:
    // how far it is in the window by Save.
    private suspend fun onCatalogSaved(text: String) {
        // a new save: the popup of the last one is closed first
        taskPopup.dismiss()
        val before = catalog
        val after = ToolPackage.parseCatalog(text, ArrayList()).filter { it.enabled }
        // removed or changed entries: their installs under way are stopped first (and waited
        // for until they really stopped)
        stopStale(after)
        // the download cache: the unfinished downloads of entries whose id, version, URL or
        // sha256 changed (and of removed ones) are of no use any more and are deleted; those of
        // unchanged entries are kept, to be resumed next time (DownloadCache)
        sweepCache(text)
        // the language servers look for their programs again (what is installed may have changed)
        toolchainChanged()
        if (!dialog.isShowing) {
            Toast.makeText(activity, activity.getString(R.string.toolchain_saved, ToolchainInstaller.CATALOG), Toast.LENGTH_SHORT).show()
            return
        }
        val settled = before.filterNot { isInstalling(it.id) }
        val failure = withContext(Dispatchers.IO) {
            try {
                ToolchainInstaller.followRenames(activity, settled, after)
                null
            } catch (e: IOException) {
                e
            }
        }
        // (the old id then stays, as not listed, and the new one is to install)
        failure?.let {
            Toast.makeText(activity, activity.getString(R.string.toolchain_rename_failed, it.message ?: it.toString()), Toast.LENGTH_LONG).show()
        }
        if (dialog.isShowing) installWhatChanged(before)
    }

    // A save of packages.json: what is being installed and is no longer in [after], or is
    // there changed (ToolPackage.sameInstall), is stopped: cancelled, and waited for (its
    // download and its staging directory are named by its id: two installs of one id must
    // not go at once). Unpacking is not cut short (ToolchainInstaller.install): one taken out
    // of the list that was not installed before, and got installed as it was stopped, is taken
    // away again. What it downloaded is cleared by the sweep after (onCatalogSaved) only if
    // its id, version, source or SHA-256 changed: otherwise it is kept, to go on with.
    private suspend fun stopStale(after: List<ToolPackage>) {
        val byId = after.associateBy { it.id }
        val stopped = HashSet<String>()

        val local = installing.filter { (id, p) -> byId[id]?.let { p.pkg.sameInstall(it) } != true }
        val cancelled = ArrayList<Job>()
        for ((id, p) in local) {
            installing.remove(id)
            p.run?.let { ToolchainJobs.cancel(it) }?.let { cancelled += it }
            p.job?.cancel()
            stopped += id
        }
        for (p in local.values) p.job?.join()
        for (job in cancelled) job.join()
        if (local.isNotEmpty()) withContext(Dispatchers.IO) {
            for ((id, p) in local) {
                // (what cannot be taken away stays, as installed and not listed: its row can)
                if (id !in byId && !p.hadBefore) runCatching { ToolchainInstaller.uninstall(activity, id) }
            }
        }
        if (stopped.isEmpty()) return
        tasks.removeAll { it.id in stopped && !isBusy(it) }
        for (id in stopped) updateRow(id)
        syncUpdateButton()
    }

    // The list read again, then what differs from [before] installed (onCatalogSaved)
    private fun installWhatChanged(before: List<ToolPackage>) {
        // (from here: the editor stays locked from the save on, till all of it is done)
        val serial = beginTasks(updates = false)
        reload {
            // (closed meanwhile, or all stopped: the set goes no further)
            if (!dialog.isShowing || serial != batchSerial) return@reload
            val changed = catalog.filter { pkg -> before.firstOrNull { it.id == pkg.id }?.sameInstall(pkg) != true }
            val local = changed.filter { needsInstall(it) && !isInstalling(it.id) }
            addTasks(local)
            batchPreparing = false
            if (local.isEmpty()) {
                if (tasks.any { isBusy(it) }) {
                    // nothing new to install, but what an earlier save started still goes
                    // on: its end is told when it is done
                    taskChanged()
                } else {
                    // nothing to install: saved, and that is all
                    batchOpen = false
                    Toast.makeText(activity, activity.getString(R.string.toolchain_saved, ToolchainInstaller.CATALOG), Toast.LENGTH_SHORT).show()
                }
            } else {
                showTaskPopup(byList = false)
                taskChanged()
            }
        }
    }

    // Update: the list read again, then what is installed into X Editor updated (to a new
    // version in the list). How it goes is in the rows and Update's dots; at the end a word
    // (all updated, up to date, nothing to update), or the window with what failed. (Not
    // enabled while the list shows nothing.)
    private fun refreshAndUpdate() {
        val serial = beginTasks(updates = true)
        syncUpdateButton()
        if (taskPopup.isShowing) refreshTaskPopup()
        reload {
            // (closed meanwhile, or all stopped: the set goes no further)
            if (!dialog.isShowing || serial != batchSerial) return@reload
            val listedInstalled = catalog.filter { pkg -> installed.any { it.pkg.id == pkg.id } }
            val local = listedInstalled.filter { needsInstall(it) && !isInstalling(it.id) }
            upToDateCount = listedInstalled.count { !needsInstall(it) }
            addTasks(local)
            batchPreparing = false
            if (entries.isEmpty() && local.isEmpty()) {
                // the list shows nothing now: nothing to update; its middle says why (its
                // icon, all of it)
                batchOpen = false
                syncUpdateButton()
                refreshTaskPopup()
            } else {
                taskChanged()
            }
        }
    }

    // whether Update is going on: the list being read again, or what it started still going on
    private fun isUpdating(): Boolean = tasksAreUpdates && (batchPreparing || tasks.any { isBusy(it) })

    // whether anything is going on: a set being made, or an install or update of any kind (a
    // save's, Update's, one of a toolchain alone)
    fun isWorking(): Boolean =
        batchPreparing || installing.values.any { it.error == null }

    /**
     * Whether this sheet is to be shown again rather than a new one (EditorActivity.showToolchain):
     * something goes on, or what failed is still to be seen (a new sheet would know of neither).
     */
    fun wantsReopen(): Boolean = !released && !detached && (isWorking() || failures().isNotEmpty())

    // Update's icon: the dots while it goes on, its own icon again after; on while the list
    // shows something, or something goes on (it shows how far that is)
    private fun syncUpdateButton() {
        syncLock()
        updateButton.isEnabled = entries.isNotEmpty() || isWorking()
        updateButton.setBusyDots(isUpdating(), updateDots, R.drawable.ic_update)
    }

    // A new set of installs (a save) or updates (Update), being made; what an earlier one
    // started and is still going on stays in it
    private fun beginTasks(updates: Boolean): Int {
        tasks.retainAll { isBusy(it) }
        tasksAreUpdates = updates
        upToDateCount = 0
        batchPreparing = true
        batchOpen = true
        return ++batchSerial
    }

    // [packages] added to the set and started, into X Editor
    private fun addTasks(packages: List<ToolPackage>) {
        if (packages.isEmpty()) return
        for (pkg in packages) {
            if (tasks.none { it.id == pkg.id }) tasks += Task(pkg.id, pkg.name)
        }
        packages.forEach { install(it) }
    }

    // An install or update started, ended or failed: Update's dots, the window of what goes
    // on, and, the set all done, its word (in that window, while it is shown)
    private fun taskChanged() {
        syncUpdateButton()
        // closed, and nothing goes on any more: this sheet is not shown again (a new one is)
        if (!dialog.isShowing && !wantsReopen()) releaseEditor()
        refreshTaskPopup()
        refreshEditorPopup()
        if (!batchOpen || batchPreparing || tasks.any { isBusy(it) }) return
        batchOpen = false
        if (!dialog.isShowing) return
        val failed = tasks.count { failureOf(it) != null }
        // what failed is in the window: no word over it
        if (failed > 0) return
        val done = tasks.size
        val word = when {
            !tasksAreUpdates -> activity.getString(R.string.toolchain_popup_title_done, ToolchainInstaller.CATALOG)
            done > 0 -> activity.getString(R.string.toolchain_updated_count, done)
            upToDateCount > 0 -> activity.getString(R.string.toolchain_all_up_to_date)
            else -> activity.getString(R.string.toolchain_popup_title_nothing)
        }
        if (taskPopup.isShowing && failures().isEmpty()) {
            taskPopup.show(
                taskAnchor(), activity.getString(R.string.toolchain_progress_done), doneTaskLines(word),
                installedColor, sticky = true,
            )
        } else {
            Toast.makeText(activity, word, Toast.LENGTH_SHORT).show()
        }
    }

    // whether [pkg] is to be installed into X Editor: it can be, and it is not there (or a
    // version of it other than the list's is)
    private fun needsInstall(pkg: ToolPackage): Boolean {
        if (!pkg.isInstallable(abis)) return false
        val have = installed.firstOrNull { it.pkg.id == pkg.id } ?: return true
        return pkg.version != null && pkg.version != have.pkg.version
    }

    private fun isInstalling(id: String): Boolean = installing[id]?.let { it.error == null } == true

    private fun isBusy(task: Task): Boolean = isInstalling(task.id)

    // why [task] failed, if it did
    private fun failureOf(task: Task): String? = installing[task.id]?.error

    // #endregion

    // #region what goes on, and what failed

    // the name of the toolchain [id], as the list (or what is installed) says
    private fun nameOf(id: String): String =
        catalog.firstOrNull { it.id == id }?.name ?: installed.firstOrNull { it.pkg.id == id }?.pkg?.name ?: id

    // Everything that failed, now: what failed (the Progress), the toolchain, where, and why
    private class Failure(val key: Any, val name: String, val where: String, val error: String)

    private fun failures(): List<Failure> {
        val list = ArrayList<Failure>()
        for ((id, progress) in installing) {
            val error = progress.error ?: continue
            list += Failure(progress, nameOf(id), activity.getString(R.string.toolchain_runner_local), error)
        }
        return list
    }

    // The window of what goes on, by Save ([byList] false) or by the list, opened by the user
    // (Update tapped again) or by a save that installs something
    private fun showTaskPopup(byList: Boolean) {
        taskPopupByList = byList
        // one window at a time over the list
        rowPopup.dismiss()
        rowPopupId = null
        refreshTaskPopup(force = true)
    }

    // The window of what goes on: how far all of it is (a bar), what each is doing, and what
    // failed. Shown when something fails that it was not shown for yet ([force]: shown anyway,
    // by where it goes now); changed in place while it is shown. Nothing going on nor failed
    // any more: closed, unless the end of a set is yet to be told in it (taskChanged).
    private fun refreshTaskPopup(force: Boolean = false) {
        if (!dialog.isShowing) return
        val failures = failures()
        val working = isWorking()
        if (!working && failures.isEmpty()) {
            if (!batchOpen) taskPopup.dismiss()
            return
        }
        val fresh = failures.any { it.key !in announced }
        if (!taskPopup.isShowing && !fresh && !force) return
        failures.forEach { announced += it.key }
        val blocks = ArrayList<String>()
        if (working) blocks += workLines()
        failures.mapTo(blocks) { "✗ ${it.name} · ${it.where}\n${it.error}" }
        if (working) {
            val (finished, parts) = workParts()
            val heading = activity.getString(if (tasksAreUpdates) R.string.toolchain_progress_update else R.string.toolchain_progress_install)
            taskPopup.show(
                taskAnchor(),
                if (parts.isEmpty()) heading else activity.getString(R.string.lsp_popup_title, heading, "$finished/${parts.size}"),
                blocks.joinToString("\n\n").ifEmpty { activity.getString(R.string.toolchain_progress_preparing) },
                primary, ToolchainRules.overallPercent(parts) ?: EntryProblemPopup.INDETERMINATE,
                sticky = true, bar = true,
                // stop every task at once (a slow network, hours of downloading, no wish to wait):
                // asked first, and the popup stays while asking
                action = PopupAction(activity.getString(R.string.toolchain_stop_all), danger = true, dismisses = false) { askStopAll() },
            )
        } else {
            val title = activity.getString(R.string.toolchain_failed_title, failures.size)
            taskPopup.show(taskAnchor(), title, blocks.joinToString("\n\n"), bad, sticky = true)
        }
    }

    // What is being done, a toolchain a block: its name, where, and how far
    private fun workLines(): List<String> {
        val lines = ArrayList<String>()
        for (progress in installing.values) {
            if (progress.error != null) continue
            lines += "▸ ${progress.pkg.name} · ${activity.getString(R.string.toolchain_runner_local)}\n${progress.text}"
        }
        return lines
    }

    // How many of what was started are done, and how far each part is (percent; null: not
    // known), for the bar: the set, and what was started alone
    private fun workParts(): Pair<Int, List<Int?>> {
        // (a set told done long ago is not what goes on now)
        val set = if (batchOpen) tasks else tasks.filter { isBusy(it) }
        val parts = ArrayList<Int?>()
        for (task in set) {
            parts += if (!isBusy(task)) 100 else installing[task.id]?.percent
        }
        for ((id, progress) in installing) {
            if (progress.error == null && set.none { it.id == id }) parts += progress.percent
        }
        return set.count { !isBusy(it) } to parts
    }

    // The window at the end of a set: its [word], and what was done
    private fun doneTaskLines(word: String): String =
        (listOf(word) + tasks.map { task -> doneLine(task.name) }).joinToString("\n")

    // by the list when the user asked there; else by Save, for a save with the editor shown
    private fun taskAnchor(): View = if (!taskPopupByList && !tasksAreUpdates && editorShown) {
        fileEditor.view.findViewById<View>(R.id.file_save).parent as View
    } else {
        listHeader
    }

    // #endregion

    // #region the small window in the editor, while the sheet is closed

    // What goes on, by the editor's toolbar (statusAnchor), while the sheet is closed: what is
    // being installed and how far (a bar), what failed, what got done; Open (the sheet) and
    // Close. Not closed by a tap outside (one types in the editor under it): Close, Back.
    // Closed by the user, it stays closed ([editorPopupMuted]) till something fails; the end
    // is then a word. All done with nothing failed: it says so, then goes by itself.
    private var editorPopup: EntryProblemPopup? = null
    // shown by this sheet, and not closed since (closed, not by it: by the user)
    private var editorPopupShown = false
    private var editorPopupMuted = false
    // the failures it was shown for (and those seen on the sheet before it was closed)
    private val editorAnnounced: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    // what got done while the sheet was closed, a line each (✓ name · where)
    private val doneWhileClosed = ArrayList<String>()
    private val hideWhenDone = Runnable {
        if (!isWorking() && failures().isEmpty()) {
            doneWhileClosed.clear()
            hideEditorPopup()
        }
    }
    // the screen this sheet was for is gone (detach): nothing of it is shown any more
    private var detached = false

    // The small window over the editor: compact (narrow, fewer lines of text, out of the way
    // of the code), and dragged elsewhere by holding its title bar. Where it was dragged to is
    // remembered in the process (when the screen is recreated, or it is closed and shown
    // again, it comes back there); a double tap on the title bar returns it to its default
    // place (below the toolbar, on the right)
    private fun editorPopup(): EntryProblemPopup = editorPopup ?: EntryProblemPopup(
        activity, (activity as? ComponentActivity)?.onBackPressedDispatcher, compact = true, draggable = true,
    ).also { popup ->
        popup.position = editorPopupPosition
        popup.onMoved = { editorPopupPosition = it }
        editorPopup = popup
    }

    // "✓ clang · X Editor"
    private fun doneLine(name: String): String =
        "✓ $name · " + activity.getString(R.string.toolchain_runner_local)

    // The sheet closed while something goes on: how it goes, in the editor (what failed before
    // it was closed was seen on the sheet: it does not open the window again by itself)
    private fun onClosedWhileWorking() {
        if (dialog.isShowing || detached) return
        editorPopupMuted = false
        editorAnnounced.clear()
        failures().forEach { editorAnnounced += it.key }
        refreshEditorPopup()
    }

    private fun hideEditorPopup() {
        editorPopupShown = false
        mainHandler.removeCallbacks(hideWhenDone)
        editorPopup?.dismiss()
    }

    // The editor's window as what goes on is now (changed in place while it is shown)
    private fun refreshEditorPopup() {
        if (dialog.isShowing || detached) return
        // closed meanwhile, not by this sheet: by the user
        if (editorPopupShown && editorPopup?.isShowing != true) {
            editorPopupShown = false
            editorPopupMuted = true
        }
        val failures = failures()
        val fresh = failures.filter { it.key !in editorAnnounced }
        if (fresh.isNotEmpty()) {
            // something failed since: shown again, whether closed by the user or not
            editorPopupMuted = false
            fresh.forEach { editorAnnounced += it.key }
        }
        val working = isWorking()
        if (!working && failures.isEmpty() && doneWhileClosed.isEmpty()) {
            hideEditorPopup()
            return
        }
        if (editorPopupMuted) {
            // closed by the user: all done, a word says so
            if (!working && failures.isEmpty()) {
                Toast.makeText(activity, activity.getString(R.string.toolchain_status_done_word, doneNames()), Toast.LENGTH_SHORT).show()
                doneWhileClosed.clear()
            }
            return
        }
        val anchor = statusAnchor()?.takeIf { it.isAttachedToWindow } ?: return
        val lines = ArrayList<String>()
        // while some are still under way, the finished ones come first, those under way after them
        if (working) lines += doneWhileClosed
        for (progress in installing.values) {
            if (progress.error != null) continue
            lines += "▸ ${progress.pkg.name} · ${activity.getString(R.string.toolchain_runner_local)}  ${progress.text}"
        }
        for (failure in failures) {
            // why, in its first lines: all of it on the sheet
            val why = failure.error.lines().filter { it.isNotBlank() }.take(FAILURE_LINES).joinToString("\n")
            lines += "✗ ${failure.name} · ${failure.where}\n$why"
        }
        if (!working) lines += doneWhileClosed
        val open = PopupAction(activity.getString(R.string.toolchain_status_open)) { openSheet() }
        val popup = editorPopup()
        mainHandler.removeCallbacks(hideWhenDone)
        when {
            working -> {
                val (finished, parts) = workParts()
                val updating = tasksAreUpdates || installing.values.any { it.error == null && it.hadBefore }
                val heading = activity.getString(if (updating) R.string.toolchain_progress_update else R.string.toolchain_progress_install)
                val title = if (parts.size > 1) activity.getString(R.string.lsp_popup_title, heading, "$finished/${parts.size}") else heading
                popup.show(
                    anchor, activity.getString(R.string.toolchain_status_title, title), lines.joinToString("\n"), primary,
                    ToolchainRules.overallPercent(parts) ?: EntryProblemPopup.INDETERMINATE,
                    sticky = true, action = open, bar = true,
                )
            }
            failures.isNotEmpty() -> popup.show(
                anchor, activity.getString(R.string.toolchain_status_title, activity.getString(R.string.toolchain_failed_title, failures.size)),
                lines.joinToString("\n"), bad, sticky = true, action = open,
            )
            else -> {
                popup.show(
                    anchor, activity.getString(R.string.toolchain_status_title, activity.getString(R.string.toolchain_progress_done)),
                    lines.joinToString("\n"), installedColor, sticky = true,
                )
                mainHandler.postDelayed(hideWhenDone, DONE_SHOWN_MILLIS)
            }
        }
        editorPopupShown = popup.isShowing
    }

    // the names of what got done while the sheet was closed, for the word at the end
    private fun doneNames(): String =
        doneWhileClosed.joinToString("、") { it.removePrefix("✓ ").substringBefore(" · ") }

    /**
     * What goes on in the process with no sheet to show it (ToolchainJobs: the screen was
     * recreated, by another theme or the night mode, or closed and opened again): this sheet,
     * not shown, takes it over, and shows how it goes in the small window in the editor; what
     * ended meanwhile is told there too (and the language servers hear of it).
     */
    fun adopt() {
        if (detached) return
        editorPopupMuted = false
        for (run in ToolchainJobs.localRuns()) {
            val id = run.pkg.id
            if (installing.containsKey(id)) continue
            val text = run.step?.let { stepText(it, run.percent) } ?: activity.getString(R.string.toolchain_installing)
            val progress = Progress(run.pkg, run.hadBefore, text, run.percent)
            progress.run = run
            installing[id] = progress
            trackLocal(progress)
        }
        syncUpdateButton()
        refreshEditorPopup()
    }

    /**
     * The screen this sheet is for goes (recreated, or closed): nothing of it is shown any
     * more. What it waits for goes on in the process (ToolchainJobs), for the next screen's
     * sheet to take over ([adopt]); the waiting goes with [scope].
     */
    fun detach() {
        detached = true
        mainHandler.removeCallbacksAndMessages(null)
        editorPopup?.release()
        editorPopup = null
        rowPopup.dismiss()
        taskPopup.dismiss()
        footerPopup.dismiss()
        emptyPopup.dismiss()
        if (dialog.isShowing) dialog.dismiss()
    }

    // #endregion

    // #region the footer

    // All of the footer in a window by it (its points one under another, each aligned after
    // its bullet); ⋯ again (or the line) closes it
    private fun toggleFooter() {
        if (footerPopup.isShowing) {
            footerPopup.dismiss()
            return
        }
        footerPopup.show(footerRow, activity.getString(R.string.toolchain_footer_title), bulletText(activity.getString(R.string.toolchain_footer)), primary)
    }

    // [raw], a point a line (the footer, why the list is empty): each with a bullet of
    // [color], its lines after the first aligned with it
    private fun bulletText(raw: String, color: Int = primary): CharSequence {
        val density = activity.resources.displayMetrics.density
        val text = SpannableStringBuilder()
        raw.split('\n').filter { it.isNotBlank() }.forEachIndexed { index, line ->
            if (index > 0) text.append('\n')
            val start = text.length
            text.append(line.trim())
            val gap = (FOOTER_BULLET_GAP_DP * density).toInt()
            val bullet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                BulletSpan(gap, color, (FOOTER_BULLET_RADIUS_DP * density).toInt().coerceAtLeast(1))
            } else {
                BulletSpan(gap, color)
            }
            text.setSpan(bullet, start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    // #endregion

    // #region a row tapped

    private fun idOf(entry: Pair<ToolPackage?, InstalledPackage?>): String = entry.first?.id ?: entry.second!!.pkg.id

    // all of the toolchain [id], by its row [anchor]
    private fun showRow(anchor: View, id: String) {
        val entry = entries.firstOrNull { idOf(it) == id } ?: return
        rowPopupId = id
        showRowPopup(anchor, entry.first, entry.second)
    }

    // Being installed or updated: how far (a bar; turning while it is not known), what is
    // being done, the version, where from and where to, in place of its version. Whether it
    // is. Done, the window shown goes on to its version (refreshRowPopup).
    private fun showRowProgress(anchor: View, pkg: ToolPackage?, have: InstalledPackage?): Boolean {
        val id = pkg?.id ?: have!!.pkg.id
        val name = pkg?.name ?: have!!.pkg.name
        val progress = installing[id]?.takeIf { it.error == null } ?: return false
        val lines = ArrayList<String>()
        val updating = have != null
        lines += progress.text
        val newVersion = pkg?.version
        val oldVersion = have?.pkg?.version
        when {
            newVersion != null && oldVersion != null && newVersion != oldVersion ->
                lines += activity.getString(R.string.toolchain_progress_version_update, oldVersion, newVersion)
            newVersion != null -> lines += activity.getString(R.string.toolchain_progress_version, newVersion)
        }
        pkg?.sourceFor(abis)?.let { lines += activity.getString(R.string.toolchain_progress_source, it.source) }
        lines += activity.getString(R.string.toolchain_progress_where, activity.getString(R.string.toolchain_runner_local))
        rowPopup.show(
            anchor,
            activity.getString(if (updating) R.string.toolchain_progress_row_update else R.string.toolchain_progress_row_install, name),
            lines.joinToString("\n"), primary, progress.percent ?: EntryProblemPopup.INDETERMINATE, bar = true,
        )
        return true
    }

    // The toolchain tapped shown again (what it says changed: its version found, an install
    // going on, the list read again), by its row as it is now; gone from the list, the popup
    // goes too. The list told of a change (render, updateRow): once it is laid out again, so
    // that its row is where it is now; nothing changed in it (the version found): at once,
    // as no layout would come to show it.
    private fun refreshRowPopup() {
        val id = rowPopupId ?: return
        if (!rowPopup.isShowing) {
            rowPopupId = null
            return
        }
        if (entries.none { idOf(it) == id }) {
            rowPopup.dismiss()
            rowPopupId = null
            return
        }
        if (!list.isLayoutRequested && !list.hasPendingAdapterUpdates()) {
            showRowPopupNow()
            return
        }
        if (rowPopupLayout != null) return
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                list.removeOnLayoutChangeListener(this)
                rowPopupLayout = null
                showRowPopupNow()
            }
        }
        rowPopupLayout = listener
        list.addOnLayoutChangeListener(listener)
        // (a layout asked for anyway: the listener is never left waiting)
        list.requestLayout()
    }

    // waiting for the list to be laid out, to show the toolchain tapped again (one at a time)
    private var rowPopupLayout: View.OnLayoutChangeListener? = null

    // the toolchain tapped, shown again by its row as it is now (not shown: the popup goes)
    private fun showRowPopupNow() {
        val id = rowPopupId ?: return
        if (!rowPopup.isShowing) {
            rowPopupId = null
            return
        }
        val index = entries.indexOfFirst { idOf(it) == id }
        val row = if (index >= 0) list.findViewHolderForAdapterPosition(index)?.itemView else null
        if (row == null || !row.isAttachedToWindow) {
            rowPopup.dismiss()
            rowPopupId = null
            return
        }
        val (pkg, have) = entries[index]
        showRowPopup(row, pkg, have)
    }

    // What [pkg] says of its version, where it is installed: in X Editor (its programs, run
    // in usr); asked the first time it is shown (a ring before "Asking its version…" while it
    // is asked), kept till the list is read again. Installed
    // nowhere: a word saying so, with the version the list has. Why an install or update
    // failed is in the window of what failed, not here; while one goes on, how far it is
    // (showRowProgress), its version only once it is done.
    private fun showRowPopup(anchor: View, pkg: ToolPackage?, have: InstalledPackage?) {
        if (showRowProgress(anchor, pkg, have)) return
        val id = pkg?.id ?: have!!.pkg.id
        val name = pkg?.name ?: have!!.pkg.name
        val version = have?.pkg?.version ?: pkg?.version
        val title = if (version.isNullOrEmpty()) name else "$name  $version"
        val localCommands = localVersionCommands(pkg, have)
        if (localCommands.isEmpty()) {
            // nothing to ask: not installed, or with nothing to run (where it is, then)
            val lines = installLocations(have)
            if (lines.isEmpty()) {
                lines += activity.getString(R.string.toolchain_not_installed)
                // what the list has of it for this device
                if (pkg != null && !pkg.isInstallable(abis)) lines += activity.getString(R.string.toolchain_no_source)
            }
            // installed nowhere: its dot red
            rowPopup.show(anchor, title, lines.joinToString("\n"), if (have != null) installedColor else muted, action = nextStep(pkg, have))
            return
        }
        if (!versions.containsKey(id)) queryVersions(id, localCommands)
        val known = versions[id]
        if (known == null) {
            rowPopup.show(anchor, title, activity.getString(R.string.toolchain_version_checking), primary, EntryProblemPopup.INDETERMINATE, action = nextStep(pkg, have))
            return
        }
        val blocks = ArrayList<String>()
        var anyOk = false
        for (section in known) {
            val body = when {
                section.error != null -> activity.getString(R.string.toolchain_version_failed, section.error) +
                    section.output.takeIf { it.isNotEmpty() }?.let { "\n" + it }.orEmpty()
                section.output.isEmpty() -> activity.getString(R.string.toolchain_version_no_output)
                else -> section.output.also { anyOk = true }
            }
            blocks += "$ ${section.command}\n$body"
        }
        rowPopup.show(anchor, title, blocks.joinToString("\n\n"), if (anyOk) installedColor else warn, action = nextStep(pkg, have))
    }

    // The button at the foot of a toolchain's info: what is most likely wanted next: update it,
    // install it, else edit it (none while it is being installed, or for one not listed)
    private fun nextStep(pkg: ToolPackage?, have: InstalledPackage?): PopupAction? {
        pkg ?: return null
        if (isInstalling(pkg.id)) return null
        val installable = pkg.isInstallable(abis)
        val newVersion = pkg.version
        return when {
            have != null && installable && newVersion != null && newVersion != have.pkg.version ->
                PopupAction(activity.getString(R.string.toolchain_update)) { requestInstall(pkg) }
            have == null && installable -> PopupAction(activity.getString(R.string.toolchain_install)) { requestInstall(pkg) }
            else -> PopupAction(activity.getString(R.string.config_edit)) { editPackage(pkg.id) }
        }
    }

    // What runs to say [pkg]'s version in X Editor: its "version_command", else its commands
    // that it put in usr/bin (all its programs there, if none of them is a command it gives)
    private fun localVersionCommands(pkg: ToolPackage?, have: InstalledPackage?): List<String> {
        if (have == null) return emptyList()
        val programs = have.programs()
        val commands = (pkg ?: have.pkg).commands().filter { it in programs }.ifEmpty { programs }
        if (commands.isEmpty() && (pkg ?: have.pkg).versionCommand.isEmpty()) return emptyList()
        return ToolVersions.commandsFor(pkg ?: have.pkg, commands)
    }

    // Asks [id] its version (in X Editor); the popup shown again with it, if it still shows
    // [id]. What was asked before the list was read again is not kept.
    private fun queryVersions(id: String, localCommands: List<String>) {
        versions[id] = null
        val generation = versionsGeneration
        scope.launch {
            val local = try {
                ToolVersions.local(activity, localCommands)
            } catch (e: IOException) {
                localCommands.map { ToolVersions.Section(it, "", e.message ?: e.toString()) }
            }
            if (generation != versionsGeneration) return@launch
            versions[id] = local
            if (rowPopupId == id) refreshRowPopup()
        }
    }

    // what was asked of versions forgotten: asked again when shown
    private fun forgetVersions() {
        versions.clear()
        versionsGeneration++
    }

    // where it is installed in X Editor (its directory, its programs, else where its files
    // are under usr), for one with nothing to run that says its version
    private fun installLocations(have: InstalledPackage?): ArrayList<String> {
        val lines = ArrayList<String>()
        if (have == null) return lines
        val usr = Usr.root(activity)
        val into = have.pkg.into?.trim('/').orEmpty()
        lines += activity.getString(R.string.toolchain_runner_local) + ":"
        if (into.isNotEmpty()) lines += "  " + java.io.File(usr, into).path
        val programs = have.programs()
        for (program in programs) lines += "  " + java.io.File(Usr.bin(activity), program).path
        if (into.isEmpty() && programs.isEmpty()) {
            have.files.map { it.substringBefore('/') }.distinct().take(MAX_PATHS).forEach {
                lines += "  " + java.io.File(usr, it).path
            }
        }
        return lines
    }

    // #endregion

    // #region packages.json, checked

    // what is wrong with a text of the list, in lines
    private fun describeCatalog(check: ToolchainInstaller.CatalogCheck): List<String> =
        if (check.syntax) {
            val message = check.problems.firstOrNull().orEmpty()
            listOf(if (check.line > 0) activity.getString(R.string.config_error_line, check.line, message) else message)
        } else {
            check.problems
        }

    // #endregion

    private fun badge(kind: ToolPackage.Kind): Pair<String, Int> = when (kind) {
        ToolPackage.Kind.SERVER -> "LS" to 0xFF61AFEF.toInt()
        ToolPackage.Kind.COMPILER -> "cc" to 0xFFE5C07B.toInt()
        ToolPackage.Kind.TOOL -> "⚙" to 0xFF98C379.toInt()
    }

    private companion object {
        // where the editor's window was dragged to (null: its own place, under the toolbar at
        // its end); kept while the process lives, over recreated screens
        var editorPopupPosition: android.graphics.Point? = null

        // all done with nothing failed, the editor's window says so this long, then goes
        const val DONE_SHOWN_MILLIS = 5_000L
        // of why something failed, the editor's window shows this many lines
        const val FAILURE_LINES = 2

        // the key of packages.json in the editor (the one file it edits)
        const val KEY = "packages"

        // what the id of a new toolchain may be
        val NEW_ID = Regex("[A-Za-z0-9_.+-]+")

        // where the value of a new toolchain's description starts, in its template
        const val DESCRIPTION_VALUE = "\"description\": \""

        // the list of toolchains no taller than this (about five rows), scrolling beyond
        const val LIST_MAX_HEIGHT_DP = 340

        // about the height of a row, for the list's height before it has been measured whole
        const val ROW_HEIGHT_DP = 72

        // the paths of a toolchain shown at most, when it has no directory nor program of its own
        const val MAX_PATHS = 6

        // in the dialog of a long press: the tile behind the kind, the badge of where it is
        const val TILE_ALPHA = 0x2E
        const val BADGE_ALPHA = 0x26

        // between a point's bullet and its text, in the window of the footer
        const val FOOTER_BULLET_GAP_DP = 8
        const val FOOTER_BULLET_RADIUS_DP = 2
    }
}
