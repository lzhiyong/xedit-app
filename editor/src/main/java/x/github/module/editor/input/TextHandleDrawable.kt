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

import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A handle on the text: the cursor's, or one of the two ends of a selection.
 *
 * What it looks like is up to a subclass ([DrawnTextHandle], [ResourceTextHandle]),
 * and so is the one thing that differs between looks and that the rest of the
 * editor has to know about: **which side of its row the handle is on**.
 *
 *  - Most handles hang below the row they stand on, like those of Android's own
 *    text fields: the two ends of a selection then point away from each other,
 *    one to the left and one to the right, so that they do not cover each other
 *    however short the selection is.
 *  - A handle can also stand above its row ([heightAboveRow] > 0), as the start
 *    handle that the editor draws itself does. On the first row on screen there is
 *    no room above; it is then [isFlipped] below the row instead.
 *
 * Everything else is worked out from the **body**, the part that the finger takes
 * hold of, laid out by [layoutBody]: where a touch takes hold of the handle, where
 * it is on its knob, and how far it is from it. So a subclass only says where its
 * body is and how it is drawn.
 *
 * A handle is told where its tip is ([place]) and how much of it is there
 * ([presence]); it knows nothing about text. Coordinates are those of the canvas it
 * is drawn on.
 */
abstract class TextHandleDrawable(
    val kind: Kind,
    density: Float
) : Drawable() {

    enum class Kind { START, END, INSERTION }

    // A finger needs this much room, however small the handle looks (Material's
    // smallest touch target).
    private val minTouchSize = 48f * density

    /**
     * How far the handle reaches above the row it stands on, or 0 for a handle that
     * hangs below it. A handle that reaches above is put below its row instead when
     * there is not that much room above (see [isFlipped]).
     */
    abstract val heightAboveRow: Float

    /**
     * How much of the handle is there, from 0 (nothing) to 1 (all of it). In between
     * it is smaller and fainter; a little above 1 it is larger, which is what makes
     * it "pop" when it appears.
     */
    var presence: Float = 1f
        set(value) {
            field = value
            invalidateSelf()
        }

    /** Whether a finger is holding it: drawn a little larger. */
    var isHeld: Boolean = false
        set(value) {
            field = value
            invalidateSelf()
        }

    /**
     * Whether a handle that stands above its row was put below it, because there is
     * no room above (the first row on screen).
     */
    var isFlipped: Boolean = false
        private set

    /** Whether the body is above the row right now. */
    val isAboveRow: Boolean get() = heightAboveRow > 0f && !isFlipped

    // where it stands: the x of its tip, and the row it stands on
    protected var tipX = 0f
        private set

    /** The x of its tip, the point of the text it marks (content coordinates). */
    val tip: Float get() = tipX
    protected var rowTop = 0f
        private set
    protected var rowBottom = 0f
        private set

    /** The body, as [layoutBody] last laid it out. */
    protected val body = RectF()

    // the body, grown to the room a finger needs
    private val touchArea = RectF()

    /**
     * Lays out the body for where the handle stands now: [tipX], [rowTop] /
     * [rowBottom], and above or below the row as [isAboveRow] says.
     */
    protected abstract fun layoutBody(body: RectF)

    /**
     * The color the handles take (by default the theme's accent color). A handle
     * whose look comes from elsewhere (an image) may ignore it.
     */
    open fun applyColor(color: Int) {}

    /**
     * Puts the handle on a row: its tip at [tipX], on the row from [rowTop] to
     * [rowBottom]. [flipped] asks a handle that stands above its row to go below.
     */
    fun place(tipX: Float, rowTop: Float, rowBottom: Float, flipped: Boolean = false) {
        this.tipX = tipX
        this.rowTop = rowTop
        this.rowBottom = rowBottom
        this.isFlipped = flipped && heightAboveRow > 0f
        layoutBody(body)

        touchArea.set(body)
        if (touchArea.width() < minTouchSize) {
            touchArea.inset((touchArea.width() - minTouchSize) / 2f, 0f)
        }
        if (touchArea.height() < minTouchSize) {
            touchArea.inset(0f, (touchArea.height() - minTouchSize) / 2f)
        }

        // everything it may paint or be touched at, for whoever wants to know
        setBounds(
            Math.min(touchArea.left, tipX - 1f).toInt() - 1,
            Math.min(touchArea.top, rowTop).toInt() - 1,
            Math.max(touchArea.right, tipX + 1f).toInt() + 1,
            Math.max(touchArea.bottom, rowBottom).toInt() + 1
        )
        invalidateSelf()
    }

    /**
     * Whether a touch at ([x], [y]) takes hold of the handle.
     *
     * The area is the body with room for a finger around it, and from there one
     * line height on towards the text: down for a body above its row, up for one
     * below it. So a drag can also start on the row the handle stands on, and a
     * finger that lands between the body and the text does not miss.
     */
    fun isNear(x: Int, y: Int): Boolean {
        if (x < touchArea.left || x > touchArea.right) {
            return false
        }
        val lineHeight = rowBottom - rowTop
        return if (isAboveRow) {
            y >= touchArea.top && y <= touchArea.bottom + lineHeight
        } else {
            y >= touchArea.top - lineHeight && y <= touchArea.bottom
        }
    }

    /**
     * Whether ([x], [y]) is on the body itself, as opposed to the row of text the
     * handle stands on (which [isNear] counts in as well, so that a drag can start
     * there).
     *
     * The body has room for the finger on every side but one: towards the text it
     * ends where the row begins. Anything within the height of the row is text,
     * however close to the body; otherwise most of a row would belong to the handle
     * hanging under it, and pressing the text there would never be taken for
     * pressing the text.
     */
    fun isOnKnob(x: Int, y: Int): Boolean {
        if (x < touchArea.left || x > touchArea.right) {
            return false
        }
        return if (isAboveRow) {
            y >= touchArea.top && y < rowTop
        } else {
            y > rowBottom && y <= touchArea.bottom
        }
    }

    /** How far ([x], [y]) is from the middle of the body. */
    fun distanceToKnob(x: Int, y: Int): Float {
        val dx = x - body.centerX()
        val dy = y - body.centerY()
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /** The scale to draw at: how much of it is there, and a little more while held. */
    protected fun drawScale(): Float =
        presence.coerceAtLeast(0f) * (if (isHeld) 1.2f else 1f)

    /** The alpha to draw with, 0..255: how much of it is there. */
    protected fun drawAlpha(): Int =
        (255 * presence.coerceIn(0f, 1f)).toInt()

    override fun setAlpha(alpha: Int) {
        // the handle fades through its presence
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
