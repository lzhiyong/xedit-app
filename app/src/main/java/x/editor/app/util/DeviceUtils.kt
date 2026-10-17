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

import android.os.Build

object DeviceUtils {

    /**
     * The architecture name of this device as used in the names of the downloaded
     * native libraries: aarch64, arm, x86_64 or x86.
     *
     * @throws RuntimeException if the device supports none of them
     */
    @Throws(RuntimeException::class)
    fun getArchName(): String = archNameOf(Build.SUPPORTED_ABIS)

    /**
     * The architecture name for the first known ABI in [abis], which are ordered by
     * preference like `Build.SUPPORTED_ABIS`.
     *
     * @throws RuntimeException if none of them is known
     */
    @Throws(RuntimeException::class)
    fun archNameOf(abis: Array<String>): String {
        for (abi in abis) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86_64" -> return "x86_64"
                "x86" -> return "x86"
            }
        }
        throw RuntimeException(
            "Unable to determine arch from Build.SUPPORTED_ABIS = ${abis.joinToString()}"
        )
    }
}
