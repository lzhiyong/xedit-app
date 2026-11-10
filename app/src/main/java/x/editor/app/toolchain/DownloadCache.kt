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

import java.security.MessageDigest

/**
 * The rules of the toolchain download cache (cache/toolchain). Touches no files, so it
 * can be unit tested.
 *
 * A remote package is downloaded to `<id>-<fingerprint>.download`, the fingerprint
 * worked out from the id, the version, the download URL and the sha256:
 *
 *  - None of the four changed: the file name is the same, and a download that did not
 *    finish goes on where it stopped (resumed). What a stop, a failed download or the app
 *    being killed left behind can still be used.
 *  - Any of them changed (another version, URL, id or checksum): the file name changes,
 *    the old partial file is never used again, and [stale] picks it out to be deleted
 *    when packages.json is saved.
 *  - Only name, description, strip, into, bin and the like changed: which file is
 *    downloaded is the same, and the partial file is kept.
 *
 * There are also the temporary copies made while installing, `<id>.local` and
 * `<id>.content` (copied from files on the device): deleted once the install is done,
 * left behind only when the app is killed halfway. They cannot be resumed, and are
 * deleted when not in use.
 */
internal object DownloadCache {

    /** The name of the directory under the cache dir. */
    const val DIR = "toolchain"

    private const val DOWNLOAD = ".download"
    private const val LOCAL_COPY = ".local"
    private const val CONTENT_COPY = ".content"

    // The fingerprint is the first 16 hex characters of the SHA-256: enough to tell them
    // apart, and the file name stays short
    private const val FINGERPRINT_CHARS = 16

    /** The cache file name of the remote package [id] downloaded from [url] (version and sha256 go into the fingerprint too). */
    fun downloadName(id: String, version: String?, url: String, sha256: String?): String {
        val key = listOf(id, version.orEmpty().trim(), url.trim(), sha256.orEmpty().trim().lowercase())
            .joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(FINGERPRINT_CHARS)
        return "${safe(id)}-$hex$DOWNLOAD"
    }

    /** The file name of the temporary copy of a file on the device. */
    fun localCopyName(id: String): String = safe(id) + LOCAL_COPY

    fun contentCopyName(id: String): String = safe(id) + CONTENT_COPY

    /**
     * The names in [present] (the files now in the directory) to delete: cache files this
     * object manages (downloads, temporary copies) that are neither [wanted] (the download
     * names of the entries now in packages.json, which can be resumed) nor in [inUse]
     * (used by installs under way). Any other file is left alone.
     */
    fun stale(present: Collection<String>, wanted: Set<String>, inUse: Set<String>): List<String> =
        present.filter { name ->
            isCacheFile(name) && name !in inUse && !(name.endsWith(DOWNLOAD) && name in wanted)
        }

    private fun isCacheFile(name: String): Boolean =
        name.endsWith(DOWNLOAD) || name.endsWith(LOCAL_COPY) || name.endsWith(CONTENT_COPY)

    // In case an id has a path separator or the like in it (parsing already rejects
    // those; this is a second safeguard)
    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9._+-]"), "_")
}
