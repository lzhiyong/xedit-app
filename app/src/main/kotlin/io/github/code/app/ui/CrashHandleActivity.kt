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
 
package io.github.code.app.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment

import kotlinx.coroutines.*

import io.github.code.app.databinding.FragmentCrashReportBinding
import io.github.code.app.R

class CrashHandleActivity : BaseActivity() {
    
    private val LOG_TAG = this::class.simpleName
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bundle = getIntent().getExtras()
        if (bundle == null) {
            finishAffinity()
            return@onCreate
        }
        
        setContentView(R.layout.activity_crash_handle)
        
        findViewById<Toolbar>(R.id.toolbar)?.let {
            setSupportActionBar(it)
        }
        
        val fragment = CrashReportFragment.newInstance(bundle)
        getSupportFragmentManager()
            .beginTransaction()
            .replace(R.id.fragment_container, fragment, fragment::class.simpleName)
            .addToBackStack(null)
            .commit()
    }
    
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        // show menu icons
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        return super.onMenuOpened(featureId, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.activity_crash_handle, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_report -> bugReport()           
            R.id.action_copy -> copyStackTrace()           
            R.id.action_restart -> restartApp()         
            R.id.action_close -> finishAffinity()
        }
        return true
    }
    
    private fun bugReport() {
        mainScope.launch {
            // TODO
        }
    }
    
    private fun copyStackTrace() {
        val trace = getIntent().getExtras()?.getString("CRASH_STACK_TRACR")
        if(!TextUtils.isEmpty(trace)) {
            val content = ClipData.newPlainText("content", trace)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(content)
            Toast.makeText(this, getString(R.string.copy_text_hint), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or 
            Intent.FLAG_ACTIVITY_CLEAR_TOP or 
            Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        intent?.action = "restart"
        startActivity(intent)
        Process.killProcess(Process.myPid())
        System.exit(0)
    }
    
    // fragment
    class CrashReportFragment : Fragment() {
        
        private var binding: FragmentCrashReportBinding? = null
        
        companion object {
            @JvmStatic
            fun newInstance(bundle: Bundle) = CrashReportFragment().apply {
                arguments = bundle
            }
        }
        
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return FragmentCrashReportBinding.inflate(inflater, container, false).also { binding = it }.root
        }
        
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val args = requireArguments()
            
            binding!!.textView.text = args.getString("CRASH_STACK_TRACR")
        }
        
        override fun onDestroyView() {
            super.onDestroyView()
        }
    }
}

