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

package x.editor.app.lsp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import x.github.module.lsp.LanguageServerConnection
import x.github.module.lsp.ProcessConnection
import x.github.module.lsp.SocketConnection
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Termux, which runs servers installed in it (`pkg install clang socat`) on the app's behalf. */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val BIN = "/data/data/com.termux/files/usr/bin"
    const val HOME = "/data/data/com.termux/files/home"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Has Termux run [command] in the background (its RUN_COMMAND service). Needs the
     * permission, and `allow-external-apps = true` in `~/.termux/termux.properties`.
     */
    @Throws(IOException::class)
    fun run(context: Context, command: TermuxCommand, result: PendingIntent? = null) {
        if (!isInstalled(context)) throw IOException("Termux is not installed")
        if (!hasPermission(context)) throw IOException("no permission to run commands in Termux ($PERMISSION)")
        val path = if (command.command.startsWith("/")) command.command else "$BIN/${command.command}"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            component = ComponentName(PACKAGE, "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", path)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", command.arguments.toTypedArray())
            putExtra("com.termux.RUN_COMMAND_WORKDIR", command.workingDirectory ?: HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            // what came of it: Termux sends it back when the command ends, or is refused
            if (result != null) putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            // the app is in the background: only a foreground service may be started
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else throw IOException(e)
        } catch (e: SecurityException) {
            throw IOException("Termux refused to run ${command.command}: ${e.message}", e)
        }
    }
}

/**
 * A server on [host]:[port] (SocketConnection), started in Termux when nothing listens
 * there yet: with [termux], Termux is asked to run it, and the connection is tried again
 * until it starts listening, for [startupMillis] at most.
 */
class TermuxSocketConnection(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val termux: TermuxCommand?,
    private val startupMillis: Long = 10_000,
) : LanguageServerConnection {

    private var socket: SocketConnection? = null
    // why the last connection failed (refused, not allowed, ...), for the log
    private var lastError: IOException? = null

    override val input: InputStream get() = checkNotNull(socket) { "not started" }.input
    override val output: OutputStream get() = checkNotNull(socket) { "not started" }.output

    override fun start() {
        // a server that listens already (started by the user, or by a client before)
        if (connect()) return
        val command = termux ?: throw IOException("nothing listens on $host:$port, and no termux command to start one")
        // the permission may be being asked for right now: its dialog is waited for
        if (Termux.isInstalled(context) && !Termux.hasPermission(context)) {
            LspLog.i("waiting for the permission to run commands in Termux")
            val asked = System.currentTimeMillis() + PERMISSION_WAIT_MILLIS
            while (!Termux.hasPermission(context) && System.currentTimeMillis() < asked) sleep(300)
        }
        LspLog.i("cannot connect to $host:$port (${reason(lastError)}): asking Termux to run ${command.command} ${command.arguments.joinToString(" ")}")
        // What Termux says of the command, if it ends (or is refused) while the server is
        // waited for: a server that runs does not end, one that cannot start ends at once.
        val ended = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val action = "${context.packageName}.TERMUX_RESULT.${System.nanoTime()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val outcome = describeResult(intent)
                LspLog.w("Termux: ${command.command} ended: $outcome")
                ended.set(outcome)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(action).setPackage(context.packageName), flags or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            Termux.run(context, command, pending)
            val deadline = System.currentTimeMillis() + startupMillis
            // the port is taken: something listens there already (a bridge started before)
            var portTaken = false
            while (System.currentTimeMillis() < deadline) {
                sleep(250)
                if (connect()) {
                    LspLog.i("connected to $host:$port")
                    return
                }
                val outcome = ended.get() ?: continue
                if (outcome.contains("Address already in use", ignoreCase = true)) {
                    // not a reason to give up: what listens there is tried until the deadline
                    if (!portTaken) LspLog.w("$host:$port is taken (by a bridge started before?): connecting to it")
                    portTaken = true
                } else {
                    // it ended (or Termux refused it): no use waiting for it
                    throw IOException("Termux: ${command.command} ended: $outcome")
                }
            }
            LspLog.w("last connection to $host:$port: ${reason(lastError)}")
            throw IOException(
                if (portTaken) {
                    "something listens on $host:$port, but X Editor cannot connect to it: ${reason(lastError)}. ${hint(lastError)}"
                } else {
                    "Termux did not start a server on $host:$port (${reason(lastError)}; allow-external-apps = true in " +
                        "~/.termux/termux.properties? is ${command.command} installed? run it in Termux to see)"
                }
            )
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // not registered
            }
        }
    }

    // what Termux's result bundle says: the error, the exit code, what was written to stderr
    private fun describeResult(intent: Intent): String {
        val result = intent.getBundleExtra("result") ?: return "no result (Termux too old?)"
        val parts = ArrayList<String>()
        result.getString("errmsg")?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        if (result.containsKey("exitCode")) parts += "exit code ${result.getInt("exitCode")}"
        result.getString("stderr")?.takeIf { it.isNotBlank() }?.let { parts += "stderr: " + it.trim().take(500) }
        result.getString("stdout")?.takeIf { it.isNotBlank() }?.let { parts += "stdout: " + it.trim().take(300) }
        return parts.joinToString("; ").ifEmpty { "ended without output" }
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            throw IOException("interrupted", e)
        }
    }

    private fun connect(): Boolean {
        val attempt = SocketConnection(host, port, connectTimeoutMillis = 1_000)
        return try {
            attempt.start()
            socket = attempt
            true
        } catch (e: IOException) {
            lastError = e
            attempt.close()
            false
        }
    }

    // a connection that failed, in a few words: the exception and its message
    private fun reason(e: IOException?): String =
        if (e == null) "no attempt" else "${e.javaClass.simpleName}: ${e.message ?: "-"}"

    // what to do about it
    private fun hint(e: IOException?): String {
        val text = (e?.message ?: "") + " " + (e?.cause?.message ?: "")
        return when {
            text.contains("EACCES") || text.contains("EPERM") || text.contains("Permission denied", ignoreCase = true) ->
                "The system does not let X Editor open connections: allow it network access (WLAN and mobile data) " +
                    "in its app settings, or in the system's network control for apps."
            text.contains("ECONNREFUSED") || text.contains("refused", ignoreCase = true) ->
                "Nothing accepts the connection: run `pkill socat` in Termux and try again."
            text.contains("timed out", ignoreCase = true) || text.contains("ETIMEDOUT") ->
                "The connection gets no answer: a firewall or VPN app may hold it back."
            else -> "Run `pkill socat` in Termux and try again."
        }
    }

    override fun close() {
        socket?.close()
    }

    override fun toString() = "TermuxSocketConnection($host:$port)"

    private companion object {
        // how long the dialog asking for the permission to run commands in Termux is waited for
        const val PERMISSION_WAIT_MILLIS = 30_000L
    }
}

/**
 * Servers the app starts itself, from binaries the user installed: put in the `bin/`
 * directory of the configuration (LanguageServers.configDirectory), with what they link
 * against in `lib/`. The app ships none: they would make the APK too large.
 *
 * The shared storage cannot hold executables (it is mounted noexec), and an app that
 * targets Android 10 or later may not execute files of its data directory either: the
 * binaries are copied to the app's own storage, and started through the dynamic linker
 * (`/system/bin/linker64 server ...`), which may load them. So a server has to be built
 * for Android (bionic), dynamically linked. Servers of Termux's packages need Termux's
 * libraries: run those in Termux (a socket connection).
 */
object InstalledServers {

    private const val TAG = "InstalledServers"

    /** The connection to the process of [command], started in [workingDirectory]. */
    fun connection(
        context: Context,
        command: List<String>,
        environment: Map<String, String>,
        workingDirectory: File?,
    ): LanguageServerConnection = DeferredProcessConnection(context.applicationContext, command, environment, workingDirectory)

    // copies the binaries and libraries in, and only then starts the process (off the main thread)
    private class DeferredProcessConnection(
        private val context: Context,
        private val command: List<String>,
        private val environment: Map<String, String>,
        private val workingDirectory: File?,
    ) : LanguageServerConnection {
        private var process: ProcessConnection? = null

        override val input: InputStream get() = checkNotNull(process) { "not started" }.input
        override val output: OutputStream get() = checkNotNull(process) { "not started" }.output

        override fun start() {
            val config = LanguageServers.configDirectory(context)
            val bin = File(context.filesDir, "lsp/bin")
            val lib = File(context.filesDir, "lsp/lib")
            val home = File(context.filesDir, "lsp/home").apply { mkdirs() }
            copyNewer(File(config, "bin"), bin, executable = true)
            copyNewer(File(config, "lib"), lib, executable = false)

            val name = command.first()
            val binary = if (name.startsWith("/")) File(name) else File(bin, name)
            if (!binary.isFile) {
                throw IOException("$name is not installed: put it in ${File(config, "bin")}")
            }
            binary.setExecutable(true, false)
            val ownData = binary.canonicalPath.startsWith(File(context.applicationInfo.dataDir).canonicalPath)
            val launch = if (ownData && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                listOf(linker(), binary.path) + command.drop(1)
            } else {
                listOf(binary.path) + command.drop(1)
            }
            val env = mapOf(
                "LD_LIBRARY_PATH" to lib.path,
                "HOME" to home.path,
                "TMPDIR" to context.cacheDir.path,
            ) + environment
            LspLog.i("starting ${launch.joinToString(" ")}")
            process = ProcessConnection(launch, workingDirectory, env) { line -> LspLog.i("$name: $line") }
                .also { it.start() }
        }

        override fun close() {
            process?.close()
        }

        override fun toString() = "InstalledServer(${command.joinToString(" ")})"
    }

    private fun linker(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    // the files of [from] that [to] does not have, or has older or of another size
    private fun copyNewer(from: File, to: File, executable: Boolean) {
        val files = from.listFiles() ?: return
        to.mkdirs()
        for (source in files) {
            if (!source.isFile) continue
            val target = File(to, source.name)
            if (target.isFile && target.length() == source.length() && target.lastModified() >= source.lastModified()) continue
            try {
                val partial = File(to, source.name + ".part")
                source.inputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (!partial.renameTo(target)) throw IOException("cannot replace $target")
                target.setLastModified(source.lastModified())
                if (executable) target.setExecutable(true, false)
            } catch (e: IOException) {
                Log.w(TAG, "cannot install $source", e)
            }
        }
    }
}
