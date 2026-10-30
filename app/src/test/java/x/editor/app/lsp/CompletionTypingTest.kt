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

import x.editor.app.lsp.CompletionTyping.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/** What typing does to completion, keyboards that compose words included (CompletionTyping). */
class CompletionTypingTest {

    private val triggers = setOf(".", "->", "::")
    private val later = Action.Request(null, immediate = false)

    private fun decide(line: String, inserted: String, deleted: Int = 0, atEnd: Boolean = true, showing: Boolean = false, complete: Boolean = false) =
        CompletionTyping.decide(line, inserted, deleted, atEnd, showing, complete, triggers)

    @Test
    fun aCharTyped() = assertEquals(later, decide("  pri", "i"))

    @Test
    fun aWordComposedByTheKeyboard() {
        // every key replaces what was composed so far: "pr" by "pri"
        assertEquals(later, decide("  pri", "pri", deleted = 2))
        assertEquals(Action.Refilter, decide("  prin", "prin", deleted = 3, showing = true, complete = true))
        assertEquals(later, decide("  prin", "prin", deleted = 3, showing = true, complete = false))
    }

    @Test
    fun triggerCharacters() {
        assertEquals(Action.Request(".", immediate = true), decide("s.", "."))
        assertEquals(Action.Request("->", immediate = true), decide("p->", ">"))
        assertEquals(Action.Request("::", immediate = true), decide("std::", ":"))
        assertEquals(Action.Dismiss, decide("a >", ">"))
    }

    @Test
    fun whatEndsAWord() {
        assertEquals(Action.Dismiss, decide("int ", " "))
        assertEquals(Action.Dismiss, decide("", "\n"))
        assertEquals(Action.Dismiss, decide("x".repeat(100), "x".repeat(100)))
        assertEquals(Action.Dismiss, decide("  pri", "#include <stdio.h>", atEnd = false))
    }

    @Test
    fun deleting() {
        assertEquals(Action.None, decide("  pr", "", deleted = 1))
        assertEquals(Action.Refilter, decide("  pr", "", deleted = 1, showing = true, complete = true))
        assertEquals(Action.Dismiss, decide("  ", "", deleted = 1, showing = true, complete = true))
    }

    @Test
    fun wordsOfAnyLetters() {
        assertEquals(later, decide("x = my_var2", "2"))
        assertEquals(later, decide("名字", "字"))
        assertEquals("my_var2", CompletionTyping.wordBefore("x = my_var2"))
    }
}
