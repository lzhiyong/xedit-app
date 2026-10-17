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
import x.editor.app.lsp.Termux
import x.editor.app.lsp.TermuxCommand
import java.io.File
import java.io.IOException

/**
 * Runs a command of the console's build tab in Termux (where the compilers are: `pkg
 * install clang python`), with `bash -lc` in [workDir], and hands back what it printed:
 * Termux sends it when the command ends (RUN_COMMAND's result, at most about 100 KB of
 * output). What it printed goes to the build channel of ConsoleLog; the problems found in
 * it ([ProblemMatcher]) are kept in [problems] until the next run.
 */
object BuildRunner {

    /** What a run came to: its exit code (null if Termux could not run it), and what it printed. */
    data class Result(val exitCode: Int?, val stdout: String, val stderr: String, val error: String?)

    /** The problems of the last run. Main thread. */
    @Volatile
    var problems: List<Problem> = emptyList()
        private set

    /** Whether a run is going on. */
    @Volatile
    var running = false
        private set

    /**
     * Runs [command] in [workDir] (Termux's home if null); [done] is called on the main
     * thread when it ended. Throws IOException if Termux cannot be asked to.
     */
    @Throws(IOException::class)
    fun run(context: Context, command: String, workDir: File?, done: (Result) -> Unit) {
        val app = context.applicationContext
        val action = "${app.packageName}.BUILD_RESULT.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                try {
                    app.unregisterReceiver(this)
                } catch (e: IllegalArgumentException) {
                    // gone already
                }
                val bundle = intent.getBundleExtra("result")
                val result = Result(
                    exitCode = bundle?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                    stdout = bundle?.getString("stdout").orEmpty(),
                    stderr = bundle?.getString("stderr").orEmpty(),
                    error = bundle?.getString("errmsg")?.takeIf { it.isNotBlank() },
                )
                finish(command, workDir, result)
                done(result)
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
        ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.INFO, "$ $command" + (workDir?.let { "    (in $it)" } ?: ""))
        try {
            Termux.run(app, TermuxCommand("bash", listOf("-lc", command), workDir?.path), pending)
            running = true
        } catch (e: IOException) {
            try {
                app.unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
            }
            ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.ERROR, e.message ?: e.toString())
            throw e
        }
    }

    // what it printed, to the build channel; its problems kept
    private fun finish(command: String, workDir: File?, result: Result) {
        running = false
        result.error?.let { ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.ERROR, it) }
        if (result.stdout.isNotBlank()) ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.INFO, result.stdout)
        val found = ProblemMatcher.parse(result.stderr + "\n" + result.stdout, workDir)
        for (line in result.stderr.lines().filter { it.isNotBlank() }) {
            val level = when {
                line.contains(": error:") || line.contains("Error") -> ConsoleLog.Level.ERROR
                line.contains(": warning:") -> ConsoleLog.Level.WARNING
                else -> ConsoleLog.Level.INFO
            }
            ConsoleLog.append(ConsoleLog.BUILD, level, line)
        }
        val code = result.exitCode
        ConsoleLog.append(
            ConsoleLog.BUILD,
            if (code == 0) ConsoleLog.Level.INFO else ConsoleLog.Level.ERROR,
            "[${command.take(40)}${if (command.length > 40) "…" else ""}] exit code ${code ?: "?"}, ${found.size} problems"
        )
        problems = found
    }
}
