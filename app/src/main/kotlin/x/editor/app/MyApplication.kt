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

package x.editor.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log

import androidx.work.Configuration
import androidx.work.WorkManager

import com.google.android.material.color.DynamicColors

import java.io.File

import kotlin.system.exitProcess

import x.github.module.crash.AnrException
import x.github.module.crash.CrashHandler
import x.github.module.crash.CrashLog
import x.github.module.crash.CrashLogStore
import x.github.module.crash.OnExceptionListener

class MyApplication : Application() {

    companion object {
        private const val TAG = "MyApplication"

        // The Binder transaction buffer is ~1MB (shared by the whole process);
        // an oversized stack trace would throw TransactionTooLargeException
        private const val MAX_STACK_TRACE_CHARS = 100_000
    }

    private var isCrashProcess = false
    
    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)

        // CrashActivity runs in the :crash process, which also gets here;
        // crash capture must not be installed there
        isCrashProcess = detectCrashProcess()
        if (isCrashProcess) {
            return@attachBaseContext
        }

        // Every callback receives a log that is already on disk
        CrashHandler.install(this, object : OnExceptionListener {

            // Do NOT exit the process here: after this returns, CrashHandler hands
            // the exception to the system's default handler, which records the
            // crash (Android vitals, ApplicationExitInfo) and kills the process.
            // It does so even if this callback throws.
            override fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog) {
                startCrashActivity(log)
            }

            // Do NOT exit the process here: after this returns, the native layer
            // hands the signal to debuggerd to write a tombstone, and the process
            // then dies on its own.
            override fun onNativeCrash(signum: Int, message: String, log: CrashLog) {
                startCrashActivity(log)
            }

            // Invoked on the native anr_report thread while the main thread is stuck
            override fun onAnrCrash(thread: Thread, error: AnrException, log: CrashLog) {
                try {
                    startCrashActivity(log)
                } finally {
                    killSelf()
                }
            }
        })
    }
    
    override fun onCreate() {
        super.onCreate()

        // Needed in both the main and :crash processes so CrashActivity uses the same theme
        DynamicColors.applyToActivitiesIfAvailable(this)

        AppSettings.applyAppTheme(getApplicationContext())

        if (isCrashProcess) {
            initWorkManagerForCrashProcess()
        } else {
            showPendingCrashOnFirstActivity()
        }
    }

    // WorkManager's default initializer is a ContentProvider, which only runs in
    // the main process. CrashActivity queues uploads from :crash, so initialize
    // it here. With the main process as the default process, :crash only
    // records the work; it is EXECUTED in the main process (started by the
    // system job scheduler if needed), so an upload survives :crash being killed.
    private fun initWorkManagerForCrashProcess() {
        try {
            WorkManager.initialize(
                this,
                Configuration.Builder()
                    .setDefaultProcessName(packageName)
                    .build()
            )
        } catch (e: IllegalStateException) {
            // Already initialized
        }
    }

    // A crash that happened while the app was in the background could not open
    // CrashActivity (Android 10+ blocks background activity starts), but its log
    // is still on disk. Once the user opens the app again, show ALL unseen
    // crashes in a single CrashActivity.
    private fun showPendingCrashOnFirstActivity() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Only check once per process
                unregisterActivityLifecycleCallbacks(this)

                if (!CrashLogStore.hasLogs(this@MyApplication)) return

                // No NEW_TASK / CLEAR_TASK: open on top of the running app, not instead of it.
                // No log path: CrashActivity reads every unseen log from disk.
                val intent = Intent(Constants.ACTION_CRASH_REPORT).apply {
                    setPackage(packageName)
                    putExtra(Constants.EXTRA_CRASH_FROM_PREVIOUS_LAUNCH, true)
                }
                try {
                    activity.startActivity(intent)
                } catch (e: Throwable) {
                    Log.e(TAG, "failed to show pending crash log", e)
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    // Build a new Intent every time: several threads may crash at once, and a
    // shared Intent would let them overwrite each other's extras
    private fun startCrashActivity(log: CrashLog) {
        try {
            val intent = Intent(Constants.ACTION_CRASH_REPORT).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // Normally the log is on disk and CrashActivity reads it from there,
                // together with any other unseen crashes. Pass the text inline only
                // if it could not be written.
                if (log.file == null) {
                    putExtra(Constants.EXTRA_CRASH_STACK_TRACE, log.content.take(MAX_STACK_TRACE_CHARS))
                }
            }
            startActivity(intent)
        } catch (e: Throwable) {
            // If the log is on disk, it will be shown on the next launch
            Log.e(TAG, "failed to start CrashActivity", e)
        }
    }

    private fun killSelf() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    private fun detectCrashProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000')
            }.getOrDefault("")
        }
        return processName.endsWith(":crash")
    }
}
