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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sources, packages, and where a command runs (see PackageSource, ToolPackage, ToolchainRules). */
class ToolchainRulesTest {

    @Test
    fun oneUriForEverySource() {
        assertEquals(PackageSource.Remote("https://x.org/a.tar.gz"), PackageSource.parse(" https://x.org/a.tar.gz "))
        assertEquals("/sdcard/Download/a b.zip", (PackageSource.parse("file:///sdcard/Download/a%20b.zip") as PackageSource.LocalFile).file.path)
        assertEquals("/sdcard/a.zip", (PackageSource.parse("/sdcard/a.zip") as PackageSource.LocalFile).file.path)
        assertTrue(PackageSource.parse("content://com.android.providers.downloads.documents/document/12") is PackageSource.Content)
        assertFailsWith<IllegalArgumentException> { PackageSource.parse("ftp://x") }
        // what Termux has is said by "termux", not by a source
        assertFailsWith<IllegalArgumentException> { PackageSource.parse("termux:clang") }
        assertEquals("a.tar.gz", PackageSource.parse("https://x.org/dl/a.tar.gz?raw=1").fileName())
    }

    @Test
    fun aCatalogAndAManifest() {
        val problems = ArrayList<String>()
        val packages = ToolPackage.parseCatalog("""
            {
              // comments are fine
              "packages": [
                { "id": "clangd", "kind": "server", "source": "https://x.org/clangd.tgz", "strip": 1, "into": "opt/clangd",
                  "bin": { "clangd": "opt/clangd/bin/clangd" }, "env": { "A": "${'$'}{usr}/lib" } },
                { "id": "bad id", "source": "https://x" },
                { "id": "escape", "source": "/a", "into": "../x" },
                { "id": "t", "termux": "pkg install clang", "provides": ["clang"] },
                { "id": "old", "source": "termux:clang" },
              ]
            }
        """.trimIndent(), problems)
        assertEquals(listOf("clangd", "t"), packages.map { it.id })
        assertEquals(3, problems.size)
        // had in Termux, not installed into X Editor
        val t = packages[1]
        assertNull(t.source)
        assertEquals("pkg install clang", t.termux)
        assertEquals(listOf("clang"), t.commands())
        assertFalse(t.isInstallable(listOf("arm64-v8a")))
        val clangd = packages[0]
        assertEquals(ToolPackage.Kind.SERVER, clangd.kind)
        assertEquals("opt/clangd", clangd.into)
        val manifest = InstalledPackage(clangd, listOf("opt/clangd/bin/clangd"), mapOf("clangd" to "opt/clangd/bin/clangd"), 42)
        assertEquals(manifest, InstalledPackage.parse(manifest.toJson()))
        // only an id and a source: the layout is worked out when installed
        val bare = ToolPackage.parseCatalog("""{"packages":[{"id":"x","source":"/sdcard/x.zip"}]}""").single()
        assertNull(bare.strip)
        assertNull(bare.into)
        // what a package put in usr/bin: its links and its own files there
        assertEquals(listOf("clang", "clangd"), InstalledPackage(bare, listOf("bin/clang", "lib/x.so"), mapOf("clangd" to "opt/c/bin/clangd"), 1).programs())
        assertNull(InstalledPackage.parse("{"))
    }

    @Test
    fun theInterpreterOfAScript() {
        assertEquals(ToolchainRules.Shebang("node", emptyList()), ToolchainRules.shebang("#!/usr/bin/env node"))
        assertEquals(ToolchainRules.Shebang("python3", listOf("-u")), ToolchainRules.shebang("#!/usr/bin/env -S python3 -u"))
        assertEquals(ToolchainRules.Shebang("/bin/sh", listOf("-e")), ToolchainRules.shebang("#! /bin/sh -e"))
        assertNull(ToolchainRules.shebang("echo"))
    }

    @Test
    fun theProgramsOfACommand() {
        assertEquals(listOf("cmake", "cmake"), ToolchainRules.programs("cmake -S . -B build && cmake --build build -j4"))
        assertEquals(listOf("test", "cmake", "cmake"),
            ToolchainRules.programs("(test -f build/CMakeCache.txt || cmake -S . -B build) && cmake --build build"))
        assertEquals(listOf("clang", "/system/bin/linker64"), ToolchainRules.programs("CC=x clang \"a b.c\" -o /t/a && /system/bin/linker64 /t/a"))
        assertEquals(listOf("sh"), ToolchainRules.programs("sh ./gradlew build --console=plain"))
        assertEquals(listOf("make", "grep"), ToolchainRules.programs("make 2>&1 | grep 'error; x'"))
    }

    @Test
    fun ownToolsFirstTermuxOtherwise() {
        val local = setOf("clang", "cmake", "java")
        fun choose(command: String, termux: Boolean = true, mode: RunnerMode = RunnerMode.AUTO) =
            ToolchainRules.choose(command, mode, termux) { it in local }
        assertEquals(Runner.LOCAL, choose("clang -Wall a.c -o \${tmp}/a && \${exec}\${tmp}/a"))
        assertEquals(Runner.LOCAL, choose("cmake --build build"))
        assertEquals(Runner.TERMUX, choose("python main.py"))
        // not installed anywhere it can be known: the app's own says what is missing
        assertEquals(Runner.LOCAL, choose("python main.py", termux = false))
        // the wrapper of Gradle needs java
        assertEquals(Runner.LOCAL, choose("sh ./gradlew build"))
        assertEquals(Runner.LOCAL, choose("./gradlew build"))
        assertEquals(Runner.TERMUX, ToolchainRules.choose("./gradlew build", RunnerMode.AUTO, true) { false })
        // run by sh too: with no java of its own, in Termux
        assertEquals(Runner.TERMUX, ToolchainRules.choose("sh ./gradlew assembleDebug --console=plain", RunnerMode.AUTO, true) { it == "cmake" })
        assertEquals(Runner.LOCAL, ToolchainRules.choose("sh ./build.sh -x", RunnerMode.AUTO, true) { false })
        // a script of its own: Termux's tools
        assertEquals(Runner.TERMUX, choose("./build.sh"))
        assertEquals(Runner.LOCAL, choose("echo hi"))
        assertEquals(Runner.TERMUX, choose("clang a.c", mode = RunnerMode.TERMUX))
        assertEquals(Runner.LOCAL, choose("python a.py", mode = RunnerMode.LOCAL))
    }

    @Test
    fun functionsThatStartTheProgramsOfUsr() {
        val preamble = ToolchainRules.preamble(mapOf(
            "clang" to listOf("/system/bin/linker64", "/data/x/usr/bin/clang"),
            "clang++" to listOf("/system/bin/linker64", "/data/x/usr/bin/clang++"),
            "bad name" to listOf("x"),
        ))
        assertTrue("clang() { /system/bin/linker64 /data/x/usr/bin/clang \"\$@\"; }" in preamble)
        assertTrue("alias clang++='/system/bin/linker64 /data/x/usr/bin/clang++'" in preamble)
        assertTrue("bad name" !in preamble)
        assertEquals("'it''s'".replace("''", "'\\''"), ToolchainRules.quote("it's"))
    }

    @Test
    fun theSourceForTheDevice() {
        val pkg = ToolPackage.parseCatalog("""
            { "packages": [ {
              "id": "clangd", "version": "18.1.8", "provides": ["clangd"], "termux": "pkg install clang",
              "source": "https://x.org/clangd-any.tgz", "sha256": "AA",
              "abi": { "arm64-v8a": { "source": "https://x.org/clangd-arm64.tgz", "sha256": "BB" },
                       "x86_64": { "source": "https://x.org/clangd-x64.tgz" } }
            } ] }
        """.trimIndent()).single()
        assertEquals(ToolPackage.AbiSource("https://x.org/clangd-arm64.tgz", "bb"), pkg.sourceFor(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(ToolPackage.AbiSource("https://x.org/clangd-x64.tgz", null), pkg.sourceFor(listOf("x86_64", "x86")))
        // no source for its ABI: the one source
        assertEquals(ToolPackage.AbiSource("https://x.org/clangd-any.tgz", "aa"), pkg.sourceFor(listOf("armeabi-v7a")))
        // only by ABI: nothing for another
        val only = pkg.copy(source = null, sha256 = null)
        assertNull(only.sourceFor(listOf("armeabi-v7a")))
        assertTrue(only.isInstallable(listOf("arm64-v8a")))
        assertEquals(listOf("clangd"), pkg.commands())
    }

    @Test
    fun askingTermuxForCommands() {
        val script = TermuxProbe.script(listOf("clang", "java", "clang"))
        assertEquals("for c in clang java; do command -v \"\$c\" >/dev/null 2>&1 && echo \"\$c\"; done; true", script)
        assertEquals(setOf("clang"), TermuxProbe.parse("clang\nnoise\n", listOf("clang", "java")))
    }
}
