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
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast

import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import kotlin.system.exitProcess

import x.editor.app.Constants
import x.editor.app.databinding.ActivityCrashBinding
import x.editor.app.R

import x.github.module.crash.CrashLogStore
import x.github.module.crash.CrashReportUploader
import x.github.module.crash.StoredCrash

/**
 * Holds everything CrashActivity must not lose on a configuration change.
 *
 * The crash logs are deleted from disk as soon as they are loaded, so from
 * then on the text exists only here. It survives rotation, but not the
 * :crash process being killed - by design, seen logs are not kept.
 * Exporting is how the user keeps a copy.
 */
class CrashReportViewModel(application: Application) : AndroidViewModel(application) {

    enum class Exit { CLOSE, RESTART }

    sealed interface State {
        object Loading : State
        object Empty : State
        class Loaded(val text: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var loadStarted = false

    // Queuing a report (staging + WorkManager enqueue); short, but leaving waits for it
    var reportJob: Job? = null
        private set

    // Set once the report was queued successfully: one report per screen
    var reportQueued = false
        private set

    // Writing the export file; leaving waits for it, so no half-written file is left
    var exportJob: Job? = null
        private set

    // Set once the user asked to leave; the exit runs after reportJob/exportJob finish
    var pendingExit: Exit? = null

    val isReporting: Boolean
        get() = reportJob?.isActive == true

    val isExporting: Boolean
        get() = exportJob?.isActive == true

    /** Loads (and deletes) every unseen crash log, once per screen. */
    fun load(inlineText: String?) {
        if (loadStarted) return
        loadStarted = true

        viewModelScope.launch {
            val crashes = withContext(Dispatchers.IO) {
                CrashLogStore.consumeAll(getApplication())
            }
            val text = format(crashes, inlineText)
            _state.value = if (text.isEmpty()) State.Empty else State.Loaded(text)
        }
    }

    /**
     * Queues [content] for upload to [url]. The upload itself runs in
     * WorkManager and does not depend on this screen staying open.
     *
     * @return false if a report is already being queued or was queued
     */
    fun submitReport(url: String, content: String): Boolean {
        if (isReporting || reportQueued) return false

        val app = getApplication<Application>()
        reportJob = viewModelScope.launch {
            val queued = try {
                withContext(Dispatchers.IO) {
                    CrashReportUploader.enqueue(app, url, content)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "failed to queue crash report", t)
                false
            }

            reportQueued = queued
            Toast.makeText(
                app,
                if (queued) "报告已提交，将在联网时自动上传" else "报告提交失败，请重试",
                Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    /**
     * Exports [content] as a text file into the public Download directory.
     * Every export creates a new file, so exporting twice is allowed.
     *
     * The caller must already hold WRITE_EXTERNAL_STORAGE on API < 29.
     *
     * @return false if an export is already running
     */
    fun exportLog(content: String): Boolean {
        if (isExporting) return false

        val app = getApplication<Application>()
        exportJob = viewModelScope.launch {
            val location = try {
                withContext(Dispatchers.IO) {
                    CrashLogExporter.export(app, content)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "failed to export crash log", t)
                null
            }

            Toast.makeText(
                app,
                if (location != null) "已导出到 $location" else "导出失败，请重试",
                Toast.LENGTH_LONG
            ).show()
        }
        return true
    }

    // All crashes in one text, newest first, each under a heading with its date
    private fun format(crashes: List<StoredCrash>, inlineText: String?): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            // Only present when the current crash could not be written to disk;
            // it is the newest one, so it goes first
            if (!inlineText.isNullOrEmpty()) {
                appendHeading("Latest crash")
                append(inlineText).append("\n\n")
            }
            for (crash in crashes) {
                val label = crash.type?.label ?: "Crash"
                val times = if (crash.count > 1) "  ×${crash.count}" else ""
                appendHeading("${dateFormat.format(Date(crash.time))}  $label$times")
                append(crash.content).append("\n\n")
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendHeading(title: String) {
        append("========================= ")
          .append(title)
          .append(" =========================\n")
    }

    private companion object {
        const val TAG = "CrashReportViewModel"
    }
}

/**
 * Writes a crash report into the public Download directory
 * (/sdcard/Download), named crash_<yyyyMMdd>_<HHmmss>.txt.
 *
 * API 29+: through MediaStore.Downloads. No permission needed, and the file
 *          belongs to the user, so it survives uninstalling the app.
 * API < 29: a plain file write; needs WRITE_EXTERNAL_STORAGE
 *          (declared with maxSdkVersion="28" in the manifest).
 */
private object CrashLogExporter {

    private const val MIME_TYPE = "text/plain"

    /** @return a human-readable location of the exported file */
    fun export(context: Context, content: String): String {
        val name = "crash_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(context, name, content)
        } else {
            exportToFile(context, name, content)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToMediaStore(context: Context, name: String, content: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until fully written
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")

        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("cannot open $uri for writing")
            out.use { it.write(content.toByteArray(Charsets.UTF_8)) }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            // Do not leave an empty or half-written entry behind
            resolver.delete(uri, null, null)
            throw t
        }

        // MediaStore renames on a name clash ("crash_xxx (1).txt"): report the real name
        val actualName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (t: Throwable) {
            null
        } ?: name
        return "${Environment.DIRECTORY_DOWNLOADS}/$actualName"
    }

    @Suppress("DEPRECATION")
    private fun exportToFile(context: Context, name: String, content: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create ${dir.path}")
        }
        val file = File(dir, name)
        file.writeText(content, Charsets.UTF_8)
        // Make it show up in file managers / over MTP right away
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(MIME_TYPE), null)
        return file.path
    }
}

/**
 * Runs in a separate :crash process (see AndroidManifest), so it can still be
 * shown while the main process is stuck (ANR) or exiting (native crash).
 *
 * Shows ALL unseen crashes at once (current one included), newest first, and
 * deletes their log files as it loads them.
 *
 * Two modes:
 *   - live crash: the main process is dying; closing ends :crash as well
 *   - previous launch: crashes from an earlier run, shown on top of the
 *     running app; closing only finishes this screen
 *
 * All exits (back, close, restart) go through [requestExit], which waits for
 * a report that is still being queued and an export that is still being written.
 */
class CrashActivity : BaseActivity() {
    
    private lateinit var binding: ActivityCrashBinding
    
    private val LOG_TAG = this::class.simpleName

    // Not named `viewModel`: BaseActivity already declares that (a lazy MainViewModel,
    // never touched here, so it is not created in the :crash process)
    private val crashViewModel: CrashReportViewModel by viewModels()

    private var fromPreviousLaunch = false

    // API < 29 only: writing to the public Download directory needs this permission
    private val writePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            exportLog()
        } else {
            Toast.makeText(this, "没有存储权限，无法导出", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fromPreviousLaunch = intent.getBooleanExtra(
            Constants.EXTRA_CRASH_FROM_PREVIOUS_LAUNCH, false
        )
        
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsetsMargin(binding.root)
        
        setSupportActionBar(binding.toolbar)

        onBackPressedDispatcher.addCallback(this) {
            requestExit(CrashReportViewModel.Exit.CLOSE)
        }

        // No-op after the first call, e.g. when recreated by a rotation
        crashViewModel.load(intent.getStringExtra(Constants.EXTRA_CRASH_STACK_TRACE))

        lifecycleScope.launch {
            crashViewModel.state.collect { state ->
                when (state) {
                    is CrashReportViewModel.State.Loading -> Unit
                    // Nothing to show (e.g. already seen in another window)
                    is CrashReportViewModel.State.Empty -> closeNow()
                    is CrashReportViewModel.State.Loaded -> binding.textView.text = state.text
                }
            }
        }

        // Recreated while waiting for a report/export before leaving: keep waiting
        crashViewModel.pendingExit?.let { exitAfterPendingWork(it) }
    }
    
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        // show menu icons
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // The app is already running in previous-launch mode; restarting makes no sense
        menu.findItem(R.id.action_restart)?.isVisible = !fromPreviousLaunch
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.activity_crash, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_report -> bugReport()           
            R.id.action_copy -> copyStackTrace()
            R.id.action_export -> exportLog()
            R.id.action_restart -> requestExit(CrashReportViewModel.Exit.RESTART)
            R.id.action_close -> requestExit(CrashReportViewModel.Exit.CLOSE)
        }
        return true
    }
    
    private fun bugReport() {
        val state = crashViewModel.state.value as? CrashReportViewModel.State.Loaded ?: return
        if (crashViewModel.pendingExit != null) return

        if (!crashViewModel.submitReport(Constants.CRASH_REPORT_URL, state.text)) {
            val message = if (crashViewModel.reportQueued) "报告已提交" else "正在提交报告…"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Export to /sdcard/Download. Also called again once the permission is granted.
    private fun exportLog() {
        val state = crashViewModel.state.value as? CrashReportViewModel.State.Loaded ?: return
        if (crashViewModel.pendingExit != null) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        if (!crashViewModel.exportLog(state.text)) {
            Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun copyStackTrace() {
        val stacktrace = binding.textView.text.toString()
        if (!TextUtils.isEmpty(stacktrace)) {
            val content = ClipData.newPlainText("stacktrace", stacktrace)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(content)
            // Android 13+ shows its own "copied" confirmation; avoid a duplicate
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Leaving the screen. Every exit (back, close, restart) goes through here.
    // ---------------------------------------------------------------------

    private fun requestExit(exit: CrashReportViewModel.Exit) {
        // Already leaving: ignore repeated taps
        if (crashViewModel.pendingExit != null) return
        crashViewModel.pendingExit = exit
        exitAfterPendingWork(exit)
    }

    private fun exitAfterPendingWork(exit: CrashReportViewModel.Exit) {
        lifecycleScope.launch {
            // Both take milliseconds. Once queued, the upload runs in WorkManager
            // and does not need this process; an export must finish writing so
            // that killing the process does not leave a half-written file.
            crashViewModel.reportJob?.join()
            crashViewModel.exportJob?.join()
            when (exit) {
                CrashReportViewModel.Exit.CLOSE -> closeNow()
                CrashReportViewModel.Exit.RESTART -> restartNow()
            }
        }
    }

    private fun closeNow() {
        if (fromPreviousLaunch) {
            // The app is running underneath: just close this screen.
            // finishAffinity() here would close the app's activities too.
            finish()
        } else {
            finishAndKill()
        }
    }
    
    private fun restartNow() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            it.action = "restart"
            startActivity(it)
        }
        finishAndKill()
    }

    // Close the screen and end the :crash process
    private fun finishAndKill() {
        finishAffinity()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
