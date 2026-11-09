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

package x.editor.app.console

import android.content.Context
import androidx.annotation.WorkerThread
import x.editor.app.lsp.LocalFiles
import java.io.File

/**
 * The project a file is in: its root, where "compile and run" and the commands of the
 * console's build tab run, and its [Kind], which has a command of its own (RunCommands).
 *
 * The root is looked for from the file's directory up, not past a `.git`: the directory
 * with a Gradle settings file, else the outermost of a CMake project's nested
 * CMakeLists.txt (one in each directory up), else the nearest with a build file. None of
 * them: the file's own directory, of no kind. Directories are read through [Files], which
 * the app reads by path or through the folder the user granted.
 */
object ProjectRoot {

    /** How the directories are read: the names in one (null: it cannot be read), the text of a file (null: not there). */
    fun interface Files {
        fun names(directory: File): Set<String>?
        fun read(file: File): String? = null
    }

    /**
     * The kinds of project with a command of their own, for the files of [extensions] (and
     * of [names]) in them; the other files of the project keep the command of their kind.
     */
    enum class Kind(val key: String, val label: String, private val extensions: Set<String>, private val names: Set<String> = emptySet()) {
        CMAKE("cmake", "CMake", C_FILES + setOf("cmake"), setOf("CMakeLists.txt", "CMakePresets.json")),
        GRADLE("gradle", "Gradle", JVM_FILES),
        ANDROID("android", "Android (Gradle)", JVM_FILES + C_FILES + setOf("xml", "aidl", "pro")),
        ;

        /** Whether [file] is built with its project's command. */
        fun covers(file: File): Boolean =
            file.name in names || file.extension.lowercase() in extensions
    }

    /** A project: its [root], its [kind] (null: none with a command of its own), whether it has a Gradle wrapper. */
    data class Project(val root: File, val kind: Kind? = null, val gradleWrapper: Boolean = false)

    private val BUILD_FILES = listOf(
        "CMakeLists.txt", "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
        "Makefile", "makefile", "GNUmakefile", "meson.build", "Cargo.toml", "go.mod", "package.json",
    )

    private val GRADLE_FILES = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")

    /** The project [file] is in; its own directory, of no kind, if there is none. */
    fun of(file: File, files: Files): Project {
        val own = file.absoluteFile.parentFile ?: File("/")
        val chain = ArrayList<Pair<File, Set<String>>>()
        var dir: File? = own
        while (dir != null) {
            val names = files.names(dir) ?: break
            chain += dir to names
            if (".git" in names) break
            dir = dir.parentFile
        }
        val found = chain.firstOrNull { (_, names) -> "settings.gradle" in names || "settings.gradle.kts" in names }
            ?: chain.indexOfFirst { "CMakeLists.txt" in it.second }.takeIf { it >= 0 }?.let { first ->
                // CMake: the outermost of the CMakeLists.txt that are nested, one in each directory up
                var top = first
                while (top + 1 < chain.size && "CMakeLists.txt" in chain[top + 1].second) top++
                chain[top]
            }
            ?: chain.firstOrNull { (_, names) -> BUILD_FILES.any { it in names } }
            ?: return Project(own)
        val (root, names) = found
        return when {
            GRADLE_FILES.any { it in names } ->
                Project(root, if (isAndroid(root, names, files)) Kind.ANDROID else Kind.GRADLE, gradleWrapper = "gradlew" in names)
            "CMakeLists.txt" in names -> Project(root, Kind.CMAKE)
            else -> Project(root)
        }
    }

    // a Gradle build with the Android plugin: in its own build file, or in that of its app module
    private fun isAndroid(root: File, names: Set<String>, files: Files): Boolean {
        val texts = GRADLE_FILES.filter { it in names }.mapNotNull { files.read(File(root, it)) } +
            listOf("app/build.gradle.kts", "app/build.gradle").takeIf { "app" in names }.orEmpty().mapNotNull { files.read(File(root, it)) }
        return texts.any { text -> ANDROID_MARKS.any { it in text } }
    }

    // `id("com.android.application")`, `alias(libs.plugins.android.application)`, `android {`
    private val ANDROID_MARKS = listOf("com.android.", "plugins.android.", "android {")

    /** The project [file] is in, read by path or through the folder the user granted. */
    @WorkerThread
    fun of(context: Context, file: File): Project = of(file, object : Files {
        override fun names(directory: File): Set<String>? =
            LocalFiles.list(context, directory)?.entries?.map { it.first }?.toSet()

        override fun read(file: File): String? = LocalFiles.readText(context, file)
    })
}

// the files of C, C++ and assembly, and of the JVM's languages
private val C_FILES = setOf("c", "h", "cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "s", "asm")
private val JVM_FILES = setOf("java", "kt", "kts", "gradle", "groovy")
