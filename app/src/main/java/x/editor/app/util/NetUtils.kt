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

/** Small helpers for downloads that need nothing from Android. */
object NetUtils {

    /**
     * Scheme and host of [url], up to and including the first slash after the host:
     * `https://github.com/a/b.zip` gives `https://github.com/`. A url without a
     * scheme is taken as https; a url without a path is returned without a slash.
     */
    fun getBaseUrl(url: String): String {
        var rest = url
        var scheme = "https://"
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd >= 0) {
            scheme = rest.substring(0, schemeEnd + 3)
            rest = rest.substring(schemeEnd + 3)
        }
        val hostEnd = rest.indexOf('/')
        if (hostEnd >= 0) {
            rest = rest.substring(0, hostEnd + 1)
        }
        return scheme + rest
    }

    /**
     * How much of [total] bytes [copied] bytes are, as 0..100. 0 if the total is
     * not known (a server that sends no Content-Length reports -1).
     */
    fun progressPercent(copied: Long, total: Long): Int {
        if (total <= 0L || copied <= 0L) return 0
        if (copied >= total) return 100
        // not copied * 100 / total: that overflows for sizes near Long.MAX_VALUE
        return (copied.toDouble() / total.toDouble() * 100.0).toInt()
    }
}
