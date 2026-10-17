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

package x.github.module.editor.input

import android.animation.ValueAnimator
import android.os.Build
import android.view.animation.LinearInterpolator
import android.widget.Magnifier
import androidx.annotation.RequiresApi

/**
 * Moves a [Magnifier] the way EditText moves its own (Editor.MagnifierMotionAnimator).
 *
 * Along a row the magnifier simply follows. When it has to go to another row, it
 * does not jump: it slides there in a tenth of a second. A handle dragged down a page
 * of text changes rows all the time, and without this the magnifier would flicker
 * from row to row.
 */
@RequiresApi(Build.VERSION_CODES.P)
class MagnifierAnimator(private val magnifier: Magnifier) {

    private companion object {
        const val DURATION_MS = 100L
    }

    // whether the magnifier is on screen
    private var isShowing = false
    // where the magnifier was when the running animation started
    private var animationStartX = 0f
    private var animationStartY = 0f
    // where it is in the latest animation frame
    private var animationCurrentX = 0f
    private var animationCurrentY = 0f
    // where it was last asked to be
    private var lastX = 0f
    private var lastY = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = DURATION_MS
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            // on the way from where the animation started to where it was last asked to be
            animationCurrentX = animationStartX + (lastX - animationStartX) * animation.animatedFraction
            animationCurrentY = animationStartY + (lastY - animationStartY) * animation.animatedFraction
            magnifier.show(animationCurrentX, animationCurrentY)
        }
    }

    /**
     * Shows the magnifier at a new position (in the coordinates of the view it
     * magnifies). If the y is another one than before, which means another row of
     * text, the move there is animated.
     */
    fun show(x: Float, y: Float) {
        val startNewAnimation = isShowing && y != lastY

        if (startNewAnimation) {
            if (animator.isRunning) {
                animator.cancel()
                animationStartX = animationCurrentX
                animationStartY = animationCurrentY
            } else {
                animationStartX = lastX
                animationStartY = lastY
            }
            // the target has to be set before the first frame is computed
            lastX = x
            lastY = y
            animator.start()
        } else {
            if (!animator.isRunning) {
                magnifier.show(x, y)
            }
            lastX = x
            lastY = y
        }
        isShowing = true
    }

    /** Has the magnifier take a fresh picture of what is under it. */
    fun update() {
        if (isShowing) {
            magnifier.update()
        }
    }

    /** Takes the magnifier away, or does nothing if it is not there. */
    fun dismiss() {
        magnifier.dismiss()
        animator.cancel()
        isShowing = false
    }
}
