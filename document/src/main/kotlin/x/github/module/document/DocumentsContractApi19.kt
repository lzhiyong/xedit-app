/*
 * Copyright 2018 The Android Open Source Project
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

package x.github.module.document

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import androidx.annotation.RequiresApi

@RequiresApi(19)
object DocumentsContractApi19 {

    // DocumentsContract API level 24.
    private val FLAG_VIRTUAL_DOCUMENT: Int = 1 shl 9

    public fun isVirtual(context: Context, self: Uri, flags: Int): Boolean {
        if (!DocumentsContract.isDocumentUri(context, self)) {
            return false
        }

        return (flags and FLAG_VIRTUAL_DOCUMENT) != 0
    }
    
    public fun exists(context: Context, self: Uri): Boolean {
        var count: Int = 0
        context.contentResolver.query(
            self, arrayOf<String>(), null, null, null
        )?.use { cursor ->
            count = cursor.getCount()
        }
        return count > 0
    }
    
    public fun canRead(context: Context, self: Uri, type: String?, flags: Int): Boolean {
        // Ignore if grant doesn't allow read
        if (context.checkCallingOrSelfUriPermission(
            self, Intent.FLAG_GRANT_READ_URI_PERMISSION) 
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        // Ignore documents without MIME
        if (TextUtils.isEmpty(type)) {
            return false
        }

        return true
    }

    public fun canWrite(context: Context, self: Uri, type: String?, flags: Int): Boolean {
        // Ignore if grant doesn't allow write
        if (context.checkCallingOrSelfUriPermission(self, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        // Ignore documents without MIME
        if (TextUtils.isEmpty(type)) {
            return false
        }

        // Deletable documents considered writable
        if ((flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0) {
            return true
        }

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type) &&
            (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        ) {
            // Directories that allow create considered writable
            return true
        } else if (!TextUtils.isEmpty(type) &&
            (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        ) {
            // Writable normal files considered writable
            return true
        }

        return false
    }
}
