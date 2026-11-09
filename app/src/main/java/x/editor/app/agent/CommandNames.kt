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

package x.editor.app.agent

/**
 * The programs a shell command line runs, by their names (`cd app && python3 x.py | grep -c
 * ok` → cd, python3, grep), for run_command to tell where it can run: with the app's own
 * tools if it has them all, otherwise in Termux (AgentTools).
 *
 * Not a shell's parser, a reading close enough for that: the line is cut into commands at
 * `;` `&` `|` `&&` `||` new lines, parentheses, `$(` and backquotes (what is quoted kept
 * whole); of each command the first word is the program, past variables set for it
 * (`CC=clang make`), redirections (`2>&1`, `> out.txt`), the words of the shell's own syntax
 * (`if`, `then`, `do`, `!`, `{`, ...) and what only starts another program (`env`, `exec`,
 * `command`, `time`, `nohup`, `timeout 60`). Left out (nothing run, or nothing to tell by):
 * `command -v x` (which only asks where x is), commands that only set a loop's or a
 * case's words (`for f in *.c`), a program given by a path (`./gradlew`, `/system/bin/ls`:
 * where it is, is said), or by a variable (`$CC`), and functions being defined.
 */
internal object CommandNames {

    // words of the shell's syntax before a command (the command is the next word)
    private val LEADING = setOf("!", "{", "}", "if", "then", "else", "elif", "do", "while", "until", "time")

    // words that end a construct (nothing to run in them)
    private val CLOSING = setOf("fi", "done", "esac", "}", "]]")

    // constructs whose own words are not commands (until the next command)
    private val NOT_COMMANDS = setOf("for", "case", "select", "function", "[[")

    // programs that only start the program after them (and their options)
    private val STARTERS = setOf("env", "exec", "command", "nohup", "nice", "timeout", "builtin")

    /** The programs [line] runs, in order, each once. */
    fun of(line: String): List<String> {
        val names = LinkedHashSet<String>()
        for (command in commands(line)) {
            name(command)?.let { names += it }
        }
        return names.toList()
    }

    // the program of [words] (one command), if it names one
    private fun name(words: List<String>): String? {
        var i = 0
        var starter: String? = null
        while (i < words.size) {
            val word = words[i]
            when {
                isAssignment(word) -> i++
                isRedirection(word) -> i += if (redirectionNeedsTarget(word)) 2 else 1
                starter == null && word in LEADING -> i++
                starter == null && (word in CLOSING || word in NOT_COMMANDS) -> return null
                // `command -v x` only asks where x is
                starter == "command" && (word == "-v" || word == "-V") -> return null
                starter != null && word.startsWith("-") -> i++
                // timeout's duration (a number, with s/m/h/d after it)
                starter == "timeout" && word.matches(DURATION) -> i++
                word in STARTERS -> {
                    starter = word
                    i++
                }
                else -> return programName(word)
            }
        }
        return null
    }

    private fun programName(word: String): String? = when {
        word.isEmpty() -> null
        '/' in word -> null
        '$' in word || '`' in word -> null
        word.endsWith("()") -> null
        word.endsWith(")") -> null
        else -> word
    }

    private fun isAssignment(word: String): Boolean = ASSIGNMENT.matches(word)

    private fun isRedirection(word: String): Boolean = REDIRECTION.matches(word)

    // `>` `2>` `>>` `<` on their own: the next word is the file
    private fun redirectionNeedsTarget(word: String): Boolean = REDIRECTION_ALONE.matches(word)

    /** [line] cut into commands, each its words (quotes taken off what they held). */
    internal fun commands(line: String): List<List<String>> {
        val commands = ArrayList<List<String>>()
        var words = ArrayList<String>()
        val word = StringBuilder()
        var inWord = false
        fun endWord() {
            if (inWord) words += word.toString()
            word.setLength(0)
            inWord = false
        }
        fun endCommand() {
            endWord()
            if (words.isNotEmpty()) commands += words
            words = ArrayList()
        }
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length -> {
                    if (line[i + 1] != '\n') word.append(line[i + 1])
                    inWord = true
                    i += 2
                    continue
                }
                c == '\'' -> {
                    val end = line.indexOf('\'', i + 1).let { if (it < 0) line.length else it }
                    word.append(line, i + 1, end)
                    inWord = true
                    i = end + 1
                    continue
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != '"') {
                        if (line[j] == '\\' && j + 1 < line.length) j++
                        j++
                    }
                    word.append(line, i + 1, minOf(j, line.length))
                    inWord = true
                    i = j + 1
                    continue
                }
                c == '$' && i + 1 < line.length && line[i + 1] == '(' -> {
                    // $(( arithmetic )) is no command; $( a command ) is one
                    if (i + 2 < line.length && line[i + 2] == '(') {
                        word.append("$((")
                        inWord = true
                        i += 3
                    } else {
                        endCommand()
                        i += 2
                    }
                    continue
                }
                c == '#' && !inWord -> {
                    // a comment, to the end of the line
                    val end = line.indexOf('\n', i).let { if (it < 0) line.length else it }
                    i = end
                    continue
                }
                c == ';' || c == '&' || c == '|' || c == '\n' || c == '(' || c == ')' || c == '`' -> {
                    // (a redirection's & stays in its word: 2>&1, &>out)
                    if (c == '&' && (word.endsWith(">") || word.endsWith("<"))) {
                        word.append(c)
                        inWord = true
                    } else if (c == '&' && i + 1 < line.length && line[i + 1] == '>' && !inWord) {
                        word.append(c)
                        inWord = true
                    } else {
                        endCommand()
                    }
                    i++
                    continue
                }
                c == ' ' || c == '\t' || c == '\r' -> endWord()
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        endCommand()
        return commands
    }

    private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*\\+?=.*")
    private val REDIRECTION = Regex("[0-9]*(>>?|<<?<?|>&|<&|&>>?|>\\|)[^ ]*")
    private val REDIRECTION_ALONE = Regex("[0-9]*(>>?|<<?<?|&>>?|>\\|)")
    private val DURATION = Regex("[0-9]+(\\.[0-9]+)?[smhd]?")
}
