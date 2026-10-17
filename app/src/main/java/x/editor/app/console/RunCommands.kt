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

import android.content.Context
import x.editor.app.AppSettings
import java.io.File

/**
 * What "compile and run" runs, by the kind of file (its extension): a template with
 * `${file}` (its name; the command runs in its directory), `${name}` (without the
 * extension), `${path}` (absolute), `${dir}` (its directory) and `${tmp}` (Termux's tmp
 * directory, [TERMUX_TMP]). The user's own (set by a long press on the toolbar's run
 * button, see RunSettingsDialog) win over [DEFAULTS]. Compiled programs go to Termux's
 * tmp directory: the shared storage cannot hold executables.
 */
object RunCommands {

    private const val PREF_PREFIX = "run_command."

    /** Where compiled programs go (`${tmp}`): Termux's own tmp directory, where they may run. */
    const val TERMUX_TMP = "/data/data/com.termux/files/usr/tmp"

    val DEFAULTS: Map<String, String> = mapOf(
        "c" to "clang -Wall \"\${file}\" -o \${tmp}/\${name} && \${tmp}/\${name}",
        "cc" to "clang++ -Wall -std=c++17 \"\${file}\" -o \${tmp}/\${name} && \${tmp}/\${name}",
        "cpp" to "clang++ -Wall -std=c++17 \"\${file}\" -o \${tmp}/\${name} && \${tmp}/\${name}",
        "cxx" to "clang++ -Wall -std=c++17 \"\${file}\" -o \${tmp}/\${name} && \${tmp}/\${name}",
        "py" to "python \"\${file}\"",
        "js" to "node \"\${file}\"",
        "ts" to "npx tsx \"\${file}\"",
        "sh" to "bash \"\${file}\"",
        "lua" to "lua \"\${file}\"",
        "go" to "go run \"\${file}\"",
        "rs" to "rustc \"\${file}\" -o \${tmp}/\${name} && \${tmp}/\${name}",
        "java" to "java \"\${file}\"",
        "kt" to "kotlinc \"\${file}\" -include-runtime -d \${tmp}/\${name}.jar && java -jar \${tmp}/\${name}.jar",
        "rb" to "ruby \"\${file}\"",
        "php" to "php \"\${file}\"",
        "pl" to "perl \"\${file}\"",
    )

    /** The template for files of [extension]: the user's, or the default; null if neither. */
    fun template(context: Context, extension: String): String? {
        val key = extension.lowercase()
        val saved = AppSettings.preferences(context).getString(PREF_PREFIX + key, null)
        return saved?.takeIf { it.isNotBlank() } ?: DEFAULTS[key]
    }

    /** Whether files of [extension] have a template of the user's (not the default). */
    fun isCustom(context: Context, extension: String): Boolean =
        AppSettings.preferences(context).contains(PREF_PREFIX + extension.lowercase())

    /** Sets the template of [extension]; null or blank: back to the default. */
    fun setTemplate(context: Context, extension: String, template: String?) {
        val key = PREF_PREFIX + extension.lowercase()
        val editor = AppSettings.preferences(context).edit()
        if (template.isNullOrBlank()) editor.remove(key) else editor.putString(key, template.trim())
        editor.apply()
    }

    /** The command for [file], its template filled in; null if there is no template for it. */
    fun commandFor(context: Context, file: File): String? = template(context, file.extension)?.let { expand(it, file) }

    /** [template] with the variables of [file] filled in. */
    fun expand(template: String, file: File): String = template
        .replace("\${file}", file.name)
        .replace("\${name}", file.nameWithoutExtension)
        .replace("\${path}", file.absolutePath)
        .replace("\${dir}", file.absoluteFile.parent ?: ".")
        .replace("\${tmp}", TERMUX_TMP)
}
