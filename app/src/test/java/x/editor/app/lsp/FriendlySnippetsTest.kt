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

package x.editor.app.lsp

import x.editor.app.lsp.FriendlySnippets.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which files hold the user's snippets, and how they rank (FriendlySnippets). */
class FriendlySnippetsTest {

    @Test
    fun aLanguageFriendlySnippetsKnows() =
        assertEquals(listOf("c", "cdoc", "all", "global"), FriendlySnippets.userIdsOf("main.c"))

    @Test
    fun anExtensionNoLanguageIsKnownFor() =
        assertEquals(listOf("just", "all", "global"), FriendlySnippets.userIdsOf("build.JUST"))

    @Test
    fun aFileWithoutExtension() =
        assertEquals(listOf("justfile", "all", "global"), FriendlySnippets.userIdsOf("/project/Justfile"))

    @Test
    fun aFileKnownByItsName() =
        assertEquals(listOf("make", "makefile", "all", "global"), FriendlySnippets.userIdsOf("Makefile"))

    @Test
    fun theUsersSnippetsComeFirst() {
        val packaged = Snippet("for", "for loop", "for (;;) {\n\t$0\n}", null)
        val mine = Snippet("for", "my for", "for (int i = 0; i < $1; i++) {\n\t$0\n}", null, user = true)
        val matched = FriendlySnippets.match(listOf(packaged, mine), "    for", 10).map { it.snippet }
        assertEquals(listOf(mine, packaged), matched)
    }

    @Test
    fun theLineOfAnError() {
        val text = "{\n  \"a\": 1\n  \"b\": 2\n}"
        val offset = text.indexOf("\"b\"")
        assertEquals(3, FriendlySnippets.errorLine(text, "Unexpected JSON token at offset $offset: Expected end of the object or comma"))
        assertEquals(0, FriendlySnippets.errorLine(text, "something else"))
    }
}
