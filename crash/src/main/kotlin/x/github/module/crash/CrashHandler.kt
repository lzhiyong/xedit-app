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
package x.github.module.crash

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep

import dalvik.annotation.optimization.FastNative
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Crash callbacks. Each one receives a [CrashLog] that has ALREADY been
 * persisted by [CrashLogStore] (unless writing failed, in which case
 * [CrashLog.file] is null), so the crash is kept even if the listener fails.
 */
interface OnExceptionListener {

    // Java crash: an uncaught exception on any thread, the main thread included.
    // Invoked on the crashing thread, at most once per process.
    //
    // After this returns (or throws), the exception is handed to the previous
    // default handler - normally the platform's - which reports the crash to
    // the system (Android vitals, ApplicationExitInfo, other crash SDKs) and
    // kills the process. So do NOT exit the process here, and return quickly.
    fun onJavaCrash(thread: Thread, throwable: Throwable, log: CrashLog)

    // Native crash: invoked on the native crash_report thread
    fun onNativeCrash(signum: Int, message: String, log: CrashLog)

    // ANR callback, invoked on the native anr_report thread while the main thread
    // is stuck. Do not touch the UI or wait for the main thread here.
    // Forwards to onJavaCrash by default; override as needed.
    fun onAnrCrash(thread: Thread, error: AnrException, log: CrashLog) {
        onJavaCrash(thread, error, log)
    }
}

// ANR exception; its stack trace is the main thread's stack at the moment SIGQUIT arrived
class AnrException(message: String, mainThreadStack: Array<StackTraceElement>) :
    RuntimeException(message) {
    init {
        stackTrace = mainThreadStack
    }
}

@Keep
public object CrashHandler {

    private const val TAG = "CrashHandler"

    // Max time to spend confirming an ANR after SIGQUIT, and the polling interval
    private const val ANR_CONFIRM_TIMEOUT_MS = 5000L
    private const val ANR_CONFIRM_INTERVAL_MS = 500L

    // Directory (under filesDir) where the native layer writes its fallback reports
    private const val FALLBACK_DIR_NAME = "native_crash"

    private lateinit var listener: OnExceptionListener
    private var application: Application? = null

    // The handler set by the current install(); null while not installed
    private var javaCrashHandler: JavaCrashHandler? = null

    // Only the first Java crash of the process reaches the listener
    private val javaCrashNotified = AtomicBoolean(false)

    // Read and written on the main thread only
    private var anrMonitorEnabled = false

    // Read and written on the anr_report thread only
    @Volatile
    private var anrStackSnapshot: Array<StackTraceElement>? = null

    @Volatile
    private var isInstalled: Boolean = false

    // Set once nativeSetFallbackDir() succeeded; null means the fallback is off
    @Volatile
    private var fallbackDir: File? = null
    
    private val mutex: Any = Any()
    
    init {
        System.loadLibrary("crash_handler")
    }

    // ---------------------------------------------------------------------
    // Test APIs - no-ops unless the app is debuggable
    // ---------------------------------------------------------------------

    // Test APIs only work in debuggable builds (android:debuggable, i.e. debug
    // variants), so they can never crash a release build by accident.
    // A runtime check is used instead of BuildConfig.DEBUG because library
    // modules do not generate BuildConfig by default since AGP 8.
    private fun testsAllowed(name: String): Boolean {
        val app = application
        val allowed = app != null &&
            (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!allowed) {
            Log.w(TAG, "$name() ignored: test APIs require a debuggable build and CrashHandler.install()")
        }
        return allowed
    }

    /**
     * Triggers a real ANR without touching the screen.
     *
     * A foreground ordered broadcast is delivered to a receiver whose onReceive()
     * deadlocks the main thread on a lock held by another thread. Ordered
     * broadcasts are subject to the broadcast timeout, so the system declares an
     * ANR on its own after about 10 seconds (it may be a little longer on
     * Android 14+). Tapping the screen still triggers an input ANR sooner.
     *
     * Call it while the app is in the foreground: a background ANR is killed
     * silently and cannot show CrashActivity.
     */
    public fun testAnrCrash() {
        if (!testsAllowed("testAnrCrash")) return
        val app = application ?: return

        // Hold the lock on another thread first, so the receiver is guaranteed to block
        val lockHeld = CountDownLatch(1)
        Thread({
            synchronized(mutex) {
                lockHeld.countDown()
                while (true) {
                    try {
                        Thread.sleep(60_000L)
                    } catch (e: InterruptedException) {
                        // keep holding the lock
                    }
                }
            }
        }, "anr-test-lock-holder").start()
        lockHeld.await(1, TimeUnit.SECONDS)

        val action = app.packageName + ".CRASH_HANDLER_ANR_TEST"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Runs on the main thread and blocks forever
                synchronized(mutex) {
                    throw IllegalStateException("Shouldn't happen")
                }
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }

        val intent = Intent(action)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        // Must be ORDERED: normal broadcasts to dynamic receivers have no timeout
        app.sendOrderedBroadcast(intent, null)
    }
    
    // Throws a RuntimeException on the calling thread
    public fun testJavaCrash() {
        if (!testsAllowed("testJavaCrash")) return
        throw RuntimeException("java crash test, you can ignore it.")
    }

    // Dereferences a null pointer in native code
    public fun testNativeCrash() {
        if (!testsAllowed("testNativeCrash")) return
        nativeTestCrash()
    }

    // ---------------------------------------------------------------------
    // Callbacks from native code
    // ---------------------------------------------------------------------
    
    // Native crash callback, called by JNI on the crash_report thread.
    //
    // fallbackName is the native write-ahead copy of this report inside
    // fallbackDir ("" or null if none was written). Order matters:
    //   1. persist our own log
    //   2. only if that succeeded, delete the native copy - BEFORE the listener,
    //      so a listener that throws or hangs cannot leave a duplicate behind
    //   3. notify the listener
    // If step 1 fails (e.g. disk full) the native copy is kept and adopted on
    // the next launch, so the crash is never lost.
    @JvmStatic
    @Deprecated(
        "The method can't be called directly.", 
        level = DeprecationLevel.HIDDEN
    )
    private fun callback(signum: Int, message: String, fallbackName: String?) {
        val log = CrashLogStore.write(
            CrashType.NATIVE, null, "Signal: $signum\n$message",
            CrashSignature.ofNative(signum, message)
        )

        if (log.file != null && !fallbackName.isNullOrEmpty()) {
            try {
                fallbackDir?.let { File(it, fallbackName).delete() }
            } catch (t: Throwable) {
                // A leftover copy is merely adopted again later (deduplicated by signature)
                Log.w(TAG, "failed to delete native fallback report $fallbackName", t)
            }
        }

        // Not installed yet (crash inside install()) or already uninstalled: the log
        // is saved, but there is no listener to notify
        if (!isInstalled) return
        listener.onNativeCrash(signum, message, log)
    }

    // Called by native code (anr_report thread) when SIGQUIT arrives, BEFORE it is
    // forwarded to Signal Catcher. Does one thing only: snapshot the main thread's
    // stack as early as possible, since a background ANR may be killed right after
    // the system finishes its dump.
    @JvmStatic
    @Deprecated(
        "The method can't be called directly.",
        level = DeprecationLevel.HIDDEN
    )
    private fun onAnrSignal() {
        anrStackSnapshot = Looper.getMainLooper().thread.stackTrace
    }

    // Called by native code (anr_report thread) AFTER SIGQUIT has been forwarded
    // to Signal Catcher. Invokes onAnrCrash only once the ANR is confirmed to
    // belong to this process.
    @JvmStatic
    @Deprecated(
        "The method can't be called directly.",
        level = DeprecationLevel.HIDDEN
    )
    private fun onAnrConfirm() {
        val stack = anrStackSnapshot ?: return
        anrStackSnapshot = null

        val app = application ?: return
        if (!isInstalled) return

        val reason = waitForAnrConfirmation(app)
        if (reason == null) {
            Log.i(TAG, "SIGQUIT ignored: not an ANR of this process, or the main thread recovered")
            return
        }
        Log.i(TAG, "ANR confirmed: $reason")

        val mainThread = Looper.getMainLooper().thread
        val error = AnrException("ANR: $reason", stack)
        val log = CrashLogStore.write(
            CrashType.ANR, mainThread.name, error.stackTraceToString(),
            CrashSignature.ofAnr(stack)
        )
        listener.onAnrCrash(mainThread, error, log)
    }

    // Returns the ANR reason, or null if this is not our ANR or the main thread has recovered
    private fun waitForAnrConfirmation(context: Context): String? {
        // Post at the front of the queue so we measure whether the main thread is
        // stuck, not how long its message backlog is
        val responded = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).postAtFrontOfQueue { responded.set(true) }

        val deadline = SystemClock.uptimeMillis() + ANR_CONFIRM_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            // Main thread is responsive: a collateral dump for another app's ANR,
            // `adb shell kill -3`, or the main thread recovered. Don't report.
            if (responded.get()) return null

            // The system has marked this process NOT_RESPONDING, so its trace dump is done too
            findAnrReason(context)?.let { return it }

            try {
                Thread.sleep(ANR_CONFIRM_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return null
            }
        }

        // Some ROMs never expose an error state: a main thread that stays
        // unresponsive is treated as an ANR as well
        return if (responded.get()) null else "main thread not responding after SIGQUIT"
    }

    private fun findAnrReason(context: Context): String? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val states = try {
            am.processesInErrorState
        } catch (t: Throwable) {
            null
        } ?: return null

        val pid = Process.myPid()
        return states.firstOrNull {
            it.pid == pid &&
                it.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
        }?.let { it.shortMsg ?: "Application Not Responding" }
    }

    /**
     * Default uncaught exception handler: records the crash, notifies the
     * listener, then ALWAYS passes the exception on to [previous] - normally
     * the platform's handler, which reports it to ActivityManager (so Android
     * vitals, ApplicationExitInfo REASON_CRASH and other crash SDKs see it)
     * and kills the process.
     *
     * Main-thread exceptions arrive here too: the exception propagates out of
     * Looper.loop() in ActivityThread.main() and the runtime dispatches it to
     * the default handler on the main thread.
     */
    private class JavaCrashHandler(
        val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        // Cleared by uninstall(). If another SDK has wrapped this handler since,
        // it cannot be unhooked, so it stays in that chain and only forwards.
        @Volatile
        var active = true

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                if (active) handleJavaCrash(thread, throwable)
            } catch (t: Throwable) {
                // Never let our own failure stop the exception reaching the system
                Log.e(TAG, "failed to handle java crash", t)
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    // No platform handler (unusual): end the process like it would
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    // Persist first, then notify the listener (first crash only)
    private fun handleJavaCrash(thread: Thread, throwable: Throwable) {
        val log = CrashLogStore.write(
            CrashType.JAVA, thread.name, throwable.stackTraceToString(),
            CrashSignature.of(throwable)
        )
        // Several threads may crash at nearly the same time. Each crash is
        // saved, but only the first one opens CrashActivity, which reads every
        // saved log anyway.
        if (javaCrashNotified.compareAndSet(false, true)) {
            listener.onJavaCrash(thread, throwable, log)
        }
    }

    // ---------------------------------------------------------------------
    // Install / uninstall
    // ---------------------------------------------------------------------

    /**
     * Installs crash capture for this process. Call it once, as early as
     * possible (Application.attachBaseContext), on the main thread, and NOT in
     * the :crash process. Calling it again while installed does nothing.
     *
     * It hooks process-wide state:
     *  - the default [Thread.UncaughtExceptionHandler] (Java crashes on every
     *    thread, chained to the previous handler so the system still sees them)
     *  - SIGQUIT, if [anrMonitor] is true (ANRs; no polling in normal operation)
     *  - the native fallback directory, after adopting reports left by an
     *    earlier run
     *
     * Native signal handlers are already active once the native library is
     * loaded (first access to [CrashHandler]); install only connects them to
     * [listener].
     */
    fun install(
        app: Application,
        exListener: OnExceptionListener,
        anrMonitor: Boolean = true
    ) {
        if (isInstalled) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CrashHandler.install() must be called on the main thread"
        }

        // Ready the store before any crash can be reported
        CrashLogStore.init(app)

        // Native crashes the previous run could not save on the Kotlin side:
        // convert them into regular logs BEFORE the fallback is switched on, so
        // they are shown/uploaded like any other crash
        val nativeFallbackDir = File(app.filesDir, FALLBACK_DIR_NAME)
        CrashLogStore.adoptNativeFallbacks(nativeFallbackDir)
        if (nativeSetFallbackDir(nativeFallbackDir.path)) {
            fallbackDir = nativeFallbackDir
        } else {
            Log.w(TAG, "native fallback report is unavailable")
        }

        listener = exListener
        application = app
        isInstalled = true

        // Java crashes on every thread, the main thread included, go through
        // the default handler and are then handed to the system. The main
        // Looper is not wrapped: a crash is never swallowed to keep running.
        val handler = JavaCrashHandler(Thread.getDefaultUncaughtExceptionHandler())
        javaCrashHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)

        if (anrMonitor) {
            anrMonitorEnabled = nativeEnableAnrMonitor()
            if (anrMonitorEnabled) {
                Log.i(TAG, "SIGQUIT ANR monitor enabled")
            } else {
                Log.w(TAG, "SIGQUIT ANR monitor is unavailable on this device")
            }
        }
    }

    /**
     * Undoes [install]: the listener is no longer called, Java crashes go
     * straight to the system, and the ANR monitor stops. Must be called on
     * the main thread. Does nothing if not installed; [install] may be called
     * again afterwards.
     *
     * Native signal handlers stay registered: other SDKs may have chained
     * theirs on top since, and removing ours would break that chain. A native
     * crash after uninstall is therefore still SAVED to disk (and shown on the
     * next launch), just not reported to the listener.
     */
    fun uninstall() {
        if (!isInstalled) return
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "CrashHandler.uninstall() must be called on the main thread"
        }

        isInstalled = false

        if (anrMonitorEnabled) {
            nativeDisableAnrMonitor()
            anrMonitorEnabled = false
        }

        javaCrashHandler?.let { handler ->
            handler.active = false
            // Restore the previous handler only if ours is still the installed
            // one. If another SDK has wrapped it since, unhooking here would
            // cut that SDK out too; ours stays in its chain and just forwards.
            if (Thread.getDefaultUncaughtExceptionHandler() === handler) {
                Thread.setDefaultUncaughtExceptionHandler(handler.previous)
            }
        }
        javaCrashHandler = null
    }

    // ---------------------------------------------------------------------
    // Natives
    // ---------------------------------------------------------------------

    @JvmStatic
    @FastNative
    private external fun nativeTestCrash()
    
    /**
     * Sets the directory where the native layer writes its fallback crash report.
     *
     * On a native crash, the reporter thread writes `native_<epoch_ms>_<pid>.log`
     * into this directory before calling [callback], and passes the file name to
     * it. [callback] deletes the file once [CrashLogStore] has saved its own log.
     * Any file still present at the next launch is a crash the Kotlin side failed
     * to record; [install] converts it with [CrashLogStore.adoptNativeFallbacks].
     *
     * Called from [install]. The directory is created if missing, and an fd to it
     * is kept for the crash path. Calling again switches to the new directory.
     *
     * @param path absolute path of the directory, e.g. `File(filesDir, "native_crash").path`
     * @return `true` if the directory is ready, `false` if it could not be created or
     *   opened (the fallback is then disabled; crash capture itself still works)
     */
    @JvmStatic
    private external fun nativeSetFallbackDir(path: String): Boolean
    
    // Must be called on the main thread: signal masks are per-thread, and only
    // the main thread gets SIGQUIT unblocked
    @JvmStatic
    private external fun nativeEnableAnrMonitor(): Boolean

    @JvmStatic
    private external fun nativeDisableAnrMonitor()
    
}
