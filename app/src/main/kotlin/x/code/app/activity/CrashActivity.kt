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
 
package x.code.app.activity

import android.content.Context
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.launch

import x.code.app.databinding.ActivityCrashBinding
import x.code.app.R

class CrashActivity : BaseActivity() {
    
    private lateinit var binding: ActivityCrashBinding
    
    private val LOG_TAG = this::class.simpleName
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bundle = getIntent().getExtras()
        if (bundle == null) {
            finishAffinity()
            return@onCreate
        }
        
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        window.decorView.post {
            binding.textView.text = bundle.getString("CRASH_STACK_TRACR")
        }
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
        lifecycleScope.launch {
            // TODO
        }
    }
    
    private fun copyStackTrace() {
        val trace = binding.textView.getText().toString()
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
}

