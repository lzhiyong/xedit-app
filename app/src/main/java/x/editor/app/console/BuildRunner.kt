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

package x.editor.app.console

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.MainThread
import x.editor.app.AppDirs
import x.editor.app.R
import x.editor.app.lsp.Termux
import x.editor.app.lsp.TermuxCommand
import x.editor.app.toolchain.Runner
import x.editor.app.toolchain.Toolchain
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the commands of the console's build tab ("compile and run" too), where
 * Toolchain.runnerFor says:
 *
 *  - with the app's own toolchain (Runner.LOCAL): `/system/bin/sh -c` in the target's
 *    directory, with the toolchain's environment and the functions that start the programs
 *    of its usr/bin (Toolchain.shellPreamble); what it prints comes line by line;
 *  - in Termux (Runner.TERMUX, where `pkg install clang` puts the compilers): with
 *    `bash -lc`. RUN_COMMAND hands back what a command printed only when it ends, so the
 *    command is wrapped ([WRAPPER]): its stdout and stderr go to two connections to a
 *    socket of the app's on 127.0.0.1 (bash's `/dev/tcp`), and come line by line here as
 *    they are printed. A random token, the first line of each, tells them apart and keeps
 *    other apps out. If bash cannot connect, the command runs as it is and what it printed
 *    comes when it ends, as before.
 *
 * Builds run by [Target]: a project (its command is the project's, RunCommands.keyFor), or
 * a file. One [Job] per target: a target runs one build at a time ([run] returns null
 * while one runs), different targets side by side, each into a console channel of its own
 * (ConsoleLog.buildChannel). A build's lines carry no time: its first line says when it
 * started, its last when it ended and how long it took. The problems found in what it
 * printed ([ProblemMatcher]) are kept in its job until its next run.
 *
 * Main thread, but for what the runs print.
 */
object BuildRunner {

    /** What a run came to: its exit code (null if it could not run, or was given up), and what it printed. */
    data class Result(val exitCode: Int?, val stdout: String, val stderr: String, val error: String?)

    /**
     * What builds are of: [key] tells targets apart (`project:<root>` or `file:<path>`),
     * [label] is shown (the project's directory, the file's name), [workDir] is where its
     * commands run (null: the home of where they run).
     */
    data class Target(val key: String, val label: String, val workDir: File?)

    /**
     * The target of [file] in [project] (ProjectRoot): the project if its command is the
     * project's for the file (a CMake, Gradle or Android project: any of its files builds
     * it), else the file itself, in the project's root. No file: the project's root, or
     * nothing (the home of where commands run).
     */
    fun targetOf(file: File?, project: ProjectRoot.Project?): Target {
        if (file == null) {
            val root = project?.root?.absoluteFile ?: return Target("home", "~", null)
            return Target("dir:" + canonical(root), root.name.ifEmpty { root.path }, root)
        }
        val workDir = RunCommands.workDirOf(file, project)
        return if (project?.kind?.covers(file) == true) {
            Target("project:" + canonical(workDir), workDir.name.ifEmpty { workDir.path }, workDir)
        } else {
            Target("file:" + canonical(file), file.name, workDir)
        }
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    /** The builds of a target: the one going on, or the last. */
    class Job internal constructor(val target: Target, val channel: String) {
        @Volatile var command: String = ""
            internal set
        @Volatile var runner: Runner? = null
            internal set
        @Volatile var running = false
            internal set
        /** A stop was asked for: the run has not ended yet. */
        @Volatile var stopping = false
            internal set
        @Volatile var startedAt = 0L
            internal set
        @Volatile var endedAt = 0L
            internal set
        /** The last run's exit code; null while it runs, or if it could not run. */
        @Volatile var exitCode: Int? = null
            internal set
        /** The problems of the last run. */
        @Volatile var problems: List<Problem> = emptyList()
            internal set

        /** How long the run took, or has been going on. */
        val elapsed: Long get() = if (startedAt == 0L) 0L else (if (running) System.currentTimeMillis() else endedAt) - startedAt

        // the process of a run with the app's own toolchain, to stop it
        @Volatile internal var process: Process? = null
        // the shell of a run in Termux (sent with its output), to stop it
        @Volatile internal var termuxPid: Int? = null
        // a run in Termux: ends it without waiting for Termux any longer
        @Volatile internal var giveUp: (() -> Unit)? = null
        // which run of the job: what a run ended late does not touch the next one
        @Volatile internal var generation = 0
    }

    /** Told on the main thread when a job starts, is asked to stop, or ends. */
    fun interface Listener {
        fun onChanged(job: Job)
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val channels = HashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    // the targets whose build an open console shows: no toast when they end
    private val shown = HashSet<String>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val random = SecureRandom()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Whether an open console shows the builds of [target] (when one ends unseen, a toast says so). */
    @MainThread
    fun setShown(target: Target, isShown: Boolean) {
        if (isShown) shown += target.key else shown -= target.key
    }

    /** The job of [target]; null if it never ran. */
    fun job(target: Target): Job? = synchronized(jobs) { jobs[target.key] }

    /** The jobs, in the order their targets first ran. */
    fun jobs(): List<Job> = synchronized(jobs) { jobs.values.toList() }

    /** Whether a build of [target] is going on. */
    fun isRunning(target: Target): Boolean = job(target)?.running == true

    /** The console channel of [target]'s builds: by its label, made unique (two projects named `app`). */
    fun channelOf(target: Target): String = synchronized(jobs) {
        channels.getOrPut(target.key) {
            val taken = channels.values.toSet()
            var channel = ConsoleLog.buildChannel(target.label)
            var n = 2
            while (channel in taken) channel = ConsoleLog.buildChannel("${target.label} (${n++})")
            channel
        }
    }

    /**
     * Runs [command] for [target], where [runner] says (by default as Toolchain.runnerFor
     * chooses); [fresh]: its channel is cleared first. Returns its job, null if a build of
     * [target] is going on already (one at a time). Throws IOException if Termux cannot be
     * asked to run it.
     */
    @MainThread
    @Throws(IOException::class)
    fun run(context: Context, target: Target, command: String, runner: Runner? = null, fresh: Boolean = false): Job? {
        val app = context.applicationContext
        val job = synchronized(jobs) {
            val existing = jobs[target.key]
            if (existing?.running == true) return null
            existing ?: Job(target, channelOf(target)).also { jobs[target.key] = it }
        }
        val where = runner ?: Toolchain.runnerFor(app, command)
        if (fresh) ConsoleLog.clear(job.channel)
        job.command = command
        job.runner = where
        job.running = true
        job.stopping = false
        job.startedAt = System.currentTimeMillis()
        job.endedAt = 0L
        job.exitCode = null
        job.problems = emptyList()
        job.process = null
        job.termuxPid = null
        job.giveUp = null
        val generation = ++job.generation
        val dir = if (where == Runner.LOCAL) target.workDir?.takeIf { it.isDirectory } ?: AppDirs.home(app) else target.workDir
        val runnerName = app.getString(if (where == Runner.LOCAL) R.string.toolchain_runner_local else R.string.toolchain_runner_termux)
        ConsoleLog.append(
            job.channel, ConsoleLog.Level.INFO,
            app.getString(R.string.build_started, clock(job.startedAt), runnerName), heading = true
        )
        ConsoleLog.append(
            job.channel, ConsoleLog.Level.INFO,
            app.getString(R.string.build_workdir, dir?.path ?: Termux.HOME) + "\n" + "$ " + command
        )
        try {
            if (where == Runner.LOCAL) runLocal(app, job, generation, dir ?: AppDirs.home(app)) else runInTermux(app, job, generation)
        } catch (e: IOException) {
            finish(app, job, generation, Result(null, "", "", e.message ?: e.toString()), printed = true)
            throw e
        }
        notifyChanged(job)
        return job
    }

    /**
     * Stops [job]'s run: with the app's own toolchain its process; in Termux its shell and
     * what it started (Termux is asked to kill them). A run in Termux that does not end
     * (Termux says nothing), or a second stop, is no longer waited for. Returns whether
     * anything was done.
     */
    @MainThread
    fun stop(context: Context, job: Job): Boolean {
        if (!job.running) return false
        val app = context.applicationContext
        job.process?.let { process ->
            process.destroy()
            job.stopping = true
            ConsoleLog.append(job.channel, ConsoleLog.Level.WARNING, app.getString(R.string.build_stop_requested))
            notifyChanged(job)
            return true
        }
        if (job.runner != Runner.TERMUX) return false
        val pid = job.termuxPid
        if (job.stopping || pid == null) {
            // stopped again, or what to kill is not known (its output did not come here)
            job.giveUp?.invoke()
            return true
        }
        try {
            Termux.run(app, TermuxCommand("bash", listOf("-c", KILL_TREE, "xedit-stop", pid.toString())))
        } catch (e: IOException) {
            ConsoleLog.append(job.channel, ConsoleLog.Level.ERROR, e.message ?: e.toString())
            job.giveUp?.invoke()
            return true
        }
        job.stopping = true
        ConsoleLog.append(job.channel, ConsoleLog.Level.WARNING, app.getString(R.string.build_stop_requested))
        notifyChanged(job)
        // Termux says nothing of it a while after: it is no longer waited for
        val generation = job.generation
        main.postDelayed({ if (job.running && job.generation == generation) job.giveUp?.invoke() }, STOP_WAIT_MILLIS)
        return true
    }

    // with the app's own toolchain: a shell of the system, its output line by line
    private fun runLocal(app: Context, job: Job, generation: Int, dir: File) {
        thread(name = "build ${job.target.label}") {
            val out = StringBuilder()
            val err = StringBuilder()
            var code: Int? = null
            var error: String? = null
            try {
                val env = Toolchain.environment(app)
                val script = Toolchain.shellPreamble(app) + job.command
                val builder = ProcessBuilder("/system/bin/sh", "-c", script).directory(dir)
                builder.environment().apply {
                    clear()
                    putAll(env)
                }
                val started = builder.start()
                job.process = started
                started.outputStream.close()
                val errors = thread(name = "build-stderr") {
                    started.errorStream.bufferedReader().forEachLine { line ->
                        keep(err, line)
                        ConsoleLog.append(job.channel, levelOf(line), line)
                    }
                }
                started.inputStream.bufferedReader().forEachLine { line ->
                    keep(out, line)
                    ConsoleLog.append(job.channel, levelOf(line), line)
                }
                errors.join()
                code = started.waitFor()
            } catch (e: IOException) {
                error = e.message ?: e.toString()
            } catch (e: InterruptedException) {
                error = "interrupted"
            } finally {
                job.process = null
            }
            val result = Result(code, synchronized(out) { out.toString() }, synchronized(err) { err.toString() }, error)
            main.post { finish(app, job, generation, result, printed = true) }
        }
    }

    // In Termux: the command wrapped (WRAPPER) so that what it prints comes here as it is
    // printed, through a socket of the app's; when it ends, Termux's result (RUN_COMMAND's
    // pending intent) has its exit code.
    @Throws(IOException::class)
    private fun runInTermux(app: Context, job: Job, generation: Int) {
        val token = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            null // bash cannot connect, the command runs as it is: its output comes when it ends
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val readers = CopyOnWriteArrayList<Thread>()
        val sockets = CopyOnWriteArrayList<Socket>()
        if (server != null) thread(name = "build-accept") {
            try {
                // its stdout and its stderr; a few more at most (others' tries, dropped for their token)
                while (readers.size < MAX_CONNECTIONS) {
                    val socket = server.accept()
                    sockets += socket
                    readers += thread(name = "build-stream") { stream(job, generation, socket, token, out, err) }
                }
            } catch (e: IOException) {
                // closed: the run ended
            } finally {
                runCatching { server.close() }
            }
        }

        val action = "${app.packageName}.BUILD_RESULT.${System.nanoTime()}"
        val ended = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver
        // what Termux said (or the run given up): what is still coming of its output is
        // waited for, off the main thread, then the run is done
        fun end(result: Result) {
            if (!ended.compareAndSet(false, true)) return
            try {
                app.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // gone already
            }
            val marker = "$token streamed"
            val streamed = result.stdout.lineSequence().any { it.trim() == marker }
            thread(name = "build-drain") {
                if (streamed) {
                    // both connections were made before the command ran: they may not be accepted yet
                    val accepted = System.currentTimeMillis() + ACCEPT_WAIT_MILLIS
                    while (readers.size < 2 && System.currentTimeMillis() < accepted) Thread.sleep(20)
                    // what was printed last; something the command left running may hold the connections open
                    val drained = System.currentTimeMillis() + DRAIN_MILLIS
                    for (reader in readers) reader.join((drained - System.currentTimeMillis()).coerceAtLeast(1))
                }
                runCatching { server?.close() }
                for (socket in sockets) runCatching { socket.close() }
                for (reader in readers) reader.join(500)
                // what Termux has besides (a login script's output): the marker taken out
                val restOut = result.stdout.lineSequence().filter { it.trim() != marker }.joinToString("\n").trim()
                val full = if (streamed) {
                    Result(result.exitCode, synchronized(out) { out.toString() } + restOut, synchronized(err) { err.toString() } + result.stderr, result.error)
                } else {
                    result
                }
                main.post {
                    if (streamed) {
                        for (line in (restOut + "\n" + result.stderr).lines()) {
                            if (line.isNotBlank()) ConsoleLog.append(job.channel, levelOf(line), line)
                        }
                    }
                    finish(app, job, generation, full, printed = streamed)
                }
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val bundle = intent.getBundleExtra("result")
                end(
                    Result(
                        exitCode = bundle?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                        stdout = bundle?.getString("stdout").orEmpty(),
                        stderr = bundle?.getString("stderr").orEmpty(),
                        error = bundle?.getString("errmsg")?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            app, action.hashCode(), Intent(action).setPackage(app.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, IntentFilter(action))
        }
        job.giveUp = { end(Result(null, "", "", app.getString(R.string.build_given_up))) }
        val port = server?.localPort ?: 0
        try {
            Termux.run(
                app,
                TermuxCommand("bash", listOf("-lc", WRAPPER, "xedit-build", job.command, port.toString(), token), job.target.workDir?.path),
                pending,
            )
        } catch (e: IOException) {
            ended.set(true)
            job.giveUp = null
            try {
                app.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
            }
            runCatching { server?.close() }
            throw e
        }
    }

    // One connection of the wrapper: its first line the token and what it carries (`out`
    // with the shell's pid, or `err`), then the lines printed there.
    private fun stream(job: Job, generation: Int, socket: Socket, token: String, out: StringBuilder, err: StringBuilder) {
        try {
            socket.use { s ->
                s.soTimeout = HEADER_TIMEOUT_MILLIS
                val reader = s.getInputStream().bufferedReader()
                val header = reader.readLine()?.split(' ') ?: return
                if (header.size < 2 || header[0] != token) return
                s.soTimeout = 0
                val isErr = header[1] == "err"
                if (!isErr && job.generation == generation) header.getOrNull(2)?.toIntOrNull()?.let { job.termuxPid = it }
                val buffer = if (isErr) err else out
                reader.forEachLine { line ->
                    keep(buffer, line)
                    ConsoleLog.append(job.channel, levelOf(line), line)
                }
            }
        } catch (e: IOException) {
            // closed: the run ended, or was given up
        }
    }

    // what is kept of the output for the problems: the last megabyte
    private fun keep(buffer: StringBuilder, line: String) = synchronized(buffer) {
        buffer.append(line).append('\n')
        if (buffer.length > MAX_KEPT) buffer.delete(0, buffer.length - MAX_KEPT)
    }

    private fun levelOf(line: String) = when {
        // clang, gcc, javac (`: error:`), Kotlin (`e: `), Gradle (`FAILURE:`, `BUILD FAILED`)
        line.contains("error:") || line.startsWith("e: ") || line.startsWith("FAILURE:") || line.startsWith("BUILD FAILED") ->
            ConsoleLog.Level.ERROR
        line.contains("warning:") || line.startsWith("w: ") -> ConsoleLog.Level.WARNING
        else -> ConsoleLog.Level.INFO
    }

    // The run ended: what it printed to its channel (unless it was [printed] as it came),
    // its problems kept, its last line when it ended and how long it took.
    @MainThread
    private fun finish(app: Context, job: Job, generation: Int, result: Result, printed: Boolean) {
        if (job.generation != generation || !job.running) return
        job.running = false
        job.stopping = false
        job.endedAt = System.currentTimeMillis()
        job.exitCode = result.exitCode
        job.process = null
        job.termuxPid = null
        job.giveUp = null
        result.error?.let { ConsoleLog.append(job.channel, ConsoleLog.Level.ERROR, it) }
        if (!printed) {
            for (line in (result.stdout + "\n" + result.stderr).lines()) {
                if (line.isNotBlank()) ConsoleLog.append(job.channel, levelOf(line), line)
            }
        }
        val found = ProblemMatcher.parse(result.stderr + "\n" + result.stdout, job.target.workDir)
        job.problems = found
        val code = result.exitCode
        val took = formatDuration(app, job.elapsed)
        var last = app.getString(
            R.string.build_finished, if (code == 0) "✓" else "✗", clock(job.endedAt), code?.toString() ?: "?", took
        )
        if (found.isNotEmpty()) last += " · " + app.getString(R.string.build_problems, found.size)
        ConsoleLog.append(job.channel, if (code == 0) ConsoleLog.Level.INFO else ConsoleLog.Level.ERROR, last, heading = true)
        notifyChanged(job)
        // ended where it is not seen (another file is shown, the console closed): a toast says so
        if (job.target.key !in shown) {
            val text = if (code == 0) app.getString(R.string.build_done_success, job.target.label, took)
            else app.getString(R.string.build_done_failed, job.target.label, code?.toString() ?: "?", took)
            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifyChanged(job: Job) {
        for (listener in listeners) listener.onChanged(job)
    }

    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** [time] as a time of day: 14:03:52. */
    fun clock(time: Long): String = synchronized(clockFormat) { clockFormat.format(Date(time)) }

    /** How long [millis] is, to be read: 4.2 s, 3 min 12 s, 1 h 5 min. */
    fun formatDuration(context: Context, millis: Long): String {
        val seconds = millis / 1000
        return when {
            millis < 60_000 -> context.getString(R.string.duration_seconds, millis / 1000f)
            seconds < 3600 -> context.getString(R.string.duration_minutes, seconds / 60, seconds % 60)
            else -> context.getString(R.string.duration_hours, seconds / 3600, seconds % 3600 / 60)
        }
    }

    /** [millis] as a stopwatch shows it: 0:07, 3:12, 1:05:00. */
    fun formatElapsed(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds < 3600) "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
        else "%d:%02d:%02d".format(Locale.US, seconds / 3600, seconds % 3600 / 60, seconds % 60)
    }

    /**
     * The script that runs a command in Termux (`bash -lc WRAPPER xedit-build COMMAND PORT
     * TOKEN`): its stdout to fd 3, its stderr to fd 4, both connections to the app's socket
     * on PORT, each starting with TOKEN and what it carries (stdout: and the shell's pid,
     * to stop it); a line `TOKEN streamed` to Termux's own stdout says that they were. If
     * they cannot be made, the command runs as it is. Python is asked not to buffer (its
     * output is no longer a terminal).
     */
    private val WRAPPER = listOf(
        "if { exec 3>\"/dev/tcp/127.0.0.1/\$2\"; } 2>/dev/null && { exec 4>\"/dev/tcp/127.0.0.1/\$2\"; } 2>/dev/null; then",
        "  printf '%s out %s\\n' \"\$3\" \"\$\$\" >&3",
        "  printf '%s err\\n' \"\$3\" >&4",
        "  printf '%s streamed\\n' \"\$3\"",
        "  export PYTHONUNBUFFERED=1",
        "  eval \"\$1\" </dev/null >&3 2>&4",
        "  c=\$?",
        "  exec 3>&- 4>&-",
        "  exit \$c",
        "fi",
        "exec 3>&- 4>&-",
        "eval \"\$1\"",
    ).joinToString("\n")

    // kills a process and what it started, their children first (`bash -c KILL_TREE x PID`)
    private const val KILL_TREE =
        "k() { for c in \$(pgrep -P \"\$1\" 2>/dev/null); do k \"\$c\"; done; kill -TERM \"\$1\" 2>/dev/null; }; k \"\$1\""

    private const val MAX_KEPT = 1 shl 20
    private const val MAX_CONNECTIONS = 8
    private const val HEADER_TIMEOUT_MILLIS = 10_000
    private const val ACCEPT_WAIT_MILLIS = 2_000L
    private const val DRAIN_MILLIS = 5_000L
    private const val STOP_WAIT_MILLIS = 8_000L
}
