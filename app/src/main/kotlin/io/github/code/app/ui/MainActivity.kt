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

package io.github.code.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.DocumentsContract
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

import android.window.OnBackInvokedDispatcher

import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.updateLayoutParams
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.ui.AppBarConfiguration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import android.content.SharedPreferences
import androidx.preference.PreferenceManager

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import io.github.code.app.common.PackageInfo
import io.github.code.app.R
import io.github.code.app.databinding.ActivityMainBinding
import io.github.code.app.common.AppSettings
import io.github.code.app.common.BaseAdapter
import io.github.code.app.common.DatabaseManager
import io.github.code.app.common.EntityDao
import io.github.code.app.common.HeaderEntity
import io.github.code.app.common.DownloadManager
import io.github.code.app.common.DownloadState
import io.github.code.app.common.JsonParser
import io.github.code.app.common.Span
import io.github.code.app.view.HighlightTextView

import io.github.module.alerter.Alerter
import io.github.module.document.DocumentFile
import io.github.module.piecetable.PieceTreeTextBufferBuilder
import io.github.module.piecetable.common.Range
import io.github.module.piecetable.common.Strings
import io.github.module.treesitter.*

import kotlin.system.measureTimeMillis

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.lang.Runtime
import java.text.DecimalFormat
import java.util.zip.CRC32
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry


class MainActivity : BaseActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private lateinit var progressBar: LinearProgressIndicator

    private lateinit var editor: HighlightTextView

    private lateinit var shortcutRecyclerView: RecyclerView
    private lateinit var vertRecyclerView: RecyclerView
    private lateinit var horizRecyclerView: RecyclerView

    private lateinit var primary: MutableList<DocumentFile>
    private lateinit var prefix: MutableList<DocumentFile>
    
    private lateinit var behavior: BottomSheetBehavior<View>
    
    // /storage/emulated/0/Android/data/github.codex.app/files
    private lateinit var internalFilesDir: DocumentFile
    // opened file
    private lateinit var openedFiles: MutableSet<DocumentFile>
    
    // search matches list
    private lateinit var searchMatches: MutableList<Range>
    // lambda for perform the search operation
    private lateinit var performSearch: (String) -> Job
    
    private var searchTextJob: Job? = null
    
    private lateinit var snackbar: Snackbar
    private lateinit var sharedPref: SharedPreferences
    
    
    // suffix => tree_sitter_grammar
    private val filetype = mutableMapOf<String, String>()
    // token => span
    private val spans = mutableMapOf<String, Span>()
    
    // the single thread pool dispatcher, for search operations
    private val searchThreadExecutor by lazy {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }
    
    // initial the vibrator
    private val vibrator: Vibrator by lazy {
        if(Build.VERSION.SDK_INT >= 31) {
            val vibratorManager = getSystemService(
                Context.VIBRATOR_MANAGER_SERVICE
            ) as VibratorManager
        
            vibratorManager.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    // log tag
    private val TAG = this::class.simpleName
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.appBarMain.toolbar)
        
        sharedPref = getPreferences(Context.MODE_PRIVATE)
        
        binding.appBarMain.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }
        
        // val navController = findNavController(R.id.nav_host_fragment_content_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_settings
            ),
            binding.drawerLayout
        )
        // setupActionBarWithNavController(navController, appBarConfiguration)

        binding.drawerLayout.addDrawerListener(
            ActionBarDrawerToggle(
                this@MainActivity,
                binding.drawerLayout,
                binding.appBarMain.toolbar,
                R.string.app_name,
                R.string.app_name
            ).apply { 
                syncState() 
            }
        )

        binding.navViewStart.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
            return@setNavigationItemSelectedListener true
        }

        editor = binding.appBarMain.contentMain.editor.apply {            
            //setTypeface(resources.getFont(R.font.jetbrains_mono_regular))            
            //post { setWordwrap(true) }            
            if (getUiMode() == Configuration.UI_MODE_NIGHT_YES) {
                setPaintColor(Color.LTGRAY)
            } else if (getUiMode() == Configuration.UI_MODE_NIGHT_NO) {
                setPaintColor(Color.DKGRAY)
            } else {
                setPaintColor(Color.GRAY)
            }
        }
        
        progressBar = binding.appBarMain.progressIndicator.apply {
            setVisibility(View.GONE)
        }
        
        snackbar = Snackbar.make(editor, String(), Int.MAX_VALUE).apply {
            setAction(getString(android.R.string.ok)) {
                this.dismiss()
            }
        }
        
        internalFilesDir = DocumentFile.fromFile(getExternalFilesDir(null)!!)!!
        
        openedFiles = mutableSetOf<DocumentFile>()
        
        primary = mutableListOf(internalFilesDir)
        
        contentResolver.getPersistedUriPermissions().map {
            DocumentFile.fromUri(this, it.getUri())
        }.filterNotNull().forEach(primary::add)
        
        // create an invalid uri at position 0
        prefix = mutableListOf(
            DocumentFile.createEmptyFile(
                "primary", 
                Uri.parse(getString(R.string.document_base_uri))
            )
        )
        
        setupShortcutKey()
        setupHorizRecyclerView()
        setupVertRecyclerView()

        flowSubscribers()
        showSearchBottonSheet()
        
        shortcutRecyclerView.post {
            // set the bottom margin for shortcut key
            editor.setMarginLayout(
                bottom = shortcutRecyclerView.getHeight()
            )
        }
        
        window.decorView.post {
            
            downloadTreeSitter()
            
            // load native libraies at /data/data/package_name/tree-sitter-libraies/*.so
            File(getFilesDir(), "lib").listFiles()?.forEach {
                System.load(it.absolutePath)
            }
            
            mainViewModel.execute(Dispatchers.IO) {
                File(getFilesDir(), "theme/dark.json").also {
                    if(it.exists()) {
                        makeTokenSpan(it.inputStream())
                    }
                }                
            }
            
            handleExtraIntent(intent)
            
            // parser file type
            detectFileType(resources.assets.open("filetypes.json"))          
        }       
    }
    
    // parse the filetypes.json
    // get the file type by suffix
    @Throws(SerializationException::class)
    fun detectFileType(input: InputStream) {
        JsonParser.parse(input) { _, element ->
            element.jsonObject?.forEach { entry ->
                // Map.Entry<String, JsonElement>           
                entry.value.jsonArray?.forEach {
                    filetype.put(it.jsonPrimitive.content, entry.key)
                }                
            }
        }
    }
    
    @Throws(SerializationException::class)
    fun makeTokenSpan(input: InputStream) {
        JsonParser.parse(input) { _, element ->
            element.jsonObject["highlights"]?.jsonObject?.forEach {                
                if (it.value is JsonObject) {
                    val span = Span(null, null)
                    it.value.jsonObject["fg"]?.let {
                        // foreground color
                        span.fg = Color.parseColor(it.jsonPrimitive.content)
                    }
               
                    it.value.jsonObject["bg"]?.let {
                        // background color
                        span.bg = Color.parseColor(it.jsonPrimitive.content)
                    }
               
                    it.value.jsonObject["bold"]?.let {
                        // bold style
                        span.bold = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["italic"]?.let {
                        // italic style
                        span.italic = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["strikethrough"]?.let {
                        // strikethrough style
                        span.strikethrough = it.jsonPrimitive.boolean
                    }
               
                    it.value.jsonObject["underline"]?.let {
                        // underline style
                        span.underline = it.jsonPrimitive.boolean
                    }
                    // add span
                    spans.put(it.key, span)  
                } else if (it.value !is JsonNull) {
                    // foreground color and add span
                    spans.put(it.key, Span(Color.parseColor(it.value.jsonPrimitive.content)))  
                }                     
            }
        }
    }
    
    // handle intent from external
    fun handleExtraIntent(intent: Intent) {
        if(intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                handleExtraUri(uri)
            }
        }
    }
    
    // received intent content uri from external
    fun handleExtraUri(uri: Uri) {
        val scheme = uri.getScheme()
        if(!TextUtils.isEmpty(scheme)) {
            if(scheme == "content") {
                DocumentFile.fromSingleUri(this, uri)?.let {
                    openFile(it)
                }
            }
        }
    }
        
    override fun onBackKeyPressed() {
        with(binding.drawerLayout) {
            if(isDrawerOpen(GravityCompat.START)) {
                closeDrawer(GravityCompat.START)
            } else if(isDrawerOpen(GravityCompat.END)) {
                closeDrawer(GravityCompat.END)
            } else {
                showDialog(
                    getString(R.string.app_name),
                    getString(R.string.app_exit_prompt),
                    null
                ) { 
                    this@MainActivity.finishAffinity()
                }
            }
        }
    }
    
    fun getDownloadLinks(): List<String> {
        val links = mutableListOf<String>()
        val input = resources.assets.open("treesitter.json")            
        // get the download links from json file
        JsonParser.parse(input) { _, jsonElement ->            
            jsonElement.jsonObject?.forEach { element ->
                if(element.value is JsonObject) {
                    element.value.jsonObject.forEach { entry ->
                        if(entry.key == getDeviceArchName()) {
                            links.add(entry.value.jsonPrimitive.content.toString())
                            return@forEach
                        }
                    }
                } else {
                    links.add(element.value.jsonPrimitive.content.toString())
                }
            }
        }
        return links
    }
    
    fun downloadTreeSitter() {
        val alerter = Alerter.create(this@MainActivity).apply {
            setElevation(30f)
            setBackgroundColorRes(
                resolveAttr(com.google.android.material.R.attr.colorPrimaryContainer)
            )
        }
        val database = DatabaseManager.getInstance(this@MainActivity).entityDao()
        val links = getDownloadLinks()
        val tasks = mutableListOf<Job>()

        val needDownloadLinks = links.filter {
            database.query(it) == null
        }.toList()
        
        // lambda for download code snippet
        val download = { link: String ->
            val filename = link.run {
                substring(indexOfLast { it == '/' } + 1)
            }
            val outputFile = File(getCacheDir(), filename)
            // start download
            val job = downloadFile(
                url = link,
                outputFile = outputFile,
                onProgress = { progress ->
                    alerter.setTitle("${getString(R.string.file_download)}(${tasks.size})")
                    alerter.setText("$link...($progress%)")
                },
                onBackground = { file ->
                    // hardcode the file path name
                    val prefix = file.name.run {
                        substring(0, indexOfLast { it == '.' })
                    }
                    val targetFile = when(prefix) {
                        "libtree-sitter-${getDeviceArchName()}" -> File(getFilesDir(), "lib")
                        "tree-sitter-queries" -> File(getFilesDir(), "query")
                        "tree-sitter-themes" -> File(getFilesDir(), "theme")
                        else -> getFilesDir()
                    }
                    
                    // extract the zip file
                    unzipFile(file, targetFile)
         
                    when(targetFile.name) {
                        "lib" -> {
                            targetFile.listFiles()?.forEach {
                                System.load(it.absolutePath)
                            }
                        }
                        "theme" -> {
                            makeTokenSpan(File(targetFile, "dark.json").inputStream())                           
                        }
                        "query" -> {
                            // TODO
                        }
                        else -> { /* nothing to do */ }
                    }
                },
                onComplete = { file, etag ->
                    val headerEntity = database.query(link)
                    if (headerEntity != null) {
                        // delete old HeaderEntity for current url
                        database.delete(headerEntity)
                    }
                    // add new HeaderEntity
                    database.add(HeaderEntity(link, etag))
                    // delete cache zip file
                    file.delete()
                    
                    tasks.removeAt(0)
                    
                    if(tasks.isEmpty()) {
                        with(alerter) {
                            setIcon(R.drawable.ic_download_done)
                            enableProgress(false)
                            setTitle(getString(R.string.file_done))
                            setText(link)
                            setDuration(3000L)
                        }
                        handler.postDelayed({
                            alerter.dismiss()
                        }, 2000L)
                    }
                },
                onError = { message ->
                    alerter.setIcon(R.drawable.ic_error_outline)
                    alerter.enableProgress(false)
                    alerter.setTitle(
                        "${getString(R.string.file_download)}${getString(R.string.fail)}"
                    )
                    alerter.setText(message)
                }
            )
            // add the current download task
            tasks.add(job)
        }
        
        // check need downloads
        if (needDownloadLinks.size > 0) {
            // check network state
            if (DownloadManager.isConnected(this@MainActivity)) {
                with(alerter) {
                    enableProgress(true)
                    setTitle("${getString(R.string.file_download)}(${needDownloadLinks.size})")
                    setText(needDownloadLinks[0])
                }
                needDownloadLinks.forEach { link ->
                    download(link)
                }
            }
            // show alert
            alerter.show()
        }

        // check network state
        if (DownloadManager.isConnected(this@MainActivity)) {
            mainScope.launch {
                // check need updates
                val needUpdateLinks = links.filter {
                    val headerEntity = database.query(it)
                    headerEntity != null && DownloadManager.validate(it, headerEntity.etag)
                }.toList()

                if (needUpdateLinks.size > 0) {
                    with(alerter) {
                        setText("发现新版本，是否进行更新")
                        addButton(
                            getString(android.R.string.cancel),
                            View.OnClickListener {
                                setDuration(3000L)
                                dismiss()
                            },
                        )

                        addButton(
                            getString(android.R.string.ok),
                            View.OnClickListener {
                                needUpdateLinks.forEach { link ->
                                    download(link)
                                }
                            },
                        )
                        show() // show alert
                    }
                }
            }
        }
    }
    
    fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null,
        onBackground: ((File) -> Unit)? = null,
        onComplete: ((File, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    ) = mainScope.launch {
        val startBytes = if (outputFile.exists()) outputFile.length() else 0L
        DownloadManager.download(url, outputFile, startBytes).collect { state ->
            when (state) {
                is DownloadState.InProgress -> {
                    onProgress?.invoke(state.progress)
                }
                is DownloadState.Success -> {
                    if (!state.file.exists()) {
                        // has download success, but the file is not exists or deleted
                        // nothing to do
                        return@collect
                    }
                    // do in background thread
                    withContext(Dispatchers.IO) {
                        // extract the zip file
                        onBackground?.invoke(state.file)
                    }                  
                    // finish
                    onComplete?.invoke(state.file, state.etag)
                }
                is DownloadState.Error -> {
                    onError?.invoke(state.throwable.message!!)
                }
            }
        }
    }
    
    fun unzipFile(zipFile: File, destDir: File, block: ((String) -> Unit)? = null) {    
        val zfile = ZipFile(zipFile)       
        val zinput = ZipInputStream(FileInputStream(zipFile))
        var entry: ZipEntry? = null
        
        while (zinput.nextEntry.also { entry = it } != null) {
            val outFile = File(destDir, entry!!.name)
            if (outFile.parentFile != null && !outFile.parentFile.exists()) {
                outFile.parentFile.mkdirs()
            }

            if (!outFile.exists()) {
                if (entry!!.isDirectory) {
                    outFile.mkdirs()
                    continue
                } else {
                    outFile.createNewFile()
                }
            }
                       
            val bis = BufferedInputStream(zfile.getInputStream(entry))
            val bos = BufferedOutputStream(FileOutputStream(outFile))
            val buffer = ByteArray(1024)
            var len = bis.read(buffer)
            while (len >= 0) {
                bos.write(buffer, 0, len)
                // continue to read
                len = bis.read(buffer)
            }
            bos.close()
            bis.close()
            
            block?.invoke(outFile.path)
        }
        // close the zip stream
        zfile.close()
    }
    
    @Throws(RuntimeException::class)
    fun getDeviceArchName(): String {
        for (androidArch in Build.SUPPORTED_ABIS) {
            when (androidArch) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "i686"
            }
        }
        throw RuntimeException(
            "Unable to determine arch from Build.SUPPORTED_ABIS = ${Build.SUPPORTED_ABIS}"
        )
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // requires to set the new intent
        setIntent(intent)
        // receive the intent to open file from other app
        handleExtraIntent(intent)
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val uiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        //
        when (uiMode) {
            Configuration.UI_MODE_NIGHT_NO -> {
                // Night mode is not active, we're using the light theme
                Log.i(TAG, "light theme")
            }
            Configuration.UI_MODE_NIGHT_YES -> {
                // Night mode is active, we're using dark theme
                Log.i(TAG, "night theme")
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
        // set undo menu icon
        menu.findItem(R.id.action_undo).apply {
            setEnabled(mainViewModel.stateUndo.value)
        }

        // set redo menu icon
        menu.findItem(R.id.action_redo).apply {
            setEnabled(mainViewModel.stateRedo.value)
        }

        // set edit mode menu icon
        menu.findItem(R.id.action_edit_mode).apply {
            when (editor.isEditable()) {
                true -> setIcon(R.drawable.ic_read_write)
                else -> setIcon(R.drawable.ic_read_only)
            }
        }

        // set save menu icon state
        menu.findItem(R.id.action_save_file).apply { 
            setEnabled(mainViewModel.stateTextChanged.value) 
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
            R.id.action_undo -> editor.undo()
            R.id.action_redo -> editor.redo()
            R.id.action_edit_mode -> {            
                with(editor) {
                    // toggle the read-only and write mode
                    setEditable(!isEditable())
                }
                invalidateOptionsMenu()
            }
            R.id.action_open_file -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                
                startActivityForResult(intent) { result ->
                    result.data?.let { uri ->
                        openDocumentTree(uri) 
                    }
                }
            }
            R.id.action_search -> {
                binding.appBarMain.bottomSheetSearchView.apply {
                    behavior.peekHeight = searchViewLayout.getHeight()
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    // dismiss editor action mode
                    editor.dismissActionMode()                   
                    // escape the select text
                    //searchTextView.setText(Strings.escape(editor.getSelectText()))
                }
            }
            R.id.action_save_file -> {
                saveFile(openedFiles.elementAt(0))
            }
            R.id.action_tree_sitter -> {
                //CrashReport.testAnrCrash()
                //CrashReport.testJavaCrash()
                //CrashReport.testNativeCrash()                                               
            }
        }

        return true
    }
    
    fun setupShortcutKey() {
        val symbols = mutableListOf("+", "-", "*", "/", "=", "<", ">", "|", "(", ")", "{", "}", "$")
        val baseAdapter = BaseAdapter(symbols, R.layout.recyclerview_item_shortcut_key)
        
        baseAdapter.onBindView = { holder, datas, position ->
            with(holder.getView<TextView>(R.id.shortcut_text_view)) {
                setText(datas[position] as String)
            }
        }
        
        baseAdapter.onItemClick = { _, datas, position ->
            val effect = VibrationEffect.createPredefined(
                VibrationEffect.EFFECT_CLICK
            )
            vibrator.vibrate(effect)
            editor.insert(datas[position] as String)
        }
        
        baseAdapter.onItemLongClick = { _, datas, position ->
            editor.insert(datas[position] as String)
        }
        
        // shortcut key recycler view
        shortcutRecyclerView = binding.appBarMain.contentMain.shortcutRecyclerView.apply {
            // recyclerView layout manager
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                setOrientation(LinearLayoutManager.HORIZONTAL)
            }
            // recyclerView adapter
            adapter = baseAdapter
        }
    }    
    
    fun showSearchBottonSheet() {
        // replace dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_search_replace_text, null)
        val replaceTextView = dialogView.findViewById<AutoCompleteTextView>(R.id.replace_text_view)
        
        val adapter = ArrayAdapter<String>(this, android.R.layout.select_dialog_item, listOf<String>())
        replaceTextView.setThreshold(1)
        replaceTextView.setAdapter(adapter)
        
        // init the bottom sheet search view
        binding.appBarMain.bottomSheetSearchView.apply {            
            var currentMatchedIndex: Int = 0  
            // regex options
            val options = mutableSetOf<RegexOption>(/*RegexOption.MULTILINE*/)
            // init the bottom sheet behavior
            behavior = BottomSheetBehavior.from(searchViewLayout)
            behavior.peekHeight = 0
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
            // bottom sheet callback
            behavior.addBottomSheetCallback(
                object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        when(newState) {
                            BottomSheetBehavior.STATE_EXPANDED -> {
                                editor.setMarginLayout(
                                    bottom = bottomSheet.getHeight()
                                )
                            }
                            BottomSheetBehavior.STATE_HIDDEN -> {
                                editor.setMarginLayout(
                                    bottom = shortcutRecyclerView.getHeight()
                                )
                                // clear the search matches
                                searchMatches.clear()
                                editor.setSearchMatches(null)
                                findCounter.setText("0/${searchMatches.size}")
                            }
                        }
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        // TODO
                    }
                }
            )
            
            // init the popup menu           
            val popupMenu = getPopupMenu(
                buttonMore, R.menu.searchview_more_options
            ) { menuItem ->                
                mainViewModel.removeJobs(searchTextJob)
                when (menuItem.itemId) {
                    R.id.action_regex -> {
                        menuItem.isChecked = !menuItem.isChecked
                        // research
                        searchTextJob = performSearch(searchTextView.getText().toString())
                    }
                    R.id.action_ignorecase -> {
                        menuItem.isChecked = !menuItem.isChecked
                        when (menuItem.isChecked) {
                            true -> options.add(RegexOption.IGNORE_CASE)
                            else -> options.remove(RegexOption.IGNORE_CASE)
                        }
                        // research
                        searchTextJob = performSearch(searchTextView.getText().toString())
                    }
                    R.id.action_close -> {
                        behavior.peekHeight = 0
                        behavior.state = BottomSheetBehavior.STATE_HIDDEN
                        // clear the search matches
                        searchMatches.clear()
                        editor.setSearchMatches(null)
                        findCounter.setText("0/${searchMatches.size}")
                    }
                }
            }
            
            // init the search operation lambda
            performSearch = { searchText ->
                // running on background thread
                // return the search job
                mainViewModel.execute(searchThreadExecutor) {       
                    delay(300L) // debounce 300ms
                    searchMatches = if(searchText.length > 0) {
                        when(popupMenu.menu.findItem(R.id.action_regex).isChecked) {
                            true -> { // find by regex
                                try {
                                    // check the PatternSyntaxException
                                    editor.find(Regex(searchText, options)) { !isActive }                          
                                } catch(e: java.util.regex.PatternSyntaxException) {
                                    e.printStackTrace()
                                    mutableListOf<Range>()
                                }
                            } // find by word
                            else -> editor.find(Strings.unescape(searchText)) { !isActive }
                        }
                    } else {
                        mutableListOf<Range>()
                    }

                    // running on UI thread                  
                    withContext(Dispatchers.Main) {
                        // find current index in search matches
                        // note that the index may be less than 0
                        currentMatchedIndex = searchMatches.binarySearch(editor.getSelection())
                        editor.setSearchMatches(searchMatches)
                        if(searchMatches.size > 0) {
                            // match found
                            currentMatchedIndex = Math.max(currentMatchedIndex, 0)                 
                            editor.setSelection(searchMatches[currentMatchedIndex])                  
                        }
                        findCounter.setText("${currentMatchedIndex + 1}/${searchMatches.size}")
                    }
                }
            }
            
            // text changed callback
            mainScope.launch {
                mainViewModel.textSharedFlow.collect {
                    // re-perform search when text has changed
                    if (behavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                        mainViewModel.removeJobs(searchTextJob)
                        searchTextJob = performSearch(searchTextView.getText().toString())
                    }              
                }
            }
            
            // add TextWatcher for search EditText
            searchTextView.addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                    mainViewModel.removeJobs(searchTextJob)
                }
                
                override fun afterTextChanged(s: Editable) {
                    searchTextJob = performSearch(s.toString())
                }
    
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    // TODO
                }
            })
            
            editor.setOnFocusChangeListener { _, hasFocus ->
                if(hasFocus) {
                    searchTextView.clearFocus()
                }
            }
            
            searchTextView.setOnFocusChangeListener { _, hasFocus ->
                if(hasFocus) {
                    editor.removeFocus()
                }
            }

            // search TextInputLayout
            searchTextField.setEndIconOnClickListener {
               mainViewModel.removeJobs(searchTextJob)
               searchTextJob = performSearch(searchTextView.getText().toString())
            }

            // goto previous match item
            buttonFindPrev.setOnClickListener {
                if(!searchMatches.isEmpty()) {
                    // previous index of match item
                    if(--currentMatchedIndex < 0) {
                        // goto the last index
                        currentMatchedIndex = searchMatches.size - 1
                    }
                    editor.setSelection(searchMatches[currentMatchedIndex])
                }                                                          
                findCounter.setText("${currentMatchedIndex + 1}/${searchMatches.size}")
            }
            
            // goto next match item
            buttonFindNext.setOnClickListener {
                if(!searchMatches.isEmpty()) {
                    // next index of match item
                    if(++currentMatchedIndex >= searchMatches.size) {
                        // goto the first index
                        currentMatchedIndex = 0
                    }
                    editor.setSelection(searchMatches[currentMatchedIndex])
                }
                findCounter.setText("${currentMatchedIndex + 1}/${searchMatches.size}")
            }
            
            // show replace dialog
            buttonReplace.setOnClickListener {
                showDialog(getString(R.string.replace_text_hint), null, dialogView) {
                    // execute the real replace text
                    val replaceText = replaceTextView.getText().toString()
                    // default replace all
                    editor.replace(replaceText)
                    if(replaceText.length > 0) {
                        adapter.add(replaceText)
                        adapter.notifyDataSetChanged()
                    }
                }
                replaceTextView.setHint(getString(R.string.replace_prompt))
                replaceTextView.requestFocus()
            }
            
            // button more options
            buttonMore.setOnClickListener {
                popupMenu.show()
            }
        }
    }


    // init the horizontal RecyclerView
    fun setupHorizRecyclerView() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_document_input, null)      
        val inputTextView = dialogView.findViewById<EditText>(R.id.doc_text_view)
        
        val baseHorizAdapter = BaseAdapter(prefix, R.layout.recyclerview_item_horiz_filelist)

        baseHorizAdapter.onBindView = { holder, datas, position ->
            with(holder.getView<TextView>(R.id.parent_file_name)) {
                val documentFile = datas[position] as DocumentFile
                if(documentFile === internalFilesDir)
                    setText("internal")
                else
                    setText(documentFile.getName())
            }
        }

        baseHorizAdapter.onItemClick = { _, datas, position ->
            for (i in prefix.size - 1 downTo position + 1) {
                prefix.removeAt(i)
            }

            mainViewModel.execute(Dispatchers.Default) {
                // get children files on background thread
                val documentFile = datas[position] as DocumentFile
                val childrenFiles = documentFile.listFiles() 
                // update the recyclerview on main thread
                withContext(Dispatchers.Main) {
                    val vertAdapter = vertRecyclerView.adapter as BaseAdapter
                    // check position 0
                    vertAdapter.datas = if(position > 0) childrenFiles else primary
                    // update the vertical recyclerview data
                    vertAdapter.notifyDataSetChanged()
                    // update the horizontal recyclerview data
                    baseHorizAdapter.notifyDataSetChanged()
                }
            }
        }

        baseHorizAdapter.onItemLongClick = { view, datas, position ->
            val documentFile = datas[position] as DocumentFile
                        
            val popupMenu = getPopupMenu(view, R.menu.horiz_popup_menu) { menuItem ->
                when (menuItem.itemId) {
                    // create a new directory
                    R.id.action_create_dir -> {
                        // show dialog
                        showDialog(getString(R.string.action_create_dir), null, dialogView) {
                            val name = inputTextView.getText().toString()
                            if(TextUtils.isEmpty(name)) {
                                return@showDialog
                            }
                            
                            documentFile.createDirectory(name)?.let { newDir ->
                                (vertRecyclerView.adapter as? BaseAdapter)?.let { adapter ->
                                     // comparator for search
                                    val comparator = compareBy<DocumentFile>{ 
                                        it.isDirectory() 
                                    }.thenBy { 
                                        it.getName() 
                                    }
                                    
                                    @Suppress("UNCHECKED_CAST")
                                    val index = (adapter.datas!! as MutableList<DocumentFile>).run {
                                        // add the new create directory
                                        add(newDir)
                                        // sort the files list
                                        sortWith(this)
                                        // find the target file
                                        binarySearch(newDir, comparator)
                                    }
                                    
                                    // update the vertical recyclerview data
                                    // check position at the last index
                                    if(position == prefix.size - 1) {
                                        adapter.notifyItemInserted(index)
                                    }
                                }
                            }
                        }
                        // set the EditText
                        inputTextView.setHint(getString(R.string.create_dir_prompt))
                        inputTextView.requestFocus()
                    }
                    
                    // create a new file
                    R.id.action_create_file -> {
                        // show dialog
                        showDialog(getString(R.string.action_create_file), null, dialogView) {
                            val name = inputTextView.getText().toString()
                            if(TextUtils.isEmpty(name)) {
                                return@showDialog
                            }
                        
                            documentFile.createFile("text/plain", name)?.let { newFile ->
                                // note: the <text/plain> can only create plain text files
                                // so we need to check the file name
                                var targetFile: DocumentFile = newFile
                                if (newFile.getName() != name) {
                                    newFile.renameTo(name)?.let { targetFile = it }
                                }

                                (vertRecyclerView.adapter as? BaseAdapter)?.let { adapter ->
                                    // comparator for search
                                    val comparator = compareBy<DocumentFile>{ 
                                        it.isFile()
                                    }.thenBy { 
                                        it.getName() 
                                    }
                                    
                                    @Suppress("UNCHECKED_CAST")
                                    val index = (adapter.datas!! as MutableList<DocumentFile>).run {
                                        // add the new create file
                                        add(targetFile)
                                        // sort the files list
                                        sortWith(this)                                       
                                        // find the target file
                                        binarySearch(targetFile, comparator)
                                    }
                                                              
                                    // update the vertical recyclerview data
                                    // check position at the last index
                                    if(position == prefix.size - 1) {
                                        adapter.notifyItemInserted(index)
                                    }
                                }
                            }
                        }
                        // set the EditText
                        inputTextView.setHint(getString(R.string.create_file_prompt))
                        inputTextView.requestFocus()
                    }
                }
            }
            
            // set the menu item state
            popupMenu.menu.findItem(R.id.action_create_dir).apply {
                // position 0 is invalid document uri
                setEnabled(position > 0)
            }
            
            popupMenu.menu.findItem(R.id.action_create_file).apply {
                // position 0 is invalid document uri
                setEnabled(position > 0)
            }
            
            // show the popup menu
            popupMenu.show()
        }

        horizRecyclerView = binding.navDrawerEnd.horizRecyclerView.apply {
            // recyclerView layout manager
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                setOrientation(LinearLayoutManager.HORIZONTAL)
            }
            // recyclerView adapter
            adapter = baseHorizAdapter
        }
    }

    // init the vertical RecyclerView
    fun setupVertRecyclerView() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_document_input, null)        
        val inputTextView = dialogView.findViewById<EditText>(R.id.doc_text_view)
        
        val baseVertAdapter = BaseAdapter(primary, R.layout.recyclerview_item_vert_filelist)

        baseVertAdapter.onBindView = { holder, datas, position ->
            val documentFile = datas[position] as DocumentFile

            with(holder.getView<ImageView>(R.id.doc_file_icon)) {
                setImageResource(
                    if (documentFile.isDirectory()) {
                        R.drawable.ic_folder
                    } else {
                        R.drawable.ic_file
                    }
                )
            }

            with(holder.getView<TextView>(R.id.doc_file_name)) {
                if(documentFile === internalFilesDir)
                    setText("internal")
                else
                    setText(documentFile.getName())
            }

            with(holder.getView<TextView>(R.id.doc_file_size)) {
                setText(
                    if (documentFile.isDirectory()) {
                        "${documentFile.getChildCount()} ${getString(R.string.type_file)}"
                    } else {
                        formatSize(DecimalFormat("#.00"), documentFile.length())
                    }
                )
            }
        }
        
        // item click callback
        baseVertAdapter.onItemClick = { _, datas, position ->
            val documentFile = datas[position] as DocumentFile
            // document is directory
            if (documentFile.isDirectory()) {
                prefix.add(documentFile)

                mainViewModel.execute(Dispatchers.Default) {
                    // get children files on background thread
                    val childrenFiles = documentFile.listFiles()

                    // update the recyclerview data on main thread
                    withContext(Dispatchers.Main) {
                        // vertical recyclerview adapter
                        baseVertAdapter.datas = childrenFiles
                        baseVertAdapter.notifyDataSetChanged()

                        // horizontal recyclerview adapter
                        (horizRecyclerView.adapter as? BaseAdapter)?.let { adapter ->
                            adapter.datas = prefix
                            adapter.notifyItemInserted(prefix.size - 1)
                        }
                        horizRecyclerView.smoothScrollToPosition(prefix.size - 1)
                    }
                }
            } else {
                // document is file
                openFile(documentFile)
                binding.drawerLayout.closeDrawer(GravityCompat.END)
            }
        }
        
        // item long click callback
        baseVertAdapter.onItemLongClick = { view, datas, position ->
            val documentFile = datas[position] as DocumentFile
            val validUri = documentFile.getUri().getPermitUri()
            val uriPermission = contentResolver.getPersistedUriPermissions()
                .map{ it.getUri() }.toList()
            
            val popupMenu = getPopupMenu(view, R.menu.vert_popup_menu) { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_favorite -> {
                        
                        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        
                        grantUriPermission(getPackageName(), validUri, takeFlags)
                               
                        if(menuItem.title == getString(R.string.action_favorite)) {
                            // add favorite folder
                            contentResolver.takePersistableUriPermission(validUri, takeFlags)                          
                        } else {
                            // remove favorite folder
                            contentResolver.releasePersistableUriPermission(validUri, takeFlags)
                        }
                    }
                    // rename file
                    R.id.action_rename_file -> {
                        // show dialog                                                                        
                        showDialog(getString(R.string.action_rename_file), null, dialogView) {
                            val name = inputTextView.getText().toString()
                            if(TextUtils.isEmpty(name)) {
                                return@showDialog
                            }
                            
                            documentFile.renameTo(name)?.let { targetFile ->
                                // comparator for search target file
                                val comparator = compareBy<DocumentFile>{ 
                                    if(targetFile.isFile()) it.isFile() else it.isDirectory()
                                }.thenBy { 
                                    it.getName() 
                                }
                                
                                @Suppress("UNCHECKED_CAST")
                                val index = (baseVertAdapter.datas!! as MutableList<DocumentFile>).run {
                                    removeAt(position)                                    
                                    // add the renamed file
                                    add(targetFile)
                                    // sort the files list
                                    sortWith(this)
                                    // find the target file
                                    binarySearch(targetFile, comparator)                                   
                                }
                                
                                // update the vertical recyclerview data
                                baseVertAdapter.notifyItemRemoved(position)
                                baseVertAdapter.notifyItemInserted(index)
                            }
                        }
                        // set the EditText
                        with(inputTextView) {
                            setText(documentFile.getName())
                            setSelection(getText().length)
                            requestFocus()
                        }
                    }
                    // delete files
                    R.id.action_delete_file -> {
                        showDialog(
                            getString(R.string.action_delete_file) + getString(R.string.type_file),
                            "${getString(R.string.delete_file_prompt)} ${documentFile.getName()}?",
                            null,
                        ) {
                            // check delete result
                            if (documentFile.delete()) {
                                baseVertAdapter.datas?.removeAt(position)
                                baseVertAdapter.notifyItemRemoved(position)
                            }
                        }
                    }
                }
            }
            
            // set the menu item state
            popupMenu.menu.findItem(R.id.action_favorite).apply {
                
                if(uriPermission.contains(validUri)) {
                    setIcon(R.drawable.ic_favorite_off)
                    setTitle(getString(R.string.action_favorite_off))
                } else {
                    setIcon(R.drawable.ic_favorite)
                    setTitle(getString(R.string.action_favorite))                                        
                }
                
                // check position 0
                setEnabled(
                    documentFile !== internalFilesDir &&
                    documentFile.getParent() == null
                )
            }
            
            popupMenu.menu.findItem(R.id.action_rename_file).apply {
                setEnabled(documentFile.getParent() != null)
            }
            
            popupMenu.menu.findItem(R.id.action_delete_file).apply {
                setEnabled(documentFile.getParent() != null)
            }
                                   
            // show the popup menu
            popupMenu.show()
        }

        vertRecyclerView = binding.navDrawerEnd.vertRecyclerView.apply {
            // recyclerview layout manager
            layoutManager = LinearLayoutManager(this@MainActivity)
            // recyclerView adapter
            adapter = baseVertAdapter
            setHasFixedSize(true)
        }
    }

    fun sortWith(filesList: MutableList<DocumentFile>) {
        filesList.sortWith(
            kotlin.Comparator { a, b ->
                if (a.isDirectory() != b.isDirectory()) {
                    b.isDirectory().compareTo(a.isDirectory())
                } else {
                    (a.getName().lowercase())
                        .compareTo((b.getName().lowercase()))
                }
            }
        )
    }
    
    fun Uri.getPermitUri(): Uri {
        // note: OPEN_DOCUMENT or OPEN_DOCUMENT_TREE
        // the UriPermission.getUri ≠ DocumentFile.getUri
        val uriPath: String = this.getPath()!!
        val startFragment = uriPath.run {
            substring(0, Math.max(0, indexOfFirst{ it == ':' }))
        }
        
        val endFragment = uriPath.run {
            Uri.encode(substring(Math.max(0, indexOfLast{ it == ':' })))
        }
        
        val baseFragment = getScheme() + "://" + getAuthority()
        
        // convert to permitted uri
        return Uri.parse(baseFragment + startFragment + endFragment)
    }

    fun getPopupMenu(
        anchorView: View,
        menuRes: Int,
        block: ((MenuItem) -> Unit)? = null
    ) = PopupMenu(this@MainActivity, anchorView).apply {
        menuInflater.inflate(menuRes, menu)
        
        val iconMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            0.toFloat(), 
            resources.displayMetrics
        ).toInt()
        
        val menuBuilder = menu as MenuBuilder
        // show menu icon
        menuBuilder.setOptionalIconsVisible(true)
        menuBuilder.visibleItems.forEach { item ->
            item.icon?.let { icon ->
                // Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP
                item.icon = InsetDrawable(icon, iconMarginPx, 0, iconMarginPx, 0)
            }
        }
        
        setOnMenuItemClickListener { menuItem ->
            // menuItem click callback
            block?.invoke(menuItem)
            return@setOnMenuItemClickListener true
        }
    }

    // stateFlow subscriber
    fun flowSubscribers() {
        mainScope.launch {
            mainViewModel.stateUndo.collect {
                // when undo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }

        mainScope.launch {
            mainViewModel.stateRedo.collect {
                // when redo state changed refresh the UI
                invalidateOptionsMenu()
            }
        }

        mainScope.launch {
            mainViewModel.stateTextChanged.collect {
                // when text changed refresh the UI
                invalidateOptionsMenu()
            }
        }
        
        mainScope.launch {
            mainViewModel.stateTextScaled.collect { value ->
                // when text changed refresh the UI
                when (value) {
                    true -> refreshComponentState(View.GONE)
                    else -> refreshComponentState(View.VISIBLE)
                }
            }
        }
    }

    fun openDocumentTree(treeUri: Uri) {
        // check the document tree uri
        val permittedUri = primary.find { 
            it.getUri().getPermitUri() == treeUri 
        }
        
        if(permittedUri == null) {
            primary.add(DocumentFile.fromUri(this, treeUri)!!)
        
            for (i in prefix.size - 1 downTo 1) {
                prefix.removeAt(i)
            }
            
            // update the horizontal recyclerview data
            (horizRecyclerView.adapter as? BaseAdapter)?.let { adapter ->
                adapter.notifyDataSetChanged()
            }
        
            // update the veritical recyclerview data
            (vertRecyclerView.adapter as? BaseAdapter)?.let { adapter ->
                adapter.datas = primary
                adapter.notifyDataSetChanged()
            }
        
            vertRecyclerView.smoothScrollToPosition(primary.size - 1)
        }
        
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    // format document size
    fun formatSize(df: DecimalFormat, size: Long) = when {
        size < 1024 -> size.toString() + "B"
        size < 1048576 -> df.format(size / 1024f) + "KB"
        size < 1073741824 -> df.format(size / 1048576f) + "MB"
        else -> df.format(size / 1073741824f) + "GB"
    }

    // check file crc32
    fun checkCRC32(bytes: ByteArray): Long {
        return CRC32().apply { update(bytes) }.value
    }
    
    fun getLanguageGrammar(filename: String): Pair<TSLanguage?, String?> {
        val suffix = filename.substring(filename.indexOf("."))
        val scope = filetype[suffix] ?: filetype[filename]
        
        val language = if (scope != null) TSLanguage(scope) else null
        
        val grammar = scope?.run {
            // length of tree_sitter_
            val name = substring(12).lowercase()
            // s-expression query grammar content           
            val expression = File(getFilesDir(), "query/${name}/highlights.scm").readText()
            val result = Regex("; inherits: (\\w+)").find(expression)
            if(result != null) {
                // super expression, like c++ inherits c
                val base = result.value.substring(result.value.indexOf(":") + 1).trim()
                val parent = File(getFilesDir(), "query/${base}/highlights.scm").readText()
                parent + expression
            } else {
                expression
            }
        }
        return Pair(language, grammar)
    }

    fun openFile(file: DocumentFile) {
        refreshComponentState(View.VISIBLE)
        // add the opened document file
        openedFiles.add(file)
        
        // running on background thread
        mainViewModel.execute(Dispatchers.IO) {
            val pieceBuilder = PieceTreeTextBufferBuilder()
            contentResolver.openInputStream(file.getUri())?.use { input ->
                BufferedReader(InputStreamReader(input, "UTF-8")).run {
                    // 64k size per read
                    val buffer = CharArray(1024 * 64)
                    var len = 0
                    while ({ len = read(buffer, 0, buffer.size); len }() > 0) {
                        pieceBuilder.acceptChunk(String(buffer, 0, len))
                    }
                }
            }
                                            
            with(editor) {
                setBuffer(pieceBuilder.build())
                // parse the text buffer with treesitter
                val (language, grammar) = getLanguageGrammar(file.getName())
                if(language != null && grammar != null) {
                    //Log.i(TAG, "${language::class.simpleName}")
                    treeSitterConfig(language, grammar, spans, true)
                }
            }                       

            // running on main thread
            withContext(Dispatchers.Main) {
                // read file finished
                refreshComponentState(View.GONE)
                editor.updateDisplayList()
            }
        }
    }

    fun saveFile(file: DocumentFile) {
        refreshComponentState(View.VISIBLE)
        // running on background thread
        mainViewModel.execute(Dispatchers.IO) {
            contentResolver.openOutputStream(file.getUri())?.use { output ->
                BufferedWriter(OutputStreamWriter(output, "UTF-8")).run {
                    with(editor) {
                        for (line in 1 until getLineCount()) {
                            // contains line feed \n
                            write(getLineWithEOL(line))
                        }
                        // the lastest line, not contains line feed \n
                        write(getLine(getLineCount()))
                        // flush cache
                        flush()
                    }
                }
            }

            // running on main thread
            withContext(Dispatchers.Main) { // write file finished
                refreshComponentState(View.GONE)
                mainViewModel.setTextChangedState(false)                
            }
        }
    }

    fun refreshComponentState(state: Int) {
        // running on main thread
        when (state) {
            View.VISIBLE -> editor.setEditable(false)
            else -> editor.setEditable(true)
        }
        progressBar.setVisibility(state)
        invalidateOptionsMenu()
    }

    override fun onSupportNavigateUp(): Boolean {
        // val navController = findNavController(R.id.nav_host_fragment_content_main)
        // return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
        return super.onSupportNavigateUp()
    }
    
    override fun onStart() {
        super.onStart()
        //window.setBackgroundDrawableResource(android.R.color.white)
    }

    override fun onDestroy() {
        super.onDestroy()
        this.searchThreadExecutor.close()
        editor.recycleRenderNode()
    }
    
}
