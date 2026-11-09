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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolVersionsTest {

    @Test
    fun eachCommandWithItsFlag() {
        assertEquals("-version", ToolVersions.versionArgs("java"))
        assertEquals("version", ToolVersions.versionArgs("go"))
        assertEquals("--version", ToolVersions.versionArgs("clangd"))
        assertEquals(listOf("java -version", "gradle --version"), ToolVersions.commandsFor(null, listOf("java", "gradle")))
    }

    @Test
    fun pyrightLangserverIsAskedThroughPyright() {
        // pyright-langserver takes no --version (it starts the server): pyright says it
        assertEquals(listOf("pyright --version"), ToolVersions.commandsFor(null, listOf("pyright-langserver")))
    }

    @Test
    fun versionCommandOfThePackageGoesFirst() {
        val one = ToolPackage.parse(Json.parseToJsonElement("""{"id":"jdk","version_command":"java -version"}""") as JsonObject)
        assertEquals(listOf("java -version"), ToolVersions.commandsFor(one, listOf("java", "gradle")))
        val two = ToolPackage.parse(Json.parseToJsonElement("""{"id":"jdk","version_command":["java -version","javac -version"]}""") as JsonObject)
        assertEquals(listOf("java -version", "javac -version"), two.versionCommand)
    }

    @Test
    fun longOutputIsCut() {
        val text = (1..10).joinToString("\n") { "line $it" }
        assertEquals((1..6).joinToString("\n") { "line $it" } + "\n…", ToolVersions.clean(text))
    }
}
