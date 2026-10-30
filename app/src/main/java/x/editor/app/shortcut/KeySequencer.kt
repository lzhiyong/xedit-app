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

package x.editor.app.shortcut

import android.os.Handler
import android.os.Looper

/**
 * Turns keys, one at a time and from wherever they come (a hardware keyboard, an input
 * method, the row of extra keys), into what the [keymap] binds them to, as Neovim does:
 *
 *  - keys that are bound and begin nothing longer: run at once;
 *  - keys that begin longer ones (`<C-k>` of `<C-k>c`): the next key is waited for, at
 *    most [Keymap.timeoutMillis]; if none comes, what the keys so far are bound to (if
 *    anything) runs then;
 *  - a key that ends no sequence that was begun: the sequence is dropped, the key with
 *    it (it was meant for the sequence, not to be typed) and [onUnbound] told.
 *
 * Main thread.
 *
 * @param run carries out an action
 * @param onPendingChanged the keys waited on (empty: none), to show them
 * @param onUnbound keys that were begun but are bound to nothing
 */
class KeySequencer(
    private val run: (ShortcutAction) -> Unit,
    private val onPendingChanged: (List<KeyStroke>) -> Unit,
    private val onUnbound: (List<KeyStroke>) -> Unit,
) {

    /** What keys do with the last key [feed] was given. */
    enum class Result {
        /** Bound: done (or left out, a held key that does not repeat). */
        RAN,
        /** The beginning of longer keys: the next is waited for. */
        PENDING,
        /** The end of a sequence that is bound to nothing: taken all the same. */
        DROPPED,
        /** Not bound to anything, and no sequence begun: the key goes on as usual. */
        NONE
    }

    var keymap: Keymap = Keymap.DEFAULT
        set(value) {
            field = value
            reset()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayList<KeyStroke>()
    private var waitingAction: ShortcutAction? = null

    private val timeout = Runnable {
        val action = waitingAction
        val keys = pending.toList()
        reset()
        if (action != null) run(action) else onUnbound(keys)
    }

    /** Whether a sequence was begun: the next key is for it. */
    val isPending: Boolean get() = pending.isNotEmpty()

    /**
     * [stroke], pressed; [allowed]: whether an action may run where the focus is now (an
     * action of the editor not while another text field has it), else the key goes on as
     * if it were not bound; [repeat]: a key held down (an action that does not repeat is
     * left out, the key taken).
     */
    fun feed(stroke: KeyStroke, repeat: Boolean = false, allowed: (ShortcutAction) -> Boolean = { true }): Result {
        handler.removeCallbacks(timeout)
        val keys = pending + stroke
        val exact = keymap.exact(keys)?.action?.takeIf(allowed)
        val longer = keymap.hasLonger(keys)
        return when {
            exact != null && !longer -> {
                reset()
                if (!repeat || exact.repeatable) run(exact)
                Result.RAN
            }
            longer -> {
                pending += stroke
                waitingAction = exact
                handler.postDelayed(timeout, keymap.timeoutMillis)
                onPendingChanged(pending.toList())
                Result.PENDING
            }
            pending.isNotEmpty() -> {
                reset()
                onUnbound(keys)
                Result.DROPPED
            }
            else -> Result.NONE
        }
    }

    /** No sequence begun any more. */
    fun reset() {
        handler.removeCallbacks(timeout)
        val had = pending.isNotEmpty()
        pending.clear()
        waitingAction = null
        if (had) onPendingChanged(emptyList())
    }
}
