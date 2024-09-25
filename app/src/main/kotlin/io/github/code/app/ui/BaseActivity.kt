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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.TypedValue
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import kotlinx.coroutines.*

abstract class BaseActivity : AppCompatActivity() {
    
    open val navigationBarColor: Int = Color.TRANSPARENT
        //get() = resolveAttr(com.google.android.material.R.attr.colorSurface)
        
    open val statusBarColor: Int = Color.TRANSPARENT
        //get() = resolveAttr(com.google.android.material.R.attr.colorSurface)
    
    protected var windowInsetsController: WindowInsetsControllerCompat? = null
    
    // view model for activity and view interacts and kotlin coroutines
    protected val mainViewModel by viewModels<MainViewModel>()
    protected lateinit var mainScope: CoroutineScope
    
    // activity result callback
    private var activityCallback: ((Intent) -> Unit)? = null
    // start activity for result
    private val activityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            activityCallback?.invoke(result.data!!)
        }
    }
    
    protected fun startActivityForResult(
        intent: Intent, 
        callback: ((Intent) -> Unit)? = null
    ) {
        activityCallback = callback
        activityLauncher.launch(intent)
    }
    
    protected val handler = object: Handler() {
        override fun handleMessage(msg: Message) {
            // TODO
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // set the view tree owners before setting the content view so that the inflation process
        // and attach listeners will see them already present
        // viewModelStoreOwner for the HighlightTextView
        window.decorView.setViewTreeViewModelStoreOwner(this)

        // comment out to support edge to edge
        // WindowCompat.setDecorFitsSystemWindows(window, false)
        mainScope = mainViewModel.viewModelScope
        
        window?.apply {
            navigationBarColor = this@BaseActivity.navigationBarColor
            statusBarColor = this@BaseActivity.statusBarColor
        }
        
        windowInsetsController = WindowCompat.getInsetsController(
            window, window.decorView
        )?.apply{
            //isAppearanceLightNavigationBars = true
            //isAppearanceLightStatusBars = true
        }
        
        // back key callback
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBackKeyPressed()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }
    
    protected fun getUiMode(): Int {
        return resources.configuration.uiMode and 
            Configuration.UI_MODE_NIGHT_MASK
    }
    
    protected open fun onBackKeyPressed() {
        // implement your own logic
        // TODO
    }
    
    protected fun showDialog(
        dialogTitle: String? = null, 
        dialogMessage: String? = null, 
        dialogView: View? = null,
        positiveCallback: (() -> Unit)? = null
    ) = showDialog(
        dialogTitle,
        dialogMessage,
        dialogView,
        getString(android.R.string.ok),
        getString(android.R.string.cancel),
        positiveCallback
    )
    
    protected fun showDialog(
        dialogTitle: String? = null, 
        dialogMessage: String? = null, 
        dialogView: View? = null,
        positiveText: String? = null,
        negativeText: String? = null,
        positiveCallback: (() -> Unit)? = null
    ) = showDialog(
        dialogTitle,
        dialogMessage,
        dialogView,
        null,
        getString(android.R.string.ok),
        getString(android.R.string.cancel),
        null,
        positiveCallback
    )
    
    protected fun showDialog(
        dialogTitle: String? = null, 
        dialogMessage: String? = null, 
        dialogView: View? = null,
        neutralText: String? = null,
        positiveText: String? = null,
        negativeText: String? = null,
        neutralCallback: (() -> Unit)? = null,
        positiveCallback: (() -> Unit)? = null
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(this).apply {
            if(!TextUtils.isEmpty(dialogTitle)) {
                setTitle(dialogTitle)
            }
            
            if(!TextUtils.isEmpty(dialogMessage)) {
                setMessage(dialogMessage)
            }
            
            dialogView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                setView(view)
            }
            
            neutralText?.let { text ->
                setNeutralButton(text) { dialog, _ ->
                    dialog.dismiss()
                    neutralCallback?.invoke()
                }
            }
           
            positiveText?.let { text ->
                setPositiveButton(text) { dialog, _ ->
                    dialog.dismiss()
                    positiveCallback?.invoke()
                }
            }
            
            negativeText?.let { text ->
                setNegativeButton(text) { dialog, _ ->
                    dialog.dismiss()
                }
            }
        }
        
        return builder.create().apply{ show() }
    }
    
    // insetsType#WindowInsetsCompat.Type.ime()
    // insetsType#WindowInsetsCompat.Type.statusBars()
    fun applyWindowInsets(view: View, insetsType: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(insetsType)
            // set margin for appbar layout
            view.updateLayoutParams<MarginLayoutParams> {
    	        topMargin = insets.top
                leftMargin = insets.left
                bottomMargin = insets.bottom
                rightMargin = insets.right
            }
            WindowInsetsCompat.CONSUMED
        }
    }
    
    protected fun resolveAttr(id: Int): Int {
        val typedValue = TypedValue()
        getTheme().resolveAttribute(id, typedValue, true)
        return typedValue.resourceId
    }
    
    protected fun getStatusBarHeight() = getResources().run {
        getDimensionPixelSize(getIdentifier("status_bar_height", "dimen", "android"))
    }
    
    protected fun getActionBarHeight(): Int {
        val tv = TypedValue()
        return if(getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            TypedValue.complexToDimensionPixelSize(tv.data, resources.getDisplayMetrics())
        } else {
            0
        }
    }
}

