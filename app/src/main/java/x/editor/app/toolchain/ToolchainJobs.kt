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

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The toolchain installs run in the process, not tied to the screen.
 *
 * Installs used to run in the lifecycleScope of EditorActivity: when the screen was
 * recreated (switching dark mode and the like), the installs into X Editor were
 * cancelled. Now:
 *
 *  - The install itself runs in [scope] here; the screen (ToolchainSheet) only follows it
 *    ([await]). When the screen goes, the coroutine following it is cancelled and simply
 *    stops following: the install goes on.
 *  - A new screen takes over once it is set up (ToolchainSheet.adopt): whatever is still
 *    in [localRuns], including what finished while there was no screen, still has its
 *    result, and the screen taking over reports it as usual.
 *  - Only the user's "Stop", or the entry being removed on save ([cancel]), really stops
 *    an install: one that started unpacking is finished, and what was downloaded is kept
 *    and can be resumed later, see DownloadCache.
 *  - While an install is under way, a foreground service (ToolchainJobsService) keeps the
 *    process alive: after the user leaves the app, the install goes on in the background
 *    with its progress in the notification; when all are done, the service stops.
 *
 * Main thread only, but for what the installs report, which is posted to it.
 */
internal object ToolchainJobs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    /** An install (or update) into X Editor of [pkg]; [hadBefore]: it was installed already. */
    class LocalRun internal constructor(val pkg: ToolPackage, val hadBefore: Boolean) {
        /** What it is doing last told (download, verify, unpack, copy) and how far; null: not yet. */
        var step: String? = null
            internal set
        var percent: Int? = null
            internal set
        internal var listener: ((String, Int?) -> Unit)? = null
        internal val outcome = CompletableDeferred<InstalledPackage>()
        internal var job: Job? = null
        val isDone: Boolean get() = outcome.isCompleted
        /** Why it failed (ended with no sheet to tell: the notification says so); null: not failed. */
        var error: String? = null
            internal set
    }

    private val locals = LinkedHashMap<String, LocalRun>()

    // #region into X Editor

    /**
     * Starts installing [pkg] into X Editor (one going on for it already: that one). It goes
     * on whatever becomes of the screen; [await] to hear how it goes.
     */
    @MainThread
    fun startLocal(context: Context, pkg: ToolPackage, hadBefore: Boolean): LocalRun {
        locals[pkg.id]?.takeIf { !it.isDone }?.let { return it }
        val app = context.applicationContext
        val run = LocalRun(pkg, hadBefore)
        locals[pkg.id] = run
        ToolchainJobsService.ensureStarted(app)
        run.job = scope.launch {
            try {
                val installed = ToolchainInstaller.install(app, pkg) { step, percent ->
                    main.post {
                        if (run.isDone) return@post
                        run.step = step
                        run.percent = percent
                        run.listener?.invoke(step, percent)
                        ToolchainJobsService.refresh()
                    }
                }
                // (no sheet may hear of it now: what reads the toolchain reads it again)
                ToolchainInstaller.noteChanged()
                run.outcome.complete(installed)
            } catch (e: CancellationException) {
                // stopped ([cancel]); unpacked as it was, it is installed all the same
                ToolchainInstaller.noteChanged()
                run.outcome.cancel(e)
                if (locals[pkg.id] === run) locals.remove(pkg.id)
                throw e
            } catch (e: Exception) {
                run.error = e.message ?: e.toString()
                run.outcome.completeExceptionally(e)
            } finally {
                // (after whoever waits for it has heard: posted)
                main.post { ToolchainJobsService.refresh() }
            }
        }
        return run
    }

    /**
     * How [run] ends: what was installed, or why not (IOException, ...); [listener] hears how
     * far it is till then. The caller cancelled (its screen gone): it is only no longer told,
     * the install goes on. [run] stopped ([cancel]): CancellationException.
     */
    suspend fun await(run: LocalRun, listener: (String, Int?) -> Unit): InstalledPackage {
        run.listener = listener
        try {
            return run.outcome.await()
        } finally {
            if (run.listener === listener) run.listener = null
        }
    }

    /** Stops [run] (unpacking, once begun, is done all the same); its job, to wait for. */
    @MainThread
    fun cancel(run: LocalRun): Job? {
        if (locals[run.pkg.id] === run) locals.remove(run.pkg.id)
        run.listener = null
        run.job?.cancel()
        ToolchainJobsService.refresh()
        return run.job
    }

    /** [run]'s end was told (by the sheet that waited for it): it is no longer to be taken over. */
    @MainThread
    fun forget(run: LocalRun) {
        if (locals[run.pkg.id] === run) locals.remove(run.pkg.id)
    }

    /** The installs into X Editor going on, or ended with no sheet to tell it yet. */
    fun localRuns(): List<LocalRun> = locals.values.toList()

    // #endregion

    /** Whether there is something for a sheet to take over ([localRuns]). */
    fun hasAny(): Boolean = locals.isNotEmpty()

    /**
     * Those under way (installs into X Editor not finished yet): the foreground service
     * (ToolchainJobsService) stays only while there are any.
     */
    fun activeLocal(): List<LocalRun> = locals.values.filter { !it.isDone }

    fun isActive(): Boolean = activeLocal().isNotEmpty()

    /** The cache file names (DownloadCache) used by installs into X Editor under way: a cache cleanup must not delete them. */
    fun cacheInUse(): Set<String> =
        locals.values.filter { !it.isDone }.flatMap { ToolchainInstaller.cacheNamesOf(it.pkg) }.toSet()
}
