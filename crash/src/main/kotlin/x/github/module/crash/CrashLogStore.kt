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

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class CrashType(val label: String) {
    JAVA("Java Crash"),
    NATIVE("Native Crash"),
    ANR("ANR")
}

/**
 * The crash just captured, as passed to [OnExceptionListener].
 *
 * [content] is the full text (header + stack trace). [file] is where it was
 * persisted, or null if it could not be written (e.g. disk full) - callers
 * should then pass [content] on directly.
 */
class CrashLog internal constructor(
    val type: CrashType,
    val content: String,
    val file: File?
)

/** A crash read back from disk by [CrashLogStore.consumeAll]. */
class StoredCrash internal constructor(
    // Epoch millis of the newest occurrence
    val time: Long,
    // Null only for files written by an older version of this store
    val type: CrashType?,
    // How many times this same crash happened before it was seen
    val count: Int,
    val content: String
)

/**
 * Persists every crash to disk BEFORE any listener runs, so a crash is never
 * lost just because CrashActivity could not be started (e.g. the app was in
 * the background, where Android 10+ blocks activity starts).
 *
 * Lifecycle: a file exists only while the user has not seen it.
 * [consumeAll] reads every log and deletes it in the same pass.
 *
 * Deduplication: each log carries a signature of the crash (exception classes
 * and code locations, not messages or addresses). When the same crash happens
 * again, the new log replaces the old one and the occurrence count goes up,
 * so only the newest copy of each distinct crash is kept.
 *
 * Files live in filesDir/logs, named
 *   crash_<yyyyMMdd>_<HHmmss>_<SSS>_<seq>_<type>_<signature>_<count>.log
 * Only files named crash_* are listed or deleted, so the directory can be
 * shared with the app's other logs.
 *
 * Native fallback reports (written by fallback_report.cpp in a separate
 * directory) are not read directly: [adoptNativeFallbacks] converts any that
 * were left behind into regular crash_* logs, so everything downstream
 * (dedup, CrashActivity, upload) only ever sees one format.
 *
 * This object deliberately does not touch CrashHandler, so reading logs from
 * the :crash process does not load the native library there.
 */
object CrashLogStore {

    private const val TAG = "CrashLogStore"

    private const val DIR_NAME = "logs"
    private const val FILE_PREFIX = "crash_"
    private const val LOG_SUFFIX = ".log"
    private const val TMP_SUFFIX = ".tmp"
    private const val NAME_TIME_PATTERN = "yyyyMMdd_HHmmss_SSS"

    // native_<epoch_ms>_<pid>.log, see fallback_report.cpp
    private const val FALLBACK_PREFIX = "native_"

    // Distinct crashes kept while unseen. Only reached if the app keeps crashing
    // in different ways and the user never opens it.
    private const val MAX_LOGS = 20

    // Temp files older than this are leftovers of a process that died mid-write.
    // Also used for fallback reports whose writer pid still appears alive.
    private const val STALE_TMP_MS = 60_000L

    private val sequence = AtomicInteger()

    private val FALLBACK_SIGNAL = Regex("""^signal (\d+)""")
    private val FALLBACK_THREAD = Regex("""^pid: \d+, tid: \d+, name: (.*)$""", RegexOption.MULTILINE)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var staticHeader: String = ""

    /**
     * Called once from CrashHandler.install(). Resolves the directory, precomputes
     * everything that never changes, and prunes old files, so that the crash
     * path itself does as little work as possible.
     */
    internal fun init(context: Context) {
        val logDir = logDir(context)
        dir = logDir
        staticHeader = buildStaticHeader(context)
        try {
            prune(logDir)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to prune crash logs", t)
        }
    }

    /**
     * Writes a crash record, replacing any earlier record of the same crash.
     * Never throws: if persisting fails, the returned [CrashLog] has a null
     * [CrashLog.file] but still carries the content.
     *
     * @param signature identifies "the same crash"; see [CrashSignature]
     * @param time when the crash happened; differs from "now" only for crashes
     *   recovered from an earlier run (see [adoptNativeFallbacks])
     */
    internal fun write(
        type: CrashType,
        threadName: String?,
        detail: String,
        signature: String,
        time: Long = System.currentTimeMillis()
    ): CrashLog {
        val sig = hash(type.name + '\n' + signature)
        val logDir = dir

        // Earlier logs of this same crash: replaced by the new one below
        val previous = try {
            logDir?.listFiles()?.filter { parseName(it)?.signature == sig }
        } catch (t: Throwable) {
            null
        }.orEmpty()
        val previousNames = previous.mapNotNull { parseName(it) }
        val count = (previousNames.maxOfOrNull { it.count } ?: previous.size) + 1

        // The file name carries the time of the NEWEST occurrence. An adopted
        // crash can be older than a log of the same crash already on disk, so
        // it must not move that time backwards.
        val nameTime = maxOf(time, previousNames.maxOfOrNull { it.time } ?: time)

        val content = buildString {
            append("Type: ").append(type.label).append('\n')
            append("Time: ").append(formatTime(time, "yyyy-MM-dd HH:mm:ss.SSS")).append('\n')
            if (count > 1) {
                append("Occurrences: ").append(count).append(" (only the latest is kept)\n")
            }
            if (threadName != null) {
                append("Thread: ").append(threadName).append('\n')
            }
            append(staticHeader)
            append("------------------------------------------\n")
            append(detail)
        }

        if (logDir == null) return CrashLog(type, content, null)

        return try {
            if (!logDir.exists()) logDir.mkdirs()

            val baseName = FILE_PREFIX + formatTime(nameTime, NAME_TIME_PATTERN) +
                "_" + sequence.incrementAndGet() +
                "_" + type.name.lowercase(Locale.ROOT) +
                "_" + sig +
                "_" + count

            // Write to a temp file, then rename: a reader never sees a half-written log
            val tmp = File(logDir, baseName + TMP_SUFFIX)
            tmp.writeText(content)

            val file = File(logDir, baseName + LOG_SUFFIX)
            if (tmp.renameTo(file)) {
                // Only once the new copy is safely on disk
                previous.forEach { it.delete() }
                CrashLog(type, content, file)
            } else {
                tmp.delete()
                CrashLog(type, content, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to persist crash log", t)
            CrashLog(type, content, null)
        }
    }

    /**
     * Converts native fallback reports left behind by an earlier run into
     * regular crash logs, then deletes them.
     *
     * A fallback file survives only when the Kotlin callback never saved the
     * crash itself (JNI callback failed, process killed mid-report, disk full
     * at the time...), so every file found here is a crash that would
     * otherwise be lost.
     *
     * The signature is computed from the same "Abort message line + trace"
     * text the live path passes to [CrashSignature.ofNative], so an adopted
     * crash deduplicates against normal logs of the same bug.
     *
     * Must be called after [init] and BEFORE nativeSetFallbackDir(), i.e.
     * before this process can write fallback files of its own.
     */
    internal fun adoptNativeFallbacks(fallbackDir: File) {
        val files = try {
            fallbackDir.listFiles()?.filter {
                it.isFile && it.name.startsWith(FALLBACK_PREFIX) && it.name.endsWith(LOG_SUFFIX)
            }
        } catch (t: Throwable) {
            null
        } ?: return

        val now = System.currentTimeMillis()
        val myPid = Process.myPid()

        for (file in files) {
            // native_<epoch_ms>_<pid>.log
            val parts = file.name.removePrefix(FALLBACK_PREFIX).removeSuffix(LOG_SUFFIX).split('_')
            val crashTime = parts.getOrNull(0)?.toLongOrNull() ?: file.lastModified()
            val pid = parts.getOrNull(1)?.toIntOrNull()

            // Another process of this app (sharing filesDir) may be writing this
            // report right now. Leave it alone while its writer is alive, unless
            // it is clearly too old to still be in progress.
            if (pid != null && pid != myPid && File("/proc/$pid").exists() &&
                now - file.lastModified() < STALE_TMP_MS
            ) {
                continue
            }

            try {
                adoptOne(file, crashTime)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to adopt ${file.name}", t)
            }
        }
    }

    private fun adoptOne(file: File, crashTime: Long) {
        val text = file.readText()
        if (text.isBlank()) {
            // Process died before even the header was written: nothing to keep
            file.delete()
            return
        }

        // Layout (fallback_report.cpp): header lines, optional
        // "Abort message: '...'" line, a blank line, then the stack trace
        val split = text.indexOf("\n\n")
        val header = if (split >= 0) text.substring(0, split) else text.trimEnd()
        val trace = if (split >= 0) text.substring(split + 2) else ""

        val signum = FALLBACK_SIGNAL.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val threadName = FALLBACK_THREAD.find(header)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
        val abortLine = header.lineSequence().firstOrNull { it.startsWith("Abort message: ") }

        // Same text the live path builds in crash_report_thread(), so the
        // signature matches normal logs of the same crash
        val message = buildString {
            if (abortLine != null) append(abortLine).append('\n')
            append(trace)
        }

        val detail = buildString {
            append("Signal: ").append(signum).append('\n')
            append("(Recovered from the native fallback report: ")
            append("the in-process report did not complete.)\n")
            append(header).append("\n\n")
            if (trace.isBlank()) {
                append("<stack trace missing: the reporter stopped while unwinding>\n")
            } else {
                append(trace)
            }
        }

        val log = write(
            CrashType.NATIVE, threadName, detail,
            CrashSignature.ofNative(signum, message),
            crashTime
        )
        // Keep the fallback if converting failed; it is retried on the next launch
        if (log.file != null) file.delete()
    }

    fun logDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** True if there are crash logs the user has not seen yet. */
    fun hasLogs(context: Context): Boolean =
        logDir(context).listFiles()?.any(::isCrashLog) == true

    /**
     * Reads every crash log, newest first, and DELETES each one as it is read:
     * a log that has been read counts as seen, and seen logs are not kept.
     * Logs written while this runs are not in the listing and are left alone.
     */
    @WorkerThread
    fun consumeAll(context: Context): List<StoredCrash> {
        val files = logDir(context).listFiles()?.filter(::isCrashLog) ?: return emptyList()

        val crashes = files.mapNotNull { file ->
            val parsed = parseName(file)
            val content = try {
                file.readText()
            } catch (t: Throwable) {
                Log.w(TAG, "failed to read ${file.name}", t)
                null
            }
            // Deleted even if unreadable: a corrupt file would otherwise reappear forever
            file.delete()
            // ...
            content?.let {
                StoredCrash(
                    time = parsed?.time ?: file.lastModified(),
                    type = parsed?.type,
                    count = parsed?.count ?: 1,
                    content = it
                )
            }
        }
        return crashes.sortedByDescending { it.time }
    }

    private fun isCrashLog(file: File): Boolean =
        file.name.startsWith(FILE_PREFIX) && file.name.endsWith(LOG_SUFFIX)

    private data class ParsedName(
        val time: Long,
        val type: CrashType,
        val signature: String,
        val count: Int
    )

    private fun parseName(file: File): ParsedName? {
        if (!isCrashLog(file)) return null
        // crash, yyyyMMdd, HHmmss, SSS, seq, type, signature, count
        val parts = file.name.removeSuffix(LOG_SUFFIX).split('_')
        if (parts.size != 8) return null
        return try {
            val time = SimpleDateFormat(NAME_TIME_PATTERN, Locale.US)
                .parse("${parts[1]}_${parts[2]}_${parts[3]}")?.time
                ?: return null
            ParsedName(
                time = time,
                type = CrashType.valueOf(parts[5].uppercase(Locale.ROOT)),
                signature = parts[6],
                count = parts[7].toInt()
            )
        } catch (e: Exception) {
            null
        }
    }

    // Startup cleanup: stale temp files, then the oldest crashes beyond MAX_LOGS
    private fun prune(logDir: File) {
        val files = logDir.listFiles() ?: return
        val now = System.currentTimeMillis()

        files.filter {
            it.name.startsWith(FILE_PREFIX) && it.name.endsWith(TMP_SUFFIX) &&
                now - it.lastModified() > STALE_TMP_MS
        }.forEach { it.delete() }

        // Names start with a sortable timestamp
        files.filter(::isCrashLog)
            .sortedByDescending { it.name }
            .drop(MAX_LOGS)
            .forEach { it.delete() }
    }

    // 12 hex chars of SHA-1: no '_', so it is safe inside the file name
    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private fun buildStaticHeader(context: Context): String {
        var versionName: String? = null
        var versionCode = -1L
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            versionName = info.versionName
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (t: Throwable) {
            // keep defaults
        }

        return buildString {
            append("Process: ").append(processName())
                .append(" (pid ").append(Process.myPid()).append(")\n")
            append("App: ").append(context.packageName).append(' ')
                .append(versionName).append(" (").append(versionCode).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("ABIs: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        }
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching {
                File("/proc/self/cmdline").readText().substringBefore('\u0000')
            }.getOrDefault("unknown")
        }

    private fun formatTime(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(Date(millis))
}

/**
 * What makes two crashes "the same": exception classes and code locations.
 * Messages, addresses and ids are left out on purpose, since they usually
 * differ between occurrences of one and the same bug.
 */
internal object CrashSignature {

    private const val MAX_CAUSES = 5
    private const val FRAMES_PER_CAUSE = 10
    private const val ANR_FRAMES = 20

    private val HEX_ADDRESS = Regex("0x[0-9a-fA-F]+")
    private val PID_TID = Regex("\\b(pid|tid)\\b\\D{0,3}\\d+", RegexOption.IGNORE_CASE)

    // /data/app/~~<random>==/<pkg>-<random>==/ changes on every install or update
    private val INSTALL_DIR = Regex("/data/app/\\S*?/(lib/|base\\.apk)")

    // Exception class + top frames of each throwable in the cause chain
    fun of(throwable: Throwable): String = buildString {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            append(current.javaClass.name).append('\n')
            appendFrames(current.stackTrace.take(FRAMES_PER_CAUSE))
            current = current.cause
            depth++
        }
    }

    // Top frames of the main thread
    fun ofAnr(mainThreadStack: Array<StackTraceElement>): String = buildString {
        appendFrames(mainThreadStack.take(ANR_FRAMES))
    }

    // Best effort: the message format comes from the native stack dumper, so
    // only the obviously volatile parts are normalized
    fun ofNative(signum: Int, message: String): String {
        val normalized = message
            .replace(HEX_ADDRESS, "0x*")
            .replace(PID_TID, "$1 *")
            .replace(INSTALL_DIR, "/data/app/*/$1")
        return "$signum\n$normalized"
    }

    private fun StringBuilder.appendFrames(frames: List<StackTraceElement>) {
        for (frame in frames) {
            append(frame.className).append('.').append(frame.methodName)
                .append(':').append(frame.lineNumber).append('\n')
        }
    }
}

