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
    fun theDefaults() {
        // a name with a space stays one argument; nothing left unfilled; programs in Termux's tmp
        assertEquals("python \"main.py\"", RunCommands.expand(RunCommands.DEFAULTS.getValue("py"), File("/sdcard/a b/main.py")))
        assertTrue(RunCommands.DEFAULTS.values.none { RunCommands.expand(it, file).contains("\${") })
        assertTrue(RunCommands.DEFAULTS.values.none { it.contains("~/") })
    }
}
