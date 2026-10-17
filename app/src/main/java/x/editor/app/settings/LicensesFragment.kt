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

import android.os.Bundle

import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import x.editor.app.Constants
import x.editor.app.R

/**
 * The licenses: of the app, and of the open source projects it is built on. A tap
 * on one tells who holds it under which license, with the way to the full text of
 * the license and to the project.
 */
class LicensesFragment : BaseSettingsFragment() {

    override val preferencesRes: Int = 0

    override val titleRes: Int = R.string.pref_app_license_title

    private enum class License(@StringRes val title: Int, @StringRes val notice: Int, val url: String) {
        APACHE_2(R.string.license_apache_2, R.string.license_apache_2_notice, "https://www.apache.org/licenses/LICENSE-2.0"),
        MIT(R.string.license_mit, R.string.license_mit_notice, "https://opensource.org/license/mit"),
        OFL(R.string.license_ofl, R.string.license_ofl_notice, "https://openfontlicense.org/open-font-license-official-text/")
    }

    private class Project(
        val name: String,
        val holder: String,
        val license: License,
        val url: String,
        @StringRes val description: Int = 0
    )

    private val app = Project(
        "XEdit", "Github Lzhiyong", License.APACHE_2, Constants.GITHUB_URL
    )

    private val libraries = listOf(
        Project(
            "AndroidX", "The Android Open Source Project", License.APACHE_2,
            "https://developer.android.com/jetpack/androidx", R.string.license_androidx_description
        ),
        Project(
            "Material Components for Android", "The Android Open Source Project", License.APACHE_2,
            "https://github.com/material-components/material-components-android"
        ),
        Project("Kotlin", "JetBrains s.r.o. and Kotlin Programming Language contributors", License.APACHE_2, "https://github.com/JetBrains/kotlin"),
        Project("kotlinx.coroutines", "JetBrains s.r.o. and contributors", License.APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines"),
        Project("kotlinx.serialization", "JetBrains s.r.o. and contributors", License.APACHE_2, "https://github.com/Kotlin/kotlinx.serialization"),
        Project("OkHttp", "Square, Inc.", License.APACHE_2, "https://github.com/square/okhttp"),
        Project("Retrofit", "Square, Inc.", License.APACHE_2, "https://github.com/square/retrofit"),
        Project(
            "tree-sitter", "Max Brunsfeld", License.MIT,
            "https://github.com/tree-sitter/tree-sitter", R.string.license_tree_sitter_description
        ),
        Project(
            "Visual Studio Code", "Microsoft Corporation", License.MIT,
            "https://github.com/microsoft/vscode", R.string.license_vscode_description
        ),
        Project(
            "JetBrains Mono", "The JetBrains Mono Project Authors", License.OFL,
            "https://github.com/JetBrains/JetBrainsMono", R.string.license_jetbrains_mono_description
        ),
        Project(
            "Source Code Pro", "Adobe Systems Incorporated", License.OFL,
            "https://github.com/adobe-fonts/source-code-pro", R.string.license_source_code_pro_description
        )
    )

    override fun onPreferencesCreated(savedInstanceState: Bundle?) {
        val context = preferenceManager.context
        val screen = preferenceScreen

        fun addCategory(@StringRes categoryTitle: Int, projects: List<Project>) {
            val category = PreferenceCategory(context).apply {
                setTitle(categoryTitle)
                isIconSpaceReserved = false
            }
            // a preference has to be on the screen before preferences can go in it
            screen.addPreference(category)
            projects.forEach { project ->
                category.addPreference(Preference(context).apply {
                    key = "license_${project.name}"
                    title = project.name
                    summary = getString(project.license.title)
                    setIcon(R.drawable.ic_license)
                    isPersistent = false
                    setOnPreferenceClickListener {
                        showLicense(project)
                        true
                    }
                })
            }
        }

        addCategory(R.string.license_category_app, listOf(app))
        addCategory(R.string.license_category_libraries, libraries)
    }

    private fun showLicense(project: Project) {
        val message = buildString {
            if (project.description != 0) {
                append(getString(project.description)).append("\n\n")
            }
            append("Copyright © ").append(project.holder).append("\n\n")
            append(getString(project.license.notice))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_license)
            .setTitle(project.name)
            .setMessage(message)
            .setPositiveButton(R.string.license_full_text) { _, _ -> openUrl(project.license.url) }
            .setNeutralButton(R.string.license_project_page) { _, _ -> openUrl(project.url) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }
}
