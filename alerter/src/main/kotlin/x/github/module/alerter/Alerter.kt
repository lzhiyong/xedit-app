/*
 * Copyright © 2024 Github Lzhiyong
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

package x.github.module.alerter

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.annotation.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import java.lang.ref.WeakReference

/**
 * Alert helper class. Will attach a temporary layout to the current activity's content, on top of
 * all other views. It should appear under the status bar.
 */
class Alerter private constructor() {
    /**
     * Sets the Alert
     *
     * @param alert The Alert to be references and maintained
     */
    private var alert: Alert? = null

    /**
     * Shows the Alert, after it's built
     *
     * @return An Alert object check can be altered or hidden
     */
    fun show(): Alert? {
        // This will get the Activity Window's DecorView
        decorView?.get()?.let {
            android.os.Handler(Looper.getMainLooper()).post {
                (alert?.parent as? ViewGroup)?.let { viewGroup ->
                    viewGroup.removeView(alert)
                }
                it.addView(alert)
            }
        }
        return alert
    }

    /**
     * Sets the title of the Alert
     *
     * @param titleId Title String Resource
     * @return This Alerter
     */
    fun setTitle(
        @StringRes titleId: Int,
    ) = apply {
        alert?.setTitle(titleId)
    }

    /**
     * Set Title of the Alert
     *
     * @param title Title as a CharSequence
     * @return This Alerter
     */
    fun setTitle(title: CharSequence) = apply {
        alert?.setTitle(title)
    }

    /**
     * Set the Title's Typeface
     *
     * @param typeface Typeface to use
     * @return This Alerter
     */
    fun setTitleTypeface(typeface: Typeface) = apply {
        alert?.setTitleTypeface(typeface)
    }

    /**
     * Set the Title's text appearance
     *
     * @param textAppearance The style resource id
     * @return This Alerter
     */
    fun setTitleAppearance(
        @StyleRes textAppearance: Int,
    ) = apply {
        alert?.setTitleAppearance(textAppearance)
    }

    /**
     * Set Layout Gravity of the Alert
     *
     * @param layoutGravity of Alert
     * @return This Alerter
     */
    fun setLayoutGravity(layoutGravity: Int) = apply {
        alert?.layoutGravity = layoutGravity
    }

    /**
     * Set Gravity of the Alert
     *
     * @param gravity Gravity of Alert
     * @return This Alerter
     */
    fun setContentGravity(gravity: Int) = apply {
        alert?.contentGravity = gravity
    }

    /**
     * Sets the Alert Text
     *
     * @param textId Text String Resource
     * @return This Alerter
     */
    fun setText(
        @StringRes textId: Int,
    ) = apply {
        alert?.setText(textId)
    }

    /**
     * Sets the Alert Text
     *
     * @param text CharSequence of Alert Text
     * @return This Alerter
     */
    fun setText(text: CharSequence) = apply {
        alert?.setText(text)
    }

    /**
     * Set the Text's Typeface
     *
     * @param typeface Typeface to use
     * @return This Alerter
     */
    fun setTextTypeface(typeface: Typeface) = apply {
        alert?.setTextTypeface(typeface)
    }

    /**
     * Set the Text's text appearance
     *
     * @param textAppearance The style resource id
     * @return This Alerter
     */
    fun setTextAppearance(
        @StyleRes textAppearance: Int,
    ) = apply {
        alert?.setTextAppearance(textAppearance)
    }

    /**
     * Set the Alert's Background Colour
     *
     * @param colorInt Colour int value
     * @return This Alerter
     */
    fun setBackgroundColorInt(
        @ColorInt colorInt: Int,
    ) = apply {
        alert?.setAlertBackgroundColor(colorInt)
    }

    /**
     * Set the Alert's Background Colour
     *
     * @param colorResId Colour Resource Id
     * @return This Alerter
     */
    fun setBackgroundColorRes(
        @ColorRes colorResId: Int,
    ) = apply {
        decorView?.get()?.let {
            alert?.setAlertBackgroundColor(ContextCompat.getColor(it.context.applicationContext, colorResId))
        }
    }

    /**
     * Set the Alert's Background Drawable
     *
     * @param drawable Drawable
     * @return This Alerter
     */
    fun setBackgroundDrawable(drawable: Drawable) = apply {
        alert?.setAlertBackgroundDrawable(drawable)
    }

    /**
     * Set the Alert's Background Drawable Resource
     *
     * @param drawableResId Drawable Resource Id
     * @return This Alerter
     */
    fun setBackgroundResource(
        @DrawableRes drawableResId: Int,
    ) = apply {
        alert?.setAlertBackgroundResource(drawableResId)
    }

    /**
     * Set the Alert's Icon
     *
     * @param iconId The Drawable's Resource Idw
     * @return This Alerter
     */
    fun setIcon(
        @DrawableRes iconId: Int,
    ) = apply {
        alert?.setIcon(iconId)
    }

    /**
     * Set the Alert's Icon
     *
     * @param bitmap The Bitmap object to use for the icon.
     * @return This Alerter
     */
    fun setIcon(bitmap: Bitmap) = apply {
        alert?.setIcon(bitmap)
    }

    /**
     * Set the Alert's Icon
     *
     * @param drawable The Drawable to use for the icon.
     * @return This Alerter
     */
    fun setIcon(drawable: Drawable) = apply {
        alert?.setIcon(drawable)
    }

    /**
     * Set the Alert's Icon size
     *
     * @param size Dimension int.
     * @return This Alerter
     */
    fun setIconSize(
        @DimenRes size: Int,
    ) = apply {
        alert?.setIconSize(size)
    }

    /**
     * Set the Alert's Icon size
     *
     * @param size Icon size in pixel.
     * @return This Alerter
     */
    fun setIconPixelSize(
        @Px size: Int,
    ) = apply {
        alert?.setIconPixelSize(size)
    }

    /**
     * Set the icon color for the Alert
     *
     * @param color Color int
     * @return This Alerter
     */
    fun setIconColorFilter(
        @ColorInt color: Int,
    ) = apply {
        alert?.setIconColorFilter(color)
    }

    /**
     * Set the icon color for the Alert
     *
     * @param colorFilter ColorFilter
     * @return This Alerter
     */
    fun setIconColorFilter(colorFilter: ColorFilter) = apply {
        alert?.setIconColorFilter(colorFilter)
    }

    /**
     * Set the icon color for the Alert
     *
     * @param color Color int
     * @param mode  PorterDuff.Mode
     * @return This Alerter
     */
    fun setIconColorFilter(
        @ColorInt color: Int,
        mode: PorterDuff.Mode,
    ) = apply {
        alert?.setIconColorFilter(color, mode)
    }

    /**
     * Hide the Icon
     *
     * @return This Alerter
     */
    fun hideIcon() = apply {
        alert?.showIcon(false)
    }

    /**
     * Set the Alert's Right Icon
     *
     * @param iconId The Drawable's Resource Idw
     * @return This Alerter
     */
    fun setRightIcon(
        @DrawableRes rightIconId: Int,
    ) = apply {
        alert?.setRightIcon(rightIconId)
    }

    /**
     * Set the Alert's Right Icon
     *
     * @param bitmap The Bitmap object to use for the right icon.
     * @return This Alerter
     */
    fun setRightIcon(bitmap: Bitmap) = apply {
        alert?.setRightIcon(bitmap)
    }

    /**
     * Set the Alert's Right Icon
     *
     * @param drawable The Drawable to use for the right icon.
     * @return This Alerter
     */
    fun setRightIcon(drawable: Drawable) = apply {
        alert?.setRightIcon(drawable)
    }

    /**
     * Set the Alert's Right Icon size
     *
     * @param size Dimension int.
     * @return This Alerter
     */
    fun setRightIconSize(
        @DimenRes size: Int,
    ) = apply {
        alert?.setRightIconSize(size)
    }

    /**
     * Set the Alert's Right Icon size
     *
     * @param size Right Icon size in pixel.
     * @return This Alerter
     */
    fun setRightIconPixelSize(
        @Px size: Int,
    ) = apply {
        alert?.setRightIconPixelSize(size)
    }

    /**
     * Set the right icon color for the Alert
     *
     * @param color Color int
     * @return This Alerter
     */
    fun setRightIconColorFilter(
        @ColorInt color: Int,
    ) = apply {
        alert?.setRightIconColorFilter(color)
    }

    /**
     * Set the right icon color for the Alert
     *
     * @param colorFilter ColorFilter
     * @return This Alerter
     */
    fun setRightIconColorFilter(colorFilter: ColorFilter) = apply {
        alert?.setRightIconColorFilter(colorFilter)
    }

    /**
     * Set the right icon color for the Alert
     *
     * @param color Color int
     * @param mode  PorterDuff.Mode
     * @return This Alerter
     */
    fun setRightIconColorFilter(
        @ColorInt color: Int,
        mode: PorterDuff.Mode,
    ) = apply {
        alert?.setRightIconColorFilter(color, mode)
    }

    /**
     * Set the right icons's position for the Alert
     *
     * @param gravity Gravity int
     * @return This Alerter
     */
    fun setRightIconPosition(gravity: Int) = apply {
        alert?.setRightIconPosition(gravity)
    }

    /**
     * Set the onClickListener for the Alert
     *
     * @param onClickListener The onClickListener for the Alert
     * @return This Alerter
     */
    fun setOnClickListener(onClickListener: View.OnClickListener) = apply {
        alert?.setOnClickListener(onClickListener)
    }

    /**
     * Set the on screen duration of the alert
     *
     * @param milliseconds The duration in milliseconds
     * @return This Alerter
     */
    fun setDuration(milliseconds: Long) = apply {
        alert?.duration = milliseconds
    }

    /**
     * Enable or Disable Icon Pulse Animations
     *
     * @param pulse True if the icon should pulse
     * @return This Alerter
     */
    fun enableIconPulse(pulse: Boolean) = apply {
        alert?.pulseIcon(pulse)
    }

    /**
     * Set whether to show the icon in the alert or not
     *
     * @param showIcon True to show the icon, false otherwise
     * @return This Alerter
     */
    fun showIcon(showIcon: Boolean) = apply {
        alert?.showIcon(showIcon)
    }

    /**
     * Enable or Disable Right Icon Pulse Animations
     *
     * @param pulse True if the right icon should pulse
     * @return This Alerter
     */
    fun enableRightIconPulse(pulse: Boolean) = apply {
        alert?.pulseRightIcon(pulse)
    }

    /**
     * Set whether to show the right icon in the alert or not
     *
     * @param showRightIcon True to show the right icon, false otherwise
     * @return This Alerter
     */
    fun showRightIcon(showRightIcon: Boolean) = apply {
        alert?.showRightIcon(showRightIcon)
    }

    /**
     * Set whether to show the animation on focus/pressed states
     *
     * @param enabled True to show the animation, false otherwise
     */
    fun enableClickAnimation(enabled: Boolean) = apply {
        alert?.enableClickAnimation(enabled)
    }

    /**
     * Sets the Alert Shown Listener
     *
     * @param listener OnShowAlertListener of Alert
     * @return This Alerter
     */
    fun setOnShowListener(listener: OnShowAlertListener) = apply {
        alert?.setOnShowListener(listener)
    }

    /**
     * Sets the Alert Hidden Listener
     *
     * @param listener OnHideAlertListener of Alert
     * @return This Alerter
     */
    fun setOnHideListener(listener: OnHideAlertListener) = apply {
        alert?.onHideListener = listener
    }

    /**
     * Enables swipe to dismiss
     *
     * @return This Alerter
     */
    fun enableSwipeToDismiss() = apply {
        alert?.enableSwipeToDismiss()
    }

    /**
     * Enable or Disable Vibration
     *
     * @param enable True to enable, False to disable
     * @return This Alerter
     */
    fun enableVibration(enable: Boolean) = apply {
        alert?.setVibrationEnabled(enable)
    }

    /**
     * Set sound Uri
     * if set null, sound will be disabled
     *
     * @param uri To set sound Uri (raw folder)
     * @return This Alerter
     */
    @JvmOverloads
    fun setSound(uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) = apply {
        alert?.setSound(uri)
    }

    /**
     * Disable touch events outside of the Alert
     *
     * @return This Alerter
     */
    fun disableOutsideTouch() = apply {
        alert?.disableOutsideTouch()
    }

    /**
     * Enable or disable progress bar
     *
     * @param enable True to enable, False to disable
     * @return This Alerter
     */
    fun enableProgress(enable: Boolean) = apply {
        alert?.setEnableProgress(enable)
    }

    /**
     * Set the Progress bar color from a color resource
     *
     * @param color The color resource
     * @return This Alerter
     */
    fun setProgressColorRes(
        @ColorRes color: Int,
    ) = apply {
        alert?.setProgressColorRes(color)
    }

    /**
     * Set the Progress bar color from a color resource
     *
     * @param color The color resource
     * @return This Alerter
     */
    fun setProgressColorInt(
        @ColorInt color: Int,
    ) = apply {
        alert?.setProgressColorInt(color)
    }

    /**
     * Set if the Alert is dismissible or not
     *
     * @param dismissible true if it can be dismissed
     * @return This Alerter
     */
    fun setDismissable(dismissible: Boolean) = apply {
        alert?.setDismissible(dismissible)
    }

    /**
     * Set a Custom Enter Animation
     *
     * @param animation The enter animation to play
     * @return This Alerter
     */
    fun setEnterAnimation(
        @AnimRes animation: Int,
    ) = apply {
        alert?.enterAnimation = AnimationUtils.loadAnimation(alert?.context, animation)
    }

    /**
     * Set a Custom Exit Animation
     *
     * @param animation The exit animation to play
     * @return This Alerter
     */
    fun setExitAnimation(
        @AnimRes animation: Int,
    ) = apply {
        alert?.exitAnimation = AnimationUtils.loadAnimation(alert?.context, animation)
    }

    /**
     * Show a button with the given text, and on click listener
     *
     * @param text The text to display on the button
     * @param onClick The on click listener
     */
     
    fun addButton(
        text: CharSequence,
        onClick: View.OnClickListener,
    ) = addButton(text, R.style.AlertButton, onClick)
     
    fun addButton(
        text: CharSequence,
        @StyleRes style: Int,
        onClick: View.OnClickListener,
    ) = apply {
        alert?.addButton(text, style, onClick)
    }

    /**
     * Set the Button's Typeface
     *
     * @param typeface Typeface to use
     * @return This Alerter
     */
    fun setButtonTypeface(typeface: Typeface) = apply {
        alert?.buttonTypeFace = typeface
    }

    /**
     *  Set elevation of the alert background.
     *
     *  Only available for version LOLLIPOP and above.
     *
     *  @param elevation Elevation value, in pixel.
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setElevation(elevation: Float) = apply {
        alert?.setBackgroundElevation(elevation)
    }

    fun getLayoutContainer(): View? {
        return alert?.layoutContainer
    }
    
    fun dismiss() {
        alert?.hide()
    }

    companion object {
        private var decorView: WeakReference<ViewGroup>? = null

        /**
         * Creates the Alert
         *
         * @param activity The calling Activity
         * @return This Alerter
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            activity: Activity,
            layoutId: Int = R.layout.alerter_layout,
        ) = create(activity = activity, dialog = null, layoutId = layoutId)
        

        /**
         * Creates the Alert
         *
         * @param dialog The calling Dialog
         * @return This Alerter
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            dialog: Dialog,
            layoutId: Int = R.layout.alerter_layout,
        ) = create(activity = null, dialog = dialog, layoutId = layoutId)
        

        /**
         * Creates the Alert with custom view, and maintains a reference to the calling Activity or Dialog's
         * DecorView
         *
         * @param activity The calling Activity
         * @param dialog The calling Dialog
         * @param layoutId Custom view layout res id
         * @return This Alerter
         */
        @JvmStatic
        private fun create(
            activity: Activity? = null,
            dialog: Dialog? = null,
            @LayoutRes layoutId: Int,
        ): Alerter {
            val alerter = Alerter()

            // Hide current Alert, if one is active
            clearCurrent(activity, dialog)

            alerter.alert = dialog?.window?.let {
                decorView = WeakReference(it.decorView as ViewGroup)
                Alert(context = it.decorView.context, layoutId = layoutId)
            } ?: run {
                activity?.window?.let {
                    decorView = WeakReference(it.decorView as ViewGroup)
                    Alert(context = it.decorView.context, layoutId = layoutId)
                }
            }

            return alerter
        }

        /**
         * Cleans up the currently showing alert view, if one is present. Either pass
         * the calling Activity, or the calling Dialog
         *
         * @param activity The current Activity
         * @param dialog The current Dialog
         * @param listener OnHideAlertListener to known when Alert is dismissed
         */
        @JvmStatic
        @JvmOverloads
        fun clearCurrent(
            activity: Activity?,
            dialog: Dialog?,
            listener: OnHideAlertListener? = null,
        ) {
            dialog?.let {
                it.window?.decorView as? ViewGroup
            } ?: kotlin.run {
                activity?.window?.decorView as? ViewGroup
            }?.also {
                removeAlertFromParent(it, listener)
            } ?: listener?.onHide()
        }

        /**
         * Cleans up the currently showing alert view, if one is present. Either pass
         * the calling Activity, or the calling Dialog
         *
         * @param activity The current Activity
         * @param listener OnHideAlertListener to known when Alert is dismissed
         */
        @JvmStatic
        @JvmOverloads
        fun clearCurrent(
            activity: Activity?,
            listener: OnHideAlertListener? = null,
        ) {
            clearCurrent(activity, null, listener)
        }

        /**
         * Hides the currently showing alert view, if one is present
         * @param listener to known when Alert is dismissed
         */
        @JvmStatic
        @JvmOverloads
        fun hide(listener: OnHideAlertListener? = null) {
            decorView?.get()?.let {
                removeAlertFromParent(it, listener)
            } ?: listener?.onHide()
        }

        private fun removeAlertFromParent(
            decorView: ViewGroup,
            listener: OnHideAlertListener?,
        ) {
            // Find all Alert Views in Parent layout
            for (i in 0..decorView.childCount) {
                val childView = if (decorView.getChildAt(i) is Alert) decorView.getChildAt(i) as Alert else null
                if (childView != null && childView.windowToken != null) {
                    ViewCompat.animate(childView).alpha(0f).withEndAction(getRemoveViewRunnable(childView, listener))
                }
            }
        }

        /**
         * Check if an Alert is currently showing
         *
         * @return True if an Alert is showing, false otherwise
         */
        @JvmStatic
        val isShowing: Boolean
            get() {
                var isShowing = false

                decorView?.get()?.let {
                    isShowing = it.findViewById<View>(R.id.llAlertBackground) != null
                }

                return isShowing
            }

        private fun getRemoveViewRunnable(
            childView: Alert?,
            listener: OnHideAlertListener?,
        ): Runnable {
            return Runnable {
                childView?.let {
                    (childView.parent as? ViewGroup)?.removeView(childView)
                }
                listener?.onHide()
            }
        }
    }
}
