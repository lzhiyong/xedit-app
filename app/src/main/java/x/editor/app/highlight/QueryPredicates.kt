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

import android.util.Log

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

import x.github.module.treesitter.TSNode
import x.github.module.treesitter.TSQueryMatch
import x.github.module.treesitter.TSQueryPredicate
import x.github.module.treesitter.TSQueryPredicateArgs

/**
 * The query predicates Neovim adds to those of tree-sitter, which the queries of
 * nvim-treesitter use; tree-sitter itself knows `eq?`, `match?` and `any-of?`.
 * Without these a predicate such as `(#lua-match? @_import "^[A-Z]")` would always
 * hold, and its pattern would capture what it is meant to leave out (every part of
 * an import path a type, not only the class at its end).
 *
 *  - `lua-match?`: a Lua pattern ([luaToRegex]);
 *  - `vim-match?`: a Vim regular expression, the common part of it ([vimToRegex]);
 *  - `contains?`: the text contains one of the strings;
 *  - `has-ancestor?`, `has-parent?`: a node above it is of one of the types;
 *  - `kind-eq?`: the node is of one of the types;
 *
 * each also as `not-…` and `any-…` (one of the nodes of a quantified capture is
 * enough). Directives (`#set!`, `#offset!`, `#gsub!`, …) change nothing here and
 * hold; so does a predicate this does not know, as it would rather style too much
 * than lose a pattern.
 */
object QueryPredicates {

    private const val TAG = "QueryPredicates"

    // the patterns turned into regular expressions; FAILED for one that cannot be
    private val regexCache = ConcurrentHashMap<String, Any>()
    private val FAILED = Any()

    // A predicate, worked out once (it is asked about for every match of its pattern,
    // hundreds a screen): the capture it tests, the test of one of its nodes, and how
    // the nodes add up. HOLDS: it holds whatever the match (a directive, a predicate
    // this does not know, one without a capture). Kept as long as the predicate, by
    // the predicate itself (a TSQueryPredicate is equal to itself only).
    private class Compiled(
        val capture: String,
        val test: (TSNode) -> Boolean,
        val negated: Boolean,
        val isAny: Boolean
    )

    private val HOLDS = Any()
    private val compiled: MutableMap<TSQueryPredicate, Any> = Collections.synchronizedMap(WeakHashMap())

    /** Whether [predicate] holds for [match]. Any thread. */
    fun evaluate(predicate: TSQueryPredicate, match: TSQueryMatch): Boolean {
        val compiled = compiled[predicate] ?: compile(predicate).also { compiled[predicate] = it }
        if (compiled !is Compiled) return true

        // a capture that did not take part in the match (optional): hold
        val nodes = match[compiled.capture]
        if (nodes.isEmpty()) return true
        if (compiled.isAny) {
            for (node in nodes) if (compiled.test(node) != compiled.negated) return true
            return false
        }
        for (node in nodes) if (compiled.test(node) == compiled.negated) return false
        return true
    }

    private fun compile(predicate: TSQueryPredicate): Any {
        val name = predicate.name
        // directives: #set!, #offset!, #gsub!, #trim!, ...
        if (name.endsWith("!")) return HOLDS

        val isAny = name.startsWith("any-")
        val base = name.removePrefix("any-")
        val negated = base.startsWith("not-")
        val kind = base.removePrefix("not-")

        val args = predicate.args
        val capture = (args.firstOrNull() as? TSQueryPredicateArgs.Capture)?.value ?: return HOLDS
        val literals = args.drop(1).mapNotNull { (it as? TSQueryPredicateArgs.Literal)?.value }

        val test: (TSNode) -> Boolean = when (kind) {
            "lua-match?" -> regexTest("lua", literals.firstOrNull(), ::luaToRegex)
            "vim-match?" -> regexTest("vim", literals.firstOrNull(), ::vimToRegex)
            "contains?" -> { node -> textOf(node)?.let { text -> literals.any { text.contains(it) } } ?: true }
            "has-ancestor?" -> { node -> hasAncestor(node, literals) }
            "has-parent?" -> { node -> node.parent?.type in literals }
            "kind-eq?" -> { node -> node.type in literals }
            // not one of Neovim's: hold
            else -> return HOLDS
        }
        return Compiled(capture, test, negated, isAny)
    }

    private fun textOf(node: TSNode): String? = node.text()?.toString()

    private fun hasAncestor(node: TSNode, types: List<String>): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent.type in types) return true
            parent = parent.parent
        }
        return false
    }

    // a test of the text against [pattern], turned into a regex by [convert]; a
    // pattern that cannot be turned into one matches nothing
    private fun regexTest(dialect: String, pattern: String?, convert: (String) -> Regex): (TSNode) -> Boolean {
        val regex = pattern?.let { regexFor(dialect, it, convert) } ?: return { false }
        return { node -> textOf(node)?.let { regex.containsMatchIn(it) } ?: true }
    }

    private fun regexFor(dialect: String, pattern: String, convert: (String) -> Regex): Regex? {
        val key = "$dialect\u0000$pattern"
        regexCache[key]?.let { return it as? Regex }
        val regex = try {
            convert(pattern)
        } catch (e: Exception) {
            Log.w(TAG, "cannot use the pattern \"$pattern\"", e)
            null
        }
        regexCache[key] = regex ?: FAILED
        return regex
    }

    // #region Lua patterns

    // the classes of Lua patterns (%a, %d, ...), as the content of a regex set
    private fun luaClass(letter: Char): String? = when (letter) {
        'a' -> "A-Za-z"
        'd' -> "0-9"
        'l' -> "a-z"
        'u' -> "A-Z"
        's' -> " \\t\\n\\r\\f\\x0B"
        'w' -> "A-Za-z0-9"
        'x' -> "0-9A-Fa-f"
        'p' -> "\\p{Punct}"
        'c' -> "\\p{Cntrl}"
        'g' -> "\\x21-\\x7E"
        else -> null
    }

    // %X: a class (upper case: its complement), or X itself, literally
    private fun luaEscape(c: Char, inSet: Boolean): String {
        val set = luaClass(c.lowercaseChar())
        if (set != null) {
            return when {
                c.isUpperCase() -> "[^$set]"
                inSet -> set
                else -> "[$set]"
            }
        }
        return if (c.isLetterOrDigit()) c.toString() else "\\$c"
    }

    /**
     * A Lua pattern as a Java regular expression. Lua patterns are searched for, as
     * `string.find` does: anchors (`^` at the start, `$` at the end) only where they
     * stand there.
     *
     * @throws IllegalArgumentException if it is not a pattern (an unclosed set, ...)
     */
    fun luaToRegex(pattern: String): Regex {
        val out = StringBuilder()
        var i = 0
        val n = pattern.length
        // whether the last thing written can take a quantifier (for "-")
        var lastIsItem = false

        // [set] from [start] (at its "["): the regex set, and where the pattern goes on
        fun readSet(start: Int): Pair<String, Int> {
            val set = StringBuilder("[")
            var j = start + 1
            if (j < n && pattern[j] == '^') { set.append('^'); j++ }
            // a "]" first is the character itself
            if (j < n && pattern[j] == ']') { set.append("\\]"); j++ }
            while (j < n && pattern[j] != ']') {
                val c = pattern[j]
                when {
                    c == '%' && j + 1 < n -> { set.append(luaEscape(pattern[j + 1], inSet = true)); j += 2 }
                    // special in a Java set, not in a Lua one
                    c == '[' || c == '&' || c == '\\' || c == '^' -> { set.append('\\').append(c); j++ }
                    else -> { set.append(c); j++ }
                }
            }
            require(j < n) { "unclosed [ in \"$pattern\"" }
            set.append(']')
            return set.toString() to j + 1
        }

        if (n > 0 && pattern[0] == '^') { out.append('^'); i = 1 }
        while (i < n) {
            val c = pattern[i]
            when {
                c == '%' && i + 1 < n -> {
                    val d = pattern[i + 1]
                    when {
                        // %b(): balanced; without nesting, which is what a regex can do
                        d == 'b' && i + 3 < n -> {
                            val open = Regex.escape(pattern[i + 2].toString())
                            val close = Regex.escape(pattern[i + 3].toString())
                            out.append("$open(?:(?!$close).)*$close")
                            i += 4
                        }
                        // %f[set]: the frontier, where a char not in the set is followed by one in it
                        d == 'f' && i + 2 < n && pattern[i + 2] == '[' -> {
                            val (set, next) = readSet(i + 2)
                            // (the start of the text counts as outside of the set)
                            out.append("(?<!$set)(?=$set)")
                            i = next
                            lastIsItem = false
                            continue
                        }
                        // %1: the text of a capture again
                        d.isDigit() -> { out.append('\\').append(d); i += 2 }
                        else -> { out.append(luaEscape(d, inSet = false)); i += 2 }
                    }
                    lastIsItem = true
                }
                c == '[' -> {
                    val (set, next) = readSet(i)
                    out.append(set)
                    i = next
                    lastIsItem = true
                }
                c == '.' -> { out.append('.'); i++; lastIsItem = true }
                c == '$' && i == n - 1 -> { out.append('$'); i++; lastIsItem = false }
                c == '*' || c == '+' || c == '?' -> {
                    out.append(if (lastIsItem) c.toString() else "\\$c"); i++; lastIsItem = false
                }
                // the lazy * of Lua; a "-" with nothing before it is itself
                c == '-' -> { out.append(if (lastIsItem) "*?" else "\\-"); i++; lastIsItem = false }
                c == '(' || c == ')' -> { out.append(c); i++; lastIsItem = false }
                c.isLetterOrDigit() || c == ' ' || c == '_' -> { out.append(c); i++; lastIsItem = true }
                // anything else is itself in Lua, and may not be in Java: escaped
                else -> { out.append('\\').append(c); i++; lastIsItem = true }
            }
        }
        return Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    // #endregion

    // #region Vim regular expressions

    /**
     * The common part of Vim regular expressions as a Java one: magic (the default)
     * and very magic (`\v`); `\c` and `\C` for the case; `\<` and `\>` (`<` and `>`
     * with `\v`) word boundaries, `\%(` a group that captures nothing, `\=` the `?`
     * of Vim, `\a \l \u \x` classes.
     *
     * @throws IllegalArgumentException if what comes out is no regular expression
     */
    fun vimToRegex(pattern: String): Regex {
        var veryMagic = false
        var ignoreCase = false
        val out = StringBuilder()
        var i = 0
        val n = pattern.length
        while (i < n) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < n) {
                val d = pattern[i + 1]
                i += 2
                when (d) {
                    'v' -> veryMagic = true
                    'm', 'M', 'V' -> veryMagic = false
                    'c' -> ignoreCase = true
                    'C' -> ignoreCase = false
                    '<', '>' -> out.append(if (veryMagic) "\\$d" else "\\b")
                    'a' -> out.append("[A-Za-z]")
                    'l' -> out.append("[a-z]")
                    'u' -> out.append("[A-Z]")
                    'x' -> out.append("[0-9A-Fa-f]")
                    'h' -> out.append("[A-Za-z_]")
                    '%' -> if (i < n && pattern[i] == '(') { out.append("(?:"); i++ } else out.append("%")
                    // in magic mode these are the operators, written with a backslash
                    '(', ')', '|', '+', '?', '{', '}' -> out.append(if (veryMagic) "\\$d" else d.toString())
                    '=' -> out.append(if (veryMagic) "\\=" else "?")
                    else -> out.append('\\').append(d)
                }
                continue
            }
            i++
            if (veryMagic) {
                when (c) {
                    '<', '>' -> out.append("\\b")
                    '=' -> out.append('?')
                    '%' -> if (i < n && pattern[i] == '(') { out.append("(?:"); i++ } else out.append("%")
                    else -> out.append(c)
                }
            } else {
                when (c) {
                    // without the backslash, these are themselves in magic mode
                    '(', ')', '|', '+', '?', '{', '}' -> out.append('\\').append(c)
                    else -> out.append(c)
                }
            }
        }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(out.toString(), options)
    }

    // #endregion
}
