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
import androidx.annotation.StringRes
import x.editor.app.AppSettings
import x.editor.app.R
import x.editor.app.toolchain.Runner
import x.editor.app.toolchain.Toolchain
import java.io.File

/**
 * What "compile and run" runs: the command of the file's project if it has one for the
 * file (a CMake, Gradle or Android project, see ProjectRoot.Kind), else that of the file's
 * kind (its extension). Each a template with
 *
 *  - `${file}`: the file, relative to the project's root (the command runs there, see
 *    ProjectRoot); `${name}`: its name without the extension; `${path}`: absolute;
 *    `${dir}`: its directory; `${root}`: the project's root;
 *  - `${tmp}`: where compiled programs go: the app's usr/tmp with its own toolchain,
 *    Termux's tmp directory [TERMUX_TMP] in Termux (the shared storage cannot hold
 *    executables); `${exec}`: what starts such a program: the system's linker with the
 *    app's own toolchain (Toolchain.execPrefix), nothing in Termux;
 *  - `${prefix}`: the prefix of where it runs, the app's own (AppDirs.prefix) or Termux's
 *    [TERMUX_PREFIX];
 *  - `${gradle}`: what runs Gradle: the project's wrapper (`sh ./gradlew`: the shared
 *    storage executes nothing), else `gradle`.
 *
 * The user's own (set by a long press on the toolbar's run button, see RunSettingsDialog)
 * win over [DEFAULTS]. They are kept by key: the extension, or `project.<kind>`
 * ([keyFor]).
 *
 * Where it runs is chosen from the template (Toolchain.runnerFor): with the app's own
 * toolchain when its usr/bin has the compiler, in Termux otherwise.
 */
object RunCommands {

    private const val PREF_PREFIX = "run_command."

    /** The keys of the commands of projects: `project.cmake`, `project.gradle`, `project.android`. */
    const val PROJECT_PREFIX = "project."

    /** Termux's prefix (`$PREFIX` in Termux): `${prefix}` in Termux. */
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** Where compiled programs go (`${tmp}`): Termux's own tmp directory, where they may run. */
    const val TERMUX_TMP = "$TERMUX_PREFIX/tmp"

    private const val CLANG_RUN = "-o \${tmp}/\${name} && \${exec}\${tmp}/\${name}"

    // CMake: configured once into build/ (with compile_commands.json for clangd), then built
    private const val CMAKE_BUILD = "(test -f build/CMakeCache.txt || cmake -S . -B build -DCMAKE_BUILD_TYPE=Debug" +
        " -DCMAKE_EXPORT_COMPILE_COMMANDS=ON) && cmake --build build -j4"

    val DEFAULTS: Map<String, String> = mapOf(
        "c" to "clang -Wall \"\${file}\" $CLANG_RUN",
        "cc" to "clang++ -Wall -std=c++17 \"\${file}\" $CLANG_RUN",
        "cpp" to "clang++ -Wall -std=c++17 \"\${file}\" $CLANG_RUN",
        "cxx" to "clang++ -Wall -std=c++17 \"\${file}\" $CLANG_RUN",
        // ARM assembly (AArch64 on a 64-bit device), linked with libc: its entry is `main`;
        // `.S` is run through the preprocessor first (by clang, from its name), `.asm` too
        "s" to "clang \"\${file}\" $CLANG_RUN",
        "asm" to "clang -x assembler-with-cpp \"\${file}\" $CLANG_RUN",
        "py" to "python \"\${file}\"",
        "js" to "node \"\${file}\"",
        "ts" to "npx tsx \"\${file}\"",
        "sh" to "bash \"\${file}\"",
        "lua" to "lua \"\${file}\"",
        "go" to "go run \"\${file}\"",
        "rs" to "rustc \"\${file}\" $CLANG_RUN",
        "java" to "java \"\${file}\"",
        "kt" to "kotlinc \"\${file}\" -include-runtime -d \${tmp}/\${name}.jar && java -jar \${tmp}/\${name}.jar",
        "rb" to "ruby \"\${file}\"",
        "php" to "php \"\${file}\"",
        "pl" to "perl \"\${file}\"",
        // projects: built in their root
        PROJECT_PREFIX + ProjectRoot.Kind.CMAKE.key to CMAKE_BUILD,
        PROJECT_PREFIX + ProjectRoot.Kind.GRADLE.key to "\${gradle} assemble --console=plain",
        PROJECT_PREFIX + ProjectRoot.Kind.ANDROID.key to "\${gradle} assembleDebug --console=plain",
    )

    /**
     * The key of the command for [file] in [project]: `project.<kind>` if its project has a
     * command for it (ProjectRoot.Kind.covers), else its extension.
     */
    fun keyFor(file: File, project: ProjectRoot.Project?): String =
        project?.kind?.takeIf { it.covers(file) }?.let { PROJECT_PREFIX + it.key } ?: file.extension.lowercase()

    /** The kind of project of [key] (`project.<kind>`); null for an extension. */
    fun projectKindOf(key: String): ProjectRoot.Kind? =
        if (key.startsWith(PROJECT_PREFIX)) ProjectRoot.Kind.entries.firstOrNull { PROJECT_PREFIX + it.key == key } else null

    /** The template of [key] (an extension, or `project.<kind>`): the user's, or the default; null if neither. */
    fun template(context: Context, key: String): String? {
        val k = key.lowercase()
        val saved = AppSettings.preferences(context).getString(PREF_PREFIX + k, null)
        return saved?.takeIf { it.isNotBlank() } ?: DEFAULTS[k]
    }

    /** Whether [key] has a template of the user's (not the default). */
    fun isCustom(context: Context, key: String): Boolean =
        AppSettings.preferences(context).contains(PREF_PREFIX + key.lowercase())

    /** Sets the template of [key]; null or blank: back to the default. */
    fun setTemplate(context: Context, key: String, template: String?) {
        val k = PREF_PREFIX + key.lowercase()
        val editor = AppSettings.preferences(context).edit()
        if (template.isNullOrBlank()) editor.remove(k) else editor.putString(k, template.trim())
        editor.apply()
    }

    /**
     * The command for [file] in [project] (where it runs; the file's own directory if null),
     * its template ([keyFor]) filled in for where it runs (see the class); null if there is
     * no template for it.
     */
    fun commandFor(context: Context, file: File, project: ProjectRoot.Project? = null): String? =
        template(context, keyFor(file, project))?.let { expandFor(context, it, file, project) }

    /** Where [template] runs, for [project]: by what it starts, Gradle's wrapper filled in first. */
    fun runnerOf(context: Context, template: String, project: ProjectRoot.Project?): Runner =
        Toolchain.runnerFor(context, template.replace("\${gradle}", gradleOf(project)))

    /** [template] filled in for [file] in [project], with the values of where it runs ([runnerOf]). */
    fun expandFor(context: Context, template: String, file: File, project: ProjectRoot.Project?): String =
        expand(template, valuesFor(context, template, file, project))

    /** What each variable comes to for [file] in [project], where [template] runs ([runnerOf]). */
    fun valuesFor(context: Context, template: String, file: File, project: ProjectRoot.Project?): Map<Variable, String> {
        val runner = runnerOf(context, template, project)
        return values(file, project, Toolchain.tmpDirectory(context, runner), Toolchain.execPrefix(context, runner),
            Toolchain.prefixDirectory(context, runner))
    }

    /** The working directory of the commands of [file] in [project]: the project's root, else the file's directory. */
    fun workDirOf(file: File, project: ProjectRoot.Project?): File =
        project?.root?.absoluteFile ?: file.absoluteFile.parentFile ?: File("/")

    /** What runs Gradle in [project]: its wrapper, with sh (the shared storage executes nothing), else `gradle`. */
    fun gradleOf(project: ProjectRoot.Project?): String = if (project?.gradleWrapper == true) "sh ./gradlew" else "gradle"

    /**
     * The variables of the templates, in the order the settings show them, each with what
     * it stands for ([description], shown in RunSettingsDialog).
     */
    enum class Variable(val key: String, @StringRes val description: Int) {
        FILE("file", R.string.run_var_file),
        NAME("name", R.string.run_var_name),
        DIR("dir", R.string.run_var_dir),
        PATH("path", R.string.run_var_path),
        ROOT("root", R.string.run_var_root),
        TMP("tmp", R.string.run_var_tmp),
        EXEC("exec", R.string.run_var_exec),
        PREFIX("prefix", R.string.run_var_prefix),
        GRADLE("gradle", R.string.run_var_gradle),
        ;

        /** As written in a template: `${file}`. */
        val token: String get() = "\${$key}"
    }

    /**
     * What the variables come to for [file] in [project] (the file's own directory, of no
     * kind, if null); [tmp], [exec] and [prefix] those of where it runs.
     */
    fun values(
        file: File,
        project: ProjectRoot.Project? = null,
        tmp: String = TERMUX_TMP,
        exec: String = "",
        prefix: String = TERMUX_PREFIX,
    ): Map<Variable, String> {
        val path = file.absoluteFile
        val top = workDirOf(file, project)
        return linkedMapOf(
            Variable.FILE to path.relativeToOrSelf(top).path,
            Variable.NAME to path.nameWithoutExtension,
            Variable.DIR to (path.parent ?: "."),
            Variable.PATH to path.path,
            Variable.ROOT to top.path,
            Variable.TMP to tmp,
            Variable.EXEC to exec,
            Variable.PREFIX to prefix,
            Variable.GRADLE to gradleOf(project),
        )
    }

    /**
     * [template] with the variables of [file] filled in, for [project] (the file's own
     * directory, of no kind, if null); [tmp], [exec] and [prefix] those of where it runs.
     */
    fun expand(
        template: String,
        file: File,
        project: ProjectRoot.Project? = null,
        tmp: String = TERMUX_TMP,
        exec: String = "",
        prefix: String = TERMUX_PREFIX,
    ): String = expand(template, values(file, project, tmp, exec, prefix))

    /** [template] with [values] filled in (`${gradle}` first: what it comes to has no variables). */
    fun expand(template: String, values: Map<Variable, String>): String {
        var out = template.replace(Variable.GRADLE.token, values[Variable.GRADLE].orEmpty())
        for ((variable, value) in values) if (variable != Variable.GRADLE) out = out.replace(variable.token, value)
        return out
    }
}
