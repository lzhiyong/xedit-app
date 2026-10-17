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

package x.github.module.editor

import android.graphics.drawable.Drawable


/**
 * Geometry and overlay operations shared by every controller that needs to know about
 * EditorView's current size/scroll/padding, or needs to put a drawable on top of the
 * text. Each controller declares its own narrower `Host : EditorGeometry` interface
 * adding only the extra members it personally needs, instead of repeating these.
 *
 * Two coordinate systems are in play, and padding is the difference between them:
 *
 *  - **view coordinates**: what touch events come in. (0, 0) is the top left corner
 *    of the view, padding included.
 *  - **content coordinates**: what everything else uses, the text, the cursor, the
 *    handles, the scrollbar thumbs. (0, 0) is the top left corner of the text.
 *
 *     content = view + scroll - padding (left / top)
 *
 * The **viewport** is the part of the view inside its padding, the window the content
 * is seen through. It shows the content from (viewScrollX, viewScrollY) to
 * (viewScrollX + viewportWidth, viewScrollY + viewportHeight); nothing is ever drawn
 * in the padding.
 *
 * Note the viewWidth/viewHeight/viewScrollX/viewScrollY names: they deliberately do NOT
 * reuse View's own width/height/scrollX/scrollY. A Kotlin property with those names would
 * compile to getWidth()/getHeight()/getScrollX()/getScrollY(), which clash with View's
 * final Java getters, so EditorView forwards them explicitly instead.
 */
interface EditorGeometry {
    /** The size of the whole view, padding included. */
    val viewWidth: Int
    val viewHeight: Int
    /** Left + right padding, top + bottom padding. */
    val paddingHorizontal: Int
    val paddingVertical: Int
    /** The size of the viewport: the view without its padding. */
    val viewportWidth: Int
    val viewportHeight: Int
    /**
     * The height of the part of the viewport that can actually be seen: the viewport
     * without what the soft keyboard (and a bar riding on top of it) covers at its
     * bottom. The same as [viewportHeight] when nothing is covered, which is always
     * the case in a window that is resized for the keyboard.
     *
     * Whatever has to stay in sight or within reach belongs inside this height, not
     * inside [viewportHeight]: the cursor, the scrollbars, the glow at the bottom edge.
     */
    val visibleHeight: Int
    val viewScrollX: Int
    val viewScrollY: Int
    val maxScrollX: Int
    val maxScrollY: Int

    /** Puts [drawable] on top of the text. Its bounds are in content coordinates. */
    fun addOverlay(drawable: Drawable)
    fun removeOverlay(drawable: Drawable)

    /**
     * Whether [drawable] is currently attached to the overlay (i.e. actually on screen).
     * Hit-testing must check this: a handle/thumb that was removed from the overlay
     * still keeps its last bounds, and must not swallow touches while invisible.
     */
    fun hasOverlay(drawable: Drawable): Boolean
}
