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

package x.github.module.bypass

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reflection on the JVM, where Build.VERSION.SDK_INT is 0: the path of Android 9 and
 * earlier, reflection through Class's own methods (from Android 10 on it goes through
 * JNI, on a device). The bypass itself needs ART: here it fails, as it should, for want of
 * dalvik.system.VMRuntime.
 */
class ReflectionTest {

    private class Sample {
        @Suppress("unused")
        private val secret = "hidden"

        @Suppress("unused")
        private fun twice(n: Int) = n * 2
    }

    @Test
    fun aPrivateMethodByName() {
        val method = Reflection.getDeclaredMethod(Sample::class.java, "twice", Int::class.javaPrimitiveType!!)
        method.isAccessible = true
        assertEquals(42, method.invoke(Sample(), 21))
    }

    @Test
    fun aPrivateFieldByName() {
        val field = Reflection.getDeclaredField(Sample::class.java, "secret")
        field.isAccessible = true
        assertEquals("hidden", field.get(Sample()))
    }

    @Test
    fun whatIsNotThereIsAnError() {
        // what Class.getDeclaredMethod throws comes wrapped, it was called by reflection
        val error = assertFailsWith<InvocationTargetException> { Reflection.getDeclaredMethod(Sample::class.java, "missing") }
        assertTrue(error.targetException is NoSuchMethodException, "${error.targetException}")
    }

    @Test
    fun theBypassNeedsArt() {
        assertFailsWith<ClassNotFoundException> { Reflection.bypass() }
    }
}
