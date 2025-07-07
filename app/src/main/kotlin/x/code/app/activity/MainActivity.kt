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
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity

import java.io.IOException

import x.code.app.databinding.ActivityMainBinding
import x.code.app.util.PackageUtils

class MainActivity : BaseActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)        
        /*       
        if (!PackageUtils.checkStoragePermission(this)) {
            createView(savedInstanceState)
        } else {
            startActivity(Intent(this, EditorActivity::class.java))
            this.finish()
        }
        */
    }
    
    fun Context.readAssetFile(filename: String): String? {
        return try {
            assets.open(filename).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
    
    fun createView(savedInstanceState: Bundle?) {       
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        binding.summaryTextView.setText(readAssetFile("privacy_policy_cn.txt"))
        
        binding.storagePermissionAccessButton.setOnClickListener {
            //PackageUtils.requestStoragePermission(this)
        }
        
        binding.storagePermissionDeniedButton.setOnClickListener {
            startActivity(Intent(this, EditorActivity::class.java))
        }
    }
    
}
