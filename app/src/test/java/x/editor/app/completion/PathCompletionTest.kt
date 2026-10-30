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

package x.editor.app.completion

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a path is being typed, and what is listed for it (see PathCompletion). */
class PathCompletionTest {

    @Test
    fun pathsThatAreCompleted() {
        assertEquals(PathCompletion.Query("./src/", "ma", false), PathCompletion.query("x = load(./src/ma"))
        assertEquals(PathCompletion.Query("../", "", false), PathCompletion.query("cd ../"))
        assertEquals(PathCompletion.Query("inc/", "ut", true), PathCompletion.query("#include \"inc/ut"))
        assertEquals(PathCompletion.Query("/sdcard/", "Do", true), PathCompletion.query("open('/sdcard/Do"))
        assertEquals(PathCompletion.Query("/sdcard/", "Do", false), PathCompletion.query("ls /sdcard/Do"))
        // the files next to the document, where a file is meant
        assertEquals(PathCompletion.Query("", "ma", true), PathCompletion.query("#include \"ma"))
        assertEquals(PathCompletion.Query("", "", true), PathCompletion.query("<img src=\""))
    }

    @Test
    fun whatOnlyLooksLikeAPath() {
        assertNull(PathCompletion.query("x = a / b"))
        assertNull(PathCompletion.query("x = a /b"))
        assertNull(PathCompletion.query("// comment/"))
        assertNull(PathCompletion.query("url = \"https://example.com/"))
        assertNull(PathCompletion.query("int x = y"))
        // a quote that closes a string does not open one
        assertNull(PathCompletion.query("s = \"a\" + b/c"))
        // any string: not a file
        assertNull(PathCompletion.query("printf(\"ma"))
    }

    @Test
    fun listsWhatIsInTheDirectory() {
        val dir = Files.createTempDirectory("paths").toFile()
        try {
            File(dir, "main.c").writeText("")
            File(dir, "Makefile").writeText("")
            File(dir, "module").mkdir()
            File(dir, ".hidden").writeText("")
            File(dir, "other.txt").writeText("")
            assertEquals(
                listOf(PathCompletion.Entry("module", true), PathCompletion.Entry("main.c", false), PathCompletion.Entry("Makefile", false)),
                PathCompletion.list(dir, "m", 10)
            )
            assertEquals(listOf(PathCompletion.Entry(".hidden", false)), PathCompletion.list(dir, ".", 10))
            assertEquals(4, PathCompletion.list(dir, "", 10).size)
            val query = PathCompletion.query("load(\"./sub/../")!!
            assertEquals(dir, PathCompletion.directoryOf(query, dir))
            assertNull(PathCompletion.directoryOf(PathCompletion.Query("src/", "", true), null))
            assertEquals(dir, PathCompletion.directoryOf(PathCompletion.Query("", "ma", true), dir))
            // the name typed whole is not offered again; a directory is (to go into it)
            assertEquals(listOf(PathCompletion.Entry("module", true)), PathCompletion.filter(
                listOf(PathCompletion.Entry("main.c", false), PathCompletion.Entry("module", true)), "main.c", 10) +
                PathCompletion.filter(listOf(PathCompletion.Entry("module", true)), "module", 10))
        } finally {
            dir.deleteRecursively()
        }
    }
}
