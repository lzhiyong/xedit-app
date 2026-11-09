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
package x.editor.app.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One thing at a time on a panel of the developer tools. */
class OperationGateTest {

    @Test
    fun aSecondOperationIsRefusedTillTheFirstEnds() {
        val gate = OperationGate()
        val save = assertNotNull(gate.begin(PanelOp.SAVE, "a.json", "a"))
        assertTrue(gate.isBusy)
        assertNull(gate.begin(PanelOp.DELETE, "b.json", "b"))
        assertSame(save, gate.current)
        gate.end(save)
        assertFalse(gate.isBusy)
        assertNotNull(gate.begin(PanelOp.DELETE, "b.json", "b"))
    }

    @Test
    fun endingTwiceOrAnEndedOneDoesNotEndWhatGoesOnNow() {
        val gate = OperationGate()
        val first = assertNotNull(gate.begin(PanelOp.PREVIEW, "a"))
        gate.end(first)
        val second = assertNotNull(gate.begin(PanelOp.SAVE, "b"))
        // (a preview ends where the text is decided, and again in its finally)
        gate.end(first)
        assertSame(second, gate.current)
    }

    @Test
    fun anOperationGoesOnAsAnotherAndKeepsItsPlace() {
        val gate = OperationGate()
        val ticket = assertNotNull(gate.begin(PanelOp.SAVE, "clangd.json", "clangd"))
        gate.become(PanelOp.RESTART, "clangd")
        assertSame(ticket, gate.current)
        assertEquals(PanelOp.RESTART, ticket.op)
        assertEquals("clangd", ticket.subject)
        assertEquals("clangd", ticket.key)
        assertNull(gate.begin(PanelOp.SAVE, "other.json"))
    }

    @Test
    fun thePanelIsToldOfEachChangeAndOnlyThose() {
        val gate = OperationGate()
        var told = 0
        gate.onChanged = { told++ }
        val ticket = assertNotNull(gate.begin(PanelOp.SAVE))
        assertEquals(1, told)
        assertNull(gate.begin(PanelOp.DELETE))
        assertEquals(1, told)
        gate.become(PanelOp.RESTART)
        assertEquals(2, told)
        gate.end(ticket)
        assertEquals(3, told)
        gate.end(ticket)
        assertEquals(3, told)
        gate.become(PanelOp.SAVE)
        assertEquals(3, told)
    }

    @Test
    fun aWriteIsNotClosedOnWhatGoesOnByItselfIs() {
        assertFalse(PanelOp.SAVE.closable)
        assertFalse(PanelOp.DELETE.closable)
        assertTrue(PanelOp.RESTART.closable)
        assertTrue(PanelOp.PREVIEW.closable)
    }
}
