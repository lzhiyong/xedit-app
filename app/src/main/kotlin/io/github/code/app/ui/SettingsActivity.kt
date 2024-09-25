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

package io.github.code.app.ui

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

import io.github.code.app.R
import io.github.code.app.common.AppSettings
import io.github.code.app.common.JsonParser
import io.github.code.app.preference.MaterialListPreference
import io.github.code.app.common.BaseAdapter

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class Project(
    val name: String,
    val license: String,
    val hyperlink: String,
    val describe: String
)

class SettingsActivity : BaseActivity() {
    
    private val LOG_TAG = this::class.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)
        
        if (savedInstanceState == null) {
            setPreferenceFragment(AppSettingsFragment())
        }

        findViewById<Toolbar>(R.id.toolbar)?.let {
            setSupportActionBar(it)
        }

        getSupportActionBar()?.let { actionbar ->
            actionbar.setDisplayHomeAsUpEnabled(true)
            actionbar.setDisplayShowHomeEnabled(true)
        }
    }
    
    fun setPreferenceFragment(
        targetFragment: PreferenceFragmentCompat
    ) {
        getSupportFragmentManager().beginTransaction().apply {
            addToBackStack(targetFragment::class.simpleName)
            setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.slide_in_left,
                android.R.anim.slide_out_right
            )
            replace(
                R.id.fragment_container, 
                targetFragment
            )
            commit()
        }
    }
    
    override fun onBackKeyPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 1 ){
            getSupportFragmentManager().popBackStackImmediate()
        } else {
            this@SettingsActivity.finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackKeyPressed()
        return true
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
                setupPreferenceCopyright()
                return@setOnPreferenceClickListener true
            }
        }
        
        private fun setupPreferenceCopyright() {
            val contentView = LayoutInflater
                .from(context)
                .inflate(R.layout.bottom_sheet_pref_copyright, null)

            val dragHandle = contentView.findViewById<View>(R.id.drag_handle)
            // init the RecyclerView
            contentView.findViewById<RecyclerView>(R.id.recyclerview_pref_copyright).apply {
                post {
                    with(layoutParams as MarginLayoutParams) {
                        // set the margin top 
                        topMargin = dragHandle.getHeight()
                    }
                    requestLayout()
                }
                
                addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))

                layoutManager = LinearLayoutManager(context)
                adapter = BaseAdapter(getDatas(), R.layout.recyclerview_item_copyright_list).apply {
                    onBindView = { holder, datas, position ->
                        val project = datas[position] as Project
                        holder.getView<TextView>(R.id.project_name).setText(project.name)
                        holder.getView<TextView>(R.id.project_license).setText(project.license)
                        holder.getView<TextView>(R.id.project_describe).setText(project.describe)
                    }

                    onItemClick = { _, datas, position ->
                        val project = datas[position] as Project
                        val intent = Intent(Intent.ACTION_VIEW)
                        intent.setData(Uri.parse(project.hyperlink))
                        context.startActivity(intent)
                    }
                }
            }

            val bottomSheetDialog = BottomSheetDialog(context!!)
            bottomSheetDialog.setContentView(contentView)
            val behavior = BottomSheetBehavior.from(
                contentView.parent as View
            )
            //behavior.state = BottomSheetBehavior.STATE_EXPANDED

            bottomSheetDialog.show()
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

        // get copyright datas
        fun getDatas(): MutableList<Project> {
            
            val listDatas = mutableListOf<Project>()
            
            val input = context!!.resources.assets.open("licenses.json")
            
            JsonParser.parse(input) { _, jsonElement ->
                jsonElement.jsonObject["projects"]?.jsonArray?.map {
                    Json.decodeFromJsonElement<Project>(it)
                }?.forEach(listDatas::add)
            }
            
            return listDatas
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
            
            activity.showDialog(
                dialogTitle = getString(R.string.pref_editor_textsize_title),
                dialogView = contentView,
                neutralText = getString(R.string.dialog_neutral_button_text),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                neutralCallback = {
                },
                positiveCallback = {}
            )
        }
    }
}
