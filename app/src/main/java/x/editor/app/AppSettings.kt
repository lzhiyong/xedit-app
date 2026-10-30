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

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.PowerManager

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager

import x.editor.app.highlight.HighlightThemes
import x.editor.app.treesitter.TreeSitterResources

/**
 * The settings of the app as a whole: the theme (dark or not, pure black, colors
 * from the wallpaper), and the highlight theme of the editor.
 *
 * Everything is in the default shared preferences, under the keys of the settings
 * screens (see [Constants]). The defaults are resources (`res/values/bools.xml`,
 * `@string/pref_*_default`) shared with the preference XMLs, so the screens and
 * this class agree on them.
 */
object AppSettings {

    fun preferences(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    // #region dark theme

    /** Applies an `AppCompatDelegate.MODE_NIGHT_*` value. Started activities are recreated by AppCompat. */
    fun applyNightMode(mode: Int) = AppCompatDelegate.setDefaultNightMode(mode)

    /** Applies the theme chosen in the settings; follows the system if none was chosen yet. */
    fun applySavedTheme(context: Context) {
        applyNightMode(getNightMode(context))
    }

    /**
     * The night mode chosen in the settings, `MODE_NIGHT_FOLLOW_SYSTEM` if none. The
     * value of the "dark theme" list is a `MODE_NIGHT_*` value itself; earlier versions
     * stored the mode as an int under another key, which is still read.
     */
    fun getNightMode(context: Context): Int {
        val preferences = preferences(context)
        preferences.getString(Constants.PREF_APP_DARK_THEME, null)?.toIntOrNull()?.let {
            return sanitizeNightMode(it)
        }
        val legacy = try {
            preferences.getInt(Constants.PREF_APP_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } catch (e: ClassCastException) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        return sanitizeNightMode(legacy)
    }

    /** Remembers the night mode [mode] (a `MODE_NIGHT_*` value) and applies it. */
    fun setNightMode(context: Context, mode: Int) {
        val sanitized = sanitizeNightMode(mode)
        preferences(context).edit {
            putString(Constants.PREF_APP_DARK_THEME, sanitized.toString())
        }
        applyNightMode(sanitized)
    }

    private fun sanitizeNightMode(mode: Int): Int = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES,
        AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY -> mode
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * Whether the *app* theme is dark right now: the night mode of the settings, and
     * the system's where it follows the system. Not what an activity is shown with:
     * the editor screen is light or dark as its highlight theme is (see
     * EditorActivity.attachBaseContext), so its own configuration does not say this.
     */
    fun isAppNightMode(context: Context): Boolean = when (AppCompatDelegate.getDefaultNightMode()) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        // dark while the battery saver is on, as AppCompat has it
        AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY ->
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        else -> (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // #endregion

    // #region colors

    /** Whether the dark theme is pure black (laid over the dark theme only). */
    fun isPureBlack(context: Context): Boolean = preferences(context).getBoolean(
        Constants.PREF_APP_PURE_BLACK,
        context.resources.getBoolean(R.bool.pref_app_pure_black_default)
    )

    /** Whether the device can take the colors of the app from the wallpaper (Android 12+). */
    val isDynamicColorSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Whether the colors of the app come from the wallpaper (where the device can do that). */
    fun isDynamicColor(context: Context): Boolean = isDynamicColorSupported &&
        preferences(context).getBoolean(
            Constants.PREF_APP_DYNAMIC_COLOR,
            context.resources.getBoolean(R.bool.pref_app_dynamic_color_default)
        )

    /**
     * What an activity is themed with, beyond the night mode (which AppCompat takes
     * care of): when this changes, an activity that was themed before is recreated
     * (see BaseActivity). Pure black only counts in the dark theme.
     */
    fun themeSignature(context: Context, isNightMode: Boolean): Int =
        (if (isNightMode && isPureBlack(context)) 1 else 0) or (if (isDynamicColor(context)) 2 else 0)

    /** The theme overlay [activity] needs on top of its theme, 0 for none. */
    fun themeOverlayFor(activity: Activity, isNightMode: Boolean): Int =
        if (isNightMode && isPureBlack(activity)) R.style.ThemeOverlay_XEdit_PureBlack else 0

    // #endregion

    // #region highlight theme

    /** The highlight themes that were downloaded: the names of `themes/<name>.json`, sorted. */
    fun availableHighlightThemes(context: Context): List<String> =
        TreeSitterResources.themesDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            .orEmpty()

    /**
     * Whether the highlight theme [name] is dark. HighlightThemes knows for sure once
     * the theme was read; before (when the editor screen starts, where nothing may be
     * read from a file yet) what was found out the last time it was read
     * ([rememberHighlightThemeDark]), and only for a theme never read, its name.
     */
    fun isHighlightThemeDark(context: Context, name: String): Boolean {
        if (HighlightThemes.isLoaded(name)) return HighlightThemes.isDark(name)
        val preferences = preferences(context)
        if (preferences.getString(Constants.PREF_UI_THEME_NAME, null) == name &&
            preferences.contains(Constants.PREF_UI_THEME_DARK)
        ) {
            return preferences.getBoolean(Constants.PREF_UI_THEME_DARK, true)
        }
        return HighlightThemes.isDark(name)
    }

    /** Remembers whether the highlight theme [name] turned out to be dark, see [isHighlightThemeDark]. */
    fun rememberHighlightThemeDark(context: Context, name: String, dark: Boolean) {
        val preferences = preferences(context)
        if (preferences.getString(Constants.PREF_UI_THEME_NAME, null) == name &&
            preferences.contains(Constants.PREF_UI_THEME_DARK) &&
            preferences.getBoolean(Constants.PREF_UI_THEME_DARK, true) == dark
        ) {
            return
        }
        preferences.edit {
            putString(Constants.PREF_UI_THEME_NAME, name)
            putBoolean(Constants.PREF_UI_THEME_DARK, dark)
        }
    }

    /** The highlight theme chosen in the settings: a theme name, or [Constants.HIGHLIGHT_THEME_AUTO]. */
    fun getHighlightTheme(context: Context): String =
        preferences(context).getString(Constants.PREF_EDITOR_HIGHLIGHT_THEME, null)
            ?: Constants.HIGHLIGHT_THEME_AUTO

    /**
     * The name of the highlight theme to load: the one chosen, or with "follow the
     * app theme" one that is dark or light like the app theme ([Constants.TREE_SITTER_STYLE]
     * and [Constants.TREE_SITTER_STYLE_LIGHT] first, see HighlightThemes.isDark). With
     * no theme of that kind, any; with no theme at all, [Constants.TREE_SITTER_STYLE]
     * (which is then not there: no highlighting until they are downloaded).
     */
    fun resolveHighlightTheme(context: Context, isNightMode: Boolean): String {
        val available = availableHighlightThemes(context)
        val chosen = getHighlightTheme(context)
        if (chosen != Constants.HIGHLIGHT_THEME_AUTO && chosen in available) {
            return chosen
        }
        val preferred = if (isNightMode) Constants.TREE_SITTER_STYLE else Constants.TREE_SITTER_STYLE_LIGHT
        return when {
            preferred in available -> preferred
            else -> available.firstOrNull { HighlightThemes.isDark(it) == isNightMode }
                ?: available.firstOrNull()
                ?: Constants.TREE_SITTER_STYLE
        }
    }

    /**
     * A few colors of the highlight theme [name], to show it at a glance: its
     * background, then those of keywords, strings, functions, types and numbers.
     * Empty if it cannot be read. Reads the file: not on the main thread.
     */
    fun highlightThemeSwatches(context: Context, name: String, max: Int = 5): IntArray {
        val theme = HighlightThemes.get(context, name) ?: return IntArray(0)
        val picked = LinkedHashSet<Int>()
        theme.editorColors?.background?.let { picked += it }
        for (kind in SWATCH_CAPTURES) {
            if (picked.size >= max) break
            theme.styleFor(kind, null)?.foreground?.let { picked += it }
        }
        return picked.take(max).toIntArray()
    }

    private val SWATCH_CAPTURES = listOf("keyword", "string", "function", "type", "number", "comment", "constant")

    /** A name for the menu: `one_dark` → `One Dark`. */
    fun highlightThemeLabel(name: String): String =
        name.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifEmpty { name }

    // #endregion
}
