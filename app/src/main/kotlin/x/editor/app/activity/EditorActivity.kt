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

import android.app.UiModeManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.provider.DocumentsContract
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.os.Environment
import android.os.Process
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.Magnifier
import android.Manifest
import android.icu.text.SimpleDateFormat
import android.content.pm.PackageManager
import android.provider.Settings
import android.window.OnBackInvokedDispatcher

import android.app.Activity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.ui.AppBarConfiguration
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import x.editor.app.R
import x.editor.app.databinding.ActivityEditorBinding
import x.editor.app.model.BaseAdapter
import x.editor.app.model.BaseListAdapter
import x.editor.app.model.DatabaseManager
import x.editor.app.model.EntityDao
import x.editor.app.model.HeaderEntity
import x.editor.app.model.DownloadManager
import x.editor.app.model.DownloadState
import x.editor.app.model.ImeAnimationInsetsHandler
import x.editor.app.model.Span
import x.editor.app.model.SimpleItemCallback
import x.editor.app.model.TreeSitter
import x.editor.app.util.AppUtils
import x.editor.app.util.DeviceUtils
import x.editor.app.util.FileUtils
import x.editor.app.util.JsonUtils
import x.editor.app.util.PackageUtils
import x.editor.app.view.HighlightTextView
import x.editor.app.view.ContentTranslatingDrawerLayout
import x.editor.app.model.XDividerItemDecoration

import x.github.module.alerter.Alerter
import x.github.module.crash.CrashHandler
import x.github.module.document.DocumentFile
import x.github.module.editor.SavedState
import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.Strings
import x.github.module.treesitter.*

import kotlin.system.measureTimeMillis

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.cbor.*

import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.lang.SecurityException
import java.io.InputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.charset.Charset
import java.lang.Runtime
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.regex.PatternSyntaxException
import java.security.MessageDigest

import x.editor.app.model.BaseArrayAdapter

class EditorActivity : BaseActivity() {

    private lateinit var binding: ActivityEditorBinding
    
    private lateinit var service: EditorService
    
    private lateinit var openedFile: DocumentFile
    
    private val uriFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_READ_URI_PERMISSION
    
    // save the all opened uri
    private val openedUris by lazy { mutableListOf<Uri>() }
    
    private val openedJobs by lazy { mutableMapOf<Uri, Job>() }
      
    private val behavior by lazy {
        BottomSheetBehavior.from(binding.bottomSheetLayout.bottomSheet)
    }
    
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
         
    // logging tag
    private val LOG_TAG = this::class.simpleName
    
    override fun onCreate(savedInstanceState: Bundle?) {
        
        super.onCreate(savedInstanceState)               
        // (intent.getFlags() and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0
        if (!this.isTaskRoot() && intent != null) {
            if (
                intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                Intent.ACTION_MAIN == intent.action
            ) {
                this.finish()
                return@onCreate
            }
        }
              
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
                
        setSupportActionBar(binding.toolbar)
        onBackPressedDispatcher.addCallback(this, backCallback)
        flowSubscribers()
        
        val toggle = ActionBarDrawerToggle(
            this@EditorActivity,
            binding.drawerLayout,
            binding.toolbar,
            R.string.app_name,
            R.string.app_name
        )
        
        binding.drawerLayout.apply {
            addDrawerListener(toggle)
            toggle.syncState() 
            childId = binding.contentMain.id
            translationBehaviorStart = ContentTranslatingDrawerLayout.TranslationBehavior.FULL
            translationBehaviorEnd = ContentTranslatingDrawerLayout.TranslationBehavior.FULL
            setScrimColor(Color.TRANSPARENT)                 
        }

        binding.editor.apply {            
            //setTypeface(resources.getFont(R.font.jetbrains_mono_regular))            
            //post { setWordwrap(true) }            
            if (uiModeManager.getNightMode() == UiModeManager.MODE_NIGHT_YES) {
                setPaintColor(Color.LTGRAY)
            } else if (uiModeManager.getNightMode() == UiModeManager.MODE_NIGHT_NO) {
                setPaintColor(Color.DKGRAY)
            } else {
                setPaintColor(Color.GRAY)
            }
            setActionCallback(actionModeCallback)
            setHapticFeedbackEnabled(false)
        }
               
        addExtraKeys(
            mutableListOf("+", "-", "*", "/", "=", "<", ">", "|", "(", ")", "{", "}", "$")
        )
        
        binding.extraKeysPanel.post {
            // set the bottom margin for shortcut key
            binding.editor.setMargin(
                bottomMargin = binding.extraKeysPanel.getHeight()
            )
        }
        
        val imeInsetsHandler = ImeAnimationInsetsHandler(
            drawerLayout = binding.drawerLayout,
            extraKeysPanel = binding.extraKeysPanel
        )
        imeInsetsHandler.attach(binding.root)
        
        bindService(
            Intent(this, EditorService::class.java), 
            serviceConnection, 
            Context.BIND_AUTO_CREATE
        )
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {            
            service = (binder as EditorService.ServiceBinder).getService()
            // initialize components
            loadTreeSitter()
            createFileTree()       
            createSearchView()
            if(intent.action == Intent.ACTION_VIEW) {
                intent.data?.let { openExternalUri(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // TODO
        }
    }
    
    private val backCallback = object: OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            doBackPressed()
        }
    }
    
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val lastIndex = openedUris.size - 1        
        for(i in 0 until uris.size) {            
            if (!openedUris.contains(uris[i])) {
                openedUris.add(uris[i])
                // see the AOSP framework/**/UriGrantsManagerService.java
                // android 11 and above the value of MAX_PERSISTED_URI_GRANTS is 512
                try {
                    contentResolver.takePersistableUriPermission(uris[i], uriFlags)
                } catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }            
        }    
        
        // already add some uris                                          
        if(lastIndex != openedUris.lastIndex) {           
            DocumentFile.fromSingleUri(this, uris.first())?.let {
                binding.fileListPanel.scrollToPosition(lastIndex + 1)               
                binding.fileListPanel.adapter?.let {
                    if(::openedFile.isInitialized) {
                        // refresh the previously opened file position
                        it.notifyItemChanged(openedUris.indexOf(openedFile.getUri()))
                    }
                }
                // open the document
                this@EditorActivity.openFile(it)
            }          
            binding.tvNoFiles.setVisibility(View.GONE)
            binding.fileListPanel.setVisibility(View.VISIBLE)
            
            binding.fileListPanel.adapter?.let {
                it.notifyItemRangeChanged(lastIndex + 1, it.getItemCount())            
            }
                    
            with(binding.drawerLayout) {
                if(isDrawerOpen(GravityCompat.START)) {
                    closeDrawer(GravityCompat.START)
                }
            }                                      
        }
    }
    
    private val actionModeCallback = object: ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.action_mode, menu)
            return true
        }
        
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {            
            return true
        }
        
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.getItemId()) {
                R.id.action_search -> showSearchView()
                R.id.action_copy -> binding.editor.copy()
                R.id.action_cut -> binding.editor.cut()
                R.id.action_paste -> binding.editor.paste()
                R.id.action_select_all -> binding.editor.selectAll()
            }
            mode.finish()
            return true
        }
        
        override fun onDestroyActionMode(mode: ActionMode) {
            // TODO
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            // set the action mode location
            with(binding.editor) {
                outRect.set(cx - scrollX, cy - scrollY, cx - scrollX, cy - scrollY)
            }
        }
    }
    
    fun doBackPressed() {
        backCallback.isEnabled = false
        handler.postDelayed({
            backCallback.isEnabled = true
        }, 2000)
    }
    
    override fun onRestart() {
        super.onRestart()
        handler.removeCallbacksAndMessages(null)
        backCallback.isEnabled = true
    }
    
    fun loadTreeSitter() = lifecycleScope.launch {
        // load the libraries of tree-sitter and configure
        service.loadTreeSitter("dark", binding.editor.treeSitter)
        var alerter: Alerter? = null
        var database: EntityDao? = null
        
        val downloadLinks = service.getDownloadLinks()        
        if (!downloadLinks.isEmpty()) {
            alerter = createAlerter(
                contentText = downloadLinks.keys.first()
            )
            alerter?.setTextAppearance(
                AppUtils.resolveAttr(
                    this@EditorActivity,
                    com.google.android.material.R.attr.textAppearanceBodyMedium
                )
            )
            alerter?.setIcon(R.drawable.ic_download)
            alerter?.show()
            database = DatabaseManager.getInstance(this@EditorActivity).entityDao()
        }
        
        downloadLinks.forEach {
            lifecycleScope.launch(Dispatchers.IO) {
                service.downloadFile(
                    it.key,
                    it.value.first,
                    progressCallback = { progress ->
                        alerter?.setText("${it.key}...(${progress}%)")
                    },
                    finishCallback = { file, etag ->
                        alerter?.setIcon(R.drawable.ic_download_done)
                        // extract file
                        FileUtils.unzipFile(file, it.value.second)                        
                        val headerEntity = database?.query(it.key) ?: null
                        if (headerEntity != null) {
                            // delete old HeaderEntity for current url
                            database?.delete(headerEntity)
                        }
                        // add new HeaderEntity
                        database?.add(HeaderEntity(it.key, etag))
                        // delete the cache zip file
                        file.delete()
                        // load the libraries of tree-sitter and configure
                        lifecycleScope.launch {
                            service.loadTreeSitter("dark", binding.editor.treeSitter)
                            delay(3000L)
                            alerter?.dismiss()
                        }                
                    },
                    errorCallback = { emsg ->
                        alerter?.setIcon(R.drawable.ic_error_outline)
                        alerter?.setText(emsg)                       
                    }
                )
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // requires to set the new intent
        setIntent(intent)
        // receive the intent from external app
        if(intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { openExternalUri(it) }
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val uiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        //
        when (uiMode) {
            Configuration.UI_MODE_NIGHT_NO -> {
                // Night mode is not active, we're using the light theme
                Log.i(LOG_TAG, "light theme")
            }
            Configuration.UI_MODE_NIGHT_YES -> {
                // Night mode is active, we're using dark theme
                Log.i(LOG_TAG, "night theme")
            }
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        //outState.putInt("theme", myTheme)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        //myTheme = savedInstanceState.getInt("theme")
    }
    
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        // show menu icons
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // set undo option menu icon
        menu.findItem(R.id.action_undo).apply {
            setEnabled(
                viewModel.canUndo.value &&
                !binding.progressBar.isShown()
            )
        }

        // set redo option menu icon
        menu.findItem(R.id.action_redo).apply {
            setEnabled(
                viewModel.canRedo.value &&
                !binding.progressBar.isShown()
            )
        }

        // set edit mode option menu icon
        menu.findItem(R.id.action_edit_mode).apply {           
            when (binding.editor.isEditable()) {
                true -> setIcon(R.drawable.ic_read_write)
                else -> setIcon(R.drawable.ic_read_only)
            }
            setEnabled(!binding.progressBar.isShown())               
        }

        // set save option menu icon state
        menu.findItem(R.id.action_save_file).apply { 
            setEnabled(
                viewModel.isTextChanged.value &&
                !binding.progressBar.isShown()
            ) 
        }        
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo -> { binding.editor.undo() }
            R.id.action_redo -> { binding.editor.redo() }
            R.id.action_edit_mode -> {            
                with(binding.editor) {                    
                    setEditable(!isEditable())
                }
                invalidateOptionsMenu()
            }
            R.id.action_open_file -> {
                openDocumentLauncher.launch(
                    arrayOf("text/*", "application/*")
                )
            }
            R.id.action_search -> {               
                CrashHandler.testNativeCrash()
                showSearchView()
            }
            R.id.action_save_file -> {
                if(::openedFile.isInitialized) {
                    saveFile(openedFile)
                }                
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))           
            }
        }

        return true
    }
    
    fun createFileTree() {
        val decimalFormat = DecimalFormat("#.##")
        val dateFormat = SimpleDateFormat("yyyy/MM/dd")
        
        val defaultTextColor = getCompatColor(
            android.R.attr.textColorPrimary
        )
        val highlightTextColor = getCompatColor(            
            androidx.appcompat.R.attr.colorPrimary
        )       
        
        val selectBackground = getCompatColor(
            com.google.android.material.R.attr.colorSecondaryContainer
        )
        
        val defaultBackground = getCompatColor(
            com.google.android.material.R.attr.colorOnSurfaceInverse
        )
        
        val deleteBackground = getCompatColor(
            androidx.appcompat.R.attr.colorError
        )
        
        val itemBackground = ColorDrawable(deleteBackground)
          
        val iconBackground = getCompatColor(
            com.google.android.material.R.attr.colorErrorContainer
        ) 
        
        val deleteIcon = getCompatDrawable(
            R.drawable.ic_close
        )!!.apply {
            setTint(iconBackground)
        }       
                
        val effect = VibrationEffect.createPredefined(
            VibrationEffect.EFFECT_TICK
        )
        
        // add all the persisted uris
        contentResolver.getPersistedUriPermissions().forEach {
            if(DocumentFile.isValid(this, it.getUri()))
                openedUris.add(it.getUri())
            else
                contentResolver.releasePersistableUriPermission(it.getUri(), uriFlags)
        }
        
        if(!openedUris.isEmpty()) {
            val uriString = sharedPreference.getString("current_opened_uri", null)           
            var uri = openedUris.first()             
            try {
                // check if the uri exists and access permission
                if(uriString != null && DocumentFile.isValid(this, Uri.parse(uriString))) {
                    uri = Uri.parse(uriString)                     
                }                   
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
                       
            DocumentFile.fromSingleUri(this, uri)?.let { 
                openFile(it)
            }
            
            binding.tvNoFiles.setVisibility(View.GONE)
            binding.fileListPanel.setVisibility(View.VISIBLE)            
        }
                      
        val baseAdapter = BaseAdapter<Uri>(openedUris, R.layout.simple_item_list_file)
        
        val simpleCallback = SimpleItemCallback(0, ItemTouchHelper.RIGHT).apply {            
            swipeCallback = { position ->
                contentResolver.releasePersistableUriPermission(openedUris[position], uriFlags)
                openedUris.removeAt(position)
                        
                baseAdapter.notifyItemRemoved(position)
                baseAdapter.notifyItemRangeChanged(
                    position, 
                    baseAdapter.getItemCount() - position
                )
                
                if(openedUris.isEmpty()) {
                    binding.tvNoFiles.setVisibility(View.VISIBLE)
                    binding.fileListPanel.setVisibility(View.GONE)
                }
            }
            
            drawCallback = { canvas, holder, dx ->                               
                itemBackground.setBounds(
                    holder.itemView.getLeft(),
                    holder.itemView.getTop(),
                    dx.toInt(),
                    holder.itemView.getBottom(),           
                )
                itemBackground.draw(canvas)
                
                val iconMargin = (holder.itemView.height - deleteIcon.intrinsicHeight) / 2
                val factor = dx / (holder.itemView.width / 2)
                val deltaX = Math.min(iconMargin, (factor * iconMargin).toInt())                
                deleteIcon.setBounds(
                    holder.itemView.getLeft() + deltaX,
                    holder.itemView.getTop() + iconMargin,                  
                    holder.itemView.getLeft() + deltaX + deleteIcon.intrinsicWidth,
                    holder.itemView.getTop() + iconMargin + deleteIcon.intrinsicHeight           
                )            
                deleteIcon.draw(canvas)                         
            }
            
            stateCallback = { vibrator.vibrate(effect) }
            
            clearCallback = { vibrator.vibrate(effect) }
        }
        
        baseAdapter.onBindView = { holder, uri ->                
            DocumentFile.fromSingleUri(this@EditorActivity, uri)?.let {             
                with(holder.getView<TextView>(R.id.tv_name)) {                                    
                    // set the document file name
                    setText(it.getName())                    
                    // check the current opened uri
                    if (it.getUri() == openedFile.getUri()) {                       
                        setTextColor(highlightTextColor)
                        holder.itemView.setBackgroundColor(selectBackground)
                    } else {
                        setTextColor(defaultTextColor)                                                                         
                        holder.itemView.setBackgroundColor(defaultBackground)                        
                    }
                }
                
                with(holder.getView<TextView>(R.id.tv_modified_time)) {                
                    setText(dateFormat.format(it.lastModified()))
                }
                
                with(holder.getView<TextView>(R.id.tv_size)) {                
                    setText(FileUtils.formatSize(decimalFormat, it.length()))
                }
                
                with(holder.getView<TextView>(R.id.tv_type)) {                
                    setText(it.getType())                    
                }
            }
        }
        
        baseAdapter.onItemClick = { holder, uri ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            // skip the same file
            if(uri != openedFile.getUri()) {                
                DocumentFile.fromSingleUri(this@EditorActivity, uri)?.let {
                    // refresh the previously opened file position
                    notifyItemChanged(openedUris.indexOf(openedFile.getUri()))
                    // open the current document
                    openFile(it)
                    // refresh the currently opened file position                
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
            }           
        }
        
        // recycler view for file list
        binding.fileListPanel.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity)
            // recyclerview adapter
            adapter = baseAdapter
            addItemDecoration(XDividerItemDecoration(this@EditorActivity))  
            setItemAnimator(DefaultItemAnimator())
            ItemTouchHelper(simpleCallback).attachToRecyclerView(this)           
        }        
    }
    
    fun addExtraKeys(keys: MutableList<String>) {
        val effect = VibrationEffect.createPredefined(
            VibrationEffect.EFFECT_CLICK
        )
        val baseListAdapter = BaseListAdapter<String>(
            layoutId = R.layout.simple_item_list_shortcut,
            idSelector = { it },
            contentComparator = { oldItem, newItem ->
                oldItem == newItem
            }
        )
        
        baseListAdapter.onBindView = { holder, content ->
            with(holder.getView<TextView>(R.id.tv_name)) {
                setText(content)
            }
        }
        
        baseListAdapter.onItemClick = { _, content ->            
            vibrator.vibrate(effect)
            binding.editor.insert(content)
        }
        
        baseListAdapter.onItemLongClick = { _, content ->
            binding.editor.insert(content)
        }
        
        // recycler view for extra keys
        binding.extraKeysPanel.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity).apply {
                setOrientation(LinearLayoutManager.HORIZONTAL)
            }
            // recyclerview adapter
            adapter = baseListAdapter.apply { submitList(keys) }
        }
    }

    // stateFlow subscriber
    fun flowSubscribers() {
        lifecycleScope.launch {
            viewModel.canUndo.collect {
                // when undo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }

        lifecycleScope.launch {
            viewModel.canRedo.collect {
                // when redo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }
        
        lifecycleScope.launch {
            viewModel.isTextChanged.collect {
                // when text changed refresh the UI
                invalidateOptionsMenu()
            }
        }
        
        lifecycleScope.launch {           
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            viewModel.textSharedFlow.debounce(300L).collectLatest {
                if(behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    binding.bottomSheetLayout.tvSearchBox.getText().toString().also {
                        if (!it.isEmpty()) {
                            // re-perform query
                            viewModel.performSearch(it)
                        }
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            viewModel.isTextScaled.collect { value ->
                // when text scaled refresh the UI               
                val state = if(value) View.GONE else View.VISIBLE                
                binding.editor.setEditable(value)
                binding.progressBar.setVisibility(state)
                invalidateOptionsMenu()
            }
        }
    }
    
    // received intent content uri from external app
    fun openExternalUri(uri: Uri) {
        if(!TextUtils.isEmpty(uri.scheme)) {
            if(uri.scheme == "content" && !openedUris.contains(uri)) {                                
                try {
                    DocumentFile.fromSingleUri(this, uri)?.let {
                        openFile(it)
                    }
                    // persistable the uri access permission
                    contentResolver.takePersistableUriPermission(uri, uriFlags)
                } catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    private enum class MatchMode { REGEX, WORD }
    
    private val textWatcher = object: TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // TODO
        }
                
        override fun afterTextChanged(s: Editable) {
            viewModel.performSearch(s.toString())            
        }
    
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            // TODO
        }
    }
    
    private val bottomSheetCallback = object: BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            when(newState) {
                BottomSheetBehavior.STATE_EXPANDED -> {
                    binding.editor.setMargin(
                        bottomMargin = bottomSheet.getHeight()
                    )
                }
                BottomSheetBehavior.STATE_HIDDEN -> {                  
                    with(binding.bottomSheetLayout.tvReplace) {               
                        if(visibility != View.GONE) {
                            visibility = View.GONE
                        }
                    }
            
                    with(binding.bottomSheetLayout.tvReplaceBox) {
                        if(visibility != View.GONE) {
                            visibility = View.GONE
                        }
                    }
                    
                    with(binding.bottomSheetLayout.dropDownReplace) {
                        if(visibility != View.GONE) {
                            visibility = View.GONE
                        }
                    }
                    
                    with(binding.bottomSheetLayout.buttonReplaceAll) {
                        if(isEnabled) {
                            setEnabled(false)
                        }      
                    }
                    
                    binding.editor.setMargin(
                        bottomMargin = binding.extraKeysPanel.getHeight()
                    )
                    
                    binding.editor.setMatchResult(null)
                    binding.editor.getSearchResult().clear()
                }
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            // TODO
        }
    }
    
    @WorkerThread
    private suspend fun performQuery(
        queryText: String, 
        matchMode: MatchMode,
        regexOptions: Set<RegexOption>,
        searchAdapter: ArrayAdapter<String>? = null
    ) {
        try {    
            val results = if (queryText.length > 0)                
                if (matchMode == MatchMode.REGEX)
                    binding.editor.find(Regex(queryText, regexOptions))
                else
                    binding.editor.find(queryText)
            else null
            // update the UI states
            withContext(Dispatchers.Main) {
                if(results != null && searchAdapter != null) {                    
                    if (searchAdapter.getPosition(queryText) < 0) {
                        searchAdapter.add(queryText)
                        searchAdapter.setNotifyOnChange(true)
                    }
                }         
                binding.editor.setSearchResult(results)
                binding.bottomSheetLayout.tvSearchResult.setText(
                    "${getString(R.string.search_result_count)}: ${results?.size ?: 0}"
                )
            }            
        } catch (e: PatternSyntaxException) {
            withContext(Dispatchers.Main) {
                
            }
        } catch (e: CancellationException) {
            e.printStackTrace()
        }
    }
        
    fun showSearchView() {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.peekHeight = binding.bottomSheetLayout.bottomSheet.getHeight()
        
        var queryText = binding.editor.getSelectedText()
        if(queryText.isEmpty()) {
            queryText = binding.bottomSheetLayout.tvSearchBox.getText().toString()
        }
        
        if (!queryText.isEmpty()) {
            with(binding.bottomSheetLayout.tvSearchBox) {                
                setText(queryText)                
                selectAll()
                requestLayout()
                requestFocus()         
            }
        }
    }
   
    fun dismissSearchView() {
        behavior.peekHeight = 0
        behavior.state = BottomSheetBehavior.STATE_HIDDEN
    }
    
    fun ListPopupWindow.getItemHeight(adapter: ArrayAdapter<*>): Int {       
        val itemView = adapter.getView(0, null, FrameLayout(this@EditorActivity))

        itemView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        return itemView.measuredHeight
    }
          
    fun createPopupWindow(
        anchorView: View,
        editView: EditText,
        visibleItemLimit: Int
    ): Pair<ListPopupWindow, ArrayAdapter<String>> {        
        val colorControlNormal = getCompatColor(
            android.R.attr.colorControlNormal
        )
        
        val colorBackground = getCompatColor(
            com.google.android.material.R.attr.colorSurfaceContainer
        )
        
        val popupBackground = getCompatDrawable(
            R.drawable.popup_window_background
        )?.apply {
            setTint(colorBackground)
        }
        
        val closeDrawable = getCompatDrawable(
            R.drawable.ic_close
        )?.apply {
            setTint(colorControlNormal)
        }
        
        val popupWindow = ListPopupWindow(this)
        val baseAdapter = BaseArrayAdapter<String>(
            this@EditorActivity, 
            R.layout.simple_item_list_dropdown
        ) { position, holder ->
            with(holder.getView<TextView>(R.id.text_view)) {                
                setText(getItem(position))                    
            }
            
            holder.itemView.setOnClickListener {
                with(editView) {
                    if (getText().toString() != getItem(position)) {
                        setText(getItem(position))
                        selectAll()
                        requestLayout()
                        requestFocus()                                            
                    }                    
                }
                popupWindow.dismiss()                
            }
            
            with(holder.getView<ImageView>(R.id.image_view)) {
                setOnClickListener {
                    remove(getItem(position))
                    if (getCount() == 0) {
                        popupWindow.dismiss()
                    } else if (getCount() <= visibleItemLimit) {
                        popupWindow.setHeight(
                            ListPopupWindow.WRAP_CONTENT
                        )
                    }
                    setNotifyOnChange(true)                
                }
            }
        }
        
        with(popupWindow) {
            setAnchorView(anchorView)
            setModal(true)  
            setAdapter(baseAdapter)
                
            setHorizontalOffset(-editView.getWidth())
            setWidth(editView.getWidth())            
            setHeight(ListPopupWindow.WRAP_CONTENT)
                        
            setBackgroundDrawable(popupBackground)
            setPromptPosition(ListPopupWindow.POSITION_PROMPT_ABOVE)           
            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED)                                 
        }
        
        return Pair(popupWindow, baseAdapter)
    }
    
    fun createSearchView() {
        val defaultTextColor = getCompatColor(
            android.R.attr.textColorSecondary
        )
        
        val highlightTextColor = getCompatColor(            
            androidx.appcompat.R.attr.colorError
        )
        
        // visible item count limit
        val visibleItemLimit: Int = 7
        // init search and replace drop down menu
        val (searchPopupWindow, searchAdapter) = createPopupWindow(
            binding.bottomSheetLayout.dropDownSearch,
            binding.bottomSheetLayout.tvSearchBox,
            visibleItemLimit
        )
        // only for get item height
        val fakeItem = if (searchAdapter.getCount() == 0) {
            searchAdapter.add(getString(R.string.search_box_prompt))
            searchAdapter.getItem(0)
        } else { null }
              
        val itemHeight = searchPopupWindow.getItemHeight(searchAdapter)        
        fakeItem?.let { searchAdapter.remove(fakeItem) }
        
        var replacePopupWindow: ListPopupWindow? = null
        var replaceAdapter: ArrayAdapter<String>? = null
        
        // search drop down menu click callback
        with(binding.bottomSheetLayout.dropDownSearch) {
            setOnClickListener {
                if (searchAdapter.getCount() >= visibleItemLimit)
                    searchPopupWindow.setHeight(itemHeight * visibleItemLimit)
                
                if (
                    !searchPopupWindow.isShowing() &&
                    searchAdapter.getCount() > 0
                )
                    searchPopupWindow.show()
                else
                    searchPopupWindow.dismiss()
            }
        }
        
        // replace drop down menu click callback 
        with(binding.bottomSheetLayout.dropDownReplace) {
            setOnClickListener {
                if (searchAdapter.getCount() >= visibleItemLimit)
                    searchPopupWindow.setHeight(itemHeight * visibleItemLimit)
                    
                if (
                    !replacePopupWindow!!.isShowing() &&
                    replaceAdapter!!.getCount() > 0
                )         
                    replacePopupWindow!!.show()
                else
                    replacePopupWindow!!.dismiss()
            }
        }
        
        // search shared flow
        val regexOptions = mutableSetOf<RegexOption>()
        var matchMode = MatchMode.REGEX        
        lifecycleScope.launch(Dispatchers.Default) {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            viewModel.searchSharedFlow                              
                .debounce(300L) // add a debounce to wait for user to stop typing
                .collectLatest {
                    if (it.any { it == '\n' || it == '\r' })
                        regexOptions.add(RegexOption.MULTILINE)
                    else
                        regexOptions.remove(RegexOption.MULTILINE)
                    performQuery(it, matchMode, regexOptions, searchAdapter) 
                }
        }
        
        behavior.isDraggable = false
        behavior.addBottomSheetCallback(bottomSheetCallback)
        
        binding.bottomSheetLayout.tvSearchResult.setText(
            "${getString(R.string.search_result_count)}: 0"
        )
        
        with(binding.bottomSheetLayout.tvSearchBox) {           
            addTextChangedListener(textWatcher)               
        }
        
        with(binding.bottomSheetLayout.scrollViewSearch) {
            isNestedScrollingEnabled = true
            setMaxHeight(
                binding.bottomSheetLayout.tvSearchBox.getHeight() * 2
            )         
        }
                
        with(binding.bottomSheetLayout.scrollViewReplace) {
            isNestedScrollingEnabled = true
            setMaxHeight(
                binding.bottomSheetLayout.tvSearchBox.getHeight() * 2
            )
        }
        
        // option ignore case
        with(binding.bottomSheetLayout.buttonIgnoreCase) {
            setTextColor(highlightTextColor)            
            setOnClickListener {               
                if (regexOptions.contains(RegexOption.IGNORE_CASE)) {
                    regexOptions.remove(RegexOption.IGNORE_CASE)                       
                    setTextColor(highlightTextColor)                        
                } else {
                    regexOptions.add(RegexOption.IGNORE_CASE)
                    setTextColor(defaultTextColor)
                }
                // re-perform query
                viewModel.performSearch(
                    binding.bottomSheetLayout.tvSearchBox.getText().toString()
                )
            }
        }
        
        // option match word
        with(binding.bottomSheetLayout.buttonMatchWord) {
            setOnClickListener {
                if (matchMode == MatchMode.REGEX) {
                    matchMode = MatchMode.WORD
                    setTextColor(highlightTextColor)                                   
                    binding.bottomSheetLayout.buttonMatchRegex.setTextColor(defaultTextColor)
                    // re-perform query
                    viewModel.performSearch(
                        binding.bottomSheetLayout.tvSearchBox.getText().toString()
                    )
                }
            }
        }
        
        // option regex expression
        with(binding.bottomSheetLayout.buttonMatchRegex) {
            setTextColor(highlightTextColor)
            setOnClickListener {
                if (matchMode == MatchMode.WORD) {
                    matchMode = MatchMode.REGEX
                    setTextColor(highlightTextColor)
                    binding.bottomSheetLayout.buttonMatchWord.setTextColor(defaultTextColor)
                    // re-perform query
                    viewModel.performSearch(
                        binding.bottomSheetLayout.tvSearchBox.getText().toString()
                    )
                }
            }
        }        
        
        // previous button
        binding.bottomSheetLayout.buttonPrev.setOnClickListener {
            val searchResult = binding.editor.getSearchResult()
            if (!searchResult.isEmpty()) {
                val matchResult = binding.editor.getMatchResult()
                val resultIndex = searchResult.binarySearch(matchResult)
                val prevIndex = if (resultIndex >= 0)
                    if (resultIndex - 1 < 0) searchResult.lastIndex else resultIndex - 1
                else
                    if (-(resultIndex + 1) <= 0) searchResult.lastIndex else -(resultIndex + 1) - 1
                binding.editor.setMatchResult(searchResult[prevIndex])
                binding.bottomSheetLayout.tvSearchResult.setText(
                    "${getString(R.string.search_result_count)}: ${prevIndex + 1}/${searchResult.size}"
                )
            }
        }
        
        // next button
        binding.bottomSheetLayout.buttonNext.setOnClickListener {
            val searchResult = binding.editor.getSearchResult()
            if (!searchResult.isEmpty()) {
                val matchResult = binding.editor.getMatchResult()
                val resultIndex = searchResult.binarySearch(matchResult)
                val nextIndex = if (resultIndex >= 0)
                    if (resultIndex + 1 > searchResult.lastIndex) 0 else resultIndex + 1
                else
                    if (-(resultIndex + 1) > searchResult.lastIndex) 0 else -(resultIndex + 1)
                binding.editor.setMatchResult(searchResult[nextIndex])
                binding.bottomSheetLayout.tvSearchResult.setText(
                    "${getString(R.string.search_result_count)}: ${nextIndex + 1}/${searchResult.size}"
                )
            }
        }
        
        // replace button
        binding.bottomSheetLayout.buttonReplace.setOnClickListener {
            // ...
            with(binding.bottomSheetLayout.tvReplace) {
                if(visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                }
            }
            
            with(binding.bottomSheetLayout.dropDownReplace) {
                if(visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                }
            }
            
            with(binding.bottomSheetLayout.buttonReplaceAll) {
                if(!isEnabled) {
                    setEnabled(true)
                }      
            }
            
            // replace box
            with(binding.bottomSheetLayout.tvReplaceBox) {
                if(visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                    post {
                        behavior.peekHeight = binding.bottomSheetLayout.bottomSheet.getHeight()
                        behavior.state = BottomSheetBehavior.STATE_EXPANDED
                        binding.editor.setMargin(
                            bottomMargin = binding.bottomSheetLayout.bottomSheet.getHeight()
                        )
                        
                        createPopupWindow(
                            binding.bottomSheetLayout.dropDownReplace,
                            binding.bottomSheetLayout.tvReplaceBox,
                            visibleItemLimit
                        ).also {
                            replacePopupWindow = it.first
                            replaceAdapter = it.second
                            replaceAdapter!!.add(getString(R.string.button_replace))
                        }
                    }
                } else {
                    val searchResult = binding.editor.getSearchResult()                    
                    if (!searchResult.isEmpty()) {                        
                        val matchResult = binding.editor.getMatchResult()
                        val resultIndex = searchResult.binarySearch(matchResult)
                        if (resultIndex >= 0) {
                            val replaceText = binding.bottomSheetLayout.tvReplaceBox.getText().toString()
                            if (
                                replaceText.length > 0 && 
                                replaceAdapter!!.getPosition(replaceText) < 0
                            ) {
                                replaceAdapter!!.add(replaceText)
                                replaceAdapter!!.setNotifyOnChange(true)
                            }
                                            
                            binding.editor.replace(replaceText, searchResult.removeAt(resultIndex))                        
                            binding.bottomSheetLayout.tvSearchResult.setText(
                                "${getString(R.string.search_result_count)}: ${searchResult.size}"
                            )
                        }
                    }
                }
            }          
        }
        
        // replace all button
        binding.bottomSheetLayout.buttonReplaceAll.setOnClickListener {
            val searchResult = binding.editor.getSearchResult()
            if (!searchResult.isEmpty()) {
                val replaceText = binding.bottomSheetLayout.tvReplaceBox.getText().toString()                
                if (
                    replaceText.length > 0 && 
                    replaceAdapter!!.getPosition(replaceText) < 0
                ) {
                    replaceAdapter!!.add(replaceText)
                    replaceAdapter!!.setNotifyOnChange(true)
                }
                                
                binding.editor.replaceAll(replaceText, searchResult)
                binding.editor.setMatchResult(null)
                searchResult.clear()
                binding.bottomSheetLayout.tvSearchResult.setText(
                    "${getString(R.string.search_result_count)}: ${searchResult.size}"
                )
            }
        }
        
        // close button
        binding.bottomSheetLayout.buttonClose.setOnClickListener {
            if (searchPopupWindow.isShowing())
                searchPopupWindow.dismiss()
            else if (
                 replacePopupWindow != null && 
                 replacePopupWindow.isShowing()
            )
                replacePopupWindow.dismiss()
            else
                dismissSearchView()
        }
    }
    
    @WorkerThread
    suspend fun showFileDifferDialog(title: String, message: String): Boolean {
        val deferredResult = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            createDialog(
                dialogTitle = title,
                dialogMessage = message,
                negativeButtonText = getString(android.R.string.cancel),
                positiveButtonText = getString(android.R.string.ok),
                onNegativeCallback = {
                    deferredResult.complete(false)
                },
                onPositiveCallback = {
                    deferredResult.complete(true)
                },                
                cancelable = false
            ).show()
        }
        return deferredResult.await()
    }
    
    @WorkerThread    
    suspend fun CoroutineScope.loadSerializeFile(
        document: DocumentFile
    ): SavedState? {
        val uri = document.getUri()
        val serializeFile = service.getSerializeFile(uri)
        if (serializeFile.exists()) {           
            val stateDeferred = async { service.deserialize(uri) }            
            val savedState = stateDeferred.await()           
            // restore the editor saved states
            savedState?.let { 
                binding.editor.restoreState(it) { !isActive }
            }
            val savedHash = savedState?.hash ?: null
            
            val hashDeferred = async {
                contentResolver.openInputStream(uri)?.let {
                    FileUtils.calculateFileHash(it)
                } ?: null
            }          
            val originHash = hashDeferred.await()            
            // check the file origin and saved hash
            if (originHash != savedHash && originHash != null) {         
                // the file has been changed
                val isReloadFile = showFileDifferDialog(
                    document.getName(),
                    getString(R.string.dialog_msg_file_changed),
                )        
               
                if (isReloadFile) {
                    binding.editor.setBuffer(service.readFile(uri))                      
                }
            } else if(originHash != savedHash && originHash == null) {
                // the file has been deleted
                val isReserveFile = showFileDifferDialog(
                    document.getName(),
                    getString(R.string.dialog_msg_file_deleted),
                )                
                
                if (!isReserveFile) {
                    serializeFile.delete()
                }
            }                      
            // initialize the tree-sitter
            service.initTreeSitter(
                document.getName(),
                binding.editor.treeSitter,
                binding.editor.getTextBuffer()
            )
            return@loadSerializeFile savedState       
        
        } else {
            // the file is not serialized and loaded directly
            binding.editor.setBuffer(service.readFile(uri))
            // initialize the tree-sitter
            service.initTreeSitter(
                document.getName(),
                binding.editor.treeSitter,
                binding.editor.getTextBuffer()
            )
        }        
        return@loadSerializeFile null
    }
    
    @AnyThread
    fun openFile(document: DocumentFile) {       
        // the previous opened document
        val previousUri = if(::openedFile.isInitialized)
            openedFile.getUri()
        else null
        
        // cancel the previous job
        val previousJob = openedJobs.get(previousUri)?.also {
            it.cancel() 
        }
        
        val currentUri = document.getUri()        
        // reassign the opened file to a new document
        openedFile = document
        // save the current opened uri        
        sharedPreference.edit().apply {
            putString("current_opened_uri", currentUri.toString())
        }.commit()
             
        // update the UI state
        if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
            binding.editor.setMargin(
                bottomMargin = binding.extraKeysPanel.getHeight()
            )
        }
        binding.editor.setEditable(false)
        binding.editor.treeSitter.recycle()   
        binding.progressBar.setVisibility(View.VISIBLE)
        invalidateOptionsMenu()
                        
        var savedState: SavedState? = null
        val job = lifecycleScope.launch(Dispatchers.IO) {
            // when opening a new document
            // we should save and serialize the previously opened document            
            if (
                previousUri != null && 
                previousJob!!.isCompleted && 
                viewModel.isTextChanged.value
            ) {              
                service.writeFile(previousUri, binding.editor.getTextBuffer())
                val stateDeferred = async { 
                    service.serialize(previousUri, binding.editor) 
                }
                stateDeferred.await()
            }
            openedJobs.remove(previousUri)
            savedState = loadSerializeFile(document)
        }
        
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                return@invokeOnCompletion
            }
            // update the UI on main thread
            lifecycleScope.launch(Dispatchers.Main) {
                // the document read finished
                binding.editor.refresh()            
                binding.editor.setEditable(true)
                binding.progressBar.setVisibility(View.GONE)                
                // update the option menu state                                   
                savedState?.let {                    
                    viewModel.setCanUndo(it.undoStack.size > 0)
                    viewModel.setCanRedo(it.redoStack.size > 0)
                } ?: run {                    
                    viewModel.setCanUndo(false)
                    viewModel.setCanRedo(false)
                }
                viewModel.setTextChanged(false)
                invalidateOptionsMenu()
            }
        }     
        openedJobs.put(currentUri, job)
    }
    
    @AnyThread
    fun saveFile(document: DocumentFile) {      
        // update the UI state
        binding.editor.setEditable(false)
        binding.progressBar.setVisibility(View.VISIBLE)
        invalidateOptionsMenu()
        // running on background thread
        val job = lifecycleScope.launch(Dispatchers.IO) {
            val uri = document.getUri()
            service.writeFile(uri, binding.editor.getTextBuffer())            
            // serialize the editor states
            async { 
                service.serialize(uri, binding.editor) 
            }    
        }
        job.invokeOnCompletion { cause ->
            // running on main thread
            lifecycleScope.launch(Dispatchers.Main) {                 
                binding.progressBar.setVisibility(View.GONE)
                binding.editor.setEditable(true)
                viewModel.setTextChanged(false)
                invalidateOptionsMenu()                                       
            }
        }
    }
    
    override fun onLowMemory() {
        super.onLowMemory()
        if (::openedFile.isInitialized && viewModel.isTextChanged.value) {                      
            lifecycleScope.launch(Dispatchers.IO) {               
                val uri = openedFile.getUri()  
                service.writeFile(uri, binding.editor.getTextBuffer())            
                // serialize the editor states
                async { 
                    service.serialize(uri, binding.editor) 
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()       
        with(binding.editor) {
            recycleRenderNode()
            treeSitter.recycle()
        }
    }
}

