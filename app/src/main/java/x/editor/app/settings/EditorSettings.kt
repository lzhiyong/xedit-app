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

package x.editor.app.settings

import android.content.Context
import android.os.Parcelable

import androidx.core.content.edit

import kotlinx.parcelize.Parcelize

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R

/**
 * The settings of the editor, as the editor settings screen stores them (see
 * `res/xml/editor_preferences.xml`), read all at once.
 *
 * EditorActivity keeps the settings it applied last (also over a recreation, it
 * is Parcelable) and, when it comes back to the front, applies only what changed
 * since: see [load] and EditorActivity.applyEditorSettings.
 *
 * @property highlightTheme the name of the highlight theme to load, already
 *   resolved (see [AppSettings.resolveHighlightTheme])
 * @property font the font, as EditorFonts stores it
 * @property textSize in sp
 */
@Parcelize
data class EditorSettings(
    val highlightTheme: String,
    val font: String,
    val textSize: Float,
    val tabSize: Int,
    val wordwrap: Boolean,
    val lineNumbers: Boolean,
    val gutter: Boolean,
    val indentGuides: Boolean,
    val folding: Boolean,
    val bracketMatching: Boolean,
    val codeBlockBackground: Boolean,
    val colorPreview: Boolean,
    val syntaxHighlight: Boolean,
    val autoPairs: Boolean,
    val autoPairChars: String
) : Parcelable {

    companion object {

        /** The editor settings as they are stored now; [isNightMode] picks the highlight theme. */
        fun load(context: Context, isNightMode: Boolean): EditorSettings {
            val preferences = AppSettings.preferences(context)
            val resources = context.resources
            fun bool(key: String, default: Int) =
                preferences.getBoolean(key, resources.getBoolean(default))

            return EditorSettings(
                highlightTheme = AppSettings.resolveHighlightTheme(context, isNightMode),
                font = preferences.getString(Constants.PREF_EDITOR_FONT, null) ?: EditorFonts.DEFAULT,
                textSize = getTextSize(context),
                tabSize = preferences.getString(Constants.PREF_EDITOR_TAB_SIZE, null)
                    ?.toIntOrNull()
                    ?.coerceIn(1, 16)
                    ?: 4,
                wordwrap = bool(Constants.PREF_EDITOR_WORDWRAP, R.bool.pref_default_editor_wordwrap),
                lineNumbers = bool(Constants.PREF_EDITOR_LINE_NUMBERS, R.bool.pref_default_editor_line_numbers),
                gutter = bool(Constants.PREF_EDITOR_GUTTER, R.bool.pref_default_editor_gutter),
                indentGuides = bool(Constants.PREF_EDITOR_INDENT_GUIDES, R.bool.pref_default_editor_indent_guides),
                folding = bool(Constants.PREF_EDITOR_FOLDING, R.bool.pref_default_editor_folding),
                bracketMatching = bool(Constants.PREF_EDITOR_BRACKET_MATCHING, R.bool.pref_default_editor_bracket_matching),
                codeBlockBackground = bool(
                    Constants.PREF_EDITOR_CODE_BLOCK_BACKGROUND,
                    R.bool.pref_default_editor_code_block_background
                ),
                colorPreview = bool(Constants.PREF_EDITOR_COLOR_PREVIEW, R.bool.pref_default_editor_color_preview),
                syntaxHighlight = isSyntaxHighlight(context),
                autoPairs = bool(Constants.PREF_EDITOR_AUTO_PAIRS, R.bool.pref_default_editor_auto_pairs),
                autoPairChars = getAutoPairChars(context)
            )
        }

        /** The pairs typed together, as text (see AutoPair.parse): what was set, or the default. */
        fun getAutoPairChars(context: Context): String =
            AppSettings.preferences(context).getString(Constants.PREF_EDITOR_AUTO_PAIR_CHARS, null)
                ?: context.getString(R.string.pref_editor_auto_pair_chars_default)

        /** Remembers the pairs typed together; null goes back to the default. */
        fun setAutoPairChars(context: Context, chars: String?) {
            AppSettings.preferences(context).edit {
                if (chars == null) remove(Constants.PREF_EDITOR_AUTO_PAIR_CHARS)
                else putString(Constants.PREF_EDITOR_AUTO_PAIR_CHARS, chars)
            }
        }

        /**
         * What in [text] is not a pair that can be typed together: not two chars, a
         * letter, a digit or white space in it, or an opening char that came before.
         * Empty if all of it is fine.
         */
        fun invalidAutoPairs(text: String): List<String> {
            val opens = HashSet<Char>()
            return text.split(' ', '\t', '\n').filter { it.isNotEmpty() }.filter { token ->
                token.length != 2 ||
                    token.any { it.isLetterOrDigit() || it.isWhitespace() } ||
                    !opens.add(token[0])
            }
        }

        /** Whether the text is highlighted (parsed with tree-sitter) at all. */
        /** Completion from the words of the text (when there is no language server, or it has nothing). */
        fun isBufferCompletion(context: Context): Boolean =
            AppSettings.preferences(context).getBoolean(
                Constants.PREF_EDITOR_BUFFER_COMPLETION,
                context.resources.getBoolean(R.bool.pref_default_editor_buffer_completion)
            )

        fun isSyntaxHighlight(context: Context): Boolean =
            AppSettings.preferences(context).getBoolean(
                Constants.PREF_EDITOR_SYNTAX_HIGHLIGHT,
                context.resources.getBoolean(R.bool.pref_default_editor_syntax_highlight)
            )

        /** The text size chosen in the settings, in sp. */
        fun getTextSize(context: Context): Float =
            AppSettings.preferences(context)
                .getFloat(Constants.PREF_EDITOR_TEXTSIZE, Constants.EDITOR_TEXTSIZE_DEFAULT)
                .coerceIn(Constants.EDITOR_TEXTSIZE_MIN, Constants.EDITOR_TEXTSIZE_MAX)

        /** Remembers the text size [sp]; null goes back to the default. */
        fun setTextSize(context: Context, sp: Float?) {
            AppSettings.preferences(context).edit {
                if (sp == null) {
                    remove(Constants.PREF_EDITOR_TEXTSIZE)
                } else {
                    putFloat(
                        Constants.PREF_EDITOR_TEXTSIZE,
                        sp.coerceIn(Constants.EDITOR_TEXTSIZE_MIN, Constants.EDITOR_TEXTSIZE_MAX)
                    )
                }
            }
        }

        /** Remembers whether long lines are wrapped (the menu of the editor and the settings share it). */
        fun setWordwrap(context: Context, wordwrap: Boolean) {
            AppSettings.preferences(context).edit {
                putBoolean(Constants.PREF_EDITOR_WORDWRAP, wordwrap)
            }
        }
    }
}
