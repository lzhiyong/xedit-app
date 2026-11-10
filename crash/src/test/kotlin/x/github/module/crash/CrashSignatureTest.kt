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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What makes two crashes the same crash (CrashSignature): the exception classes and the
 * top frames of the cause chain, not the messages; for native crashes, the message with
 * what changes from run to run (addresses, pids, install directories) taken out.
 */
class CrashSignatureTest {

    private fun frame(method: String, line: Int) = StackTraceElement("x.app.Main", method, "Main.kt", line)

    private fun error(message: String, vararg frames: StackTraceElement, cause: Throwable? = null) =
        IllegalStateException(message, cause).apply { stackTrace = arrayOf(*frames) }

    @Test
    fun theMessageDoesNotMakeAnotherCrash() {
        val a = error("index 3", frame("load", 10), frame("main", 2))
        val b = error("index 7", frame("load", 10), frame("main", 2))
        assertEquals(CrashSignature.of(a), CrashSignature.of(b))
    }

    @Test
    fun anotherPlaceMakesAnotherCrash() {
        val a = error("x", frame("load", 10))
        val b = error("x", frame("load", 11))
        assertNotEquals(CrashSignature.of(a), CrashSignature.of(b))
        assertNotEquals(CrashSignature.of(a), CrashSignature.of(IllegalArgumentException("x").apply { stackTrace = a.stackTrace }))
    }

    @Test
    fun theCauseCounts() {
        val root = error("io", frame("read", 5))
        val withCause = error("wrapped", frame("load", 10), cause = root)
        val signature = CrashSignature.of(withCause)
        assertTrue(signature.contains("x.app.Main.read:5"), signature)
        assertNotEquals(signature, CrashSignature.of(error("wrapped", frame("load", 10))))
    }

    @Test
    fun anAnrIsItsMainThreadStack() {
        val stack = Array(30) { frame("step$it", it) }
        val signature = CrashSignature.ofAnr(stack)
        assertTrue(signature.contains("step19") && !signature.contains("step20"), "the top 20 frames")
    }

    @Test
    fun aNativeCrashWithoutWhatChangesFromRunToRun() {
        val first = "Fatal signal 11 at 0x7b1c2d3e40, pid: 1234, tid: 1240 in /data/app/~~a1B2==/x.editor.app-c3D4==/lib/arm64/libx.so"
        val again = "Fatal signal 11 at 0x7a00000010, pid: 4321, tid: 4400 in /data/app/~~zZ9==/x.editor.app-qQ1==/lib/arm64/libx.so"
        assertEquals(CrashSignature.ofNative(11, first), CrashSignature.ofNative(11, again))
        assertNotEquals(CrashSignature.ofNative(11, first), CrashSignature.ofNative(6, first))
        assertTrue(CrashSignature.ofNative(11, first).startsWith("11\n"))
    }
}
