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

package x.github.module.bypass

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.Log

class BypassProvider : ContentProvider() {
    private val TAG = "BypassProvider"
    
    override fun onCreate(): Boolean {
        return true
    }
    
    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }
    
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int {
        return 0
    }
    
    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?, 
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        return null
    }
    
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        try {
            Reflection.bypass() {               
                if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Log.i(TAG, "Successfully restriction bypass process <${Os.getpid()}>")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failure to access hidden api ${e.toString()}")
        }
    }
}

