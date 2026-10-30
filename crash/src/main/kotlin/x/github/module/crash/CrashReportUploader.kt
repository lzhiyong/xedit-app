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

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Uploads crash reports in the background with WorkManager, so an upload
 * survives the user leaving the screen, the process being killed, and the
 * device being offline (it waits for a network connection).
 *
 * The report is sent as an HTTP POST with a text/plain UTF-8 body.
 *
 * Staging: WorkManager input Data is limited to 10 KB, so the content is
 * written to noBackupFilesDir/crash_reports and only its path is passed to
 * the worker. That file is an upload queue entry, not a crash log: the worker
 * deletes it once the upload succeeds or is abandoned.
 *
 * WorkManager must be initialized in the calling process. Its default
 * initializer only runs in the main process; see MyApplication for the
 * :crash process.
 */
object CrashReportUploader {

    private const val TAG = "CrashReportUploader"

    internal const val KEY_URL = "url"
    internal const val KEY_FILE = "file"

    const val WORK_TAG = "crash-report-upload"

    private const val DIR_NAME = "crash_reports"

    // Staged files older than this belong to work that can no longer run
    private const val STALE_STAGED_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Queues [content] for upload to [url].
     *
     * Blocks until the work is persisted by WorkManager, so the caller may end
     * its process right after this returns. Call it off the main thread.
     *
     * @throws IllegalArgumentException if [url] is not http(s)
     * @throws Exception if the content could not be staged or the work queued
     */
    @WorkerThread
    fun enqueue(context: Context, url: String, content: String) {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "Unsupported upload url: $url"
        }
        val appContext = context.applicationContext

        val dir = stagingDir(appContext)
        pruneStale(dir)

        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "report_${System.currentTimeMillis()}_${UUID.randomUUID()}.txt")
        file.writeText(content)

        val request = OneTimeWorkRequestBuilder<CrashReportWorker>()
            .setInputData(workDataOf(KEY_URL to url, KEY_FILE to file.absolutePath))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        try {
            // get(): wait until the work is actually written to WorkManager's database
            WorkManager.getInstance(appContext).enqueue(request).result.get()
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    internal fun stagingDir(context: Context): File =
        File(context.noBackupFilesDir, DIR_NAME)

    private fun pruneStale(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > STALE_STAGED_MS }
            ?.forEach {
                Log.w(TAG, "dropping stale crash report ${it.name}")
                it.delete()
            }
    }
}

/**
 * Posts one staged crash report. Public with the standard constructor so the
 * default WorkerFactory can create it.
 */
class CrashReportWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "CrashReportWorker"
        private const val MAX_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    override fun doWork(): Result {
        val url = inputData.getString(CrashReportUploader.KEY_URL)
        val file = inputData.getString(CrashReportUploader.KEY_FILE)?.let(::File)
        if (url == null || file == null || !file.exists()) {
            Log.w(TAG, "nothing to upload (missing url or staged file)")
            return Result.failure()
        }

        return try {
            val code = post(url, file)
            when {
                code in 200..299 -> {
                    Log.i(TAG, "crash report uploaded (HTTP $code)")
                    file.delete()
                    Result.success()
                }
                // Timeout, rate limit, server error: worth trying again
                code == 408 || code == 429 || code >= 500 -> retryOrGiveUp(file, "HTTP $code")
                // Other 4xx: the request itself is wrong, retrying won't help
                else -> {
                    Log.e(TAG, "crash report rejected (HTTP $code)")
                    file.delete()
                    Result.failure()
                }
            }
        } catch (e: IOException) {
            retryOrGiveUp(file, e.toString())
        }
    }

    private fun retryOrGiveUp(file: File, reason: String): Result {
        // runAttemptCount starts at 0
        return if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.e(TAG, "giving up crash report after $MAX_ATTEMPTS attempts: $reason")
            file.delete()
            Result.failure()
        } else {
            Log.w(TAG, "crash report upload failed, will retry: $reason")
            Result.retry()
        }
    }

    private fun post(url: String, file: File): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            // The staged file is UTF-8, so its size is the body size
            connection.setFixedLengthStreamingMode(file.length())

            connection.outputStream.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }

            val code = connection.responseCode
            // Read and close the response so the connection can be reused
            (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes() }
            return code
        } finally {
            connection.disconnect()
        }
    }
}
