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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

/**
 * A selection made by a long press or a double tap does not just appear: it grows
 * out from where the finger was to its full size. This keeps how far it has grown
 * ([fraction], 0 to 1) and from where ([pivotX]); the painter draws the selection
 * that much of its width, around the pivot. [invalidate] is asked for a frame on
 * every step. Main thread.
 */
class SelectionReveal(
    private val durationMillis: Long,
    private val invalidate: () -> Unit
) {

    /** How far the selection has grown, 0 to 1; 1 when nothing is growing. */
    var fraction = 1f
        private set

    /** The x (content coordinates) the selection grows out from. */
    var pivotX = 0
        private set

    private var animator: ValueAnimator? = null

    /**
     * The selection between [left] and [right] (its two ends, in content x) grows out
     * from [x], or from the nearest point inside it if the finger was a little off.
     */
    fun start(x: Int, left: Int, right: Int) {
        animator?.cancel()
        pivotX = x.coerceIn(Math.min(left, right), Math.max(left, right))
        val growing = ValueAnimator.ofFloat(0f, 1f)
        growing.duration = durationMillis
        growing.interpolator = DecelerateInterpolator()
        growing.addUpdateListener {
            fraction = it.animatedValue as Float
            // have the selection painted again at its new size
            invalidate()
        }
        growing.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // also when it was cut short: never leave a selection half shown
                fraction = 1f
                invalidate()
                if (animator === animation) {
                    animator = null
                }
            }
        })
        fraction = 0f
        animator = growing
        growing.start()
    }
}
