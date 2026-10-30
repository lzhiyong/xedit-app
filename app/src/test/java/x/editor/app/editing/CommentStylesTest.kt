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

package x.editor.app.editing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import x.github.module.editor.input.CommentToggle

/** The comment marks of a file's language (see CommentStyles). */
class CommentStylesTest {

    @Test
    fun byExtensionAndByName() {
        assertEquals(CommentToggle.Style("//", "/*", "*/"), CommentStyles.forFile("src/main.cpp"))
        assertEquals("#", CommentStyles.forFile("a.PY")?.line)
        assertEquals(CommentToggle.Style(null, "<!--", "-->"), CommentStyles.forFile("index.html"))
        assertEquals("--", CommentStyles.forFile("init.lua")?.line)
        assertEquals("#", CommentStyles.forFile("project/Makefile")?.line)
        // a document of the Storage Access Framework: `primary:dir/a.kt`
        assertEquals("//", CommentStyles.forFile("primary:dir/a.kt")?.line)
        assertEquals("//", CommentStyles.forFile("primary:a.kt")?.line)
        assertNull(CommentStyles.forFile("data.json"))
        assertNull(CommentStyles.forFile("notes.txt"))
    }
}
