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

package x.editor.app.treesitter

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log

import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.CancellationException

/**
 * Checks for updates of the tree-sitter resources in the background, downloads them
 * and stages them (see TreeSitterUpdater); they go in at the next start. Also
 * installs what is missing, when the first start had no connection.
 *
 * When it runs ([schedule]):
 *  - every few hours (the manifest says how many), whenever there is a network
 *    and the battery is not low; the system picks the moment;
 *  - shortly after the app is opened, if the last check is older than that, so a
 *    phone that was offline for a while catches up soon.
 *
 * A large update waits for a network that is not metered (Wi-Fi); the check itself
 * and small packages (up to `meteredMaxBytes` of the manifest) do not.
 *
 * A check the user asked for ([checkNow], "check for updates" in the resources status)
 * is forced: it downloads what it finds on any network and whatever the battery, says
 * how far it got ([KEY_PACKAGE], [KEY_PROGRESS]) and what it came to ([KEY_FOUND],
 * [KEY_DOWNLOADED], [KEY_ERROR]), and a failure is told rather than retried later in
 * silence. What it does goes to the console too (the X Editor channel).
 */
class TreeSitterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val forced = inputData.getBoolean(KEY_FORCED, false)
        var found = 0
        var downloaded = 0
        return try {
            val manifest = TreeSitterResources.manifest(context)
            val metered = context.getSystemService(ConnectivityManager::class.java)
                ?.isActiveNetworkMetered ?: true

            var waitsForWifi = false
            val updates = TreeSitterUpdater.findUpdates(context)
            found = updates.size
            for (update in updates) {
                val size = update.remote.length ?: Long.MAX_VALUE
                // what is missing is needed now; an update of a large package can wait,
                // unless the user asked for it
                if (!forced && metered && !update.isMissing && size > manifest.meteredMaxBytes) {
                    TreeSitterUpdater.log("${update.pkg.id}: ${size / 1024} KB on a metered network, waits for Wi-Fi")
                    waitsForWifi = true
                    continue
                }
                TreeSitterUpdater.log("${update.pkg.id}: downloading ${update.pkg.url}")
                TreeSitterUpdater.download(context, update) { percent ->
                    setProgressAsync(
                        Data.Builder()
                            .putString(KEY_PACKAGE, update.pkg.id)
                            .putInt(KEY_PROGRESS, percent)
                            .build()
                    )
                }
                downloaded++
                TreeSitterUpdater.log("${update.pkg.id}: downloaded and staged")
            }
            // what was not installed goes in at once; the rest at the next start
            TreeSitterUpdater.applyStaged(context)

            // checked; a large update left for Wi-Fi is checked again then
            if (!waitsForWifi) TreeSitterUpdater.markChecked(context)
            if (waitsForWifi) scheduleOnUnmetered(context)
            TreeSitterUpdater.log("check done: $found to update, $downloaded downloaded")
            Result.success(outcome(found, downloaded, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            TreeSitterUpdater.log("update failed: ${e.message ?: e}", error = true)
            if (forced) {
                // asked for now: told, not tried again in silence (a download is
                // continued the next time)
                Log.w(TAG, "update failed", e)
                Result.failure(outcome(found, downloaded, e.message ?: e.toString()))
            } else {
                // the connection went, say: again later (a download is continued)
                Log.w(TAG, "update failed, retrying later", e)
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "update failed", e)
            TreeSitterUpdater.log("update failed: ${e.message ?: e}", error = true)
            Result.failure(outcome(found, downloaded, e.message ?: e.toString()))
        }
    }

    private fun outcome(found: Int, downloaded: Int, error: String?): Data =
        Data.Builder()
            .putInt(KEY_FOUND, found)
            .putInt(KEY_DOWNLOADED, downloaded)
            .putString(KEY_ERROR, error)
            .build()

    companion object {
        private const val TAG = "TreeSitterUpdateWorker"

        private const val PERIODIC = "treesitter-update-periodic"
        private const val SOON = "treesitter-update-soon"
        private const val ON_WIFI = "treesitter-update-unmetered"
        private const val NOW = "treesitter-update-now"

        // after the app is opened: not while it starts up
        private const val SOON_DELAY_SECONDS = 20L

        private const val KEY_FORCED = "forced"

        /** Progress of a forced check: the package being downloaded, and how far (0..100). */
        const val KEY_PACKAGE = "package"
        const val KEY_PROGRESS = "progress"

        /** Outcome of a check: packages to update, how many were downloaded, why it failed. */
        const val KEY_FOUND = "found"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_ERROR = "error"

        private fun constraints(network: NetworkType) = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /**
         * Schedules the checks: the periodic one (kept if it is scheduled already),
         * and one soon if the last check is due. Cheap; call when the app is opened.
         */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val hours = TreeSitterResources.manifest(context).checkIntervalHours.coerceAtLeast(1)

            workManager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TreeSitterUpdateWorker>(hours, TimeUnit.HOURS)
                    .setConstraints(constraints(NetworkType.CONNECTED))
                    .build()
            )

            if (TreeSitterUpdater.isCheckDue(context)) {
                workManager.enqueueUniqueWork(
                    SOON,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<TreeSitterUpdateWorker>()
                        .setInitialDelay(SOON_DELAY_SECONDS, TimeUnit.SECONDS)
                        .setConstraints(constraints(NetworkType.CONNECTED))
                        .build()
                )
            }
        }

        /**
         * Checks now (as soon as there is a connection), whatever the time of the last
         * check, and downloads what it finds on any network, whatever the battery: the
         * user asked for it. The id of the work, to follow it (see the KEY_ constants).
         */
        fun checkNow(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<TreeSitterUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(Data.Builder().putBoolean(KEY_FORCED, true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
            return request.id
        }

        // a large update found on a metered network: as soon as there is Wi-Fi
        private fun scheduleOnUnmetered(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_WIFI,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TreeSitterUpdateWorker>()
                    .setConstraints(constraints(NetworkType.UNMETERED))
                    .build()
            )
        }
    }
}
