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

package x.editor.app.toolchain

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.editor.app.R
import x.editor.app.lsp.Termux
import java.io.File
import java.io.IOException
import com.google.android.material.R as MaterialR

/**
 * The toolchain (menu → Toolchain), all on one sheet:
 *
 *  - **Termux**: found and allowed, or a button for what it needs. What its packages give is
 *    found by itself (TermuxProbe): you install them in Termux (`pkg install clang`), X
 *    Editor installs nothing there.
 *  - **Use first**: X Editor's own toolchain, Termux's, or (auto) X Editor's when it has
 *    what a command needs.
 *  - **The toolchains of packages.json**, each with what it is and where it is: installed
 *    into X Editor (the installed mark: a menu to reinstall or uninstall), to update (a
 *    newer version in the list), to install (its source, version and SHA-256 come from the
 *    list, nothing is typed in), and whether Termux has its commands. An install shows its
 *    progress in its row. What is installed but no longer in the list is shown too, to be
 *    uninstalled.
 *
 * [openFile] opens a file in the editor (packages.json).
 */
class ToolchainSheet(
    private val activity: Activity,
    private val scope: LifecycleCoroutineScope,
    private val openFile: (File) -> Unit,
) {
    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_toolchain, null)
    private val dialog = BottomSheetDialog(activity)
    private val list = view.findViewById<LinearLayout>(R.id.toolchain_packages)
    private val empty = view.findViewById<TextView>(R.id.toolchain_empty)

    private val good = 0xFF98C379.toInt()
    private val warn = 0xFFE5C07B.toInt()
    private val bad = 0xFFE06C75.toInt()
    private val muted = MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0xFF9E9E9E.toInt())
    private val primary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())

    private val abis = Build.SUPPORTED_ABIS.toList()

    // what is shown: the list, what is installed, what Termux has (null: not known)
    private var catalog: List<ToolPackage> = emptyList()
    private var installed: List<InstalledPackage> = emptyList()
    private var termuxFound: Set<String>? = TermuxProbe.last?.first
    private var probing = false

    // the installs going on (or failed): their state, by id
    private class Progress(var text: String, var percent: Int? = null, var error: String? = null)
    private val installing = LinkedHashMap<String, Progress>()

    // the rows, by id, to update an install's in place
    private val rows = HashMap<String, View>()

    fun show() {
        view.findViewById<View>(R.id.toolchain_refresh).setOnClickListener {
            showTermux()
            reload(probe = true)
        }
        view.findViewById<View>(R.id.toolchain_edit_catalog).setOnClickListener {
            scope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { ToolchainInstaller.catalogFile(activity) }.getOrNull() } ?: return@launch
                dialog.dismiss()
                openFile(file)
            }
        }
        setUpMode()
        dialog.setContentView(view)
        dialog.show()
        showTermux()
        reload(probe = true)
    }

    // #region Termux

    private fun showTermux() {
        val dot = view.findViewById<View>(R.id.toolchain_termux_dot)
        val text = view.findViewById<TextView>(R.id.toolchain_termux_text)
        val action = view.findViewById<MaterialButton>(R.id.toolchain_termux_action)
        fun dot(color: Int) {
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        when {
            !Termux.isInstalled(activity) -> {
                dot(muted)
                text.setText(R.string.toolchain_termux_missing)
                action.setText(R.string.toolchain_termux_get)
                action.visibility = View.VISIBLE
                action.setOnClickListener { openUrl(TERMUX_PAGE) }
            }
            !Termux.hasPermission(activity) -> {
                dot(warn)
                text.setText(R.string.toolchain_termux_no_permission)
                action.setText(R.string.toolchain_termux_allow)
                action.visibility = View.VISIBLE
                action.setOnClickListener {
                    ActivityCompat.requestPermissions(activity, arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
                }
            }
            else -> {
                action.visibility = View.GONE
                val found = termuxFound
                when {
                    probing -> {
                        dot(good)
                        text.setText(R.string.toolchain_termux_checking)
                    }
                    found == null -> {
                        dot(warn)
                        text.setText(R.string.toolchain_termux_unknown)
                    }
                    else -> {
                        dot(good)
                        text.text = activity.getString(R.string.toolchain_termux_found, found.size)
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, url, Toast.LENGTH_LONG).show()
        }
    }

    // asks Termux which commands of the list it has
    private fun probeTermux() {
        val commands = catalog.flatMap { it.commands() }.distinct()
        if (!Termux.isInstalled(activity) || !Termux.hasPermission(activity) || commands.isEmpty()) return
        probing = true
        showTermux()
        TermuxProbe.check(activity, commands) { found ->
            probing = false
            termuxFound = found
            if (!dialog.isShowing) return@check
            showTermux()
            render()
        }
    }

    // #endregion

    // which goes first, with a line about what it means
    private fun setUpMode() {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.toolchain_mode)
        val hint = view.findViewById<TextView>(R.id.toolchain_mode_hint)
        val ids = mapOf(
            RunnerMode.AUTO to R.id.toolchain_mode_auto,
            RunnerMode.LOCAL to R.id.toolchain_mode_local,
            RunnerMode.TERMUX to R.id.toolchain_mode_termux,
        )
        fun describe(mode: RunnerMode) {
            hint.setText(when (mode) {
                RunnerMode.AUTO -> R.string.toolchain_mode_auto_hint
                RunnerMode.LOCAL -> R.string.toolchain_mode_local_hint
                RunnerMode.TERMUX -> R.string.toolchain_mode_termux_hint
            })
        }
        val mode = Toolchain.mode(activity)
        group.check(ids.getValue(mode))
        describe(mode)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val chosen = ids.entries.firstOrNull { it.value == id }?.key ?: return@addOnButtonCheckedListener
            Toolchain.setMode(activity, chosen)
            describe(chosen)
        }
    }

    // #region the list

    // the list and what is installed, read again (and Termux asked again)
    private fun reload(probe: Boolean) {
        scope.launch {
            val problems = ArrayList<String>()
            val (packages, have) = withContext(Dispatchers.IO) {
                val packages = runCatching { ToolchainInstaller.catalog(activity, problems) }.getOrElse { e ->
                    problems += e.message ?: e.toString()
                    emptyList()
                }
                packages to runCatching { ToolchainInstaller.installed(activity) }.getOrDefault(emptyList())
            }
            if (problems.isNotEmpty()) Toast.makeText(activity, "packages.json: " + problems.joinToString("\n"), Toast.LENGTH_LONG).show()
            catalog = packages
            installed = have
            render()
            if (probe) probeTermux()
        }
    }

    // a row for each toolchain of the list, then for what is installed but not listed
    private fun render() {
        list.removeAllViews()
        rows.clear()
        val listed = catalog.map { it.id }.toSet()
        val entries: List<Pair<ToolPackage?, InstalledPackage?>> =
            catalog.map { pkg -> pkg to installed.firstOrNull { it.pkg.id == pkg.id } } +
                installed.filter { it.pkg.id !in listed }.map { null to it }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        for ((pkg, have) in entries) {
            val row = LayoutInflater.from(activity).inflate(R.layout.item_toolchain_package, list, false)
            bind(row, pkg, have)
            rows[pkg?.id ?: have!!.pkg.id] = row
            list.addView(row)
        }
    }

    private fun bind(row: View, pkg: ToolPackage?, have: InstalledPackage?) {
        val id = pkg?.id ?: have!!.pkg.id
        val kind = pkg?.kind ?: have!!.pkg.kind
        val (letter, color) = badge(kind)
        row.findViewById<TextView>(R.id.package_kind).apply {
            text = letter
            setTextColor(color)
            background = GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(ColorUtils.setAlphaComponent(color, 0x33))
            }
        }
        row.findViewById<TextView>(R.id.package_name).text = pkg?.name ?: have!!.pkg.name
        val installable = pkg?.isInstallable(abis) == true
        val newVersion = pkg?.version
        val update = have != null && installable && newVersion != null && newVersion != have.pkg.version
        row.findViewById<TextView>(R.id.package_version).text = when {
            update -> "${have?.pkg?.version ?: "?"} → $newVersion"
            have != null -> have.pkg.version.orEmpty()
            installable -> newVersion.orEmpty()
            else -> ""
        }

        val detail = row.findViewById<TextView>(R.id.package_detail)
        val bar = row.findViewById<LinearProgressIndicator>(R.id.package_progress)
        val action = row.findViewById<MaterialButton>(R.id.package_action)
        val progress = installing[id]
        if (progress != null && progress.error == null) {
            detail.text = progress.text
            detail.setTextColor(muted)
            bar.visibility = View.VISIBLE
            progress.percent?.let {
                if (bar.isIndeterminate) bar.isIndeterminate = false
                bar.setProgressCompat(it, true)
            }
            action.visibility = View.INVISIBLE
            return
        }
        bar.visibility = View.GONE
        detail.text = progress?.error ?: describe(pkg, have, installable)
        detail.setTextColor(if (progress?.error != null) bad else muted)

        action.visibility = View.VISIBLE
        action.isEnabled = true
        when {
            update && pkg != null -> setAction(action, R.drawable.ic_update, warn, R.string.toolchain_update) { install(pkg) }
            have != null -> setAction(action, R.drawable.ic_check_circle, good, R.string.toolchain_installed) { menu(action, pkg, have, installable) }
            installable && pkg != null -> setAction(action, R.drawable.ic_download, primary, R.string.toolchain_install) { install(pkg) }
            termuxHasAll(pkg) -> {
                // there, in Termux: a mark, nothing to do
                setAction(action, R.drawable.ic_check_circle, muted, R.string.toolchain_in_termux) {}
                action.isEnabled = false
            }
            else -> action.visibility = View.INVISIBLE
        }
    }

    private fun setAction(button: MaterialButton, icon: Int, color: Int, description: Int, click: () -> Unit) {
        button.setIconResource(icon)
        button.iconTint = ColorStateList.valueOf(color)
        button.contentDescription = activity.getString(description)
        button.setOnClickListener { click() }
    }

    // where it is: X Editor, Termux; and how to have it in Termux if it is in neither
    private fun describe(pkg: ToolPackage?, have: InstalledPackage?, installable: Boolean): String {
        val parts = ArrayList<String>()
        when {
            have != null -> parts += activity.getString(R.string.toolchain_in_local) +
                have.programs().takeIf { it.isNotEmpty() }?.let { "  " + it.joinToString(" ") }.orEmpty()
            installable -> parts += activity.getString(R.string.toolchain_installable)
        }
        if (pkg == null) {
            parts += activity.getString(R.string.toolchain_not_listed)
            return parts.joinToString(" · ")
        }
        val commands = pkg.commands()
        val found = termuxFound
        when {
            commands.isEmpty() || found == null -> if (have == null && !installable) {
                pkg.termux?.let { parts += activity.getString(R.string.toolchain_termux_install_command, it) }
            }
            commands.all { it in found } -> parts += activity.getString(R.string.toolchain_in_termux)
            commands.any { it in found } ->
                parts += activity.getString(R.string.toolchain_in_termux_some, commands.filter { it in found }.joinToString(" "))
            have == null -> parts += pkg.termux?.let { activity.getString(R.string.toolchain_termux_install_command, it) }
                ?: activity.getString(R.string.toolchain_not_in_termux)
        }
        if (parts.isEmpty()) parts += pkg.description ?: activity.getString(R.string.toolchain_not_installed)
        return parts.joinToString(" · ")
    }

    private fun termuxHasAll(pkg: ToolPackage?): Boolean {
        val found = termuxFound ?: return false
        val commands = pkg?.commands().orEmpty()
        return commands.isNotEmpty() && commands.all { it in found }
    }

    // the installed mark's menu: reinstall (if it can be), uninstall
    private fun menu(anchor: View, pkg: ToolPackage?, have: InstalledPackage, installable: Boolean) {
        val popup = PopupMenu(activity, anchor)
        if (installable && pkg != null) popup.menu.add(0, 1, 0, R.string.toolchain_reinstall)
        popup.menu.add(0, 2, 1, R.string.toolchain_uninstall)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pkg?.let(::install)
                2 -> confirmUninstall(have)
            }
            true
        }
        popup.show()
    }

    private fun confirmUninstall(have: InstalledPackage) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.toolchain_uninstall_title, have.pkg.name))
            .setMessage(activity.getString(R.string.toolchain_uninstall_message, have.files.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.toolchain_uninstall) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { ToolchainInstaller.uninstall(activity, have.pkg.id) }
                    reload(probe = false)
                }
            }
            .show()
    }

    // #endregion

    // Installs [pkg] into X Editor, its progress in its row; the download goes once it is in.
    private fun install(pkg: ToolPackage) {
        val going = installing[pkg.id]
        if (going != null && going.error == null) return
        val progress = Progress(activity.getString(R.string.toolchain_installing))
        installing[pkg.id] = progress
        update(pkg)
        scope.launch {
            val result = try {
                Result.success(ToolchainInstaller.install(activity, pkg) { step, percent ->
                    activity.runOnUiThread {
                        progress.text = activity.getString(when (step) {
                            "download" -> R.string.toolchain_step_download
                            "verify" -> R.string.toolchain_step_verify
                            "unpack" -> R.string.toolchain_step_unpack
                            else -> R.string.toolchain_step_copy
                        }) + (percent?.let { "  $it%" } ?: "")
                        progress.percent = percent
                        update(pkg)
                    }
                })
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                Result.failure(e)
            }
            result.onSuccess {
                installing.remove(pkg.id)
                Toast.makeText(activity, activity.getString(R.string.toolchain_install_done, pkg.name), Toast.LENGTH_SHORT).show()
                reload(probe = false)
            }.onFailure { e ->
                progress.error = activity.getString(R.string.toolchain_install_failed, e.message ?: e.toString())
                update(pkg)
            }
        }
    }

    // the row of [pkg], bound again
    private fun update(pkg: ToolPackage) {
        val row = rows[pkg.id] ?: return
        bind(row, pkg, installed.firstOrNull { it.pkg.id == pkg.id })
    }

    private fun badge(kind: ToolPackage.Kind): Pair<String, Int> = when (kind) {
        ToolPackage.Kind.SERVER -> "LS" to 0xFF61AFEF.toInt()
        ToolPackage.Kind.COMPILER -> "cc" to 0xFFE5C07B.toInt()
        ToolPackage.Kind.TOOL -> "⚙" to 0xFF98C379.toInt()
    }

    private companion object {
        const val TERMUX_PAGE = "https://f-droid.org/packages/com.termux/"
        const val REQUEST_TERMUX = 0x7e
    }
}
