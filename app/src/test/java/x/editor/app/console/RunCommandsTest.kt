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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The commands of "compile and run": the variables of a template, the defaults. */
class RunCommandsTest {

    private val file = File("/storage/emulated/0/nvim/rbtree.c")
    private val tmp = RunCommands.TERMUX_TMP

    @Test
    fun theDefaultForC() {
        assertEquals("clang -Wall \"rbtree.c\" -o $tmp/rbtree && $tmp/rbtree", RunCommands.expand(RunCommands.DEFAULTS.getValue("c"), file))
    }

    @Test
    fun everyVariable() {
        assertEquals(
            "/storage/emulated/0/nvim|/storage/emulated/0/nvim/rbtree.c|rbtree|rbtree.c|$tmp",
            RunCommands.expand("\${dir}|\${path}|\${name}|\${file}|\${tmp}", file)
        )
        assertEquals("make -j4", RunCommands.expand("make -j4", file))
    }

    @Test
    fun inTheProjectsRoot() {
        // the command runs in the root: the file is relative to it
        val project = ProjectRoot.Project(File("/storage/emulated/0/proj"))
        val source = File("/storage/emulated/0/proj/src/main.c")
        assertEquals("clang -Wall \"src/main.c\" -o $tmp/main && $tmp/main", RunCommands.expand(RunCommands.DEFAULTS.getValue("c"), source, project))
        assertEquals("/storage/emulated/0/proj|/storage/emulated/0/proj/src|main", RunCommands.expand("\${root}|\${dir}|\${name}", source, project))
        // no project given: the file's own directory
        assertEquals("/storage/emulated/0/nvim", RunCommands.expand("\${root}", file))
        assertEquals(RunCommands.TERMUX_PREFIX + "/include", RunCommands.expand("\${prefix}/include", file))
    }

    @Test
    fun armAssembly() {
        assertEquals("s", RunCommands.keyFor(File("/p/hello.S"), null))
        assertEquals("clang \"hello.S\" -o $tmp/hello && $tmp/hello", RunCommands.expand(RunCommands.DEFAULTS.getValue("s"), File("/p/hello.S")))
        assertTrue(RunCommands.expand(RunCommands.DEFAULTS.getValue("asm"), File("/p/a.asm")).startsWith("clang -x assembler-with-cpp \"a.asm\""))
    }

    @Test
    fun theCommandsOfProjects() {
        val cmake = ProjectRoot.Project(File("/p"), ProjectRoot.Kind.CMAKE)
        // a source of the project: built with it; another file: its own command
        assertEquals("project.cmake", RunCommands.keyFor(File("/p/src/main.cpp"), cmake))
        assertEquals("py", RunCommands.keyFor(File("/p/tools/gen.py"), cmake))
        assertTrue(RunCommands.DEFAULTS.getValue("project.cmake").endsWith("cmake --build build -j4"))
        assertEquals(ProjectRoot.Kind.CMAKE, RunCommands.projectKindOf("project.cmake"))
        assertEquals(null, RunCommands.projectKindOf("c"))

        val android = ProjectRoot.Project(File("/a"), ProjectRoot.Kind.ANDROID, gradleWrapper = true)
        val activity = File("/a/app/src/main/java/x/Main.kt")
        assertEquals("project.android", RunCommands.keyFor(activity, android))
        assertEquals("sh ./gradlew assembleDebug --console=plain", RunCommands.expand(RunCommands.DEFAULTS.getValue("project.android"), activity, android))
        val gradle = ProjectRoot.Project(File("/g"), ProjectRoot.Kind.GRADLE)
        assertEquals("gradle assemble --console=plain", RunCommands.expand(RunCommands.DEFAULTS.getValue("project.gradle"), File("/g/src/A.java"), gradle))
    }

    @Test
    fun theDefaults() {
        // a name with a space stays one argument; nothing left unfilled; programs in Termux's tmp
        assertEquals("python \"main.py\"", RunCommands.expand(RunCommands.DEFAULTS.getValue("py"), File("/sdcard/a b/main.py")))
        assertTrue(RunCommands.DEFAULTS.values.none { RunCommands.expand(it, file).contains("\${") })
        assertTrue(RunCommands.DEFAULTS.values.none { it.contains("~/") })
    }
}
