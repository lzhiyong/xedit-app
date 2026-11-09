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

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.work.WorkInfo
import androidx.work.WorkManager

import java.util.UUID

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.preference.CardPreferenceStyler
import x.editor.app.preference.Status
import x.editor.app.preference.StatusHeaderPreference
import x.editor.app.preference.StatusPreference
import x.editor.app.treesitter.TreeSitterDiagnostics
import x.editor.app.treesitter.TreeSitterDiagnostics.Step
import x.editor.app.treesitter.TreeSitterUpdateWorker

/**
 * The tree-sitter resources at a glance (see TreeSitterDiagnostics):
 *
 *  - a card at the top that says whether all is well, or how many problems there
 *    are, with the numbers of file types, themes, grammars and queries;
 *  - the packages: installed (with the version), missing, or waiting for the next
 *    start;
 *  - a few sample files, each with how far it gets on the way to its highlighting,
 *    or the step where it stops;
 *  - the themes, libraries and query folders in detail;
 *  - copy the report (for a bug report), check for updates now, check again.
 */
class ResourcesStatusFragment : BaseSettingsFragment() {

    override val preferencesRes: Int = 0

    override val titleRes: Int = R.string.pref_editor_resources_status_title

    private var report: TreeSitterDiagnostics.Report? = null
    private var loadJob: Job? = null

    override fun onPreferencesCreated(savedInstanceState: Bundle?) {
        // the card while the files are read
        preferenceScreen.addPreference(header().apply {
            loading = true
            status = Status.PENDING
            headline = getString(R.string.resources_status_loading)
        })
        load()
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        val context = requireContext().applicationContext
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { TreeSitterDiagnostics.report(context) }
            report = result
            show(result)
        }
    }

    private fun header() = StatusHeaderPreference(preferenceManager.context).apply { key = KEY_HEADER }

    // #region the screen

    private fun show(report: TreeSitterDiagnostics.Report) {
        val screen = preferenceScreen
        screen.removeAll()

        // the card at the top
        val problems = report.problemCount
        val pending = report.packages.any { it.staged != null }
        screen.addPreference(header().apply {
            status = when {
                problems > 0 -> Status.PROBLEM
                pending -> Status.PENDING
                else -> Status.OK
            }
            headline = when {
                problems > 0 -> resources.getQuantityString(R.plurals.resources_status_problems, problems, problems)
                pending -> getString(R.string.resources_status_pending)
                else -> getString(R.string.resources_status_ok)
            }
            subtitle = report.manifestProblem ?: "${report.root.substringAfterLast("/files/")} · ${report.arch}"
            stats = intArrayOf(
                report.fileTypes ?: 0,
                report.themes.size,
                report.libraries.size,
                report.queryLanguages.size
            )
        })

        // the packages
        category(screen, R.string.resources_category_packages) { category ->
            report.packages.forEach { pkg ->
                category.addPreference(row(
                    title = packageTitle(pkg.id, report.arch),
                    summary = when {
                        pkg.staged != null -> getString(R.string.resources_package_staged)
                        pkg.version != null -> getString(R.string.resources_package_installed, shortVersion(pkg.version)) +
                            pkg.entries.takeIf { it.isNotEmpty() }?.let { "\n" + it.joinToString("  ") }.orEmpty()
                        else -> getString(R.string.resources_package_missing)
                    },
                    icon = packageIcon(pkg.id),
                    status = when {
                        pkg.staged != null -> Status.PENDING
                        pkg.version != null -> Status.OK
                        else -> Status.PROBLEM
                    }
                ))
            }
        }

        // the sample files
        category(screen, R.string.resources_category_files) { category ->
            report.fileTypesProblem?.let { problem ->
                category.addPreference(row(
                    title = "filetypes.json",
                    summary = problem,
                    icon = R.drawable.ic_file,
                    status = Status.PROBLEM
                ))
            }
            report.samples.forEach { sample ->
                category.addPreference(row(
                    title = sample.fileName,
                    summary = when (sample.failed) {
                        Step.FILE_TYPE -> getString(R.string.resources_file_no_type)
                        Step.LIBRARY -> getString(R.string.resources_file_no_library, sample.symbol)
                        Step.QUERIES -> getString(R.string.resources_file_no_queries, sample.symbol)
                        null -> "${sample.library}  ·  queries/${sample.queries}"
                    },
                    icon = R.drawable.ic_file,
                    status = if (sample.failed == null) Status.OK else Status.PROBLEM
                ))
            }
        }

        // in detail
        category(screen, R.string.resources_category_details) { category ->
            category.addPreference(detail(R.string.resources_detail_themes, report.themes, R.drawable.ic_format_paint))
            category.addPreference(detail(
                R.string.resources_detail_libraries,
                report.libraries.map { it.removePrefix("lib").removeSuffix(".so") },
                R.drawable.ic_data_object
            ))
            category.addPreference(detail(R.string.resources_detail_queries, report.queryLanguages, R.drawable.ic_format_list_numbered))
        }

        // what can be done
        category(screen, R.string.resources_category_actions) { category ->
            category.addPreference(action(R.string.resources_action_refresh, R.string.resources_action_refresh_summary, R.drawable.ic_restart) {
                load()
            })
            category.addPreference(action(R.string.resources_action_check_updates, R.string.resources_action_check_updates_summary, R.drawable.ic_download) {
                followUpdate(TreeSitterUpdateWorker.checkNow(requireContext()))
                showMessage(getString(R.string.resources_update_started))
            }.apply {
                key = KEY_CHECK_UPDATES
                // a check going on: what it is at, and not a second one meanwhile
                updateStatus?.let {
                    summary = it
                    isEnabled = false
                }
            })
            category.addPreference(action(R.string.resources_action_copy_report, R.string.resources_action_copy_report_summary, R.drawable.ic_content_copy) {
                copyReport()
            })
        }

        // the cards, as on every screen of the settings
        CardPreferenceStyler.apply(screen)
    }

    private inline fun category(
        screen: androidx.preference.PreferenceScreen,
        @StringRes title: Int,
        fill: (PreferenceCategory) -> Unit
    ) {
        val category = PreferenceCategory(preferenceManager.context).apply {
            setTitle(title)
            isIconSpaceReserved = false
        }
        // in the screen first: a preference must be there before it takes others
        screen.addPreference(category)
        fill(category)
    }

    private fun row(title: CharSequence, summary: CharSequence, @DrawableRes icon: Int, status: Status) =
        StatusPreference(preferenceManager.context).apply {
            this.title = title
            this.summary = summary
            setIcon(icon)
            this.status = status
            isSelectable = false
        }

    // a list of names: how many, and the first of them
    private fun detail(@StringRes title: Int, names: List<String>, @DrawableRes icon: Int) =
        Preference(preferenceManager.context).apply {
            setTitle(title)
            summary = if (names.isEmpty()) {
                getString(R.string.resources_detail_none)
            } else {
                val shown = names.take(DETAIL_PREVIEW).joinToString(", ")
                val more = names.size - DETAIL_PREVIEW
                "${names.size}  ·  $shown" + if (more > 0) " " + getString(R.string.resources_detail_more, more) else ""
            }
            setIcon(icon)
            isPersistent = false
            isSelectable = false
        }

    private fun action(@StringRes title: Int, @StringRes summary: Int, @DrawableRes icon: Int, run: () -> Unit) =
        Preference(preferenceManager.context).apply {
            setTitle(title)
            setSummary(summary)
            setIcon(icon)
            isPersistent = false
            setOnPreferenceClickListener {
                run()
                true
            }
        }

    // #endregion

    // #region check for updates

    // what the check asked for is at (on its preference); null: none going on
    private var updateStatus: String? = null

    // Follows the check asked for: its progress on the preference, its outcome in a
    // message; the screen is read again when it is over (a package may be staged now).
    private fun followUpdate(id: UUID) {
        setUpdateStatus(getString(R.string.resources_update_waiting))
        WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(id)
            .observe(viewLifecycleOwner) { info ->
                info ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        setUpdateStatus(getString(R.string.resources_update_waiting))
                    WorkInfo.State.RUNNING -> {
                        val pkg = info.progress.getString(TreeSitterUpdateWorker.KEY_PACKAGE)
                        setUpdateStatus(
                            if (pkg == null) {
                                getString(R.string.resources_update_checking)
                            } else {
                                getString(
                                    R.string.resources_update_downloading,
                                    pkg, info.progress.getInt(TreeSitterUpdateWorker.KEY_PROGRESS, 0)
                                )
                            }
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        setUpdateStatus(null)
                        val found = info.outputData.getInt(TreeSitterUpdateWorker.KEY_FOUND, 0)
                        val downloaded = info.outputData.getInt(TreeSitterUpdateWorker.KEY_DOWNLOADED, 0)
                        showMessage(
                            if (found == 0) getString(R.string.resources_update_none)
                            else getString(R.string.resources_update_done, downloaded)
                        )
                        load()
                    }
                    WorkInfo.State.FAILED -> {
                        setUpdateStatus(null)
                        val error = info.outputData.getString(TreeSitterUpdateWorker.KEY_ERROR)
                            ?: getString(R.string.resources_update_unknown_error)
                        showMessage(getString(R.string.resources_update_failed, error))
                        load()
                    }
                    WorkInfo.State.CANCELLED -> setUpdateStatus(null)
                }
            }
    }

    private fun setUpdateStatus(status: String?) {
        updateStatus = status
        val preference = findPreference<Preference>(KEY_CHECK_UPDATES) ?: return
        preference.summary = status ?: getString(R.string.resources_action_check_updates_summary)
        preference.isEnabled = status == null
    }

    // #endregion

    private fun packageTitle(id: String, arch: String): String = when (id) {
        "configs" -> getString(R.string.resources_package_configs)
        "languages" -> getString(R.string.resources_package_languages, arch)
        "queries" -> getString(R.string.resources_package_queries)
        "snippets" -> getString(R.string.resources_package_snippets)
        else -> id
    }

    @DrawableRes
    private fun packageIcon(id: String): Int = when (id) {
        "configs" -> R.drawable.ic_palette
        "languages" -> R.drawable.ic_code
        "queries" -> R.drawable.ic_search
        else -> R.drawable.ic_download
    }

    // an ETag as a short version: no quotes, no W/, the first characters
    private fun shortVersion(version: String): String =
        version.removePrefix("W/").trim('"').let { if (it.length > 12) it.take(12) + "…" else it }

    private fun copyReport() {
        val context = context ?: return
        val text = report?.let { TreeSitterDiagnostics.text(it) } ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("tree-sitter", text))
        showMessage(getString(R.string.resources_report_copied))
    }

    private companion object {
        const val KEY_HEADER = "resources_status_header"
        const val KEY_CHECK_UPDATES = "resources_check_updates"
        const val DETAIL_PREVIEW = 6
    }
}
