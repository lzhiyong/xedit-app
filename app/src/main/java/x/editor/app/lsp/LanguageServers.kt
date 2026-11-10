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

import android.content.Context
import android.util.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import x.editor.app.AppDirs
import x.editor.app.config.TemplateText
import x.editor.app.config.Templates
import x.editor.app.toolchain.Toolchain
import x.editor.app.toolchain.TermuxProbe
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The language servers the app knows of. It ships none: they are the user's.
 *
 *  - One file a server in `servers/` of the configuration directory ([configDirectory]:
 *    `lsp/` in the app's external files directory, `/sdcard/Android/data/<package>/files`,
 *    see AppDirs): `servers/clangd.json`, `servers/pyright.json`. The name of the file is
 *    the id of the server; the file holds the server as an object (LanguageServerSpec),
 *    without an id. Read leniently (comments, trailing commas). They are edited in the
 *    language servers sheet (LanguageServersSheet, which checks them before it writes them:
 *    [check], [write]), or in the editor like any file.
 *  - Or registered in code with [register]: the interface for whatever wants to provide
 *    servers of its own. Those come before the ones of the files.
 *
 * The first time, the folder is made with the examples of the assets
 * (assets/templates/lsp/presets/<id>.jsonc, with their comments), all off. A server is added with a
 * file of its own (New on the panel: a draft from [newFileText], written when it is saved):
 * its preset, or the template of the assets (assets/templates/lsp/server.jsonc).
 *
 * A server does not depend on the toolchain's list (`packages.json`): every right file is
 * listed and can be started. Where its program is found is said by its connection:
 *
 *  - `auto`: the command in the app's usr/bin (the toolchain) if it is there, else in Termux,
 *    bridged to a port (socat, which the user installs in Termux);
 *  - `process`: run by the app itself, locally (an absolute path, usr/bin, its home);
 *  - `socket`: a server listening at a port; with `"termux"`, started by Termux (socat).
 */
object LanguageServers {

    private const val TAG = "LanguageServers"

    /** The folder of the servers' files, in the configuration directory. */
    const val DIR = "servers"

    private const val EXTENSION = "json"

    // read leniently (comments, trailing commas)
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    private val registered = CopyOnWriteArrayList<LanguageServerSpec>()

    // each file as read last, by its path: when it was changed then, and what it holds
    private val cache = ConcurrentHashMap<String, Pair<Long, ServerFile>>()

    /** Adds [spec], in place of one with its id. */
    fun register(spec: LanguageServerSpec) {
        registered.removeAll { it.id == spec.id }
        registered += spec
    }

    /** Removes the server registered with [id]. */
    fun unregister(id: String) {
        registered.removeAll { it.id == id }
    }

    // #region files

    /**
     * Where the user configures servers (`servers/`), formatting rules, toolchains, and the
     * log is (`lsp.log`): `lsp/` in the app's external files directory,
     * `/sdcard/Android/data/<package>/files/lsp` (see AppDirs).
     */
    fun configDirectory(context: Context): File = File(AppDirs.external(context), "lsp")

    /** The folder of the servers' files, made (see the class) if it is not there. Not on the main thread. */
    fun directory(context: Context): File {
        val dir = File(configDirectory(context), DIR)
        if (!dir.isDirectory) setUp(context, dir)
        return dir
    }

    /** The name of the file of the server [id]: the id, made safe for a file name. */
    fun fileNameOf(id: String): String =
        id.trim().replace(UNSAFE, "_").replace(LEADING_DOT, "_").ifEmpty { "_" } + ".$EXTENSION"

    /** The file of the server [id] (it may not be there). Not on the main thread. */
    fun fileOf(context: Context, id: String): File = File(directory(context), fileNameOf(id))

    /** The id of the server of a file: its name without `.json`. */
    fun idOfFile(file: File): String = file.nameWithoutExtension

    /** Whether [file] is the file of a server. Not on the main thread. */
    fun isServerFile(context: Context, file: File): Boolean = runCatching {
        file.extension.equals(EXTENSION, ignoreCase = true) &&
            file.canonicalFile.parentFile == File(configDirectory(context), DIR).canonicalFile
    }.getOrDefault(false)

    /** The files of the servers, by id. Not on the main thread. */
    private fun files(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.extension.equals(EXTENSION, ignoreCase = true) && !f.name.startsWith(".") }
            ?.sortedBy { idOfFile(it).lowercase() }
            .orEmpty()

    /** What the file of the server [id] says, as it is written; null if there is none. Not on the main thread. */
    fun read(context: Context, id: String): String? {
        val file = fileOf(context, id)
        return if (file.isFile) file.readText() else null
    }

    /**
     * Writes [text] as the file of the server [id], if it is right ([check]); as the user
     * wrote it (comments are kept), ending with a newline, whole or not at all. Not on the
     * main thread.
     *
     * @return what [check] found: nothing was written unless it is [Check.Valid]
     */
    fun write(context: Context, id: String, text: String): Check {
        val check = check(id, text)
        if (check !is Check.Valid) return check
        val file = fileOf(context, id)
        FormattingRules.writeAtomically(file, if (text.endsWith("\n")) text else text + "\n")
        cache.remove(file.path)
        return check
    }

    /** Removes the file of the server [id]; whether it is gone (one gone already is). Not on the main thread. */
    fun delete(context: Context, id: String): Boolean {
        val file = fileOf(context, id)
        val gone = file.delete() || !file.exists()
        if (gone) cache.remove(file.path)
        return gone
    }

    // #endregion

    // #region what a server runs

    /**
     * The programs [spec] runs, by name: the command of a process or of auto; of a socket, what
     * Termux runs for it (through a socat bridge: what the bridge runs). None: a socket nothing
     * starts.
     */
    internal fun programsOf(spec: LanguageServerSpec): List<String> {
        val programs = when (val c = spec.connection) {
            is LanguageServerSpec.Connection.Process -> c.command.take(1)
            is LanguageServerSpec.Connection.Auto -> c.command.take(1)
            is LanguageServerSpec.Connection.Socket -> c.termux?.let { programsOfTermux(it) }.orEmpty()
        }
        return programs.map { it.substringAfterLast('/') }.filter { it.isNotEmpty() }
    }

    private fun programsOfTermux(command: TermuxCommand): List<String> {
        if (command.command.substringAfterLast('/') != "socat") return listOf(command.command)
        // socat ... "EXEC:<program> <arguments>,<its options>"
        return command.arguments.filter { it.startsWith("EXEC:") }.map { argument ->
            argument.removePrefix("EXEC:").trim().trim('\'', '"').split(' ', ',').first()
        }
    }

    /**
     * Whether the program of [spec], started by the app itself, is gone from where it was found
     * (its toolchain uninstalled): the process may still run, from files no longer there. Not on
     * the main thread.
     */
    fun isOwnProgramGone(context: Context, spec: LanguageServerSpec): Boolean {
        val name = when (val c = spec.connection) {
            is LanguageServerSpec.Connection.Process -> c.command.firstOrNull()
            is LanguageServerSpec.Connection.Auto -> c.command.firstOrNull()
            is LanguageServerSpec.Connection.Socket -> null
        } ?: return false
        return if (name.startsWith("/")) !File(name).isFile
        else Toolchain.locate(context, name) == null && !File(AppDirs.home(context), name).isFile
    }

    // #endregion

    // #region checking

    /** What a server's file holds: right ([Valid]), or not ([Invalid]). */
    sealed class Check {
        class Valid(val spec: LanguageServerSpec) : Check()

        /**
         * What is wrong: not JSON ([syntax]: [message] is the parser's, at [line], 1-based,
         * 0 if not known), not an object ([notObject]), or not a server ([message] says why).
         */
        class Invalid(
            val message: String,
            val line: Int = 0,
            val syntax: Boolean = false,
            val notObject: Boolean = false,
        ) : Check()
    }

    /** Checks [text] as the file of the server [id]: JSON, an object, and a server. */
    fun check(id: String, text: String): Check {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Check.Invalid(FriendlySnippets.errorMessage(e), FriendlySnippets.errorLine(text, e.message), syntax = true)
        }
        val server = root as? JsonObject ?: return Check.Invalid("", notObject = true)
        return try {
            Check.Valid(LanguageServerSpec.parse(server, id))
        } catch (e: IllegalArgumentException) {
            Check.Invalid(e.message ?: e.toString())
        }
    }

    // #endregion

    // #region the servers

    /** A server's file as read: its server, or what is wrong with it. */
    class ServerFile(val id: String, val file: File, val check: Check) {
        val spec: LanguageServerSpec? get() = (check as? Check.Valid)?.spec
    }

    /**
     * What the configuration holds, as LanguageServersSheet shows it: the servers registered in
     * code and the servers' files, each read and checked.
     */
    class Inspection(
        val directory: File,
        val registered: List<LanguageServerSpec>,
        val files: List<ServerFile>,
    )

    /** The configuration read now, for the sheet. Not on the main thread. */
    fun inspect(context: Context): Inspection =
        Inspection(directory(context), registered.toList(), files(context).map(::readFile))

    /**
     * The servers that can be started: those registered first, then those of the files; a
     * file is read again when it changed. Not on the main thread.
     */
    fun all(context: Context): List<LanguageServerSpec> =
        registered.toList() + files(context).mapNotNull { readFile(it).spec }

    /**
     * The address a server is reached at through a port ("host:port"): a socket's, or the
     * Termux bridge of one of type auto (not used when the app's toolchain has its
     * command, but then the bridge is not needed: still worth keeping apart). Null: none
     * (a process).
     */
    fun addressOf(spec: LanguageServerSpec): String? = when (val c = spec.connection) {
        is LanguageServerSpec.Connection.Socket -> "${loopback(c.host)}:${c.port}"
        is LanguageServerSpec.Connection.Auto -> "${loopback(c.host)}:${c.port}"
        is LanguageServerSpec.Connection.Process -> null
    }

    // the names of this device, as one
    private fun loopback(host: String): String =
        if (host == "localhost" || host == "::1" || host.startsWith("127.")) "127.0.0.1" else host.lowercase()

    /**
     * The servers on (of [specs]) that are reached at the same address as another one on:
     * each server's id, to the ids of the others. A client of one of them would connect to
     * whatever listens there, maybe the other server (pyright's files sent to clangd).
     */
    fun sharedAddresses(specs: List<LanguageServerSpec>): Map<String, List<String>> {
        val byAddress = specs.filter { it.enabled }
            .mapNotNull { spec -> addressOf(spec)?.let { it to spec.id } }
            .groupBy({ it.first }, { it.second })
        val shared = HashMap<String, List<String>>()
        for (ids in byAddress.values) {
            val distinct = ids.distinct()
            if (distinct.size < 2) continue
            for (id in distinct) shared[id] = distinct - id
        }
        return shared
    }

    /**
     * The extensions of the servers on (of [specs], in the order [all] has them) that an
     * earlier server on has too: a file goes to the first server of its extension
     * ([specFor]), so these never get one. Each server's id, to its extensions taken, each
     * to the id of the server that has it.
     */
    fun shadowedExtensions(specs: List<LanguageServerSpec>): Map<String, Map<String, String>> {
        val owner = HashMap<String, String>()
        val shadowed = LinkedHashMap<String, MutableMap<String, String>>()
        for (spec in specs) {
            if (!spec.enabled) continue
            for (extension in spec.extensions.sorted()) {
                val first = owner.putIfAbsent(extension, spec.id) ?: continue
                if (first != spec.id) shadowed.getOrPut(spec.id) { LinkedHashMap() }[extension] = first
            }
        }
        return shadowed
    }

    /**
     * The server a document of [languageId] goes to, of [specs] (in the order [all] has
     * them): the first on with an extension whose language id is it (its "languageIds",
     * else the usual ones, else the extension itself). Null: none. The formatting sheet
     * says by it which server formats a language.
     */
    fun specForLanguage(specs: List<LanguageServerSpec>, languageId: String): LanguageServerSpec? {
        val id = languageId.lowercase()
        return specs.firstOrNull { spec ->
            spec.enabled && spec.extensions.any { extension ->
                (spec.languageIds[extension] ?: LanguageServerSpec.DEFAULT_LANGUAGE_IDS[extension] ?: extension).lowercase() == id
            }
        }
    }

    /** The server for a file named [fileName], or null. Not on the main thread. */
    fun specFor(context: Context, fileName: String): LanguageServerSpec? = all(context).firstOrNull { it.handles(fileName) }

    /**
     * What the configuration holds, for the log: the folder read, and each server with
     * whether it is on and its extensions. Not on the main thread.
     */
    fun describe(context: Context): String {
        val dir = directory(context)
        val servers = registered.map { it to "registered" } + files(context).mapNotNull { readFile(it).spec }.map { it to "in its file" }
        val list = if (servers.isEmpty()) "no servers" else servers.joinToString("; ") { (spec, where) ->
            "${spec.id} [${if (spec.enabled) "on" else "off"}, $where] .${spec.extensions.joinToString(" .")}"
        }
        return "read ${dir.path}: $list"
    }

    /**
     * Whether the program of a server is there: [INSTALLED], [MISSING] (it cannot be started
     * until it is installed: [command] is what is not there), or [UNKNOWN] (reached at a
     * port: what listens there, or what Termux runs, is not looked for).
     */
    sealed class Installation {
        object Installed : Installation()
        data class Missing(val command: String) : Installation()
        object Unknown : Installation()
    }

    /**
     * Whether the program of [spec] is there (LanguageServersSheet: "Not installed" on its
     * row). Its command of process is looked for as InstalledServers runs it (an absolute
     * path, the app's usr/bin, its home); of auto, in the app's usr/bin, else in Termux
     * ([ofAuto]). A socket is not looked at. Not on the main thread.
     */
    fun installationOf(context: Context, spec: LanguageServerSpec): Installation =
        when (val c = spec.connection) {
            is LanguageServerSpec.Connection.Process -> {
                val name = c.command.firstOrNull().orEmpty()
                val there = when {
                    name.isEmpty() -> false
                    name.startsWith("/") -> File(name).isFile
                    else -> Toolchain.locate(context, name) != null || File(AppDirs.home(context), name).isFile
                }
                if (there) Installation.Installed else Installation.Missing(name)
            }
            is LanguageServerSpec.Connection.Auto -> {
                val name = c.command.firstOrNull().orEmpty()
                ofAuto(
                    name,
                    inUsr = name.isNotEmpty() && Toolchain.locate(context, name) != null,
                    termuxInstalled = Termux.isInstalled(context),
                    termuxFound = TermuxProbe.last?.first,
                    termuxAsked = TermuxProbe.asked,
                )
            }
            is LanguageServerSpec.Connection.Socket -> Installation.Unknown
        }

    /**
     * Whether the program [name] of a server of type auto is there: in the app's usr/bin
     * ([inUsr]), or as Termux said when it was asked last (TermuxProbe). Termux asked about it
     * and without it, or no Termux at all: missing. Termux not asked about it (yet): not known.
     */
    internal fun ofAuto(
        name: String,
        inUsr: Boolean,
        termuxInstalled: Boolean,
        termuxFound: Set<String>?,
        termuxAsked: Set<String>,
    ): Installation = when {
        name.isEmpty() -> Installation.Missing(name)
        inUsr -> Installation.Installed
        !termuxInstalled -> Installation.Missing(name)
        termuxFound != null && name in termuxFound -> Installation.Installed
        termuxFound != null && name in termuxAsked -> Installation.Missing(name)
        else -> Installation.Unknown
    }

    // [file] read and checked, or as it was read last if it has not changed since
    private fun readFile(file: File): ServerFile {
        val modified = file.lastModified()
        cache[file.path]?.let { (time, read) -> if (time == modified) return read }
        val id = idOfFile(file)
        val check = try {
            check(id, file.readText())
        } catch (e: Exception) {
            Check.Invalid(e.message ?: e.toString())
        }
        if (check is Check.Invalid) Log.w(TAG, "$file: ${check.message}")
        return ServerFile(id, file, check).also { cache[file.path] = modified to it }
    }

    // #endregion

    // #region the folder made, the examples, a new server

    // the folder made, with the examples
    @Synchronized
    private fun setUp(context: Context, dir: File) {
        if (dir.isDirectory) return
        try {
            dir.mkdirs()
            for (id in exampleIds(context)) {
                val text = example(context, id) ?: continue
                FormattingRules.writeAtomically(File(dir, fileNameOf(id)), text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot set up $dir", e)
        }
    }

    // the presets, and the template of a new server, in the assets (JSON with comments)
    private const val NAME_KEY = "name"
    private const val EXTENSION_KEY = "extension"

    // the ids of the presets of the assets, read once
    @Volatile
    private var exampleIds: List<String>? = null

    /** The ids of the servers the app ships a preset of (assets/templates/lsp/presets/<id>.jsonc). */
    fun exampleIds(context: Context): List<String> =
        exampleIds ?: Templates.namesIn(context, Templates.LSP_PRESETS).also { exampleIds = it }

    /**
     * The file of the server [id] as it is shipped (assets/templates/lsp/presets/<id>.jsonc,
     * with its comments); null for one that is not. Reads the asset.
     */
    fun example(context: Context, id: String): String? {
        if (id !in exampleIds(context)) return null
        return runCatching {
            Templates.read(context, "${Templates.LSP_PRESETS}/$id${Templates.EXTENSION}")
        }.getOrNull()
    }

    /**
     * The text of a new server [id], not written (a draft, saved by the panel): the preset if
     * the app ships one of that id, else the template of the assets
     * (assets/templates/lsp/server.jsonc) for it ([fillTemplate]): off, running the command
     * [command] ([id] if none is given), for the files of [extension] (the document's) if it
     * is given. Reads the asset.
     */
    fun newFileText(context: Context, id: String, extension: String?, command: String = id): String =
        example(context, id) ?: fillTemplate(Templates.read(context, Templates.LSP_SERVER), command, extension)

    /**
     * [template] with its placeholders, in quotes, put in: `"{{name}}"` the id (as a JSON
     * string), `"{{extension}}"` the extension (as a JSON string; none or blank: left out,
     * so that its list is empty). The comments of the template do not write them in quotes.
     */
    internal fun fillTemplate(template: String, id: String, extension: String?): String {
        val ext = extension?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
        return TemplateText.fill(
            template,
            values = mapOf(
                NAME_KEY to TemplateText.jsonString(id),
                EXTENSION_KEY to ext?.let(TemplateText::jsonString).orEmpty(),
            ),
        )
    }

    // what a file name cannot have; a name starting with a dot would be hidden
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val LEADING_DOT = Regex("^\\.")

    // #endregion
}
