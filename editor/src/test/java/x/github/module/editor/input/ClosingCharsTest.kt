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

package x.github.module.editor.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A completion goes over the closing char already after it, not before it (see ClosingChars). */
class ClosingCharsTest {

    @Test
    fun includesOfClangd() {
        // `#include <st|>`: clangd puts `stdlib.h>` in place of `st`
        assertTrue(ClosingChars.goesOver("#include <", "stdlib.h>", '>'))
        assertTrue(ClosingChars.goesOver("#include \"", "util.h\"", '"'))
        // a directory: no closing char of its own
        assertFalse(ClosingChars.goesOver("#include <", "sys/", '>'))
        // the range of the server took the `<` too
        assertTrue(ClosingChars.goesOver("#include ", "<stdio.h>", '>'))
    }

    @Test
    fun aBracketStillOpenKeepsTheCharAfter() {
        // `bar(fo|)`: `foo()` closes its own bracket, the `)` after is bar's
        assertFalse(ClosingChars.goesOver("bar(", "foo()", ')'))
        // a snippet that closes what was opened before it
        assertTrue(ClosingChars.goesOver("vector<", "int>", '>'))
        assertFalse(ClosingChars.goesOver("map<string, vector<", "int>", '>'))
        assertTrue(ClosingChars.goesOver("x = [", "1, 2]", ']'))
    }

    @Test
    fun quotes() {
        // JSON: `"na|"`, the server's item `"name"` from the opening quote
        assertTrue(ClosingChars.goesOver("  ", "\"name\"", '"'))
        // `"na|"`, the item `name"` after the opening quote
        assertTrue(ClosingChars.goesOver("  \"", "name\"", '"'))
        // a quote that would open a string: kept
        assertFalse(ClosingChars.goesOver("x = ", "a\"", '"'))
        // an escaped quote does not count
        assertFalse(ClosingChars.goesOver("s = \"a\\\"", "b\\\"", '"'))
    }

    @Test
    fun nothingToGoOver() {
        assertFalse(ClosingChars.goesOver("#include <", "stdlib.h>", null))
        assertFalse(ClosingChars.goesOver("#include <", "stdlib.h>", ';'))
        assertFalse(ClosingChars.goesOver("foo", "bar", 'r'))
        assertFalse(ClosingChars.goesOver("", "", '>'))
        // over lines: only the last line of what is put in counts
        assertTrue(ClosingChars.goesOver("if (", "a) {\n  b\n}", '}'))
    }

    @Test
    fun theOpeningCharTheItemBringsItself() {
        // a server with no range: `<stdio.h>` in place of `st` in `#include <st|>`
        assertTrue(ClosingChars.takesOpening("#include <", "<stdio.h>"))
        assertTrue(ClosingChars.goesOver("#include ", "<stdio.h>", '>'))
        assertTrue(ClosingChars.takesOpening("  \"", "\"name\""))
        // not one that brings no pair, nor one before a closed string
        assertFalse(ClosingChars.takesOpening("#include <", "stdio.h>"))
        assertFalse(ClosingChars.takesOpening("x = \"a\" + \"", "b"))
        assertFalse(ClosingChars.takesOpening("a < ", "<b>"))
        assertFalse(ClosingChars.takesOpening("", "<a>"))
    }
}
