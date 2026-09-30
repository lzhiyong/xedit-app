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

package x.github.module.editor.view

import android.graphics.drawable.Drawable

/**
 * Geometry and overlay operations shared by every controller that needs to know about
 * EditorView's current size/scroll/padding, or needs to add/remove a drawable on top of
 * it (ScrollbarController today; SelectionController and others later). Each controller
 * declares its own narrower `XXGeometry : EditorGeometry` interface adding only the
 * extra members it personally needs, instead of repeating these eight every time.
 *
 * Note the viewWidth/viewHeight/viewScrollX/viewScrollY names: they deliberately do NOT
 * reuse View's own width/height/scrollX/scrollY. A Kotlin property with those names would
 * compile to getWidth()/getHeight()/getScrollX()/getScrollY(), which clash with View's
 * final Java getters, so EditorView forwards them explicitly instead.
 */
interface EditorGeometry {
    val viewWidth: Int
    val viewHeight: Int
    val paddingHorizontal: Int
    val paddingVertical: Int
    val viewScrollX: Int
    val viewScrollY: Int
    val maxScrollX: Int
    val maxScrollY: Int

    fun addOverlay(drawable: Drawable)
    fun removeOverlay(drawable: Drawable)
}
