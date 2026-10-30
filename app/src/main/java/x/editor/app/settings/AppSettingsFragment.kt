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

import android.net.Uri
import android.os.Bundle

import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

import x.editor.app.AppSettings
import x.editor.app.Constants
import x.editor.app.R
import x.editor.app.activity.SettingsActivity
import x.editor.app.preference.SimpleMenuPreference

/** The first screen of the settings: the app theme, the way to the editor settings, about. */
class AppSettingsFragment : BaseSettingsFragment() {

    override val preferencesRes: Int = R.xml.pref_app

    override val titleRes: Int = R.string.settings_title

    override fun onPreferencesCreated(savedInstanceState: Bundle?) {
        // dark theme: always off, always on, follow the system
        findPreference<SimpleMenuPreference>(Constants.PREF_APP_DARK_THEME)?.apply {
            // carries over the choice of earlier versions, stored elsewhere
            value = AppSettings.getNightMode(requireContext()).toString()
                .takeIf { findIndexOfValue(it) >= 0 }
                ?: value
            setOnPreferenceChangeListener { _, newValue ->
                val mode = (newValue as String).toIntOrNull() ?: return@setOnPreferenceChangeListener false
                // after the menu has faded out; AppCompat recreates the activities itself
                val list = listView
                if (list != null) {
                    list.postDelayed({ AppSettings.applyNightMode(mode) }, THEME_CHANGE_DELAY_MS)
                } else {
                    AppSettings.applyNightMode(mode)
                }
                true
            }
        }

        // pure black, colors from the wallpaper: the activities are themed with them
        // when they are created, so this one is recreated (the others when they are
        // shown again, see BaseActivity)
        findPreference<SwitchPreferenceCompat>(Constants.PREF_APP_PURE_BLACK)
            ?.setOnPreferenceChangeListener { _, _ -> recreateForThemeChange(); true }

        findPreference<SwitchPreferenceCompat>(Constants.PREF_APP_DYNAMIC_COLOR)?.apply {
            isVisible = AppSettings.isDynamicColorSupported
            setOnPreferenceChangeListener { _, _ -> recreateForThemeChange(); true }
        }

        findPreference<Preference>(Constants.PREF_APP_EDITOR)?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.openScreen(EditorSettingsFragment())
            true
        }

        // GitHub: the source, a new issue with the version and the device filled in,
        // and the releases (what is new, and newer versions to download). The source
        // keeps the summary of pref_app.xml, which says what is there, rather than
        // showing the address of the repository.
        findPreference<Preference>(Constants.PREF_APP_SOURCE_CODE)?.setOnPreferenceClickListener {
            openUrl(Constants.GITHUB_URL)
            true
        }
        findPreference<Preference>(Constants.PREF_APP_REPORT_ISSUE)?.setOnPreferenceClickListener {
            val url = Uri.parse("${Constants.GITHUB_URL}/issues/new").buildUpon()
                .appendQueryParameter("body", AppInfo.issueBody(requireContext()))
                .build()
                .toString()
            openUrl(url)
            true
        }
        findPreference<Preference>(Constants.PREF_APP_RELEASES)?.setOnPreferenceClickListener {
            openUrl("${Constants.GITHUB_URL}/releases")
            true
        }

        findPreference<Preference>(Constants.PREF_APP_LICENSE)?.setOnPreferenceClickListener {
            (activity as? SettingsActivity)?.openScreen(LicensesFragment())
            true
        }

        findPreference<Preference>(Constants.PREF_APP_ABOUT)?.apply {
            summary = getString(R.string.pref_app_about_version, AppInfo.version(requireContext()))
            setOnPreferenceClickListener {
                if (childFragmentManager.findFragmentByTag(AboutDialogFragment.TAG) == null) {
                    AboutDialogFragment().show(childFragmentManager, AboutDialogFragment.TAG)
                }
                true
            }
        }
    }

    // The value is stored right after the listener returns: let the switch move
    // first, then recreate if what the activity was themed with has changed.
    private fun recreateForThemeChange() {
        val list = listView ?: return
        list.postDelayed({
            (activity as? SettingsActivity)?.recreateIfThemeChanged()
        }, THEME_CHANGE_DELAY_MS)
    }

    private companion object {
        const val THEME_CHANGE_DELAY_MS = 250L
    }
}
