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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * A language server the user set up: which files it is for, and how to reach it. The app
 * ships no server; see LanguageServers for where these come from.
 *
 * In `servers.json`:
 *
 * ```json
 * {
 *   "servers": [
 *     {
 *       "id": "clangd",
 *       "extensions": ["c", "h", "cc", "cpp", "hpp"],
 *       "languageIds": { "h": "cpp" },
 *       "connection": {
 *         "type": "socket", "port": 5007,
 *         "termux": {
 *           "command": "socat",
 *           "arguments": ["TCP-LISTEN:5007,bind=127.0.0.1,reuseaddr,fork", "EXEC:clangd"]
 *         }
 *       }
 *     },
 *     {
 *       "id": "lua",
 *       "extensions": ["lua"],
 *       "connection": { "type": "process", "command": ["lua-language-server"] }
 *     },
 *     {
 *       "id": "clangd (yours, else Termux's)",
 *       "extensions": ["c", "cpp"],
 *       "connection": { "type": "auto", "command": ["clangd", "--background-index"], "port": 5009 }
 *     }
 *   ]
 * }
 * ```
 */
data class LanguageServerSpec(
    /** A name of its own, for messages and to tell servers apart. */
    val id: String,
    /** The extensions of the files it is for, without the dot, in lower case. */
    val extensions: Set<String>,
    /** How to reach it. */
    val connection: Connection,
    /** The LSP language id of an extension, where it is not the usual one (`h` to `cpp`). */
    val languageIds: Map<String, String> = emptyMap(),
    /** The project root; null: found from [rootMarkers] above the file, else its directory. */
    val root: String? = null,
    /** Files or directories that mark a project root. */
    val rootMarkers: List<String> = DEFAULT_ROOT_MARKERS,
    /** Handed to the server as they are (`initialize`), as maps, lists and primitives. */
    val initializationOptions: Any? = null,
    /** How long the server may take to answer `initialize` (`"initializeTimeout"`, in seconds); null: the client's default. */
    val initializeTimeoutMillis: Long? = null,
    val enabled: Boolean = true,
) {

    /** How to reach a server. */
    sealed class Connection {
        /**
         * A server that listens on [host]:[port], such as one in Termux behind a TCP bridge.
         * [termux]: what Termux is asked to run when nothing listens there yet.
         */
        data class Socket(val host: String, val port: Int, val termux: TermuxCommand?) : Connection()

        /**
         * A server the app starts itself: [command] is the binary (an absolute path, or
         * one in the app's home, see InstalledServers) and its arguments; [environment]
         * is added to the app's.
         */
        data class Process(val command: List<String>, val environment: Map<String, String>) : Connection()

        /**
         * The server [command] (its program and arguments), wherever it is installed: the
         * app's own if its usr/bin has the program (started as a process, as [Process] is),
         * else Termux's, behind a bridge on [host]:[port] that Termux is asked to start
         * (`socat TCP-LISTEN:<port> EXEC:<command>`, see [termuxBridge]).
         */
        data class Auto(
            val command: List<String>,
            val environment: Map<String, String>,
            val host: String,
            val port: Int,
        ) : Connection() {
            /** What Termux runs for it: a bridge from [port] to the server. */
            fun termuxBridge(): TermuxCommand = TermuxCommand(
                "socat",
                listOf("TCP-LISTEN:$port,bind=127.0.0.1,reuseaddr,fork", "EXEC:" + command.joinToString(" "))
            )
        }
    }

    /** The LSP language id of [file]. */
    fun languageIdFor(file: File): String {
        val extension = file.extension.lowercase()
        return languageIds[extension] ?: DEFAULT_LANGUAGE_IDS[extension] ?: extension
    }

    fun handles(fileName: String): Boolean = enabled && fileName.substringAfterLast('.', "").lowercase() in extensions

    companion object {
        val DEFAULT_ROOT_MARKERS = listOf(
            ".git", "compile_commands.json", "compile_flags.txt", ".clangd", "CMakeLists.txt",
            "package.json", "tsconfig.json", "pyproject.toml", "setup.py", "Cargo.toml", "go.mod",
            "build.gradle", "build.gradle.kts", "pom.xml", ".luarc.json"
        )

        // the language ids of the protocol for common extensions
        val DEFAULT_LANGUAGE_IDS = mapOf(
            "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp",
            "hxx" to "cpp", "m" to "objective-c", "mm" to "objective-cpp", "py" to "python", "rs" to "rust",
            "go" to "go", "js" to "javascript", "mjs" to "javascript", "jsx" to "javascriptreact",
            "ts" to "typescript", "tsx" to "typescriptreact", "java" to "java", "kt" to "kotlin", "kts" to "kotlin",
            "lua" to "lua", "sh" to "shellscript", "bash" to "shellscript", "json" to "json", "html" to "html",
            "css" to "css", "md" to "markdown", "yaml" to "yaml", "yml" to "yaml", "toml" to "toml",
            "zig" to "zig", "dart" to "dart", "swift" to "swift", "rb" to "ruby", "php" to "php", "cs" to "csharp"
        )

        /**
         * The servers in the JSON of `servers.json`. A server with something wrong in it is
         * left out, and what is wrong goes to [problems]; the others are kept.
         */
        fun parseAll(root: JsonElement, problems: MutableList<String> = ArrayList()): List<LanguageServerSpec> {
            val servers = (root as? JsonObject)?.get("servers") as? JsonArray ?: run {
                problems += "no \"servers\" list"
                return emptyList()
            }
            return servers.mapIndexedNotNull { index, element ->
                try {
                    parse(element as? JsonObject ?: throw IllegalArgumentException("not an object"))
                } catch (e: IllegalArgumentException) {
                    problems += "server #${index + 1}: ${e.message}"
                    null
                }
            }
        }

        /** One server; throws IllegalArgumentException with what is wrong. */
        fun parse(json: JsonObject): LanguageServerSpec {
            val id = json.string("id") ?: throw IllegalArgumentException("no \"id\"")
            val extensions = json.strings("extensions").map { it.removePrefix(".").lowercase() }.toSet()
            if (extensions.isEmpty()) throw IllegalArgumentException("$id: no \"extensions\"")
            val connection = json["connection"] as? JsonObject ?: throw IllegalArgumentException("$id: no \"connection\"")
            return LanguageServerSpec(
                id = id,
                extensions = extensions,
                connection = ClangdDefaults.apply(parseConnection(id, connection)),
                languageIds = (json["languageIds"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k.lowercase() to it }
                }?.toMap().orEmpty(),
                root = json.string("root"),
                rootMarkers = json.strings("rootMarkers").ifEmpty { DEFAULT_ROOT_MARKERS },
                initializationOptions = json["initializationOptions"]?.let(::plain),
                initializeTimeoutMillis = json.string("initializeTimeout")?.toDoubleOrNull()
                    ?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
                enabled = (json["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }

        private fun parseConnection(id: String, json: JsonObject): Connection = when (json.string("type")) {
            "socket" -> {
                val port = json.string("port")?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("$id: no valid \"port\"")
                Connection.Socket(
                    host = json.string("host") ?: "127.0.0.1",
                    port = port,
                    termux = (json["termux"] as? JsonObject)?.let { termux ->
                        TermuxCommand(
                            command = termux.string("command") ?: throw IllegalArgumentException("$id: termux without \"command\""),
                            arguments = termux.strings("arguments"),
                            workingDirectory = termux.string("workdir"),
                        )
                    }
                )
            }
            "process" -> {
                val command = json.strings("command")
                if (command.isEmpty()) throw IllegalArgumentException("$id: no \"command\"")
                Connection.Process(
                    command = command,
                    environment = (json["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap().orEmpty()
                )
            }
            "auto" -> {
                val command = json.strings("command")
                if (command.isEmpty()) throw IllegalArgumentException("$id: no \"command\"")
                Connection.Auto(
                    command = command,
                    environment = (json["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap().orEmpty(),
                    host = json.string("host") ?: "127.0.0.1",
                    // a port of its own for each server, if none is given
                    port = json.string("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort(id),
                )
            }
            else -> throw IllegalArgumentException("$id: \"type\" is none of \"auto\", \"socket\", \"process\"")
        }

        /** The port of the Termux bridge of a server of type auto that gives none: 5100 to 5899, by its id. */
        fun defaultPort(id: String): Int = 5100 + Math.floorMod(id.hashCode(), 800)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        // JSON as maps, lists and primitives, which the protocol library writes as they are
        internal fun plain(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonObject -> element.mapValues { plain(it.value) }
            is JsonArray -> element.map { plain(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
            }
        }
    }
}

/**
 * What Termux is asked to run (its RUN_COMMAND service): [command] (a name in Termux's
 * `usr/bin`, or an absolute path) with [arguments], in [workingDirectory] (Termux's home
 * by default).
 */
data class TermuxCommand(
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
)

/**
 * What clangd is started with, wherever the configuration starts it: without inserting
 * `#include`s by itself when a completion is taken (`--header-insertion=never`; it also
 * takes the "•" off the labels of such items). A configuration that says otherwise
 * (`--header-insertion=iwyu`) is left as it is.
 *
 *  - a process: `["clangd", ...]` gets the flag;
 *  - Termux runs clangd itself (command `clangd`): its arguments get it;
 *  - Termux runs a bridge (`socat ... "EXEC:clangd --background-index"`): the command
 *    in the argument gets it.
 *
 * A bridge that runs already keeps the clangd command it was started with: `pkill socat`
 * in Termux, and the next connection starts it anew.
 */
internal object ClangdDefaults {

    const val HEADER_INSERTION = "--header-insertion=never"

    // "clangd" as a command in a command line: at its start or after a space, ":" (EXEC:)
    // or a path, and followed by a space or the end
    private val CLANGD = Regex("""(^|[\s:/])clangd(?=\s|$)""")

    fun apply(connection: LanguageServerSpec.Connection): LanguageServerSpec.Connection = when (connection) {
        is LanguageServerSpec.Connection.Process ->
            if (isClangd(connection.command.firstOrNull()) && connection.command.none(::setsHeaderInsertion)) {
                connection.copy(command = listOf(connection.command.first(), HEADER_INSERTION) + connection.command.drop(1))
            } else {
                connection
            }
        is LanguageServerSpec.Connection.Socket -> {
            val termux = connection.termux
            if (termux == null) connection else connection.copy(termux = apply(termux))
        }
        is LanguageServerSpec.Connection.Auto ->
            if (isClangd(connection.command.firstOrNull()) && connection.command.none(::setsHeaderInsertion)) {
                connection.copy(command = listOf(connection.command.first(), HEADER_INSERTION) + connection.command.drop(1))
            } else {
                connection
            }
    }

    private fun apply(command: TermuxCommand): TermuxCommand = when {
        command.arguments.any(::setsHeaderInsertion) -> command
        isClangd(command.command) -> command.copy(arguments = listOf(HEADER_INSERTION) + command.arguments)
        else -> command.copy(arguments = command.arguments.map { argument ->
            // the first clangd of a command line in an argument (socat's EXEC:...)
            if (argument.contains("clangd")) {                
                CLANGD.replaceFirst(argument, "\$0 " + Regex.escapeReplacement(HEADER_INSERTION))
            } else {
                argument
            }
        })
    }

    private fun isClangd(command: String?): Boolean =
        command != null && command.substringAfterLast('/').let { it == "clangd" || it.startsWith("clangd-") }

    private fun setsHeaderInsertion(argument: String) = argument.contains("--header-insertion=") ||
        argument.contains("--header-insertion ")
}
