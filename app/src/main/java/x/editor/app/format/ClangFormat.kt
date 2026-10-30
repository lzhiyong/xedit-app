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

package x.editor.app.format

/**
 * What a `.clang-format` says of indentation, for the editor to type as clangd will
 * format: `IndentWidth`, `TabWidth`, `UseTab`, and the indent width of `BasedOnStyle`
 * when it sets none itself. The rest of the file is clangd's business.
 *
 * A file of several YAML documents (`---`), one per `Language`: the one for the language
 * of the document, over the one without a `Language` (it applies to all).
 */
object ClangFormat {

    /** The names clang-format looks for, in each directory from the file's up. */
    val FILES = listOf(".clang-format", "_clang-format")

    data class Style(val indentWidth: Int?, val tabWidth: Int?, val useTabs: Boolean?)

    // the indent widths of the predefined styles
    private val BASED_ON = mapOf(
        "llvm" to 2, "google" to 2, "chromium" to 2, "mozilla" to 2, "gnu" to 2,
        "webkit" to 4, "microsoft" to 4,
    )

    // clang-format's Language for a language id
    private fun languageOf(languageId: String): String = when (languageId.lowercase()) {
        "c", "cpp", "cuda" -> "cpp"
        "objective-c", "objective-cpp", "objc" -> "objc"
        "javascript", "typescript" -> "javascript"
        "csharp" -> "csharp"
        else -> languageId.lowercase()
    }

    /** What [text] (a `.clang-format`) says of indentation for [languageId]. */
    fun parse(text: String, languageId: String): Style {
        val wanted = languageOf(languageId)
        var general: Map<String, String> = emptyMap()
        var specific: Map<String, String>? = null
        for (document in text.split(Regex("""(?m)^---\s*$"""))) {
            val keys = topLevel(document)
            if (keys.isEmpty()) continue
            val language = keys["language"]?.lowercase()
            when (language) {
                null -> general = keys
                wanted -> specific = keys
            }
        }
        val keys = general + (specific ?: emptyMap())
        val basedOn = keys["basedonstyle"]?.lowercase()?.let { BASED_ON[it] }
        val useTab = keys["usetab"]?.lowercase()?.let { it != "never" && it != "false" }
        return Style(
            indentWidth = keys["indentwidth"]?.toIntOrNull() ?: basedOn ?: if (keys.isNotEmpty()) BASED_ON.getValue("llvm") else null,
            tabWidth = keys["tabwidth"]?.toIntOrNull(),
            useTabs = useTab,
        )
    }

    // the keys at the top of a YAML document (not indented), lowercased, with their values
    private fun topLevel(document: String): Map<String, String> {
        val keys = LinkedHashMap<String, String>()
        for (line in document.lineSequence()) {
            if (line.isEmpty() || line[0].isWhitespace() || line.startsWith("#") || line.startsWith("...")) continue
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            val value = line.substring(colon + 1).substringBefore(" #").trim().trim('\'', '"')
            keys[line.substring(0, colon).trim().lowercase()] = value
        }
        return keys
    }
}
