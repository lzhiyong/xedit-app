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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What a toolchain says of its version, as the toolchain sheet shows on a tap on its row:
 * `java -version`, `clang --version`, run where it is installed: in X Editor's usr (a
 * shell of the system with the toolchain's environment, Toolchain.environment). What is
 * run is the package's `"version_command"`, else each of its commands with the flag it
 * takes ([versionArgs]).
 *
 * What is printed is cut to its first lines ([MAX_LINES]), blank lines and rules (`-----`)
 * left out; nothing waits for input (stdin is empty), and what does not end in time
 * ([TIMEOUT_SECONDS]) is stopped.
 */
object ToolVersions {

    /** What one line said: [command] as run, its [output] (cut), or why it could not be run ([error]). */
    class Section(val command: String, val output: String, val error: String? = null)

    /**
     * The lines to run for [pkg] (null: not listed) whose commands of [programs] are there
     * (a program that does not say its version itself is asked through the one of its
     * package that does, [versionCommandOf]).
     */
    fun commandsFor(pkg: ToolPackage?, programs: Collection<String>): List<String> {
        pkg?.versionCommand?.takeIf { it.isNotEmpty() }?.let { return it }
        return programs.distinct().map { versionCommandOf(it) }.distinct().take(MAX_COMMANDS)
    }

    /**
     * The line that says the version of [program]: it with its flag ([versionArgs]), or, for
     * one that takes no such flag, the program of its package that does. pyright-langserver
     * starts the server and waits for a client (it has no --version): `pyright --version`
     * says it.
     */
    fun versionCommandOf(program: String): String = when (program) {
        "pyright-langserver" -> "pyright --version"
        else -> "$program ${versionArgs(program)}"
    }

    /** The flag (or word) after which [program] says its version. */
    fun versionArgs(program: String): String = when (program) {
        "java", "javac", "javap", "jar", "kotlin", "kotlinc", "kotlinc-jvm", "scala", "scalac", "ecj" -> "-version"
        "go" -> "version"
        "lua", "luac", "luajit", "lua5.1", "lua5.2", "lua5.3", "lua5.4" -> "-v"
        "socat" -> "-V"
        else -> "--version"
    }

    /** Runs [commands] in X Editor's usr, one after another (on a thread of IO). */
    suspend fun local(context: Context, commands: List<String>): List<Section> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val env = Toolchain.environment(app)
        val preamble = Toolchain.shellPreamble(app)
        commands.map { command -> runLocal(app, env, preamble, command) }
    }

    private fun runLocal(app: Context, env: Map<String, String>, preamble: String, command: String): Section {
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", "$preamble{ $command\n} 2>&1")
                .directory(Usr.tmp(app).apply { mkdirs() })
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(env)
                }
                .start()
        } catch (e: IOException) {
            return Section(command, "", e.message ?: e.toString())
        }
        // nothing to read: a program that asks waits for nothing
        try {
            process.outputStream.close()
        } catch (ignored: IOException) {
        }
        val out = StringBuilder()
        val reader = thread(name = "version $command") {
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(out) { if (out.length < MAX_CHARS) out.append(buffer, 0, n) }
                    }
                }
            } catch (ignored: IOException) {
                // stopped
            }
        }
        val ended = try {
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ended) process.destroyForcibly()
        reader.join(1000)
        val text = clean(synchronized(out) { out.toString() })
        return when {
            !ended -> Section(command, text, "no answer in $TIMEOUT_SECONDS s")
            text.isEmpty() && process.exitValue() != 0 -> Section(command, "", "exit ${process.exitValue()}")
            else -> Section(command, text)
        }
    }

    /** [text] cut to its first lines, blank lines and rules left out ("…" if there was more). */
    internal fun clean(text: String): String {
        val lines = text.lines().map { it.trimEnd() }
            .filter { line -> line.isNotBlank() && !line.all { it == '-' || it == '=' || it == '_' || it == ' ' } }
        return if (lines.size <= MAX_LINES) lines.joinToString("\n")
        else (lines.take(MAX_LINES) + "…").joinToString("\n")
    }

    private const val MAX_COMMANDS = 4
    private const val MAX_LINES = 6
    private const val MAX_CHARS = 8192
    private const val TIMEOUT_SECONDS = 15L
}
