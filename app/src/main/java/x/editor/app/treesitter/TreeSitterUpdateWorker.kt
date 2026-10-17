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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

import java.io.IOException
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
 */
class TreeSitterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val manifest = TreeSitterResources.manifest(context)
            val metered = context.getSystemService(ConnectivityManager::class.java)
                ?.isActiveNetworkMetered ?: true

            var waitsForWifi = false
            for (update in TreeSitterUpdater.findUpdates(context)) {
                val size = update.remote.length ?: Long.MAX_VALUE
                // what is missing is needed now; an update of a large package can wait
                if (metered && !update.isMissing && size > manifest.meteredMaxBytes) {
                    waitsForWifi = true
                    continue
                }
                TreeSitterUpdater.download(context, update)
            }
            // what was not installed goes in at once; the rest at the next start
            TreeSitterUpdater.applyStaged(context)

            // checked; a large update left for Wi-Fi is checked again then
            if (!waitsForWifi) TreeSitterUpdater.markChecked(context)
            if (waitsForWifi) scheduleOnUnmetered(context)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // the connection went, say: again later (a download is continued)
            Log.w(TAG, "update failed, retrying later", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "update failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "TreeSitterUpdateWorker"

        private const val PERIODIC = "treesitter-update-periodic"
        private const val SOON = "treesitter-update-soon"
        private const val ON_WIFI = "treesitter-update-unmetered"
        private const val NOW = "treesitter-update-now"

        // after the app is opened: not while it starts up
        private const val SOON_DELAY_SECONDS = 20L

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

        /** Checks now (as soon as there is a connection), whatever the time of the last check. */
        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TreeSitterUpdateWorker>()
                    .setConstraints(constraints(NetworkType.CONNECTED))
                    .build()
            )
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
