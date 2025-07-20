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

package x.code.app.activity

import android.app.UiModeManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.provider.DocumentsContract
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.Paint
import android.graphics.PixelFormat
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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
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

import x.code.app.R
import x.code.app.databinding.ActivityEditorBinding
import x.code.app.model.BaseAdapter
import x.code.app.model.BaseListAdapter
import x.code.app.model.DatabaseManager
import x.code.app.model.EntityDao
import x.code.app.model.HeaderEntity
import x.code.app.model.DownloadManager
import x.code.app.model.DownloadState
import x.code.app.model.Span
import x.code.app.model.SimpleItemCallback
import x.code.app.model.TreeSitter
import x.code.app.util.AppUtils
import x.code.app.util.DeviceUtils
import x.code.app.util.FileUtils
import x.code.app.util.JsonUtils
import x.code.app.util.PackageUtils
import x.code.app.view.HighlightTextView
import x.code.app.view.ContentTranslatingDrawerLayout
import x.code.app.model.XDividerItemDecoration

import x.github.module.alerter.Alerter
import x.github.module.crash.CrashReport
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
import java.security.MessageDigest


class EditorActivity : BaseActivity() {

    private lateinit var binding: ActivityEditorBinding
    
    private lateinit var service: EditorService
    
    private lateinit var openedFile: DocumentFile
    
    // save the all opened uri
    private val openedUris by lazy { mutableListOf<Uri>() }
    
    private val openedJobs by lazy { mutableMapOf<Uri, Job>() }
      
    private val uriFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_READ_URI_PERMISSION        
            
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
        }
        
        backPressed.isEnabled = true
        
        addExtraKeys(
            mutableListOf("+", "-", "*", "/", "=", "<", ">", "|", "(", ")", "{", "}", "$")
        )

        flowSubscribers()
        //showSearchBottonSheet()
        binding.apply {
            recyclerViewKeys.post {
                // set the bottom margin for shortcut key
                editor.setMarginLayout(
                    bottom = recyclerViewKeys.getHeight()
                )
            }
        }     
        
        bindService(
            Intent(this, EditorService::class.java), 
            serviceConnection, 
            Context.BIND_AUTO_CREATE
        )
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {            
            service = (binder as EditorService.ServiceBinder).getService()
            this@EditorActivity.loadTreeSitter()
            this@EditorActivity.createFileTree()       
            if(intent.action == Intent.ACTION_VIEW) {
                intent.data?.let { openExternalUri(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // TODO
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
                // android11 and above the value of MAX_PERSISTED_URI_GRANTS is 512
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
                binding.recyclerViewFiles.scrollToPosition(lastIndex + 1)               
                binding.recyclerViewFiles.adapter?.let {
                    if(::openedFile.isInitialized) {
                        it.notifyItemChanged(openedUris.indexOf(openedFile.getUri()))
                    }
                }
                // open the document
                this@EditorActivity.openFile(it)
            }          
            binding.tvNoFiles.setVisibility(View.GONE)
            binding.recyclerViewFiles.setVisibility(View.VISIBLE)
            
            binding.recyclerViewFiles.adapter?.let {
                it.notifyItemRangeChanged(lastIndex + 1, it.getItemCount())            
            }
                    
            with(binding.drawerLayout) {
                if(isDrawerOpen(GravityCompat.START)) {
                    closeDrawer(GravityCompat.START)
                }
            }                                      
        }
    }
    
    override fun onRestart() {
        super.onRestart()
        handler.removeCallbacksAndMessages(null)
        backPressed.isEnabled = true
    }
    
    override fun doBackPressed() {
        backPressed.isEnabled = false
        handler.postDelayed({
            backPressed.isEnabled = true
        }, 2000)
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
                mainViewModel.canUndo.value &&
                !binding.progressBar.isShown()
            )
        }

        // set redo option menu icon
        menu.findItem(R.id.action_redo).apply {
            setEnabled(
                mainViewModel.canRedo.value &&
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
                mainViewModel.isTextChanged.value &&
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
            R.id.action_undo -> binding.editor.undo()
            R.id.action_redo -> binding.editor.redo()
            R.id.action_edit_mode -> {            
                with(binding.editor) {
                    // toggle the read-only and write mode
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
                
            }
            R.id.action_save_file -> {
                if(this::openedFile.isInitialized) {
                    saveFile(openedFile)
                }                
            }
            R.id.action_settings -> {
                binding.editor.gotoLine(500)
            }
        }

        return true
    }
    
    private fun createFileTree() {
        val decimalFormat = DecimalFormat("#.##")
        val dateFormat = SimpleDateFormat("yyyy/MM/dd")
        var defaultTextColor: Int = getColor(
            AppUtils.resolveAttr(
                this@EditorActivity,
                android.R.attr.textColorPrimary
            )
        )
        val highlightTextColor: Int = getColor(            
            AppUtils.resolveAttr(
                this@EditorActivity,
                com.google.android.material.R.attr.colorPrimary
            )
        )       
        
        val selectBackground: Int = getColor(
            AppUtils.resolveAttr(
                this@EditorActivity,
                com.google.android.material.R.attr.colorSecondaryContainer
            )
        )
        
        val defaultBackground: Int = getColor(
            AppUtils.resolveAttr(
                this@EditorActivity,
                com.google.android.material.R.attr.colorOnSurfaceInverse
            )
        )
        
        val deleteBackground: Int = getColor(
            AppUtils.resolveAttr(
                this@EditorActivity,
                com.google.android.material.R.attr.colorError
            )
        )
        
        val itemBackground = ColorDrawable(deleteBackground)
          
        val iconBackground: Int = getColor(
            AppUtils.resolveAttr(
                this@EditorActivity,
                com.google.android.material.R.attr.colorErrorContainer
            )
        ) 
        
        val deleteIcon = ResourcesCompat.getDrawable(
            resources, 
            R.drawable.ic_close, 
            null
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
            binding.recyclerViewFiles.setVisibility(View.VISIBLE)            
        }
                      
        val baseAdapter = BaseAdapter<Uri>(openedUris, R.layout.item_file)
        
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
                    binding.recyclerViewFiles.setVisibility(View.GONE)
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
                    // refresh the previous position
                    notifyItemChanged(openedUris.indexOf(openedFile.getUri()))
                    // open the current document
                    openFile(it)
                    // refresh the current position                   
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
            }           
        }
        
        // recycler view for file list
        binding.recyclerViewFiles.apply {
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
            layoutId = R.layout.item_extra_key,
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
        binding.recyclerViewKeys.apply {
            layoutManager = LinearLayoutManager(this@EditorActivity).apply {
                setOrientation(LinearLayoutManager.HORIZONTAL)
            }
            // recyclerview adapter
            adapter = baseListAdapter.apply { submitList(keys) }
        }
    }      

    fun popupFileActionMenu(anchorView: View) {
        val popupMenu = PopupMenu(this@EditorActivity, anchorView).apply {
            menuInflater.inflate(R.menu.file_action_menu, menu)
        }
        
        val iconMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            0.toFloat(), 
            resources.displayMetrics
        ).toInt()
        
        val menuBuilder = popupMenu as MenuBuilder
        // show menu icon
        menuBuilder.setOptionalIconsVisible(true)
        menuBuilder.visibleItems.forEach { item ->
            item.icon?.let { icon ->
                // Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP
                item.icon = InsetDrawable(icon, iconMarginPx, 0, iconMarginPx, 0)
            }
        }
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            // menuItem click callback
            when (menuItem.itemId) {
            
            }
            return@setOnMenuItemClickListener true
        }
    }

    // stateFlow subscriber
    fun flowSubscribers() {
        lifecycleScope.launch {
            mainViewModel.canUndo.collect {
                // when undo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }

        lifecycleScope.launch {
            mainViewModel.canRedo.collect {
                // when redo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }

        lifecycleScope.launch {
            mainViewModel.isTextChanged.collect {
                // when text changed refresh the UI
                invalidateOptionsMenu()
            }
        }
        
        lifecycleScope.launch {
            mainViewModel.isTextScaled.collect { value ->
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
                    // persistable the uri permission
                    contentResolver.takePersistableUriPermission(uri, uriFlags)
                } catch(e: SecurityException) {
                    e.printStackTrace()
                }
            }
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
            val stateDeferred = async { 
                service.deserializeEditorState(uri) 
            }
            val bufferDeferred = async { 
                service.deserializeTextBuffer(uri) 
            }
            
            val savedState = stateDeferred.await()
            val textBuffer = bufferDeferred.await()
            // restore the editor saved datas
            if (savedState != null && textBuffer != null) {
                binding.editor.restoreState(savedState, textBuffer)                
            }
            
            val hashDeferred = async {
                contentResolver.openInputStream(uri)?.let {
                    FileUtils.calculateFileHash(it)
                } ?: null
            }
      
            val savedHash = savedState?.hash ?: null
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
        
        val previousJob = openedJobs.get(previousUri)?.also {
            it.cancel() 
        }
        
        val currentUri = document.getUri()        
        // save the current opened uri        
        sharedPreference.edit().apply {
            putString("current_opened_uri", currentUri.toString())
        }.commit()
        
        // reassign the opened file to a new document
        openedFile = document
        // update the UI state
        binding.editor.setEditable(false)
        binding.editor.treeSitter.recycle()       
        binding.progressBar.setVisibility(View.VISIBLE)
        invalidateOptionsMenu()
                
        var savedState: SavedState? = null
        val job = lifecycleScope.launch(Dispatchers.IO) {
            // when opening a new document
            // we should save and serialize the previously opened document            
            if (previousUri != null && previousJob!!.isCompleted) {                
                openedJobs.remove(previousUri)
                service.writeFile(previousUri, binding.editor.getTextBuffer())
                val stateDeferred = async { 
                    service.serializeEditorState(previousUri, binding.editor, mainViewModel) 
                }
                val bufferDeferred = async { 
                    service.serializeTextBuffer(previousUri, binding.editor) 
                }
                stateDeferred.await()
                bufferDeferred.await()
            }            
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
                with(mainViewModel) {
                    savedState?.let {
                        setTextChanged(it.modified)
                        setCanUndo(it.undoStack.size > 0)
                        setCanRedo(it.redoStack.size > 0)
                    } ?: run {
                        setTextChanged(false)
                        setCanUndo(false)
                        setCanRedo(false)
                    }
                }
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
            // serialize the editor datas
            async { 
                service.serializeEditorState(uri, binding.editor, mainViewModel) 
            }
            async { 
                service.serializeTextBuffer(uri, binding.editor) 
            }            
        }
        job.invokeOnCompletion { cause ->
            // running on main thread
            lifecycleScope.launch(Dispatchers.Main) {                 
                binding.progressBar.setVisibility(View.GONE)
                binding.editor.setEditable(true)
                mainViewModel.setTextChanged(false)
                invalidateOptionsMenu()                                       
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        /*if (this::openedFile.isInitialized) {
            binding.editor.setEditable(false)
            binding.progressBar.setVisibility(View.VISIBLE)
            invalidateOptionsMenu()
            lifecycleScope.launch(Dispatchers.IO) {
                // serialize the editor datas            
                val uri = openedFile.getUri()
                async { 
                    service.serializeEditorState(uri, binding.editor, mainViewModel) 
                }
                async { 
                    service.serializeTextBuffer(uri, binding.editor) 
                }
                withContext(Dispatchers.Main) {
                    binding.progressBar.setVisibility(View.GONE)
                    binding.editor.setEditable(true)
                    invalidateOptionsMenu()
                }
            }
        }*/
    }

    override fun onDestroy() {
        super.onDestroy()
        with(binding.editor) {
            recycleRenderNode()
            treeSitter.recycle()
        }
    }
}


