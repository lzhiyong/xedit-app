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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * [target] shown, or hidden; hidden with animate, it closes upwards from its height to none
 * and fades (faster than it closes), the sheet it is in shrinking with it, rather than going
 * at once (the editor of a sheet: ConfigListSheet, ToolchainSheet). Shown again while it
 * closes: it stops closing, and is there.
 *
 * Its content keeps its size meanwhile (not laid out again at every frame: an editor), cut
 * to what is left of it.
 */
class CollapseAnimator(private val target: ViewGroup) {

    // the closing going on; null: none
    private var animator: ValueAnimator? = null

    /** Whether it is closing now. */
    val isCollapsing: Boolean get() = animator != null

    fun setVisible(visible: Boolean, animate: Boolean = false) {
        // one closing: stopped (put back as it was), whatever comes now
        animator?.cancel()
        if (visible || !animate || target.visibility != View.VISIBLE || !target.isLaidOut || target.height == 0) {
            target.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        collapse()
    }

    private fun collapse() {
        val from = target.height
        val child = target.getChildAt(0)
        val childParams = child?.layoutParams
        val childHeight = childParams?.height
        if (child != null && childParams != null) {
            childParams.height = child.height
            child.layoutParams = childParams
        }
        val clip = Rect(0, 0, target.width, from)
        animator = ValueAnimator.ofInt(from, 0).apply {
            duration = DURATION_MILLIS
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { animation ->
                val height = animation.animatedValue as Int
                target.layoutParams = target.layoutParams.apply { this.height = height }
                clip.set(0, 0, target.width, height)
                target.clipBounds = clip
                // faded before it is closed
                target.alpha = (1f - animation.animatedFraction * FADE_SPEED).coerceAtLeast(0f)
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                    if (!canceled) target.visibility = View.GONE
                    target.layoutParams = target.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    target.clipBounds = null
                    target.alpha = 1f
                    if (child != null && childParams != null && childHeight != null) {
                        childParams.height = childHeight
                        child.layoutParams = childParams
                    }
                }
            })
            start()
        }
    }

    companion object {
        // how long it takes to close, and how much faster than that it fades
        const val DURATION_MILLIS = 240L
        const val FADE_SPEED = 1.6f
    }
}
