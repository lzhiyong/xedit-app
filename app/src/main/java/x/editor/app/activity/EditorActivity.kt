/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app.activity

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import x.github.module.editor.view.FloatingBar
import x.editor.app.controller.GotoLineDialog
import x.editor.app.controller.LspController
import x.editor.app.editing.CommentStyles
import x.editor.app.toolchain.ToolchainSheet
import x.editor.app.lsp.FormattingRules
import x.editor.app.lsp.FriendlySnippets
import x.editor.app.lsp.LocalFiles
import x.editor.app.console.BuildRunner
import x.editor.app.console.ConsoleSheet
import x.editor.app.console.RunSettingsDialog
import x.editor.app.console.RunCommands
import x.editor.app.console.ConsoleLog
import x.editor.app.console.ProjectRoot
import x.editor.app.lsp.LanguageServers
import x.github.module.editor.view.EditorView
import androidx.appcompat.content.res.AppCompatResources
import android.graphics.drawable.Drawable
import android.content.pm.ResolveInfo
import android.content.ClipboardManager
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import androidx.annotation.WorkerThread
import android.provider.Settings
import android.util.TypedValue
import android.util.Log
import android.webkit.MimeTypeMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.MenuItemCompat
import androidx.core.graphics.ColorUtils
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.controller.DownloadPanelController
import x.editor.app.controller.EditorTabsController
import x.editor.app.controller.ExtraKeysController
import x.editor.app.controller.FileTreeController
import x.editor.app.controller.SearchPanelController
import x.editor.app.filetree.DocumentPaths
import x.editor.app.databinding.ActivityEditorBinding
import x.editor.app.download.FileDownloader
import x.editor.app.download.RemoteFile
import x.editor.app.highlight.EditorColorScheme
import x.editor.app.highlight.HighlightThemes
import x.editor.app.service.DiskChanges
import x.editor.app.service.DiskState
import x.editor.app.service.DocumentSaves
import x.editor.app.service.DocumentStamp
import x.editor.app.service.EditorService
import x.editor.app.settings.EditorFonts
import x.editor.app.settings.EditorSettings
import x.editor.app.shortcut.KeySequencer
import x.editor.app.shortcut.KeyStroke
import x.editor.app.shortcut.Keymap
import x.editor.app.shortcut.KeymapFile
import x.editor.app.shortcut.ShortcutAction
import x.editor.app.shortcut.ShortcutCommand
import x.editor.app.shortcut.ShortcutsSheet
import x.editor.app.treesitter.TreeSitterResources
import x.editor.app.treesitter.TreeSitterUpdateWorker
import x.editor.app.treesitter.TreeSitterUpdater
import x.editor.app.util.isPermissionDenied
import x.editor.app.view.CodeEditorView
import x.editor.app.view.ContentTranslatingDrawerLayout
import x.editor.app.view.ImeAnimationInsetsHandler

import x.github.module.editor.input.AutoPair
import x.github.module.editor.input.CommentToggle
import x.github.module.document.DocumentFile
import x.github.module.editor.model.EditSession

import com.google.android.material.R as MaterialR
import com.google.android.material.shape.MaterialShapeDrawable

/**
 * The editor screen: an editor for each open document, under their tabs
 * ([EditorTabsController]); the toolbar, the drawer with the file tree
 * ([FileTreeController]), the row of extra keys and the search panel
 * ([SearchPanelController]).
 *
 * What this class does itself is keeping the open documents: opening one in an
 * editor of its own, showing one or another, writing them back and closing them.
 * See the "documents" region for the rules.
 */
class EditorActivity : BaseActivity() {

    private lateinit var binding: ActivityEditorBinding
    
    private lateinit var editorService: EditorService

    // true from bindService() to unbindService()
    private var serviceBound = false

    // created when the service is connected, see serviceConnection
    private lateinit var fileTreeController: FileTreeController
    private lateinit var searchPanelController: SearchPanelController

    // the card that shows the downloads of the tree-sitter resources
    private lateinit var downloadPanelController: DownloadPanelController

    // the tabs of the open documents, see the "documents" region
    private lateinit var editorTabs: EditorTabsController

    // the downloads of the tree-sitter resources; started again by "retry"
    private var downloadJob: Job? = null

    // Complete once the updates staged in the background were put in place (or there
    // were none): no grammar library may be loaded before, see TreeSitterUpdater.
    private val resourcesReady = CompletableDeferred<Unit>()

    // the colors of the editor itself, from the highlight theme; null for the system's
    private var editorColors: EditorColorScheme? = null

    // #region the bars around the editors, in its colors (see applyChromeColors)

    private lateinit var drawerToggle: ActionBarDrawerToggle

    // what the app theme gives them, to go back to with the system's colors
    private var defaultWindowBackground: android.graphics.drawable.Drawable? = null
    private var defaultDrawerBackground: android.graphics.drawable.Drawable? = null
    private var defaultAppBarBackground: android.graphics.drawable.Drawable? = null
    private var defaultToolbarBackground: android.graphics.drawable.Drawable? = null
    // the fill of the drawer (a shape with a rounded corner, which a plain color would lose)
    private var defaultNavigationFill: ColorStateList? = null
    private var defaultArrowColor = Color.GRAY

    // the icons of the toolbar and its menu; null: the theme's
    private var toolbarIconColor: Int? = null

    // the colors of the row of extra keys, from the highlight theme; null: the app theme's
    private var extraKeyColors: ExtraKeysController.Colors? = null

    // #endregion
    
    private val persistableUriFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    
    // true once the roots of the file tree are built; see setUpFileTree
    private var fileTreeReady = false

    // picked before the file tree was ready; added to it when it is
    private val pendingFolderUris = mutableListOf<Uri>()
    private val pendingFileUris = mutableListOf<Uri>()

    // #region the open documents (main thread only)

    /**
     * An open document: it has a tab, and an editor of its own that keeps its text,
     * its unsaved changes, its undo history and its cursor while another one is
     * shown.
     */
    private inner class OpenDocument(var document: DocumentFile) {

        val uri: Uri
            get() = document.getUri()

        /** Its editor; created when it is shown the first time (see ensureEditor). */
        var editor: CodeEditorView? = null

        /**
         * The text was read completely. Only then may it be written back: otherwise
         * a file could be overwritten with a part of itself.
         */
        var isLoaded = false

        /** Reading the text into the editor, from the moment it is shown first. */
        var isLoading = false
        var loadJob: Job? = null

        /** Setting the highlighting up again (the text must not change meanwhile). */
        var isParsing = false
        var highlightJob: Job? = null

        /**
         * The save started last, with its result (false: failed). The saves of a
         * document run one after the other.
         */
        var saveJob: Deferred<Boolean>? = null

        /** Being written back while it is shown: not to be changed until it is. */
        var isSaving = false

        /** The hash of the file as it was read or written last: what its session belongs to. */
        var contentHash: String? = null

        /** The highlight resources are loaded into the engine of its editor. */
        var hasHighlightResources = false

        /** Made read-only by the user (the edit mode button). */
        var isReadOnly = false

        /**
         * Moved where it cannot be followed (see onDocumentRenamed): there is no file
         * to write it to any more. Its text stays, readable.
         */
        var isDetached = false

        /**
         * What the storage said about its file when it was read or written last, or when
         * a change of it by another app was let be: another answer means the file was
         * written since (see checkDisk). Null: not known.
         */
        var diskStamp: DocumentStamp? = null

        /**
         * The hash of the content of its file that the editor knows of: read, written,
         * or changed by another app and let be. Unlike [contentHash] it is not what the
         * session belongs to.
         */
        var diskHash: String? = null

        /** Its file was deleted by another app and the text kept: saving creates it again. */
        var isMissingOnDisk = false

        /** Finding out whether its file was changed (see checkDisk). */
        var diskCheckJob: Job? = null

        /** The question about a change of its file, while it is shown. */
        var diskDialog: AlertDialog? = null

        val isModified: Boolean
            get() = editor?.isModified() == true
    }

    // Renames and deletes in the file tree that are running: meanwhile the file of a
    // document may be gone under its old uri, which is not another app's doing.
    private var fileTreeChanges = 0

    // the open documents; their order is that of the tabs
    private val openDocuments = mutableListOf<OpenDocument>()

    // the document shown; null when none is open
    private var current: OpenDocument? = null

    // the editor shown
    private val activeEditor: CodeEditorView?
        get() = current?.editor

    // The shortcuts of keymap.json (KeymapFile), keys of every keyboard turned into what
    // they are bound to, sequences too (see #region keyboard shortcuts). The keymap is
    // read again in onResume and when the file is saved.
    private val keySequencer: KeySequencer by lazy {
        KeySequencer(
            run = ::runShortcut,
            onPendingChanged = ::showPendingKeys,
            onUnbound = { strokes ->
                Toast.makeText(this, getString(R.string.keymap_unbound, strokes.joinToString("")), Toast.LENGTH_SHORT).show()
            }
        )
    }
    private var pendingKeysToast: Toast? = null
    // what was left out of keymap.json when it was read last (shown in ShortcutsSheet)
    private var keymapProblems: List<String> = emptyList()

    // the height of the row of extra keys, which lies over the bottom of every editor
    private var extraKeysHeight = 0

    // the row of extra keys; set up in onCreate
    private var extraKeys: ExtraKeysController? = null

    // #endregion

    // The editor settings as they were applied to the editors last (each new editor
    // gets them at once); null until they were applied once.
    private var appliedEditorSettings: EditorSettings? = null
    
    // initial the vibrator
    private val vibrator: Vibrator by lazy {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(
                Context.VIBRATOR_MANAGER_SERVICE
            ) as VibratorManager
        
            vibratorManager.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
    }
    
    /**
     * This screen is light or dark as its highlight theme is, not as the app theme:
     * a light highlight theme in a dark app gives a light drawer, light menus and
     * dialogs, all of a piece with the editor (the bars take the exact colors of the
     * theme, see applyChromeColors). Set before the context is made, so that the
     * screen is created that way right away and not recreated.
     *
     * "Follow the app theme" picks a theme of the app's kind, so then nothing changes.
     */
    override fun attachBaseContext(newBase: Context) {
        val theme = AppSettings.resolveHighlightTheme(newBase, AppSettings.isAppNightMode(newBase))
        delegate.localNightMode = nightModeFor(AppSettings.isHighlightThemeDark(newBase, theme))
        super.attachBaseContext(newBase)
    }

    private fun nightModeFor(dark: Boolean): Int =
        if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    /**
     * The highlight theme [theme] was read and is [dark], or not: remembered for the
     * next start, and if this screen is shown otherwise, it is recreated the way the
     * theme is (AppCompat does that when the local night mode changes). That happens
     * when another theme was picked in the settings, or a theme that was never read
     * yet turned out to be other than its name says.
     */
    private fun followHighlightTheme(theme: String, dark: Boolean) {
        AppSettings.rememberHighlightThemeDark(this, theme, dark)
        val mode = nightModeFor(dark)
        if (delegate.localNightMode != mode && !isFinishing) {
            delegate.localNightMode = mode
        }
    }

    // Whether the app theme is dark: what "follow the app theme" picks a highlight
    // theme by. Not isNightMode, which is what this screen is shown with: the theme's.
    private val isAppNightMode: Boolean
        get() = AppSettings.isAppNightMode(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started from the launcher while the app is already running in another
        // task position: this instance is not needed. Nothing below is set up
        // then; onDestroy and the menu callbacks check for that.
        if (!isTaskRoot && intent != null) {
            if (
                intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                Intent.ACTION_MAIN == intent.action
            ) {
                finish()
                return
            }
        }
              
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
                
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, doubleBackCallback)

        editorTabs = EditorTabsController(
            activity = this,
            tabLayout = binding.editorTabs,
            preferences = activityPreferences,
            nameOf = { uri -> documentOf(uri).getName() },
            onSelect = { uri -> onTabSelected(uri) },
            onClose = { uris -> closeTabs(uris) },
            onShare = { uri -> shareDocument(uri) }
        ).also { it.attach() }

        collectMenuState()
        
        val toggle = ActionBarDrawerToggle(
            this@EditorActivity,
            binding.drawerLayout,
            binding.toolbar,
            R.string.app_name,
            R.string.app_name
        )
        drawerToggle = toggle

        // what the app theme gives the bars around the editors (see applyChromeColors)
        defaultWindowBackground = window.decorView.background
        defaultDrawerBackground = binding.drawerLayout.background
        defaultAppBarBackground = binding.appBar.background
        defaultToolbarBackground = binding.toolbar.background
        defaultNavigationFill = (binding.navViewStart.background as? MaterialShapeDrawable)?.fillColor
        defaultArrowColor = toggle.drawerArrowDrawable.color
        
        binding.drawerLayout.apply {
            addDrawerListener(toggle)
            toggle.syncState() 
            contentViewId = binding.contentMain.id
            translationBehaviorStart = ContentTranslatingDrawerLayout.TranslationBehavior.FULL
            translationBehaviorEnd = ContentTranslatingDrawerLayout.TranslationBehavior.FULL
            setScrimColor(Color.TRANSPARENT)                 
        }

        setUpExtraKeys()
        
        binding.extraKeysPanel.post {
            // the bottom margin of the editors: the row of extra keys lies over them
            extraKeysHeight = binding.extraKeysPanel.height
            openDocuments.forEach { it.editor?.setMargin(bottomMargin = extraKeysHeight) }
        }
        
        val imeInsetsHandler = ImeAnimationInsetsHandler(
            drawerLayout = binding.drawerLayout,
            extraKeysPanel = binding.extraKeysPanel,
            searchPanel = binding.searchPanel.root,
            searchPanelContent = binding.searchPanel.searchPanelContent
        )
        imeInsetsHandler.attach(binding.root)

        downloadPanelController = DownloadPanelController(
            views = binding.downloadPanel,
            onRetry = { downloadHighlightResources() }
        )
        
        bindService(
            Intent(this, EditorService::class.java), 
            serviceConnection, 
            Context.BIND_AUTO_CREATE
        )
        serviceBound = true

        // While the screen is in front (coming back from another app first of all): was
        // the file of the document shown changed or deleted meanwhile? See checkDisk.
        // The interval is a setting, read each time the screen comes to the front (back
        // from the settings, too); 0: checked only then and when a tab is selected.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val interval = EditorSettings.getDiskCheckIntervalMillis(this@EditorActivity)
                current?.let { checkDisk(it) }
                if (interval > 0) {
                    while (true) {
                        delay(interval)
                        current?.let { checkDisk(it) }
                    }
                }
            }
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {            
            editorService = (binder as EditorService.LocalBinder).getService()
            // connected again after the service was lost: everything is set up already
            if (::fileTreeController.isInitialized) return

            // initialize components
            prepareTreeSitterResources()
            searchPanelController = SearchPanelController(
                this@EditorActivity, binding, editorViewModel
            ) { activeEditor ?: detachedEditor }.also {
                it.setup()
            }
            // also opens the document of the intent, once the tree is built
            setUpFileTree()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The service runs in this process: this only happens when the process
            // dies. The old instance stays in `editorService` until a new one connects.
        }
    }

    // What the search panel works on when no editor is shown: nothing. It is closed
    // before the editor shown goes, so this is only there to be safe.
    private val detachedEditor: CodeEditorView by lazy { CodeEditorView(this) }

    // completion from the language servers the user set up (see LspController)
    private var lspControllerCreated = false
    private val lspController: LspController by lazy {
        lspControllerCreated = true
        LspController(this).also { it.navigator = { file, line, column -> openAt(file, line, column) } }
    }

    // where to go in a document once it is loaded (a definition in another file)
    private val pendingGoto = HashMap<Uri, Pair<Int, Int>>()

    // #region lifecycle and menu
    
    // Back, pressed once, says so and arms the second press: for two seconds this
    // callback is off, and back is handled by the system (the app goes to the background).
    private val doubleBackCallback = object: OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@EditorActivity, R.string.editor_exit_confirm, Toast.LENGTH_SHORT).show()
            isEnabled = false
            mainHandler.postDelayed({
                isEnabled = true
            }, BACK_PRESS_WINDOW_MS)
        }
    }
    
    override fun onRestart() {
        super.onRestart()
        mainHandler.removeCallbacksAndMessages(null)
        doubleBackCallback.isEnabled = true
    }
    
    override fun onResume() {
        super.onResume()
        // whatever was changed in the settings meanwhile goes to every editor
        applyEditorSettings()
        lifecycleScope.launch {
            when (val read = withContext(Dispatchers.IO) { KeymapFile.read(this@EditorActivity) }) {
                is KeymapFile.Read.Ok -> {
                    keySequencer.keymap = read.keymap
                    keymapProblems = read.problems
                }
                // not JSON: the defaults, and why
                is KeymapFile.Read.Failed -> {
                    keySequencer.keymap = Keymap.DEFAULT
                    keymapProblems = listOf(getString(R.string.keymap_read_error, read.line, read.message))
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The process may be killed in the background: keep the session of every
        // document that is loaded (cursor, folds, undo history; unsaved edits go on
        // its redo stack, see EditorView.saveSession).
        // Being recreated (another theme, the night mode): onDestroy writes them
        // back instead.
        if (::editorService.isInitialized && !isChangingConfigurations) saveSessions()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // requires to set the new intent
        setIntent(intent)
        // receive the intent from external app; until the file tree is built
        // there is nothing to open it with, and setUpFileTree opens it
        if (fileTreeReady) {
            openViewIntent(intent)
        }
    }
    
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        // show menu icons
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (!::binding.isInitialized) return super.onPrepareOptionsMenu(menu)

        val document = current
        val editor = document?.editor
        // a document is shown and loaded, and is not being loaded, saved, parsed or
        // laid out again
        val idle = !binding.progressBar.isShown
        val ready = document != null && editor != null && document.isLoaded && idle

        // set undo option menu icon
        menu.findItem(R.id.action_undo)?.isEnabled = ready && editorViewModel.canUndo.value

        // set redo option menu icon
        menu.findItem(R.id.action_redo)?.isEnabled = ready && editorViewModel.canRedo.value

        // set edit mode option menu icon: whether the user made it read-only
        val editing = document != null && !document.isReadOnly && !document.isDetached
        menu.findItem(R.id.action_edit_mode)?.apply {
            setIcon(if (editing) R.drawable.ic_read_write else R.drawable.ic_read_only)
            // what a tap turns it to
            setTitle(if (editing) R.string.action_read_only_mode else R.string.action_edit_mode)
            isEnabled = ready && document?.isDetached == false
        }

        // compile and run: a file Termux can read; a long press sets the command
        menu.findItem(R.id.action_run)?.isEnabled =
            document?.isLoaded == true && LocalFiles.fileOf(this, document.document.getUri()) != null
        binding.toolbar.post {
            binding.toolbar.findViewById<View>(R.id.action_run)?.setOnLongClickListener {
                showRunSettings()
                true
            }
        }

        // set save option menu icon state
        menu.findItem(R.id.action_save_file)?.isEnabled =
            ready && document?.isDetached == false && editorViewModel.isModified.value

        menu.findItem(R.id.action_search)?.isEnabled = document?.isLoaded == true
        menu.findItem(R.id.action_goto_line)?.isEnabled = document?.isLoaded == true
        // formatting the document and its outline are in the floating bar (selectionMenu)

        // word wrap: checked while long lines are wrapped; it lays the text out
        // again, so not while that is going on already
        menu.findItem(R.id.action_wordwrap)?.apply {
            isChecked = appliedEditorSettings?.wordwrap ?: editor?.isWordwrap() ?: false
            isEnabled = idle
        }

        // The icons in the color of the toolbar (see applyChromeColors), as bright as
        // what they do is at hand: full for an action that can be taken, faint for one
        // that cannot (nothing to undo, nothing to save), and the edit mode icon in
        // between while the document is read-only, so editing and read-only tell apart
        // at a glance (the icon changes too, a pencil or an eye).
        val iconColor =
            toolbarIconColor ?: MaterialColors.getColor(binding.toolbar, androidx.appcompat.R.attr.colorControlNormal)
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            val emphasis = when {
                !item.isEnabled -> ICON_ALPHA_DISABLED
                item.itemId == R.id.action_edit_mode && !editing -> ICON_ALPHA_READ_ONLY
                else -> 1f
            }
            MenuItemCompat.setIconTintList(item, ColorStateList.valueOf(withEmphasis(iconColor, emphasis)))
            // a submenu (the developer tools): its icons shown and tinted the same way
            item.subMenu?.let { sub ->
                (sub as? MenuBuilder)?.setOptionalIconsVisible(true)
                for (subIndex in 0 until sub.size()) {
                    val subItem = sub.getItem(subIndex)
                    val subEmphasis = if (subItem.isEnabled) 1f else ICON_ALPHA_DISABLED
                    MenuItemCompat.setIconTintList(subItem, ColorStateList.valueOf(withEmphasis(iconColor, subEmphasis)))
                }
            }
        }
        return true
    }

    // [color] with its alpha scaled by [emphasis]
    private fun withEmphasis(color: Int, emphasis: Float): Int =
        if (emphasis >= 1f) color
        else ColorUtils.setAlphaComponent(color, (Color.alpha(color) * emphasis).toInt())

    // #region keyboard shortcuts

    // Keys come from three places, and all go to the same keymap (KeySequencer):
    //  - a hardware keyboard: here, before the view with the focus gets them;
    //  - an input method that sends keys (keyboards with Ctrl keys of their own): the
    //    editor's keyInterceptor (see ensureEditor);
    //  - Ctrl and Alt of the row of extra keys, latched: the next char of the soft
    //    keyboard (the editor's textInterceptor) or symbol of the row (onExtraSymbol).
    // Keys bound to nothing go on as usual: to the editor, which has Ctrl+C, Ctrl+Z and
    // so on of its own (KeyboardController), or to the field with the focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // A key of a keyboard; whether it was taken.
    private fun onKey(event: KeyEvent): Boolean {
        if (!::binding.isInitialized) return false
        val stroke = KeyStroke.ofEvent(event) ?: return false
        return keySequencer.feed(stroke, repeat = event.repeatCount > 0, allowed = ::canRun) != KeySequencer.Result.NONE
    }

    // Text of the soft keyboard (or a symbol of the row): a key of a shortcut while Ctrl
    // or Alt of the row is latched, or a sequence was begun; whether it was taken.
    private fun onSoftKey(text: String): Boolean {
        val extra = extraKeys ?: return false
        val ctrl = extra.ctrl
        val alt = extra.alt
        if (!ctrl && !alt && !keySequencer.isPending) return false
        // what was typed last: the input method may hand over a word it was composing
        val char = text.lastOrNull() ?: return false
        val stroke = KeyStroke.ofChar(char, ctrl, alt)
        extra.consumeModifiers()
        if (keySequencer.feed(stroke, allowed = ::canRun) == KeySequencer.Result.NONE) {
            // meant as a shortcut, bound to nothing: said, and not typed
            Toast.makeText(this, getString(R.string.keymap_unbound, stroke.toString()), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // Whether [action] can run where the focus is: one of the editor not while another
    // text field (the search panel) has it, which has Ctrl+C and the like of its own.
    private fun canRun(action: ShortcutAction): Boolean {
        if (action.scope == ShortcutCommand.Scope.GLOBAL) return true
        if (currentFocus is EditText) return false
        val document = current ?: return false
        return document.editor != null && document.isLoaded
    }

    private fun runShortcut(action: ShortcutAction) {
        when (action) {
            is ShortcutAction.Insert -> activeEditor?.let { lspController.insertSnippet(it, action.body) }
            is ShortcutAction.Run -> when (action.command.scope) {
                ShortcutCommand.Scope.EDITOR -> activeEditor?.let { runEditorCommand(action.command, it) }
                ShortcutCommand.Scope.GLOBAL -> runGlobalCommand(action.command)
            }
        }
    }

    // the keys of a sequence begun, shown until it ends (as Neovim's showcmd)
    private fun showPendingKeys(strokes: List<KeyStroke>) {
        pendingKeysToast?.cancel()
        pendingKeysToast = if (strokes.isEmpty()) null else {
            Toast.makeText(this, strokes.joinToString(""), Toast.LENGTH_SHORT).apply { show() }
        }
    }

    // The keymap (keymap.json), opened in the editor: written from a template that lists
    // the defaults and the actions the first time. Saved, it is read again (onKeymapSaved).
    private fun openKeymap() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { KeymapFile.open(this@EditorActivity) }
            }.getOrElse { e ->
                Toast.makeText(this@EditorActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                return@launch
            }
            openDocument(DocumentFile.fromFile(this@EditorActivity, file))
        }
    }

    // [document] was saved: if it is the keymap, it is read again, and the user told how
    // many mappings it has, or what is wrong with it
    private fun onKeymapSaved(document: DocumentFile) {
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) {
                val file = LocalFiles.fileOf(this@EditorActivity, document.getUri()) ?: return@withContext null
                if (!KeymapFile.isKeymapFile(this@EditorActivity, file)) return@withContext null
                KeymapFile.read(this@EditorActivity)
            } ?: return@launch
            val message = when (read) {
                is KeymapFile.Read.Ok -> {
                    keySequencer.keymap = read.keymap
                    keymapProblems = read.problems
                    if (read.problems.isEmpty()) getString(R.string.keymap_reloaded, read.count)
                    else getString(R.string.keymap_reloaded_problems, read.count, read.problems.size, read.problems.first())
                }
                is KeymapFile.Read.Failed -> getString(R.string.keymap_read_error, read.line, read.message).also {
                    keymapProblems = listOf(it)
                }
            }
            Toast.makeText(this@EditorActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun runEditorCommand(command: ShortcutCommand, editor: CodeEditorView) {
        val editable = editor.isEditable()
        when (command) {
            ShortcutCommand.COPY -> editor.copy()
            ShortcutCommand.CUT -> if (editable) editor.cut()
            ShortcutCommand.PASTE -> if (editable) editor.paste()
            ShortcutCommand.SELECT_ALL -> editor.selectAll()
            ShortcutCommand.UNDO -> if (editable) editor.undo()
            ShortcutCommand.REDO -> if (editable) editor.redo()
            ShortcutCommand.TOGGLE_COMMENT -> if (editable) commentStyleOf(editor)?.let { editor.toggleComment(it) }
            ShortcutCommand.FORMAT -> if (editable) {
                // the selection if the server can format a part, as in the floating bar
                val features = lspController.features(editor)
                lspController.format(editor, selection = editor.isSelected() && features?.rangeFormatting == true)
            }
            ShortcutCommand.OUTLINE -> lspController.showOutline(editor)
            else -> {}
        }
    }

    private fun runGlobalCommand(command: ShortcutCommand) {
        when (command) {
            ShortcutCommand.SAVE -> {
                val document = current ?: return
                if (document.isModified && !document.isSaving) saveCurrentDocument()
            }
            ShortcutCommand.SEARCH -> showSearchPanel()
            ShortcutCommand.GOTO_LINE -> showGotoLine()
            ShortcutCommand.RUN -> if (current?.isLoaded == true) compileAndRun()
            ShortcutCommand.CONSOLE -> showConsole()
            ShortcutCommand.WORDWRAP -> if (!binding.progressBar.isShown) toggleWordwrap()
            ShortcutCommand.READ_ONLY -> {
                val document = current ?: return
                if (!document.isLoaded || document.isDetached) return
                document.isReadOnly = !document.isReadOnly
                updateEditorState()
            }
            else -> {}
        }
    }

    // #endregion

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.editor_toolbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo -> { activeEditor?.undo() }
            R.id.action_redo -> { activeEditor?.redo() }
            R.id.action_edit_mode -> {
                current?.let { it.isReadOnly = !it.isReadOnly }
                updateEditorState()
            }
            R.id.action_add_folder -> {
                pickFolderLauncher.launch(null)
            }
            R.id.action_open_file -> {
                pickDocumentsLauncher.launch(
                    arrayOf("text/*", "application/*")
                )
            }
            R.id.action_search -> showSearchPanel()
            R.id.action_goto_line -> showGotoLine()
            R.id.action_lsp_config -> openLspConfig()
            R.id.action_format_rules -> openFormattingRules()
            R.id.action_toolchain -> showToolchain()
            R.id.action_snippets -> openUserSnippets()
            R.id.action_shortcuts -> ShortcutsSheet(this, keySequencer.keymap, keymapProblems, onEdit = ::openKeymap).show()
            R.id.action_console -> showConsole()
            R.id.action_run -> compileAndRun()
            R.id.action_wordwrap -> toggleWordwrap()
            R.id.action_save_file -> saveCurrentDocument()
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))           
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // Word wrap from the menu: the same setting as in the editor settings, for every editor.
    private fun toggleWordwrap() {
        val wordwrap = !(appliedEditorSettings?.wordwrap ?: activeEditor?.isWordwrap() ?: false)
        EditorSettings.setWordwrap(this, wordwrap)
        openDocuments.forEach { document ->
            document.editor?.let { if (it.isWordwrap() != wordwrap) it.setWordwrap(wordwrap) }
        }
        appliedEditorSettings = appliedEditorSettings?.copy(wordwrap = wordwrap)
        invalidateOptionsMenu()
    }

    /**
     * Applies the editor settings (see EditorSettings) to every editor: what changed
     * since they were applied last. Called whenever the editor screen comes to the
     * front, so changes made in the settings show up when coming back from there. A
     * new editor gets all of them (see ensureEditor).
     */
    private fun applyEditorSettings() {
        if (!::binding.isInitialized) return
        val settings = EditorSettings.load(this, isAppNightMode)
        val applied = appliedEditorSettings
        if (settings == applied) return

        openDocuments.forEach { document ->
            document.editor?.let { applySettings(it, settings, applied) }
        }

        // other symbols for the row of extra keys
        if (applied == null || applied.extraKeys != settings.extraKeys) {
            extraKeys?.setKeys(settings.extraKeys)
        }

        // another highlight theme: its colors for the editors and the screen around them
        if (applied == null || applied.highlightTheme != settings.highlightTheme) {
            loadEditorColors(settings)
        }

        // highlighting turned on or off: every document parsed now, or let go of
        if (applied != null && applied.syntaxHighlight != settings.syntaxHighlight) {
            openDocuments.forEach { document ->
                if (settings.syntaxHighlight) rehighlight(document) else stopHighlighting(document)
            }
        }

        // another highlight theme: into the engines that have the resources loaded
        // (the others load it with them)
        if (applied != null && applied.highlightTheme != settings.highlightTheme) {
            openDocuments.filter { it.hasHighlightResources }.forEach { document ->
                val editor = document.editor ?: return@forEach
                lifecycleScope.launch {
                    editorService.loadHighlightStyle(settings.highlightTheme, editor.syntaxEngine)
                    editor.refreshHighlighting()
                }
            }
        }

        appliedEditorSettings = settings
        invalidateOptionsMenu()
    }

    /**
     * Gives [editor] the [settings]: those that differ from [previous], what it had;
     * all of them for a new editor ([previous] null).
     */
    private fun applySettings(editor: CodeEditorView, settings: EditorSettings, previous: EditorSettings?) {
        fun <T> changed(value: (EditorSettings) -> T): Boolean =
            previous == null || value(previous) != value(settings)

        // these lay the text out again, in the background, one after the other
        if (changed { it.font }) {
            // the default font is what a new editor has already
            if (!(previous == null && settings.font == EditorFonts.DEFAULT)) {
                editor.setTypeface(EditorFonts.typeface(this, settings.font) ?: Typeface.DEFAULT)
            }
        }
        if (changed { it.textSize }) {
            val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, settings.textSize, resources.displayMetrics
            )
            editor.setTextSize(px)
        }
        if (changed { it.tabSize } && editor.getTabSize() != settings.tabSize) {
            editor.setTabSize(settings.tabSize)
        }
        if (changed { it.wordwrap } && editor.isWordwrap() != settings.wordwrap) {
            editor.setWordwrap(settings.wordwrap)
        }

        // these only change what is drawn
        if (changed { it.lineNumbers }) editor.setLineNumbersVisible(settings.lineNumbers)
        if (changed { it.gutter }) editor.setGutterEnabled(settings.gutter)
        if (changed { it.indentGuides }) editor.setIndentGuidesEnabled(settings.indentGuides)
        if (changed { it.folding }) editor.setFoldingEnabled(settings.folding)
        if (changed { it.bracketMatching }) editor.setBracketMatchingEnabled(settings.bracketMatching)
        if (changed { it.codeBlockBackground }) editor.setCodeBlockBackgroundEnabled(settings.codeBlockBackground)
        if (changed { it.colorPreview }) editor.setColorPreviewEnabled(settings.colorPreview)
        if (changed { it.autoPairs } || changed { it.autoPairChars }) {
            editor.setAutoPairs(if (settings.autoPairs) AutoPair.parse(settings.autoPairChars) else emptyList())
        }
    }

    // The highlight theme of the settings, as it is to be loaded now.
    private fun highlightThemeName(): String =
        AppSettings.resolveHighlightTheme(this, isAppNightMode)

    // the menu items depend on these: prepare the menu again when one changes
    private fun collectMenuState() {
        lifecycleScope.launch {
            merge(
                editorViewModel.canUndo,
                editorViewModel.canRedo,
                editorViewModel.isModified
            ).collect {
                invalidateOptionsMenu()
            }
        }
        
        lifecycleScope.launch {
            // the editor shown is being laid out again, or is through
            editorViewModel.isLayoutReady.collect { updateEditorState() }
        }
    }

    /**
     * Whether the editor shown can be edited, and whether the spinner turns: not
     * while its text is being read, written back from the menu, parsed for the
     * highlighting or laid out again; not when the user made it read-only, nor
     * when its file is gone.
     */
    private fun updateEditorState() {
        if (!::binding.isInitialized) return
        val document = current
        val editor = document?.editor
        val busy = document != null && (
            document.isLoading || document.isSaving || document.isParsing ||
                editor?.isLayoutReady == false
            )
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        if (document != null && editor != null) {
            editor.setEditable(
                document.isLoaded && !busy && !document.isReadOnly && !document.isDetached
            )
        }
        invalidateOptionsMenu()
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        // The process may be killed soon: write the changes now.
        openDocuments
            .filter { it.isLoaded && !it.isDetached && it.isModified }
            .forEach { launchSave(it) }
    }

    override fun onDestroy() {
        // the language servers are shut down
        if (lspControllerCreated) lspController.release()
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        // Recreated (another theme, the night mode, ...): the new screen opens the
        // documents again, from their files. Their changes are written back first,
        // in writes that outlive this screen; the new one waits for them.
        if (isChangingConfigurations && ::editorService.isInitialized) {
            saveBeforeRecreation()
        }
        // the writes above read the editors on the main thread later: what these
        // release is not what they read (render nodes, syntax trees)
        openDocuments.forEach { document ->
            document.diskDialog?.dismiss()
            document.diskDialog = null
            document.editor?.let { releaseEditor(it) }
        }
    }

    // See onDestroy: every document with changes is written back, and the session of
    // every other one is stored.
    private fun saveBeforeRecreation() {
        openDocuments
            .filter { it.isLoaded && !it.isDetached }
            .forEach { document ->
                if (document.isModified) {
                    launchSave(document)
                } else {
                    storeSession(document)
                }
            }
    }

    // #endregion

    // #region editor and its helpers
    
    // What the floating bar over the selection (or the cursor) of an editor offers: in
    // the bar search, cut, copy, paste, select all; in its overflow menu share and the
    // apps that take text (translators, ...), which can be many: the menu scrolls.
    private val selectionMenu = object : EditorView.SelectionMenu {
        override fun onCreateItems(editor: EditorView): List<FloatingBar.Item> {
            val selected = editor.isSelected()
            val editable = editor.isEditable()
            val items = ArrayList<FloatingBar.Item>()
            items += FloatingBar.Item(ID_SEARCH, getString(R.string.selection_search), drawable(R.drawable.ic_search))
            if (selected && editable) {
                items += FloatingBar.Item(ID_CUT, getString(R.string.selection_cut), drawable(R.drawable.ic_content_cut))
            }
            if (selected) {
                items += FloatingBar.Item(ID_COPY, getString(R.string.selection_copy), drawable(R.drawable.ic_content_copy))
            }
            if (editable && clipboardHasText()) {
                items += FloatingBar.Item(ID_PASTE, getString(R.string.selection_paste), drawable(R.drawable.ic_content_paste))
            }
            items += FloatingBar.Item(ID_SELECT_ALL, getString(R.string.selection_select_all), drawable(R.drawable.ic_select_all))
            // what the language server can do at the cursor
            val features = (editor as? CodeEditorView)?.let { lspController.features(it) }
            if (features != null) {
                fun lsp(id: Int, title: Int, icon: Int) =
                    FloatingBar.Item(id, getString(title), drawable(icon), overflow = true)
                if (features.hover) items += lsp(ID_LSP_HOVER, R.string.lsp_action_hover, R.drawable.ic_lsp_hover)
                if (features.definition) items += lsp(ID_LSP_DEFINITION, R.string.lsp_action_definition, R.drawable.ic_lsp_definition)
                if (features.references) items += lsp(ID_LSP_REFERENCES, R.string.lsp_action_references, R.drawable.ic_lsp_references)
                if (features.rename && editable) items += lsp(ID_LSP_RENAME, R.string.lsp_action_rename, R.drawable.ic_lsp_rename)
                if (features.codeAction && editable) items += lsp(ID_LSP_ACTIONS, R.string.lsp_action_code_actions, R.drawable.ic_lsp_code_action)
                // the selection if the server can format a part, else the whole document
                if (editable && selected && features.rangeFormatting) {
                    items += lsp(ID_LSP_FORMAT_SELECTION, R.string.lsp_action_format_selection, R.drawable.ic_lsp_format)
                } else if (editable && features.formatting) {
                    items += lsp(ID_LSP_FORMAT, R.string.lsp_action_format, R.drawable.ic_lsp_format)
                }
                if (features.documentSymbol) items += lsp(ID_LSP_OUTLINE, R.string.lsp_action_outline, R.drawable.ic_lsp_outline)
            }
            // the selection (or the line of the cursor) commented out, or back in if it is
            // one: in the overflow menu, under the outline of the language server
            if (editable) {
                commentStyleOf(editor)?.let { style ->
                    editor.isCommentedOut(style)?.let { commented ->
                        items += FloatingBar.Item(
                            ID_TOGGLE_COMMENT,
                            getString(if (commented) R.string.selection_uncomment else R.string.selection_comment),
                            drawable(R.drawable.ic_toggle_comment),
                            overflow = true,
                        )
                    }
                }
            }
            if (selected) {
                items += FloatingBar.Item(ID_SHARE, getString(R.string.selection_share), drawable(R.drawable.ic_share), overflow = true)
                processTextActivities.forEachIndexed { index, info ->
                    items += FloatingBar.Item(
                        ID_PROCESS_TEXT + index, info.loadLabel(packageManager), info.loadIcon(packageManager),
                        overflow = true, tintIcon = false
                    )
                }
            }
            return items
        }

        override fun onItemClick(editor: EditorView, item: FloatingBar.Item): Boolean {
            when (item.id) {
                ID_SEARCH -> showSearchPanel()
                ID_COPY -> editor.copy()
                ID_CUT -> editor.cut()
                ID_PASTE -> editor.paste()
                // it shows the bar again itself, over all the text
                ID_SELECT_ALL -> editor.selectAll()
                ID_TOGGLE_COMMENT -> commentStyleOf(editor)?.let { editor.toggleComment(it) }
                ID_LSP_HOVER -> lspController.showHover(editor)
                ID_LSP_DEFINITION -> lspController.gotoDefinition(editor)
                ID_LSP_REFERENCES -> lspController.findReferences(editor)
                ID_LSP_RENAME -> lspController.rename(editor)
                ID_LSP_ACTIONS -> lspController.codeActions(editor)
                ID_LSP_FORMAT -> lspController.format(editor)
                ID_LSP_FORMAT_SELECTION -> lspController.format(editor, selection = true)
                ID_LSP_OUTLINE -> lspController.showOutline(editor)
                ID_SHARE -> selectedTextToSend(editor)?.let { text ->
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    startSafely(Intent.createChooser(send, null))
                }
                else -> {
                    val info = processTextActivities.getOrNull(item.id - ID_PROCESS_TEXT) ?: return true
                    selectedTextToSend(editor)?.let { text ->
                        startSafely(
                            Intent(Intent.ACTION_PROCESS_TEXT)
                                .setType("text/plain")
                                .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                // what they give back is not put in place of the selection
                                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                        )
                    }
                }
            }
            return true
        }
    }

    // the comment marks of the language of [editor]'s document (by its name; no I/O)
    private fun commentStyleOf(editor: EditorView): CommentToggle.Style? {
        val document = openDocuments.firstOrNull { it.editor === editor } ?: return null
        return CommentStyles.forFile(document.uri.lastPathSegment ?: return null)
    }

    // the apps that take text (Intent.ACTION_PROCESS_TEXT), asked for once
    private val processTextActivities: List<ResolveInfo> by lazy {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    // The selected text, if it is small enough to go in an Intent: more than about a
    // megabyte in a transaction makes the system throw, and all of a large file would.
    private fun selectedTextToSend(editor: EditorView): String? {
        val text = editor.getSelectedText()
        if (text.length > MAX_SENT_CHARS) {
            Toast.makeText(this, R.string.selection_share_too_large, Toast.LENGTH_SHORT).show()
            return null
        }
        return text
    }

    private fun clipboardHasText(): Boolean {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        return clipboard.primaryClipDescription?.hasMimeType("text/*") == true
    }

    private fun drawable(id: Int): Drawable? = AppCompatResources.getDrawable(this, id)

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    // The configuration of the language servers, the file the app reads (servers.json,
    // written with examples the first time), opened in the editor: saved, it is read again.
    private fun openLspConfig() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { LanguageServers.configFile(this@EditorActivity) }
            openDocument(DocumentFile.fromFile(this@EditorActivity, file))
        }
    }

    // The toolchain (ToolchainSheet): Termux, which goes first, and the toolchains of
    // packages.json, which opens in the editor.
    private fun showToolchain() {
        ToolchainSheet(this, lifecycleScope, openFile = { file -> openDocument(DocumentFile.fromFile(this, file)) }).show()
    }

    // The rules documents are formatted with (FormattingRules), opened in the editor: a
    // section for the language of the document shown and for each language of the servers
    // that are on, generated (from the editor's indentation) where there is none yet, to
    // be changed there. Saved, they are read again (LspController.onDocumentSaved).
    private fun openFormattingRules() {
        val fileName = current?.document?.getName()
        val editor = activeEditor
        val indentUnit = editor?.getIndentUnit() ?: "    "
        val tabSize = editor?.getTabSize() ?: appliedEditorSettings?.tabSize ?: 4
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val ids = FormattingRules.languageIds(this@EditorActivity, fileName)
                    FormattingRules.open(this@EditorActivity, ids) { id -> FormattingRules.defaults(id, indentUnit, tabSize) }
                }
            }.getOrElse { e ->
                Toast.makeText(this@EditorActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                return@launch
            }
            openDocument(DocumentFile.fromFile(this@EditorActivity, file))
        }
    }

    // The user's own snippets for the language of the document shown (all.json, for every
    // file, when there is none), written from a template the first time, opened in the
    // editor: saved, they are read again (LspController.onDocumentSaved). friendly-snippets
    // itself is not edited: an update of it replaces it whole.
    private fun openUserSnippets() {
        val fileName = current?.document?.getName()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { FriendlySnippets.userFile(this@EditorActivity, fileName) }
            }.getOrElse { e ->
                Toast.makeText(this@EditorActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
                return@launch
            }
            openDocument(DocumentFile.fromFile(this@EditorActivity, file))
        }
    }

    // [file] opened (or shown, if it is open) at [line]:[column]: where a definition is, a
    // problem of the console. Servers and builds name files by their path; the document
    // for it is found as the user has access to it (see documentForFile), so that a file
    // opened through the Storage Access Framework is shown in its own tab, not opened
    // again by its path (which needs all files access, and left an empty tab).
    private fun openAt(file: File, line: Int, column: Int) {
        val openUris = openDocuments.map { it.uri }
        lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching { documentForFile(file, openUris) }.getOrElse { e ->
                    Log.w(TAG, "no document for $file", e)
                    null
                }
            }
            if (document == null) {
                Toast.makeText(this@EditorActivity, getString(R.string.file_open_not_in_tree, file.name), Toast.LENGTH_LONG).show()
                return@launch
            }
            val open = find(document.getUri())
            if (open != null && open.isLoaded) {
                openDocument(open.document)
                open.editor?.gotoLine(line, column, flash = true)
                return@launch
            }
            pendingGoto[document.getUri()] = line to column
            openDocument(document)
        }
    }

    // The document of [file] (a path a server or a build names), as the user can read it:
    //  1. a document that is open ([openUris]) and is that file;
    //  2. in a folder the user granted through the Storage Access Framework (the file
    //     tree's), or a file granted alone: by its document uri there;
    //  3. by its path, if the app may read it so (all files access).
    // Null if none: the app has no access to it, and asking for all files access for it
    // is not what the user wanted (the file came from a folder they granted otherwise).
    @WorkerThread
    private fun documentForFile(file: File, openUris: List<Uri>): DocumentFile? {
        val target = file.absoluteFile.normalize()
        fun isTarget(uri: Uri) = LocalFiles.fileOf(this, uri)?.absoluteFile?.normalize() == target

        openUris.firstOrNull(::isTarget)?.let { return documentOf(it) }

        LocalFiles.documentUriOf(this, target)?.let { return documentOf(it) }

        if (hasStorageAccess() || target.canRead()) return DocumentFile.fromFile(this, target)
        return null
    }

    // The console: output by channel, problems, build (see ConsoleSheet); [runNow]: a
    // command to run at once in its build tab; [showBuild]: opens on that tab. Its
    // commands run in the root of the file's project ([project], looked for here if not
    // given; see ProjectRoot).
    private fun showConsole(runNow: String? = null, project: ProjectRoot.Project? = null, showBuild: Boolean = runNow != null) {
        val file = current?.document?.getUri()?.let { LocalFiles.fileOf(this, it) }
        lifecycleScope.launch {
            val found = project ?: file?.let { projectOf(it) }
            if (isFinishing || isDestroyed) return@launch
            ConsoleSheet(
                this@EditorActivity, file, found,
                lspProblems = { if (lspControllerCreated) lspController.problems() else emptyList() },
                open = { target, line, column -> openAt(target, line, column) },
                runNow = runNow,
                showBuild = showBuild,
            ).show()
        }
    }

    // [file]'s project (ProjectRoot), read off the main thread; the file's own directory,
    // of no kind, if it cannot be read.
    private suspend fun projectOf(file: File): ProjectRoot.Project = withContext(Dispatchers.IO) {
        runCatching { ProjectRoot.of(this@EditorActivity, file) }.getOrNull()
    } ?: ProjectRoot.Project(file.absoluteFile.parentFile ?: File("/"))

    // Whether a "compile and run" is on its way (its project looked for, the file saved):
    // a tap meanwhile does nothing, so that it does not start twice.
    private var runStarting = false

    // The file's command (RunCommands: its project's, a CMake or Gradle build, else that of
    // its kind), run in the console's build tab, in the root of its project (ProjectRoot):
    // what was typed is saved first, it is the file that is compiled. No command: set one.
    // One build at a time of a project (or of a file, BuildRunner.Target): while one goes
    // on, a tap shows it rather than starting another; those of other projects run beside.
    private fun compileAndRun() {
        val document = current ?: return
        if (!document.isLoaded || runStarting) return
        val file = LocalFiles.fileOf(this, document.document.getUri())
        if (file == null) {
            Toast.makeText(this, R.string.run_file_not_local, Toast.LENGTH_LONG).show()
            return
        }
        runStarting = true
        lifecycleScope.launch {
            try {
                val project = projectOf(file)
                if (isFinishing || isDestroyed) return@launch
                val running = BuildRunner.job(BuildRunner.targetOf(file, project))?.takeIf { it.running }
                if (running != null) {
                    Toast.makeText(
                        this@EditorActivity,
                        getString(R.string.build_busy, running.target.label, BuildRunner.formatElapsed(running.elapsed)),
                        Toast.LENGTH_SHORT
                    ).show()
                    showConsole(project = project, showBuild = true)
                    return@launch
                }
                val command = RunCommands.commandFor(this@EditorActivity, file, project) ?: return@launch showRunSettings()
                if (editorViewModel.isModified.value && !document.isDetached && !document.isSaving) {
                    document.isSaving = true
                    updateEditorState()
                    launchSave(document).await()
                    document.isSaving = false
                    if (document === current) updateEditorState()
                }
                showConsole(runNow = command, project = project)
            } finally {
                runStarting = false
            }
        }
    }

    // The command of "compile and run" for the current file (a long press on it): its
    // project's, or that of its kind, shown for the root of its project.
    private fun showRunSettings() {
        val file = current?.document?.getUri()?.let { LocalFiles.fileOf(this, it) }
        if (file == null) {
            Toast.makeText(this, R.string.run_file_not_local, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val project = projectOf(file)
            if (isFinishing || isDestroyed) return@launch
            RunSettingsDialog(this@EditorActivity, file, project).show()
        }
    }

    // Go to a line of the file in the editor shown (see GotoLineDialog).
    private fun showGotoLine() {
        val editor = activeEditor ?: return
        if (current?.isLoaded != true) return
        GotoLineDialog(this, editor).show()
    }

    private fun showSearchPanel() {
        // the panel is created when the service is connected, a moment after start
        if (::searchPanelController.isInitialized && current?.isLoaded == true) {
            searchPanelController.show()
        }
    }

    private fun hideSearchPanel() {
        if (::searchPanelController.isInitialized) {
            searchPanelController.hide()
        }
    }

    /**
     * Sets the highlighting up for the text in [editor], the one of [document], named
     * [name]: loads the resources into its engine the first time, parses the text in
     * the background, then has it painted again (lines drawn meanwhile were drawn
     * plain). Call TreeSitterEngine.release before.
     */
    private suspend fun prepareHighlighting(document: OpenDocument, editor: CodeEditorView, name: String) {
        // not before the updates staged for this start are in place
        resourcesReady.await()
        try {
            // highlighting off in the settings: nothing is loaded or parsed
            if (!EditorSettings.isSyntaxHighlight(this)) {
                editor.syntaxEngine.release()
                return
            }
            if (!document.hasHighlightResources) {
                try {
                    editorService.loadHighlightResources(highlightThemeName(), editor.syntaxEngine)
                    document.hasHighlightResources = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // none yet (they are downloaded at the first start), or broken:
                    // the text is shown plain, and highlighted once they are there
                    Log.w(TAG, "no highlight resources", e)
                }
            }
            editorService.prepareHighlighting(name, editor.syntaxEngine, editor.getTextBuffer())
            // turned off while it was being parsed
            if (!EditorSettings.isSyntaxHighlight(this)) editor.syntaxEngine.release()
        } finally {
            editor.refreshHighlighting()
        }
    }

    /**
     * Highlighting was turned off: [document] is shown plain, and its tree and parser
     * are let go of. After a highlighting that is being set up (which cannot be
     * stopped halfway, it runs on another thread) is done.
     */
    private fun stopHighlighting(document: OpenDocument) {
        val previous = document.highlightJob
        previous?.cancel()
        document.highlightJob = lifecycleScope.launch {
            previous?.join()
            val editor = document.editor ?: return@launch
            editor.syntaxEngine.release()
            editor.refreshHighlighting()
        }
    }

    /**
     * Sets the highlighting of [document] up again: new resources were downloaded,
     * or it was renamed to another language. After its load, if that is running;
     * the text cannot be changed meanwhile.
     */
    private fun rehighlight(document: OpenDocument) {
        val previous = document.highlightJob
        previous?.cancel()
        document.highlightJob = lifecycleScope.launch {
            previous?.join()
            document.loadJob?.join()
            val editor = document.editor ?: return@launch
            if (!document.isLoaded || document !in openDocuments) return@launch

            document.isParsing = true
            if (document === current) updateEditorState()
            try {
                val name = withContext(Dispatchers.IO) { document.document.getName() }
                editor.syntaxEngine.release()
                prepareHighlighting(document, editor, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "failed to highlight ${document.uri}", e)
            } finally {
                document.isParsing = false
                if (document === current) updateEditorState()
            }
        }
    }

    /**
     * The tree-sitter resources at start (see TreeSitterUpdater): what was downloaded
     * in the background is put in place, before any grammar library is loaded; what
     * is missing is downloaded now, with its progress shown; the updates are checked
     * in the background from now on (TreeSitterUpdateWorker).
     */
    private fun prepareTreeSitterResources() {
        lifecycleScope.launch {
            try {
                TreeSitterUpdater.applyStaged(this@EditorActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "cannot put the staged tree-sitter resources in place", e)
            } finally {
                resourcesReady.complete(Unit)
            }
            loadEditorColors()
            downloadHighlightResources()
        }

        // put in place while the editor is open: the first download, or one the
        // background update installed because it was missing
        lifecycleScope.launch {
            TreeSitterUpdater.applied.collect { ids ->
                // the snippets are the completion's (LspController reads them again)
                if (ids.any { it != TreeSitterResources.SNIPPETS }) onResourcesApplied()
            }
        }

        TreeSitterUpdateWorker.schedule(this)
    }

    // Downloads the packages that are missing (the first start), showing the
    // progress in the download panel; they go in at once. Does nothing while a run is
    // going on. Says why when it cannot: the manifest is missing, or a package cannot
    // be reached; without a connection, that the background update installs them
    // once there is one.
    private fun downloadHighlightResources() {
        if (downloadJob?.isActive == true) return
        downloadJob = lifecycleScope.launch {
            val missing = try {
                TreeSitterUpdater.findMissing(this@EditorActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "failed to check the downloads", e)
                downloadPanelController.showError(e.message ?: e.toString())
                return@launch
            }

            // no assets/treesitter.json in the APK (or a broken one): no urls at all
            missing.problem?.let { problem ->
                downloadPanelController.showError(getString(R.string.treesitter_manifest_missing, problem))
                return@launch
            }

            val online = FileDownloader.isNetworkAvailable(this@EditorActivity)
            if (missing.unreachable.isNotEmpty() && !online) {
                // TreeSitterUpdateWorker installs them as soon as there is a connection
                Snackbar.make(binding.contentMain, R.string.treesitter_waiting_network, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.extraKeysPanel)
                    .show()
            }
            // Online, a package the check got no answer for is downloaded anyway: what
            // fails then says why (the version is taken from the download itself).
            val toDownload = if (online) {
                missing.updates + missing.unreachable.map {
                    TreeSitterUpdater.Update(it, RemoteFile(etag = null, lastModified = null, length = null), isMissing = true)
                }
            } else {
                missing.updates
            }
            if (toDownload.isNotEmpty()) {
                downloadPanelController.start(toDownload.size)
                toDownload.forEachIndexed { index, update ->
                    val url = update.pkg.url
                    downloadPanelController.update(index, url, 0)
                    try {
                        TreeSitterUpdater.download(this@EditorActivity, update) { progress ->
                            downloadPanelController.update(index, url, progress)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "failed to download $url", e)
                        // stays until it is closed, or retried
                        downloadPanelController.showError(e.message ?: e.toString())
                        return@launch
                    }
                }
                // nothing of them was there: they go in at once (and onResourcesApplied
                // has every editor load them)
                TreeSitterUpdater.applyStaged(this@EditorActivity)
            }

            if (toDownload.isNotEmpty()) {
                downloadPanelController.showSuccess()
            }
        }
    }

    // New tree-sitter resources were put in place: every editor loads them again,
    // and its text is highlighted with them (one still being read once it is: see
    // rehighlight). The editor colors may have come with a theme too.
    private fun onResourcesApplied() {
        HighlightThemes.clear()
        openDocuments.forEach { it.hasHighlightResources = false }
        openDocuments.filter { it.isLoaded || it.isLoading }.forEach { rehighlight(it) }
        loadEditorColors()
    }

    /**
     * The colors of the editor itself (background, cursor, line of the cursor, ...)
     * for every editor, from the "editor" block of the highlight theme; a theme
     * without that block: the system's. The screen around them follows: its bars in
     * the same colors (applyChromeColors), and light or dark as the theme is
     * (followHighlightTheme), which may recreate it.
     */
    private fun loadEditorColors(settings: EditorSettings = EditorSettings.load(this, isAppNightMode)) {
        lifecycleScope.launch {
            val theme = withContext(Dispatchers.IO) {
                HighlightThemes.get(this@EditorActivity, settings.highlightTheme)
            }
            if (theme != null) {
                followHighlightTheme(settings.highlightTheme, HighlightThemes.isDark(theme))
                // being recreated: the new screen does all of this again
                if (isFinishing || isChangingConfigurations) return@launch
            }
            editorColors = theme?.editorColors
            openDocuments.forEach { document -> document.editor?.let { applyEditorColors(it) } }
            applyChromeColors()
        }
    }

    /**
     * The bars around the editors in the colors of the editor, so that the screen is
     * of one piece: the status bar (the window behind it), the toolbar and the tabs,
     * and the row of extra keys (which lies over the navigation bar); their text and
     * icons in the color of the text, the indicator and the spinner in the color of
     * the cursor, the icons of the system bars light or dark as the background needs.
     * With the system's colors, or a theme without a background, the app theme's.
     */
    private fun applyChromeColors() {
        val scheme = editorColors
        val background = scheme?.background
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        if (background == null) {
            window.setBackgroundDrawable(defaultWindowBackground)
            binding.drawerLayout.background = defaultDrawerBackground
            (binding.navViewStart.background as? MaterialShapeDrawable)?.fillColor = defaultNavigationFill
            binding.appBar.background = defaultAppBarBackground
            binding.toolbar.background = defaultToolbarBackground
            binding.toolbar.setTitleTextColor(MaterialColors.getColor(binding.toolbar, MaterialR.attr.colorOnSurface))
            drawerToggle.drawerArrowDrawable.color = defaultArrowColor
            binding.toolbar.overflowIcon?.mutate()?.setTint(
                MaterialColors.getColor(binding.toolbar, androidx.appcompat.R.attr.colorControlNormal)
            )
            binding.progressBar.setIndicatorColor(
                MaterialColors.getColor(binding.progressBar, androidx.appcompat.R.attr.colorPrimary)
            )
            toolbarIconColor = null
            extraKeyColors = null
            extraKeys?.setColors(null)
            editorTabs.setColors(null)
            controller.isAppearanceLightStatusBars = !isNightMode
            controller.isAppearanceLightNavigationBars = !isNightMode
        } else {
            val isLight = ColorUtils.calculateLuminance(background) > 0.5
            val foreground = scheme.foreground ?: if (isLight) Color.DKGRAY else Color.LTGRAY
            val muted = ColorUtils.setAlphaComponent(foreground, 0x99)
            val accent = scheme.cursor ?: foreground

            window.setBackgroundDrawable(ColorDrawable(background))
            binding.drawerLayout.setBackgroundColor(background)
            // the drawer a little off the background, as a sheet over it
            (binding.navViewStart.background as? MaterialShapeDrawable)?.fillColor =
                ColorStateList.valueOf(ColorUtils.blendARGB(background, foreground, 0.04f))
            binding.appBar.setBackgroundColor(background)
            binding.toolbar.setBackgroundColor(Color.TRANSPARENT)
            binding.toolbar.setTitleTextColor(foreground)
            drawerToggle.drawerArrowDrawable.color = foreground
            binding.toolbar.overflowIcon?.mutate()?.setTint(foreground)
            binding.progressBar.setIndicatorColor(accent)
            toolbarIconColor = foreground
            // keys a little off the background, so that they are seen; the cursor's
            // color for the keys that act on the completion list
            extraKeyColors = ExtraKeysController.Colors.of(background, foreground, accent)
            extraKeys?.setColors(extraKeyColors)
            editorTabs.setColors(
                EditorTabsController.Colors(
                    background = background,
                    selected = foreground,
                    unselected = muted,
                    indicator = accent
                )
            )
            controller.isAppearanceLightStatusBars = isLight
            controller.isAppearanceLightNavigationBars = isLight
        }

        invalidateOptionsMenu()
    }

    private fun applyEditorColors(editor: CodeEditorView) {
        EditorColorScheme.applyTo(editor, editorColors, isNightMode)
        editor.setCodeBlockColor(EditorColorScheme.codeBlockColor(editorColors, isNightMode))
    }

    // The row of extra keys over the keyboard: Tab, the symbols of the settings and the
    // arrow keys, which move through the completion list while it is shown (see
    // ExtraKeysController). The symbols change with the settings, see applyEditorSettings.
    private fun setUpExtraKeys() {
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        extraKeys = ExtraKeysController(
            binding = binding,
            activeEditor = { activeEditor },
            haptic = { vibrator.vibrate(effect) },
            onSymbol = { symbol -> onSoftKey(symbol) }
        ).apply {
            setColors(extraKeyColors)
            setKeys(appliedEditorSettings?.extraKeys ?: EditorSettings.getExtraKeys(this@EditorActivity))
        }
    }

    // the keys show whether the completion list of the editor shown is up
    private fun updateExtraKeysCompletion() {
        extraKeys?.setCompletionActive(activeEditor?.isShowingCompletions == true)
    }

    // #endregion

    // #region file tree

    // Builds the file tree: "external storage", then the folders and files the app
    // still has the permission for. Then opens the document of the intent, or the
    // one that was open the last time.
    private fun setUpFileTree() {
        fileTreeController = FileTreeController(
            activity = this,
            binding = binding,
            currentUri = { current?.uri },
            hasStorageAccess = ::hasStorageAccess,
            onOpen = { document -> openDocument(document) },
            onRemove = { uri -> releasePersistedPermission(uri) },
            beforeChange = { _, _ ->
                fileTreeChanges++
                awaitRunningSaves()
            },
            afterChange = { fileTreeChanges = maxOf(0, fileTreeChanges - 1) },
            hasUnsavedChangesIn = { uri, isDirectory ->
                openDocuments.any { affects(it.uri, uri, isDirectory) && it.isModified }
            },
            onRenamed = { oldUri, newDocument, newName, isDirectory ->
                onDocumentRenamed(oldUri, newDocument.getUri(), newName, isDirectory)
            },
            onDeleted = { uri, isDirectory -> onDocumentDeleted(uri, isDirectory) },
            onStorageAccessRequired = ::requestStorageAccess
        ).also {
            it.attach()
        }

        lifecycleScope.launch {
            // the persisted permissions, oldest first: tree uris are folders, the rest files
            val (folders, files) = withContext(Dispatchers.IO) {
                val valid = contentResolver.persistedUriPermissions
                    .sortedBy { it.persistedTime }
                    .map { it.uri }
                    .filter { uri ->
                        if (isAccessible(uri)) {
                            true
                        } else {
                            // the document is gone: the permission is of no use any more
                            releasePersistedPermission(uri)
                            false
                        }
                    }
                valid.partition { DocumentsContract.isTreeUri(it) }
            }
            // the tree starts collapsed
            fileTreeController.addFolders(folders, reveal = false)
            fileTreeController.addFiles(files)
            fileTreeReady = true

            // the tabs of the last run, those that can still be opened
            val savedTabs = editorTabs.savedUris()
            val tabs = withContext(Dispatchers.IO) { savedTabs.filter { isAccessible(it) } }
            // their text is read when they are shown
            tabs.forEach { addDocument(documentOf(it)) }

            // picked while the tree was being built
            val pickedFolders = pendingFolderUris.toList()
            val pickedFiles = pendingFileUris.toList()
            pendingFolderUris.clear()
            pendingFileUris.clear()
            if (pickedFolders.isNotEmpty()) addPickedFolder(pickedFolders.last())
            if (pickedFiles.isNotEmpty()) {
                addPickedDocuments(pickedFiles)
            } else if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
                openViewIntent(intent)
            } else {
                chooseStartDocument(tabs, files)?.let { openDocument(it) }
            }
            // nothing opened: "no file open"
            updateEmptyState()
        }
    }

    // The document to open at start: the one that was open the last time if it can
    // still be opened (a file of the list, or one inside a folder or the storage),
    // else the first of the tabs, else the first file of the list, else none.
    private suspend fun chooseStartDocument(tabs: List<Uri>, files: List<Uri>): DocumentFile? {
        val lastUri = activityPreferences.getString(Constants.PREF_CURRENT_OPENED_URI, null)
            ?.let { Uri.parse(it) }
        if (lastUri != null && (lastUri in tabs || withContext(Dispatchers.IO) { isAccessible(lastUri) })) {
            return documentOf(lastUri)
        }
        return (tabs.firstOrNull() ?: files.firstOrNull())?.let { documentOf(it) }
    }

    // A document for a uri of the tree; no I/O. file:// uris are the files under
    // "external storage".
    private fun documentOf(uri: Uri): DocumentFile {
        val path = uri.path
        return if (uri.scheme == ContentResolver.SCHEME_FILE && path != null) {
            DocumentFile.fromFile(this, File(path))
        } else {
            DocumentFile.fromSingleUri(this, uri)
        }
    }

    // check if the uri exists and access permission. A tree uri itself cannot be
    // queried (providers only answer for documents): its top document is checked.
    private fun isAccessible(uri: Uri): Boolean {
        return try {
            val target = if (
                DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(this, uri)
            ) {
                DocumentsContract.buildDocumentUriUsingTree(
                    uri, DocumentsContract.getTreeDocumentId(uri)
                )
            } else {
                uri
            }
            DocumentFile.isValid(this, target)
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    // "add": a folder for the file tree
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@registerForActivityResult
        if (fileTreeReady) {
            addPickedFolder(treeUri)
        } else {
            // The result arrived before the tree was built (the activity was
            // recreated while the picker was open): keep the access, add it then.
            takePersistedPermission(treeUri)
            pendingFolderUris.add(treeUri)
        }
    }

    // "open": files for the file tree
    private val pickDocumentsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (fileTreeReady) {
            addPickedDocuments(uris)
        } else {
            // see pickFolderLauncher
            uris.forEach { takePersistedPermission(it) }
            pendingFileUris.addAll(uris)
        }
    }

    private fun addPickedFolder(treeUri: Uri) {
        // see the AOSP framework/**/UriGrantsManagerService.java
        // android 11 and above the value of MAX_PERSISTED_URI_GRANTS is 512
        if (!fileTreeController.containsRoot(treeUri) && !takePersistedPermission(treeUri)) {
            return
        }
        lifecycleScope.launch {
            // expands the folder and scrolls to it (also when it was listed already)
            fileTreeController.addFolders(listOf(treeUri))
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun addPickedDocuments(uris: List<Uri>) {
        val newUris = uris.filter { uri ->
            // see addPickedFolder for the limit of persisted permissions
            !fileTreeController.containsRoot(uri) && takePersistedPermission(uri)
        }
        lifecycleScope.launch {
            fileTreeController.addFiles(newUris)
        }

        // open the first of the picked documents; the others get tabs after it, in
        // order, and are read when they are shown
        openDocument(documentOf(uris.first()))
        uris.drop(1).asReversed().forEach { addDocument(documentOf(it)) }

        with(binding.drawerLayout) {
            if (isDrawerOpen(GravityCompat.START)) {
                closeDrawer(GravityCompat.START)
            }
        }
    }

    // #region rename and delete in the file tree
    //
    // How the open documents follow the file tree:
    //  - Before a rename or delete, the saves that are running are let finish: none
    //    must write a text under the old name afterwards.
    //  - Renamed (the file, or a folder above it): each document stays open, with
    //    its text, unsaved changes, undo history and cursor; it is just filed under
    //    the new uri (also its stored session and its tab). If the extension
    //    changed, the highlighting is set up again for the new language.
    //  - A renamed SAF folder of a provider whose ids are not paths cannot be
    //    followed: such a document is detached, its text stays readable but cannot
    //    be saved, and a message says to open it again.
    //  - Deleted (the file, or a folder above it): the confirmation has warned about
    //    unsaved changes. The document is closed without being written, so nothing
    //    can write the file back to the disk.

    // Whether [document] is [changed], or inside it for a directory.
    private fun affects(document: Uri, changed: Uri, isDirectory: Boolean): Boolean =
        if (isDirectory) DocumentPaths.isWithin(document, changed) else document == changed

    // see the rules above; for every rename and delete
    private suspend fun awaitRunningSaves() {
        DocumentSaves.awaitAll()
    }

    private fun onDocumentRenamed(oldUri: Uri, newUri: Uri, newName: String, isDirectory: Boolean) {
        for (document in openDocuments.toList()) {
            val uri = document.uri
            val moved = (
                if (isDirectory) DocumentPaths.relocate(uri, oldUri, newUri)
                else newUri.takeIf { uri == oldUri }
            )

            if (moved == null) {
                // Not inside, or a SAF folder whose ids are opaque: find out which.
                if (isDirectory && !document.isDetached &&
                    uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == oldUri.authority
                ) {
                    lifecycleScope.launch {
                        val stillThere = withContext(Dispatchers.IO) { isAccessible(uri) }
                        if (!stillThere && document in openDocuments && document.uri == uri) {
                            detachDocument(document)
                        }
                    }
                }
                continue
            }

            document.document = documentOf(moved)
            editorTabs.rename(uri, moved, if (isDirectory) null else newName)
            if (document === current) {
                activityPreferences.edit()
                    .putString(Constants.PREF_CURRENT_OPENED_URI, moved.toString())
                    .apply()
            }

            lifecycleScope.launch {
                // undo history and cursor go with the document
                editorService.moveSession(uri, moved)
                // a.txt -> a.kt: another language
                if (!isDirectory && document.isLoaded &&
                    extensionOf(newName) != extensionOf(uri.lastPathSegment.orEmpty())
                ) {
                    rehighlight(document)
                }
            }
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('/').substringAfterLast('.', "").lowercase()


    // [document] was moved where it cannot be followed: its text stays in its
    // editor, read-only and not saveable (there is no file for it any more).
    private fun detachDocument(document: OpenDocument) {
        val name = document.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        document.loadJob?.cancel()
        document.isDetached = true
        if (document === current) updateEditorState()
        Snackbar.make(binding.contentMain, getString(R.string.file_moved, name), Snackbar.LENGTH_LONG)
            .setAnchorView(binding.extraKeysPanel)
            .show()
    }

    private fun onDocumentDeleted(uri: Uri, isDirectory: Boolean) {
        val deleted = openDocuments.filter { affects(it.uri, uri, isDirectory) }
        // not written back, and what was kept for them goes too
        closeDocuments(deleted, keepSessions = false)
    }

    // #endregion

    // a document handed over by another app (ACTION_VIEW)
    private fun openViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openExternalUri(it) }
        }
    }
    
    // received intent content uri from external app
    private fun openExternalUri(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        // it is the open document already
        if (uri == current?.uri) return

        // List it if the other app lets us keep the access; if not, the document
        // is open for this once only.
        if (!fileTreeController.containsRoot(uri) && takePersistedPermission(uri)) {
            lifecycleScope.launch { fileTreeController.addFiles(listOf(uri)) }
        }
        openDocument(DocumentFile.fromSingleUri(this, uri))
    }

    // Keeps the access to a document beyond this run of the app.
    // false if whoever granted it did not allow that.
    private fun takePersistedPermission(uri: Uri): Boolean {
        return try {
            contentResolver.takePersistableUriPermission(uri, persistableUriFlags)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable permission for $uri", e)
            false
        }
    }

    private fun releasePersistedPermission(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(uri, persistableUriFlags)
        } catch (e: SecurityException) {
            // it was never persisted, or is gone already
            Log.w(TAG, "no persisted permission to release for $uri", e)
        }
    }

    // Whether "external storage" (/sdcard) may be read: "all files access" on
    // Android 11 and above, the storage permission below.
    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    // Asks for the access to /sdcard; when granted, the folder that was refused is
    // listed again (FileTreeController.retryStorageAccess).
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                manageStorageLauncher.launch(appIntent)
            } catch (e: ActivityNotFoundException) {
                // some systems have only the list of all apps
                try {
                    manageStorageLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no settings screen for all files access", e)
                }
            }
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasStorageAccess()) fileTreeController.retryStorageAccess()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStorageAccess()) fileTreeController.retryStorageAccess()
    }

    // #endregion

    // #region documents
    //
    // The rules:
    //  - Every open document has a tab (EditorTabsController) and an editor of its
    //    own, created when it is shown first; its text is read then. The selected
    //    tab is the document shown. Showing another one changes nothing else: each
    //    keeps its text, unsaved changes, undo history and cursor in its editor.
    //  - A document is written back only if it was read completely (isLoaded), and
    //    only by save (the menu, closing its tab, low memory).
    //  - The saves of a document run one after the other, and are never cancelled.
    //  - Closing a tab with unsaved changes asks whether to save them; one that
    //    cannot be written stays open, with its changes.
    //  - The session of a document (cursor, folds, undo history) is stored when it is
    //    saved, when its tab is closed, and when the app goes to the background.

    private fun find(uri: Uri): OpenDocument? = openDocuments.firstOrNull { it.uri == uri }

    /** Opens [document]: shows it if it is open already, else in a new tab and editor. */
    private fun openDocument(document: DocumentFile) {
        val open = find(document.getUri()) ?: addDocument(document)
        showDocument(open)
    }

    // A tab for [document], after the one shown; its text is read when it is shown.
    private fun addDocument(document: DocumentFile): OpenDocument {
        find(document.getUri())?.let { return it }
        val open = OpenDocument(document)
        openDocuments.add(open)
        editorTabs.add(open.uri)
        return open
    }

    // The user selected the tab of [uri].
    private fun onTabSelected(uri: Uri) {
        val document = find(uri) ?: return
        if (document !== current) showDocument(document)
    }

    /**
     * Shows [document]: its editor (the one shown before is hidden, as it is), its
     * tab, its entry in the file tree. Its text is read the first time (and again
     * after a load that failed).
     */
    private fun showDocument(document: OpenDocument) {
        val previous = current
        val previousEditor = previous?.editor
        if (previous !== document) {
            // the search and its matches belong to the editor that was shown
            hideSearchPanel()
        }
        current = document

        val editor = ensureEditor(document)
        val hadFocus = previousEditor?.hasFocus() == true
        if (previousEditor != null && previousEditor !== editor) {
            previousEditor.isActive = false
            // its completion list and its floating bar belong to its text, not to
            // the one shown now (EditorView also lets them go once it is hidden)
            previousEditor.dismissCompletions()
            previousEditor.dismissSelectionMenu()
            previousEditor.clearFocus()
            // Hidden as it is: laid out still, so nothing is laid out again when it
            // is shown. Its hardware layer (a texture the size of the screen) goes.
            previousEditor.visibility = View.INVISIBLE
            previousEditor.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        editor.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        editor.visibility = View.VISIBLE
        // from here on the menu and the spinner are this editor's
        editor.isActive = true
        // the keyboard was up for the one before: it stays up for this one
        if (hadFocus) editor.requestFocus()

        editorTabs.addAndSelect(document.uri)
        activityPreferences.edit()
            .putString(Constants.PREF_CURRENT_OPENED_URI, document.uri.toString())
            .apply()
        if (::fileTreeController.isInitialized) {
            // move the highlight in the file tree
            fileTreeController.notifyChanged(previous?.uri)
            fileTreeController.notifyChanged(document.uri)
        }
        updateEmptyState()

        if (!document.isLoaded && !document.isLoading && !document.isDetached) {
            loadDocument(document)
        } else if (previous !== document) {
            // it may have been changed while another one was shown
            checkDisk(document)
        }
        updateExtraKeysCompletion()
        updateEditorState()
    }

    // No document is shown: none is open.
    private fun showNothing() {
        current = null
        updateExtraKeysCompletion()
        editorTabs.clearSelection()
        activityPreferences.edit()
            .remove(Constants.PREF_CURRENT_OPENED_URI)
            .apply()
        editorViewModel.setCanUndo(false)
        editorViewModel.setCanRedo(false)
        editorViewModel.setModified(false)
        editorViewModel.setLayoutReady(true)
        updateEmptyState()
        updateEditorState()
    }

    // "No file open", once the start is through and none is
    private fun updateEmptyState() {
        if (!::binding.isInitialized) return
        binding.emptyState.visibility = if (fileTreeReady && current == null) View.VISIBLE else View.GONE
        // The row of extra keys only with a document to type in. Invisible rather than
        // gone: it keeps its height, which the editors are laid out with (extraKeysHeight).
        binding.extraKeysPanel.visibility = if (current == null) View.INVISIBLE else View.VISIBLE
    }

    // The editor of [document]; created the first time, with the settings every
    // editor has.
    private fun ensureEditor(document: OpenDocument): CodeEditorView {
        document.editor?.let { return it }
        val editor = CodeEditorView(this).apply {
            //setTypeface(resources.getFont(R.font.jetbrains_mono_regular))
            setSelectionMenu(selectionMenu)
            setHapticFeedbackEnabled(false)
            setBottomOverlay(binding.extraKeysPanel)
            if (extraKeysHeight > 0) setMargin(bottomMargin = extraKeysHeight)
            // matching brackets: a highlight behind them and bold, no line under them
            setBracketMatchStyle(highlight = true, bold = true, underline = false)
            // nothing to edit until its text is read
            setEditable(false)
            visibility = View.INVISIBLE
            // the dot on its tab, also while it is not the one shown
            onModifiedListener = { modified -> editorTabs.setModified(document.uri, modified) }
            // the extra keys act on its completion list while it is shown
            onCompletionsShownChanged = { shown ->
                if (document === current) extraKeys?.setCompletionActive(shown)
            }
            // keys and text of the soft keyboard that are keys of shortcuts (keymap.json)
            keyInterceptor = { event -> onKey(event) }
            textInterceptor = { text -> onSoftKey(text) }
        }
        binding.editorContainer.addView(
            editor,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // what the other editors have; applyEditorSettings brings all of them up to date
        applySettings(editor, appliedEditorSettings ?: EditorSettings.load(this, isAppNightMode), previous = null)
        applyEditorColors(editor)
        document.editor = editor
        return editor
    }

    // The editor of a document that was closed: what it holds outside the heap.
    private fun releaseEditor(editor: CodeEditorView) {
        editor.recycleRenderNodes()
        editor.syntaxEngine.release()
    }

    /**
     * Reads the text of [document] into its editor, puts back the session stored for
     * it if the file is still the one it was saved for, and sets the highlighting up.
     * A failure is shown; the document stays, empty, and is read again when it is
     * shown again.
     *
     * [cursor]: where to put the cursor afterwards (line and column, 1-based), for a
     * document that is read again (see reloadDocument); the session's place otherwise.
     */
    private fun loadDocument(document: OpenDocument, cursor: Pair<Int, Int>? = null) {
        val editor = ensureEditor(document)
        val uri = document.uri
        document.isLoading = true
        document.loadJob = lifecycleScope.launch {
            try {
                // A write of the file may still be running: the screen before this
                // one wrote it back as it was recreated. Read what it wrote.
                DocumentSaves.await(uri)

                // the syntax tree belongs to the text that is about to be replaced
                editor.syntaxEngine.release()

                // Taken before the file is read: a write by another app while it is
                // read gives another stamp, and the next check compares the contents.
                val diskState = withContext(Dispatchers.IO) { DiskChanges.stateOf(this@EditorActivity, uri) }
                val loaded = editorService.readDocument(uri)
                val session = editorService.loadSession(uri)

                // EditorView.setBuffer lays the text out and must not run on the main thread
                val name = withContext(Dispatchers.IO) {
                    // The editor refuses another text while a pinch-zoom is in progress.
                    while (!editor.setBuffer(loaded.buffer)) {
                        delay(EDITOR_BUSY_RETRY_DELAY_MS)
                    }
                    document.document.getName()
                }
                document.contentHash = loaded.contentHash
                document.diskHash = loaded.contentHash
                document.diskStamp = (diskState as? DiskState.Present)?.stamp
                document.isMissingOnDisk = false

                // main thread again, and setBuffer has returned: what restoreSession asks for
                if (session != null) {
                    restoreSession(editor, uri, session, loaded.contentHash)
                }

                // the text must not change while it is parsed: not loaded until then
                prepareHighlighting(document, editor, name)

                document.isLoaded = true
                // completion: the document goes to its language server, if it has one
                lspController.onDocumentLoaded(editor, document.document)
                // read again: the cursor where it was, as far as the new text has lines
                if (cursor != null) editor.gotoLine(cursor.first, cursor.second) else editor.centerOnCursor()
                // opened to show a place in it (a definition): there
                pendingGoto.remove(document.uri)?.let { (line, column) -> editor.gotoLine(line, column, flash = true) }
                if (document === current) {
                    // the history came with the session: the menu shows it
                    editorViewModel.setCanUndo(editor.canUndo())
                    editorViewModel.setCanRedo(editor.canRedo())
                    editorViewModel.setModified(editor.isModified())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage providers fail in many ways (IOException,
                // SecurityException, IllegalArgumentException, ...): none of them
                // is a reason to crash, the document just cannot be opened.
                Log.e(TAG, "failed to open $uri", e)
                // opened only to show a place in it (a definition, a problem): its tab, with
                // nothing in it, goes again (after this job, which closing it cancels)
                if (pendingGoto.remove(uri) != null && !document.isLoaded) {
                    binding.root.post {
                        if (document in openDocuments && !document.isLoaded) closeDocuments(listOf(document), keepSessions = true)
                    }
                }
                if (e.isPermissionDenied()) {
                    showPermissionDenied(uri)
                } else {
                    showError(document.document, e)
                }
            } finally {
                document.isLoading = false
                if (document === current) updateEditorState()
            }
        }
    }

    // Puts the stored session back on the text that was just loaded. The editor
    // itself checks that it is the text the session was saved for (the hash of the
    // file, the length, the number of lines): undo steps replayed on any other text
    // would garble it.
    private suspend fun restoreSession(
        editor: CodeEditorView,
        uri: Uri,
        session: EditSession,
        contentHash: String
    ) {
        while (true) {
            when (editor.restoreSession(session, contentHash)) {
                EditSession.RestoreResult.RESTORED -> return
                EditSession.RestoreResult.CONTENT_MISMATCH -> {
                    // the file was changed by someone else: it will never fit again
                    editorService.deleteSession(uri)
                    return
                }
                // it may still fit the file the next time it is opened: keep it
                EditSession.RestoreResult.TEXT_MODIFIED -> return
                // still laying the text out: nothing was checked, try again
                EditSession.RestoreResult.BUSY -> delay(EDITOR_BUSY_RETRY_DELAY_MS)
            }
        }
    }

    // #region changes on disk
    //
    // Another app may change or delete the file of an open document: a build in Termux,
    // a git checkout, another editor. The document shown is checked when the screen
    // comes back to the front, when its tab is selected, and, while it is shown, at the
    // interval of the settings (see onCreate, EditorSettings.getDiskCheckIntervalMillis);
    // only the stamp of its file is asked for (DiskChanges), and the file is read only
    // when that changed.
    //  - Changed, to another content than the one in the editor: the user is asked
    //    whether to read it again (the unsaved changes go) or to keep the text in the
    //    editor, which then counts as unsaved; saving it writes over the change.
    //  - Deleted: whether to keep it open (unsaved: saving creates the file again) or
    //    to close it.
    // Its own saves, and renames and deletes in the file tree, are not taken for such
    // changes: the stamp is taken again after each save, and no check runs meanwhile.

    // Whether [document] can be checked now.
    private fun canCheckDisk(document: OpenDocument): Boolean =
        ::editorService.isInitialized && !isFinishing &&
            document in openDocuments && document.isLoaded && !document.isLoading &&
            !document.isDetached && !document.isSaving && !document.isMissingOnDisk &&
            document.diskDialog == null && document.diskCheckJob?.isActive != true &&
            fileTreeChanges == 0 && !DocumentSaves.isRunning(document.uri)

    /**
     * Finds out whether the file of [document] was changed or deleted by another app,
     * and asks the user what to do about it if it was. Does nothing while the document
     * is being read or written, or a question about it is open.
     */
    private fun checkDisk(document: OpenDocument) {
        if (!canCheckDisk(document)) return
        val uri = document.uri
        val known = document.diskStamp
        document.diskCheckJob = lifecycleScope.launch {
            // still the file and the text the check began with, and nothing else on it
            fun unchangedMeanwhile(): Boolean =
                document.uri == uri && document.diskStamp == known &&
                    document in openDocuments && document.isLoaded && !document.isSaving &&
                    !document.isMissingOnDisk && document.diskDialog == null &&
                    fileTreeChanges == 0 && !DocumentSaves.isRunning(uri)

            val state = withContext(Dispatchers.IO) { DiskChanges.stateOf(this@EditorActivity, uri) }
            if (!unchangedMeanwhile()) return@launch
            when (DiskChanges.verdict(known, state)) {
                DiskChanges.Verdict.UNCHANGED -> {}

                DiskChanges.Verdict.DELETED -> {
                    // Asked again a moment later: a file written by deleting and creating
                    // it anew (some tools save that way) is only gone for an instant.
                    delay(DISK_DELETE_RECHECK_MS)
                    val again = withContext(Dispatchers.IO) { DiskChanges.stateOf(this@EditorActivity, uri) }
                    if (!unchangedMeanwhile()) return@launch
                    when (again) {
                        DiskState.Missing -> if (document === current) askAboutDeletedFile(document)
                        // back already: the next check compares what it holds
                        else -> {}
                    }
                }

                DiskChanges.Verdict.STAMP_CHANGED -> {
                    val stamp = (state as DiskState.Present).stamp
                    val hash = try {
                        editorService.hashDocument(uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: FileNotFoundException) {
                        // deleted right after: the next check finds it gone
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "cannot read $uri to compare it", e)
                        return@launch
                    }
                    if (!unchangedMeanwhile()) return@launch
                    if (hash == document.diskHash) {
                        // touched, or written with the same content: nothing to ask
                        document.diskStamp = stamp
                    } else if (document === current) {
                        askAboutChangedFile(document, stamp, hash)
                    }
                    // not shown any more: asked when it is shown again
                }
            }
        }
    }

    // The name of [document] for a message: its tab's.
    private fun displayName(document: OpenDocument): String =
        editorTabs.tabName(document.uri)
            ?: document.uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')

    // The file of [document] now holds the content of [hash], with [stamp]: read it
    // again, or keep the text of the editor?
    private fun askAboutChangedFile(document: OpenDocument, stamp: DocumentStamp, hash: String) {
        val name = displayName(document)
        val message = getString(
            if (document.isModified) R.string.file_changed_message_modified else R.string.file_changed_message,
            name
        )
        document.diskDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_changed_title)
            .setMessage(message)
            // a decision is needed: a tap beside it must not be taken for one
            .setCancelable(false)
            .setPositiveButton(R.string.file_changed_reload) { _, _ ->
                document.diskDialog = null
                if (document in openDocuments) reloadDocument(document)
            }
            .setNegativeButton(R.string.file_changed_keep) { _, _ ->
                document.diskDialog = null
                if (document in openDocuments) keepEditorText(document, stamp, hash)
            }
            .show()
    }

    // The file of [document] is gone: keep the text (and create the file again when it
    // is saved), or close it?
    private fun askAboutDeletedFile(document: OpenDocument) {
        document.diskDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_deleted_title)
            .setMessage(getString(R.string.file_deleted_message, displayName(document)))
            .setCancelable(false)
            .setPositiveButton(R.string.file_deleted_keep) { _, _ ->
                document.diskDialog = null
                if (document in openDocuments) {
                    document.isMissingOnDisk = true
                    document.diskStamp = null
                    // nothing on the disk holds this text: unsaved
                    document.editor?.markUnsaved()
                    if (document === current) updateEditorState()
                }
            }
            .setNegativeButton(R.string.action_close) { _, _ ->
                document.diskDialog = null
                // not written back, and its session goes with the file
                if (document in openDocuments) closeDocuments(listOf(document), keepSessions = false)
            }
            .show()
    }

    // The text in the editor of [document] stays, although its file now holds the
    // content of [hash]: it is unsaved, and saving it writes over that content. The
    // change is not asked about again; the next one is.
    private fun keepEditorText(document: OpenDocument, stamp: DocumentStamp, hash: String) {
        document.diskStamp = stamp
        document.diskHash = hash
        document.editor?.markUnsaved()
        if (document === current) updateEditorState()
    }

    // Reads the file of [document] again into its editor, as it is now: unsaved changes
    // and the undo history go, the cursor stays on its line. The session stored for
    // the old content is let go by the load (its hash no longer fits).
    private fun reloadDocument(document: OpenDocument) {
        val editor = document.editor ?: return
        if (document.isLoading || document.isDetached) return
        val cursor = editor.getCursorLine() to editor.getCursorColumn()
        // nothing is to be set up on the old text any more
        document.highlightJob?.cancel()
        document.diskCheckJob?.cancel()
        editor.dismissCompletions()
        editor.dismissSelectionMenu()
        // the language server is told of the new text as of a document opened anew
        lspController.onDocumentClosed(editor)
        document.isLoaded = false
        loadDocument(document, cursor)
        if (document === current) updateEditorState()
    }

    /**
     * Creates the file of [document] again, which was deleted by another app and kept
     * open: at the same path for a file, in the same folder for a document of a
     * provider. Called from its save, before writing.
     *
     * @return the uri to write to: the same, or that of the document created anew,
     *   which the open document, its tab and its session are moved to
     * @throws IOException if it cannot be created there
     */
    @Throws(IOException::class)
    private suspend fun recreateFile(document: OpenDocument): Uri {
        val uri = document.uri
        val name = displayName(document)
        val created: Uri? = withContext(Dispatchers.IO) {
            when (uri.scheme) {
                // writing creates it, once its folder is there
                ContentResolver.SCHEME_FILE -> {
                    uri.path?.let { File(it).parentFile?.mkdirs() }
                    uri
                }
                ContentResolver.SCHEME_CONTENT -> {
                    if (DocumentFile.isValid(this@EditorActivity, uri)) {
                        // created again meanwhile, by someone else: written over
                        uri
                    } else {
                        val type = FileTreeController.mimeTypeOf(name)
                        // opened from the file tree: the folder it was listed in
                        runCatching { document.document.getParent()?.createFile(type, name)?.getUri() }.getOrNull()
                            ?: createBesideDeleted(uri, type, name)
                    }
                }
                else -> null
            }
        }
        if (created == null) throw FileNotFoundException(getString(R.string.file_recreate_failed))
        if (created != uri && document in openDocuments) {
            // A new document, under another uri: the open one, its tab and the last
            // document shown follow it, as for a rename. Its session is written by the
            // save; the one of the deleted document is of no use any more.
            document.document = documentOf(created)
            editorTabs.rename(uri, created, name)
            if (document === current) {
                activityPreferences.edit()
                    .putString(Constants.PREF_CURRENT_OPENED_URI, created.toString())
    // storage name a document by its path ("primary:dir/file.txt").
            }
            editorService.deleteSession(uri)
        }
        return created
    }

    // A document named [name] of [type] in the folder that held the document of the tree
    // uri [deleted], if its folder can be told from its id: the providers of the shared
    // storage name a document by its path (\"primary:dir/file.txt\").
    @WorkerThread
    private fun createBesideDeleted(deleted: Uri, type: String, name: String): Uri? = try {
        if (!DocumentsContract.isTreeUri(deleted) || !DocumentsContract.isDocumentUri(this, deleted)) {
            null
        } else {
            val id = DocumentsContract.getDocumentId(deleted)
            val treeId = DocumentsContract.getTreeDocumentId(deleted)
            val parentId = when {
                id.contains('/') -> id.substringBeforeLast('/')
                id.endsWith(':').not() && id.contains(':') -> id.substringBefore(':') + ":"
                else -> null
            }
            // not above the folder that was granted
            if (parentId == null || !(parentId == treeId || parentId.startsWith(treeId.trimEnd('/') + "/") ||
                    (treeId.endsWith(':') && parentId.startsWith(treeId)))
            ) {
                null
            } else {
                val parent = DocumentsContract.buildDocumentUriUsingTree(deleted, parentId)
                DocumentsContract.createDocument(contentResolver, parent, type, name)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot create $name again beside $deleted", e)
        null
    }

    // #endregion

    // #region closing

    /**
     * The user closed the tabs of [uris]. Those with unsaved changes: whether to
     * save them first ("save", "don't save", "cancel"), asked once for all.
     */
    private fun closeTabs(uris: List<Uri>) {
        val closing = uris.mapNotNull { find(it) }
        // tabs without a document (cannot happen): just go
        editorTabs.remove(uris.filter { uri -> closing.none { it.uri == uri } })
        if (closing.isEmpty()) return

        val unsaved = closing.filter { it.isLoaded && !it.isDetached && it.isModified }
        if (unsaved.isEmpty()) {
            closeDocuments(closing, keepSessions = true)
            return
        }

        val message = if (unsaved.size == 1) {
            val name = editorTabs.tabName(unsaved[0].uri) ?: unsaved[0].uri.lastPathSegment.orEmpty()
            getString(R.string.tab_unsaved_message_one, name)
        } else {
            getString(R.string.tab_unsaved_message_many, unsaved.size)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tab_unsaved_title)
            .setMessage(message)
            .setPositiveButton(R.string.tab_unsaved_save) { _, _ -> saveAndClose(closing, unsaved) }
            .setNeutralButton(R.string.tab_unsaved_discard) { _, _ ->
                // those closed meanwhile (deleted in the file tree) are gone already
                closeDocuments(closing.filter { it in openDocuments }, keepSessions = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Writes [unsaved] back, then closes [closing]: but not one that could not be
    // written, which stays open with its changes (the error is shown).
    private fun saveAndClose(closing: List<OpenDocument>, unsaved: List<OpenDocument>) {
        val saves = unsaved.filter { it in openDocuments }.associateWith { launchSave(it) }
        saves.keys.forEach { it.isSaving = true }
        updateEditorState()
        lifecycleScope.launch {
            val failed = saves.filter { (_, save) -> !save.await() }.keys
            saves.keys.forEach { it.isSaving = false }
            closeDocuments(closing.filter { it in openDocuments && it !in failed }, keepSessions = true)
            updateEditorState()
        }
    }

    /**
     * Closes [closing] as they are, without writing them back: their editors and
     * their tabs go. The one shown hands over to the tab next to it, or to none.
     *
     * [keepSessions]: the session of each document that is loaded and unchanged is
     * stored, for when it is opened again (one closed with changes that were let go
     * keeps the session it had). false: they were deleted, and so are their sessions.
     */
    private fun closeDocuments(closing: List<OpenDocument>, keepSessions: Boolean) {
        if (closing.isEmpty()) return
        val shown = current
        val closesShown = shown != null && shown in closing
        val next = if (closesShown) {
            editorTabs.neighborOf(shown!!.uri, excluding = closing.map { it.uri })?.let { find(it) }
        } else {
            null
        }
        // the search and its matches belong to the editor that goes
        if (closesShown) hideSearchPanel()

        editorTabs.remove(closing.map { it.uri })
        closing.forEach { document ->
            openDocuments.remove(document)
            document.loadJob?.cancel()
            document.highlightJob?.cancel()
            document.diskCheckJob?.cancel()
            document.diskDialog?.dismiss()
            document.diskDialog = null

            val uri = document.uri
            val hash = document.contentHash
            val editor = document.editor
            editor?.let { lspController.onDocumentClosed(it) }
            val storeSession = keepSessions && document.isLoaded && !document.isDetached &&
                !document.isModified && hash != null
            document.editor = null
            if (editor != null) {
                editor.isActive = false
                binding.editorContainer.removeView(editor)
            }

            DocumentSaves.launchDetached {
                // a save of it that is running reads its editor: let it finish
                DocumentSaves.await(uri)
                try {
                    if (editor != null && storeSession && hash != null) {
                        editorService.saveSession(uri, editor, hash)
                    } else if (!keepSessions) {
                        editorService.deleteSession(uri)
                    }
                } finally {
                    editor?.let { releaseEditor(it) }
                }
            }
        }

        if (closesShown) {
            current = null
            if (next != null) showDocument(next) else showNothing()
        }
        // their rows in the file tree are not highlighted any more (showDocument only
        // knows of the one shown before, and that was set aside above)
        if (::fileTreeController.isInitialized) {
            closing.forEach { fileTreeController.notifyChanged(it.uri) }
        }
    }

    // Stores the session of every document that is loaded, see onStop.
    private fun saveSessions() {
        openDocuments
            .filter { it.isLoaded && !it.isDetached }
            .forEach { storeSession(it) }
    }

    // Stores the session of [document] (cursor, folds, undo history; unsaved edits on
    // its redo stack), after the writes of it that are running.
    private fun storeSession(document: OpenDocument) {
        val editor = document.editor ?: return
        val hash = document.contentHash ?: return
        val uri = document.uri
        DocumentSaves.launchDetached {
            // not in the middle of a save of it, which stores one itself
            DocumentSaves.await(uri)
            editorService.saveSession(uri, editor, hash)
        }
    }

    // #endregion

    // #region sharing

    /**
     * The file of [uri] (a tab's) to another app, chosen by the user. With unsaved changes
     * it is written back first, and shared as it is then; not if that fails (the error is
     * shown). A file of this app's own goes through its FileProvider, a document of another
     * app (the storage access framework) as it is: either can be read for the share only.
     */
    private fun shareDocument(uri: Uri) {
        val document = find(uri)
        lifecycleScope.launch {
            if (document != null && document.isLoaded && !document.isDetached && document.isModified) {
                document.isSaving = true
                updateEditorState()
                val saved = launchSave(document).await()
                document.isSaving = false
                if (document === current) updateEditorState()
                if (!saved) return@launch
            }
            val name = withContext(Dispatchers.IO) { runCatching { documentOf(uri).getName() }.getOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
            val shared = withContext(Dispatchers.IO) { shareableUri(uri) }
            if (shared == null) {
                Toast.makeText(this@EditorActivity, R.string.tab_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val type = withContext(Dispatchers.IO) { shareType(uri, name) }
            val send = Intent(Intent.ACTION_SEND).apply {
                setType(type)
                putExtra(Intent.EXTRA_STREAM, shared)
                putExtra(Intent.EXTRA_TITLE, name)
                // the receiver (and the chooser's preview) may read it, nothing more
                clipData = ClipData.newUri(contentResolver, name, shared)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, getString(R.string.tab_share_chooser_title, name)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@EditorActivity, R.string.tab_share_no_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // What another app can be given to read [uri]: a file through this app's FileProvider
    // (one it does not cover: null), a document of another app as it is.
    private fun shareableUri(uri: Uri): Uri? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.takeIf { it.isFile }?.let { file ->
            try {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "cannot share $file", e)
                null
            }
        }
        ContentResolver.SCHEME_CONTENT -> uri
        else -> null
    }

    // The type of the file [name] of [uri]: by its extension, else as its provider says;
    // a text (the files of this editor) when neither knows better.
    private fun shareType(uri: Uri, name: String): String {
        val byName = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val byProvider = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching { contentResolver.getType(uri) }.getOrNull()
        } else {
            null
        }
        return byName ?: byProvider?.takeIf { it != "application/octet-stream" } ?: "text/plain"
    }

    // #endregion

    // #region saving

    // The save action of the menu: the document shown.
    private fun saveCurrentDocument() {
        val document = current ?: return
        // nothing to write while the document itself is still being read
        if (!document.isLoaded || document.isDetached) return

        document.isSaving = true
        updateEditorState()
        val save = launchSave(document)
        lifecycleScope.launch {
            save.await()
            document.isSaving = false
            if (document === current) updateEditorState()
        }
    }

    /**
     * Writes the text in the editor of [document], which must be loaded, to its file,
     * and stores its editing session. Runs after the writes of it before, and is not
     * cancelled by anything, not even by the end of this screen (see DocumentSaves).
     * A failure is shown to the user; the session is stored anyway, with the unsaved
     * edits on its redo stack, so that they are not lost if the screen goes.
     *
     * @return the save, completing with whether it succeeded
     */
    private fun launchSave(document: OpenDocument): Deferred<Boolean> {
        val editor = document.editor ?: return CompletableDeferred(false)
        val uri = document.uri
        val buffer = editor.getTextBuffer()

        val save = DocumentSaves.launch(uri) {
            try {
                // Taken with the text that is written: an edit made while the file
                // is being written still counts as unsaved afterwards.
                val version = editor.getTextVersion()
                // deleted by another app, and kept: created again first
                val target = if (document.isMissingOnDisk) recreateFile(document) else uri
                val contentHash = editorService.writeDocument(target, buffer)
                // saved: its tab loses the dot, unless it was edited meanwhile
                editor.markSaved(version)
                lspController.onDocumentSaved(editor, document.document)
                onKeymapSaved(document.document)
                document.contentHash = contentHash
                // what is on the disk now is this text: not a change by another app
                document.diskHash = contentHash
                document.diskStamp = (withContext(Dispatchers.IO) {
                    DiskChanges.stateOf(this@EditorActivity, target)
                } as? DiskState.Present)?.stamp
                document.isMissingOnDisk = false
                // serialize the editor states
                editorService.saveSession(target, editor, contentHash)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // see loadDocument: no reason to crash, but the user must know
                Log.e(TAG, "failed to save $uri", e)
                document.contentHash?.let { hash ->
                    runCatching { editorService.saveSession(uri, editor, hash) }
                }
                if (!isDestroyed) showError(document.document, e)
                false
            }
        }
        document.saveJob = save
        return save
    }

    // A message that stays until it is dismissed, starting with the document name.
    // Missing permissions get a short toast instead, see showPermissionDenied.
    private suspend fun showError(document: DocumentFile, error: Throwable) {
        val name = withContext(Dispatchers.IO) {
            runCatching { document.getName() }.getOrDefault("")
        }.ifEmpty { document.getUri().lastPathSegment.orEmpty() }
        val message = error.localizedMessage ?: error.toString()
        // and in the console, with what it was
        ConsoleLog.append(ConsoleLog.APP, ConsoleLog.Level.ERROR, "$name: ${error.javaClass.simpleName}: $message")
        Snackbar.make(binding.contentMain, "$name: $message", Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(binding.extraKeysPanel)
            .setTextMaxLines(4)
            .setAction(R.string.action_close) { /* dismisses itself */ }
            .show()
    }

    // "Open failed, permission denied"; below /sdcard also asks for the permission.
    private fun showPermissionDenied(uri: Uri?) {
        Toast.makeText(this, R.string.file_open_permission_denied, Toast.LENGTH_SHORT).show()
        if (uri?.scheme == ContentResolver.SCHEME_FILE && !hasStorageAccess()) {
            requestStorageAccess()
        }
    }

    // #endregion

    // #endregion

    private companion object {
        // The items of the floating bar. None of them come from a menu file: the bar is
        // built in code, so it carries its own ids rather than borrowing the toolbar's.
        private const val ID_SHARE = 0x7f_0001
        private const val ID_SEARCH = 0x7f_0002
        private const val ID_CUT = 0x7f_0003
        private const val ID_COPY = 0x7f_0004
        private const val ID_PASTE = 0x7f_0005
        private const val ID_SELECT_ALL = 0x7f_0006
        // the first of the extra keys, and what it shows
        private const val ID_PROCESS_TEXT = 0x7f_1000
        // what the language server does at the cursor
        private const val ID_LSP_HOVER = 0x7f_0011
        private const val ID_LSP_DEFINITION = 0x7f_0012
        private const val ID_LSP_REFERENCES = 0x7f_0013
        private const val ID_LSP_RENAME = 0x7f_0014
        private const val ID_LSP_ACTIONS = 0x7f_0015
        private const val ID_LSP_FORMAT = 0x7f_0016
        private const val ID_LSP_FORMAT_SELECTION = 0x7f_0017
        private const val ID_LSP_OUTLINE = 0x7f_0018
        // the selection commented out or back in
        private const val ID_TOGGLE_COMMENT = 0x7f_0019
        // what is sent to other apps at most (an Intent carries about a megabyte)
        private const val MAX_SENT_CHARS = 100_000

        const val TAG = "EditorActivity"

        // a second back press within this time leaves the app
        const val BACK_PRESS_WINDOW_MS = 2000L

        // How strong the toolbar icons are: an action that cannot be taken (Material's
        // disabled opacity), and the edit mode icon while the document is read-only,
        // dimmed but clearly not disabled (it can be tapped to edit again).
        const val ICON_ALPHA_DISABLED = 0.38f
        const val ICON_ALPHA_READ_ONLY = 0.6f

        // how long to wait before asking the editor again while it is zooming or laying out
        const val EDITOR_BUSY_RETRY_DELAY_MS = 50L

        // how long after a file was found gone it is looked for again, before asking
        const val DISK_DELETE_RECHECK_MS = 500L
    }
}
