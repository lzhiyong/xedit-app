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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import x.editor.app.R
import x.github.module.lsp.LspFormattingOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import x.editor.app.config.TemplateText
import x.editor.app.config.Templates

/**
 * The rules a document is formatted with by its language server (the options of
 * textDocument/formatting), for each language: generated, and changed by the user where
 * they want.
 *
 *  - **the defaults** ([defaults]): the template of the language in the assets
 *    (`templates/formatting/presets/<languageId>.jsonc` if the app ships one: Go and Makefiles,
 *    with tabs; else `templates/formatting/language.jsonc`), over the editor's indentation (its
 *    indent unit and the size of a tab, [indentation]). Nothing of them is written in
 *    code: the template says which rules are on, its placeholders `"{{tabSize}}"` and
 *    `"{{insertSpaces}}"` stand for the editor's (left as they are, they are not of the
 *    right kind, so [merge] keeps the editor's). The text of a new rules file is the same
 *    template with them filled in ([newFileText]): New and Default of the sheet.
 *  - **the user's**: one file a language, `formatting/<languageId>.json`, next to
 *    `servers/` (LanguageServers.configDirectory). Each holds the rules of its
 *    language at the top level; a rule left out, or a language without a file, goes by
 *    the defaults. No file is made but by the user (New of the sheet). They are edited in the formatting sheet (FormattingSheet, which checks
 *    them before it writes them: [validate], [write]), or in the editor like any file.
 *
 * ```json
 * // formatting/lua.json
 * {
 *   "tabSize": 2,
 *   "insertSpaces": true,
 *   "trimTrailingWhitespace": true,
 *   "insertFinalNewline": true,
 *   "trimFinalNewlines": true,
 *   "quote_style": "single"
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

    /** The folder of the rules files, in the configuration directory. */
    const val DIR = "formatting"

    private const val EXTENSION = "json"

    const val TAB_SIZE = "tabSize"
    const val INSERT_SPACES = "insertSpaces"
    const val TRIM_TRAILING_WHITESPACE = "trimTrailingWhitespace"
    const val INSERT_FINAL_NEWLINE = "insertFinalNewline"
    const val TRIM_FINAL_NEWLINES = "trimFinalNewlines"

    const val MAX_TAB_SIZE = 16

    // the whitespace rules: true, false, or null (left to the server)
    private val WHITESPACE_RULES = setOf(TRIM_TRAILING_WHITESPACE, INSERT_FINAL_NEWLINE, TRIM_FINAL_NEWLINES)

    /** The keys of the protocol; any other goes to the server as it is. */
    val STANDARD = setOf(TAB_SIZE, INSERT_SPACES) + WHITESPACE_RULES

    // the templates of the rules in the assets: the general one, and those of the languages
    // that have one of their own (<languageId>.jsonc); with comments
    /** The placeholders of a template (in quotes: JSON strings until filled in) and of its comments (TemplateText). */
    const val TAB_SIZE_PLACEHOLDER = "\"{{tabSize}}\""
    const val INSERT_SPACES_PLACEHOLDER = "\"{{insertSpaces}}\""
    const val LANGUAGE_PLACEHOLDER = "{{language}}"

    // read leniently (comments, trailing commas: what people copy), written plainly
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // each file as read last, by language id: when it was changed then, and its rules
    // (null: not a JSON object)
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonObject?>>()

    // #region files

    /** The folder of the rules files (it may not be there yet). */
    fun directory(context: Context): File = File(LanguageServers.configDirectory(context), DIR)

    /** The name of the rules file of [languageId]: the id, made safe for a file name. */
    fun fileNameOf(languageId: String): String =
        languageId.trim().replace(UNSAFE, "_").ifEmpty { "_" } + ".$EXTENSION"

    /** The rules file of [languageId] (it may not be there yet). Not on the main thread. */
    fun fileOf(context: Context, languageId: String): File =
        File(directory(context), fileNameOf(languageId))

    /** Whether [file] is one of the rules files. Not on the main thread. */
    fun isRulesFile(context: Context, file: File): Boolean = runCatching {
        file.extension.equals(EXTENSION, ignoreCase = true) &&
            file.canonicalFile.parentFile == directory(context).canonicalFile
    }.getOrDefault(false)

    /** The language id of a rules file: its name without `.json`. */
    fun languageIdOfFile(file: File): String = file.nameWithoutExtension

    /** The language ids that have a rules file, sorted. Not on the main thread. */
    fun languages(context: Context): List<String> =
        directory(context).listFiles { f -> f.isFile && f.extension.equals(EXTENSION, ignoreCase = true) }
            ?.map { languageIdOfFile(it) }
            ?.sorted()
            .orEmpty()

    /** What the rules file of [languageId] says, as it is written; null if there is none. Not on the main thread. */
    fun read(context: Context, languageId: String): String? {
        val file = fileOf(context, languageId)
        return if (file.isFile) file.readText() else null
    }

    /**
     * Writes [text] as the rules of [languageId], if they are right ([validate]); the
     * file as the user wrote it (comments are kept), ending with a newline. Written
     * whole or not at all (to a file beside it, then renamed). Not on the main thread.
     *
     * @return what [validate] found: nothing was written unless it is [Validation.Valid]
     */
    fun write(context: Context, languageId: String, text: String): Validation {
        val validation = validate(text)
        if (validation !is Validation.Valid) return validation
        val file = fileOf(context, languageId)
        writeAtomically(file, if (text.endsWith("\n")) text else text + "\n")
        cache.remove(languageId)
        return validation
    }

    /** [options] as the text of a rules file. */
    fun textOf(options: LspFormattingOptions): String =
        json.encodeToString(JsonElement.serializer(), toJson(options)) + "\n"

    /** [text] written to [file] whole or not at all (to a file beside it, then renamed). */
    internal fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // a rename onto an existing file fails on some file systems
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(text)
            }
        }
    }

    // #endregion

    // #region rules

    /**
     * The indentation of an editor that indents with [indentUnit] (`"\t"`, or spaces) and
     * shows a tab [tabSize] columns wide, as rules: the size of an indent and spaces or tabs;
     * the whitespace rules left to the server (the templates say them).
     */
    fun indentation(indentUnit: String, tabSize: Int): LspFormattingOptions {
        val tabs = indentUnit == "\t" || indentUnit.isEmpty()
        val size = if (tabs) tabSize else indentUnit.length
        return LspFormattingOptions(tabSize = size.coerceIn(1, MAX_TAB_SIZE), insertSpaces = !tabs)
    }

    /**
     * The default rules of [languageId] (a language without a file goes by them, and a file
     * goes over them): its template ([templateOf]) over the editor's [indentation]. Reads the
     * asset the first time.
     */
    fun defaults(context: Context, languageId: String, indentUnit: String, tabSize: Int): LspFormattingOptions =
        defaults(templateSectionOf(context, languageId), indentUnit, tabSize)

    /** The rules of [template] (a template's text, its placeholders in or not) over the editor's [indentation]. */
    fun defaults(template: String, indentUnit: String, tabSize: Int): LspFormattingOptions =
        defaults(parseTemplate(template), indentUnit, tabSize)

    private fun defaults(section: JsonObject?, indentUnit: String, tabSize: Int): LspFormattingOptions {
        val base = indentation(indentUnit, tabSize)
        return if (section == null) base else merge(base, section)
    }

    /**
     * The text of a new rules file of [languageId] (New, Default): its template, with the
     * editor's indentation in place of the placeholders. Reads the asset the first time.
     */
    fun newFileText(context: Context, languageId: String, indentUnit: String, tabSize: Int): String =
        fillTemplate(templateOf(context, languageId), languageId, indentation(indentUnit, tabSize))

    /** [template] with [indentation] in place of its placeholders, and [languageId] in its comments. */
    fun fillTemplate(template: String, languageId: String, indentation: LspFormattingOptions): String {
        val text = TemplateText.fill(
            template,
            values = mapOf("tabSize" to indentation.tabSize.toString(), "insertSpaces" to indentation.insertSpaces.toString()),
            text = mapOf("language" to languageId),
        )
        return if (text.endsWith("\n")) text else text + "\n"
    }

    // the templates as read, by language id ("": the general one); a language without one
    // of its own maps to the general one's text
    private val templates = ConcurrentHashMap<String, String>()
    private val templateSections = ConcurrentHashMap<String, JsonObject>()
    @Volatile
    private var templateLanguages: Set<String>? = null

    /**
     * The template of [languageId] as the app ships it, with its comments: its own
     * (`templates/formatting/presets/<languageId>.jsonc`) if there is one, else the general one.
     * Empty if the assets have none (then the editor's indentation alone is the default).
     */
    fun templateOf(context: Context, languageId: String): String {
        val id = languageId.lowercase()
        templates[id]?.let { return it }
        val languages = templateLanguages
            ?: Templates.namesIn(context, Templates.FORMATTING_PRESETS).toSet().also { templateLanguages = it }
        val path = if (id in languages) "${Templates.FORMATTING_PRESETS}/$id${Templates.EXTENSION}" else Templates.FORMATTING_LANGUAGE
        val text = runCatching { Templates.read(context, path) }
            .onFailure { Log.w(TAG, "cannot read the asset $path", it) }
            .getOrDefault("")
        return text.also { templates[id] = it }
    }

    // the rules of the template of [languageId], read once; null: none, or not an object
    private fun templateSectionOf(context: Context, languageId: String): JsonObject? {
        val id = languageId.lowercase()
        templateSections[id]?.let { return it }
        return parseTemplate(templateOf(context, id))?.also { templateSections[id] = it }
    }

    // a template's rules, its placeholders as they are (strings: merge keeps the editor's)
    private fun parseTemplate(template: String): JsonObject? {
        if (template.isBlank()) return null
        return runCatching { json.parseToJsonElement(template) as? JsonObject }
            .onFailure { Log.w(TAG, "a template of the rules is not JSON", it) }
            .getOrNull()
    }

    /**
     * The rules a document of [languageId] is formatted with: the [generated] ones, with
     * those of the user's file for it over them. Not on the main thread.
     */
    fun rulesFor(context: Context, languageId: String, generated: LspFormattingOptions): LspFormattingOptions {
        val section = sectionOf(context, languageId) ?: return generated
        return merge(generated, section)
    }

    /** Whether [languageId] has a rules file that can be read. Not on the main thread. */
    fun hasSection(context: Context, languageId: String): Boolean = sectionOf(context, languageId) != null

    // the rules of the file of [languageId]; read again when it changed. Read leniently
    // (merge leaves out what is not right): a file saved in the editor with a mistake
    // still applies what it gets right, and the user is told what is wrong (reload).
    private fun sectionOf(context: Context, languageId: String): JsonObject? {
        val file = fileOf(context, languageId)
        if (!file.isFile) {
            cache.remove(languageId)
            return null
        }
        val modified = file.lastModified()
        cache[languageId]?.let { (time, section) -> if (time == modified) return section }
        val section = try {
            json.parseToJsonElement(file.readText()) as? JsonObject
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $file", e)
            null
        }
        cache[languageId] = modified to section
        return section
    }

    /**
     * The [generated] rules with those of [section] over them: the standard ones where
     * they have a value of the right kind (a tab size of 1 to [MAX_TAB_SIZE]; `null` for a
     * whitespace rule: not sent), the others as properties for the server.
     */
    fun merge(generated: LspFormattingOptions, section: JsonObject): LspFormattingOptions {
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

    /** [options] as the rules of a file. */
    fun toJson(options: LspFormattingOptions): JsonObject {
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

    // a value for the server: a string, a whole number, another number or a boolean
    private fun plain(element: JsonElement): Any? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return primitive.content
        return primitive.booleanOrNull ?: primitive.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            ?: primitive.doubleOrNull
    }

    // #endregion

    // #region checking

    /** What is wrong with the text of a rules file: at [line] (1-based; 0: not known). */
    class Problem(val line: Int, val kind: Kind, val key: String = "", val detail: String = "")

    enum class Kind {
        /** Not JSON: [Problem.detail] says why. */
        SYNTAX,
        /** JSON, but not an object. */
        NOT_OBJECT,
        /** A key that is empty. */
        EMPTY_KEY,
        /** tabSize is not a whole number of 1 to [MAX_TAB_SIZE]. */
        TAB_SIZE,
        /** insertSpaces is not true or false. */
        NOT_BOOLEAN,
        /** A whitespace rule is not true, false or null. */
        NOT_BOOLEAN_OR_NULL,
        /** A key of a server is not a string, a number or a boolean. */
        NOT_PLAIN,
        /** An object: the rules of a language are written at the top level, not in a section. */
        NESTED,
        /** A key written twice: only the last would count. */
        DUPLICATE,
    }

    sealed class Validation {
        /** Right: [rules] are what will be sent (over the generated ones). */
        class Valid(val rules: JsonObject) : Validation()

        /** Not right: [problems], in the order of the text. */
        class Invalid(val problems: List<Problem>) : Validation()
    }

    /** Checks [text] as the rules of a language (see the class), all of it. */
    fun validate(text: String): Validation {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Validation.Invalid(listOf(Problem(FriendlySnippets.errorLine(text, e.message), Kind.SYNTAX, detail = FriendlySnippets.errorMessage(e))))
        }
        if (root !is JsonObject) return Validation.Invalid(listOf(Problem(1, Kind.NOT_OBJECT)))
        // where each key of the object is written (not in a comment, not in an inner object)
        val keys = topLevelKeys(text)
        fun lineOf(key: String): Int = keys[JsonPrimitive(key).toString()]?.firstOrNull()?.let { offset ->
            text.substring(0, offset).count { it == '\n' } + 1
        } ?: 1
        val problems = ArrayList<Problem>()
        for ((key, value) in root) {
            val line = lineOf(key)
            val kind = when {
                key.isBlank() -> Kind.EMPTY_KEY
                key == TAB_SIZE -> Kind.TAB_SIZE.takeUnless {
                    // a whole number written as one: not "4", 4.5 or true
                    val size = (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    size != null && size in 1..MAX_TAB_SIZE
                }
                key == INSERT_SPACES -> Kind.NOT_BOOLEAN.takeUnless { isBoolean(value) }
                key in WHITESPACE_RULES -> Kind.NOT_BOOLEAN_OR_NULL.takeUnless { value is JsonNull || isBoolean(value) }
                value is JsonObject -> Kind.NESTED
                value is JsonArray || value is JsonNull -> Kind.NOT_PLAIN
                else -> null
            }
            if (kind != null) problems += Problem(line, kind, key)
            // the parser keeps the last of a key written twice: said, for the first would be lost
            if ((keys[JsonPrimitive(key).toString()]?.size ?: 0) > 1) problems += Problem(line, Kind.DUPLICATE, key)
        }
        return if (problems.isEmpty()) Validation.Valid(root) else Validation.Invalid(problems.sortedBy { it.line })
    }

    /** [problem] in words, for the user. */
    fun describe(context: Context, problem: Problem): String {
        val message = when (problem.kind) {
            Kind.SYNTAX -> context.getString(R.string.format_check_syntax, problem.detail)
            Kind.NOT_OBJECT -> context.getString(R.string.format_check_not_object)
            Kind.EMPTY_KEY -> context.getString(R.string.format_check_empty_key)
            Kind.TAB_SIZE -> context.getString(R.string.format_check_tab_size, MAX_TAB_SIZE)
            Kind.NOT_BOOLEAN -> context.getString(R.string.format_check_boolean, problem.key)
            Kind.NOT_BOOLEAN_OR_NULL -> context.getString(R.string.format_check_boolean_or_null, problem.key)
            Kind.NOT_PLAIN -> context.getString(R.string.format_check_plain, problem.key)
            Kind.NESTED -> context.getString(R.string.format_check_nested, problem.key)
            Kind.DUPLICATE -> context.getString(R.string.format_check_duplicate, problem.key)
        }
        return if (problem.line > 0) context.getString(R.string.config_error_line, problem.line, message) else message
    }

    private fun isBoolean(value: JsonElement): Boolean =
        (value as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull != null

    /**
     * [text] with its comments (`//` to the end of the line, `/* */`) made spaces, outside
     * strings: the same length and the same lines, so that what is found in it is where it
     * is in [text]. A key commented out is not a key.
     */
    internal fun withoutComments(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') out.setCharAt(i++, ' ')
                    continue
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    while (i < end) {
                        if (text[i] != '\n') out.setCharAt(i, ' ')
                        i++
                    }
                    continue
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * The keys of the outermost object of [text] (valid JSON, comments allowed), each as it
     * is written (with its quotes and escapes) and the offsets where it is: in the order of
     * the text, more than one if it is written more than once. Keys in comments or in inner
     * objects are not its keys.
     */
    internal fun topLevelKeys(text: String): Map<String, List<Int>> {
        val code = withoutComments(text)
        val keys = LinkedHashMap<String, MutableList<Int>>()
        var depth = 0
        var i = 0
        while (i < code.length) {
            when (code[i]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                '"' -> {
                    val start = i
                    i++
                    while (i < code.length && code[i] != '"') {
                        if (code[i] == '\\') i++
                        i++
                    }
                    // a string of the outermost object followed by a colon: a key
                    if (depth == 1) {
                        var next = i + 1
                        while (next < code.length && code[next].isWhitespace()) next++
                        if (next < code.length && code[next] == ':') {
                            keys.getOrPut(code.substring(start, minOf(i + 1, code.length))) { ArrayList() } += start
                        }
                    }
                }
            }
            i++
        }
        return keys
    }

    /** What reading a rules file again (it was saved in the editor) found. */
    sealed class Reload {
        /** It is right: the rules of [languageId]. */
        data class Read(val languageId: String) : Reload()
        /** It is not: [message] says why (what [describe] says of the first problem). */
        data class Failed(val languageId: String, val message: String) : Reload()
    }

    /** Reads the rules file [file] again, at once, and checks it. Not on the main thread. */
    fun reload(context: Context, file: File): Reload {
        val languageId = languageIdOfFile(file)
        cache.remove(languageId)
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return Reload.Failed(languageId, e.message ?: e.javaClass.simpleName)
        }
        return when (val validation = validate(text)) {
            is Validation.Valid -> Reload.Read(languageId)
            is Validation.Invalid -> Reload.Failed(languageId, validation.problems.joinToString("; ") { describe(context, it) })
        }
    }

    // #endregion

    /** The language id of a file named [fileName] when no server says otherwise. */
    fun languageIdOf(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        if (name.equals("makefile", ignoreCase = true) || name.equals("gnumakefile", ignoreCase = true)) return "makefile"
        val extension = name.substringAfterLast('.', "").lowercase()
        return LanguageServerSpec.DEFAULT_LANGUAGE_IDS[extension] ?: extension
    }

    // what a file name cannot hold (or should not: a dot at the start hides it)
    private val UNSAFE = Regex("[^A-Za-z0-9_+\\-.]|^\\.")
}
