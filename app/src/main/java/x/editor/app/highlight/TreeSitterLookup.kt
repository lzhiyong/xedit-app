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

package x.editor.app.highlight

import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a file finds its grammar, library and queries in the downloaded resources:
 * file name → grammar symbol (filetypes.json) → library (`lib<symbol>.so`) →
 * queries (`queries/<grammar>/highlights.scm`). The engine and the diagnostics
 * (see TreeSitterDiagnostics) go the same way.
 *
 * Each step takes the usual variants, since the files are made elsewhere:
 *  - filetypes.json: `{"tree_sitter_c": [".c", ".h"]}`, or with grammar names as
 *    keys (`"c"`), extensions with or without the dot or as `*.c`, an object per
 *    grammar with arrays in it (`{"c": {"extensions": [...], "filenames": [...]}}`),
 *    a map the other way round (`{".c": "c"}`), or an array of such objects;
 *  - libraries: `libtree_sitter_c.so`, `tree_sitter_c.so`, `libtree-sitter-c.so`,
 *    `libc.so`, `c.so`;
 *  - query folders: the grammar name, with `_` or `-` (`c_sharp`, `c-sharp`).
 */
object TreeSitterLookup {

    // #region file types

    /**
     * The file types of [file]: file names and extensions (`.kt`, `Makefile`) to
     * grammar symbols (`tree_sitter_kotlin`).
     *
     * @throws Exception if it is not JSON (SerializationException, IOException)
     */
    fun parseFileTypes(file: File): Map<String, String> {
        val types = HashMap<String, String>()
        collect(Json.parseToJsonElement(file.readText()), types)
        return types
    }

    private fun collect(element: JsonElement, types: MutableMap<String, String>) {
        when (element) {
            is JsonArray -> element.forEach { collect(it, types) }
            is JsonObject -> {
                // an object of one grammar: {"name": "c", "extensions": [...]}
                val name = listOf("symbol", "name", "language", "grammar", "id")
                    .firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.contentOrNull }
                if (name != null && element.values.any { it is JsonArray }) {
                    patternsOf(element).forEach { add(types, it, name) }
                    return
                }
                // a map of grammars
                element.forEach { (key, value) ->
                    when {
                        // "tree_sitter_c": [".c", ".h"]
                        value is JsonArray && value.all { it is JsonPrimitive } ->
                            patternsOf(value).forEach { add(types, it, key) }
                        // "c": {"extensions": [...], "filenames": [...]}
                        value is JsonObject && value.keys.isNotEmpty() && value.keys.all { it in GRAMMAR_FIELDS } ->
                            patternsOf(value).forEach { add(types, it, key) }
                        // a wrapper: {"filetypes": {...}}, {"languages": [...]}
                        value is JsonObject || value is JsonArray -> collect(value, types)
                        // ".c": "c", the other way round
                        value is JsonPrimitive -> value.contentOrNull?.let { add(types, key, it) }
                    }
                }
            }
            else -> Unit
        }
    }

    // the fields of an object that describes the files of one grammar
    private val GRAMMAR_FIELDS = setOf(
        "extensions", "extension", "filenames", "filename", "files", "patterns", "globs",
        "suffixes", "types", "file_types", "filetypes", "names"
    )

    // every string in arrays of [element], deep
    private fun patternsOf(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.flatMap { item ->
            if (item is JsonPrimitive) listOfNotNull(item.contentOrNull) else patternsOf(item)
        }
        is JsonObject -> element.values.filter { it !is JsonPrimitive }.flatMap { patternsOf(it) }
        else -> emptyList()
    }

    private fun add(types: MutableMap<String, String>, pattern: String, grammar: String) {
        val symbol = symbolOf(grammar)
        val clean = pattern.trim().removePrefix("*")
        if (clean.isEmpty()) return
        types.putIfAbsent(clean, symbol)
        // "kt" is the extension ".kt"; "Makefile" stays a name, and gets ".Makefile" too (harmless)
        if (!clean.startsWith(".") && !clean.contains('/')) types.putIfAbsent(".$clean", symbol)
    }

    /** The symbol of a grammar: `kotlin` and `tree_sitter_kotlin` give `tree_sitter_kotlin`. */
    fun symbolOf(grammar: String): String {
        val name = grammar.trim().replace('-', '_')
        return if (name.startsWith(TreeSitterUtils.SYMBOL_PREFIX)) name else TreeSitterUtils.SYMBOL_PREFIX + name
    }

    /**
     * The symbol for [fileName]: the whole name, then each extension from the
     * longest (`a.d.ts`: `.d.ts`, then `.ts`), each as it is and in lower case.
     */
    fun symbolFor(fileName: String, types: Map<String, String>): String? {
        val name = fileName.substringAfterLast('/')
        val candidates = ArrayList<String>()
        candidates += name
        var dot = name.indexOf('.')
        while (dot >= 0) {
            candidates += name.substring(dot)
            dot = name.indexOf('.', dot + 1)
        }
        for (candidate in candidates) {
            types[candidate]?.let { return it }
            types[candidate.lowercase()]?.let { return it }
        }
        return null
    }

    // #endregion

    // #region libraries and queries

    /** The file names a library of [symbol] may have, the usual one first. */
    fun libraryNames(symbol: String): List<String> {
        val grammar = TreeSitterUtils.grammarNameOf(symbol)
        return listOf(
            "lib$symbol.so",
            "$symbol.so",
            "lib${symbol.replace('_', '-')}.so",
            "libtree-sitter-${grammar.replace('_', '-')}.so",
            "lib$grammar.so",
            "$grammar.so"
        ).distinct()
    }

    /** The library of [symbol] among [libraries] (by file name); null if none. */
    fun findLibrary(symbol: String, libraries: Map<String, File>): File? =
        libraryNames(symbol).firstNotNullOfOrNull { libraries[it] }

    /**
     * The names the query folder of a grammar may have: the name the grammar gives
     * itself ([languageName], may be null), the one of its symbol, each with `_`
     * and with `-`.
     */
    fun queryNames(languageName: String?, symbol: String): List<String> =
        listOfNotNull(languageName, TreeSitterUtils.grammarNameOf(symbol))
            .flatMap { listOf(it, it.lowercase(), it.replace('-', '_'), it.replace('_', '-')) }
            .distinct()

    /** The first of [names] with a query of [kind] (`highlights`) in [queriesDir]; null if none. */
    fun findQueryName(queriesDir: File, names: List<String>, kind: String = "highlights"): String? =
        names.firstOrNull { File(queriesDir, "$it/$kind.scm").isFile }

    // #endregion
}
