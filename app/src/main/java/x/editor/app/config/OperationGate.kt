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

/**
 * What a panel of the developer tools can be busy with. [closable]: the panel may be closed
 * meanwhile, what goes on going on by itself; else it waits (the write of a save, of a delete
 * is short, and "discard the changes?" over a save that is being written would be a lie).
 */
internal enum class PanelOp(val closable: Boolean) {
    SAVE(false),
    DELETE(false),
    UNINSTALL(false),
    REMOVE(false),
    STOP(false),
    RESTART(true),
    PREVIEW(true),
}

/**
 * One thing at a time on a panel: what changes something (a save, a delete, a preview, a
 * server restarted) goes through here, and what is asked for while another goes on is
 * refused, not run beside it. Each panel has one.
 *
 * Main thread. Pure Kotlin: no Android in it.
 *
 * ```
 * val ticket = gate.begin(PanelOp.SAVE, "clangd.json", "clangd") ?: return  // busy
 * scope.launch { try { … } finally { gate.end(ticket) } }
 * ```
 */
internal class OperationGate {

    /** One operation going on: [op], what it is on as the user knows it ([subject]), and the [key] of its entry, if it has one. */
    class Ticket internal constructor(op: PanelOp, subject: String, val key: String?) {
        var op: PanelOp = op
            internal set
        var subject: String = subject
            internal set
    }

    /** What goes on now; null: nothing. */
    var current: Ticket? = null
        private set

    /** Told when [current] changed: the panel locks or unlocks what it shows. */
    var onChanged: () -> Unit = {}

    val isBusy: Boolean get() = current != null

    /** The operation begun; null: another goes on, nothing was begun. */
    fun begin(op: PanelOp, subject: String = "", key: String? = null): Ticket? {
        if (current != null) return null
        return Ticket(op, subject, key).also {
            current = it
            onChanged()
        }
    }

    /** The operation going on goes on as another: the write of a save, then its server restarted. */
    fun become(op: PanelOp, subject: String? = null) {
        val ticket = current ?: return
        ticket.op = op
        if (subject != null) ticket.subject = subject
        onChanged()
    }

    /** [ticket] is done; what is done already, or was not begun here, changes nothing. */
    fun end(ticket: Ticket) {
        if (current !== ticket) return
        current = null
        onChanged()
    }
}
