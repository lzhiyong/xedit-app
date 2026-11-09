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

package x.editor.app.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** keymap.json as the shortcuts sheet checks it, its tabs ("group", "groups"), and New adding one. */
class KeymapFileTest {

    private val keymap = """
        // comments stay
        {
          "version": 2,
          "keymaps": [
            { "keys": "<C-s>", "action": "save" },
            { "keys": "<C-b>", "insert": "**$0**", "group": "Markdown" },
          ],
        }
    """.trimIndent()

    @Test
    fun aTabDeletedLeavesItsMappingsAndTheRestOfTheText() {
        val text = """
            // comments stay
            {
              "groups": ["Markdown", "Other"], // my tabs
              "keymaps": [
                { "keys": "<C-s>", "action": "save" },
                { "keys": "<C-b>", "insert": "**${'$'}0**", "group": "Markdown" },
                { "group": "Markdown", "keys": "<C-i>", "insert": "_" },
                {
                  "keys": "<C-u>",
                  "insert": "__",
                  "group": "Markdown"
                },
                {
                  "keys": "<C-k>",
                  "group": "Markdown",
                  "action": "save"
                },
                { "keys": "<C-o>", "insert": "o", "group": "Other" },
              ],
            }
        """.trimIndent()
        val out = KeymapFile.withoutGroup(text, "Markdown")
        assertTrue("// comments stay" in out)
        assertTrue("// my tabs" in out)
        assertTrue("\"Markdown\"" !in out, out)
        val read = KeymapFile.parse(out)
        assertEquals(6, read.keymap.mappings.size)
        assertEquals(listOf("Other"), read.keymap.groups)
        assertEquals(listOf("Other"), read.keymap.mappings.mapNotNull { it.group })
    }

    @Test
    fun aTabInMappingsOnlyOrTheLastInGroupsIsDeletedToo() {
        val onlyInMappings = """{ "keymaps": [ { "keys": "<C-b>", "insert": "b", "group": "A" } ] }"""
        assertEquals(emptyList(), KeymapFile.parse(KeymapFile.withoutGroup(onlyInMappings, "A")).keymap.mappings.mapNotNull { it.group })
        val onlyInGroups = """{ "groups": ["A"], "keymaps": [] }"""
        assertEquals(emptyList(), KeymapFile.parse(KeymapFile.withoutGroup(onlyInGroups, "A")).keymap.groups)
    }

    @Test
    fun aTabOfAnotherNameStays() {
        val text = """{ "groups": ["AB"], "keymaps": [ { "keys": "<C-b>", "insert": "\"group\": \"A\"", "group": "AB" } ] }"""
        assertEquals(text, KeymapFile.withoutGroup(text, "A"))
    }

    @Test
    fun rightOrLeftOutOrNotJson() {
        assertEquals(KeymapFile.Check.Right(2), KeymapFile.check(keymap))
        val leftOut = assertIs<KeymapFile.Check.LeftOut>(
            KeymapFile.check("""{ "keymaps": [ { "keys": "<C-s>", "action": "no_such_action" } ] }""")
        )
        assertTrue(leftOut.problems.single().contains("no_such_action"), leftOut.problems.toString())
        val syntax = assertIs<KeymapFile.Check.Syntax>(KeymapFile.check("{\n  \"keymaps\": [\n  }\n"))
        assertTrue(syntax.line in 2..3, syntax.line.toString())
    }

    @Test
    fun aMappingNamesItsTab() {
        val read = KeymapFile.parse(withGroupsOf(keymap, "Git"))
        val bold = read.keymap.mappings.single { it.action is ShortcutAction.Insert }
        assertEquals("Markdown", bold.group)
        assertEquals(null, read.keymap.mappings.single { it.action is ShortcutAction.Run }.group)
        assertEquals(listOf("Git"), read.keymap.groups)
    }

    @Test
    fun newAddsATabInPlace() {
        // no "groups" yet: one after the opening brace, the comments kept
        val first = KeymapFile.withGroup(keymap, "Git")
        assertTrue(first.startsWith("// comments stay\n{"), first)
        assertEquals(listOf("Git"), KeymapFile.parse(first).keymap.groups)
        // then into that list, before what is in it
        val second = KeymapFile.withGroup(first, "Build \"x\"")
        assertEquals(listOf("Build \"x\"", "Git"), KeymapFile.parse(second).keymap.groups)
        // an empty list: no comma left behind
        val empty = KeymapFile.withGroup("""{ "groups": [ ], "keymaps": [] }""", "A")
        assertEquals("""{ "groups": ["A" ], "keymaps": [] }""", empty)
        // "groups" in a comment is not taken for the list
        val commented = KeymapFile.withGroup("// \"groups\": [\n{ \"keymaps\": [] }", "B")
        assertEquals(listOf("B"), KeymapFile.parse(commented).keymap.groups)
    }

    private fun withGroupsOf(text: String, vararg names: String): String =
        names.fold(text) { acc, name -> KeymapFile.withGroup(acc, name) }

    @Test
    fun aTabRenamedInGroupsTakesItsMappingsAlong() {
        val before = """
            {
              "groups": ["Markdown", "Web"],
              "keymaps": [
                // "group": "Markdown" in a comment stays
                { "keys": "<C-b>", "insert": "**$0**", "group": "Markdown" },
                { "keys": "<C-g>", "insert": "\"group\": \"Markdown\"", "group": "Web" }
              ]
            }
        """.trimIndent()
        val after = before.replace("\"groups\": [\"Markdown\", ", "\"groups\": [\"Notes\", ")
        val renames = KeymapFile.renamedGroups(before, after)
        assertEquals(mapOf("Markdown" to "Notes"), renames)
        val synced = KeymapFile.withGroupsRenamed(after, renames)
        assertEquals(
            after.replace("\"**\$0**\", \"group\": \"Markdown\"", "\"**\$0**\", \"group\": \"Notes\""),
            synced,
        )
        assertEquals(emptyMap(), KeymapFile.renamedGroups(after, after))
    }

    @Test
    fun aTabAddedOrTakenAwayIsNoRename() {
        val one = """{ "groups": ["A"] }"""
        val two = """{ "groups": ["A", "B"] }"""
        assertEquals(emptyMap(), KeymapFile.renamedGroups(one, two))
        assertEquals(emptyMap(), KeymapFile.renamedGroups(two, one))
        assertEquals(emptyMap(), KeymapFile.renamedGroups("not json", one))
    }

    @Test
    fun aFileOfVersion2GetsTheShortcutsAddedSinceWhoseKeysItDoesNotUse() {
        // neither key in it: both come in, before its own, its comments kept
        val upgraded = KeymapFile.upgradeFrom2(keymap)
        assertTrue(upgraded.startsWith("// comments stay\n{"), upgraded)
        val read = KeymapFile.parse(upgraded)
        fun actionOf(keys: String) = (read.keymap.exact(KeyStroke.parseSequence(keys, null).getOrThrow())?.action as? ShortcutAction.Run)?.command
        assertEquals(ShortcutCommand.OPEN_FILE, actionOf("<C-o>"))
        assertEquals(ShortcutCommand.FULLSCREEN, actionOf("<F11>"))
        assertEquals(ShortcutCommand.SAVE, actionOf("<C-s>"))
        assertTrue("\"version\": 3" in upgraded, upgraded)
        assertTrue(read.problems.isEmpty(), read.problems.toString())

        // keys the user mapped to something else, or took away, stay as they are
        val own = """
            {
              "version": 2,
              "keymaps": [
                { "keys": "<C-o>", "action": "outline" },
                { "keys": "<F11>", "action": "nop" },
              ],
            }
        """.trimIndent()
        val kept = KeymapFile.upgradeFrom2(own)
        val keptRead = KeymapFile.parse(kept)
        assertEquals(
            ShortcutCommand.OUTLINE,
            (keptRead.keymap.exact(KeyStroke.parseSequence("<C-o>", null).getOrThrow())?.action as? ShortcutAction.Run)?.command,
        )
        assertEquals(null, keptRead.keymap.exact(KeyStroke.parseSequence("<F11>", null).getOrThrow()))
        assertEquals(own.replace("\"version\": 2", "\"version\": 3"), kept)
    }
}
