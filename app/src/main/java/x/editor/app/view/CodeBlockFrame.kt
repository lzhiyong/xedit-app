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

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import x.github.module.editor.view.EditorView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The frame of an editor that is as high as its text, up to [maxLines] lines (the code
 * blocks of the AI assistant, AgentCodeBlock). An EditorView takes the size it is given
 * (it cannot wrap its content): this frame gives it the height of [lines] of its lines
 * and its padding. Told the line count again, or the editor laid out again in another text
 * size, it measures itself again.
 *
 * A tap on the editor (not a scroll of it, nor a press that the list around takes over for
 * its own scroll) is told to [onTap], if one is set. The touches still go to the editor.
 *
 * A drag that goes across more than up or down, once it is a drag (the touch slop), is the
 * editor's to its end (EditorView.setKeepsTouches): it scrolls the code sideways, and the
 * list around does not take it over when the finger then drifts up or down. Up or down
 * first, the list scrolls, as with any drag on it.
 *
 * With [fades], its foot fades into [fadeColor] (what is under the lines shown, faintly, as
 * the end of a text shown folded): over FADE_LINES lines and [roomBelow].
 */
class CodeBlockFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** How many lines the text has: the frame is as high as these, up to [maxLines]. */
    var lines: Int = 1
        set(value) {
            val lines = value.coerceAtLeast(1)
            if (field == lines) return
            field = lines
            requestLayout()
        }

    var maxLines: Int = 20
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Room under the last line, inside the editor's viewport (not its padding, which the
     * editor clips its drawing to): where the knob of a selection handle on the last line
     * hangs, and the space a padding would leave under the code.
     */
    var roomBelow: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var onTap: (() -> Unit)? = null

    /** Whether the foot fades out (see the class comment). */
    var fades: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** What the foot fades into: the ground the block is on. */
    var fadeColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            shaderHeight = -1
            invalidate()
        }

    private val fadePaint = Paint()
    // the height and the bottom the gradient of fadePaint was made for
    private var shaderHeight = -1
    private var shaderBottom = -1

    // a drag going on, from where it went down; decided: across (the editor's) or not
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var decided = false

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke() ?: return false
            return true
        }
    })

    // watched, never taken: the editor gets every touch as before. (Once the editor keeps
    // the touch, this is not asked again until the next one goes down.)
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        taps.onTouchEvent(ev)
        val editor = getChildAt(0) as? EditorView
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                decided = false
                editor?.setKeepsTouches(false)
            }
            MotionEvent.ACTION_MOVE -> if (!decided) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    decided = true
                    // across: the editor's to its end (it asks its parents not to take it,
                    // at this very move, before the list sees it go far enough up or down)
                    if (dx > dy) editor?.setKeepsTouches(true)
                }
            }
        }
        return false
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!fades || width == 0) return
        val editor = getChildAt(0) as? EditorView
        val lineHeight = editor?.getLineHeight()?.takeIf { it > 0 }
            ?: (FALLBACK_LINE_SP * resources.displayMetrics.scaledDensity).roundToInt()
        val bottom = height - paddingBottom
        val fade = (FADE_LINES * lineHeight + roomBelow).coerceAtMost(bottom)
        if (fade <= 0) return
        if (fade != shaderHeight || bottom != shaderBottom) {
            fadePaint.shader = LinearGradient(
                0f, (bottom - fade).toFloat(), 0f, bottom.toFloat(),
                ColorUtils.setAlphaComponent(fadeColor, 0), fadeColor, Shader.TileMode.CLAMP,
            )
            shaderHeight = fade
            shaderBottom = bottom
        }
        canvas.drawRect(0f, (bottom - fade).toFloat(), width.toFloat(), bottom.toFloat(), fadePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val editor = getChildAt(0) as? EditorView
        // before the editor knows its text size: about as high as one of its lines usually is
        val lineHeight = editor?.getLineHeight()?.takeIf { it > 0 }
            ?: (FALLBACK_LINE_SP * resources.displayMetrics.scaledDensity).roundToInt()
        val shown = lines.coerceIn(1, maxLines.coerceAtLeast(1))
        val height = paddingTop + paddingBottom + (editor?.paddingVertical ?: 0) + shown * lineHeight + roomBelow
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    private companion object {
        const val FALLBACK_LINE_SP = 18f
        // the lines (and the room below them) the foot fades over
        const val FADE_LINES = 3
    }
}
