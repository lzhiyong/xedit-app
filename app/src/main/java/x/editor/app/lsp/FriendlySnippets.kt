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

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import x.editor.app.AppDirs

/**
 * The snippets of [friendly-snippets](https://github.com/rafamadriz/friendly-snippets),
 * as the completion offers them (see LspController): a VS Code snippet collection, in
 * `home/lsp/friendly-snippets` of the app's files (AppDirs.home), where the resources
 * update puts it (the "snippets" package of assets/treesitter.json).
 *
 *  - `package.json` says which files hold the snippets of which language
 *    (`contributes.snippets`: `{"language": "c" or [...], "path": "./snippets/c/c.json"}`);
 *  - each file: `{"name": {"prefix": "for" or [...], "body": "..." or [lines], "description": ...}}`.
 *
 * A file is matched to languages by its name ([languagesOf]); the global snippets
 * (`all`, `global`: dates, a UUID) go with every file. What was read is kept, and read
 * again when the file changed (an update of the package).
 *
 * **The user's own snippets** are not in that package, which an update replaces whole,
 * but in [userDirectory] (`snippets/` of the app's external files, next to `lsp/`),
 * which nothing but the user writes to. Same format, one file per language, named by
 * the ids of [userIdsOf]: `c.json`, `python.json`; `<extension>.json` for files of a
 * kind friendly-snippets has no language for; `all.json` for every file. Comments and
 * trailing commas are allowed (VS Code's snippet files have them). They come before
 * those of the package, and one with the prefix of a snippet of the package takes its
 * place. They are there without the package too.
 */
object FriendlySnippets {

    private const val TAG = "FriendlySnippets"

    /** The directory of the package in the app's home. */
    const val DIRECTORY = "lsp/friendly-snippets"

    /** The directory of the user's own snippets in the app's external files. */
    const val USER_DIRECTORY = "snippets"

    /** For every file. */
    private val GLOBAL = listOf("all", "global")

    /** The asset a new file of the user's snippets is written from (`{{language}}`: its id). */
    private const val USER_TEMPLATE = "snippets_template.jsonc"

    // what the files are read with: comments and trailing commas are let through, VS Code
    // has them in its snippet files and they are what users copy from
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    // where the JSON parser says an error is
    private val OFFSET = Regex("""\s*at (?:offset|position) (\d+)""")

    /**
     * A snippet: typed as [prefix] (one of them, a snippet with several is there once
     * for each), [name], [body] in the snippet syntax (`${1:arg}`, `$0`), [description].
     */
    class Snippet(
        val prefix: String,
        val name: String,
        val body: String,
        val description: String?,
        /** One of the user's own ([userDirectory]), not of the package. */
        val user: Boolean = false,
    )

    /** A file of snippets read again: how many it has, or why it could not be read. */
    sealed class Reload {
        class Read(val count: Int) : Reload()
        /** [line]: where the error is (1-based), 0 if that is not known. */
        class Failed(val line: Int, val message: String) : Reload()
    }

    /**
     * A snippet that matches what was typed: [replaceLength] chars before the cursor are
     * replaced by it; [exact] when they are its whole prefix.
     */
    class Match(val snippet: Snippet, val replaceLength: Int, val exact: Boolean)

    // package.json read: which files hold the snippets of which language
    private class Index(val path: String, val modified: Long, val files: Map<String, List<File>>)

    @Volatile
    private var index: Index? = null

    // a file of snippets, by its path: when it was changed then, and what it holds
    private val files = ConcurrentHashMap<String, Pair<Long, List<Snippet>>>()

    fun directory(context: Context): File = File(AppDirs.home(context), DIRECTORY)

    /** Where the user's own snippets are: `/sdcard/Android/data/<package>/files/snippets`. */
    fun userDirectory(context: Context): File = File(AppDirs.external(context), USER_DIRECTORY)

    /**
     * The snippets for a file named [fileName]: the user's, then those of the package
     * that no snippet of the user's replaces (by prefix); of its languages, and the
     * global ones. Empty if there are none. Not on the main thread (files are read).
     */
    fun forFile(context: Context, fileName: String): List<Snippet> {
        val userDir = userDirectory(context)
        val user = userIdsOf(fileName)
            .map { File(userDir, "$it.json") }
            .flatMap { read(it, user = true) }
        val packaged = index(context)?.let { index ->
            (languagesOf(fileName) + GLOBAL)
                .flatMap { index.files[it].orEmpty() }
                .distinct()
                .flatMap { read(it, user = false) }
        }.orEmpty()
        if (user.isEmpty()) return packaged
        val replaced = user.mapTo(HashSet()) { it.prefix }
        return user + packaged.filter { it.prefix !in replaced }
    }

    /**
     * The ids the files of the user's snippets for a file named [fileName] are named by,
     * the most specific first: its languages as friendly-snippets names them
     * ([languagesOf]); its extension, or its name if it has none (so that a kind of file
     * friendly-snippets knows nothing of can have snippets too: `foo.json` for `x.foo`);
     * then the global ones.
     */
    fun userIdsOf(fileName: String): List<String> {
        val name = fileName.substringAfterLast('/')
        val own = name.substringAfterLast('.', "").ifEmpty { name }.lowercase(Locale.ROOT)
        return (languagesOf(name) + listOf(own).filter { it.isNotEmpty() } + GLOBAL).distinct()
    }

    /**
     * The file of the user's snippets for a file named [fileName] (null: the one for
     * every file, `all.json`), written from a template with an example if it is not
     * there, for the user to edit. Not on the main thread.
     */
    fun userFile(context: Context, fileName: String?): File {
        val id = fileName?.let { userIdsOf(it).first() } ?: GLOBAL.first()
        val file = File(userDirectory(context), "$id.json")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val template = context.assets.open(USER_TEMPLATE).bufferedReader().use { it.readText() }
            file.writeText(template.replace("{{language}}", id))
        }
        return file
    }

    /** Whether [file] is one of the user's snippets, which are read again when saved. */
    fun isUserFile(context: Context, file: File): Boolean =
        file.name.endsWith(".json") &&
            file.canonicalFile.parentFile == userDirectory(context).canonicalFile

    /**
     * Reads [file], one of the user's, again, changed or not: to tell the user who saved
     * it what came of it. Not on the main thread.
     */
    fun reload(file: File): Reload {
        files.remove(file.path)
        if (!file.isFile) return Reload.Read(0)
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return Reload.Failed(0, e.message ?: e.toString())
        }
        return try {
            val snippets = parse(text, user = true)
            files[file.path] = file.lastModified() to snippets
            Reload.Read(snippets.size)
        } catch (e: Exception) {
            Reload.Failed(errorLine(text, e.message), errorMessage(e))
        }
    }

    /** Forgets what was read: the package was updated. */
    fun invalidate() {
        index = null
        files.clear()
    }

    // #region reading

    private fun index(context: Context): Index? {
        val dir = directory(context)
        val manifest = File(dir, "package.json")
        val modified = manifest.lastModified()
        if (modified == 0L) return null
        index?.let { if (it.path == dir.path && it.modified == modified) return it }
        val read = try {
            val root = json.parseToJsonElement(manifest.readText()) as? JsonObject ?: return null
            val contributes = (root["contributes"] as? JsonObject)?.get("snippets") as? JsonArray ?: return null
            val byLanguage = HashMap<String, MutableList<File>>()
            for (element in contributes) {
                val entry = element as? JsonObject ?: continue
                val path = (entry["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                val file = File(dir, path.removePrefix("./"))
                for (language in strings(entry["language"])) {
                    byLanguage.getOrPut(language.lowercase(Locale.ROOT)) { ArrayList() } += file
                }
            }
            Index(dir.path, modified, byLanguage)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read ${manifest.path}", e)
            return null
        }
        index = read
        return read
    }

    // the snippets of [file], read again when it changed; none if it is not there
    private fun read(file: File, user: Boolean): List<Snippet> {
        val modified = file.lastModified()
        if (modified == 0L) return emptyList()
        files[file.path]?.let { (time, snippets) -> if (time == modified) return snippets }
        val snippets = try {
            parse(file.readText(), user)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read ${file.path}", e)
            emptyList()
        }
        files[file.path] = modified to snippets
        return snippets
    }

    // the snippets of a file whose text is [text]
    // @throws IllegalArgumentException (SerializationException is one) if it is not a JSON object
    private fun parse(text: String, user: Boolean): List<Snippet> {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("""not a JSON object: { "name": { "prefix": ..., "body": ... } }""")
        return root.flatMap { (name, value) ->
            val item = value as? JsonObject ?: return@flatMap emptyList()
            val body = strings(item["body"]).joinToString("\n")
            if (body.isEmpty()) return@flatMap emptyList()
            val description = strings(item["description"]).joinToString(" ").ifEmpty { null }
            strings(item["prefix"]).filter { it.isNotBlank() }.map { prefix ->
                Snippet(prefix.trim(), name, body, description, user)
            }
        }
    }

    // the line (1-based) of an error of the JSON parser, which says "at offset N"; 0: not said
    internal fun errorLine(text: String, message: String?): Int {
        val offset = message?.let { OFFSET.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1
    }

    // the first line of the parser's message, without the offset (the line is told instead)
    internal fun errorMessage(e: Exception): String =
        (e.message ?: e.toString()).lineSequence().first().replace(OFFSET, "").trim()

    // a string, or the strings of an array
    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.contentOrNull)
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    // #endregion

    // #region matching

    /**
     * The snippets of [snippets] whose prefix starts with what was typed before the
     * cursor ([lineBeforeCursor]), best first, at most [limit]: the word before the
     * cursor (`for`), or all that is not white space (`#inc`, `#!`) for a prefix that
     * starts with such chars. Those typed whole first, the user's before the package's,
     * then the shorter prefixes.
     */
    fun match(snippets: List<Snippet>, lineBeforeCursor: String, limit: Int): List<Match> {
        if (snippets.isEmpty()) return emptyList()
        val word = CompletionTyping.wordBefore(lineBeforeCursor)
        var tokenStart = lineBeforeCursor.length
        while (tokenStart > 0 && !lineBeforeCursor[tokenStart - 1].isWhitespace()) tokenStart--
        val token = lineBeforeCursor.substring(tokenStart)
        if (word.isEmpty() && token.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val found = ArrayList<Match>()
        for (snippet in snippets) {
            val prefix = snippet.prefix
            val typed = when {
                token.length > word.length && prefix.startsWith(token, ignoreCase = true) -> token
                word.isNotEmpty() && prefix.startsWith(word, ignoreCase = true) -> word
                else -> continue
            }
            // the same snippet in two files (c and cpp), or twice: once
            if (!seen.add(prefix + "\u0000" + snippet.body)) continue
            found += Match(snippet, typed.length, exact = prefix.length == typed.length)
        }
        return found
            .sortedWith(compareBy<Match>({ !it.exact }, { !it.snippet.user }, { it.snippet.prefix.length }, { it.snippet.prefix }))
            .take(limit)
    }

    // #endregion

    // #region languages

    /**
     * The languages of friendly-snippets (VS Code's ids) for a file named [fileName]:
     * by its name, then its extension. Empty for a file of none of them.
     */
    fun languagesOf(fileName: String): List<String> {
        val name = fileName.substringAfterLast('/')
        BY_NAME[name]?.let { return it }
        BY_NAME[name.lowercase(Locale.ROOT)]?.let { return it }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return BY_EXTENSION[extension].orEmpty()
    }

    private val BY_NAME: Map<String, List<String>> = mapOf(
        "Makefile" to listOf("make"), "makefile" to listOf("make"), "GNUmakefile" to listOf("make"),
        "CMakeLists.txt" to listOf("cmake"),
        "Dockerfile" to listOf("dockerfile"), "dockerfile" to listOf("dockerfile"),
        ".editorconfig" to listOf("editorconfig"),
        "COMMIT_EDITMSG" to listOf("gitcommit"),
        "dune" to listOf("dune"), "dune-project" to listOf("dune-project"),
        "Gemfile" to listOf("ruby"), "Rakefile" to listOf("ruby", "rails"),
        ".bashrc" to listOf("sh", "shell", "shellscript"), ".zshrc" to listOf("zsh", "shell"),
    )

    private val BY_EXTENSION: Map<String, List<String>> = buildMap {
        fun add(languages: List<String>, vararg extensions: String) = extensions.forEach { put(it, languages) }
        add(listOf("c", "cdoc"), "c", "h")
        add(listOf("cpp", "cppdoc"), "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ino")
        add(listOf("python", "pydoc"), "py", "pyw", "pyi")
        add(listOf("java", "javadoc"), "java")
        add(listOf("kotlin", "kdoc"), "kt", "kts")
        add(listOf("javascript", "jsdoc"), "js", "mjs", "cjs")
        add(listOf("javascriptreact", "javascript", "jsdoc"), "jsx")
        add(listOf("typescript", "tsdoc"), "ts", "mts", "cts")
        add(listOf("typescriptreact", "typescript", "tsdoc"), "tsx")
        add(listOf("rust", "rustdoc"), "rs")
        add(listOf("go"), "go")
        add(listOf("lua", "luadoc"), "lua")
        add(listOf("ruby", "rdoc"), "rb", "rake", "gemspec")
        add(listOf("eruby"), "erb")
        add(listOf("php", "phpdoc"), "php")
        add(listOf("sh", "shell", "shellscript", "shelldoc"), "sh", "bash")
        add(listOf("zsh", "shell", "shelldoc"), "zsh")
        add(listOf("csharp", "cs", "csharpdoc"), "cs")
        add(listOf("swift"), "swift")
        add(listOf("dart", "flutter"), "dart")
        add(listOf("scala"), "scala", "sc")
        add(listOf("haskell"), "hs")
        add(listOf("elixir"), "ex", "exs")
        add(listOf("eelixir"), "eex")
        add(listOf("heex"), "heex")
        add(listOf("erlang"), "erl", "hrl")
        add(listOf("julia"), "jl")
        add(listOf("r"), "r")
        add(listOf("rmd"), "rmd")
        add(listOf("quarto"), "qmd")
        add(listOf("sql"), "sql")
        add(listOf("html"), "html", "htm", "xhtml")
        add(listOf("css"), "css")
        add(listOf("scss"), "scss")
        add(listOf("sass"), "sass")
        add(listOf("less"), "less")
        add(listOf("stylus"), "styl")
        add(listOf("vue"), "vue")
        add(listOf("svelte"), "svelte")
        add(listOf("astro"), "astro")
        add(listOf("markdown"), "md", "markdown")
        add(listOf("tex", "plaintex"), "tex", "sty", "cls")
        add(listOf("bibtex", "bib"), "bib")
        add(listOf("zig"), "zig")
        add(listOf("nix"), "nix")
        add(listOf("objc"), "m", "mm")
        add(listOf("fortran"), "f", "f77", "f90", "f95", "f03", "f08")
        add(listOf("glsl"), "glsl", "vert", "frag", "geom", "comp")
        add(listOf("gdscript"), "gd")
        add(listOf("cmake"), "cmake")
        add(listOf("make"), "mk", "mak")
        add(listOf("yaml"), "yaml", "yml")
        add(listOf("terraform"), "tf", "tfvars")
        add(listOf("solidity"), "sol")
        add(listOf("verilog"), "v", "vh")
        add(listOf("systemverilog"), "sv", "svh")
        add(listOf("vhdl"), "vhd", "vhdl")
        add(listOf("tcl"), "tcl")
        add(listOf("perl"), "pl", "pm")
        add(listOf("powershell", "ps1"), "ps1", "psm1")
        add(listOf("ocaml"), "ml")
        add(listOf("ocamlinterface"), "mli")
        add(listOf("ocaml.ocamllex"), "mll")
        add(listOf("reason"), "re", "rei")
        add(listOf("rescript"), "res", "resi")
        add(listOf("purescript"), "purs")
        add(listOf("gleam"), "gleam")
        add(listOf("org"), "org")
        add(listOf("rst"), "rst")
        add(listOf("norg"), "norg")
        add(listOf("nu"), "nu")
        add(listOf("ejs"), "ejs")
        add(listOf("twig"), "twig")
        add(listOf("liquid"), "liquid")
        add(listOf("pug", "jade"), "pug", "jade")
        add(listOf("fennel"), "fnl")
        add(listOf("kivy"), "kv")
        add(listOf("cobol"), "cob", "cbl", "cpy")
        add(listOf("plantuml"), "puml", "plantuml", "pu")
        add(listOf("asciidoc"), "adoc", "asciidoc")
        add(listOf("beancount"), "beancount")
        add(listOf("fsh"), "fsh")
        add(listOf("mint"), "mint")
        add(listOf("plaintext"), "txt")
    }

    // #endregion

    // #region variables

    /**
     * The values of the variables of snippets (`$TM_FILENAME`, `$CURRENT_YEAR`, ...),
     * for a file named [fileName] at [filePath] (null: not a local file), the cursor on
     * line [lineNumber] whose text is [lineText], [clipboard] giving what is on the
     * clipboard (asked only for `$CLIPBOARD`). Null for a variable it does not know (the
     * snippet's default is taken).
     */
    fun variables(
        fileName: String,
        filePath: String?,
        lineNumber: Int,
        lineText: String,
        currentWord: String,
        clipboard: () -> String?,
    ): (String) -> String? {
        val now = Calendar.getInstance()
        val comments = commentsOf(fileName)
        return { name ->
            when (name) {
                "TM_FILENAME" -> fileName
                "TM_FILENAME_BASE" -> fileName.substringBeforeLast('.')
                "TM_DIRECTORY" -> filePath?.let { File(it).parent }.orEmpty()
                "TM_FILEPATH" -> filePath ?: fileName
                "RELATIVE_FILEPATH" -> fileName
                "TM_LINE_INDEX" -> (lineNumber - 1).toString()
                "TM_LINE_NUMBER" -> lineNumber.toString()
                "TM_CURRENT_LINE" -> lineText
                "TM_CURRENT_WORD" -> currentWord
                "TM_SELECTED_TEXT", "SELECTION" -> ""
                "CLIPBOARD" -> clipboard().orEmpty()
                "CURRENT_YEAR" -> now.get(Calendar.YEAR).toString()
                "CURRENT_YEAR_SHORT" -> (now.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
                "CURRENT_MONTH" -> (now.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
                "CURRENT_MONTH_NAME" -> format(now, "MMMM")
                "CURRENT_MONTH_NAME_SHORT" -> format(now, "MMM")
                "CURRENT_DATE" -> now.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                "CURRENT_DAY_NAME" -> format(now, "EEEE")
                "CURRENT_DAY_NAME_SHORT" -> format(now, "EEE")
                "CURRENT_HOUR" -> now.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                "CURRENT_MINUTE" -> now.get(Calendar.MINUTE).toString().padStart(2, '0')
                "CURRENT_SECOND" -> now.get(Calendar.SECOND).toString().padStart(2, '0')
                "CURRENT_SECONDS_UNIX" -> (now.timeInMillis / 1000).toString()
                "CURRENT_TIMEZONE_OFFSET" -> timezoneOffset(now)
                "RANDOM" -> Random.nextInt(0, 1_000_000).toString().padStart(6, '0')
                "RANDOM_HEX" -> Random.nextInt(0, 0x1000000).toString(16).padStart(6, '0')
                "UUID" -> UUID.randomUUID().toString()
                "LINE_COMMENT" -> comments.first
                "BLOCK_COMMENT_START" -> comments.second
                "BLOCK_COMMENT_END" -> comments.third
                else -> null
            }
        }
    }

    private fun format(time: Calendar, pattern: String): String =
        SimpleDateFormat(pattern, Locale.ENGLISH).format(time.time)

    private fun timezoneOffset(time: Calendar): String {
        val minutes = TimeZone.getDefault().getOffset(time.timeInMillis) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val abs = Math.abs(minutes)
        return "$sign${(abs / 60).toString().padStart(2, '0')}:${(abs % 60).toString().padStart(2, '0')}"
    }

    // the line comment and the block comment of a file's language
    private fun commentsOf(fileName: String): Triple<String, String, String> {
        val languages = languagesOf(fileName)
        return when {
            languages.any { it in setOf("python", "sh", "shell", "zsh", "ruby", "perl", "r", "yaml", "make", "cmake",
                "nix", "elixir", "julia", "tcl", "powershell", "dockerfile", "toml", "nu", "gdscript") } ->
                Triple("#", "\"\"\"", "\"\"\"")
            languages.any { it in setOf("lua", "sql", "haskell", "purescript") } -> Triple("--", "--[[", "]]")
            languages.any { it in setOf("html", "markdown", "vue", "svelte", "astro", "xml") } -> Triple("", "<!--", "-->")
            languages.any { it in setOf("tex", "plaintex", "erlang") } -> Triple("%", "", "")
            languages.any { it in setOf("ocaml", "reason") } -> Triple("", "(*", "*)")
            languages.any { it in setOf("css", "scss", "less") } -> Triple("//", "/*", "*/")
            else -> Triple("//", "/*", "*/")
        }
    }

    // #endregion
}
