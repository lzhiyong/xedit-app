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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where "compile and run" runs, and what it builds: the file's project (see ProjectRoot). */
class ProjectRootTest {

    // directories in memory: the paths of their files, and the text of some
    private class Tree(private val files: Map<String, String>) : ProjectRoot.Files {
        constructor(vararg paths: String) : this(paths.associateWith { "" })

        override fun names(directory: File): Set<String>? {
            val prefix = directory.path.trimEnd('/') + "/"
            val names = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.toSet()
            return names.ifEmpty { if (directory.path == "/") emptySet() else null }
        }

        override fun read(file: File): String? = files[file.path]
    }

    @Test
    fun cmakeAtTheTopOfItsNestedLists() {
        val tree = Tree("/w/proj/CMakeLists.txt", "/w/proj/lib/CMakeLists.txt", "/w/proj/lib/y.c")
        assertEquals(ProjectRoot.Project(File("/w/proj"), ProjectRoot.Kind.CMAKE), ProjectRoot.of(File("/w/proj/lib/y.c"), tree))
    }

    @Test
    fun gradleAtItsSettingsFile() {
        val tree = Tree(mapOf(
            "/a/settings.gradle.kts" to "include(\":lib\")",
            "/a/build.gradle.kts" to "plugins { kotlin(\"jvm\") }",
            "/a/lib/build.gradle.kts" to "",
            "/a/lib/src/Main.kt" to "",
        ))
        assertEquals(ProjectRoot.Project(File("/a"), ProjectRoot.Kind.GRADLE), ProjectRoot.of(File("/a/lib/src/Main.kt"), tree))
    }

    @Test
    fun androidByItsPluginWithTheWrapper() {
        // the plugin of a version catalog, in the app module only
        val tree = Tree(mapOf(
            "/a/settings.gradle.kts" to "include(\":app\")",
            "/a/build.gradle.kts" to "",
            "/a/gradlew" to "#!/bin/sh",
            "/a/app/build.gradle.kts" to "plugins { alias(libs.plugins.android.application) }",
            "/a/app/src/main/cpp/native.cpp" to "",
        ))
        val project = ProjectRoot.of(File("/a/app/src/main/cpp/native.cpp"), tree)
        assertEquals(ProjectRoot.Project(File("/a"), ProjectRoot.Kind.ANDROID, gradleWrapper = true), project)
    }

    @Test
    fun whatAProjectBuilds() {
        assertTrue(ProjectRoot.Kind.CMAKE.covers(File("/p/src/start.S")))
        assertTrue(ProjectRoot.Kind.CMAKE.covers(File("/p/CMakeLists.txt")))
        assertFalse(ProjectRoot.Kind.CMAKE.covers(File("/p/tools/gen.py")))
        assertTrue(ProjectRoot.Kind.GRADLE.covers(File("/a/build.gradle.kts")))
        assertFalse(ProjectRoot.Kind.GRADLE.covers(File("/a/src/x.c")))
        assertTrue(ProjectRoot.Kind.ANDROID.covers(File("/a/app/src/main/res/layout/main.xml")))
    }

    @Test
    fun theNearestBuildFile() {
        val tree = Tree("/n/package.json", "/n/src/Cargo.toml", "/n/src/a/b.rs")
        assertEquals(ProjectRoot.Project(File("/n/src")), ProjectRoot.of(File("/n/src/a/b.rs"), tree))
    }

    @Test
    fun notPastGit() {
        // the Makefile above the repository is not its project
        val tree = Tree("/x/Makefile", "/x/repo/.git/HEAD", "/x/repo/src/main.c")
        assertEquals(File("/x/repo/src"), ProjectRoot.of(File("/x/repo/src/main.c"), tree).root)
    }

    @Test
    fun noProjectTheFilesDirectory() {
        assertEquals(ProjectRoot.Project(File("/s/notes")), ProjectRoot.of(File("/s/notes/a.py"), Tree("/s/notes/a.py")))
        // directories that cannot be read: the file's directory too
        assertEquals(File("/q/r"), ProjectRoot.of(File("/q/r/a.c")) { null }.root)
    }
}
