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

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * One key of a shortcut, with the modifiers held with it, written as in Neovim:
 * `<C-s>`, `<C-S-f>`, `<A-z>`, `<F5>`, `<C-/>`, or a plain char (`f`, `F`).
 *
 * A key that makes a char is that char: Shift is in it ([key] `F` is Shift+F, `?` is
 * whatever the layout types it with), so a hardware keyboard and a soft keyboard (a
 * char typed while Ctrl of the row of extra keys is latched) come to the same stroke.
 * Only keys that make no char ([NAMED]: F1–F12, the arrows, Tab, ...) carry [shift].
 *
 * @property key a char ("s", "F", "/") or the name of a key without one ("F5", "Up")
 * @property meta the Meta (Super, Windows, Command) key: `D-` in Neovim's notation
 */
data class KeyStroke(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val shift: Boolean = false
) {

    val isNamed: Boolean get() = key.length > 1

    /**
     * Whether it can start a shortcut without getting in the way of typing: with Ctrl,
     * Alt or Meta, or a function key. A key after the first may be anything.
     */
    val canStart: Boolean
        get() = ctrl || alt || meta || FUNCTION_KEY.matches(key)

    /** The keys to draw (KeyCaps), one by one: Ctrl, Alt, Meta, Shift, then the key. */
    val capLabels: List<String>
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (meta) add("Meta")
            val capital = key.length == 1 && key[0].isUpperCase()
            if (shift || capital) add("Shift")
            add(DISPLAY[key] ?: if (key.length == 1) key.uppercase() else key)
        }

    /** As written in the keymap: `<C-S-f>`, `<F5>`, `x`, `<lt>`. */
    override fun toString(): String {
        val modifiers = buildString {
            if (ctrl) append("C-")
            if (alt) append("A-")
            if (meta) append("D-")
            if (shift) append("S-")
        }
        // a capital with a modifier: written with S- and the small letter, as Neovim does
        val (name, capital) = when {
            key.length == 1 && key[0].isUpperCase() && modifiers.isNotEmpty() -> key.lowercase() to true
            else -> (CHAR_NAMES[key] ?: key) to false
        }
        val all = modifiers + (if (capital) "S-" else "")
        return when {
            all.isEmpty() && key.length == 1 && key !in CHAR_NAMES -> key
            else -> "<$all$name>"
        }
    }

    companion object {

        private val FUNCTION_KEY = Regex("F([1-9]|1[0-2])")

        // how a key without a char is shown on a cap
        private val DISPLAY = mapOf(
            "Up" to "↑", "Down" to "↓", "Left" to "←", "Right" to "→",
            "CR" to "Enter", "BS" to "Backspace", "Del" to "Delete",
            "PageUp" to "PgUp", "PageDown" to "PgDn"
        )

        /** The keys without a char, by their names in the keymap, and their key codes. */
        val NAMED: Map<String, Int> = buildMap {
            for (n in 1..12) put("F$n", KeyEvent.KEYCODE_F1 + n - 1)
            put("Up", KeyEvent.KEYCODE_DPAD_UP)
            put("Down", KeyEvent.KEYCODE_DPAD_DOWN)
            put("Left", KeyEvent.KEYCODE_DPAD_LEFT)
            put("Right", KeyEvent.KEYCODE_DPAD_RIGHT)
            put("Home", KeyEvent.KEYCODE_MOVE_HOME)
            put("End", KeyEvent.KEYCODE_MOVE_END)
            put("PageUp", KeyEvent.KEYCODE_PAGE_UP)
            put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN)
            put("Insert", KeyEvent.KEYCODE_INSERT)
            put("Del", KeyEvent.KEYCODE_FORWARD_DEL)
            put("BS", KeyEvent.KEYCODE_DEL)
            put("Tab", KeyEvent.KEYCODE_TAB)
            put("CR", KeyEvent.KEYCODE_ENTER)
            put("Esc", KeyEvent.KEYCODE_ESCAPE)
            put("Space", KeyEvent.KEYCODE_SPACE)
        }

        private val BY_CODE: Map<Int, String> = NAMED.entries.associate { (name, code) -> code to name } +
            mapOf(KeyEvent.KEYCODE_NUMPAD_ENTER to "CR")

        // other names Neovim accepts for them
        private val ALIASES = mapOf(
            "enter" to "CR", "return" to "CR", "escape" to "Esc", "backspace" to "BS",
            "delete" to "Del", "pageup" to "PageUp", "pagedown" to "PageDown", "insert" to "Insert",
            "space" to "Space", "tab" to "Tab", "cr" to "CR", "esc" to "Esc", "bs" to "BS", "del" to "Del",
            "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right", "home" to "Home", "end" to "End"
        )

        // chars written by name: they mean something else in the notation
        private val CHAR_NAMES = mapOf("<" to "lt", "\\" to "Bslash", "|" to "Bar")
        private val NAMED_CHARS = mapOf("lt" to "<", "bslash" to "\\", "bar" to "|")

        /**
         * The keys of [text] in Neovim's notation, one after the other (`<C-k>c` is two);
         * `<leader>` is [leader]. Null with the reason if it is not that.
         */
        fun parseSequence(text: String, leader: List<KeyStroke>?): Result<List<KeyStroke>> {
            val strokes = ArrayList<KeyStroke>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '<') {
                    val end = text.indexOf('>', i + 1)
                    if (end < 0) return Result.failure(IllegalArgumentException("'<' without '>' in \"$text\""))
                    val inside = text.substring(i + 1, end)
                    if (inside.equals("leader", ignoreCase = true)) {
                        strokes += leader ?: return Result.failure(IllegalArgumentException("<leader> is not set"))
                    } else {
                        strokes += parseKey(inside).getOrElse { return Result.failure(it) }
                    }
                    i = end + 1
                } else {
                    strokes += ofChar(c)
                    i++
                }
            }
            if (strokes.isEmpty()) return Result.failure(IllegalArgumentException("no keys"))
            return Result.success(strokes)
        }

        // what is between < and >: modifiers (C- A- M- D- S-) and a key
        private fun parseKey(inside: String): Result<KeyStroke> {
            var ctrl = false
            var alt = false
            var meta = false
            var shift = false
            var rest = inside
            while (rest.length > 2 && rest[1] == '-') {
                when (rest[0].uppercaseChar()) {
                    'C' -> ctrl = true
                    'A', 'M' -> alt = true
                    'D' -> meta = true
                    'S' -> shift = true
                    else -> return Result.failure(IllegalArgumentException("unknown modifier in <$inside>"))
                }
                rest = rest.substring(2)
            }
            if (rest.length == 1) {
                // a char: Shift goes into it (S-f is F); a letter with Ctrl, Alt or Meta
                // is the small one unless S- says otherwise, as in Neovim
                var char = rest[0]
                if (char.isLetter() && (ctrl || alt || meta)) char = char.lowercaseChar()
                if (shift) {
                    if (!char.isLetter()) return Result.failure(
                        IllegalArgumentException("<$inside>: write the char Shift makes (\"?\", not \"S-/\")")
                    )
                    char = char.uppercaseChar()
                }
                return Result.success(KeyStroke(char.toString(), ctrl, alt, meta))
            }
            NAMED_CHARS[rest.lowercase()]?.let { return Result.success(KeyStroke(it, ctrl, alt, meta)) }
            val name = NAMED.keys.firstOrNull { it.equals(rest, ignoreCase = true) } ?: ALIASES[rest.lowercase()]
                ?: return Result.failure(IllegalArgumentException("unknown key <$inside>"))
            return Result.success(KeyStroke(name, ctrl, alt, meta, shift))
        }

        /** A char typed (by a soft keyboard, or a symbol of the row of extra keys). */
        fun ofChar(c: Char, ctrl: Boolean = false, alt: Boolean = false): KeyStroke = when (c) {
            ' ' -> KeyStroke("Space", ctrl, alt)
            '\t' -> KeyStroke("Tab", ctrl, alt)
            '\n' -> KeyStroke("CR", ctrl, alt)
            // a capital is Shift with it, as from a hardware keyboard
            else -> KeyStroke(c.toString(), ctrl, alt)
        }

        /** The key of [event] (a hardware keyboard's, or one an input method sent); null for a modifier alone. */
        fun ofEvent(event: KeyEvent): KeyStroke? {
            val code = event.keyCode
            if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_UNKNOWN) return null
            val ctrl = event.isCtrlPressed
            val alt = event.isAltPressed
            val meta = event.isMetaPressed
            BY_CODE[code]?.let { return KeyStroke(it, ctrl, alt, meta, event.isShiftPressed) }
            // a letter by its key, whatever the layout (Ctrl+S on a Cyrillic layout is still <C-s>)
            if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                val letter = 'a' + (code - KeyEvent.KEYCODE_A)
                return KeyStroke((if (event.isShiftPressed) letter.uppercaseChar() else letter).toString(), ctrl, alt, meta)
            }
            // else the char it makes with Shift (and nothing else) held
            val shiftOnly = event.metaState and KeyEvent.META_SHIFT_MASK
            val unicode = event.keyCharacterMap?.get(code, shiftOnly)
                ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).get(code, shiftOnly)
            if (unicode == 0 || unicode and KeyCharacterMap.COMBINING_ACCENT != 0) return null
            return KeyStroke(String(Character.toChars(unicode)), ctrl, alt, meta)
        }
    }
}
