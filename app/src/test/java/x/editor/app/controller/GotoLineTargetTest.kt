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

package x.editor.app.controller

import kotlin.test.Test
import kotlin.test.assertEquals

/** What is typed in the go-to-line dialog: a line, or line:column. */
class GotoLineTargetTest {

    private fun parse(text: String) = GotoLineTarget.parse(text, 239380)

    @Test
    fun linesAndColumns() {
        assertEquals(GotoLineTarget.Valid(1, 1), parse("1"))
        assertEquals(GotoLineTarget.Valid(120, 1), parse(" 120 "))
        assertEquals(GotoLineTarget.Valid(120, 5), parse("120:5"))
        assertEquals(GotoLineTarget.Valid(120, 5), parse("120 : 5"))
        // the column being typed
        assertEquals(GotoLineTarget.Valid(120, 1), parse("120:"))
        assertEquals(GotoLineTarget.Valid(12, Int.MAX_VALUE), parse("12:999999999999"))
    }

    @Test
    fun theEndsOfTheFile() {
        assertEquals(GotoLineTarget.Valid(239380, 1), parse("239380"))
        assertEquals(GotoLineTarget.OutOfRange(239381), parse("239381"))
        assertEquals(GotoLineTarget.OutOfRange(0), parse("0"))
        assertEquals(GotoLineTarget.OutOfRange(Long.MAX_VALUE), parse("99999999999999999999"))
    }

    @Test
    fun whatIsNotALine() {
        assertEquals(GotoLineTarget.Empty, parse(""))
        assertEquals(GotoLineTarget.Empty, parse("   "))
        assertEquals(GotoLineTarget.Invalid, parse("12:0"))
        assertEquals(GotoLineTarget.Invalid, parse(":5"))
        assertEquals(GotoLineTarget.Invalid, parse("1:2:3"))
    }
}
