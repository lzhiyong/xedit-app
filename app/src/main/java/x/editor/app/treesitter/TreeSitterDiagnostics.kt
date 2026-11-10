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

package x.editor.app.treesitter

import android.content.Context

import java.io.File

import x.editor.app.highlight.TreeSitterLookup
import x.editor.app.util.DeviceUtils

/**
 * What the tree-sitter resources on the device are, for the settings
 * (ResourcesStatusFragment): what is installed (and staged), how much of each, and
 * for a few sample files how far they get on the way to highlighting (see
 * TreeSitterLookup), with the step that fails.
 */
object TreeSitterDiagnostics {

    /** A package: [version] null if it is not installed. */
    class PackageStatus(
        val id: String,
        val target: String,
        val version: String?,
        val entries: List<String>,
        val staged: String?
    )

    /** The steps on the way from a file to its highlighting. */
    enum class Step { FILE_TYPE, LIBRARY, QUERIES }

    /** How far [fileName] gets: [failed] is the step where it stops, null if it gets through. */
    class SampleCheck(
        val fileName: String,
        val symbol: String?,
        val library: String?,
        val queries: String?,
        val failed: Step?
    )

    class Report(
        val root: String,
        val arch: String,
        val manifestProblem: String?,
        val packages: List<PackageStatus>,
        /** the patterns of filetypes.json; null if it is missing or broken ([fileTypesProblem]) */
        val fileTypes: Int?,
        val fileTypesProblem: String?,
        val grammars: Int,
        val themes: List<String>,
        val libraries: List<String>,
        val queryFolders: Int,
        val queryLanguages: List<String>,
        val samples: List<SampleCheck>
    ) {
        /** The problems a glance should see: missing packages, files that fail. */
        val problemCount: Int
            get() = (if (manifestProblem != null) 1 else 0) +
                packages.count { it.version == null && it.staged == null } +
                (if (fileTypesProblem != null) 1 else 0) +
                samples.count { it.failed != null }
    }

    private val SAMPLE_FILES = listOf(
        "main.c", "main.cpp", "Main.java", "Main.kt", "main.py", "index.js",
        "main.rs", "main.go", "index.html", "style.css", "data.json", "README.md"
    )

    /** The report. Reads files: not on the main thread. */
    fun report(context: Context): Report {
        val manifest = TreeSitterResources.manifest(context)

        val packages = manifest.packages.map { pkg ->
            val installed = TreeSitterUpdater.installed(context, pkg)
            PackageStatus(
                id = pkg.id,
                target = pkg.target.path,
                version = installed?.version,
                entries = installed?.entries.orEmpty(),
                staged = TreeSitterUpdater.stagedVersion(context, pkg)
            )
        }

        val fileTypesFile = TreeSitterResources.fileTypesFile(context)
        var fileTypesProblem: String? = null
        val fileTypes = try {
            if (fileTypesFile.isFile) TreeSitterLookup.parseFileTypes(fileTypesFile) else null
        } catch (e: Exception) {
            fileTypesProblem = e.message ?: e.javaClass.simpleName
            null
        }
        if (fileTypes == null && fileTypesProblem == null) fileTypesProblem = "missing: ${fileTypesFile.path}"

        val themes = TreeSitterResources.themesDir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }?.sorted().orEmpty()

        val libraries = TreeSitterResources.languagesDir(context).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .associateBy { it.name }

        val queriesDir = TreeSitterResources.queriesDir(context)
        val queryFolders = queriesDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted().orEmpty()
        val queryLanguages = queryFolders.filter { File(queriesDir, "$it/highlights.scm").isFile }

        val samples = SAMPLE_FILES.map { name ->
            val symbol = fileTypes?.let { TreeSitterLookup.symbolFor(name, it) }
            val library = symbol?.let { TreeSitterLookup.findLibrary(it, libraries) }
            val queries = if (symbol != null && library != null) {
                TreeSitterLookup.findQueryName(queriesDir, TreeSitterLookup.queryNames(null, symbol))
            } else null
            SampleCheck(
                fileName = name,
                symbol = symbol,
                library = library?.name,
                queries = queries,
                failed = when {
                    symbol == null -> Step.FILE_TYPE
                    library == null -> Step.LIBRARY
                    queries == null -> Step.QUERIES
                    else -> null
                }
            )
        }

        return Report(
            root = manifest.root.path,
            arch = DeviceUtils.getArchName(),
            manifestProblem = manifest.problem,
            packages = packages,
            fileTypes = fileTypes?.size,
            fileTypesProblem = fileTypesProblem,
            grammars = fileTypes?.values?.toSet()?.size ?: 0,
            themes = themes,
            libraries = libraries.keys.sorted(),
            queryFolders = queryFolders.size,
            queryLanguages = queryLanguages,
            samples = samples
        )
    }

    /** The report as plain text, to copy into a bug report. */
    fun text(report: Report): String = buildString {
        appendLine(report.root)
        appendLine("arch: ${report.arch}")
        report.manifestProblem?.let { appendLine("manifest: $it") }
        appendLine()
        for (pkg in report.packages) {
            appendLine("[${pkg.id}] → ${pkg.target}")
            appendLine("  installed: ${pkg.version?.let { "$it ${pkg.entries}" } ?: "no"}")
            pkg.staged?.let { appendLine("  staged for the next start: $it") }
        }
        appendLine()
        appendLine(
            report.fileTypesProblem?.let { "filetypes.json: $it" }
                ?: "filetypes.json: ${report.fileTypes} patterns, ${report.grammars} grammars"
        )
        appendLine("themes: ${report.themes.size} ${report.themes}")
        appendLine("libraries: ${report.libraries.size} ${report.libraries}")
        appendLine("query folders: ${report.queryFolders}, with highlights.scm: ${report.queryLanguages.size} ${report.queryLanguages}")
        appendLine()
        appendLine("file → grammar → library → queries")
        for (sample in report.samples) {
            append("  ${sample.fileName}: ")
            appendLine(
                when (sample.failed) {
                    Step.FILE_TYPE -> "✗ no file type"
                    Step.LIBRARY -> "${sample.symbol} → ✗ no library"
                    Step.QUERIES -> "${sample.symbol} → ${sample.library} → ✗ no queries"
                    null -> "${sample.symbol} → ${sample.library} → queries/${sample.queries} ✓"
                }
            )
        }
    }.trimEnd()
}
