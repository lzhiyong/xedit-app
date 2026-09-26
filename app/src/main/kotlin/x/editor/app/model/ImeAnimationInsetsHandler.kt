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
 
package x.editor.app.model

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.updateLayoutParams


class ImeAnimationInsetsHandler(
    private val drawerLayout: View,
    private val extraKeysPanel: View
) : OnApplyWindowInsetsListener, WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {

    fun attach(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root, this)
        ViewCompat.setWindowInsetsAnimationCallback(root, this)
    }

    // ---- 静止状态：直接按最终 insets 设置 padding ----
    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

        // 原来 drawerLayout.applyInsetsMargin() 做的事，搬到这里统一处理
        drawerLayout.updateLayoutParams<MarginLayoutParams> {
            topMargin = statusBar.top
        }

        extraKeysPanel.updatePadding(bottom = maxOf(ime.bottom, navBar.bottom))

        return insets
    }

    // ---- 动画期间：onPrepare 记起点，onStart 记终点，onProgress 插值 ----
    private var startBottom = 0f
    private var endBottom = 0f

    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
            // 动画开始前、view 还没被重新 layout 时的位置
            startBottom = extraKeysPanel.bottom.toFloat()
        }
    }

    override fun onStart(
        animation: WindowInsetsAnimationCompat,
        bounds: WindowInsetsAnimationCompat.BoundsCompat
    ): WindowInsetsAnimationCompat.BoundsCompat {
        // 此时 onApplyWindowInsets 已经把 padding 应用为最终态，
        // view.bottom 已经是动画结束后的位置
        endBottom = extraKeysPanel.bottom.toFloat()
        return bounds
    }

    override fun onProgress(
        insets: WindowInsetsCompat,
        runningAnimations: MutableList<WindowInsetsAnimationCompat>
    ): WindowInsetsCompat {
        val imeAnimation = runningAnimations.find {
            it.typeMask and WindowInsetsCompat.Type.ime() != 0
        } ?: return insets

        // 用 translationY 把面板从起点位置逐帧插值回最终位置
        extraKeysPanel.translationY =
            (startBottom - endBottom) * (1 - imeAnimation.interpolatedFraction)

        return insets
    }

    override fun onEnd(animation: WindowInsetsAnimationCompat) {
        if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
            extraKeysPanel.translationY = 0f
        }
    }
}

