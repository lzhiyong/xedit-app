/*
 * Copyright © 2026 Github Lzhiyong
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

package x.editor.app.view

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.R as MaterialR

// the longest the first frame of a sheet is held back, whatever it is waiting for
private const val MAX_HOLD_MS = 500L

/**
 * The sheet shown, its first frame held back until it has its final height, so it slides in
 * at that height and does not change it on the way.
 *
 * A sheet is not at its final height on its first layouts:
 *  - BottomSheetBehavior starts listening to the insets on its first layout, so the padding
 *    for the navigation bar comes a layout later, and what is fitted to it (AgentSheet) one
 *    more after that: too low, then too high, then right;
 *  - what is read from disk comes after the first frame ([contentReady]).
 *
 * The window animation starts with the first frame drawn, so a frame not drawn here is only
 * a moment more before the sheet comes in. Held until [contentReady] says so and two layouts
 * in a row agree on where the sheet is; at most [MAX_HOLD_MS]: what takes longer than that is
 * shown coming in. (As the splash screen's keep-on-screen does: false from onPreDraw, and the
 * frame is tried again at the next vsync.)
 */
internal fun BottomSheetDialog.showSettled(contentReady: () -> Boolean = { true }) {
    show()
    val decor = window?.decorView ?: return
    val sheet = findViewById<View>(MaterialR.id.design_bottom_sheet) ?: return
    val shownAt = SystemClock.uptimeMillis()
    var lastTop = -1
    var lastHeight = -1
    keepSheetInPlace(sheet)
    decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            val same = sheet.top == lastTop && sheet.height == lastHeight
            lastTop = sheet.top
            lastHeight = sheet.height
            val settled = same && sheet.height > 0 && !decor.isLayoutRequested && contentReady()
            if (!settled && SystemClock.uptimeMillis() - shownAt < MAX_HOLD_MS) return false
            // (from the view: the observer of before it was attached is no longer alive)
            decor.viewTreeObserver.removeOnPreDrawListener(this)
            return true
        }
    })
}

/**
 * The sheet not moved by an insets animation other than the keyboard's.
 *
 * BottomSheetBehavior puts a callback on the sheet that moves it back to where it was before
 * an insets animation and on to where it is now (translationY), for it to ride on the keyboard.
 * It does so at the start of any insets animation, but only the keyboard's then moves it on:
 * for any other the sheet stays that far off until the animation ends, and then jumps. One
 * runs as a window opens, while the sheet is getting its first layouts, so it is taken to
 * have come from the top of the window, or from where it was with its list still empty: seen
 * as a sheet up at the top that drops, or down low that jumps up, on some openings and not on
 * others, by how the two happen to fall.
 *
 * Its callback cannot be got at, so it stays, as it is for the keyboard. This one is on the
 * parent, to which each frame of an animation is told too: after the start, before the frame
 * is drawn, and always with the animations running, so the offset is gone before it is seen.
 * Before Android 11 there are no such animations, and the callback would take the place of
 * the parent's own listener.
 */
private fun BottomSheetDialog.keepSheetInPlace(sheet: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val parent = findViewById<View>(MaterialR.id.coordinator) ?: return
    val ime = WindowInsetsCompat.Type.ime()
    val mode = WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
    ViewCompat.setWindowInsetsAnimationCallback(parent, object : WindowInsetsAnimationCompat.Callback(mode) {
        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
            if (runningAnimations.none { it.typeMask and ime != 0 } && sheet.translationY != 0f) sheet.translationY = 0f
            return insets
        }
    })
}
