/*
 * Copyright © 2022 Github Lzhiyong
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

import android.app.Activity
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.drawable.Drawable

import android.graphics.text.MeasuredText
import android.graphics.text.LineBreaker
import android.graphics.text.LineBreaker.ParagraphConstraints

import android.icu.text.BreakIterator
import android.os.Build
import android.os.SystemClock
import android.os.TransactionTooLargeException
import android.os.Vibrator
import android.os.VibrationEffect
import android.text.InputType
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.ActionMode
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOverlay
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Magnifier
import android.widget.OverScroller
import android.widget.Toast
import android.widget.EdgeEffect

import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.Runtime

import kotlin.text.Regex

import x.github.module.editor.EditorStack
import x.github.module.editor.SavedState
import x.github.module.editor.TypefaceDescriptor
import x.github.module.editor.util.*
import x.github.module.editor.R

import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.CharCode
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.TextChange
import x.github.module.piecetable.common.Strings


open class EditorView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr),
    GestureDetector.OnGestureListener, 
    ScaleGestureDetector.OnScaleGestureListener {
    
    // text select handle drawable resources
    protected var cursorBarThumb: Drawable
    protected var selectHandleLeft: Drawable
    protected var selectHandleRight: Drawable
    protected var selectHandleMiddle: Drawable
    
    // vertical and horizontal scrollbar
    protected var vertScrollbarThumb: Drawable
    protected var horizScrollbarThumb: Drawable
    
    // vertical and horizontal scrollbar animation
    protected var vertScrollbarAnimator: ValueAnimator
    protected var horizScrollbarAnimator: ValueAnimator
    
    protected var textLayout: TextLayout
    protected val textPaint: TextPaint
    protected val stack: EditorStack
    protected val cursor: TextCursor
    protected val selection: TextSelector
    
    protected val clipboard: ClipboardManager
    protected val scroller: OverScroller
    protected val gestureDetector: GestureDetector
    protected val scaleGestureDetector: ScaleGestureDetector
    protected var pieceTreeBuffer: PieceTreeTextBuffer
        
    // Edge effect / overscroll tracking objects.
    protected val edgeEffectTop: EdgeEffect
    protected val edgeEffectBottom: EdgeEffect
    protected val edgeEffectLeft: EdgeEffect
    protected val edgeEffectRight: EdgeEffect

    protected var edgeEffectTopActive = false
    protected var edgeEffectBottomActive = false
    protected var edgeEffectLeftActive = false
    protected var edgeEffectRightActive = false
    
    protected var actionMode: ActionMode? = null
    // internal class OverlayViewGroup
    protected var overlayViewGroup: Any? = null
    protected var verifyDrawable: Method? = null
    
    protected var searchResults = mutableListOf<Range>()
    protected var mergeTextChanges = listOf<TextChange>()
    // cache the RenderNode for hardware accelerated
    protected var cacheRenderNodes = mutableListOf<TextRenderNode>()
    
    protected var defaultPaintColor: Int = Color.GRAY
    
    // logging tag
    private val LOG_TAG = this::class.simpleName
    
    protected val magnifier: Magnifier? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val overlay = context.getDrawable(R.drawable.ic_magnifier_overlay)!!
            Magnifier.Builder(this).apply {
                setElevation(10f)
                setSize(overlay.intrinsicWidth, overlay.intrinsicHeight)
                setInitialZoom(1.5f)
                setOverlay(overlay)
            }.build()
        } else { null /* only support Android Q above */ }
    }
    
    data class TextRenderNode(
        var line: Int = 1,
        val name: String? = null,
        val renderNode: RenderNode = RenderNode(name),
        var isDirty: Boolean = true
    )
    
    
    init {
        textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
            // the default text size and font and color                        
            color = defaultPaintColor
            typeface = Typeface.DEFAULT
            textSize = 15f.sp
            setSubpixelText(true)
            setLinearText(true)            
        } 
        
        // the cursor drawable resource
        cursorBarThumb = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_text_cursor_material,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        
        // the text select handle drawable resources
        selectHandleLeft = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_text_select_handle_left,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        
        selectHandleRight = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_text_select_handle_right,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        
        selectHandleMiddle = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_text_select_handle_middle,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
        }
        
        val colorStateList = ResourcesCompat.getColorStateList(
            context.resources,
            R.drawable.scrollbar_state_lists, 
            context.getTheme()
        )
        
        // vertical scrollbar thumb drawable
        vertScrollbarThumb = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_vert_scrollbar_thumb,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }
        
        // horizontal scrollbar thumb drawable
        horizScrollbarThumb = ResourcesCompat.getDrawable(
            context.resources,
            R.drawable.ic_horiz_scrollbar_thumb,
            null
        )!!.apply {
            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            setTintList(colorStateList)
        }
        
        // animation for vertical scrollbar
        vertScrollbarAnimator = ValueAnimator.ofInt(0, vertScrollbarThumb.bounds.width()).apply {
            setDuration(1000L)
            setInterpolator(FastOutLinearInInterpolator())
            
            addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    getOverlay().remove(vertScrollbarThumb)
                }
            })
            
            addUpdateListener { animator ->
                val value = animator.getAnimatedValue() as Int
                with(vertScrollbarThumb) {
                    val bounds = copyBounds()
                    bounds.offset(value, 0)
                    setBounds(bounds)
                }
            }
        }
        
        // animation for horizontal scrollbar
        horizScrollbarAnimator = ValueAnimator.ofInt(0, horizScrollbarThumb.bounds.height()).apply {
            setDuration(1000L)
            setInterpolator(FastOutLinearInInterpolator())
            
            addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    getOverlay().remove(horizScrollbarThumb)
                }
            })
            
            addUpdateListener { animator ->
                val value = animator.getAnimatedValue() as Int
                with(horizScrollbarThumb) {
                    val bounds = copyBounds()
                    bounds.offset(0, value)
                    setBounds(bounds)
                }
            }
        }
        
        // Sets up edge effects
        edgeEffectTop = EdgeEffect(context)
        edgeEffectBottom = EdgeEffect(context)
        edgeEffectLeft = EdgeEffect(context)
        edgeEffectRight = EdgeEffect(context)
        
        pieceTreeBuffer = PieceTreeTextBufferBuilder().build()

        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        scroller = OverScroller(context)
        gestureDetector = GestureDetector(context, this).apply {
            setIsLongpressEnabled(true)
        }
        scaleGestureDetector = ScaleGestureDetector(context, this)
        
        textLayout = LinearLayout(this)
        stack = EditorStack()
        cursor = TextCursor(null)
        selection = TextSelector(null)
        
        // java reflection non-sdk api
        getOverlay().javaClass.getDeclaredFields().firstOrNull {
            it.name == "mOverlayViewGroup"
        }?.let { field ->
            field.setAccessible(true)
            // ViewOverlayOverlayViewGroup
            overlayViewGroup = field.get(getOverlay())
                
            overlayViewGroup!!.javaClass.getDeclaredMethods().firstOrNull {
                it.name == "verifyDrawable"
            }?.let { method ->
                method.setAccessible(true)
                verifyDrawable = method
            }              
        }
    }
    
    fun setText(text: String) {
        Range(1, 1, getLineCount(), getLineEndColumn()).also {
            applyEdits(listOf(SingleEditOperation(it, text)), false)
        }       
    }
    
    // this should be running on backgroud thrrad
    fun setTextSize(pxSize: Float) {
        textPaint.textSize = pxSize
        textLayout.relayout()
        updateDisplayList()
        updateDrawableBounds()
        invalidate()
    }

    fun setBuffer(textBuffer: PieceTreeTextBuffer) {
        scrollTo(0, 0)
        recycleRenderNode()
        stack.clear()  
        selection.cancel() 
        cursor.cancelBlink(false, false)              
              
        pieceTreeBuffer = textBuffer   
        cursor.setPosition(1, 1)
        cursor.makeBlink()
        textLayout.relayout()
        invalidate()
    }
    
    // this should be running on backgroud thrrad
    fun setTypeface(typeface: Typeface) {
        textPaint.typeface = typeface
        textLayout.relayout()
        updateDisplayList()
        updateDrawableBounds()
        invalidate()
    }

    fun setEditable(value: Boolean) {
        cursorBarThumb.setVisible(value, false)
        /*when(editable) {
            true -> addFocus()
            else -> {
                hideSoftKeyboard()
                removeFocus()
            }
        }*/
    }
    
    // this should be running on backgroud thrrad
    fun setWordwrap(value: Boolean) {
        horizScrollbarThumb.setVisible(value, false)
        val recycleLayout = textLayout
        textLayout = if (value)            
            WordwrapLayout(this).apply{ relayout() }
        else
            LinearLayout(this).apply{ relayout() }                         
                
        // free memory
        recycleLayout.recycle()
    }
    
    fun addFocus() {
        if(!isSelected() && cursor.isCancelled()) {
            cursor.makeBlink()
        }
        requestFocus()
        invalidate()
    }
    
    fun removeFocus() {
        cursor.cancelBlink(false, false)
        clearFocus()
        invalidate()
    }
    
    // this method should be run on main thread
    // use find to get the matches
    fun setSearchResults(
        matches: MutableList<Range>?
    ): Boolean {
        searchResults.clear()
        if(matches != null && matches.size > 0) {
            searchResults = matches
        }
        invalidate()
        return true
    }
    
    fun setSelection(range: Range) {
        if(range.isEmpty()) {
            // set the range as cursor position
            selection.cancel()
            cursor.setPosition(range.getStartPosition())
            cursor.cancelBlink(true, false)
            moveTo(cursor.getX(), cursor.getY())
            cursor.makeBlink()
        } else {
            // set the range as selection
            cursor.cancelBlink(false, false)
            selection.select(range)
            scrollToMatchRange(range)
        }
        invalidate()
    }
    
    fun getSelection() = if(isSelected()) {
        selection.clone() 
    } else {
        Range.fromPositions(cursor)
    }
    
    // the default paint color
    fun setPaintColor(color: Int) {
        defaultPaintColor = color
    }
    
    fun setMarginLayout(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
        with(layoutParams as MarginLayoutParams) {
            // set the margin params
            leftMargin = left
            topMargin = top
            rightMargin = right
            bottomMargin = bottom
        }
        requestLayout()
    }
    
    open fun preserveState(
        uriPath: String, 
        fileHash: String,
        fileModified: Boolean = false,
        fontPath: String? = null
    ): SavedState {     
        return SavedState(
            uri = uriPath,
            hash = fileHash,
            modified = fileModified,
            position = Position(cursor.lineNumber, cursor.column),
            range = Range(
                selection.startLine, 
                selection.startColumn,
                selection.endLine, 
                selection.endColumn
            ),
            layoutWidth = textLayout.width(),
            layoutHeight = textLayout.height(),
            textSize = textPaint.getTextSize(),
            fontDescriptor = TypefaceDescriptor(
                textPaint.typeface.getSystemFontFamilyName(),
                fontPath,
                textPaint.typeface.getStyle()
            ),
            breakResults = textLayout.getBreakResults(),
            undoStack = stack.getUndoStack(),
            redoStack = stack.getRedoStack(),          
            matchResults = searchResults            
        )
    }
    
    open fun restoreState(
        savedState: SavedState, 
        textBuffer: PieceTreeTextBuffer
    ) {        
        with(savedState) {
             textPaint.setTextSize(textSize)
             textPaint.setTypeface(fontDescriptor.toTypeface(context))
             
             textLayout.setWidth(layoutWidth)
             textLayout.setHeight(layoutHeight)
             textLayout.setBreakResults(breakResults)
             
             stack.setUndoStack(undoStack)
             stack.setRedoStack(redoStack)
                          
             searchResults = matchResults             
             pieceTreeBuffer = textBuffer
             
             cursor.setPosition(position)
             selection.select(range)
        }
    }
    
    fun refresh() {
        val dx = cursor.getX() - getWidth() / 2
        val dy = cursor.getY() - getHeight() / 2
        smoothScrollTo(
            Math.max(0, Math.min(dx, maxScrollX)),
            Math.max(0, Math.min(dy, maxScrollY))
        )
        // update the render node display datas
        updateDisplayList()
        // refresh the editor
        invalidate()
    }
    
    override fun isSelected() = !selection.isEmpty()
    // check if cusor drawable visible
    fun isEditable() = cursorBarThumb.isVisible()
    
    // text start indent
    fun getStartSpacing() = getLineCount().count * measureText("0") + spacingWidth * 4
    // text end indent
    fun getEndSpacing() = spacingWidth * 4
    
    fun getLineHeight() = textPaint.fontMetricsInt.run { bottom - top }
    
    // \n or \r\n
    fun getLineEOL() = pieceTreeBuffer.getEOL()

    fun getLineCount() = pieceTreeBuffer.getLineCount()
    
    fun getLineLength(lineNumber: Int) = pieceTreeBuffer.getLineLength(lineNumber)
    
    fun getBufferLength() = pieceTreeBuffer.length
    
    fun getRangeLength(range: Range) = pieceTreeBuffer.getValueLengthInRange(range)
    
    fun getPosition(offset: Int) = pieceTreeBuffer.getPositionAt(offset)
    
    fun getOffset(lineNumber: Int, column: Int) = pieceTreeBuffer.getOffsetAt(lineNumber, column)
    
    fun getOffset(pos : Position) = getOffset(pos.lineNumber, pos.column)
    
    fun getCharAt(lineNumber: Int, column: Int) = pieceTreeBuffer.run { getCharCode(getOffsetAt(lineNumber, column)).toChar() }
    
    fun getLineCharAt(lineNumber: Int, offset: Int) = pieceTreeBuffer.getLineCharCode(lineNumber, offset).toChar()
    
    fun getLine(lineNumber: Int) = pieceTreeBuffer.getLineContent(lineNumber)

    fun getLineWithEOL(lineNumber: Int) = pieceTreeBuffer.getLineContentWithEOL(lineNumber)

    fun getLineStart(lineNumber: Int) = getOffset(lineNumber, 1)

    fun getLineEnd(lineNumber: Int) = getOffset(lineNumber, pieceTreeBuffer.getLineMaxColumn(lineNumber))
    
    fun getLineWidth(lineNumber: Int) = getStartSpacing() + textLayout.getLineWidth(lineNumber)
    
    fun getLineTextWidth(lineNumber: Int) = textLayout.getLineWidth(lineNumber)
    
    fun getLineNumberWidth(lineNumber: Int = getLineCount()) = measureText(lineNumber.toString())
    
    fun getLineStartColumn(lineNumber: Int) = pieceTreeBuffer.getLineMinColumn(lineNumber)

    fun getLineEndColumn(lineNumber: Int = getLineCount()) = pieceTreeBuffer.getLineMaxColumn(lineNumber)
    
    fun getValueInRange(range: Range) = pieceTreeBuffer.getValueInRange(range)
    
    fun getText() = pieceTreeBuffer.toString()
    
    fun getTextBuffer() = this.pieceTreeBuffer
    
    fun getLineWidthAdvance(lineNumber: Int, startColumn: Int, endColumn: Int) = measureText(
        pieceTreeBuffer.getValueInRange(
            Range(lineNumber, startColumn, lineNumber, endColumn)
        )
    )
    
    fun getTextWidths(text: String, start: Int = 0, end: Int = text.length) = FloatArray(end - start).also { widths ->
        // get the width of each character
        textPaint.getTextWidths(text, start, end, widths)
    }
    
    fun measureText(text: String) = textPaint.measureText(text).toInt()
    
    // middle to baseline spacing = (descent - ascent) / 2 - descent
    // middle position = ((lineNumber * lineHeight) + (lineNumber - 1) * lineHeight) / 2
    // baseline = middle + spacing
    fun getBaseLine(lineNumber: Int) = textPaint.fontMetricsInt.run {
        (lineNumber * 2 - 1) * (bottom - top) / 2 + (descent - ascent) / 2 - descent
    }
    
    // get the number of grapheme for emoji character
    fun getCharCount(text: String, offset: Int) = getCharEnd(text, offset) - offset
    
    // find the start boundary of a character
    fun getCharStart(text: String, endOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // end boundary
            following(endOffset)
            // start boundary
            previous()
        }
    }
    
    // find the end boundary of a character
    fun getCharEnd(text: String, startOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // requires startOffset < text.length
            // end boundary
            following(startOffset)
        }
    }
    
    // get the selected word boundary
    fun findWordBoundary(text: String, offset: Int): IntArray {
        return BreakIterator.getWordInstance().run {
            setText(text)
            // first to get the end boundary
            val end = following(offset)
            // then to get the start boundary
            val start = previous()
            if(end != BreakIterator.DONE) 
                intArrayOf(start, end)
            else 
                intArrayOf(start, start)            
        }
    }
    
    // convert dip unit to pixiel
    open val Float.dp: Int get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        context.resources.displayMetrics
    ).toInt()
    
    // convert sp unit to pixiel
    open val Float.sp: Float get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        context.resources.displayMetrics
    )
    
    // the HashMap cache capacity
    // note that this value cannot be too large
    // otherwise it will affect the efficiency of text editing and memory usage
    open val NODE_CACHE_SIZE: Int get() = 64
    
    // the minimum text size
    open val MIN_TEXT_SIZE: Float get() = 10f.sp
    
    // the maximum text size
    open val MAX_TEXT_SIZE: Float get() = 25f.sp
    
    // the scroll delta
    open val View.SCROLL_DELTA: Int get() = 30f.dp
    
    // whitespace character width
    val spacingWidth: Int
        get() = measureText("\t") // here \t equals spacing
    
    val Int.count: Int
        get() = Math.log10(this.toDouble()).toInt() + 1
    
    val View.paddingVertical: Int
        get() = paddingTop + paddingBottom

    val View.paddingHorizontal
        get() = paddingLeft + paddingRight

    val View.maxScrollX: Int
        get() = Math.max(0, paddingHorizontal + getStartSpacing() + textLayout.width() + getEndSpacing() - getWidth())

    val View.maxScrollY: Int
        get() = Math.max(0, paddingVertical + textLayout.height() + 2 * getLineHeight() - getHeight())
    
    // animation start time
    protected var lastAnimationTimeMills = 0L
    
    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param dx the number of pixels to scroll by on the X axis
     * @param dy the number of pixels to scroll by on the Y axis
     */
    fun smoothScrollBy(dx: Int, dy: Int) {
        if (getHeight() == 0) {
            // Nothing to do.
            return
        }
        val duration = AnimationUtils.currentAnimationTimeMillis() - lastAnimationTimeMills
        if (duration > 250L) {
            scroller.startScroll(scrollX, scrollY, dx, dy)
            postInvalidateOnAnimation()
        } else {
            if (!scroller.isFinished) {
                scroller.abortAnimation()
            }
            scrollBy(dx, dy)
        }
        lastAnimationTimeMills = AnimationUtils.currentAnimationTimeMillis()
    }

    /**
     * Like {@link #scrollTo}, but scroll smoothly instead of immediately.
     *
     * @param x the position where to scroll on the X axis
     * @param y the position where to scroll on the Y axis
     */
    fun smoothScrollTo(x: Int, y: Int) {
        smoothScrollBy(x - scrollX, y - scrollY)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {           
            // horizontal absorb           
            if (
                scroller.currX < 0 &&
                edgeEffectLeft.isFinished() &&
                !edgeEffectLeftActive
            ) {                
                edgeEffectLeft.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectLeftActive = true
            } else if (
                scroller.currX > maxScrollX &&
                edgeEffectRight.isFinished() &&
                !edgeEffectRightActive
            ) {
                edgeEffectRight.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectRightActive = true               
            }           
            
            // vertical absorb
            if (
                scroller.currY < 0 &&
                edgeEffectTop.isFinished() &&
                !edgeEffectTopActive
            ) {
                edgeEffectTop.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectTopActive = true                
            } else if (
                scroller.currY > maxScrollY &&
                edgeEffectBottom.isFinished() &&
                !edgeEffectBottomActive
            ) {                
                edgeEffectBottom.onAbsorb(scroller.currVelocity.toInt())
                edgeEffectBottomActive = true
            }
                        
            scrollTo(
               Math.max(0, Math.min(scroller.currX, maxScrollX)), 
               Math.max(0, Math.min(scroller.currY, maxScrollY))
            )

            postInvalidateOnAnimation()
        }
    }    
    
    protected val vertScrollbarAction = object: Runnable {
        override fun run() {
            if(!vertScrollbarThumb.isPressed) {
                // start animation
                vertScrollbarAnimator.start()
            }
        }
    }
    
    protected val horizScrollbarAction = object: Runnable {
        override fun run() {
            if(!horizScrollbarThumb.isPressed) {
                // start animation
                horizScrollbarAnimator.start()
            }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        if(scaleGestureDetector.isInProgress()) {
            // nothing to do
            return@onScrollChanged
        }
        
        // when invalidate cursor in visible rect
        /*if (cursor.requiresBlink()) {
            
        }*/
        
        handler.removeCallbacks(vertScrollbarAction)
        handler.removeCallbacks(horizScrollbarAction)
        
        with(vertScrollbarThumb) {
            val deltaY = ((getHeight() - paddingVertical - bounds.height()).toFloat() * scrollY / maxScrollY).toInt()
            setBounds(
                scrollX + getWidth() - paddingHorizontal - bounds.width(),
                scrollY + deltaY,
                scrollX + getWidth() - paddingHorizontal,
                scrollY + deltaY + bounds.height()
            )
        }       
        
        with(horizScrollbarThumb) {
            val deltaX = ((getWidth() - paddingHorizontal - bounds.width()).toFloat() * scrollX / maxScrollX).toInt()
            setBounds(
                scrollX + deltaX,                    
                scrollY + getHeight() - paddingVertical - bounds.height(),
                scrollX + deltaX + bounds.width(),
                scrollY + getHeight() - paddingVertical
            )
        }    
        
        // vertical scroll
        if (Math.abs(t - oldt) >= Math.abs(l - oldl)) {
            // hide horizintal scrollbar
            getOverlay().remove(horizScrollbarThumb)
            // show vertical scrollbar          
            getOverlay().add(vertScrollbarThumb)
            
            handler.postDelayed(vertScrollbarAction, 2000)
            
        } else {
            // hide vertical scrollbar
            getOverlay().remove(vertScrollbarThumb)
            // show horizontal scrollbar
            getOverlay().add(horizScrollbarThumb)
            
            handler.postDelayed(horizScrollbarAction, 2000)
        }
    }
    
    override fun onAttachedToWindow() {
        requestFocus()
        setFocusable(true)
        setFocusableInTouchMode(true)
        // cursor start blink
        cursor.setPosition(1, 1)
        cursor.makeBlink()    
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
    
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // drawer layout conflict with the vertical scrollbar
        if (vertScrollbarThumb.isPressed) {
            getParent().requestDisallowInterceptTouchEvent(true)
        } else {
            getParent().requestDisallowInterceptTouchEvent(false)
        }

        return super.dispatchTouchEvent(e)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec), 
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // scroll to visible region
        if(changed) {
            moveTo(cursor.getX(), cursor.getY())
        }
    }
    
    // #region draw
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun recycleRenderNode() {
        val iter = cacheRenderNodes.iterator()
        while (iter.hasNext()) {
            // free the node memory
            iter.next().renderNode.discardDisplayList()
            // delete the current item
            iter.remove()
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun updateDisplayList() {
        cacheRenderNodes.forEach { node ->
            node.isDirty = true
            node.renderNode.setPosition(0, 0, Int.MAX_VALUE, getLineHeight())
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    protected fun getRenderNode(line: Int): TextRenderNode {        
        val node = cacheRenderNodes.getOrNull((line - 1) % NODE_CACHE_SIZE)        
        // the node is in the cache array
        if (node != null && node.line != line) {
            node.line = line
            node.isDirty = true
        }
        
        // if the node is non-null meanning which is within the visible range
        // otherwise meanning the node is not in the cache array 
        // we need to create an new node
        return node ?: TextRenderNode(line).also {
            it.renderNode.setPosition(0, 0, Int.MAX_VALUE, getLineHeight())
            cacheRenderNodes.add(it)
        }
    }
    
    // draw rect with int values
    protected fun<L, T, R, B> Canvas.drawRect(left: L, top: T, right: R, bottom: B, paint: Paint = textPaint) 
    where L: Number, T: Number, R: Number, B: Number {
        drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }
    
    /**
     * Draws the overscroll "glow" at the four edges of the chart region, if necessary. The edges
     * of the chart region are stored in {@link #ContentRect}.
     *
     * @see EdgeEffect
     */
    protected open fun drawEdgeEffect(canvas: Canvas) {
        // The methods below rotate and translate the canvas as needed before drawing the glow,
        // since the EdgeEffect always draws a top-glow at 0,0.
        var needsInvalidate = false
        
        if (!edgeEffectTop.isFinished()) {
            val restoreCount = canvas.save()
            canvas.translate(0f, 0f)
            edgeEffectTop.setSize(getWidth(), getHeight())
            if (edgeEffectTop.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectBottom.isFinished()) {
            val restoreCount = canvas.save()
            canvas.translate(-getWidth().toFloat(), getHeight().toFloat())
            edgeEffectBottom.setSize(getWidth(), getHeight())
            canvas.rotate(180f, getWidth().toFloat(), 0f)
            
            if (edgeEffectBottom.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectLeft.isFinished()) {
            val restoreCount = canvas.save()
            canvas.translate(0f, getHeight().toFloat())
            canvas.rotate(-90f, 0f, 0f);
            edgeEffectLeft.setSize(getHeight(), getWidth())
            if (edgeEffectLeft.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (!edgeEffectRight.isFinished()) {
            val restoreCount = canvas.save()
            canvas.translate(getWidth().toFloat(), 0f)
            canvas.rotate(90f, 0f, 0f)
            edgeEffectRight.setSize(getHeight(), getWidth())
            if (edgeEffectRight.draw(canvas)) {
                needsInvalidate = true
            }
            canvas.restoreToCount(restoreCount)
        }

        if (needsInvalidate) {
            postInvalidateOnAnimation()
        }
    }
    
    /**
     * @rLine really line number
     * @mLine measured line number
     * @start start column 
     * @end end column 
     * @indent start indent spacing
     */
    protected open fun drawMatchesBackground(
        canvas: Canvas, rLine: Int, mLine: Int, startColumn: Int, endColumn: Int
    ) {        
        // here requests really line number
        searchResults.findBy(rLine, startColumn, rLine, endColumn).forEach { range ->       
            val result = textLayout.getLineResult(mLine)
            val startLine = textLayout.getIndexAt(range.startLine, range.startColumn) + 1
            // note here the end column need to decrease 1
            val endLine = textLayout.getIndexAt(range.endLine, range.endColumn - 1) + 1           
            // highlight selection background color
            textPaint.color = Color.CYAN
            
            if (mLine >= startLine && mLine <= endLine) {
                if (startLine == endLine) {
                    canvas.drawRect(
                        getStartSpacing() + getLineWidthAdvance(rLine, result.start + 1, range.startColumn), 
                        (mLine - 1) * getLineHeight(), 
                        getStartSpacing() + getLineWidthAdvance(rLine, result.start + 1, range.endColumn), 
                        mLine * getLineHeight()
                    )
                } else if (mLine == startLine) {
                    canvas.drawRect(
                        getStartSpacing() + getLineWidthAdvance(rLine, result.start + 1, range.startColumn), 
                        (mLine - 1) * getLineHeight(), 
                        getStartSpacing() + result.width, 
                        mLine * getLineHeight()
                    )
                } else if (mLine == endLine) {
                    canvas.drawRect(
                        getStartSpacing(), 
                        (mLine - 1) * getLineHeight(), 
                        getStartSpacing() + getLineWidthAdvance(rLine, result.start + 1, range.endColumn), 
                        mLine * getLineHeight()
                    )
                } else {
                    canvas.drawRect(
                        getStartSpacing(), 
                        (mLine - 1) * getLineHeight(), 
                        getStartSpacing() + result.width, 
                        mLine * getLineHeight()
                    )
                }
            }            
            
            // restore to default color
            textPaint.color = defaultPaintColor
        }        
    }
     
    protected open fun drawSelectionBackground(canvas: Canvas, line: Int) {
        // highlight selection background color
        textPaint.color = Color.argb(136, 204, 204, 204)
        
        val result = textLayout.getLineResult(line)
        val startLine = textLayout.getIndexAt(selection.startLine, selection.startColumn) + 1
        // note here the end column need to decrease 1
        val endLine = textLayout.getIndexAt(selection.endLine, selection.endColumn - 1) + 1
        
        // check if in selection rect
        if (line >= startLine && line <= endLine) {
             if (startLine == endLine) {
                 canvas.drawRect(
                    selectHandleLeft.bounds.right, 
                    selectHandleLeft.bounds.top - getLineHeight(),
                    selectHandleRight.bounds.left,
                    selectHandleRight.bounds.top
                )
             } else if (line == startLine) {
                canvas.drawRect(
                    selectHandleLeft.bounds.right, 
                    selectHandleLeft.bounds.top - getLineHeight(),
                    getStartSpacing() + result.width + spacingWidth, // +spacing extra width
                    selectHandleLeft.bounds.top
                )
            } else if (line == endLine) {
                canvas.drawRect(
                    getStartSpacing(), 
                    selectHandleRight.bounds.top - getLineHeight(),
                    selectHandleRight.bounds.left,
                    selectHandleRight.bounds.top
                )
            } else {
                canvas.drawRect(
                    getStartSpacing(), 
                    (line - 1) * getLineHeight(),
                    getStartSpacing() + result.width + spacingWidth, // +spacing extra width
                    line * getLineHeight()
                )
            }
        }
                
        // restore to default color
        textPaint.color = defaultPaintColor
    }
    
    protected open fun drawLineBackground(canvas: Canvas, line: Int) {
        // highlight current line background color
        textPaint.color = Color.argb(68, 204, 204, 204)               
        canvas.drawRect(
            scrollX,
            (line - 1) * getLineHeight(),
            scrollX + getWidth(),
            line * getLineHeight()
        )
        
        // restore to default color
        textPaint.color = defaultPaintColor
    }
    
    // override this method to implement your own logic
    protected open fun drawLineNumber(
        canvas: Canvas, line: Int, x: Float, y: Float
    ) {
        // align line numbers with respect to font
        // line number fixed width => (line length) * (character 0 width)
        val fixed = line.count * measureText("0")
        // line number really width => getLineNumberWidth
        val width = getLineNumberWidth(line)
        // scale = character width / text size
        val space = (fixed - getLineNumberWidth(line)) / line.count / textPaint.textSize
        val x = getStartSpacing() - fixed - spacingWidth * 3.0f
        // set the letter spacing
        textPaint.setLetterSpacing(space)
        // line number color
        textPaint.color = Color.GRAY
        
        // draw the line number
        canvas.drawText(line.toString(), x, y, textPaint)
        
        // restore to default color and letter spacing
        textPaint.setLetterSpacing(0f)
        textPaint.color = defaultPaintColor
    }
    
    // override this method to implement your own logic
    // draw text for unwrap mode
    protected open fun drawText(
        canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, xPaint: Float, yPaint: Float
    ) {
        canvas.drawText(text, startOffset, endOffset, xPaint, yPaint, textPaint)
    } 
    
    /**
     * @rLine really line number, which equals the cursor line number
     * @mLine measured line number, which equals the scrolled distance
     */
    protected open fun drawRegionHighlight(
        canvas: Canvas, rLine: Int, mLine: Int, start: Int, end: Int
    ) {
        if (!isSelected()) {
            // highlight the current line background
            // note here needs measured line at word wrap mode
            // at single line mode rLine == mLine
            if (rLine == cursor.lineNumber) {
                drawLineBackground(canvas, mLine)
            }
        } else {
            // highlight the selection region
            if (rLine >= selection.startLine && rLine <= selection.endLine) {
                drawSelectionBackground(canvas, mLine)
            }
        }
        
        // highlight the find items
        drawMatchesBackground(canvas, rLine, mLine, start + 1, end + 1)
    }
    
    // override this method to implement your own logic
    // draw text for word wrap mode
    open fun onWordwrapSoftwareDraw(canvas: Canvas) {        
        // the text start indent
        val spacing = getStartSpacing()
        // visible start line number        
        var line = scrollY / getLineHeight() + 1
        var result = textLayout.getLineResult(line)
        
        while (
            result.line <= getLineCount() &&
            line * getLineHeight() <= scrollY + getHeight() + getLineHeight()
        ) {            
            val text = getLine(result.line)           
            val paintX = spacing.toFloat()
            val paintY = getBaseLine(line).toFloat()
            
            val start = Math.min(result.start, text.length)
            val end = Math.min(result.end, text.length)
            
            drawRegionHighlight(canvas, result.line, line, start, end)
            
            // start index position
            if (line == textLayout.getStartIndex(result.line) + 1) {                
                // draw the really line number
                drawLineNumber(canvas, result.line, 0f, paintY)
            }            
            
            // draw current line text
            drawText(canvas, text, result.line, start, end, paintX, paintY)
            // continue to next line
            result = textLayout.getLineResult(++line)
        }
    }
    
    // hardware accelerated
    @RequiresApi(Build.VERSION_CODES.Q)
    open fun onWordwrapHardwareDraw(canvas: Canvas) {        
        // visible start line number        
        var line = scrollY / getLineHeight() + 1              
        
        val paintX = getStartSpacing().toFloat()
        val paintY = getBaseLine(1).toFloat()
                        
        while (
            line <= textLayout.count() &&
            line * getLineHeight() <= scrollY + getHeight() + getLineHeight()
        ) {            
            val result = textLayout.getLineResult(line)
            val text = getLine(result.line)           
            val start = Math.min(result.start, text.length)
            val end = Math.min(result.end, text.length)
            
            drawRegionHighlight(canvas, result.line, line, start, end)
            
            // get the current line RenderNode
            val node = getRenderNode(line)
            
            // update the displaylist
            if(node.isDirty || !node.renderNode.hasDisplayList()) {
                // start recording
                val recordingCanvas = node.renderNode.beginRecording()
                try {
                    // start index position
                    if (line == textLayout.getStartIndex(result.line) + 1) {                
                        // draw the really line number
                        drawLineNumber(recordingCanvas, result.line, 0f, paintY)
                    }
                                        
                    // draw the line content
                    drawText(recordingCanvas, text, result.line, start, end, paintX, paintY)     
                } finally {
                    // end recording
                    node.renderNode.endRecording()
                    node.isDirty = false                                    
                }               
            }
            
            // translate to target position
            node.renderNode.setTranslationY((line - 1f) * getLineHeight())
            // draw render node
            canvas.drawRenderNode(node.renderNode)
            // continue to next line
            line++
        }
    }
    
    // no hardware accelerated
    open fun onSoftwareDraw(canvas: Canvas) {               
        // the text start indent
        val spacing = getStartSpacing()
        // visible start line number        
        var line = scrollY / getLineHeight() + 1
        
        // draw the text content of the visible lines
        while (
            line <= getLineCount() &&
            line * getLineHeight() <= scrollY + getHeight() + getLineHeight()
        ) {
            val text = getLine(line)
            // the actual width measured
            val widths = FloatArray(1) { 0f }
            
            // visible start and end column indexes
            val start = textPaint.breakText(text, true, (scrollX - spacing).toFloat(), widths)
            val end = start + textPaint.breakText(text, start, text.length, true, (scrollX + getWidth()).toFloat(), null)
            val paintX = spacing + widths[0]            
            val paintY = getBaseLine(line).toFloat()
                     
            // draw region highlight
            drawRegionHighlight(canvas, line, line, start, end)
            
            // draw the line number
            drawLineNumber(canvas, line, 0f, paintY)            
            
            // draw the line content
            drawText(canvas, text, line, start, end, paintX, paintY)
            
            // continue to next line
            line++
        }
    }   
    
    @RequiresApi(Build.VERSION_CODES.Q)
    open fun onHardwareDraw(canvas: Canvas) {        
        // the text start indent
        val spacing = getStartSpacing()
        // visible start line number        
        var line = scrollY / getLineHeight() + 1
        
        val paintX = spacing.toFloat()
        // note here the line number must be 1 for render node
        val paintY = getBaseLine(1).toFloat()            
            
        // draw the text content of the visible lines
        while (
            line <= getLineCount() &&
            line * getLineHeight() <= scrollY + getHeight() + getLineHeight()
        ) {           
            // the line text content
            val text = getLine(line)           
            // draw region highlight
            drawRegionHighlight(canvas, line, line, 0, text.length)
            
            // get the current line RenderNode
            val node = getRenderNode(line)
            
            // update the displaylist
            if(node.isDirty || !node.renderNode.hasDisplayList()) {
                // start recording
                val recordingCanvas = node.renderNode.beginRecording()
                try {
                    // draw the line number
                    drawLineNumber(recordingCanvas, line, 0f, paintY)                    
                    // draw the line content
                    drawText(recordingCanvas, text, line, 0, text.length, paintX, paintY)                 
                } finally {
                    // end recording
                    node.renderNode.endRecording()
                    node.isDirty = false                                    
                }               
            }
            
            // translate to target position
            node.renderNode.setTranslationY((line - 1f) * getLineHeight())
            // draw render node
            canvas.drawRenderNode(node.renderNode)
            // continue to next line
            line++
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        canvas.clipRect(
            scrollX + paddingLeft, 
            scrollY + paddingTop,
            scrollX + width - paddingRight, 
            scrollY + height - paddingBottom
        )

        canvas.translate(
            paddingLeft.toFloat(), paddingTop.toFloat()
        )
        
        // draw the text content
        textLayout.draw(canvas)       
        // draw the edge effect
        drawEdgeEffect(canvas)
        canvas.restore()
    }

    // #endregion

    // #region input text

    fun showSoftKeyboard() {
        WindowCompat.getInsetsController((context as Activity).window ,this)?.let {
            it.show(WindowInsetsCompat.Type.ime())
        }
    }

    fun hideSoftKeyboard() {
        WindowCompat.getInsetsController((context as Activity).window ,this)?.let {
            it.hide(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object: BaseInputConnection(this, true) {

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                // insert some text
                this@EditorView.insert(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // delete some text
                this@EditorView.delete()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> insert(getLineEOL())
                        KeyEvent.KEYCODE_DEL -> delete()
                    }
                }
                return true
            }
        }
    }
    
    protected open fun beforeTextChanged(
        operations: List<SingleEditOperation>
    ) {
        // remove merge text changes action
        handler.removeCallbacksAndMessages(this)

        cursor.cancelBlink(true, false)
        selection.cancel()
        dismissActionMode()
    }
    
    // this method should be running on background thread
    // override this method to implement your own operations
    protected open fun onTextChanged(
        range: Range,
        rangeOffset: Int,
        insertedLinesCnt: Int,
        insertedTextLength: Int, 
        deletedLinesCnt: Int,       
        deletedTextLength: Int, 
        finalLineNumber: Int, 
        finalColumn: Int
    ) {          
        // update the text layout
        textLayout.update(range.startLine, insertedLinesCnt, deletedLinesCnt)
        // note measure should be run on background thread
        textLayout.measure()
        // TODO
        // here update your tokenize or syntax tree
    }

    protected open fun afterTextChanged(
        changes: List<ContentChange>,
        lastLineNumber: Int,
        lastColumn: Int
    ) {
        // update the display list for rende node
        if (!cacheRenderNodes.isEmpty()) {
            updateDisplayList()
        }
                
        // set cursor position
        cursor.setPosition(lastLineNumber, lastColumn)
        moveTo(cursor.getX(), cursor.getY())        
        // cursor start blink
        cursor.makeBlink()      
        invalidate()
    }
    
    protected fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean = true
    ) {
        // before text changed
        beforeTextChanged(operations)        
        // real text edits
        val result = pieceTreeBuffer.applyEdits(operations, false, computeUndoEdits)
        
        var lastLineNumber: Int = 1
        var lastColumn: Int = 1
        
        // results
        result.changes.forEachIndexed { index, change ->
            // compute the changed lines
            val (insertingLinesCnt, _, lastLineLength, _) = Strings.countEOL(change.text!!)
            val deletingLinesCnt = change.range.endLine - change.range.startLine
            //
            var finalLineNumber = change.range.startLine + insertingLinesCnt
            var finalColumn = change.range.endColumn
            
            // no need to check change.text ≠ null
            if (change.text!!.length > 0) {
                // insert or replace text (insert lines 0)
                finalColumn = when(insertingLinesCnt) {
                    0 -> change.range.startColumn + lastLineLength
                    else -> lastLineLength + 1
                }
            } else {
                // delete text
                finalColumn = change.range.startColumn
            }           
            
            // batch edits
            if(index == 0) {
                lastLineNumber = finalLineNumber
                lastColumn = finalColumn
            }
                        
            // update the lines and syntax tree
            onTextChanged(
                range = change.range, 
                rangeOffset = change.rangeOffset,
                insertedLinesCnt = insertingLinesCnt,
                insertedTextLength = change.text!!.length, 
                deletedLinesCnt = deletingLinesCnt,
                deletedTextLength = change.rangeLength,
                finalLineNumber = finalLineNumber, 
                finalColumn = finalColumn
            )           
        }
                
        if (computeUndoEdits) {
            // for undo and redo operations
            pushEdits(result.reverseEdits)            
        }
        
        // after text changed
        afterTextChanged(
            result.changes, lastLineNumber, lastColumn
        )
    }
    
    // Combine multiple edit operations within a specified time
    protected open fun onMergedBefore() {
        // Implement your own logic
        // TODO        
    }
    
    // Combine multiple edit operations within a specified time
    protected open fun onMergedAfter() {
        // Implement your own logic
        // TODO
    }

    protected fun pushEdits(
        reverseEdits: List<ReverseEditOperation>?, 
        delay: Long = 500L
    ) {
        // clear the redo stack when a new edit pushed
        stack.clearRedo()
        
        onMergedBefore()

        reverseEdits?.let { reverses ->
            /*
            reverses.toMutableList().sortWith(Comparator{ a, b ->
                if(a.textChange.oldPosition == b.textChange.oldPosition) {
                    a.sortIndex - b.sortIndex
                }
                a.textChange.oldPosition - b.textChange.oldPosition
            })
            */

            mergeTextChanges = TextChange.compressConsecutiveTextChanges(
                mergeTextChanges,
                reverses.map { it.textChange }
            )
            
            // delay 500ms then merge the edits
            handler.postDelayed({
                // push to the editor stack
                stack.push(mergeTextChanges)
                // clear the merge list
                mergeTextChanges = emptyList()
                // now we can undo
                onMergedAfter()
            }, this@EditorView, delay)
        }
    }

    open fun undo(): Boolean {
        val changes = stack.undo()
        val operations = mutableListOf<SingleEditOperation>()
        changes.forEach {
            val posStart = getPosition(it.newPosition)
            val posEnd = getPosition(it.newEnd)
            val range = Range(
                posStart.lineNumber, posStart.column,
                posEnd.lineNumber, posEnd.column
            )
            operations.add(SingleEditOperation(range, it.oldText))
        }

        applyEdits(operations, false)           
        
        return stack.canUndo()
    }

    open fun redo(): Boolean {
        val changes = stack.redo()
        val operations = mutableListOf<SingleEditOperation>()
        changes.forEach {
            val posStart = getPosition(it.oldPosition)
            val posEnd = getPosition(it.oldEnd)
            val range = Range(
                posStart.lineNumber, posStart.column,
                posEnd.lineNumber, posEnd.column
            )
            operations.add(SingleEditOperation(range, it.newText))
        }

        applyEdits(operations, false)
        
        return stack.canRedo()
    }
    
    // editor action
    protected enum class TextAction { INSERT, DELETE }

    protected fun getDefaultRange(action: TextAction): Range {
        if (isSelected()) {
            // on select mode
            return selection.clone()
        }
        
        // get the default range for edit text
        return when(action) { // text op action
            // action insert text
            TextAction.INSERT -> Range.fromPositions(cursor)
            // action delete text
            TextAction.DELETE -> {
                if (cursor.column == 1 && cursor.lineNumber == 1) {
                    // at the first position, index 0
                    Range.fromPositions(cursor)
                } else if (cursor.column == 1 && cursor.lineNumber > 1) {
                    // delete the line feed (\n)
                    Range(
                        cursor.lineNumber - 1,
                        pieceTreeBuffer.getLineMaxColumn(cursor.lineNumber - 1),
                        cursor.lineNumber,
                        cursor.column
                    ) 
                } else {
                    Range(cursor.lineNumber, 1, cursor.lineNumber, cursor.column).apply {
                        // reset range start column, may contains unicode characters
                        startColumn = getCharStart(getValueInRange(this), endColumn - 1) + 1
                    }
                }
            }
        }
    }

    // insert text
    open fun insert(
        text: String, // not allow null string
        range: Range = getDefaultRange(TextAction.INSERT)
    ) {
        if (isEditable()) {
            applyEdits(listOf(SingleEditOperation(range, text)))
        }
    }

    // delete text
    open fun delete(
        range: Range = getDefaultRange(TextAction.DELETE)
    ) {
        if (isEditable() && !range.isEmpty()) {
            applyEdits(listOf(SingleEditOperation(range, null)))
        }
    }

    // the binder transaction buffer has a limited fixed size, currently 1MB
    // see the android TransactionTooLargeException
    open fun copy(): Boolean {       
        val text = getValueInRange(selection)        
        if(!TextUtils.isEmpty(text)) {
            try {
                val content = ClipData.newPlainText("content", text)
                clipboard.setPrimaryClip(content)
                Toast.makeText(context, context.getString(R.string.copy_text_hint), Toast.LENGTH_LONG).show()
                return true
            } catch(e: Exception) {
                Toast.makeText(context, context.getString(R.string.copy_text_error), Toast.LENGTH_LONG).show()
                e.printStackTrace()
                return false
            }
        }
        return true
    }
    
    // cut text
    open fun cut() {
        // check if copy text success
        if (this.copy()) {
            this.delete()
        }
    }
    
    // paste text
    open fun paste() {
        if (clipboard.hasPrimaryClip()) {
            clipboard.getPrimaryClipDescription()?.let { desc ->
                if (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    clipboard.getPrimaryClip()?.let { clip ->
                        insert(clip.getItemAt(0).getText().toString())
                    }
                }
            }
        }
    }

    open fun selectAll() {
        cursor.cancelBlink(false, false)
        cursor.setPosition(getLineCount(), 1)
        
        selection.select(
            startLineNumber = 1, 
            startColumn = 1, 
            endLineNumber = getLineCount(), 
            endColumn = getLineEndColumn()
        )

        smoothScrollTo(0, maxScrollY)
        
        handler.postDelayed({ 
            showActionMode()
        }, 300L)
    }

    fun gotoLine(lineNumber: Int) {
        val targetLine = Math.max(1, Math.min(lineNumber, getLineCount()))
        cursor.setPosition(targetLine, 1)
        
        val dy = Math.min(maxScrollY, targetLine * getLineHeight() - getHeight() / 2)
        smoothScrollTo(0, Math.max(0, dy))
    }

    // find matches by regex
    fun find(
        regex: Regex,
        searchRange: Range = Range(1, 1, getLineCount(), getLineEndColumn()),
        limitResultCount: Int = Int.MAX_VALUE, 
        isCancelled: () -> Boolean = { false }
    ) = pieceTreeBuffer.find(regex, searchRange, limitResultCount, isCancelled)
    
    // find matches by word
    fun find(
        searchText: String,
        searchRange: Range = Range(1, 1, getLineCount(), getLineEndColumn()),
        limitResultCount: Int = Int.MAX_VALUE,
        isCancelled: () -> Boolean = { false }
    ) = pieceTreeBuffer.find(searchText, searchRange, limitResultCount, isCancelled)
    
    fun scrollToMatchRange(range: Range) {
        smoothScrollTo(
            Math.max(0, selectHandleLeft.bounds.right - getWidth() / 2),
            Math.max(0, selectHandleLeft.bounds.top - getHeight() / 2)
        )
        invalidate()
    }
    
    fun replace(
        replacement: String, 
        matches: MutableList<Range> = searchResults
    ) {
        if (isEditable() && matches.size > 0) {
            val operations = matches.map {
                SingleEditOperation(it, replacement)
            }
            applyEdits(operations, true)
        }
    }

    // #endregion

    fun showActionMode(x: Int = cursor.getX(), y: Int = cursor.getY()) {
        actionMode = startActionMode(
            object : ActionMode.Callback2() {

                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    mode.menuInflater.inflate(R.menu.action_mode_menu, menu)
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    return false
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.getItemId()) {
                        R.id.action_copy -> { copy() }
                        R.id.action_cut -> { cut() }
                        R.id.action_paste -> { paste() }
                        R.id.action_select_all -> { selectAll() }
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    // TODO
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                    // set the action mode location
                    outRect.set(x - scrollX, y - scrollY, x - scrollX, y - scrollY)
                }
            },
            ActionMode.TYPE_FLOATING
        )
    }

    fun dismissActionMode() {
        actionMode?.let { mode -> mode.finish() }
    }

    // #endregion
   
    // #region text cursor
    fun getLocationForPosition(lineNumber: Int, column: Int): IntArray {
        // get index and line break result
        val index = textLayout.getIndexAt(lineNumber, column)
        val result = textLayout.getLineResult(index + 1)
        // measured line number
        var line = index + 1
        // start column of current line
        var start = result.start + 1
        
        // check the start boundary of line
        // and previous line end boundary
        if (result.start + 1 == column && result.start - 1 > 0) {
            if (!isSelected()) {
                if (!getLineCharAt(lineNumber, result.start - 1).isAsciiLetter()) {
                    // previous line result
                    start = textLayout.getLineResult(--line).start + 1
                }
            } else {
                if (lineNumber == selection.endLine && column == selection.endColumn) {
                    // previous line result
                    start = textLayout.getLineResult(--line).start + 1
                }
            }
        }        
        
        return intArrayOf(
            getStartSpacing() + getLineWidthAdvance(lineNumber, start, column),
            line * getLineHeight()
        )
    }
    
    fun getLocationForPosition(pos: Position): IntArray {
        return getLocationForPosition(pos.lineNumber, pos.column)
    }
    
    // drawable dragged flag
    protected var Drawable.isPressed: Boolean
        set(value) {
            val stateSet = when(value) {
                true -> intArrayOf(android.R.attr.state_pressed)
                else -> intArrayOf() // empty states
            }
            setState(stateSet)
        }
        get() = getState().contains(android.R.attr.state_pressed)
    
    
    protected fun Drawable.contains(x: Int, y: Int): Boolean {
        return this.bounds.contains(x, y) && 
            verifyDrawable!!.invoke(overlayViewGroup!!, this) as Boolean
    }
    
    // update drawable bounds
    protected fun Drawable.updateLocation(x: Int, y: Int) {
        when(this) {
            cursorBarThumb -> {
                setBounds(
                    x - bounds.width() / 2,
                    y - getLineHeight(),
                    x + bounds.width() / 2,
                    y
                )
            }
            selectHandleMiddle -> {
                setBounds(
                    x - bounds.width() / 2, 
                    y, 
                    x + bounds.width() / 2,
                    y + bounds.height()
                )
            }
            selectHandleLeft -> {
                setBounds(
                    x - bounds.width(),
                    y, 
                    x,
                    y + bounds.height()
                )
            }
            selectHandleRight -> {
                setBounds(
                    x,
                    y, 
                    x + bounds.width(),
                    y + bounds.height()
                )
            }
        }
    }
    
    protected fun updateDrawableBounds() {
        // update the cursor drawable bounds
        run {
            val (x, y) = getLocationForPosition(cursor)
            cursorBarThumb.updateLocation(x, y)
            
            // update the select handle middle bounds
            selectHandleMiddle.updateLocation(
                cursor.getX(), 
                cursor.getY() + getLineHeight()
            )
        }
        
        // update the select handle bounds
        if(isSelected()) {
            run {
                val (x, y) = getLocationForPosition(selection.getStartPosition())
                selectHandleLeft.updateLocation(x, y)
            }
            
            run {
                val (x, y) = getLocationForPosition(selection.getEndPosition())
                selectHandleRight.updateLocation(x, y)
            }
        }
    }
    
    // Text Cursor
    protected inner class TextCursor(position: Position?) : Position() {
 
        constructor(lineNumber: Int, column: Int): this(Position(lineNumber, column))
        
        init {
            position?.let{ setPosition(it) }
        }
        
        fun setPosition(lineNumber: Int, column: Int) {
            this.lineNumber = lineNumber
            this.column = column           
            // update the cursor bounds
            val (x, y) = getLocationForPosition(lineNumber, column)
            cursorBarThumb.updateLocation(x, y)
        }
        
        fun setPosition(pos: Position) {
            setPosition(pos.lineNumber, pos.column)
        }
        
        fun setPosition(index: Int) {
            setPosition(getPosition(index))
        }       
        
        // set position with coordinates
        fun setCoordinates(x: Int, y: Int) {                       
            // measured line number
            val line = y / getLineHeight() + 1   
            val result = textLayout.getLineResult(line)    
            // here must be real line for current line text
            this.lineNumber = textLayout.getReallyLine(line)
            val text = getLine(lineNumber)
            
            val spacing = getStartSpacing()
            val offset = textPaint.getOffsetForAdvance(
                text, result.start, result.end, result.start, result.end, false, x - spacing.toFloat()
            )
            this.column = offset + 1
            
            cursorBarThumb.updateLocation(
                x = spacing + textPaint.getRunAdvance(
                    text, result.start, result.end, result.start, result.end, false, offset
                ).toInt(),
                y = Math.min(line, textLayout.count()) * getLineHeight()
            )
        }

        fun makeBlink(delay: Long, start: Long, interval: Long) {
            
            handler?.postDelayed({
                // toggle the cursor visible state
                when(verifyDrawable!!.invoke(overlayViewGroup!!, cursorBarThumb)) {
                    true -> {
                        //cursorBarThumb.setVisible(false, true)
                        getOverlay().remove(cursorBarThumb)
                        
                        // check select handle middle state
                        if(SystemClock.uptimeMillis() - start > interval) {
                            getOverlay().remove(selectHandleMiddle)
                        }
                    }
                    else -> {
                        //cursorBarThumb.setVisible(true, true)
                        getOverlay().add(cursorBarThumb)
                    }
                }
                
                // start continue blink
                makeBlink(delay, start, interval)
            }, this@TextCursor, delay)
        }
        
        fun makeBlink(delay: Long = 500L) {
	        val start = SystemClock.uptimeMillis()
	        handler?.sendEmptyMessage(0)
	        makeBlink(delay, start, 2500L)
        }
        
        fun cancelBlink(keepCursor: Boolean, keepHandleMiddle: Boolean) {
            handler?.removeCallbacksAndMessages(this@TextCursor)
            
            when(keepCursor) {
                true -> getOverlay().add(cursorBarThumb)
                else -> getOverlay().remove(cursorBarThumb)
            }
            
            when(keepHandleMiddle) {
                true -> getOverlay().add(selectHandleMiddle)
                else -> getOverlay().remove(selectHandleMiddle)
            }
        }
        
        // blink cancelled
        fun isCancelled(): Boolean {        
            handler?.run {
                return@isCancelled !hasMessages(0, this@TextCursor)
            }
            return@isCancelled true
        }
        
        // get the current offset of cursor in document
        fun getOffset() = getOffset(lineNumber, column)
        
        fun getX() = cursorBarThumb.run {
             bounds.left + bounds.width() / 2
        }
        
        fun getY() = cursorBarThumb.run {
            bounds.bottom - bounds.height()
        }
        
        fun requiresBlink() {
            // check if the cursor in visible rect
            if (
                this.getX() >= getScrollX() &&
                this.getX() <= getScrollX() + getWidth() &&
                this.getY() >= getScrollY() - getLineHeight() &&
                this.getY() <= getScrollY() + getHeight()
            ) {
                if (isCancelled()) {
                    // cursor start blink
                    makeBlink()
                }
            } else {
                cancelBlink(false, false)
            }             
        }
    }

    // #endregion

    // #region text select handle
    
    // Text Selector
    protected inner class TextSelector(range: Range?) : Range() {
        
        init {
            range?.let{ select(it) }
        }
        
        fun select(
             startLineNumber: Int, 
             startColumn: Int,
             endLineNumber: Int, 
             endColumn: Int
        ) {
            this.startLine = startLineNumber
            this.startColumn = startColumn
            this.endLine = endLineNumber
            this.endColumn = endColumn
            
            // check the select range
            if(startLine != endLine || startColumn != endColumn) {
                run {
                    val (x, y) = getLocationForPosition(startLine, startColumn)
                    selectHandleLeft.updateLocation(x, y)
                }
                
                run {   
                    val (x, y) = getLocationForPosition(endLine, endColumn)
                    selectHandleRight.updateLocation(x, y)
                }
                
                getOverlay().add(selectHandleLeft)
                getOverlay().add(selectHandleRight)
            }
        }
    
        fun select(range: Range) {
            this.select(
                range.startLine,
                range.startColumn,
                range.endLine,
                range.endColumn
            )
        }
    
        fun cancel() {
            getOverlay().remove(selectHandleLeft)
            getOverlay().remove(selectHandleRight)
            this.select(1, 1, 1, 1)
        }

        fun reverse() {
            // copy the left bounds
            val copyLeftBounds = selectHandleLeft.copyBounds()
            
            // left bounds = right bounds 
            selectHandleLeft.updateLocation(
                selectHandleRight.bounds.left, selectHandleRight.bounds.top
            )
            
            // right bounds = left bounds
            selectHandleRight.updateLocation(
                copyLeftBounds.right, copyLeftBounds.top
            )
        
            // startLine <-> endLine
            startLine = endLine.apply { 
                endLine = startLine 
            }
            
            // startColumn <-> endColumn
            startColumn = endColumn.apply { 
                endColumn = startColumn 
            }
        }
        
        fun valid() = !(startLine > endLine || startLine == endLine && startColumn > endColumn)

        fun contains(x: Int, y: Int): Boolean {
            if (!isSelected()) {
                return false
            }           

            if (
                y < selectHandleLeft.bounds.top - getLineHeight() || 
                y > selectHandleRight.bounds.top
            ) {
                return false
            }

            // on the same line
            if (selectHandleLeft.bounds.top == selectHandleRight.bounds.top) {
                if (x < selectHandleLeft.bounds.right || x > selectHandleRight.bounds.left) {
                    return false
                }
            } else {
                val spacing = getStartSpacing()
                // not on the same line
                val line = y / getLineHeight() + 1
                // at select start line
                if (line == selectHandleLeft.bounds.top / getLineHeight()) {
                    if (x < selectHandleLeft.bounds.right || x > getLineWidth(line)) {
                        return false
                    }
                } else if (line == selectHandleRight.bounds.top / getLineHeight()) {
                    // at select end line
                    if (x < spacing || x > selectHandleRight.bounds.left) {
                        return false
                    }
                } else {
                    // less left edge or more than right edge
                    if (x < spacing || x > getLineWidth(line)) {
                        return false
                    }
                }
            }
            return true
        }
    }

    // #endregion

    // #region gesture detector

    // scroll to target position
    protected fun moveTo(
        x: Int = cursor.getX(), // x offset postion
        y: Int = cursor.getY(),  // y offset position
        left: Int = SCROLL_DELTA,
        top: Int = getLineHeight(),
        right: Int = getWidth() - SCROLL_DELTA,
        bottom: Int = getHeight() - getLineHeight(),
    ): Boolean {
        // 
        var dx: Int = 0
        var dy: Int = 0

        if (x - scrollX < left) { dx = x - scrollX - left
        } else if (x - scrollX > right) { dx = x - scrollX - right
        }

        if (y - scrollY < top) { dy = y - scrollY - top
        } else if (y - scrollY > bottom) { dy = y - scrollY - bottom
        }

        // check the x bounds
        if (scrollX + dx < 0 /*|| scrollX + dx > maxScrollX && dx > 0*/) { dx = 0
        }

        // check the y bounds
        if (scrollY + dy < 0 || scrollY + dy > maxScrollY && dy > 0) { dy = 0
        }
        
        smoothScrollBy(dx, dy)
        return (dx != 0 || dy != 0)
    }
    
    fun showMagnifierView(
        x: Int = cursor.getX() - getScrollX(),
        y: Int = cursor.getY() - getScrollY()
    ) {
        magnifier?.show(x.toFloat(), y.toFloat())
        post { magnifier?.update() }
    }
    
    fun dismissMagnifierView() {
        magnifier?.dismiss()
    }
    
    protected fun releaseEdgeEffects() {
        edgeEffectTop.onRelease()
        edgeEffectBottom.onRelease()
        edgeEffectLeft.onRelease()
        edgeEffectRight.onRelease()
    }
    
    protected fun passtiveEdgeEffects() {
        edgeEffectTopActive = false
        edgeEffectBottomActive = false
        edgeEffectLeftActive = false
        edgeEffectRightActive = false
    }
    
    // #region GestureDetectorListener    
    
    // the long pressed flag
    protected var isLongPressed = false
    protected var isSelectedWord = false
    
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { 
                scroller.abortAnimation() 
            }
            MotionEvent.ACTION_MOVE -> {
                if (this.isLongPressed) {
                    // cancel the long press event
                    this.isLongPressed = false
                    e.setAction(MotionEvent.ACTION_CANCEL)
                }
            }
            MotionEvent.ACTION_UP -> { onUp(e) }
        }
        
        gestureDetector.onTouchEvent(e)
        scaleGestureDetector.onTouchEvent(e)
        return true
    }
    
    // drag and drop text in multi-window mode 
    override fun onDragEvent(event: DragEvent): Boolean {
        when(event.getAction()) {
            DragEvent.ACTION_DRAG_STARTED -> { }
            DragEvent.ACTION_DRAG_LOCATION -> { }
            DragEvent.ACTION_DRAG_EXITED -> { }
            DragEvent.ACTION_DROP -> { }
        }
        return super.onDragEvent(event)
    }
    
    override fun onDown(e: MotionEvent): Boolean {
        val x = e.x.toInt() + getScrollX() - getPaddingLeft()
        val y = e.y.toInt() + getScrollY() - getPaddingTop()
        
        if (selectHandleMiddle.contains(x, y)) {
            // press on the select handle middle
            selectHandleMiddle.isPressed = true
            cursor.cancelBlink(true, true)            
        } 
        else if (selectHandleLeft.contains(x, y)) {
            // press on the select handle left
            selectHandleLeft.isPressed = true
            cursor.cancelBlink(false, false)              
        } 
        else if (selectHandleRight.contains(x, y)) {
            // press on the select handle right
            selectHandleRight.isPressed = true
            cursor.cancelBlink(false, false)             
        } 
        else if (vertScrollbarThumb.contains(x, y)) {
            // press on the vertical scrollbar
            vertScrollbarThumb.isPressed = true
            handler.removeCallbacks(vertScrollbarAction)
            getParent().requestDisallowInterceptTouchEvent(true)
        } 
        else if (horizScrollbarThumb.contains(x, y)) {
            // press on the horizontal scrollbar
            horizScrollbarThumb.isPressed = true
            handler.removeCallbacks(horizScrollbarAction)
        }
        
        passtiveEdgeEffects()
        requestFocus()
        dismissActionMode()
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val x = e.x.toInt() + getScrollX() - getPaddingLeft()
        val y = e.y.toInt() + getScrollY() - getPaddingTop()
        
        when {
            selectHandleMiddle.contains(x, y) ||
            horizScrollbarThumb.contains(x, y) ||
            vertScrollbarThumb.contains(x, y) -> {
                return true
            }
            selectHandleLeft.contains(x, y) ||
            selectHandleRight.contains(x, y) ||
            selection.contains(x, y) -> {
                // here, requires set cusor position
                // the action mode location depend on cursor position
                cursor.setCoordinates(x, y)
                showSoftKeyboard()
                return true
            }
            !isEditable() -> return true
            else -> selection.cancel()
        }
        
        cursor.setCoordinates(x, y)
        cursor.cancelBlink(true, true)
        
        // update select handle middle
        selectHandleMiddle.updateLocation(
            cursor.getX(),
            cursor.getY() + getLineHeight()
        )
        
        // perform haptic feedback
        if (isHapticFeedbackEnabled()) {            
            performHapticFeedback(
                HapticFeedbackConstants.CONTEXT_CLICK
            )
        }
        // update position
        moveTo(cursor.getX(), cursor.getY())
        // popup ime
        showSoftKeyboard()
        // call ACTION_UP event
        onUp(e)
        
        invalidate()
        return true
    }
    
    override fun onShowPress(e: MotionEvent) {
        // TODO
    }

    override fun onLongPress(e: MotionEvent) {
        val x = e.x.toInt() + getScrollX() - getPaddingLeft()
        val y = e.y.toInt() + getScrollY() - getPaddingTop()
        
        // set the long pressed flags
        this.isLongPressed = true
        // perform haptic feedback
        if (isHapticFeedbackEnabled()) {            
            performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS
            )
        }
        
        when {
            vertScrollbarThumb.contains(x, y) ||
            horizScrollbarThumb.contains(x, y) -> {
                return@onLongPress
            }
            selectHandleMiddle.contains(x, y) ||
            selectHandleLeft.contains(x, y) || 
            selectHandleRight.contains(x, y) -> {
                showMagnifierView()
                return@onLongPress
            }
            selection.contains(x, y) -> {
                // here, requires set cusor position
                // for set the action mode location
                cursor.setCoordinates(x, y)
                showActionMode()
                return@onLongPress
            }
        }
        
        cursor.setCoordinates(x, y)
        val (startOffset, endOffset) = findWordBoundary(
            getLine(cursor.lineNumber),
            cursor.column - 1
        )
        
        if(startOffset != endOffset) {
            // has text selected
            selection.select(
                cursor.lineNumber, startOffset + 1,
                cursor.lineNumber, endOffset + 1,
            )
            
            isSelectedWord = true
            cursor.cancelBlink(false, false)
            showMagnifierView()
        } else {
            // no selected
            selection.cancel()
            if (isEditable()) {
                cursor.cancelBlink(true, false)
                // resume blink
                cursor.makeBlink()
                showActionMode()
            }
        }
        
        invalidate()
    }
    
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val x = e2.x.toInt() + getScrollX() - getPaddingLeft()
        val y = e2.y.toInt() + getScrollY() - getPaddingTop()
        
        if (selectHandleMiddle.isPressed) {
            // the cursor position changed before
            val prevLineNumber = cursor.lineNumber
            val prevColumn = cursor.column
            
            if (y < cursor.getY() + getLineHeight()) {
                cursor.setCoordinates(x, y - getLineHeight())
            } else if (y > cursor.getY() + getLineHeight() * 2) {
                cursor.setCoordinates(x, y - getLineHeight() * 2)
            } else {
                cursor.setCoordinates(x, cursor.getY())
            }
                       
            // update the select handle middle bounds
            selectHandleMiddle.updateLocation(
                cursor.getX(),
                cursor.getY() + getLineHeight()
            )
            
            // the cursor position has changed
            if (
                prevLineNumber != cursor.lineNumber ||
                prevColumn != cursor.column &&
                isHapticFeedbackEnabled()
            ) {
                // perform haptic feedback
                performHapticFeedback(
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE
                )
            }

            // auto scroll when dragging the select handle middle
            val scrolled = moveTo(
                left = SCROLL_DELTA, 
                top = getLineHeight(), 
                right = getWidth() - paddingHorizontal - SCROLL_DELTA, 
                bottom = getHeight() - paddingVertical - getLineHeight() * 3
            )

            // update the magnifier location
            showMagnifierView()
            
        } else if (selectHandleLeft.isPressed) {
            val widthDelta = selectHandleLeft.bounds.width() / 2
            val heightDelta = selectHandleLeft.bounds.height() / 2

            // startColumn is smaller than endColumn 1
            if (!(
                selection.startColumn + 1 >= selection.endColumn &&
                    selection.startLine == selection.endLine && x + widthDelta > selectHandleLeft.bounds.right &&
                    Math.abs(y - selectHandleLeft.bounds.centerY()) < getLineHeight()
                )
            ) {
                // the cursor position changed before
                val prevLineNumber = cursor.lineNumber
                val prevColumn = cursor.column      
                          
                // check the select handle left bounds
                if (y < selectHandleLeft.bounds.centerY() - getLineHeight()) { 
                    cursor.setCoordinates(x + widthDelta, y - heightDelta)
                } else if (y > selectHandleLeft.bounds.centerY() + getLineHeight()) {
                    cursor.setCoordinates(x + widthDelta, y - heightDelta - getLineHeight())
                } else { 
                    cursor.setCoordinates(x + widthDelta, cursor.getY())
                }
                
                selection.startLine = cursor.lineNumber
                selection.startColumn = cursor.column
                
                // update the select handle left bounds
                selectHandleLeft.updateLocation(
                    cursor.getX(), 
                    cursor.getY() + getLineHeight()
                )
                
                // the cursor position has changed
                if (
                    prevLineNumber != cursor.lineNumber ||
                    prevColumn != cursor.column &&
                    isHapticFeedbackEnabled()
                ) {
                    // perform haptic feedback
                    performHapticFeedback(
                        HapticFeedbackConstants.TEXT_HANDLE_MOVE
                    )
                }               
                
            } else if (x >= selectHandleRight.bounds.left + widthDelta) {
                // on the same line, no need to check startLine == endLine
                selectHandleLeft.isPressed = false
                selectHandleRight.isPressed = true
            }

            // not allow at the same point
            if (selection.startLine == selection.endLine && selection.startColumn == selection.endColumn
            ) {
                val maxColumn = getLineEndColumn(selection.endLine)
                if (maxColumn > 1) {
                    if (selection.endColumn != maxColumn) {
                        selection.endColumn++
                    } else {
                        selection.endColumn--
                    }
                } else {
                    selection.endColumn = getLineEndColumn(--selection.endLine)
                }
                                
                // update the select handle right bounds
                selectHandleRight.updateLocation(
                    getStartSpacing() + getLineWidthAdvance(selection.endLine, 1, selection.endColumn), 
                    selection.endLine * getLineHeight()
                )
            }
            
            // auto scroll when dragging the select handle left
            val scrolled = moveTo(
                left = SCROLL_DELTA + widthDelta, 
                top = getLineHeight(), 
                right = getWidth() - paddingHorizontal - SCROLL_DELTA, 
                bottom = getHeight() - paddingVertical - getLineHeight() * 3
            )

            // update the magnifier location
            showMagnifierView()
            
        } else if (selectHandleRight.isPressed) {
            val widthDelta = selectHandleRight.bounds.width() / 2
            val heightDelta = selectHandleRight.bounds.height() / 2

            // endColumn is bigger than startColumn 1
            if (!(
                selection.endColumn - 1 <= selection.startColumn &&
                    selection.startLine == selection.endLine && x - widthDelta < selectHandleRight.bounds.left &&
                    Math.abs(y - selectHandleRight.bounds.centerY()) < getLineHeight()
                )
            ) {
                // the cursor position changed before
                val prevLineNumber = cursor.lineNumber
                val prevColumn = cursor.column
                
                // check the handle right bounds
                if (y < selectHandleRight.bounds.centerY() - getLineHeight()) {
                    cursor.setCoordinates(x - widthDelta, y - heightDelta)
                } else if (y > selectHandleRight.bounds.centerY() + getLineHeight()) {
                    cursor.setCoordinates(x - widthDelta, y - heightDelta - getLineHeight())
                } else { 
                    cursor.setCoordinates(x - widthDelta, cursor.getY())
                }
                
                selection.endLine = cursor.lineNumber
                selection.endColumn = cursor.column
                // update the select handle right bounds
                selectHandleRight.updateLocation(
                    cursor.getX(), 
                    cursor.getY() + getLineHeight()
                )
                
                // the cursor position has changed
                if (
                    prevLineNumber != cursor.lineNumber ||
                    prevColumn != cursor.column && 
                    isHapticFeedbackEnabled()
                ) {
                    // perform haptic feedback
                    performHapticFeedback(
                        HapticFeedbackConstants.TEXT_HANDLE_MOVE
                    )
                }
                
            } else if (x <= selectHandleLeft.bounds.right - widthDelta) {
                // on the same line, no need to check startLine == endLine
                selectHandleLeft.isPressed = true
                selectHandleRight.isPressed = false
            }

            // not allow at the same point
            if (selection.endLine == selection.startLine && selection.endColumn == selection.startColumn
            ) {
                val maxColumn = getLineEndColumn(selection.startLine)
                if (maxColumn > 1) {
                    if (selection.startColumn != 1) {
                        selection.startColumn--
                    } else {
                        selection.startColumn++
                    }
                } else {
                    selection.startColumn = getLineStartColumn(++selection.startLine)
                }
                
                // update the select handle left bounds
                selectHandleLeft.updateLocation(
                    getStartSpacing() + getLineWidthAdvance(selection.startLine, 1, selection.startColumn), 
                    selection.startLine * getLineHeight()
                )
            }
            
            // auto scroll when dragging the select handle right
            val scrolled = moveTo(
                left = SCROLL_DELTA, 
                top = getLineHeight(), 
                right = getWidth() - paddingHorizontal - SCROLL_DELTA - widthDelta, 
                bottom = getHeight() - paddingVertical - getLineHeight() * 3
            )
            
            // update the magnifier location
            showMagnifierView()
            
        } else if (vertScrollbarThumb.isPressed) {
            // dragging the vertical scrollbar
            val deltaY = vertScrollbarThumb.run {
                (e2.y / (getHeight() - bounds.height()) * maxScrollY).toInt() - bounds.height() / 2
            }
            scrollTo(scrollX, Math.min(maxScrollY, Math.max(0, deltaY)))
            
        } else if(horizScrollbarThumb.isPressed) {
            // dragging the horizontal scrollbar            
            val deltaX = horizScrollbarThumb.run {
                (e2.x / (getWidth() - bounds.width()) * maxScrollX).toInt() - bounds.width() / 2
            }
            scrollTo(Math.min(maxScrollX, Math.max(0, deltaX)) , scrollY)
            
        } else {           
            // here not use scroller.start() function to scroll the virw
            val dx = if(Math.abs(distanceX) > Math.abs(distanceY)) distanceX.toInt() else 0
            val dy = if(Math.abs(distanceY) > Math.abs(distanceX)) distanceY.toInt() else 0
            smoothScrollTo(
                Math.max(0, Math.min(scrollX + dx, maxScrollX)),
                Math.max(0, Math.min(scrollY + dy, maxScrollY))
            )
        
            // vertical stretch overscroll effect 
            if (scroller.currY + distanceY < 0f) {                                          
                edgeEffectTop.onPullDistance(-distanceY / getHeight(), 1 - e2.getX() / getWidth())
                edgeEffectTopActive = true
            } else if (scroller.currY + distanceY > maxScrollY.toFloat()) {                                
                edgeEffectBottom.onPullDistance(distanceY / getHeight(), e2.getX() / getWidth())
                edgeEffectBottomActive = true
            }
            
            // horizontal stretch overscroll effect
            if (scroller.currX + distanceX < 0f) {                       
                edgeEffectLeft.onPullDistance(-distanceX / getWidth(), e2.getY() / getHeight())
                edgeEffectLeftActive = true
            } else if (scroller.currX + distanceX > maxScrollX.toFloat()) {                
                edgeEffectRight.onPullDistance(distanceX / getWidth(), e2.getY() / getHeight())
                edgeEffectRightActive = true
            }
        }

        if (!selection.valid()) {
            // swap the selection handle
            selection.reverse()
            selectHandleLeft.isPressed = !selectHandleLeft.isPressed
            selectHandleRight.isPressed = !selectHandleRight.isPressed
        }

        invalidate()
        return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        // Before flinging, stops the current animation.
        scroller.forceFinished(true)
        
        scroller.fling(
            // Current scroll position
            scrollX, 
            scrollY, 
            if(Math.abs(velocityX) > Math.abs(velocityY)) -velocityX.toInt() else 0, 
            if(Math.abs(velocityY) > Math.abs(velocityX)) -velocityY.toInt() else 0,
            /*
             * Minimum and maximum scroll positions. The minimum scroll
             * position is generally 0 and the maximum scroll position
             * is generally the content size less the screen size. So if the
             * content width is 2000 pixels and the screen width is 1200
             * pixels, the maximum scroll offset is 800 pixels.
             */
            0, maxScrollX, 
            0, maxScrollY,
            // The edges of the content. This comes into play when using
            // the EdgeEffect class to draw "glow" overlays.
            getWidth() / 10, 
            getHeight() / 10
        )
        postInvalidateOnAnimation()
        return true
    }

    protected open fun onUp(e: MotionEvent) {
        releaseEdgeEffects()
        // dismiss the magnifier
        dismissMagnifierView()
        
        if (selectHandleMiddle.isPressed) {
            selectHandleMiddle.isPressed = false
        } 
        else if (selectHandleLeft.isPressed) {
            selectHandleLeft.isPressed = false
            showActionMode()
        } 
        else if (selectHandleRight.isPressed) {
            selectHandleRight.isPressed = false
            showActionMode()
        } 
        else if(isSelected() && isSelectedWord) {
            isSelectedWord = false
            showActionMode()
        }
        else if (vertScrollbarThumb.isPressed) {
            vertScrollbarThumb.isPressed = false
            handler.postDelayed(vertScrollbarAction, 2000)
        } 
        else if (horizScrollbarThumb.isPressed) {
            horizScrollbarThumb.isPressed = false
            handler.postDelayed(horizScrollbarAction, 2000)
        }
        
        if(isEditable() && !isSelected() && cursor.isCancelled()) {
            // cursor resume blink
            cursor.makeBlink()
        }     
    }
    
    // #endregion
    
    // #region ScaleGestureListener 
    
    protected fun scaleCallback(
        cancelled: () -> Boolean = { false }, 
        completed: () -> Unit = { }
    ) {
        // re-measure the text widths and breaks
        // this should be running on backgroud thread       
        val line = textLayout.run TextLayout@ {
            // relayout before
            val result = getLineResult(scrollY / getLineHeight() + 1)
            // start do relayout
            this@TextLayout.relayout(
                isCancelled = cancelled,
                onCompleted = completed                
            )
            // relayout after
            this@TextLayout.getIndexAt(result.line, result.start + 1)
        }
        // update...
        updateDrawableBounds()
        updateDisplayList()
        // scroll to previous line position
        /*scrollTo(
            Math.min(scrollX, maxScrollX),
            Math.min(line * getLineHeight(), maxScrollY)
        )*/
    }
    
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        // in scale state, editing not allowed
        setEditable(false)
        
        vertScrollbarAnimator.cancel()
        horizScrollbarAnimator.cancel()
        
        handler.removeCallbacks(vertScrollbarAction)
        handler.removeCallbacks(horizScrollbarAction)
        
        getOverlay().remove(vertScrollbarThumb)
        getOverlay().remove(horizScrollbarThumb)
        
        return true
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        val pxSize = textPaint.textSize * detector.scaleFactor

        if (pxSize >= MIN_TEXT_SIZE && pxSize <= MAX_TEXT_SIZE) {
            // before changed
            val focusX = detector.focusX
            val focusY = detector.focusY
            val originLineHeight = getLineHeight().toFloat()
            
            // set the text size
            textPaint.textSize = pxSize
            updateDisplayList()
            updateDrawableBounds()
            
            // after changed
            val heightFactor = getLineHeight() / originLineHeight
            var startX = ((scroller.getCurrX() + focusX) * detector.scaleFactor - focusX).toInt()
            var startY = ((scroller.getCurrY() + focusY) * heightFactor - focusY).toInt()
            
            startX = Math.max(0, Math.min(startX, maxScrollX))
            startY = Math.max(0, Math.min(startY, maxScrollY))
            
            scroller.startScroll(startX, startY, 0, 0, 0)
        }

        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        // finished callback
        scaleCallback() 
        // scale finished, now can edit
        setEditable(true)
    }

    // #endregion
}

