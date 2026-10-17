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

import android.system.ErrnoException
import android.system.OsConstants

/**
 * Whether this error, or one of its causes, says that the app may not access a file:
 * a SecurityException (SAF permission gone, no storage permission), or EACCES / EPERM
 * from the file system (/sdcard without "all files access").
 */
fun Throwable.isPermissionDenied(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSES).any { error ->
        when {
            error is SecurityException -> true
            error is ErrnoException -> error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM
            else -> error.message?.let { message ->
                message.contains("EACCES") || message.contains("EPERM") ||
                    message.contains("Permission denied", ignoreCase = true)
            } == true
        }
    }

private const val MAX_CAUSES = 8
