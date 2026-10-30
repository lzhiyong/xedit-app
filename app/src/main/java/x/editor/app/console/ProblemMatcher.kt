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

package x.editor.app.console

import java.io.File

/**
 * Something wrong at a place of a file: a diagnostic of a language server, or an error a
 * build printed. [line] and [column] are 1-based; [severity] as EditorView's (1 error,
 * 2 warning, 3 information, 4 hint); [source]: who says so (`clangd`, `build`).
 */
data class Problem(
    val file: File,
    val line: Int,
    val column: Int,
    val severity: Int,
    val message: String,
    val source: String,
)

/**
 * The problems in what a build printed, as VS Code's problem matchers find them:
 *
 *  - gcc, clang, and much else: `file:line:col: error: message` (the column may be missing;
 *    `fatal error`, `warning`, `note` too);
 *  - Kotlin (an Android build): `e: file:///path/A.kt:12:5 message` (`w:` a warning), or
 *    as older compilers print it, `e: /path/A.kt: (12, 5): message`;
 *  - Python: a traceback's last `File "x.py", line N` with the line that ends it
 *    (`NameError: ...`), a SyntaxError the same way.
 *
 * Relative paths are taken from [workDir]. Plain computation.
 */
object ProblemMatcher {

    private val COMPILER = Regex("""^(.+?):(\d+):(?:(\d+):)?\s*(fatal error|error|warning|note|info)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val KOTLIN = Regex("""^([ew]): (?:file://)?(.+?):(\d+):(\d+) (.*)$""")
    private val KOTLIN_OLD = Regex("""^([ew]): (?:file://)?(.+?): \((\d+), (\d+)\): (.*)$""")
    private val PYTHON_FRAME = Regex("""^\s*File "(.+)", line (\d+)""")
    // the line that ends a traceback: `SomethingError: what`, at the start of a line
    private val PYTHON_ERROR = Regex("""^([A-Za-z_][\w.]*(Error|Exception|Warning|Interrupt|Exit))(:\s*(.*))?$""")

    fun parse(output: String, workDir: File?): List<Problem> {
        val problems = ArrayList<Problem>()
        var frame: Pair<String, Int>? = null
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val kotlin = KOTLIN.matchEntire(line) ?: KOTLIN_OLD.matchEntire(line)
            if (kotlin != null) {
                val (kind, path, row, column, message) = kotlin.destructured
                problems += Problem(resolve(path, workDir), row.toInt(), column.toInt(), if (kind == "e") 1 else 2, message, "kotlin")
                frame = null
                continue
            }
            COMPILER.matchEntire(line)?.let { m ->
                val (path, row, column, kind, message) = m.destructured
                problems += Problem(
                    file = resolve(path, workDir),
                    line = row.toInt(),
                    column = column.toIntOrNull() ?: 1,
                    severity = when (kind.lowercase()) {
                        "warning" -> 2
                        "note", "info" -> 3
                        else -> 1
                    },
                    message = message,
                    source = "build",
                )
                frame = null
                return@let
            } ?: run {
                PYTHON_FRAME.find(line)?.let { m ->
                    frame = m.groupValues[1] to m.groupValues[2].toInt()
                    return@run
                }
                val last = frame
                if (last != null && !line.startsWith(" ") && PYTHON_ERROR.matches(line)) {
                    problems += Problem(resolve(last.first, workDir), last.second, 1, 1, line, "python")
                    frame = null
                }
            }
        }
        return problems
    }

    private fun resolve(path: String, workDir: File?): File {
        val file = File(path)
        return if (file.isAbsolute || workDir == null) file else File(workDir, path)
    }
}
