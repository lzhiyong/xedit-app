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

import android.Manifest
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import java.io.IOException

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.highlight.HighlightThemeFiles
import x.editor.app.preference.RadioListPreference
import x.editor.app.util.Downloads
import x.github.module.editor.input.AutoPair

/**
 * The editor settings: highlight theme, font, font size, tab width, word wrap,
 * line numbers, the fixed gutter, indent guides, code folding and matching brackets.
 * They are only stored here; EditorActivity applies what changed when it is
 * shown again (see EditorSettings).
 *
 * Highlight themes can be imported from the storage of the device and the one in use
 * exported to the Download directory (see HighlightThemeFiles): the two buttons under
 * the list of themes. Each says how it went in a toast.
 */
class EditorSettingsFragment : BaseSettingsFragment() {

    override val preferencesRes: Int = R.xml.pref_editor

    override val titleRes: Int = R.string.settings_editor_title

    private var textSizePreference: Preference? = null
    private var autoPairCharsPreference: Preference? = null
    private var extraKeysPreference: Preference? = null

    // the system file picker, for a font from the storage of the device
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFont(it) }
    }

    // the system file picker, for a highlight theme from the storage of the device
    private val pickTheme = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importTheme(it) }
    }

    // Android 9 and below: the permission to write the theme into the Download directory
    private val requestWritePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exportTheme() else toast(getString(R.string.pref_editor_highlight_theme_export_permission))
    }

    override fun onPreferencesCreated(savedInstanceState: Bundle?) {
        setUpHighlightThemes()
        setUpFonts()

        findPreference<Preference>(PREF_RESOURCES_STATUS)?.apply {
            widgetLayoutResource = R.layout.pref_widget_chevron
            setOnPreferenceClickListener {
                (activity as? x.editor.app.activity.SettingsActivity)?.openScreen(ResourcesStatusFragment())
                true
            }
        }

        autoPairCharsPreference = findPreference<Preference>(Constants.PREF_EDITOR_AUTO_PAIR_CHARS)?.apply {
            setOnPreferenceClickListener {
                showAutoPairsDialog()
                true
            }
        }
        updateAutoPairsSummary()

        extraKeysPreference = findPreference<Preference>(Constants.PREF_EDITOR_EXTRA_KEYS)?.apply {
            setOnPreferenceClickListener {
                ExtraKeysDialog(requireContext(), layoutInflater) { updateExtraKeysSummary() }.show()
                true
            }
        }
        updateExtraKeysSummary()

        textSizePreference = findPreference<Preference>(Constants.PREF_EDITOR_TEXTSIZE)?.apply {
            setOnPreferenceClickListener {
                showTextSizeDialog()
                true
            }
        }
        updateTextSizeSummary()
    }

    override fun onResume() {
        super.onResume()
        // themes may have been downloaded meanwhile
        setUpHighlightThemes()
    }

    // #region auto pairs

    // the pairs as they are set, or "none"
    private fun updateAutoPairsSummary() {
        val pairs = AutoPair.parse(EditorSettings.getAutoPairChars(requireContext()))
        autoPairCharsPreference?.summary = if (pairs.isEmpty()) {
            getString(R.string.pref_editor_auto_pair_chars_none)
        } else {
            AutoPair.format(pairs)
        }
    }

    // The pairs typed together, in a text field: two chars a pair. What is not a pair
    // is pointed out under the field and nothing is kept; "default" puts the
    // default pairs into the field.
    private fun showAutoPairsDialog() {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_auto_pairs, null)
        val field = view.findViewById<TextInputLayout>(R.id.pairs_layout)
        val input = view.findViewById<TextInputEditText>(R.id.pairs_input)
        input.setText(EditorSettings.getAutoPairChars(context))
        input.setSelection(input.text?.length ?: 0)
        input.doAfterTextChanged { field.error = null }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_editor_auto_pair_chars_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pref_editor_auto_pair_chars_reset, null)
            .create()
        dialog.setOnShowListener {
            // the buttons are taken over, so that a wrong entry keeps the dialog open
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString().orEmpty()
                val invalid = EditorSettings.invalidAutoPairs(text)
                if (invalid.isNotEmpty()) {
                    field.error = getString(R.string.pref_editor_auto_pair_chars_invalid, invalid.joinToString(" "))
                    return@setOnClickListener
                }
                EditorSettings.setAutoPairChars(context, AutoPair.format(AutoPair.parse(text)))
                updateAutoPairsSummary()
                dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                input.setText(getString(R.string.pref_editor_auto_pair_chars_default))
                input.setSelection(input.text?.length ?: 0)
            }
        }
        dialog.show()
    }

    // #endregion

    // #region extra keys

    // the symbols of the row of extra keys, in their order, or "none"
    private fun updateExtraKeysSummary() {
        val keys = EditorSettings.getExtraKeys(requireContext())
        extraKeysPreference?.summary = if (keys.isEmpty()) {
            getString(R.string.pref_editor_extra_keys_none)
        } else {
            keys.joinToString("  ")
        }
    }

    // #endregion

    // #region font

    // The fonts of the system, of the app (res/font) and the imported ones; each
    // shows a line of code in itself. Imported ones can be removed, and "import"
    // takes one from the storage of the device.
    private fun setUpFonts() {
        val preference = findPreference<RadioListPreference>(Constants.PREF_EDITOR_FONT) ?: return
        val context = requireContext()
        updateFontEntries(preference)

        // loaded once per dialog, not once per bind
        val typefaces = HashMap<String, Typeface?>()
        preference.entryDetails = { value ->
            RadioListPreference.EntryDetails(
                preview = FONT_PREVIEW_TEXT,
                previewTypeface = typefaces.getOrPut(value) { EditorFonts.typeface(context, value) },
                removable = EditorFonts.isImported(value)
            )
        }
        preference.onRemoveEntry = { value ->
            EditorFonts.delete(context, value)
            typefaces.remove(value)
            // the font in use is gone: back to the default
            if (preference.value == value && preference.callChangeListener(EditorFonts.DEFAULT)) {
                preference.value = EditorFonts.DEFAULT
            }
            updateFontEntries(preference)
        }
        preference.neutralAction = RadioListPreference.ExtraAction(getString(R.string.pref_editor_font_import)) {
            pickFont.launch(EditorFonts.PICKER_MIME_TYPES)
        }
    }

    private fun updateFontEntries(preference: RadioListPreference) {
        val context = requireContext()
        val values = EditorFonts.allValues(context)
        preference.entries = values.map { EditorFonts.label(context, it) }.toTypedArray()
        preference.entryValues = values.toTypedArray()
        // a font that is not there any more is shown as the default, which the editor uses
        preference.summaryProvider = Preference.SummaryProvider<ListPreference> { list ->
            list.entry ?: getString(R.string.pref_editor_font_system_default)
        }
    }

    private fun importFont(uri: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val value = try {
                withContext(Dispatchers.IO) { EditorFonts.import(context, uri) }
            } catch (e: IOException) {
                Log.w(TAG, "cannot import the font $uri", e)
                showMessage(getString(R.string.pref_editor_font_import_failed))
                return@launch
            }
            val preference = findPreference<RadioListPreference>(Constants.PREF_EDITOR_FONT) ?: return@launch
            updateFontEntries(preference)
            // the font just imported is the one wanted
            if (preference.callChangeListener(value)) {
                preference.value = value
            }
            showMessage(getString(R.string.pref_editor_font_imported, EditorFonts.label(context, value)))
        }
    }

    // #endregion

    // #region highlight theme

    // The entries are the theme files that were downloaded and the ones imported, after
    // "follow the app theme" (with a line under it: what it does). Before the download
    // there is only that one. A theme shows its colors, and nothing more: no line under the
    // name, no remove button. A theme of the
    // user's is removed in the theme panel (Developer tools → Create theme, ThemeSheet),
    // which asks first and tells the editors. The dialog's buttons: import (neutral), and
    // export of the theme in use (positive).
    private fun setUpHighlightThemes() {
        val preference = findPreference<RadioListPreference>(Constants.PREF_EDITOR_HIGHLIGHT_THEME) ?: return
        val context = requireContext()
        val themes = AppSettings.availableHighlightThemes(context)
        val swatches = HashMap<String, IntArray>()
        preference.entryDetails = { value ->
            if (value == Constants.HIGHLIGHT_THEME_AUTO) RadioListPreference.EntryDetails(
                preview = getString(R.string.pref_editor_highlight_theme_auto_summary)
            )
            else RadioListPreference.EntryDetails(
                swatches = swatches.getOrPut(value) { AppSettings.highlightThemeSwatches(context, value) }
            )
        }
        // the dialog's own buttons: import on the left, export and cancel on the right
        preference.neutralAction = RadioListPreference.ExtraAction(getString(R.string.pref_editor_highlight_theme_import)) {
            pickTheme.launch(HighlightThemeFiles.PICKER_MIME_TYPES)
        }
        preference.action = RadioListPreference.ExtraAction(getString(R.string.pref_editor_highlight_theme_export)) {
            exportTheme()
        }
        preference.entries = (listOf(getString(R.string.pref_editor_highlight_theme_auto)) +
            themes.map { AppSettings.highlightThemeLabel(it) }).toTypedArray()
        preference.entryValues = (listOf(Constants.HIGHLIGHT_THEME_AUTO) + themes).toTypedArray()
        preference.summaryProvider = Preference.SummaryProvider<ListPreference> { list ->
            when {
                themes.isEmpty() -> getString(R.string.pref_editor_highlight_theme_none)
                // a theme that is not there any more: the auto one is used instead
                list.entry == null -> getString(R.string.pref_editor_highlight_theme_auto)
                else -> list.entry
            }
        }
    }

    // The default highlight theme (follow the app theme), as if it had been picked.
    private fun useDefaultHighlightTheme(preference: RadioListPreference) {
        val defaultTheme = getString(R.string.pref_editor_highlight_theme_default)
        if (preference.value != defaultTheme && preference.callChangeListener(defaultTheme)) {
            preference.value = defaultTheme
        }
    }

    // The theme at [uri] imported and picked. If it cannot be read, or is not a theme,
    // nothing is kept and the default theme is used; a toast says which and why.
    private fun importTheme(uri: Uri) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val name = try {
                withContext(Dispatchers.IO) { HighlightThemeFiles.import(context, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cannot import the theme $uri", e)
                findPreference<RadioListPreference>(Constants.PREF_EDITOR_HIGHLIGHT_THEME)?.let {
                    useDefaultHighlightTheme(it)
                }
                val reason = (e as? HighlightThemeFiles.ImportException)?.message
                    ?: getString(R.string.theme_import_error_read)
                toast(getString(R.string.pref_editor_highlight_theme_import_failed, reason))
                return@launch
            }
            // the list again, with the new theme (or the new version of it, its colors too)
            setUpHighlightThemes()
            val preference = findPreference<RadioListPreference>(Constants.PREF_EDITOR_HIGHLIGHT_THEME)
            // the theme just imported is the one wanted
            if (preference != null && preference.callChangeListener(name)) {
                preference.value = name
            }
            toast(getString(R.string.pref_editor_highlight_theme_imported_message, AppSettings.highlightThemeLabel(name)))
        }
    }

    // The file of the theme in use (with "follow the app theme", the one it follows now)
    // written to the Download directory, as it is; a toast says where.
    private fun exportTheme() {
        val context = requireContext().applicationContext
        if (Downloads.needsPermission(context)) {
            requestWritePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val name = AppSettings.resolveHighlightTheme(context, AppSettings.isAppNightMode(context))
        if (HighlightThemeFiles.fileOf(context, name) == null) {
            toast(getString(R.string.pref_editor_highlight_theme_export_none))
            return
        }
        lifecycleScope.launch {
            try {
                val path = withContext(Dispatchers.IO) { HighlightThemeFiles.export(context, name) }
                toast(getString(R.string.pref_editor_highlight_theme_exported, AppSettings.highlightThemeLabel(name), path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // IOException, SecurityException (no permission), ...
                Log.w(TAG, "cannot export the theme $name", e)
                toast(getString(R.string.pref_editor_highlight_theme_export_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // a toast, which stays a little longer: it may have a path in it
    private fun toast(message: CharSequence) {
        val context = context?.applicationContext ?: return
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // #endregion

    // #region text size

    private fun updateTextSizeSummary() {
        val size = EditorSettings.getTextSize(requireContext())
        textSizePreference?.summary = getString(R.string.pref_editor_textsize_value, size.roundToInt())
    }

    // A slider with a preview of the text in the size picked. Reset goes back to
    // the default size.
    private fun showTextSizeDialog() {
        val context = requireContext()
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_text_size, null)
        val preview = content.findViewById<TextView>(R.id.preview)
        val slider = content.findViewById<Slider>(R.id.slider)

        // in the font chosen, as the editor will show it
        val font = AppSettings.preferences(context).getString(Constants.PREF_EDITOR_FONT, null)
            ?: EditorFonts.DEFAULT
        preview.typeface = EditorFonts.typeface(context, font) ?: Typeface.DEFAULT
        preview.text = PREVIEW_TEXT
        slider.valueFrom = Constants.EDITOR_TEXTSIZE_MIN
        slider.valueTo = Constants.EDITOR_TEXTSIZE_MAX
        slider.stepSize = 1f
        slider.value = EditorSettings.getTextSize(context).roundToInt().toFloat()
            .coerceIn(slider.valueFrom, slider.valueTo)
        slider.setLabelFormatter { value -> getString(R.string.pref_editor_textsize_value, value.roundToInt()) }
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, slider.value)
        slider.addOnChangeListener { _, value, _ ->
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pref_editor_textsize_title)
            .setIcon(R.drawable.ic_text_fields)
            .setView(content)
            .setNeutralButton(R.string.pref_editor_textsize_reset) { _, _ ->
                EditorSettings.setTextSize(context, null)
                updateTextSizeSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                EditorSettings.setTextSize(context, slider.value)
                updateTextSizeSummary()
            }
            .show()
    }

    // #endregion

    private companion object {
        const val TAG = "EditorSettingsFragment"
        const val PREF_RESOURCES_STATUS = "pref_editor_resources_status"
        const val PREVIEW_TEXT = "fun main() {\n    println(\"Hello, XEdit\")\n}"
        const val FONT_PREVIEW_TEXT = "fun main() { 0O 1lI {}[] => }"
    }
}
