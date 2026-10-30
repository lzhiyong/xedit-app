/*
 * Copyright © 2023 Github Lzhiyong
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

package x.editor.app

object Constants {

    // ---------------------------------------------------------------------
    // Crash report
    // ---------------------------------------------------------------------

    // TODO: replace with your server endpoint. It receives an HTTP POST with a
    // text/plain UTF-8 body. Use https: cleartext http is blocked by default on API 28+.
    const val CRASH_REPORT_URL = "https://example.com/api/crash-report"

    // Must match the <intent-filter> action of CrashActivity in AndroidManifest.xml
    const val ACTION_CRASH_HANDLER = "x.editor.app.CRASH_HANDLER"

    // Inline crash text, only used when the log could not be written to disk.
    // Normally CrashActivity reads every log from disk and needs no extras.
    const val EXTRA_CRASH_STACK_TRACE = "CRASH_STACK_TRACE"

    // true: showing crashes from a previous launch while the app is running normally
    const val EXTRA_CRASH_FROM_PREVIOUS_LAUNCH = "CRASH_FROM_PREVIOUS_LAUNCH"

    // Must match android:process of CrashActivity in AndroidManifest.xml
    const val CRASH_PROCESS_SUFFIX = ":crash"

    // ---------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------

    // Activity preferences of EditorActivity: the document that was open last
    const val PREF_CURRENT_OPENED_URI = "current_opened_uri"

    // Keys of res/xml/pref_app.xml
    const val PREF_APP_DARK_THEME = "pref_app_dark_theme"
    const val PREF_APP_PURE_BLACK = "pref_app_pure_black"
    const val PREF_APP_DYNAMIC_COLOR = "pref_app_dynamic_color"
    const val PREF_APP_EDITOR = "pref_app_editor"
    const val PREF_APP_SOURCE_CODE = "pref_app_source_code"
    const val PREF_APP_REPORT_ISSUE = "pref_app_report_issue"
    const val PREF_APP_RELEASES = "pref_app_releases"
    const val PREF_APP_LICENSE = "pref_app_license"
    const val PREF_APP_ABOUT = "pref_app_about"

    // Keys of res/xml/pref_editor.xml
    const val PREF_EDITOR_HIGHLIGHT_THEME = "pref_editor_highlight_theme"
    const val PREF_EDITOR_FONT = "pref_editor_font"
    const val PREF_EDITOR_TEXTSIZE = "pref_editor_textsize"
    const val PREF_EDITOR_TAB_SIZE = "pref_editor_tab_size"
    const val PREF_EDITOR_WORDWRAP = "pref_editor_wordwrap"
    const val PREF_EDITOR_LINE_NUMBERS = "pref_editor_line_numbers"
    const val PREF_EDITOR_GUTTER = "pref_editor_gutter"
    const val PREF_EDITOR_INDENT_GUIDES = "pref_editor_indent_guides"
    const val PREF_EDITOR_CODE_BLOCK_BACKGROUND = "pref_editor_code_block_background"
    const val PREF_EDITOR_COLOR_PREVIEW = "pref_editor_color_preview"
    const val PREF_EDITOR_SYNTAX_HIGHLIGHT = "pref_editor_syntax_highlight"
    const val PREF_EDITOR_AUTO_PAIRS = "pref_editor_auto_pairs"
    const val PREF_EDITOR_BUFFER_COMPLETION = "pref_editor_buffer_completion"
    const val PREF_EDITOR_SNIPPET_COMPLETION = "pref_editor_snippet_completion"
    const val PREF_EDITOR_FORMAT_ON_PASTE = "pref_editor_format_on_paste"
    const val PREF_EDITOR_AUTO_PAIR_CHARS = "pref_editor_auto_pair_chars"
    const val PREF_EDITOR_EXTRA_KEYS = "pref_editor_extra_keys"
    // the arrow keys of the row of extra keys shown (true) or folded away; see ExtraKeysController
    const val PREF_EXTRA_KEYS_ARROWS_EXPANDED = "pref_extra_keys_arrows_expanded"
    const val PREF_EDITOR_FOLDING = "pref_editor_folding"
    const val PREF_EDITOR_BRACKET_MATCHING = "pref_editor_bracket_matching"
    // seconds between two checks of the file shown for changes by other apps; 0: no
    // timed checks (see EditorActivity.checkDisk, EditorSettings.getDiskCheckIntervalMillis)
    const val PREF_EDITOR_DISK_CHECK_INTERVAL = "pref_editor_disk_check_interval"

    // The repository of the app on GitHub
    const val GITHUB_REPOSITORY = "lzhiyong/xedit-app"
    const val GITHUB_URL = "https://github.com/$GITHUB_REPOSITORY"

    // The night mode (an AppCompatDelegate.MODE_NIGHT_* value) of earlier versions,
    // which stored it as an int. Only read, to carry the choice over.
    const val PREF_APP_NIGHT_MODE = "pref_app_night_mode"

    // Not settings: the highlight theme the editor screen was shown with last, and
    // whether it is dark, so that the screen starts light or dark as that theme is
    // before the theme file is read (see AppSettings.isHighlightThemeDark)
    const val PREF_UI_THEME_NAME = "ui_highlight_theme_name"
    const val PREF_UI_THEME_DARK = "ui_highlight_theme_dark"

    // The value of PREF_EDITOR_HIGHLIGHT_THEME that follows the app theme
    const val HIGHLIGHT_THEME_AUTO = "auto"

    // The text size of the editor, in sp: the default, and the range of the setting
    const val EDITOR_TEXTSIZE_DEFAULT = 15f
    const val EDITOR_TEXTSIZE_MIN = 8f
    const val EDITOR_TEXTSIZE_MAX = 32f

    // ---------------------------------------------------------------------
    // Editor
    // ---------------------------------------------------------------------

    // The highlight themes preferred while the highlight theme follows the app theme
    // (see AppSettings.resolveHighlightTheme): for the dark one and the light one
    const val TREE_SITTER_STYLE = "astrodark"
    const val TREE_SITTER_STYLE_LIGHT = "astrolight"
}
