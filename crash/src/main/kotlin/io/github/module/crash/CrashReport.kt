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
package io.github.module.crash

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.annotation.RequiresApi

import dalvik.annotation.optimization.CriticalNative
import dalvik.annotation.optimization.FastNative

// calkback for exceptions
interface OnExceptionListener {

    fun onJavaCrash(thread: Thread, throwable: Throwable)
    
    fun onNativeCrash(signum: Int, message: String)
}

// throw QuitCaptureException
internal class QuitCaptureException(message: String) : RuntimeException(message)

@Keep
object CrashReport {

    private lateinit var listener: OnExceptionListener
    private lateinit var uncaughtExHandler: Thread.UncaughtExceptionHandler
    
    private var isInitialized: Boolean = false
    
    private val mutex: Any = Any()
    
    init {
        System.loadLibrary("crash_report")
    }
    
    // only for anr exception test
    public fun testAnrCrash() {
        Thread {
            synchronized(mutex) {
                while(true) {
                    try {
                        Thread.sleep(1000 * 60)
                    }
                    catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
        }.start()
        
        Handler().postDelayed({
            synchronized (mutex) {
                // Shouldn't happen
                throw IllegalStateException()
            }
        }, 1000)
    }
    
    // only for java exception test
    @Throws(RuntimeException::class)
    public fun testJavaCrash() {
        throw RuntimeException("java crash test, you can ignore.")
    }
    
    // native crash callback, called by Jni
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.M)
    @Deprecated(
        "The method can't be called directly.", 
        level = DeprecationLevel.HIDDEN
    )
    private fun callback(signum: Int, message: String) {        
        listener.onNativeCrash(signum, message)
    }
    
    // enable execption capture
    fun enable(exListener: OnExceptionListener) {
        if(CrashReport.isInitialized) {
            // has initialized, nothing to do
            return@enable
        }
        
        isInitialized = true
        listener = exListener
        initNativeCrash()

        Handler(Looper.getMainLooper()).post {
            while (true) {               
                try {
                    Looper.loop()
                } catch (e: Throwable) {
                    // Binder.clearCallingIdentity()
                    if (e is QuitCaptureException) {
                        return@post
                    }                    
                    // exception callback
                    listener.onJavaCrash(Looper.getMainLooper().getThread(), e)                    
                }
            }
        }

        uncaughtExHandler = Thread.getDefaultUncaughtExceptionHandler()!!
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            listener.onJavaCrash(thread, ex)
        }
    }

    // disable execption capture
    fun disable() {
        if(!CrashReport.isInitialized) {
            // no initialized, nothing to do
            return@disable
        }
        
        isInitialized = false
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExHandler)
        // interrupt main looper
        Handler(Looper.getMainLooper()).post {
            throw QuitCaptureException("quit the capture exception.")
        }
    }
    
    @FastNative
    private external fun initNativeCrash()
    
    // only for native exception test
    @FastNative
    public external fun testNativeCrash()
}
