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

package x.editor.app.activity

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams

import androidx.activity.viewModels
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.setViewTreeViewModelStoreOwner

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import x.editor.app.AppSettings
import x.editor.app.util.AppUtils
import x.editor.app.viewmodel.EditorViewModel

abstract class BaseActivity : AppCompatActivity() {
    
    protected lateinit var activityPreferences: SharedPreferences
        
    protected var windowInsetsController: WindowInsetsControllerCompat? = null
    
    // view model for activity and view interacts and kotlin coroutines
    protected val editorViewModel by viewModels<EditorViewModel>()
    
    protected open val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Whether this activity is shown with the dark theme right now. Unlike the
     * night mode *setting* of the system, this is also right while that setting is
     * "automatic", and when the app theme was chosen in the app's own settings.
     */
    protected val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    
    // what this activity was themed with beyond the night mode, see AppSettings.themeSignature
    private var themeSignature = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the window and its decor are created, which read the theme. The
        // colors from the wallpaper are laid over the theme already (DynamicColors,
        // see EditorApplication); pure black goes over them.
        AppSettings.themeOverlayFor(this, isNightMode).let { overlay ->
            if (overlay != 0) theme.applyStyle(overlay, true)
        }
        themeSignature = AppSettings.themeSignature(this, isNightMode)

        super.onCreate(savedInstanceState)
        // set the view tree owners before setting the content view so that the inflation process
        // and attach listeners will see them already present
        // viewModelStoreOwner for the CodeEditorView
        window.decorView.setViewTreeViewModelStoreOwner(this)

        // comment out to support edge to edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        activityPreferences = getPreferences(Context.MODE_PRIVATE)
        
        // dark icons on the light theme, light icons on the dark one
        windowInsetsController = WindowCompat.getInsetsController(
            window, window.decorView
        )?.apply {
            isAppearanceLightNavigationBars = !isNightMode
            isAppearanceLightStatusBars = !isNightMode
        }
    }
    
    override fun onRestart() {
        super.onRestart()
        // the theme was changed in the settings while this activity was in the back
        recreateIfThemeChanged()
    }

    /**
     * Recreates this activity if what it was themed with (pure black, colors from
     * the wallpaper) is not what the settings say any more. The night mode is
     * AppCompat's to take care of.
     *
     * @return whether it is recreated
     */
    fun recreateIfThemeChanged(): Boolean {
        if (isFinishing || AppSettings.themeSignature(this, isNightMode) == themeSignature) {
            return false
        }
        recreate()
        return true
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
    protected fun applyInsetsMargin(
        view: View,
        type: Int = WindowInsetsCompat.Type.systemBars()
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(type)            
            // set margin for appbar layout
            if (v.layoutParams is ViewGroup.MarginLayoutParams) {
                v.updateLayoutParams<MarginLayoutParams> {
                    topMargin = insets.top
                    leftMargin = insets.left
                    bottomMargin = insets.bottom
                    rightMargin = insets.right
                }
            }
            WindowInsetsCompat.CONSUMED
        }
    }
    
    protected fun applyInsetsPadding(
        view: View,
        type: Int = WindowInsetsCompat.Type.systemBars()
    ) {
        val basePadding = view.run { Rect(paddingLeft, paddingTop, paddingRight, paddingBottom) }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(type)
            v.setPadding(
                basePadding.left + insets.left,
                basePadding.top + insets.top,
                basePadding.right + insets.right,
                basePadding.bottom + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }
    
    /** The color of a theme attribute; see [AppUtils.resolveColor]. */
    @ColorInt
    fun Context.getThemeColor(@AttrRes resource: Int): Int =
        AppUtils.resolveColor(this, resource)
    
    fun Context.getThemedDrawable(@DrawableRes resource: Int): Drawable? =
        ResourcesCompat.getDrawable(this.resources, resource, theme)
}
