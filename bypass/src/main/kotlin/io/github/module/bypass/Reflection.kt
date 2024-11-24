/*
 * Copyright © 2023 Github Lzhiyong
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

package io.github.module.bypass

import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object Reflection {
    
    @Throws(NoSuchFieldException::class, InvocationTargetException::class, IllegalAccessException::class)
    fun bypass(callback: (() -> Unit)? = null) {
        val getRuntime = getDeclaredMethod(
            Class.forName("dalvik.system.VMRuntime"),
            "getRuntime"
        ).apply { setAccessible(true) }
            
        val vmRuntime = getRuntime.invoke(null)        
        // libart/src/main/java/dalvik/system/VMRuntime.java
        // setHiddenApiExemptions(String[] signaturePrefixes)
        val setHiddenApiExemptions = getDeclaredMethod(
            vmRuntime.javaClass,
            "setHiddenApiExemptions",
             arrayOf<String>()::class.java
        ).apply { setAccessible(true) }
        
        // restriction bypass
        setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L"))
        callback?.invoke()
    }  
    
    @Throws(NoSuchFieldException::class, InvocationTargetException::class, IllegalAccessException::class)
    fun getDeclaredMethod(clazz: Any, name: String, vararg args: Class<*>): Method {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return JNI.getDeclaredMethod(clazz, name, arrayOf(*args))
        } else {
            val getDeclaredMethod = Class::class.java.getMethod(
                "getDeclaredMethod",
                String::class.java, 
                arrayOf<Class<*>>()::class.java
            )
            return getDeclaredMethod.invoke(clazz, name, args) as Method
        }
    }    

    @Throws(NoSuchFieldException::class, InvocationTargetException::class, IllegalAccessException::class)
    fun getDeclaredField(obj: Class<*>, name: String): Field {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return JNI.getDeclaredField(obj, name)
        } else {
            val getDeclaredField = Class::class.java.getMethod("getDeclaredField", String::class.java)
            return getDeclaredField.invoke(obj, name) as Field
        }
    }
}

