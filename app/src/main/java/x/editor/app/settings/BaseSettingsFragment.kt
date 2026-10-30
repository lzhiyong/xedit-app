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

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar

import x.editor.app.R
import x.editor.app.activity.SettingsActivity
import x.editor.app.preference.CardPreferenceStyler
import x.editor.app.preference.RadioListPreference
import x.editor.app.preference.RadioListPreferenceDialog

/**
 * A screen of the settings: its preferences from [preferencesRes], styled as cards
 * (see [CardPreferenceStyler]), its title [titleRes] in the large top app bar of
 * [SettingsActivity].
 *
 * The screens are stacked: a screen that is opened is laid over the one before,
 * which stays where it is under it. That is what the back gesture shows: the screen
 * on top follows the finger and fades, and the one under it comes through (see
 * SettingsActivity). So a screen is opaque, and takes the touches on it.
 */
abstract class BaseSettingsFragment : PreferenceFragmentCompat() {

    /** The preferences of the screen; 0 to build them in [onPreferencesCreated]. */
    @get:XmlRes
    protected abstract val preferencesRes: Int

    @get:StringRes
    abstract val titleRes: Int

    /**
     * Where the app bar was for this screen when another one was laid over it (0
     * expanded, below 0 collapsed by that much): it goes back there on the way back
     * (see SettingsActivity).
     */
    var appBarOffset: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appBarOffset = savedInstanceState?.getInt(STATE_APP_BAR_OFFSET, 0) ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_APP_BAR_OFFSET, appBarOffset)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (preferencesRes != 0) {
            setPreferencesFromResource(preferencesRes, rootKey)
        } else {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        }
        onPreferencesCreated(savedInstanceState)
        // after onPreferencesCreated: it may hide preferences, which changes the groups
        CardPreferenceStyler.apply(preferenceScreen)
    }

    /** The preferences are there: listeners, summaries, preferences to hide. */
    protected abstract fun onPreferencesCreated(savedInstanceState: Bundle?)

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
        clipToPadding = false
        // the rows change in place (a switch, a summary): no cross-fade
        itemAnimator = null
        updatePadding(top = resources.getDimensionPixelSize(R.dimen.pref_list_padding_top))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // covers the screen under it, and keeps taps from going through to it
        view.setBackgroundColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface))
        view.isClickable = true

        // the cards are separated by gaps, not by lines
        setDivider(null)

        // edge to edge: the last card scrolls up from under the navigation bar
        val list = listView
        val paddingBottom = resources.getDimensionPixelSize(R.dimen.pref_list_padding_bottom)
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = paddingBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(list)

        (activity as? SettingsActivity)?.onScreenViewCreated(this)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference !is RadioListPreference) {
            super.onDisplayPreferenceDialog(preference)
            return
        }
        // one at a time (a double tap would open two)
        if (parentFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        RadioListPreferenceDialog.newInstance(preference.key).apply {
            // how the dialogs of androidx.preference find their preference
            @Suppress("DEPRECATION")
            setTargetFragment(this@BaseSettingsFragment, 0)
            show(this@BaseSettingsFragment.parentFragmentManager, DIALOG_TAG)
        }
    }

    /**
     * Opens [url] in the browser. Without one, the link is copied, and the user is
     * told so.
     */
    protected fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val context = context ?: return
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(url, url))
            showMessage(getString(R.string.settings_link_copied_no_browser))
        }
    }

    /** A short message at the bottom of the screen. */
    protected fun showMessage(message: CharSequence) {
        val view = view ?: return
        Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val DIALOG_TAG = "x.editor.app.settings.DIALOG"
        const val STATE_APP_BAR_OFFSET = "settings:appBarOffset"
    }
}
