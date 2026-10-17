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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast

import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch

import kotlin.system.exitProcess

import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.databinding.ActivityCrashBinding
import x.editor.app.viewmodel.CrashReportViewModel

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
 *
 * What survives a rotation lives in [CrashReportViewModel].
 */
class CrashActivity : BaseActivity() {
    
    private lateinit var binding: ActivityCrashBinding

    // BaseActivity already declares `editorViewModel` (a lazy EditorViewModel,
    // never touched here, so it is not created in the :crash process)
    private val crashViewModel: CrashReportViewModel by viewModels()

    private var isFromPreviousLaunch = false

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

        isFromPreviousLaunch = intent.getBooleanExtra(
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
        menu.findItem(R.id.action_restart)?.isVisible = !isFromPreviousLaunch
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.activity_crash, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_report -> submitReport()           
            R.id.action_copy -> copyStackTrace()
            R.id.action_export -> exportLog()
            R.id.action_restart -> requestExit(CrashReportViewModel.Exit.RESTART)
            R.id.action_close -> requestExit(CrashReportViewModel.Exit.CLOSE)
        }
        return true
    }
    
    private fun submitReport() {
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
        if (isFromPreviousLaunch) {
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
