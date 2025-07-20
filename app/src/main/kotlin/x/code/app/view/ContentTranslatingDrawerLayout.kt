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

package x.code.app.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * A [DrawerLayout] that scales its content when navigation drawers are opened or closed.
 *
 * @author Akash Yadav
 */
class ContentTranslatingDrawerLayout : InterceptableDrawerLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
    ) : super(context, attrs, defStyleAttr)

    /**
     * The ID of the child view which will be translated when the navigation views are expanded/collapsed.
     *
     * Set this value to `-1` to disable transition.
     */
    var childId: Int = -1

    /**
     * The [TranslationBehavior] for the start navigation view.
     */
    var translationBehaviorStart: TranslationBehavior = TranslationBehavior.DEFAULT

    /**
     * The [TranslationBehavior] for the end navigation view.
     */
    var translationBehaviorEnd: TranslationBehavior = TranslationBehavior.DEFAULT
    
    private val listener = object : SimpleDrawerListener() {
        override fun onDrawerSlide(
            drawerView: View, slideOffset: Float,
        ) {
            if (childId == -1) {
                return
            }

            val gravity = (drawerView.layoutParams as LayoutParams).gravity
            val view = findViewById<View>(childId) ?: return
            val (direction, maxOffset) =
                if (gravity == GravityCompat.START) {
                    1 to translationBehaviorStart.maxOffset
                } else {
                    -1 to translationBehaviorEnd.maxOffset
                }

            val offset = (drawerView.width * slideOffset) * maxOffset
            view.translationX = direction * offset
        }        
    }

    init {
        addDrawerListener(listener)
    }

    /**
     * Translation behavior for content view of [ContentTranslatingDrawerLayout].
     */
    enum class TranslationBehavior(
        val maxOffset: Float,
    ) {
        /**
         * The default translation behavior. This makes the child view translate partially according to
         * the slide offset of the [NavigationView][com.google.android.material.navigation.NavigationView]
         */
        DEFAULT(0.2f),

        /**
         * Makes the child child view translate according to the slide offset of the
         * [NavigationView][com.google.android.material.navigation.NavigationView]. The translation offset
         * is always equal to the slide offset in this behavior.
         */
        FULL(0.95f),
    }
}
