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

package x.editor.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

object PackageUtils {

    /** @throws PackageManager.NameNotFoundException never in practice: it is this app's own package */
    @Suppress("DEPRECATION")
    val Context.packageInfo: PackageInfo
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }

    fun getApplicationInfo(context: Context): ApplicationInfo = context.applicationInfo

    fun getVersionName(context: Context): String? {
        try {
            return context.packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return null
    }

    fun getVersionCode(context: Context): Long {
        try {
            return PackageInfoCompat.getLongVersionCode(context.packageInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return 0L
    }

    /** The label of the app, whether the manifest gives it as a resource or as plain text. */
    fun getAppName(context: Context): String? =
        context.applicationInfo.loadLabel(context.packageManager).toString()
}
