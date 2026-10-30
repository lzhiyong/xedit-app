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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import x.github.module.lsp.CompletionKind

/** The call of a function taken from the list, a placeholder for each parameter (see CallSnippet). */
class CallSnippetTest {

    private fun call(name: String, vararg signatures: String?, before: String = "x = ", after: String = "", kind: CompletionKind? = CompletionKind.FUNCTION) =
        CallSnippet.of(name, kind, signatures.toList(), before, after)

    @Test
    fun theParametersOfTheSignature() {
        // pyright: the signature in the detail; self, defaults and *args get no placeholder
        assertEquals("add(\${1:a}, \${2:b})\$0", call("add", "(method) def add(self, a: int, b: str, c: str = \"x\", *args, **kw) -> None"))
        // typescript: optional ones are left out, types with commas stay whole
        assertEquals("f(\${1:m}, \${2:cb})\$0", call("f", "function f(m: Map<string, number>, cb: (a: number) => void, o?: string): void"))
        // a label detail as clangd and others send it
        assertEquals("put(\${1:const char *s}, \${2:int n})\$0", call("put", "(const char *s, int n)"))
        // lua
        assertEquals("print(\${1:a}, \${2:b})\$0", call("print", null, "function print(a, b, ...)"))
        // kotlin defaults
        assertEquals("draw(\${1:x})\$0", call("draw", "fun draw(x: Int, y: Int = 0): Unit"))
    }

    @Test
    fun bracketsWhenNothingOrNothingRequiredIsKnown() {
        assertEquals("go(\$1)\$0", call("go"))
        assertEquals("go(\$1)\$0", call("go", "a type, not a signature"))
        assertEquals("now()\$0", call("now", "def now() -> float"))
        assertEquals("log(\$1)\$0", call("log", "def log(*values: object, sep: str = ' ')"))
    }

    @Test
    fun notCalledThere() {
        assertNull(call("x", kind = CompletionKind.VARIABLE))
        assertNull(call("foo", after = "(1, 2)"))
        assertNull(call("foo", before = "from mod import "))
        assertNull(call("foo", before = "#include "))
        assertNull(call("foo", before = "callback = &"))
        assertNull(call("foo.bar"))
    }

    @Test
    fun snippetCharsAreTakenLiterally() {
        // `$q` would be a variable of the snippet: escaped, as `$` in a placeholder
        assertEquals("\\\$q(\${1:\\\$x})\$0", call("\$q", "(\$x)"))
    }
}
