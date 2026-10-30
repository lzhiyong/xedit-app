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

import android.content.Context
import android.os.Build
import android.os.Process
import x.editor.app.AppDirs
import x.editor.app.AppSettings
import x.editor.app.lsp.Termux
import java.io.File
import java.io.IOException

/**
 * The app's own prefix (AppDirs.prefix), laid out as Termux's `usr`:
 * `/data/data/<package>/files/usr` with
 *
 *  - `bin/`: what runs (the programs of the packages, or links to them);
 *  - `lib/`: their libraries (`LD_LIBRARY_PATH`);
 *  - `libexec/`, `include/`, `etc/`, `share/`, `opt/` (a package that keeps to a directory
 *    of its own: `opt/<id>`), `tmp/` (`TMPDIR`, and where compiled programs go);
 *  - `var/lib/xedit/packages/`: what each package installed (ToolchainInstaller).
 */
object Usr {
    const val NAME = AppDirs.PREFIX

    val DIRECTORIES = listOf("bin", "lib", "libexec", "include", "etc", "share", "opt", "tmp", "var/lib/xedit/packages")

    fun root(context: Context): File = AppDirs.prefix(context)

    /** The root, with its directories made. */
    fun ensure(context: Context): File = root(context).also { root -> DIRECTORIES.forEach { File(root, it).mkdirs() } }

    fun bin(context: Context) = File(root(context), "bin")
    fun lib(context: Context) = File(root(context), "lib")
    fun tmp(context: Context) = File(root(context), "tmp")
    fun packages(context: Context) = File(root(context), "var/lib/xedit/packages")
}

/**
 * The compilers, language servers and tools the app runs: its own (in [Usr], installed by
 * ToolchainInstaller) first, Termux's otherwise.
 *
 *  - [locate] finds a program in usr/bin.
 *  - [launch] is how one is started: an app that targets Android 10 or later may not
 *    execute files of its data directory directly, so a binary is started through the
 *    system's dynamic linker (`/system/bin/linker64 usr/bin/clangd ...`), which may load
 *    it, and a script through its interpreter (`#!/usr/bin/env node`: usr/bin/node, so
 *    started in turn). The binary has to be built for Android (bionic) and dynamically
 *    linked, for this prefix.
 *  - [environment] is what they run with: PREFIX, PATH (usr/bin first), LD_LIBRARY_PATH,
 *    HOME, TMPDIR, and what the packages add.
 *  - What a program starts in turn (clang its linker, cmake the compilers) is started by
 *    it, not by the app, and is refused the same way, unless an exec wrapper is installed:
 *    termux-exec's library (`usr/lib/libtermux-exec*.so`), preloaded with its system linker
 *    mode on, which starts such files through the linker too. [hasExecWrapper].
 *  - [runnerFor] says where a command runs: see ToolchainRules.choose, with the user's
 *    choice ([mode]).
 */
object Toolchain {

    /**
     * Whether the app's own toolchain is used. On: a language server of type auto and a
     * command are the app's own when its usr/bin has what they run, Termux's otherwise, and
     * the toolchain sheet installs into usr. Off: everything is Termux's ([locate] finds
     * nothing, [mode] is Termux), the code of the app's own kept as it is.
     */
    const val LOCAL_ENABLED = true

    private const val PREF_MODE = "toolchain.runner"

    /**
     * The program [name] of usr/bin, if it is there (a link: what it points to); null if
     * the app's own toolchain is off ([LOCAL_ENABLED]).
     */
    fun locate(context: Context, name: String): File? {
        if (!LOCAL_ENABLED || name.isEmpty() || '/' in name) return null
        val file = File(Usr.bin(context), name)
        return try {
            file.canonicalFile.takeIf { it.isFile }
        } catch (e: IOException) {
            null
        }
    }

    /** Whether usr/bin has [name]. */
    fun has(context: Context, name: String): Boolean = locate(context, name) != null

    /** The exec wrapper of usr/lib (termux-exec's), if one is installed. */
    fun execWrapper(context: Context): File? =
        Usr.lib(context).listFiles()?.filter { it.name.startsWith("libtermux-exec") && it.name.endsWith(".so") }
            ?.minByOrNull { if (it.name.contains("ld-preload")) 0 else 1 }

    fun hasExecWrapper(context: Context): Boolean = execWrapper(context) != null

    /** The system's dynamic linker, which starts the binaries of the app's data. */
    fun linker(): String =
        if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker"

    /**
     * The words that start [program] with [arguments]: through the linker (a binary of the
     * app's data), its interpreter (a script), or itself. Throws IOException for a script
     * whose interpreter is neither in usr/bin nor on the system.
     */
    @Throws(IOException::class)
    fun launch(context: Context, program: File, arguments: List<String> = emptyList(), depth: Int = 0): List<String> {
        if (depth > 4) throw IOException("${program.name}: interpreters of interpreters, too deep")
        val head = ByteArray(256)
        val read = try {
            program.inputStream().use { it.read(head) }
        } catch (e: IOException) {
            throw IOException("cannot read ${program.path}: ${e.message}", e)
        }
        val format = Archives.detect(head.copyOf(maxOf(read, 0)))
        val ownData = isOwnData(context, program)
        if (format == Archives.Format.SCRIPT) {
            val line = String(head, 0, maxOf(read, 0), Charsets.UTF_8).lineSequence().first()
            val shebang = ToolchainRules.shebang(line) ?: throw IOException("${program.name}: no interpreter in its #! line")
            val name = shebang.program.substringAfterLast('/')
            val interpreter = locate(context, name)
                ?: File(shebang.program).takeIf { it.isAbsolute && it.exists() && !isOwnData(context, it) }
                ?: if (name == "sh" || name == "bash") File("/system/bin/sh") else null
                ?: throw IOException("${program.name} is run with $name, which is not installed (in usr/bin)")
            return launch(context, interpreter, shebang.arguments + program.path + arguments, depth + 1)
        }
        return if (ownData && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(linker(), program.path) + arguments
        } else {
            listOf(program.path) + arguments
        }
    }

    private fun isOwnData(context: Context, file: File): Boolean = try {
        file.canonicalPath.startsWith(File(context.applicationInfo.dataDir).canonicalPath + "/") ||
            file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/")
    } catch (e: IOException) {
        false
    }

    /**
     * What the toolchain's programs run with: the system's environment, with PREFIX, PATH
     * (usr/bin first), LD_LIBRARY_PATH, HOME, TMPDIR, LANG, the exec wrapper if there is one,
     * and what the installed packages add (`${usr}`, `${env:NAME}` in their values).
     */
    fun environment(context: Context): Map<String, String> {
        val usr = Usr.ensure(context)
        val env = LinkedHashMap(System.getenv())
        val path = env["PATH"] ?: "/system/bin:/system/xbin"
        env["PREFIX"] = usr.path
        env["PATH"] = "${usr.path}/bin:$path"
        env["LD_LIBRARY_PATH"] = listOfNotNull("${usr.path}/lib", env["LD_LIBRARY_PATH"]).joinToString(":")
        env["HOME"] = AppDirs.home(context).path
        env["TMPDIR"] = Usr.tmp(context).path
        env["LANG"] = env["LANG"] ?: "en_US.UTF-8"
        env["XEDIT_USR"] = usr.path
        execWrapper(context)?.let { wrapper ->
            env["LD_PRELOAD"] = wrapper.path
            // termux-exec: what is the app's data (its files are started through the linker), always
            env["TERMUX__PREFIX"] = usr.path
            env["TERMUX__ROOTFS"] = context.filesDir.path
            env["TERMUX_APP__DATA_DIR"] = context.applicationInfo.dataDir
            env["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "force"
        }
        for (installed in ToolchainInstaller.installed(context)) {
            for ((key, value) in installed.pkg.env) env[key] = expand(value, usr, env)
        }
        return env
    }

    // `${usr}` and `${env:NAME}` in a value of a package's env
    internal fun expand(value: String, usr: File, env: Map<String, String>): String =
        Regex("""\$\{(usr|prefix|env:([A-Za-z_][A-Za-z0-9_]*))\}""").replace(value) { match ->
            when {
                match.groupValues[2].isNotEmpty() -> env[match.groupValues[2]].orEmpty()
                else -> usr.path
            }
        }

    /**
     * The functions that make the programs of usr/bin found by a shell started with the
     * toolchain (ToolchainRules.preamble); none with an exec wrapper, with which the shell
     * starts them itself.
     */
    fun shellPreamble(context: Context): String {
        if (hasExecWrapper(context)) return ""
        val launchers = LinkedHashMap<String, List<String>>()
        for (file in Usr.bin(context).listFiles().orEmpty()) {
            val target = try { file.canonicalFile } catch (e: IOException) { continue }
            if (!target.isFile) continue
            try {
                launchers[file.name] = launch(context, target)
            } catch (e: IOException) {
                // a script whose interpreter is missing: the shell says so when it is run
            }
        }
        return ToolchainRules.preamble(launchers)
    }

    /** What the user chose: where commands run (Termux if the app's own is off, [LOCAL_ENABLED]). */
    fun mode(context: Context): RunnerMode =
        if (!LOCAL_ENABLED) RunnerMode.TERMUX
        else RunnerMode.of(AppSettings.preferences(context).getString(PREF_MODE, null))

    fun setMode(context: Context, mode: RunnerMode) {
        AppSettings.preferences(context).edit().putString(PREF_MODE, mode.key).apply()
    }

    /** Where [command] runs (see ToolchainRules.choose). */
    fun runnerFor(context: Context, command: String): Runner =
        ToolchainRules.choose(command, mode(context), Termux.isInstalled(context)) { has(context, it) }

    /**
     * `${exec}` of a run command: what starts a program the command compiled (in usr/tmp):
     * the linker, with the app's own toolchain and no exec wrapper; nothing in Termux.
     */
    fun execPrefix(context: Context, runner: Runner): String =
        if (runner == Runner.LOCAL && !hasExecWrapper(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linker() + " " else ""

    /** `${tmp}` of a run command: where compiled programs go. */
    fun tmpDirectory(context: Context, runner: Runner): String =
        if (runner == Runner.LOCAL) Usr.tmp(context).apply { mkdirs() }.path else x.editor.app.console.RunCommands.TERMUX_TMP

    /** `${prefix}` of a run command: the prefix of where it runs, the app's own (AppDirs.prefix) or Termux's. */
    fun prefixDirectory(context: Context, runner: Runner): String =
        if (runner == Runner.LOCAL) AppDirs.prefix(context).path else x.editor.app.console.RunCommands.TERMUX_PREFIX
}
