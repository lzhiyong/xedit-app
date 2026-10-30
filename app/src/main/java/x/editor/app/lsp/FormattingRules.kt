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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import x.github.module.lsp.LspFormattingOptions
import java.io.File

/**
 * The rules a document is formatted with by its language server (the options of
 * textDocument/formatting), for each language: generated, and changed by the user where
 * they want.
 *
 *  - **generated** ([defaults]): from the editor's indentation (its indent unit and the
 *    size of a tab) and what the language is used to (tabs for Go and Makefiles), with
 *    the whitespace rules of the protocol on (trailing white space taken off, one
 *    newline at the end).
 *  - **the user's**: `formatting.json`, next to `servers.json` (LanguageServers.configDirectory).
 *    Opened from the menu ([open]), it gets a section for each language of the servers
 *    that are on and for the language of the document shown, filled with the generated
 *    rules: those are changed there. A rule left out of a section, or a language
 *    without one, is generated; a section taken out is generated again the next time
 *    the file is opened.
 *
 * ```json
 * {
 *   "cpp": {
 *     "tabSize": 4,
 *     "insertSpaces": true,
 *     "trimTrailingWhitespace": true,
 *     "insertFinalNewline": true,
 *     "trimFinalNewlines": true
 *   },
 *   "lua": { "tabSize": 2, "quote_style": "single" }
 * }
 * ```
 *
 * Other keys (`quote_style`) go to the server as they are: the protocol lets the options
 * carry strings, numbers and booleans of a server's own. `null` for one of the whitespace
 * rules: not sent, the server decides.
 *
 * A project's own files go over these (format.ProjectStyle): `.editorconfig` for the
 * indentation and the whitespace rules; a server with a style file of its own (clangd's
 * `.clang-format`, rustfmt's `rustfmt.toml`) goes by that rather than by any of these.
 */
object FormattingRules {

    private const val TAG = "FormattingRules"
    const val FILE = "formatting.json"

    const val TAB_SIZE = "tabSize"
    const val INSERT_SPACES = "insertSpaces"
    const val TRIM_TRAILING_WHITESPACE = "trimTrailingWhitespace"
    const val INSERT_FINAL_NEWLINE = "insertFinalNewline"
    const val TRIM_FINAL_NEWLINES = "trimFinalNewlines"

    private const val MAX_TAB_SIZE = 16

    // the languages that indent with tabs whatever the editor does
    private val TAB_LANGUAGES = setOf("go", "makefile", "make")

    // read leniently (comments, trailing commas: what people copy), written plainly
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // the file as read last, and when it was changed then
    @Volatile
    private var cached: Pair<Long, Map<String, JsonObject>>? = null

    /** The file of the user's rules (it may not be there yet). */
    fun file(context: Context): File = File(LanguageServers.configDirectory(context), FILE)

    /** Whether [file] is the file of the user's rules. Not on the main thread. */
    fun isRulesFile(context: Context, file: File): Boolean =
        runCatching { file.canonicalFile == file(context).canonicalFile }.getOrDefault(false)

    /**
     * The generated rules for [languageId], in an editor that indents with [indentUnit]
     * (`"\t"`, or spaces) and shows a tab [tabSize] columns wide.
     */
    fun defaults(languageId: String, indentUnit: String, tabSize: Int): LspFormattingOptions {
        val tabs = indentUnit == "\t" || indentUnit.isEmpty()
        val size = if (tabs) tabSize else indentUnit.length
        return LspFormattingOptions(
            tabSize = size.coerceIn(1, MAX_TAB_SIZE),
            insertSpaces = !tabs && languageId.lowercase() !in TAB_LANGUAGES,
            trimTrailingWhitespace = true,
            insertFinalNewline = true,
            trimFinalNewlines = true,
        )
    }

    /**
     * The rules a document of [languageId] is formatted with: the [generated] ones, with
     * those of the user's section for it over them. Not on the main thread.
     */
    fun rulesFor(context: Context, languageId: String, generated: LspFormattingOptions): LspFormattingOptions {
        val section = sections(context)[languageId] ?: return generated
        return merge(generated, section)
    }

    /**
     * The file of the user's rules, with a section for each language of [languageIds]
     * that has none (the generated rules of [generate] for it), written if anything was
     * added. A file that cannot be read is left as it is (to be put right in the editor).
     * Not on the main thread.
     */
    fun open(context: Context, languageIds: Collection<String>, generate: (String) -> LspFormattingOptions): File {
        val file = file(context)
        val existing: Map<String, JsonElement> = if (file.exists()) {
            try {
                json.parseToJsonElement(file.readText()) as? JsonObject ?: return file
            } catch (e: Exception) {
                Log.w(TAG, "cannot read $file", e)
                return file
            }
        } else {
            emptyMap()
        }
        val missing = languageIds.filter { it.isNotBlank() && it !in existing }.distinct()
        if (missing.isEmpty() && file.exists()) return file
        val updated = LinkedHashMap(existing)
        for (id in missing) updated[id] = toJson(generate(id))
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonElement.serializer(), JsonObject(updated)) + "\n")
        return file
    }

    /**
     * The language ids to have sections for: those of the servers that are on (each of
     * their extensions), and that of [fileName] (the document shown), first. Not on the
     * main thread.
     */
    fun languageIds(context: Context, fileName: String?): List<String> {
        val servers = LanguageServers.all(context).filter { it.enabled }
        val ids = LinkedHashSet<String>()
        if (fileName != null) {
            val file = File(fileName)
            val spec = servers.firstOrNull { it.handles(fileName) }
            val id = spec?.languageIdFor(file) ?: languageIdOf(fileName)
            if (id.isNotBlank()) ids += id
        }
        for (spec in servers) {
            for (extension in spec.extensions.sorted()) ids += spec.languageIdFor(File("x.$extension"))
        }
        return ids.filter { it.isNotBlank() }
    }

    /** The language id of a file named [fileName] when no server says otherwise. */
    fun languageIdOf(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        if (name.equals("makefile", ignoreCase = true) || name.equals("gnumakefile", ignoreCase = true)) return "makefile"
        val extension = name.substringAfterLast('.', "").lowercase()
        return LanguageServerSpec.DEFAULT_LANGUAGE_IDS[extension] ?: extension
    }

    /** What reading the file again (it was saved) found. */
    sealed class Reload {
        /** It was read: [languages] sections. */
        data class Read(val languages: Int) : Reload()
        /** It could not be read: [message] says why. */
        data class Failed(val message: String) : Reload()
    }

    /** Reads the file again, at once. Not on the main thread. */
    fun reload(context: Context): Reload {
        cached = null
        val file = file(context)
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject
                ?: return Reload.Failed("not a JSON object")
            val problems = root.entries.mapNotNull { (id, value) -> if (value is JsonObject) null else "\"$id\" is not an object" }
            if (problems.isNotEmpty()) Reload.Failed(problems.joinToString("; ")) else Reload.Read(sections(context).size)
        } catch (e: Exception) {
            // the parser's first line (the others repeat the input)
            Reload.Failed(e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
    }

    // the sections of the file, by language id; read again when it changed
    private fun sections(context: Context): Map<String, JsonObject> {
        val file = file(context)
        if (!file.exists()) return emptyMap()
        val modified = file.lastModified()
        cached?.let { (time, sections) -> if (time == modified) return sections }
        val sections = try {
            parse(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $file", e)
            emptyMap()
        }
        cached = modified to sections
        return sections
    }

    /** The sections of [text] (the file), by language id: those that are objects. */
    internal fun parse(text: String): Map<String, JsonObject> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
        return root.entries.mapNotNull { (id, value) -> (value as? JsonObject)?.let { id to it } }.toMap()
    }

    /**
     * The [generated] rules with those of [section] over them: the standard ones where
     * they have a value of the right kind (a positive tab size; `null` for a whitespace
     * rule: not sent), the others as properties for the server.
     */
    internal fun merge(generated: LspFormattingOptions, section: JsonObject): LspFormattingOptions {
        fun bool(key: String): Boolean? = (section[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        // a whitespace rule: true or false, null to leave it to the server, else the generated one
        fun rule(key: String, current: Boolean?): Boolean? = when {
            key !in section -> current
            section[key] is JsonNull -> null
            else -> bool(key) ?: current
        }
        val tabSize = (section[TAB_SIZE] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it in 1..MAX_TAB_SIZE }
        val properties = LinkedHashMap(generated.properties)
        for ((key, value) in section) {
            if (key in STANDARD) continue
            plain(value)?.let { properties[key] = it }
        }
        return generated.copy(
            tabSize = tabSize ?: generated.tabSize,
            insertSpaces = bool(INSERT_SPACES) ?: generated.insertSpaces,
            trimTrailingWhitespace = rule(TRIM_TRAILING_WHITESPACE, generated.trimTrailingWhitespace),
            insertFinalNewline = rule(INSERT_FINAL_NEWLINE, generated.insertFinalNewline),
            trimFinalNewlines = rule(TRIM_FINAL_NEWLINES, generated.trimFinalNewlines),
            properties = properties,
        )
    }

    /** [options] as a section of the file. */
    internal fun toJson(options: LspFormattingOptions): JsonObject {
        val map = LinkedHashMap<String, JsonElement>()
        map[TAB_SIZE] = JsonPrimitive(options.tabSize)
        map[INSERT_SPACES] = JsonPrimitive(options.insertSpaces)
        map[TRIM_TRAILING_WHITESPACE] = options.trimTrailingWhitespace?.let(::JsonPrimitive) ?: JsonNull
        map[INSERT_FINAL_NEWLINE] = options.insertFinalNewline?.let(::JsonPrimitive) ?: JsonNull
        map[TRIM_FINAL_NEWLINES] = options.trimFinalNewlines?.let(::JsonPrimitive) ?: JsonNull
        for ((key, value) in options.properties) {
            map[key] = when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
        return JsonObject(map)
    }

    private val STANDARD = setOf(TAB_SIZE, INSERT_SPACES, TRIM_TRAILING_WHITESPACE, INSERT_FINAL_NEWLINE, TRIM_FINAL_NEWLINES)

    // a value for the server: a string, a whole number, another number or a boolean
    private fun plain(element: JsonElement): Any? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.booleanOrNull ?: primitive.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            ?: primitive.doubleOrNull
    }
}
