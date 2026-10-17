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

import android.provider.DocumentsContract.Document

/**
 * Pure interpretation of a document's MIME type and flags, separated from the permission
 * checks in [DocumentQueries] so that it can be unit tested without Android.
 */
internal object DocumentFlags {

    /** Documents without a MIME type are never considered readable. */
    fun isReadable(mimeType: String): Boolean = mimeType.isNotEmpty()

    /**
     * A document is considered writable when it has a MIME type and either:
     * - it can be deleted;
     * - it is a directory that supports creating children;
     * - it is a regular file that supports writing.
     */
    fun isWritable(mimeType: String, flags: Int): Boolean {
        if (mimeType.isEmpty()) return false
        if ((flags and Document.FLAG_SUPPORTS_DELETE) != 0) return true
        return if (mimeType == Document.MIME_TYPE_DIR) {
            (flags and Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        } else {
            (flags and Document.FLAG_SUPPORTS_WRITE) != 0
        }
    }

    fun isVirtual(flags: Int): Boolean = (flags and Document.FLAG_VIRTUAL_DOCUMENT) != 0
}
