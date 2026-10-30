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

package x.github.module.crash

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How crashes are written (CrashLogStore.write): one file per distinct crash, named with
 * its time, type, signature and how often it happened; the same crash again counts up in
 * place of a new file. The store is given a directory of its own (init needs a Context).
 */
class CrashLogStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun useATemporaryDirectory() {
        dir = Files.createTempDirectory("crash").toFile()
        CrashLogStore::class.java.getDeclaredField("dir").apply { isAccessible = true }.set(null, dir)
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun logs() = dir.listFiles().orEmpty().filter { it.name.endsWith(".log") }.sortedBy { it.name }

    @Test
    fun aCrashIsAFileNamedForIt() {
        val log = CrashLogStore.write(CrashType.JAVA, "main", "java.lang.IllegalStateException: boom", "sig-a", time = 1_760_000_000_000)
        val file = logs().single()
        assertEquals(file, log.file)
        // crash_<date>_<time>_<millis>_<seq>_<type>_<signature>_<count>.log
        val parts = file.name.removeSuffix(".log").split('_')
        assertEquals(8, parts.size, file.name)
        assertEquals("crash", parts[0])
        assertEquals("java", parts[5].lowercase())
        assertEquals("1", parts[7])
        assertTrue(file.readText().contains("boom"))
    }

    @Test
    fun theSameCrashAgainCountsUp() {
        CrashLogStore.write(CrashType.JAVA, "main", "boom", "sig-a", time = 1_760_000_000_000)
        CrashLogStore.write(CrashType.JAVA, "main", "boom", "sig-a", time = 1_760_000_100_000)
        val file = logs().single()
        assertEquals("2", file.name.removeSuffix(".log").split('_')[7], file.name)
    }

    @Test
    fun anotherCrashIsAnotherFile() {
        CrashLogStore.write(CrashType.JAVA, "main", "boom", "sig-a")
        CrashLogStore.write(CrashType.ANR, "main", "stuck", "sig-b")
        assertEquals(2, logs().size)
    }
}
