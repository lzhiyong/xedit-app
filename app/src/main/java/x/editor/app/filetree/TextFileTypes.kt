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

package x.editor.app.filetree

import java.util.Locale

/**
 * Whether a file is one the editor can open, from its name and mime type.
 *
 * Decided by exclusion: source files often have no registered mime type
 * (Makefile, .gitignore, .kt, .rs, ...) and come as application/octet-stream or
 * empty, so only what is known to be binary is excluded: images, video, audio,
 * fonts, archives, office documents, executables and the like.
 */
object TextFileTypes {

    private val BINARY_MIME_PREFIXES = listOf("image/", "video/", "audio/", "font/")

    private val BINARY_MIME_TYPES = setOf(
        "application/pdf",
        "application/zip",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
        "application/x-bzip2",
        "application/x-xz",
        "application/java-archive",
        "application/vnd.android.package-archive",
        "application/x-sharedlib",
        "application/x-executable",
        "application/x-sqlite3",
        "application/vnd.sqlite3",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/epub+zip",
        "application/wasm",
    )

    private val BINARY_MIME_PREFIXES_APP = listOf(
        "application/vnd.openxmlformats-officedocument.",
        "application/vnd.oasis.opendocument.",
    )

    private val BINARY_EXTENSIONS = setOf(
        // images, video, audio, fonts
        "png", "jpg", "jpeg", "gif", "bmp", "webp", "heic", "heif", "ico", "tif", "tiff", "psd",
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "wmv", "m4v",
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr", "mid", "midi",
        "ttf", "otf", "woff", "woff2",
        // archives and packages
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4",
        "apk", "apks", "aab", "xapk", "jar", "aar", "dex", "class", "obb",
        // binaries and data
        "so", "a", "o", "dll", "exe", "bin", "img", "iso", "dat", "db", "sqlite", "sqlite3",
        "odex", "vdex", "oat", "art", "pyc", "wasm",
        // documents
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub",
    )

    fun isText(name: String, mimeType: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in BINARY_EXTENSIONS) return false

        val mime = mimeType.orEmpty().lowercase(Locale.ROOT)
        if (BINARY_MIME_PREFIXES.any { mime.startsWith(it) }) return false
        if (mime in BINARY_MIME_TYPES) return false
        if (BINARY_MIME_PREFIXES_APP.any { mime.startsWith(it) }) return false
        return true
    }
}
