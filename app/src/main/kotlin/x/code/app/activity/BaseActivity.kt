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

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.os.Looper
import android.util.TypedValue
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
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

import x.code.app.model.MainViewModel
import x.code.app.util.AppUtils
import x.github.module.alerter.Alerter

abstract class BaseActivity : AppCompatActivity() {
    
    protected lateinit var backPressed: OnBackPressedCallback
    
    protected lateinit var uiModeManager: UiModeManager
    
    protected lateinit var sharedPreference: SharedPreferences
        
    protected var windowInsetsController: WindowInsetsControllerCompat? = null
    
    // view model for activity and view interacts and kotlin coroutines
    protected val mainViewModel by viewModels<MainViewModel>()
    
    protected open val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // set the view tree owners before setting the content view so that the inflation process
        // and attach listeners will see them already present
        // viewModelStoreOwner for the HighlightTextView
        window.decorView.setViewTreeViewModelStoreOwner(this)

        // comment out to support edge to edge
        // WindowCompat.setDecorFitsSystemWindows(window, false)
        
        sharedPreference = getPreferences(Context.MODE_PRIVATE)                
        uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        
        windowInsetsController = WindowCompat.getInsetsController(
            window, window.decorView
        )?.apply {
            isAppearanceLightNavigationBars = 
                uiModeManager.getNightMode() == UiModeManager.MODE_NIGHT_NO
            isAppearanceLightStatusBars = 
                uiModeManager.getNightMode() == UiModeManager.MODE_NIGHT_NO
        }
        
        // back key callback
        backPressed = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                doBackPressed()
            }
        }
        // by default, the back key callback is handled by the system
        // to process by the user, set the value to true
        backPressed.isEnabled = false       
        onBackPressedDispatcher.addCallback(this, backPressed)
    }
    
    protected open fun doBackPressed() {
        // implement your own logic
        // TODO
    }
    
    protected fun createAlerter(
        titleText: String? = null,
        contentText: String? = null,
        cancelButtonText: String? = null,
        okButtonText: String? = null,
        onCancelCallback: ((Alerter) -> Unit)? = null,
        onOkCallback: ((Alerter) -> Unit)? = null,
        dismissible: Boolean = true
    ) = Alerter.create(this).apply {
        setElevation(30f)
        setBackgroundColorRes(
            AppUtils.resolveAttr(
                this@BaseActivity,
                com.google.android.material.R.attr.colorPrimaryContainer
            )
        )
        
        titleText?.let { setTitle(it) }
        contentText?.let { setText(it) }
        
        cancelButtonText?.let { label ->
            // cancel button
            addButton(
                label,               
                View.OnClickListener {    
                    setDuration(3000L)
                    dismiss()
                    onCancelCallback?.invoke(this)
                }
            )
        }
        
        okButtonText?.let { label ->
            // ok button
            addButton(
                label,
                View.OnClickListener {
                    // ok button click callback
                    onOkCallback?.invoke(this)
                }
            )
        }
        
        // can dismissible
        setDismissable(dismissible)
    }
    
    protected fun createDialog(
        dialogTitle: String? = null, 
        dialogMessage: String? = null, 
        dialogView: View? = null,
        neutralButtonText: String? = null,
        positiveButtonText: String? = null,
        negativeButtonText: String? = null,
        onNeutralCallback: (() -> Unit)? = null,
        onPositiveCallback: (() -> Unit)? = null,
        onNegativeCallback: (() -> Unit)? = null,
        onCancelCallback: (() ->Unit)? = null,
        cancelable: Boolean = true
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
            
            neutralButtonText?.let { label ->
                setNeutralButton(label) { dialog, _ ->
                    dialog.dismiss()
                    onNeutralCallback?.invoke()
                }
            }
           
            positiveButtonText?.let { label ->
                setPositiveButton(label) { dialog, _ ->
                    dialog.dismiss()
                    onPositiveCallback?.invoke()
                }
            }
            
            negativeButtonText?.let { label ->
                setNegativeButton(label) { dialog, _ ->
                    dialog.dismiss()
                    onNegativeCallback?.invoke()
                }
            }
            
            setOnCancelListener {
                onCancelCallback?.invoke()
            }
        }
        
        return builder.create().apply { 
            setCancelable(cancelable)
        }
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
}

