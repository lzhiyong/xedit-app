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

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.fragment.app.DialogFragment

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import x.editor.app.R
import x.editor.app.util.PackageUtils

/**
 * About the app: its icon, name and version, and what it and the device are
 * (package, SDK levels, Android, device, ABIs, install and update time). "Copy"
 * puts all of it on the clipboard, for a bug report.
 */
class AboutDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_about, null)

        content.findViewById<ImageView>(R.id.icon)
            .setImageDrawable(context.packageManager.getApplicationIcon(context.applicationInfo))
        content.findViewById<TextView>(R.id.name).text =
            PackageUtils.getAppName(context) ?: getString(R.string.app_name)
        content.findViewById<TextView>(R.id.version).text =
            getString(R.string.pref_app_about_version, AppInfo.version(context))

        val rows = content.findViewById<LinearLayout>(R.id.details)
        val inflater = LayoutInflater.from(context)
        AppInfo.details(context).forEach { (label, value) ->
            val row = inflater.inflate(R.layout.item_about_detail, rows, false)
            row.findViewById<TextView>(R.id.label).text = label
            row.findViewById<TextView>(R.id.value).text = value
            rows.addView(row)
        }

        return MaterialAlertDialogBuilder(context)
            .setView(content)
            .setNeutralButton(R.string.action_copy) { _, _ -> copyDetails() }
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun copyDetails() {
        val context = context ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.pref_app_about_title), AppInfo.detailsText(context))
        )
        // Android 13 and later show that something was copied themselves
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.about_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "x.editor.app.settings.ABOUT"
    }
}
