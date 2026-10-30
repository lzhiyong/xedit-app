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

package x.editor.app.toolchain

/** Where a command runs: with the app's own toolchain (its usr), or in Termux. */
enum class Runner(val key: String) {
    LOCAL("local"), TERMUX("termux");

    companion object {
        fun of(key: String?): Runner? = entries.firstOrNull { it.key == key }
    }
}

/** What the user chose for where commands run: [AUTO] picks per command (see ToolchainRules.choose). */
enum class RunnerMode(val key: String) {
    AUTO("auto"), LOCAL("local"), TERMUX("termux");

    companion object {
        fun of(key: String?): RunnerMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * The rules of the toolchain that need no device: what a script is run with, which
 * programs a command line runs, and where it runs.
 */
object ToolchainRules {

    /** The interpreter of a script, from its `#!` line: [program] (a path or a name) and [arguments] before the script. */
    data class Shebang(val program: String, val arguments: List<String>)

    /**
     * The `#!` line of a script; null if [firstLine] is not one. `#!/usr/bin/env node` is
     * `node` (to be found), `#!/bin/sh -e` is `/bin/sh` with `-e`.
     */
    fun shebang(firstLine: String): Shebang? {
        if (!firstLine.startsWith("#!")) return null
        val words = firstLine.substring(2).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val interpreter = words[0]
        if (interpreter.substringAfterLast('/') == "env") {
            // `env -S node --flag`, `env node`
            val rest = words.drop(1).dropWhile { it.startsWith("-") }
            if (rest.isEmpty()) return null
            return Shebang(rest[0], rest.drop(1))
        }
        return Shebang(interpreter, words.drop(1))
    }

    // the shell's own commands, and what Android has of its own in /system/bin: they run
    // wherever the command runs, they decide nothing
    private val NEUTRAL = setOf(
        "cd", "echo", "printf", "test", "[", "export", "set", "unset", "exit", "true", "false", ":", "read",
        "pwd", "exec", "eval", "source", ".", "shift", "wait", "trap", "time", "command", "type", "alias",
        "ls", "cat", "mkdir", "rm", "rmdir", "cp", "mv", "ln", "chmod", "touch", "env", "sleep", "sh",
        "grep", "sed", "head", "tail", "find", "xargs", "sort", "uniq", "wc", "tr", "cut", "tee", "date",
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
    )

    // what runs other programs: they need what they run too
    private val NEEDS = mapOf(
        "gradle" to listOf("java"), "gradlew" to listOf("java"), "./gradlew" to listOf("java"),
        "mvn" to listOf("java"), "kotlinc" to listOf("java"), "npx" to listOf("node"), "npm" to listOf("node"),
    )

    /**
     * The programs [command] (a line for `sh -c`) runs: the first word of each simple
     * command, after `&&`, `||`, `;`, `|`, `(` and `$(`, past `VAR=value` and `sudo`/`env`.
     * Quoted words are taken whole; what is in quotes is not looked into.
     */
    fun programs(command: String): List<String> = commands(command).map { it.first() }

    // the simple commands of [command]: each its program, then its words
    private fun commands(command: String): List<List<String>> {
        val out = ArrayList<MutableList<String>>()
        var current: MutableList<String>? = null
        for (token in tokens(command)) {
            if (token.isOperator) {
                current = null
                continue
            }
            val words = current
            if (words != null) {
                words += token.text
                continue
            }
            val word = token.text
            if ('=' in word && !word.startsWith("=") && word.substringBefore('=').all { it.isLetterOrDigit() || it == '_' }) continue
            if (word == "sudo" || word == "nohup" || word == "time") continue
            current = mutableListOf(word).also { out += it }
        }
        return out
    }

    // the script a shell runs (`sh ./gradlew build`: ./gradlew), if [words] are a shell's
    private fun scriptOf(words: List<String>): String? =
        if (words.first().substringAfterLast('/') in SHELLS) words.drop(1).firstOrNull { !it.startsWith("-") } else null

    private val SHELLS = setOf("sh", "bash")

    private class Token(val text: String, val isOperator: Boolean)

    private fun tokens(command: String): List<Token> {
        val out = ArrayList<Token>()
        val word = StringBuilder()
        var quote: Char? = null
        fun flush() {
            if (word.isNotEmpty()) out += Token(word.toString(), false)
            word.clear()
        }
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '\\' && i + 1 < command.length -> { word.append(command[i + 1]); i++ }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> flush()
                c == '$' && command.getOrNull(i + 1) == '(' -> { flush(); out += Token("$(", true); i++ }
                // a redirection (`2>&1`, `&>log`), not the end of a command
                c == '&' && (command.getOrNull(i - 1) == '>' || command.getOrNull(i + 1) == '>') -> word.append(c)
                c == ';' || c == '|' || c == '&' || c == '(' || c == ')' || c == '`' || c == '\n' -> {
                    flush()
                    out += Token(c.toString(), true)
                }
                else -> word.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /**
     * Where [command] runs, by [mode]: as chosen, or (AUTO) with the app's own toolchain
     * when it has every program the command needs ([isLocal]), else in Termux if it is
     * there ([termuxInstalled]), else with the app's own all the same (what is missing is
     * said by the shell). A command of the shell alone runs with the app's own; one that
     * runs only paths (`./build.sh`) in Termux, whose tools the script is likely to want.
     */
    fun choose(command: String, mode: RunnerMode, termuxInstalled: Boolean, isLocal: (String) -> Boolean): Runner {
        when (mode) {
            RunnerMode.LOCAL -> return Runner.LOCAL
            RunnerMode.TERMUX -> return Runner.TERMUX
            RunnerMode.AUTO -> Unit
        }
        val required = LinkedHashSet<String>()
        var paths = false
        for (words in commands(command)) {
            val program = words.first()
            NEEDS[program.substringAfterLast('/')]?.let { required += it }
            // a script run by a shell needs what it runs too (`sh ./gradlew`: java)
            scriptOf(words)?.let { script -> NEEDS[script.substringAfterLast('/')]?.let { required += it } }
            when {
                program in NEUTRAL -> Unit
                // a path, or one of ours under a variable (${tmp}/a.out): no say of its own
                '/' in program || program.startsWith("$") -> {
                    if (NEEDS[program.substringAfterLast('/')] == null) paths = true
                }
                else -> required += program
            }
        }
        return when {
            required.isNotEmpty() && required.all(isLocal) -> Runner.LOCAL
            required.isNotEmpty() -> if (termuxInstalled) Runner.TERMUX else Runner.LOCAL
            paths && termuxInstalled -> Runner.TERMUX
            else -> Runner.LOCAL
        }
    }

    /**
     * What a shell run with the app's toolchain is started with, before the command: for
     * each program of usr/bin ([launchers]: its name, and the words that start it, through
     * the system's dynamic linker or its interpreter), a function of its name (an alias for
     * a name a function cannot have, `clang++`), so that the command finds it. An app may
     * not execute its own files directly (Android 10 and above), so neither may a shell it
     * runs; see Toolchain.
     */
    fun preamble(launchers: Map<String, List<String>>): String = buildString {
        for ((name, words) in launchers.toSortedMap()) {
            if (!SAFE_NAME.matches(name)) continue
            val line = words.joinToString(" ") { quote(it) } + " \"\$@\""
            if (FUNCTION_NAME.matches(name)) append(name).append("() { ").append(line).append("; }\n")
            else append("alias ").append(quote(name)).append('=').append(quote(words.joinToString(" ") { quote(it) })).append('\n')
        }
    }

    private val SAFE_NAME = Regex("""^[A-Za-z0-9_.+\-]+$""")
    private val FUNCTION_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    /** [word] quoted for sh: in single quotes, unless it needs none. */
    fun quote(word: String): String =
        if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "/._-+=:,@%" }) word
        else "'" + word.replace("'", "'\\''") + "'"
}
