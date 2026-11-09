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

package x.editor.app.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserTest {

    private fun parse(vararg lines: String): List<SseParser.Event> {
        val parser = SseParser()
        val out = lines.mapNotNull { parser.feed(it) }.toMutableList()
        parser.finish()?.let { out += it }
        return out
    }

    @Test
    fun anEventEndsAtABlankLine() {
        val events = parse("event: message_start", "data: {\"a\":1}", "", "data: {\"b\":2}", "")
        assertEquals(listOf(SseParser.Event("message_start", "{\"a\":1}"), SseParser.Event(null, "{\"b\":2}")), events)
    }

    @Test
    fun dataLinesOfOneEventAreJoinedByNewlines() {
        assertEquals(listOf(SseParser.Event(null, "one\ntwo")), parse("data: one", "data: two", ""))
    }

    @Test
    fun commentsAndOtherFieldsAreLetBe() {
        assertEquals(listOf(SseParser.Event(null, "x")), parse(": keep-alive", "id: 7", "retry: 100", "data:x", ""))
    }

    @Test
    fun anEventNotEndedWhenTheStreamEndsStillComes() {
        assertEquals(listOf(SseParser.Event(null, "[DONE]")), parse("data: [DONE]"))
    }

    @Test
    fun blankLinesAloneMakeNoEvent() {
        val parser = SseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed(""))
        assertNull(parser.finish())
    }
}
