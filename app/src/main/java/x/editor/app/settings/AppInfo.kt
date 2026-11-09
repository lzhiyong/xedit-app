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

import android.content.Context
import android.os.Build

import java.text.DateFormat
import java.util.Date

import x.editor.app.R
import x.editor.app.util.PackageUtils
import x.editor.app.util.PackageUtils.packageInfo

/**
 * What there is to know about the app and the device it runs on: for the about
 * dialog, and to fill in an issue on GitHub.
 */
object AppInfo {

    /** "1.0.0 (1)" */
    fun version(context: Context): String =
        "${PackageUtils.getVersionName(context) ?: "?"} (${PackageUtils.getVersionCode(context)})"

    fun android(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    // MODEL often starts with the manufacturer already
    fun device(): String =
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER} ${Build.MODEL}"

    fun abis(): String = Build.SUPPORTED_ABIS.joinToString(", ")

    /** The rows of the about dialog: label and value. */
    fun details(context: Context): List<Pair<String, String>> {
        val info = context.applicationInfo
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val packageInfo = runCatching { context.packageInfo }.getOrNull()
        return buildList {
            add(context.getString(R.string.about_package) to context.packageName)
            add(context.getString(R.string.about_version) to version(context))
            add(context.getString(R.string.about_sdk) to "${info.minSdkVersion} – ${info.targetSdkVersion}")
            add(context.getString(R.string.about_android) to android())
            add(context.getString(R.string.about_device) to device())
            add(context.getString(R.string.about_abi) to abis())
            packageInfo?.let {
                add(context.getString(R.string.about_installed) to dateFormat.format(Date(it.firstInstallTime)))
                add(context.getString(R.string.about_updated) to dateFormat.format(Date(it.lastUpdateTime)))
            }
        }
    }

    /** The details as text, to copy. */
    fun detailsText(context: Context): String =
        buildString {
            appendLine("${PackageUtils.getAppName(context) ?: context.getString(R.string.app_name)} ${version(context)}")
            details(context).forEach { (label, value) -> appendLine("$label: $value") }
        }.trimEnd()

    /** The body of a new issue on GitHub: room to describe it, then the environment. */
    fun issueBody(context: Context): String = """
        |**Describe the problem**
        |
        |
        |**Steps to reproduce**
        |1.
        |
        |**Environment**
        |- XEdit: ${version(context)}
        |- ${android()}
        |- Device: ${device()}
        |- ABI: ${abis()}
    """.trimMargin()
}
