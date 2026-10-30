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

package x.editor.app.highlight

import android.content.Context
import android.graphics.Color
import android.util.TypedValue

import androidx.core.graphics.ColorUtils

import com.google.android.material.color.MaterialColors

import kotlinx.serialization.json.JsonObject

import x.github.module.editor.view.EditorView

/**
 * The colors of the editor itself, from the `editor` block of a highlight theme:
 * background, text, cursor, the line of the cursor, the selection, line numbers,
 * search matches, matching brackets, indent guides, fold markers. Each is null if
 * the theme does not say; that one is then the system's (see [applyTo]).
 */
data class EditorColorScheme(
    val background: Int?,
    val foreground: Int?,
    val cursor: Int?,
    val currentLine: Int?,
    val selection: Int?,
    val lineNumber: Int?,
    val currentLineNumber: Int?,
    val searchMatch: Int?,
    val currentMatch: Int?,
    /** behind a bracket at the cursor and its partner */
    val bracketMatch: Int?,
    /** the line under them */
    val bracketUnderline: Int?,
    val indentGuide: Int?,
    val foldMarker: Int?,
    /** behind the lines of code blocks (Markdown's), see [codeBlockColor] */
    val codeBlock: Int? = null
) {

    companion object {

        /**
         * Reads the `editor` block of a theme. Its entries are objects with `fg` and
         * `bg`, named as Neovim exports them (`background`, `cursor`, `cursor_line`,
         * `selection`, `line_number`, `line_number_current`, `search_match`,
         * `search_match_current`, `bracket_match`, `whitespace`, `fold_gutter`, ...).
         */
        fun parse(editor: JsonObject): EditorColorScheme {
            fun color(entry: String, key: String): Int? =
                HighlightTheme.colorOf(editor[entry] as? JsonObject, key)

            // the bracket match of most themes has only a foreground: a faint
            // background of that color behind the brackets, and the line under them
            val bracketForeground = color("bracket_match", "fg")
            val bracketBackground = color("bracket_match", "bg")
                ?: bracketForeground?.let { ColorUtils.setAlphaComponent(it, 0x40) }

            return EditorColorScheme(
                background = color("background", "bg"),
                foreground = color("background", "fg"),
                cursor = color("cursor", "bg") ?: color("cursor", "fg"),
                currentLine = color("cursor_line", "bg"),
                selection = color("selection", "bg"),
                lineNumber = color("line_number", "fg"),
                currentLineNumber = color("line_number_current", "fg"),
                searchMatch = color("search_match", "bg"),
                currentMatch = color("search_match_current", "bg"),
                bracketMatch = bracketBackground,
                bracketUnderline = bracketForeground,
                indentGuide = color("whitespace", "fg") ?: color("non_text", "fg"),
                foldMarker = color("fold_gutter", "fg") ?: color("line_number", "fg"),
                // as render-markdown.nvim does: the color column of the theme
                codeBlock = color("code_block", "bg") ?: color("color_column", "bg")
            )
        }

        /**
         * Gives [editor] the colors of [scheme]; each color the scheme does not have
         * (or all, with no scheme) is the system's: the defaults of the editor, the
         * text in the color the app uses on [isNightMode], the cursor in the accent
         * color of the app theme. Main thread.
         */
        fun applyTo(editor: EditorView, scheme: EditorColorScheme?, isNightMode: Boolean) {
            val context = editor.context

            val background = scheme?.background
            if (background != null) {
                editor.setBackgroundColor(background)
            } else {
                // the window behind it
                editor.background = null
            }
            editor.setTextColor(scheme?.foreground ?: if (isNightMode) Color.LTGRAY else Color.DKGRAY)
            editor.setHandleColor(
                scheme?.cursor
                    ?: MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
            )
            editor.setCurrentLineColor(scheme?.currentLine ?: DEFAULT_CURRENT_LINE)
            editor.setSelectionColor(scheme?.selection ?: systemSelection(context))
            editor.setLineNumberColor(scheme?.lineNumber ?: Color.GRAY)
            editor.setCurrentLineNumberColor(scheme?.currentLineNumber)
            editor.setSearchMatchColor(scheme?.searchMatch ?: DEFAULT_SEARCH_MATCH)
            editor.setCurrentMatchColor(scheme?.currentMatch ?: DEFAULT_CURRENT_MATCH)
            editor.setBracketMatchColor(scheme?.bracketMatch ?: DEFAULT_BRACKET_MATCH)
            editor.setBracketUnderlineColor(scheme?.bracketUnderline)
            editor.setIndentGuideColors(scheme?.indentGuide, scheme?.currentLineNumber?.let {
                ColorUtils.setAlphaComponent(it, 0x99)
            })
            editor.setFoldMarkerColor(scheme?.foldMarker)
            // the gutter takes the background of the editor
            editor.setGutterBackgroundColor(null)
        }

        /**
         * The background of the lines of code blocks: the theme's (`code_block`, or
         * `color_column` as render-markdown.nvim takes it); without one, the text color
         * faintly over the background, so that it suits any theme; with the system's
         * colors, a faint shade of the text color the app uses on [isNightMode].
         */
        fun codeBlockColor(scheme: EditorColorScheme?, isNightMode: Boolean): Int {
            scheme?.codeBlock?.let { return it }
            val background = scheme?.background
            val foreground = scheme?.foreground
            if (background != null) {
                val text = foreground
                    ?: if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE
                return ColorUtils.blendARGB(background, text, CODE_BLOCK_SHADE)
            }
            // over a window background the editor does not know
            return if (isNightMode) Color.argb(0x14, 255, 255, 255) else Color.argb(0x0F, 0, 0, 0)
        }

        // how much of the text color is mixed into the background of code blocks
        private const val CODE_BLOCK_SHADE = 0.06f

        // the defaults of EditorView (see LineDecorationPainter)
        private val DEFAULT_CURRENT_LINE = Color.argb(68, 204, 204, 204)
        private val DEFAULT_SEARCH_MATCH = Color.argb(96, 0, 188, 212)
        private val DEFAULT_CURRENT_MATCH = Color.argb(255, 255, 152, 0)
        private val DEFAULT_BRACKET_MATCH = Color.argb(72, 128, 128, 128)

        // what text fields of the app theme select with
        private fun systemSelection(context: Context): Int {
            val value = TypedValue()
            return if (context.theme.resolveAttribute(android.R.attr.textColorHighlight, value, true)) {
                if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
            } else {
                Color.argb(136, 204, 204, 204)
            }
        }
    }
}
