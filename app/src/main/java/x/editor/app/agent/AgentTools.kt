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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import x.editor.app.lsp.Termux
import x.editor.app.toolchain.Toolchain
import x.editor.app.toolchain.ToolchainRules
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What a tool may do: read (run at once), change files, run commands (both asked first, see AgentSettings.Approval). */
enum class ToolRisk { READ, WRITE, COMMAND }

/**
 * What a tool came to: the [content] the model is given, the file it changed (the editor
 * reads it again), and a file it only read ([shownFile]: View on its card shows it).
 */
data class ToolOutcome(val content: String, val changedFile: File? = null, val shownFile: File? = null)

/** A tool of the agent. */
interface AgentTool {
    val spec: ToolSpec
    val risk: ToolRisk

    /** A line about a call, for the screen: the path, the command. */
    fun summary(input: JsonObject): String

    /** What the user is to look at before allowing the call (a diff, a command); null: the summary says it. */
    suspend fun preview(input: JsonObject): String? = null

    /**
     * Runs the call, telling [progress] what it prints as it goes (run_command; the others
     * say nothing until they are done). @throws ToolException if it cannot.
     */
    suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome
}

/** What a call prints while it runs, as it is printed (from any thread). */
fun interface ToolProgress {
    fun output(text: String)

    companion object {
        val NONE = ToolProgress { }
    }
}

// the inputs, as the schemas have them
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
internal fun JsonObject.required(name: String): String = string(name) ?: throw ToolException("the input has no \"$name\"")

/** A JSON schema of an object of [properties] (name to type and description), [required] of them required. */
internal fun schema(required: List<String>, vararg properties: Triple<String, String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, type, description) in properties) {
            putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
        }
    }
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
}

/**
 * The tools of the agent, on [workspace]: list, read and search its files (READ); write a
 * file, edit one by an exact replacement (WRITE); run a shell command in its root with the
 * app's toolchain (COMMAND).
 */
class AgentTools(private val context: Context, private val workspace: Workspace) {

    val all: List<AgentTool> = listOf(ListFiles(), ReadFile(), SearchFiles(), WriteFile(), EditFile(), RunCommand())

    fun find(name: String): AgentTool? = all.firstOrNull { it.spec.name == name }

    // #region reading

    private inner class ListFiles : AgentTool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(
            "list_files",
            "List the files and directories under a directory of the workspace, as a tree (directories end with /). " +
                "Build output and VCS directories (.git, build, node_modules, ...) are named but not listed.",
            schema(
                emptyList(),
                Triple("path", "string", "Directory, relative to the workspace root (default: the root)"),
                Triple("depth", "integer", "How many levels deep to list, 1 to 5 (default 2)"),
            ),
        )

        override fun summary(input: JsonObject) = input.string("path")?.ifBlank { null } ?: "."

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome = withContext(Dispatchers.IO) {
            val dir = workspace.resolveForRead(input.string("path").orEmpty())
            val depth = (input.int("depth") ?: 2).coerceIn(1, 5)
            if (workspace.kindOf(dir) != Workspace.Kind.DIRECTORY) throw ToolException("${workspace.relative(dir)} is not a directory")
            val out = StringBuilder(workspace.relative(dir)).append("/\n")
            var count = 0
            fun walk(at: File, level: Int, indent: String) {
                val entries = workspace.list(at)?.sortedWith(compareBy({ !it.second }, { it.first.lowercase() })) ?: return
                for ((name, isDir) in entries) {
                    ensureActive()
                    if (count >= MAX_LIST_ENTRIES) return
                    count++
                    out.append(indent).append(name)
                    if (isDir) {
                        out.append('/')
                        when {
                            name in SKIPPED_DIRECTORIES -> out.append(" (not listed)\n")
                            level < depth -> {
                                out.append('\n')
                                walk(File(at, name), level + 1, "$indent  ")
                            }
                            else -> out.append('\n')
                        }
                    } else {
                        out.append('\n')
                    }
                }
            }
            walk(dir, 1, "  ")
            if (count >= MAX_LIST_ENTRIES) out.append("… (stopped at $MAX_LIST_ENTRIES entries: list a subdirectory)\n")
            ToolOutcome(out.toString())
        }
    }

    private inner class ReadFile : AgentTool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(
            "read_file",
            "Read a text file of the workspace. Each line comes after its number and a tab (the number is not part of the file). " +
                "Large files: read them in ranges of lines.",
            schema(
                listOf("path"),
                Triple("path", "string", "File, relative to the workspace root"),
                Triple("start_line", "integer", "First line to read, from 1 (default 1)"),
                Triple("end_line", "integer", "Last line to read (default: up to $MAX_READ_LINES lines on)"),
            ),
        )

        override fun summary(input: JsonObject): String {
            val path = input.string("path").orEmpty()
            val start = input.int("start_line")
            val end = input.int("end_line")
            return if (start != null || end != null) "$path:${start ?: 1}-${end ?: ""}" else path
        }

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome = withContext(Dispatchers.IO) {
            val file = workspace.resolveForRead(input.required("path"))
            val text = workspace.read(file)
            val lines = AgentText.lines(text)
            val total = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size
            if (total == 0 || text.isEmpty()) return@withContext ToolOutcome("(${workspace.relative(file)} is empty)")
            val from = (input.int("start_line") ?: 1).coerceIn(1, total)
            val to = (input.int("end_line") ?: (from + MAX_READ_LINES - 1)).coerceIn(from, minOf(total, from + MAX_READ_LINES - 1))
            val body = AgentText.clip(AgentText.numbered(lines, from, to), MAX_READ_CHARS)
            val note = if (from > 1 || to < total) "\n(lines $from-$to of $total)" else ""
            ToolOutcome(body + note, shownFile = file)
        }
    }

    private inner class SearchFiles : AgentTool {
        override val risk = ToolRisk.READ
        override val spec = ToolSpec(
            "search_files",
            "Search the text files of the workspace for a string (or a regular expression). " +
                "Answers with path:line: text for each line found.",
            schema(
                listOf("query"),
                Triple("query", "string", "What to look for"),
                Triple("path", "string", "Directory or file to search in, relative to the workspace root (default: the root)"),
                Triple("regex", "boolean", "Whether query is a regular expression (default false)"),
                Triple("case_sensitive", "boolean", "Default false"),
                Triple("glob", "string", "Only files whose name matches, such as *.kt or *.{c,h}"),
            ),
        )

        override fun summary(input: JsonObject): String {
            val where = input.string("path")?.ifBlank { null }
            val glob = input.string("glob")?.ifBlank { null }
            return listOfNotNull("\"" + input.string("query").orEmpty() + "\"", where, glob).joinToString(" · ")
        }

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome = withContext(Dispatchers.IO) {
            val query = input.required("query")
            if (query.isEmpty()) throw ToolException("query is empty")
            val options = if (input.bool("case_sensitive") == true) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = try {
                if (input.bool("regex") == true) Regex(query, options) else Regex(Regex.escape(query), options)
            } catch (e: IllegalArgumentException) {
                throw ToolException("not a valid regular expression: ${e.message}")
            }
            val glob = input.string("glob")?.ifBlank { null }?.let(AgentText::globRegex)
            val start = workspace.resolveForRead(input.string("path").orEmpty())
            val out = StringBuilder()
            var found = 0
            var files = 0
            fun searchFile(file: File) {
                if (glob != null && !glob.matches(file.name)) return
                if (file.extension.lowercase() in BINARY_EXTENSIONS) return
                files++
                val text = try {
                    workspace.read(file, MAX_SEARCH_FILE_BYTES)
                } catch (e: ToolException) {
                    return
                }
                if (text.indexOf('\u0000') >= 0) return
                AgentText.lines(text).forEachIndexed { index, line ->
                    if (found < MAX_SEARCH_RESULTS && pattern.containsMatchIn(line)) {
                        found++
                        out.append(workspace.relative(file)).append(':').append(index + 1).append(": ")
                            .append(line.trim().take(200)).append('\n')
                    }
                }
            }
            fun walk(dir: File) {
                val entries = workspace.list(dir) ?: return
                for ((name, isDir) in entries.sortedBy { it.first }) {
                    ensureActive()
                    if (found >= MAX_SEARCH_RESULTS || files >= MAX_SEARCH_FILES) return
                    val child = File(dir, name)
                    if (isDir) {
                        if (name !in SKIPPED_DIRECTORIES) walk(child)
                    } else {
                        searchFile(child)
                    }
                }
            }
            when (workspace.kindOf(start)) {
                Workspace.Kind.DIRECTORY -> walk(start)
                Workspace.Kind.FILE -> searchFile(start)
                Workspace.Kind.MISSING -> throw ToolException("${workspace.relative(start)} does not exist")
            }
            when {
                found == 0 -> ToolOutcome("No matches ($files files searched)")
                found >= MAX_SEARCH_RESULTS -> ToolOutcome(out.append("… (stopped at $MAX_SEARCH_RESULTS matches: search more narrowly)").toString())
                else -> ToolOutcome(out.toString().trimEnd())
            }
        }
    }

    // #endregion

    // #region changing files

    private inner class WriteFile : AgentTool {
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec(
            "write_file",
            "Create a file of the workspace, or replace all of an existing one, with the given content. " +
                "To change part of a file use edit_file instead. Directories are created as needed.",
            schema(
                listOf("path", "content"),
                Triple("path", "string", "File, relative to the workspace root"),
                Triple("content", "string", "The whole content of the file"),
            ),
        )

        override fun summary(input: JsonObject) = input.string("path").orEmpty()

        override suspend fun preview(input: JsonObject): String = withContext(Dispatchers.IO) {
            val file = workspace.resolve(input.required("path"))
            val content = input.string("content").orEmpty()
            val old = if (workspace.kindOf(file) == Workspace.Kind.FILE) runCatching { workspace.read(file) }.getOrNull() else null
            val head = if (old == null) "(new file)" else "(replaces ${AgentText.lines(old).size} lines)"
            head + "\n" + AgentText.diff("", content, PREVIEW_LINES)
        }

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome = withContext(Dispatchers.IO) {
            val file = workspace.resolve(input.required("path"))
            val content = input.string("content") ?: throw ToolException("the input has no \"content\"")
            if (workspace.kindOf(file) == Workspace.Kind.DIRECTORY) throw ToolException("${workspace.relative(file)} is a directory")
            workspace.write(file, content)
            ToolOutcome("Wrote ${AgentText.lines(content).size} lines to ${workspace.relative(file)}", file)
        }
    }

    private inner class EditFile : AgentTool {
        override val risk = ToolRisk.WRITE
        override val spec = ToolSpec(
            "edit_file",
            "Change part of a file of the workspace: old_text, copied exactly from the file (spaces and line ends included, " +
                "no line numbers), is replaced by new_text. old_text must be in the file only once (give a few lines around " +
                "the change), unless replace_all is true. Read the file first.",
            schema(
                listOf("path", "old_text", "new_text"),
                Triple("path", "string", "File, relative to the workspace root"),
                Triple("old_text", "string", "The exact text to replace"),
                Triple("new_text", "string", "What to put in its place (empty: delete it)"),
                Triple("replace_all", "boolean", "Replace every occurrence (default false)"),
            ),
        )

        override fun summary(input: JsonObject) = input.string("path").orEmpty()

        override suspend fun preview(input: JsonObject): String =
            AgentText.diff(input.string("old_text").orEmpty(), input.string("new_text").orEmpty(), PREVIEW_LINES)

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome = withContext(Dispatchers.IO) {
            val file = workspace.resolve(input.required("path"))
            val old = input.required("old_text")
            val new = input.string("new_text").orEmpty()
            val text = workspace.read(file)
            val changed = AgentText.replace(text, old, new, input.bool("replace_all") == true)
            workspace.write(file, changed)
            ToolOutcome("Edited ${workspace.relative(file)}", file)
        }
    }

    // #endregion

    // #region commands

    private inner class RunCommand : AgentTool {
        override val risk = ToolRisk.COMMAND
        override val spec = ToolSpec(
            "run_command",
            "Run a shell command in the workspace root. Where it runs is picked for it: in the app (/system/bin/sh, with " +
                "the compilers and tools installed into the app on the PATH) when the app has every program the command " +
                "runs, otherwise in Termux (its bash, with what was installed there with pkg), if Termux is installed and " +
                "the app may use it. The answer says where it ran. No root. Answers with its exit code and what it printed " +
                "(stdout and stderr together, cut if long). Not interactive: nothing can be typed into it.",
            schema(
                listOf("command"),
                Triple("command", "string", "The command line"),
                Triple("timeout_seconds", "integer", "Stop it after this many seconds (default $DEFAULT_TIMEOUT_S, at most $MAX_TIMEOUT_S)"),
            ),
        )

        override fun summary(input: JsonObject) = input.string("command").orEmpty()

        // the command, and where it would run if the app lacks a program of it
        override suspend fun preview(input: JsonObject): String {
            val command = input.string("command").orEmpty()
            val missing = withContext(Dispatchers.IO) { missingLocally(command) }
            return when {
                missing.isEmpty() -> "$ $command"
                AgentTermux.usable(context) -> "$ $command\n# in Termux (not in the app: ${missing.joinToString(", ")})"
                else -> "$ $command"
            }
        }

        override suspend fun run(input: JsonObject, progress: ToolProgress): ToolOutcome {
            val command = input.required("command")
            val timeout = (input.int("timeout_seconds") ?: DEFAULT_TIMEOUT_S).coerceIn(1, MAX_TIMEOUT_S)
            val dir = workspace.root
            if (!dir.isDirectory) {
                throw ToolException(
                    "the workspace cannot be reached by its path (the app has no all files access), so commands cannot run in it"
                )
            }
            // In the app if it has every program the command runs; otherwise in Termux (if
            // Termux may be asked), the programs the app lacks said with the answer.
            val missing = withContext(Dispatchers.IO) { missingLocally(command) }
            if (missing.isNotEmpty() && AgentTermux.usable(context)) {
                val result = AgentTermux.run(context, dir.path, command, timeout, progress)
                val where = "in Termux: ${missing.joinToString(", ")} not in the app"
                val code = result.exitCode
                if (code == null) {
                    val printed = AgentText.clip(result.output, MAX_OUTPUT_CHARS).trimEnd()
                    throw ToolException(
                        "Termux did not run it (${result.error})" + if (printed.isEmpty()) "" else "\n$printed"
                    )
                }
                val text = AgentText.clip(result.output, MAX_OUTPUT_CHARS).trimEnd()
                val head = if (result.timedOut) "Stopped after $timeout s (timed out), $where" else "Exit code $code ($where)"
                return ToolOutcome(if (text.isEmpty()) "$head (no output)" else "$head\n$text")
            }
            val outcome = runLocally(dir, command, timeout, progress)
            if (missing.isEmpty()) return outcome
            // not in the app, and Termux cannot be asked: said, after what the shell said
            val termux = if (!Termux.isInstalled(context)) {
                "Termux is not installed"
            } else {
                "the app may not run commands in Termux (the user can allow it in the app's permissions)"
            }
            return ToolOutcome(outcome.content + "\n(${missing.joinToString(", ")} not in the app, and $termux)")
        }

        // Of the programs [command] runs (CommandNames), those the app's shell does not have
        // (no program on its PATH, no builtin or function by that name): asked of the shell
        // itself, with the app's tools, as the command would run.
        private fun missingLocally(command: String): List<String> {
            val names = CommandNames.of(command)
            if (names.isEmpty()) return emptyList()
            val check = "for c in " + names.joinToString(" ") { ToolchainRules.quote(it) } +
                "; do command -v \"\$c\" >/dev/null 2>&1 || echo \"\$c\"; done; true"
            val builder = ProcessBuilder("/system/bin/sh", "-c", Toolchain.shellPreamble(context) + check)
                .redirectErrorStream(true)
            builder.environment().apply {
                clear()
                putAll(Toolchain.environment(context))
            }
            return try {
                val process = builder.start()
                process.outputStream.close()
                val printed = process.inputStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(CHECK_TIMEOUT_S, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return emptyList()
                }
                printed.lineSequence().map { it.trim() }.filter { it in names }.toList()
            } catch (e: IOException) {
                // the check could not be made: the command runs in the app, as before
                emptyList()
            }
        }

        // [command] run by the app's shell in [dir], with the app's tools, what it prints
        // told to [progress] as it comes
        private suspend fun runLocally(dir: File, command: String, timeout: Int, progress: ToolProgress): ToolOutcome =
            withContext(Dispatchers.IO) {
            val builder = ProcessBuilder("/system/bin/sh", "-c", Toolchain.shellPreamble(context) + command)
                .directory(dir)
                .redirectErrorStream(true)
            builder.environment().apply {
                clear()
                putAll(Toolchain.environment(context))
                put("PWD", dir.path)
            }
            val process = try {
                builder.start()
            } catch (e: IOException) {
                throw ToolException("cannot start the shell: ${e.message}")
            }
            process.outputStream.close()
            val output = StringBuilder()
            val reader = thread(name = "agent-command") {
                try {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            progress.output(String(buffer, 0, n))
                            synchronized(output) {
                                output.append(buffer, 0, n)
                                // keep the start and the end of a long output
                                if (output.length > KEEP_OUTPUT_CHARS * 2) {
                                    val clipped = AgentText.clip(output.toString(), KEEP_OUTPUT_CHARS)
                                    output.setLength(0)
                                    output.append(clipped)
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    // the process was stopped
                }
            }
            var timedOut = false
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.toLong())
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    ensureActive()
                    if (System.nanoTime() > deadline) {
                        timedOut = true
                        break
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                reader.join(2000)
            }
            val text = synchronized(output) { AgentText.clip(output.toString(), MAX_OUTPUT_CHARS) }.trimEnd()
            val head = when {
                timedOut -> "Stopped after $timeout s (timed out)"
                else -> "Exit code ${process.exitValue()}"
            }
            ToolOutcome(if (text.isEmpty()) "$head (no output)" else "$head\n$text")
        }
    }

    // #endregion

    private companion object {
        val SKIPPED_DIRECTORIES = setOf(
            ".git", ".svn", ".hg", ".gradle", ".idea", ".cxx", ".kotlin", "build", "node_modules",
            "__pycache__", ".venv", "venv", "target", ".dart_tool", "Pods",
        )
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "jar", "aar", "apk", "class", "dex",
            "so", "a", "o", "obj", "exe", "dll", "bin", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "wav", "ogg",
            "gz", "xz", "tar", "7z", "keystore", "jks",
        )
        const val MAX_LIST_ENTRIES = 500
        const val MAX_READ_LINES = 800
        const val MAX_READ_CHARS = 60_000
        const val MAX_SEARCH_RESULTS = 150
        const val MAX_SEARCH_FILES = 4000
        const val MAX_SEARCH_FILE_BYTES = 1024L * 1024
        const val PREVIEW_LINES = 60
        const val DEFAULT_TIMEOUT_S = 120
        const val MAX_TIMEOUT_S = 900
        const val KEEP_OUTPUT_CHARS = 40_000
        const val MAX_OUTPUT_CHARS = 20_000
        // how long the app's shell is given to say which programs it has
        const val CHECK_TIMEOUT_S = 10L
    }
}
