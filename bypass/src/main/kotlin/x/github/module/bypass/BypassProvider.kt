/*
 * Copyright © 2023 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://apache.org
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.github.module.bypass

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.system.Os
import android.util.Log

/**
 * ====================================================================================
 * INTERNAL SYSTEM COMPONENT — DO NOT CALL OR INITIALIZE MANUALLY
 * ====================================================================================
 * 
 * PURPOSE:
 * This ContentProvider serves strictly as an **automatic entry point** for the bypass 
 * framework. Android OS initializes registered ContentProviders immediately upon app startup 
 * (even before `Application.onCreate()` runs). By hooking into `attachInfo()`, this class 
 * automatically triggers `Reflection.bypass()` at the earliest possible stage without 
 * requiring the user to add manual boilerplate code in their Application class.
 * 
 * EXTERNAL INVOCATION WARNING:
 * Do NOT attempt to manually instantiate this class, sub-class it, or call its lifestyle 
 * methods (like `attachInfo`) from anywhere in your codebase. Doing so causes redundant 
 * execution loops and architectural leaks. 
 * 
 * If you need to manually trigger or manage the bypass state for any reason, bypass this 
 * provider entirely and invoke the public API directly via:
 * [x.github.module.bypass.Reflection.bypass]
 * ====================================================================================
 */
class BypassProvider @Deprecated(
    message = "Do not instantiate manually. This component is managed exclusively by the Android OS framework.",
    level = DeprecationLevel.ERROR
) constructor() : ContentProvider() {
    
    private val TAG = "BypassProvider"
       
    companion object {
        // State flag ensuring that the bypass execution layer only fires exactly once
        @Volatile
        private var isSystemInitialized = false
    }
    
    override fun onCreate(): Boolean {
        return true
    }
    
    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }
    
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int {
        return 0
    }
    
    override fun query(
        uri: Uri, 
        projection: Array<String>?, 
        selection: String?, 
        selectionArgs: Array<String>?, 
        sortOrder: String?
    ): Cursor? {
        return null
    }
    
    /**
     * Internal lifecycle hook triggered automatically by the Android OS Framework.
     * Manually calling this method from an application context is explicitly prohibited.
     */
    override fun attachInfo(context: Context, info: ProviderInfo) {
        // Enforce the restriction: block and reject any rogue manual or duplicate initializations
        if (isSystemInitialized) {
            Log.w(TAG, "REJECTED: Manual or duplicate execution of BypassProvider is forbidden.")
            return
        }
        
        super.attachInfo(context, info)
        isSystemInitialized = true
        
        try {
            Reflection.bypass {               
                if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Log.i(TAG, "Successfully restriction bypass process <\${Os.getpid()}>")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failure to access hidden api \${e.toString()}")
        }
    }
}

