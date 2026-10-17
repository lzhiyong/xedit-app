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
         * A server the app starts itself: [command] is the binary (a name in the `bin`
         * directory of the configuration, which the user put there, or an absolute path)
         * and its arguments; [environment] is added to the app's.
         */
        data class Process(val command: List<String>, val environment: Map<String, String>) : Connection()
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
                connection = parseConnection(id, connection),
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
            else -> throw IllegalArgumentException("$id: \"type\" is neither \"socket\" nor \"process\"")
        }

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
