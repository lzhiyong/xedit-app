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

package x.editor.app.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import x.editor.app.lsp.Termux
import x.editor.app.lsp.TermuxCommand
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * A command of run_command run in Termux (AgentTools, when the app's own tools do not have
 * a program it runs): Termux's bash, as a login shell (Termux's PATH, what `pkg install`
 * put there), in the workspace, in the background (RUN_COMMAND); its exit code is sent back
 * by Termux when it ends.
 *
 * What it prints comes here as it is printed, as the console's runs in Termux do
 * (BuildRunner): Termux runs [SCRIPT], which goes into the workspace (or says Termux cannot
 * reach it), connects to a socket of the app's on 127.0.0.1 (bash's /dev/tcp), says the
 * token of the run on it, and runs the command under `timeout` (stopped after its seconds)
 * with its stdout and stderr on that connection; each piece read is told to the progress
 * as it comes, and kept (its start and its end, if long). A line `TOKEN streamed` to
 * Termux's own stdout says it was. If the connection cannot be made, what the command
 * prints goes into a file of Termux's, and comes back with Termux's answer (its start and
 * its end if long: Termux sends back about 100 KB at most).
 *
 * Stopped from here (the answer given up on: the turn stopped), Termux is asked to kill the
 * command and what it started, by the pid the script left in a file of Termux's.
 */
internal object AgentTermux {

    /**
     * What came of a command: [exitCode] (null if Termux did not run it: [error] says why),
     * what it printed, and whether it was stopped for taking longer than it was given.
     */
    class Result(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val error: String?,
        val streamed: Boolean = false,
    )

    /** Whether Termux can be asked to run commands: installed, and the app allowed to. */
    fun usable(context: Context): Boolean = Termux.isInstalled(context) && Termux.hasPermission(context)

    /** [command] run by Termux in [dir], stopped after [timeoutSeconds]; what it prints told to [progress]. */
    suspend fun run(context: Context, dir: String, command: String, timeoutSeconds: Int, progress: ToolProgress): Result {
        val app = context.applicationContext
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val stream = Stream(token, progress)
        return try {
            // (Termux's answer waited for a while longer than the command may take)
            withTimeout((timeoutSeconds + ANSWER_GRACE_S) * 1000L) {
                suspendCancellableCoroutine { continuation ->
                    val action = "${app.packageName}.AGENT_TERMUX.$token"
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, intent: Intent) {
                            unregister(app, this)
                            // (what is still coming on the connection waited for, off the main thread)
                            thread(name = "agent-termux-end") {
                                val result = stream.finish(resultOf(intent, token))
                                if (continuation.isActive) continuation.resume(result)
                            }
                        }
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pending = PendingIntent.getBroadcast(
                        app, action.hashCode(), Intent(action).setPackage(app.packageName),
                        flags or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("UnspecifiedRegisterReceiverFlag")
                        app.registerReceiver(receiver, IntentFilter(action))
                    }
                    continuation.invokeOnCancellation {
                        unregister(app, receiver)
                        stream.close()
                        stop(app, token)
                    }
                    try {
                        Termux.run(
                            app,
                            TermuxCommand(
                                "bash",
                                listOf(
                                    "-c", SCRIPT, "xedit-agent", dir, timeoutSeconds.toString(), command, token,
                                    stream.port.toString(),
                                ),
                            ),
                            pending,
                        )
                    } catch (e: IOException) {
                        unregister(app, receiver)
                        stream.close()
                        if (continuation.isActive) {
                            continuation.resume(Result(null, "", false, e.message ?: e.toString()))
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            stream.close()
            Result(
                null, "", false,
                "Termux gave no answer in ${timeoutSeconds + ANSWER_GRACE_S} s (it may have been stopped, or " +
                    "allow-external-apps is not true in ~/.termux/termux.properties)",
            )
        }
    }

    // what Termux sent back: the output (but the line saying it was streamed), the exit
    // code (124: timeout stopped it), or why it did not run the command
    private fun resultOf(intent: Intent, token: String): Result {
        val bundle = intent.getBundleExtra("result")
            ?: return Result(null, "", false, "Termux sent back no result (too old a Termux?)")
        val stdout = bundle.getString("stdout").orEmpty()
            .lineSequence().filter { it.trim() != "$token $STREAMED" }.joinToString("\n")
        val stderr = bundle.getString("stderr").orEmpty()
        val output = if (stderr.isBlank()) stdout else stdout + (if (stdout.isEmpty() || stdout.endsWith("\n")) "" else "\n") + stderr
        val errmsg = bundle.getString("errmsg")?.trim().orEmpty()
        if (!bundle.containsKey("exitCode")) {
            return Result(null, output, false, errmsg.ifEmpty { "Termux did not run the command" })
        }
        val code = bundle.getInt("exitCode")
        val streamed = bundle.getString("stdout").orEmpty().lineSequence().any { it.trim() == "$token $STREAMED" }
        return Result(code, output, code == EXIT_TIMED_OUT || code == EXIT_KILLED_AFTER_TIMEOUT, null, streamed)
    }

    /**
     * The socket the run's output comes on: accepts the connection that starts with [token]
     * (others' tries are dropped), tells [progress] each piece read and keeps it (its start
     * and its end, if long). Without a socket (none could be opened), [port] is 0: the
     * script then cannot connect, and the output comes with Termux's answer.
     */
    private class Stream(private val token: String, private val progress: ToolProgress) {
        private val server: ServerSocket? = try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) {
            null
        }
        val port: Int get() = server?.localPort ?: 0
        private val kept = StringBuilder()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val readers = CopyOnWriteArrayList<Thread>()

        init {
            val server = server
            if (server != null) thread(name = "agent-termux-accept") {
                try {
                    while (readers.size < MAX_CONNECTIONS) {
                        val socket = server.accept()
                        sockets += socket
                        readers += thread(name = "agent-termux-stream") { read(socket) }
                    }
                } catch (e: IOException) {
                    // closed: the run ended
                }
            }
        }

        // one connection: the token on its first line, then what the command prints
        private fun read(socket: Socket) {
            try {
                socket.use { s ->
                    s.soTimeout = HEADER_TIMEOUT_MILLIS
                    val reader = s.getInputStream().bufferedReader()
                    if (reader.readLine()?.trim() != token) return
                    s.soTimeout = 0
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        val piece = String(buffer, 0, n)
                        progress.output(piece)
                        synchronized(kept) {
                            kept.append(piece)
                            if (kept.length > KEEP_CHARS * 2) {
                                val clipped = AgentText.clip(kept.toString(), KEEP_CHARS)
                                kept.setLength(0)
                                kept.append(clipped)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // closed: the run ended, or was given up
            }
        }

        /**
         * Termux's [result] with what came on the connection in place of its output, if it
         * came there (the last of it waited for a moment: something the command left
         * running may hold the connection open); the socket closed.
         */
        fun finish(result: Result): Result {
            if (result.streamed) {
                val accepted = System.currentTimeMillis() + ACCEPT_WAIT_MILLIS
                while (readers.isEmpty() && System.currentTimeMillis() < accepted) Thread.sleep(20)
                val drained = System.currentTimeMillis() + DRAIN_MILLIS
                for (reader in readers) reader.join((drained - System.currentTimeMillis()).coerceAtLeast(1))
            }
            close()
            for (reader in readers) reader.join(500)
            if (!result.streamed) return result
            val printed = synchronized(kept) { kept.toString() }
            val rest = result.output.trim()
            val output = if (rest.isEmpty()) printed else printed + (if (printed.endsWith("\n") || printed.isEmpty()) "" else "\n") + rest
            return Result(result.exitCode, output, result.timedOut, result.error, streamed = true)
        }

        fun close() {
            runCatching { server?.close() }
            for (socket in sockets) runCatching { socket.close() }
        }
    }

    // the command started for [token] stopped, and what it started (their children first)
    private fun stop(context: Context, token: String) {
        try {
            Termux.run(context, TermuxCommand("bash", listOf("-c", KILL, "xedit-agent-stop", token)))
        } catch (e: IOException) {
            // Termux cannot be asked: the command runs to its timeout
        }
    }

    private fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // gone already
        }
    }

    // `bash -c SCRIPT xedit-agent DIR SECONDS COMMAND TOKEN PORT`: on the app's socket if it
    // can connect (python asked not to keep its output back: no terminal), else into a file
    private const val SCRIPT = """
t="${'$'}{TMPDIR:-/data/data/com.termux/files/usr/tmp}"
p="${'$'}t/xedit-agent-${'$'}4.pid"
o="${'$'}t/xedit-agent-${'$'}4.out"
echo ${'$'}${'$'} > "${'$'}p" 2>/dev/null
if ! cd "${'$'}1" 2>/dev/null; then
  echo "Termux cannot open ${'$'}1. If it is in the shared storage, run termux-setup-storage in Termux once to let Termux reach it."
  rm -f "${'$'}p"
  exit 126
fi
export PYTHONUNBUFFERED=1
if [ "${'$'}5" != 0 ] && { exec 3>"/dev/tcp/127.0.0.1/${'$'}5"; } 2>/dev/null; then
  printf '%s\n' "${'$'}4" >&3
  timeout -k 5 "${'$'}2" bash -lc "${'$'}3" >&3 2>&1 < /dev/null
  r=${'$'}?
  exec 3>&-
  echo "${'$'}4 streamed"
  rm -f "${'$'}p"
  exit ${'$'}r
fi
timeout -k 5 "${'$'}2" bash -lc "${'$'}3" > "${'$'}o" 2>&1 < /dev/null
r=${'$'}?
n=${'$'}(wc -c < "${'$'}o" 2>/dev/null || echo 0)
if [ "${'$'}n" -gt 80000 ]; then
  head -c 40000 "${'$'}o"
  printf '\n[... %s bytes left out ...]\n' ${'$'}((n - 80000))
  tail -c 40000 "${'$'}o"
else
  cat "${'$'}o"
fi
rm -f "${'$'}o" "${'$'}p"
exit ${'$'}r
"""

    // `bash -c KILL xedit-agent-stop TOKEN`: the script's shell of TOKEN and what it started
    private const val KILL = """
t="${'$'}{TMPDIR:-/data/data/com.termux/files/usr/tmp}"
p=${'$'}(cat "${'$'}t/xedit-agent-${'$'}1.pid" 2>/dev/null) || exit 0
k() { for c in ${'$'}(pgrep -P "${'$'}1" 2>/dev/null); do k "${'$'}c"; done; kill -TERM "${'$'}1" 2>/dev/null; }
k "${'$'}p"
rm -f "${'$'}t/xedit-agent-${'$'}1.pid" "${'$'}t/xedit-agent-${'$'}1.out"
"""

    // how much longer than the command may take Termux's answer is waited for
    private const val ANSWER_GRACE_S = 30

    // the line Termux's own stdout gets after the command, when its output was streamed
    private const val STREAMED = "streamed"

    // the connection: how many are taken (others' tries dropped), how long its first line
    // is waited for, how long its connection is waited for once Termux has answered, how
    // long the last of its output; how much of the output is kept (its start and its end)
    private const val MAX_CONNECTIONS = 4
    private const val HEADER_TIMEOUT_MILLIS = 10_000
    private const val ACCEPT_WAIT_MILLIS = 2_000L
    private const val DRAIN_MILLIS = 5_000L
    private const val KEEP_CHARS = 40_000

    // timeout's exit codes: the command stopped at its time, or killed 5 s after that
    private const val EXIT_TIMED_OUT = 124
    private const val EXIT_KILLED_AFTER_TIMEOUT = 137
}
