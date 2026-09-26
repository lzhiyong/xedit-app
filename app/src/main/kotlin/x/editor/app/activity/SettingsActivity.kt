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

package x.editor.app.activity

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import x.editor.app.R
import x.editor.app.AppSettings
import x.editor.app.preference.MaterialListPreference


class SettingsActivity : BaseActivity() {
    
    private val LOG_TAG = this::class.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)
        
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, AppSettingsFragment())
                .commit()
        }

        findViewById<Toolbar>(R.id.toolbar)?.let {
            setSupportActionBar(it)
        }

        getSupportActionBar()?.let {
            it.setDisplayHomeAsUpEnabled(true)
            it.setDisplayShowHomeEnabled(true)
        }
    }
    
    fun setPreferenceFragment(
        targetFragment: PreferenceFragmentCompat
    ) {
        getSupportFragmentManager()
            .beginTransaction()                  
            .replace(
                R.id.fragment_container, 
                targetFragment
            )
            .setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
            .addToBackStack(null) 
            .commit()
    }
    
    
    // settings preference
    class AppSettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // laod preference resource
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            
            // config the app theme
            (findPreference("pref_app_theme") as? ListPreference)?.let { list ->
                list.setOnPreferenceChangeListener { _, newValue ->
                    val mode = list.findIndexOfValue(newValue as String)
                    AppSettings.applyAppTheme(mode)
                    activity?.recreate()
                    return@setOnPreferenceChangeListener true
                }
            }
            
            // setting the bottom sheet copyright
            (findPreference("pref_app_editor") as? Preference)?.setOnPreferenceClickListener {
                (getActivity() as? SettingsActivity)?.let { activity ->
                    activity.setPreferenceFragment(EditorSettingsFragment())
                }
                return@setOnPreferenceClickListener true
            }
            
            // setting the bottom sheet copyright
            (findPreference("pref_app_copyright") as? Preference)?.setOnPreferenceClickListener {
                
                return@setOnPreferenceClickListener true
            }
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is ListPreference) {
                val bundle = Bundle().apply {
                    putString("key", preference.getKey())
                }

                MaterialListPreference().apply {
                    setArguments(bundle)
                    setTargetFragment(this@AppSettingsFragment, 0)
                    show(
                        this@AppSettingsFragment.parentFragmentManager,
                        "androidx.preference.PreferenceFragment.DIALOG"
                    )
                }
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }
    }
    
    class EditorSettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // laod preference resource
            setPreferencesFromResource(R.xml.editor_preferences, rootKey)
            
            // set the editor text size
            (findPreference("pref_editor_textsize") as? Preference)?.setOnPreferenceClickListener {                
                (getActivity() as? SettingsActivity)?.let { activity ->
                    showSettingTextsizeDialog(activity)
                }
                return@setOnPreferenceClickListener true
            }
            
            // set the editor grammars
            (findPreference("pref_editor_grammar") as? Preference)?.setOnPreferenceClickListener {                
                (getActivity() as? SettingsActivity)?.let { activity ->
                    showSettingTextsizeDialog(activity)
                }
                return@setOnPreferenceClickListener true
            }
        }
        
        fun showSettingTextsizeDialog(activity: SettingsActivity) {
            val contentView = LayoutInflater
                .from(context)
                .inflate(R.layout.dialog_slider_textsize, null)
            
            activity.createDialog(
                dialogTitle = getString(R.string.pref_editor_textsize_title),
                dialogView = contentView,
                neutralButtonText = getString(R.string.dialog_button_neutral),
                positiveButtonText = getString(android.R.string.ok),
                negativeButtonText = getString(android.R.string.cancel),
                onNeutralCallback = {
                },
                onPositiveCallback = {}
            ).show()
        }
    }
}
