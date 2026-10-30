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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI

/**
 * Where a package comes from, one URI for all of them:
 *
 *  - `https://…`, `http://…`: downloaded (and continued where a download stopped);
 *  - `file:///sdcard/Download/clangd.tar.gz`, or a path (`/sdcard/…`): a file on the device;
 *  - `content://…`: a file picked with the system's file picker (or shared to the app).
 *
 * What Termux has of a package is said by its `"termux"` (ToolPackage.termux), not here.
 */
sealed class PackageSource {
    data class Remote(val url: String) : PackageSource()
    data class LocalFile(val file: File) : PackageSource()
    data class Content(val uri: String) : PackageSource()

    /** The name of the file it names (the last part of its path), for a single binary. */
    fun fileName(): String = when (this) {
        is Remote -> url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        is LocalFile -> file.name
        is Content -> uri.substringAfterLast("%2F").substringAfterLast('/').substringAfterLast("%3A")
    }

    companion object {
        /** [text] as a source; IllegalArgumentException with what is wrong with it. */
        fun parse(text: String): PackageSource {
            val value = text.trim()
            require(value.isNotEmpty()) { "no address" }
            val lower = value.lowercase()
            return when {
                lower.startsWith("https://") || lower.startsWith("http://") -> Remote(value)
                lower.startsWith("file:") -> {
                    val path = try { URI(value).path } catch (e: Exception) { null }
                        ?: value.removePrefix("file://").removePrefix("file:")
                    require(path.startsWith("/")) { "not an absolute file: $value" }
                    LocalFile(File(path))
                }
                value.startsWith("/") -> LocalFile(File(value))
                lower.startsWith("content://") -> Content(value)
                else -> throw IllegalArgumentException("an address starts with https://, file://, content:// or /")
            }
        }
    }
}

/**
 * A toolchain of `packages.json` (next to `servers.json`): a language server, a compiler or
 * another tool. All of it is said there, nothing is typed in: where X Editor downloads it
 * from to install into its own `usr` (Usr), which version, its SHA-256, and what Termux
 * has of it.
 *
 * ```jsonc
 * {
 *   "id": "clangd",
 *   "name": "clangd",
 *   "kind": "server",                      // server, compiler or tool
 *   "version": "18.1.8",                   // a new one in the list: an update
 *   "provides": ["clangd"],                // its commands (looked for in Termux too)
 *   "termux": "pkg install clang",         // how it is had in Termux (said, not run)
 *   "source": "https://example.org/clangd-18.1.8-android-aarch64.tar.gz",
 *   "sha256": "…",
 *   "abi": {                               // or by the device's ABI, over source and sha256
 *     "arm64-v8a": { "source": "https://…-aarch64.tar.gz", "sha256": "…" },
 *     "x86_64": { "source": "https://…-x86_64.tar.gz", "sha256": "…" }
 *   },
 *   "strip": 1, "into": "opt/clangd",     // only if the layout worked out is not right
 *   "bin": { "clangd": "opt/clangd/bin/clangd" },
 *   "env": { "CLANGD_FLAGS": "--header-insertion=never" }
 * }
 * ```
 *
 * [source]: a URI (PackageSource: https://, file://, content://); null (none, nor in [abi]):
 * not installed into X Editor, only looked for in Termux. [strip]: leading path parts taken
 * off the names in the archive; null: worked out (Archives.layout: a single top directory is
 * taken off). [into]: the directory under usr it goes to (`""`: usr itself, for an archive
 * laid out as `bin/ lib/ share/`); null: worked out (usr itself for such an archive,
 * `opt/<id>` for any other). [bin]: what is put in usr/bin, a link each (a path under
 * usr); none given: what is in its `bin` directory when it has gone into one of its own.
 * [env]: added to the environment of what runs with the toolchain (`${usr}`: its path).
 */
data class ToolPackage(
    val id: String,
    val source: String? = null,
    val name: String = id,
    val kind: Kind = Kind.TOOL,
    val version: String? = null,
    val sha256: String? = null,
    val strip: Int? = null,
    val into: String? = null,
    val bin: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
    val description: String? = null,
    val enabled: Boolean = true,
    val provides: List<String> = emptyList(),
    val termux: String? = null,
    val abi: Map<String, AbiSource> = emptyMap(),
) {
    /** The package for one ABI: its [source] and [sha256]. */
    data class AbiSource(val source: String, val sha256: String?)

    enum class Kind(val key: String) {
        SERVER("server"), COMPILER("compiler"), TOOL("tool");

        companion object {
            fun of(key: String?): Kind = entries.firstOrNull { it.key == key?.lowercase() } ?: TOOL
        }
    }

    /**
     * The source and SHA-256 for a device of [abis] (Build.SUPPORTED_ABIS, the best first):
     * that of the first of them [abi] has, else [source]; null if there is none.
     */
    fun sourceFor(abis: List<String>): AbiSource? =
        abis.firstNotNullOfOrNull { abi[it] } ?: source?.let { AbiSource(it, sha256) }

    /** Whether it can be installed into X Editor on a device of [abis]. */
    fun isInstallable(abis: List<String>): Boolean = sourceFor(abis) != null

    /** The commands it gives: [provides], else its programs for usr/bin. */
    fun commands(): List<String> = provides.ifEmpty { bin.keys.toList() }

    /** What [source] says, parsed; IllegalArgumentException if it has none. */
    fun parsedSource(): PackageSource =
        PackageSource.parse(source ?: throw IllegalArgumentException("$id: no \"source\""))

    companion object {
        private val ID = Regex("""^[A-Za-z0-9][A-Za-z0-9._+\-]*$""")

        @OptIn(ExperimentalSerializationApi::class)
        internal val json = Json {
            allowComments = true
            allowTrailingComma = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** Whether [id] can name a package (it names its manifest file). */
        fun isValidId(id: String): Boolean = ID.matches(id) && id.length <= 64

        /** One package of a catalog or a manifest; IllegalArgumentException with what is wrong. */
        fun parse(json: JsonObject): ToolPackage {
            val id = json.string("id") ?: throw IllegalArgumentException("no \"id\"")
            require(isValidId(id)) { "\"$id\" is not an id (letters, digits, . _ + -)" }
            val source = json.string("source")?.trim()?.takeIf { it.isNotEmpty() }
            val termux = json.string("termux")?.trim()?.takeIf { it.isNotEmpty() }
            val provides = json.strings("provides")
            // an address it can be had from, or IllegalArgumentException
            source?.let(PackageSource::parse)
            val abi = (json["abi"] as? JsonObject)?.mapNotNull { (key, value) ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                val url = entry.string("source")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PackageSource.parse(url)
                key to AbiSource(url, entry.string("sha256")?.lowercase()?.takeIf { it.isNotBlank() })
            }?.toMap().orEmpty()
            val into = json.string("into")?.trim('/')
            if (!into.isNullOrEmpty()) require(isUnderUsr(into)) { "$id: \"into\" is not a directory under usr" }
            val bin = json.map("bin")
            for ((name, path) in bin) {
                require(name.isNotEmpty() && '/' !in name) { "$id: \"$name\" is not a name for usr/bin" }
                require(isUnderUsr(path)) { "$id: \"$path\" is not a path under usr" }
            }
            return ToolPackage(
                id = id,
                source = source,
                name = json.string("name") ?: id,
                kind = Kind.of(json.string("kind")),
                version = json.string("version"),
                sha256 = json.string("sha256")?.lowercase()?.takeIf { it.isNotBlank() },
                strip = (json["strip"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 16),
                into = into,
                bin = bin.mapValues { it.value.trim('/') },
                env = json.map("env"),
                description = json.string("description"),
                enabled = (json["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                provides = provides,
                termux = termux,
                abi = abi,
            )
        }

        /** The packages of a catalog (`{"packages": [...]}`); those with something wrong go to [problems]. */
        fun parseCatalog(text: String, problems: MutableList<String> = ArrayList()): List<ToolPackage> {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                problems += e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                return emptyList()
            } ?: return emptyList<ToolPackage>().also { problems += "not a JSON object" }
            val list = root["packages"] as? JsonArray ?: return emptyList<ToolPackage>().also { problems += "no \"packages\" list" }
            return list.mapIndexedNotNull { index, element ->
                try {
                    parse(element as? JsonObject ?: throw IllegalArgumentException("not an object"))
                } catch (e: IllegalArgumentException) {
                    problems += "package #${index + 1}: ${e.message}"
                    null
                }
            }
        }

        // a relative path that stays under usr
        private fun isUnderUsr(path: String): Boolean = try {
            Archives.relativePath(path, 0) == path.trim('/') && !path.startsWith("/")
        } catch (e: java.io.IOException) {
            false
        }

        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.map(key: String): Map<String, String> =
            (this[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
    }

    /** As JSON: what a manifest keeps of it. */
    fun toJson(): JsonObject = JsonObject(buildMap {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        put("kind", JsonPrimitive(kind.key))
        version?.let { put("version", JsonPrimitive(it)) }
        source?.let { put("source", JsonPrimitive(it)) }
        if (provides.isNotEmpty()) put("provides", JsonArray(provides.map(::JsonPrimitive)))
        termux?.let { put("termux", JsonPrimitive(it)) }
        sha256?.let { put("sha256", JsonPrimitive(it)) }
        strip?.let { put("strip", JsonPrimitive(it)) }
        into?.let { put("into", JsonPrimitive(it)) }
        if (bin.isNotEmpty()) put("bin", JsonObject(bin.mapValues { JsonPrimitive(it.value) }))
        if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        description?.let { put("description", JsonPrimitive(it)) }
    })
}

/**
 * A package as it is installed: what it is, the paths it put under usr (to take away
 * again), the links it made in usr/bin, and when.
 */
data class InstalledPackage(
    val pkg: ToolPackage,
    val files: List<String>,
    val links: Map<String, String>,
    val installedAt: Long,
) {
    /** What it put in usr/bin: its links, and its own files there. */
    fun programs(): List<String> =
        (links.keys + files.filter { it.startsWith("bin/") && it.count { c -> c == '/' } == 1 }.map { it.removePrefix("bin/") })
            .distinct().sorted()

    fun toJson(): String = ToolPackage.json.encodeToString(JsonElement.serializer(), JsonObject(buildMap {
        putAll(pkg.toJson())
        put("files", JsonArray(files.map(::JsonPrimitive)))
        put("links", JsonObject(links.mapValues { JsonPrimitive(it.value) }))
        put("installedAt", JsonPrimitive(installedAt))
    })) + "\n"

    companion object {
        /** A manifest written by [toJson]; null if it cannot be read. */
        fun parse(text: String): InstalledPackage? = try {
            val root = ToolPackage.json.parseToJsonElement(text) as JsonObject
            InstalledPackage(
                pkg = ToolPackage.parse(root),
                files = (root["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                links = (root["links"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
                installedAt = (root["installedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        } catch (e: Exception) {
            null
        }
    }
}
