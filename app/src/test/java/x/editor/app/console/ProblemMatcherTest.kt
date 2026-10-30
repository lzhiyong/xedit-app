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

/** The problems found in what a build printed: gcc and clang, Python tracebacks. */
class ProblemMatcherTest {

    private val dir = File("/storage/emulated/0/nvim")

    @Test
    fun compilerMessages() {
        val output = """
            rbtree.c: In function 'rb_insert':
            rbtree.c:42:13: error: 'node' undeclared (first use in this function)
               42 |     node->left = x;
            rbtree.c:57:5: warning: unused variable 'y' [-Wunused-variable]
            /usr/include/stdio.h:12: note: declared here
            ld.lld: error: undefined symbol: foo
        """.trimIndent()
        val problems = ProblemMatcher.parse(output, dir)
        assertEquals(3, problems.size, problems.toString())
        assertEquals(Problem(File(dir, "rbtree.c"), 42, 13, 1, "'node' undeclared (first use in this function)", "build"), problems[0])
        assertEquals(2, problems[1].severity)
        // no column, an absolute path; a linker error has no place: not a problem
        assertEquals(File("/usr/include/stdio.h"), problems[2].file)
        assertEquals(1, problems[2].column)
        assertEquals(3, problems[2].severity)
    }

    @Test
    fun aPythonTraceback() {
        val output = """
            Traceback (most recent call last):
              File "/storage/emulated/0/nvim/main.py", line 10, in <module>
                main()
              File "/storage/emulated/0/nvim/main.py", line 6, in main
                print(x)
            NameError: name 'x' is not defined
        """.trimIndent()
        val problem = ProblemMatcher.parse(output, dir).single()
        assertEquals(6, problem.line)
        assertEquals("NameError: name 'x' is not defined", problem.message)
    }

    @Test
    fun aPythonSyntaxError() {
        val output = "  File \"t.py\", line 3\n    def f(:\n          ^\nSyntaxError: invalid syntax"
        val problem = ProblemMatcher.parse(output, dir).single()
        assertEquals(File(dir, "t.py"), problem.file)
        assertEquals(3, problem.line)
    }

    @Test
    fun plainOutputHasNone() {
        assertTrue(ProblemMatcher.parse("hello\nworld: 3 items\n", dir).isEmpty())
    }
}
