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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import x.editor.app.R
import x.editor.app.activity.EditorActivity

/**
 * A foreground service that keeps the process alive while toolchain installs are under way.
 *
 * The installs themselves run in ToolchainJobs, not tied to the screen. But once the user
 * leaves the app, the process becomes a cached process: Android freezes it (network reads
 * and writes stall) and kills it when memory runs low, so a download of several hours could
 * never finish. The foreground service keeps the process at foreground priority, with the
 * progress in the notification; tapping the notification returns to the editor, where the
 * small window at the top right goes on showing it (ToolchainSheet.adopt).
 *
 *  - Started when the first install starts ([ensureStarted]; the app is in the foreground
 *    then, so a foreground service may be started).
 *  - The notification is updated on progress, start and finish ([refresh], at most once
 *    every [UPDATE_MILLIS]).
 *  - Stops when nothing is under way any more. If no screen takes the result of finished
 *    installs (the app was left), a separate result notification (dismissable) is posted,
 *    and the small window shows it too the next time the app is opened.
 *  - From Android 15, a foreground service of type dataSync may run at most 6 hours a day;
 *    then the system calls [onTimeout]: the service stops, and the installs go on (until
 *    the process is reclaimed).
 *  - From Android 13, without the notification permission the notification is not shown,
 *    but the service still keeps the process alive.
 */
class ToolchainJobsService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var updatePosted = false
    private var lastUpdate = 0L
    // The latest start request: stopping stops only up to it (if another start request came
    // after it, the service stays)
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        instance = this
        lastStartId = startId
        // After startForegroundService it must go to the foreground first, even if the installs
        // are already done (otherwise the system crashes the app)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildProgress(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Not allowed into the foreground (background start restrictions, the quota of the
            // type used up): the installs go on, just without keeping the process alive
            Log.w(TAG, "cannot go to the foreground", e)
            if (instance === this) instance = null
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!ToolchainJobs.isActive()) finish()
        // If the process is killed the installs are gone too: the system must not restart this service
        return START_NOT_STICKY
    }

    // Android 15+: the time allowed for dataSync is used up; it must stop within seconds
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "the time for a data sync service is used up: the installs go on unguarded")
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    // Updates the progress notification (coalesced to at most once every UPDATE_MILLIS)
    private fun scheduleUpdate() {
        if (updatePosted) return
        val wait = (lastUpdate + UPDATE_MILLIS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        updatePosted = true
        handler.postDelayed({
            updatePosted = false
            if (instance !== this) return@postDelayed
            if (!ToolchainJobs.isActive()) {
                finish()
                return@postDelayed
            }
            lastUpdate = SystemClock.uptimeMillis()
            notify(NOTIFICATION_ID, buildProgress())
        }, wait)
    }

    // All done (or stopped by the system): the service stops; results no screen took get a notification
    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        updatePosted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (instance === this) instance = null
        stopSelf(lastStartId)
        buildResult()?.let { notify(RESULT_ID, it) }
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            // No notification permission (Android 13+): not shown, everything else as usual
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, EditorActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // Under way: a line for each (name, what it is doing, percent), and the overall progress bar
    private fun buildProgress(): android.app.Notification {
        ensureChannel(this)
        val locals = ToolchainJobs.activeLocal()
        val lines = ArrayList<String>()
        val parts = ArrayList<Int?>()
        for (run in locals) {
            val step = when (run.step) {
                null -> getString(R.string.toolchain_installing)
                "download" -> getString(R.string.toolchain_step_download)
                "verify" -> getString(R.string.toolchain_step_verify)
                "unpack" -> getString(R.string.toolchain_step_unpack)
                else -> getString(R.string.toolchain_step_copy)
            }
            lines += "${run.pkg.name} · $step" + (run.percent?.let { " $it%" } ?: "")
            parts += run.percent
        }
        val names = locals.map { it.pkg.name }
        val title = when (names.size) {
            0 -> getString(R.string.toolchain_notify_title_none)
            1 -> getString(R.string.toolchain_notify_title_one, names.first())
            else -> getString(R.string.toolchain_notify_title_many, names.size)
        }
        val percent = ToolchainRules.overallPercent(parts)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_toolchain)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .build()
    }

    // The installs that finished but whose result no screen took (still in ToolchainJobs,
    // waiting for a screen to take over); null when there are none
    private fun buildResult(): android.app.Notification? {
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        for (run in ToolchainJobs.localRuns()) {
            if (!run.isDone) continue
            if (run.error == null) done += run.pkg.name else failed += run.pkg.name
        }
        if (done.isEmpty() && failed.isEmpty()) return null
        ensureChannel(this)
        val lines = ArrayList<String>()
        if (done.isNotEmpty()) lines += getString(R.string.toolchain_notify_done, done.joinToString("、"))
        if (failed.isNotEmpty()) lines += getString(R.string.toolchain_notify_failed, failed.joinToString("、"))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_toolchain)
            .setContentTitle(getString(if (failed.isEmpty()) R.string.toolchain_notify_result_title else R.string.toolchain_notify_result_title_failed))
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val TAG = "ToolchainJobsService"
        private const val CHANNEL_ID = "toolchain"
        private const val NOTIFICATION_ID = 0x7c01
        private const val RESULT_ID = 0x7c02
        private const val UPDATE_MILLIS = 1000L

        private var instance: ToolchainJobsService? = null
        // a start was requested, and onStartCommand has not come yet
        private var starting = false

        /** An install started: starts the service if it is not running (call while the app is in the foreground). */
        @MainThread
        fun ensureStarted(context: Context) {
            val running = instance
            if (running != null) {
                running.scheduleUpdate()
                return
            }
            if (starting) return
            val app = context.applicationContext
            // a new batch started: the result notification of the last one is of no use any more
            NotificationManagerCompat.from(app).cancel(RESULT_ID)
            try {
                starting = true
                ContextCompat.startForegroundService(app, Intent(app, ToolchainJobsService::class.java))
            } catch (e: Exception) {
                // The app is not in the foreground (ForegroundServiceStartNotAllowedException) or
                // the like: the installs go on, just without keeping the process alive
                Log.w(TAG, "cannot start the service", e)
                starting = false
            }
        }

        /** Progress, start or finish of an install: the notification follows; the service stops when all are done. */
        @MainThread
        fun refresh() {
            instance?.scheduleUpdate()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.toolchain_notify_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.toolchain_notify_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }
}
