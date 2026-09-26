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

package x.github.module.bypass

import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * ====================================================================================
 * USAGE GUIDE FOR BYPASSING AND ACCESSING HIDDEN APIS
 * ====================================================================================
 * 
 * --- STEP 1: INITIALIZATION (CHOOSE ONE METHOD) ---
 * 
 * METHOD A: Automatic Execution via ContentProvider (Recommended)
 * Simply register the provided `BypassProvider` in your `AndroidManifest.xml`.
 * It runs automatically before `Application.onCreate()`.
 * 
 * <application>
 *     <provider
 *         android:name="x.github.module.bypass.BypassProvider"
 *         android:authorities="\${applicationId}.bypassprovider"
 *         android:exported="false"
 *         android:initOrder="100" />
 * </application>
 * 
 * ------------------------------------------------------------------------------------
 * 
 * METHOD B: Manual Execution in Custom Application Class
 * If you prefer not to use a Provider, invoke it inside `attachBaseContext`.
 * 
 * class MyApplication : android.app.Application() {
 *     override fun attachBaseContext(base: android.content.Context) {
 *         super.attachBaseContext(base)
 *         try {
 *             x.github.module.bypass.Reflection.bypass {
 *                 android.util.Log.i("Bypass", "Hidden API restrictions bypassed successfully!")
 *             }
 *         } catch (e: Exception) {
 *             android.util.Log.e("Bypass", "Failed to bypass: \${e.message}")
 *         }
 *     }
 * }
 * 
 * ------------------------------------------------------------------------------------
 * 
 * --- STEP 2: ACCESSING HIDDEN APIS ---
 * After initialization, you can use the helpers below to access restricted system APIs.
 * 
 * Example: Accessing a hidden class or method (e.g., ActivityManagerNative)
 * 
 * try {
 *     // 1. Find the restricted class
 *     val hiddenClass = Class.forName("android.app.ActivityManagerNative")
 *     
 *     // 2. Safely retrieve the hidden method using this utility class
 *     val getDefaultMethod = Reflection.getDeclaredMethod(hiddenClass, "getDefault")
 *     
 *     // 3. Invoke the method without OS interception
 *     val amInstance = getDefaultMethod.invoke(null)
 *     android.util.Log.d("BypassSuccess", "Retrieved restricted instance: \$amInstance")
 * } catch (e: Exception) {
 *     e.printStackTrace()
 * }
 * ====================================================================================
 */

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

