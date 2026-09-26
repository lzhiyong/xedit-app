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

package x.editor.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process

import com.google.android.material.color.DynamicColors

import java.io.StringWriter
import java.io.PrintWriter

import kotlin.system.exitProcess

import x.github.module.crash.CrashHandler
import x.github.module.crash.OnExceptionListener
import x.github.module.piecetable.common.Strings

import android.util.Log

class MyApplication : Application() {
    
    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        val intent = Intent("x.editor.app.CRASH_REPORT").apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        
        val activityManager = context.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as ActivityManager
            
        CrashHandler.enable(object: OnExceptionListener {        
            override fun onJavaCrash(thread: Thread, throwable: Throwable) {                
                try {
                    val sw = StringWriter()
                    PrintWriter(sw).use { throwable.printStackTrace(it) }
                    intent.putExtra("CRASH_STACK_TRACE", sw.toString())
        
                    applicationContext.startActivity(intent)
                    exitProcess(0)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
                        
            override fun onNativeCrash(signum: Int, message: String) {
                try {
                    intent.putExtra("CRASH_STACK_TRACE", message)
                    applicationContext.startActivity(intent)
                    exitProcess(0)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        })
    }
    
    override fun onCreate() {
        super.onCreate()
        
        DynamicColors.applyToActivitiesIfAvailable(this)

        AppSettings.applyAppTheme(getApplicationContext())      
    }
}

