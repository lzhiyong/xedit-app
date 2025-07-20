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

package x.code.app

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

import x.github.module.crash.CrashReport
import x.github.module.crash.OnExceptionListener
import x.github.module.piecetable.common.Strings

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        DynamicColors.applyToActivitiesIfAvailable(this)

        AppSettings.applyAppTheme(getApplicationContext())
        
        val intent = Intent().apply {
            action = "${getPackageName()}.CRASH_REPORT"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        val activityManager = getApplicationContext().getSystemService(
            Context.ACTIVITY_SERVICE) as ActivityManager
        /*
        CrashReport.enable(object: OnExceptionListener {        
            override fun onJavaCrash(thread: Thread, throwable: Throwable) {
                try {
                    StringWriter().use { sw ->
                        val pw = PrintWriter(sw)
                        throwable.printStackTrace(pw)
                        sw.flush()
                        pw.flush()
                        intent.putExtra("CRASH_STACK_TRACR", sw.toString())
                    }                    
                    startActivity(intent)
                    exitProcess(0)               
                } catch (e: Throwable) {
                    e.printStackTrace()
                }     
            }
            
            override fun onNativeCrash(signum: Int, message: String) {
                try {                                     
                    val buffer = StringBuffer("${message}\n")                   
                    val mainThread = Looper.getMainLooper().getThread()
                    val indent = Strings.indent(4)
                    buffer.append("stacktrace: \n")
                    mainThread.getStackTrace().forEach { element ->           
                        buffer.append("${indent}at ${element.toString()}\n")   
                    }
                    intent.putExtra("CRASH_STACK_TRACR", buffer.toString())
                    startActivity(intent)
                    exitProcess(0)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }     
            }
        })*/
    }
}

