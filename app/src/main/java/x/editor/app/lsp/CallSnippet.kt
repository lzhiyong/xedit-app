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

import x.github.module.lsp.CompletionKind

/**
 * The call of a function taken from the completion list, as a snippet with a placeholder
 * for each of its parameters: Tab goes from one to the next, then past the `)`, as in nvim.
 *
 * Servers that can send such snippets do (clangd: `printf(${1:const char *format}, ...)`),
 * and those are taken as they are. Others send only the name of a function (pyright,
 * lua-language-server, typescript-language-server by default): for those the parameters
 * are read from what the item says of its signature (its label details, its detail, its
 * documentation), as `foo(${1:a}, ${2:b})$0`. The required ones only: a parameter with a
 * default, an optional one, `*args`, `...` and `self` get no placeholder. Nothing known
 * of the parameters: `foo($1)$0`, the cursor between the brackets (nvim's auto brackets).
 */
object CallSnippet {

    private val CALLABLE = setOf(CompletionKind.FUNCTION, CompletionKind.METHOD, CompletionKind.CONSTRUCTOR)

    // what comes before a name that is not called there: an import, an #include, ...
    private val NOT_A_CALL_LINE = Regex("""^\s*(import|from|export|#\s*include|using|use|package|require)\b""")
    // a name taken as a value, a decorator
    private val NOT_A_CALL_BEFORE = setOf('&', '@')

    private val IDENTIFIER = Regex("""^[\p{L}_$][\p{L}\p{N}_$]*$""")

    /**
     * The snippet that calls [name] (what the item puts in), or null if it is not called
     * there: not a function ([kind]), already followed by `(` ([textAfter], the rest of
     * the line), or in an import ([textBefore], the line before the name). [signatures]:
     * what the item says of its signature, the first with a parameter list wins.
     */
    fun of(
        name: String,
        kind: CompletionKind?,
        signatures: List<String?>,
        textBefore: String,
        textAfter: String,
    ): String? {
        if (kind !in CALLABLE || !IDENTIFIER.matches(name)) return null
        if (textAfter.trimStart().startsWith("(")) return null
        if (NOT_A_CALL_LINE.containsMatchIn(textBefore)) return null
        if (textBefore.trimEnd().lastOrNull() in NOT_A_CALL_BEFORE) return null
        val parameters = signatures.asSequence().filterNotNull().mapNotNull { parameters(it, name) }.firstOrNull()
        val call = escape(name)
        return when {
            // nothing known of them: between the brackets, Tab past them
            parameters == null -> "$call(\$1)\$0"
            // none: after the brackets
            parameters.all.isEmpty() -> "$call()\$0"
            // only optional ones: between the brackets
            parameters.required.isEmpty() -> "$call(\$1)\$0"
            else -> parameters.required.withIndex().joinToString(", ", "$call(", ")\$0") { (i, p) ->
                "\${${i + 1}:${escape(p)}}"
            }
        }
    }

    /** The parameters of a signature: all of them, and the names of the required ones. */
    data class Parameters(val all: List<String>, val required: List<String>)

    /**
     * The parameters in [signature]: the list after `name(` if it is there, else after its
     * first `(`. Null if it has no parameter list.
     */
    fun parameters(signature: String, name: String): Parameters? {
        val open = Regex("""(?<![\p{L}\p{N}_$])${Regex.escape(name)}\s*\(""").find(signature)?.let { it.range.last }
            ?: signature.indexOf('(').takeIf { it >= 0 }
            ?: return null
        val close = matching(signature, open) ?: return null
        val all = splitTopLevel(signature.substring(open + 1, close), ',').map { it.trim() }.filter { it.isNotEmpty() }
        return Parameters(all, all.mapNotNull(::requiredName))
    }

    // the placeholder of a required parameter; null for one that is not
    private fun requiredName(parameter: String): String? {
        val p = parameter.trim()
        if (p == "self" || p == "cls" || p == "this" || p == "/" || p == "*" || p == "void") return null
        if (p.startsWith("*") || p.startsWith("...") || p.endsWith("...")) return null
        if (splitTopLevel(p, '=').size > 1) return null
        val colon = topLevelColon(p)
        val namePart = if (colon >= 0) p.substring(0, colon).trim() else p
        // TypeScript and Swift mark optional ones: `b?: string`
        if (namePart.endsWith("?")) return null
        return namePart.ifEmpty { null }
    }

    // where the bracket at [open] closes, brackets and quotes in between skipped
    private fun matching(text: String, open: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when (c) {
                '"', '\'', '`' -> quote = c
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return if (c == ')') i else null
                }
            }
            i++
        }
        return null
    }

    // [text] split at [separator] where it is not in brackets (also <> of generics) or quotes
    private fun splitTopLevel(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var quote: Char? = null
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else when {
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '(' || c == '[' || c == '{' || c == '<' -> depth++
                (c == ')' || c == ']' || c == '}' || (c == '>' && i > 0 && text[i - 1] != '=' && text[i - 1] != '-')) -> depth = (depth - 1).coerceAtLeast(0)
                // `==`, `<=`, `>=`, `!=`, `=>` are not the `=` of a default
                c == separator && depth == 0 && !(separator == '=' && isOperator(text, i)) -> {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        parts += text.substring(start)
        return parts
    }

    private fun isOperator(text: String, i: Int): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return after == '=' || after == '>' || before == '=' || before == '<' || before == '>' || before == '!'
    }

    // the `:` of `name: Type` (not of C++'s `::`), outside brackets; -1 if none
    private fun topLevelColon(text: String): Int {
        var depth = 0
        for (i in text.indices) {
            when (text[i]) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth = (depth - 1).coerceAtLeast(0)
                ':' -> if (depth == 0 && text.getOrNull(i + 1) != ':' && text.getOrNull(i - 1) != ':') return i
            }
        }
        return -1
    }

    // the chars that mean something in a snippet, taken literally
    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c == '\\' || c == '$' || c == '}') append('\\')
            append(c)
        }
    }
}
