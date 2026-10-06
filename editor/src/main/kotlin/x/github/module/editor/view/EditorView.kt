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
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.icu.text.BreakIterator
import android.os.Build
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Magnifier
import android.widget.Toast

import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

import kotlin.text.Regex

import x.github.module.editor.R
import x.github.module.editor.SavedState
import x.github.module.editor.UndoManager
import x.github.module.editor.util.*

import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.PieceTreeTextBufferBuilder
import x.github.module.piecetable.common.ContentChange
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.Strings


/**
 * The editor widget.
 *
 * EditorView itself is a thin shell around the text buffer. It keeps three jobs:
 *
 *  - the public API (set/get text, edit, search, undo/redo, clipboard ...);
 *  - the edit pipeline: beforeTextChanged -> buffer -> onTextChanged -> afterTextChanged;
 *  - the Android View plumbing (measure, layout, draw, IME, insets, overlay).
 *
 * Everything else is delegated to a collaborator that owns exactly one concern, and
 * reaches back into the view only through its own narrow `Host` interface
 * (see [EditorEnvironment]):
 *
 *  | concern                                    | owner                   |
 *  |--------------------------------------------|-------------------------|
 *  | line breaking / visual lines             | [TextLayout]            |
 *  | visible-line loop, RenderNode cache        | [TextRenderer]          |
 *  | smooth scroll, fling, overscroll glow      | [ScrollController]      |
 *  | scrollbar thumbs                           | [ScrollbarController]   |
 *  | cursor, selection and their handles        | [SelectionController]   |
 *  | touch routing (tap, long press, drag, zoom)| [GestureController]     |
 *  | undo/redo stacks, merging of rapid edits   | [EditHistory]           |
 *
 * Subclasses customise the editor through the `protected open` hooks: the draw*
 * methods for painting, and before/on/afterTextChanged for reacting to edits.
 */
open class EditorView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), EditorEnvironment {
    
    protected var defaultTextColor: Int = Color.GRAY
    
    protected val textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
        // the default text size and font and color                        
        color = defaultTextColor
        typeface = Typeface.DEFAULT
        textSize = 15f.sp
        setSubpixelText(true)
        setLinearText(true)            
    }
    
    protected var pieceTreeBuffer: PieceTreeTextBuffer = PieceTreeTextBufferBuilder().build()
    
    final override var textLayout: TextLayout = UnwrappedLayout(this)
        protected set
    
    protected val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    protected var actionMode: ActionMode? = null
    protected var actionCallback: ActionMode.Callback? = null
    protected var matchResult: Range? = null
    
    protected var overlayDrawables = mutableSetOf<Drawable>()
    protected var searchMatches = mutableListOf<Range>()
    
    protected var imeInsets: Insets = Insets.NONE
        private set
    
    // whether text can be edited; turned off while pinch-zooming
    private var editable = true
    
    // the cursor was just placed by a tap, and the keyboard is about to resize the view
    protected var revealCursorOnLayout = false
    
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
    
    // #region collaborators
    
    // Forwards TextRenderer's paint requests to the protected draw* hooks below, so
    // subclasses keep overriding them exactly as before without them becoming public.
    private val linePainter = object : TextRenderer.Painter {
        override fun drawRegionHighlight(canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int) =
            this@EditorView.drawRegionHighlight(canvas, bufferLine, visualLine, start, end)
        
        override fun drawLineNumber(canvas: Canvas, line: Int, x: Float, y: Float) =
            this@EditorView.drawLineNumber(canvas, line, x, y)
        
        override fun drawText(
            canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, x: Float, y: Float
        ) = this@EditorView.drawText(canvas, text, line, startOffset, endOffset, x, y)
    }
    
    protected val undoManager = UndoManager()
    
    private val renderer = TextRenderer(this, linePainter, textPaint)
    private val scrollController = ScrollController(context, this)
    private val scrollbarController = ScrollbarController(context, this)
    private val selectionController = SelectionController(context, this, textPaint)
    private val gestureController = GestureController(
        context, this, selectionController, scrollbarController, scrollController
    )
    private val history = EditHistory(
        host = this,
        undoManager = undoManager,
        onMergeStarted = { onMergeStarted() },
        onMergeCommitted = { onMergeCommitted() }
    )
    
    protected val cursor: SelectionController.TextCursor get() = selectionController.cursor
    protected val selection: SelectionController.TextSelector get() = selectionController.range
    
    // #endregion
       
    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            insets  // Do not consume, continue dispatch to other child views
        }
    }
    
    // #region public settings
    
    fun setText(text: String) {
        Range(1, 1, getLineCount(), getLineEndColumn()).also {
            applyEdits(listOf(SingleEditOperation(it, text)), false)
        }       
    }
    
    @WorkerThread
    fun setTextSize(pxSize: Float) {
        textPaint.textSize = pxSize
        textLayout.relayout()
        invalidateRenderNodes()
        updateDrawableBounds()
        invalidate()
    }
    
    @WorkerThread
    fun setBuffer(textBuffer: PieceTreeTextBuffer) {
        scrollTo(0, 0)
        recycleRenderNodes()
        history.clear()
        selection.deselect() 
        cursor.stopBlink(false, false)              
              
        pieceTreeBuffer = textBuffer   
        textLayout.relayout()
        cursor.setPosition(1, 1)
        cursor.startBlink()
        invalidate()
    }
    
    @WorkerThread
    fun setTypeface(typeface: Typeface) {
        textPaint.typeface = typeface
        textLayout.relayout()
        invalidateRenderNodes()
        updateDrawableBounds()
        invalidate()
    }

    fun setEditable(value: Boolean) {
        editable = value
    }
    
    @WorkerThread
    fun setWordwrap(value: Boolean) {
        scrollbarController.setHorizontalThumbVisible(value)
        val recycleLayout = textLayout
        textLayout = if (value)            
            WordwrapLayout(this).apply{ relayout() }
        else
            UnwrappedLayout(this).apply{ relayout() }                         
                
        // free memory
        recycleLayout.recycle()
    }
    
    // this method should be run on main thread
    // use find to get the matches
    fun setSearchResults(matches: MutableList<Range>?) {
        searchMatches.clear()
        if(matches != null && matches.size > 0) {
            searchMatches = matches
        }
        invalidate()
    }
    
    fun getSearchResults() = searchMatches
    
    fun setSelection(range: Range) {
        if(range.isEmpty()) {
            // set the range as cursor position
            selection.deselect()
            cursor.setPosition(range.getStartPosition())
            cursor.stopBlink(true, false)
            scrollIntoView(cursor.getX(), cursor.getY())
            cursor.startBlink()
        } else {
            // set the range as selection
            cursor.stopBlink(false, false)
            selection.select(range)
            scrollToRange(range)
        }
        invalidate()
    }
    
    fun getSelection() = if(isSelected()) selection.clone() else null
    
    @JvmName("setMatchedResult")
    fun setMatchResult(range: Range?) {
        matchResult = range
        range?.let {
            scrollToRange(it)
            invalidate()
        }
    }
    
    @JvmName("getMatchedResult")
    fun getMatchResult() = matchResult
    
    // the default paint color
    fun setTextColor(color: Int) {
        defaultTextColor = color
    }
    
    fun setMargin(
        leftMargin: Int = 0, 
        topMargin: Int = 0, 
        rightMargin: Int = 0, 
        bottomMargin: Int = 0
    ) {
        with(layoutParams as MarginLayoutParams) {
            // set the margin params
            this.leftMargin = leftMargin
            this.topMargin = topMargin
            this.rightMargin = rightMargin
            this.bottomMargin = bottomMargin
        }
        requestLayout()
    }
    
    open fun saveState(uriPath: String, fileHash: String): SavedState {     
        return SavedState(
            uri = uriPath,
            hash = fileHash,
            position = Position(cursor.lineNumber, cursor.column),
            undoStack = history.getUndoStack(),
            redoStack = history.getRedoStack(),
            textBuffer = pieceTreeBuffer
        )
    }
    
    open fun restoreState(
        savedState: SavedState,
        isCancelled: () -> Boolean
    ) {        
        with(savedState) {
             scrollTo(0, 0)
             pieceTreeBuffer = textBuffer
             textLayout.relayout(
                 isCancelled = isCancelled,
                 onCompleted = { recycleRenderNodes() }
             )
             cursor.setPosition(position)
             history.restore(undoStack, redoStack)
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
        invalidateRenderNodes()
        // refresh the editor view
        invalidate()
    }
    
    override fun isSelected() = selectionController.isSelected()
    
    override fun isEditable() = editable
    
    // #endregion
    
    // #region text buffer queries
    
    // \n or \r\n
    fun getEOL() = pieceTreeBuffer.getEOL()

    override fun getLineCount(): Int = pieceTreeBuffer.getLineCount()
    
    fun getLineLength(lineNumber: Int) = pieceTreeBuffer.getLineLength(lineNumber)
    
    fun getBufferLength() = pieceTreeBuffer.length
    
    fun getRangeLength(range: Range) = pieceTreeBuffer.getValueLengthInRange(range)
    
    override fun getPosition(offset: Int): Position = pieceTreeBuffer.getPositionAt(offset)
    
    override fun getOffset(lineNumber: Int, column: Int): Int = pieceTreeBuffer.getOffsetAt(lineNumber, column)
    
    fun getOffset(pos : Position) = getOffset(pos.lineNumber, pos.column)
    
    fun getCharAt(lineNumber: Int, column: Int) = pieceTreeBuffer.run { getCharCode(getOffsetAt(lineNumber, column)).toChar() }
    
    override fun getLineCharAt(lineNumber: Int, offset: Int): Char = pieceTreeBuffer.getLineCharCode(lineNumber, offset).toChar()
    
    override fun getLine(lineNumber: Int): String = pieceTreeBuffer.getLineContent(lineNumber)

    fun getLineWithEOL(lineNumber: Int) = pieceTreeBuffer.getLineContentWithEOL(lineNumber)

    fun getLineStartOffset(lineNumber: Int) = getOffset(lineNumber, 1)

    fun getLineEndOffset(lineNumber: Int) = getOffset(lineNumber, pieceTreeBuffer.getLineMaxColumn(lineNumber))
    
    override fun getLineStartColumn(lineNumber: Int): Int = pieceTreeBuffer.getLineMinColumn(lineNumber)

    // lineNumber defaults to the last line
    override fun getLineEndColumn(lineNumber: Int): Int = pieceTreeBuffer.getLineMaxColumn(lineNumber)
    
    fun getValueInRange(range: Range) = pieceTreeBuffer.getValueInRange(range)
    
    fun getText() = pieceTreeBuffer.toString()
    
    fun getSelectedText() = getValueInRange(selection)
    
    fun getTextBuffer() = this.pieceTreeBuffer
    
    // #endregion
    
    // #region text metrics
    
    // text start indent
    override fun getGutterWidth(): Int = getLineCount().digitCount * measureText("0") + spacingWidth * 4
    // text end indent
    fun getEndPadding() = spacingWidth * 4
    
    override fun getLineHeight(): Int = textPaint.fontMetricsInt.run { bottom - top }
    
    // x of the end of the line text
    override fun getLineEndX(lineNumber: Int): Int = getGutterWidth() + textLayout.getLineWidth(lineNumber)
    
    fun getLineTextWidth(lineNumber: Int) = textLayout.getLineWidth(lineNumber)
    
    fun getLineNumberWidth(lineNumber: Int = getLineCount()) = measureText(lineNumber.toString())
    
    override fun getLineRangeWidth(lineNumber: Int, startColumn: Int, endColumn: Int): Int = measureText(
        pieceTreeBuffer.getValueInRange(
            Range(lineNumber, startColumn, lineNumber, endColumn)
        )
    )
    
    // start defaults to 0 and end to text.length
    override fun getTextWidths(text: String, start: Int, end: Int): FloatArray = FloatArray(end - start).also { widths ->
        // get the width of each character
        textPaint.getTextWidths(text, start, end, widths)
    }
    
    override fun measureText(text: String): Int = textPaint.measureText(text).toInt()
    
    // middle to baseline spacing = (descent - ascent) / 2 - descent
    // middle position = ((lineNumber * lineHeight) + (lineNumber - 1) * lineHeight) / 2
    // baseline = middle + spacing
    override fun getLineBaseline(lineNumber: Int): Int = textPaint.fontMetricsInt.run {
        (lineNumber * 2 - 1) * (bottom - top) / 2 + (descent - ascent) / 2 - descent
    }
    
    // the width a wrapped line can take up
    override val wordwrapWidth: Int
        get() = getWidth() - getGutterWidth() - getEndPadding()
    
    // whitespace character width
    val spacingWidth: Int
        get() = measureText("\t") // here \t equals spacing
    
    // the number of decimal digits
    val Int.digitCount: Int
        get() = Math.log10(this.toDouble()).toInt() + 1
    
    // get the number of grapheme for emoji character
    fun getGraphemeLength(text: String, offset: Int) = getGraphemeEnd(text, offset) - offset
    
    // find the start boundary of a character
    fun getGraphemeStart(text: String, endOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // end boundary
            following(endOffset)
            // start boundary
            previous()
        }
    }
    
    // find the end boundary of a character
    fun getGraphemeEnd(text: String, startOffset: Int): Int {
        return BreakIterator.getCharacterInstance().run {
            setText(text)
            // requires startOffset < text.length
            // end boundary
            following(startOffset)
        }
    }
    
    // get the selected word boundary
    override fun findWordBoundary(text: String, offset: Int): IntArray {
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
    
    // convert dip unit to pixel
    open val Float.dp: Int get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        context.resources.displayMetrics
    ).toInt()
    
    // convert sp unit to pixel
    open val Float.sp: Float get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this,
        context.resources.displayMetrics
    )
    
    // the RenderNode cache capacity
    // note that this value cannot be too large
    // otherwise it will affect the efficiency of text editing and memory usage
    override val nodeCacheSize: Int get() = 64
    
    // the minimum text size
    open val minTextSize: Float get() = 10f.sp
    
    // the maximum text size
    open val maxTextSize: Float get() = 25f.sp
    
    // how close to the left/right edge a point may get before the view auto scrolls
    open val autoScrollMargin: Int get() = 30f.dp
    
    // #endregion
    
    // #region geometry and overlay (EditorGeometry)
    
    override val viewWidth: Int get() = getWidth()
    
    override val viewHeight: Int get() = getHeight()
    
    override val viewScrollX: Int get() = getScrollX()
    
    override val viewScrollY: Int get() = getScrollY()
    
    override val contentPaddingLeft: Int get() = getPaddingLeft()
    
    override val contentPaddingTop: Int get() = getPaddingTop()
    
    override val paddingVertical: Int
        get() = paddingTop + paddingBottom

    override val paddingHorizontal: Int
        get() = paddingLeft + paddingRight

    override val maxScrollX: Int
        get() = Math.max(0, paddingHorizontal + getGutterWidth() + textLayout.width() + getEndPadding() - getWidth())

    override val maxScrollY: Int
        get() = Math.max(0, paddingVertical + textLayout.height() + 2 * getLineHeight() - getHeight())
    
    // the part of the view not covered by the soft keyboard
    protected val visibleWidth: Int
        get() = getWidth()
    
    protected val visibleHeight: Int
        get() = getHeight() - imeInsets.bottom
    
    override fun addOverlay(drawable: Drawable) {
        getOverlay().add(drawable)
        overlayDrawables.add(drawable)
    }
    
    override fun removeOverlay(drawable: Drawable) {
        getOverlay().remove(drawable)
        overlayDrawables.remove(drawable)
    }
    
    override fun hasOverlay(drawable: Drawable): Boolean {
        return overlayDrawables.contains(drawable)
    }
    
    override fun verifyDrawable(who: Drawable): Boolean {
        return hasOverlay(who)
    }
    
    // #endregion
    
    // #region scrolling
    
    /**
     * Like {@link View#scrollBy}, but scroll smoothly instead of immediately.
     *
     * @param dx the number of pixels to scroll by on the X axis
     * @param dy the number of pixels to scroll by on the Y axis
     */
    fun smoothScrollBy(dx: Int, dy: Int) = scrollController.smoothScrollBy(dx, dy)

    /**
     * Like {@link #scrollTo}, but scroll smoothly instead of immediately.
     *
     * @param x the position where to scroll on the X axis
     * @param y the position where to scroll on the Y axis
     */
    fun smoothScrollTo(x: Int, y: Int) = scrollController.smoothScrollTo(x, y)

    override fun computeScroll() {
        scrollController.computeScroll()
    }
    
    // scroll to target position
    protected fun scrollIntoView(
        x: Int = cursor.getX(), // x offset postion
        y: Int = cursor.getY(),  // y offset position
        left: Int = autoScrollMargin,
        top: Int = getLineHeight(),
        right: Int = visibleWidth - autoScrollMargin,
        bottom: Int = visibleHeight - getLineHeight() * 2,
    ): Boolean {
        return scrollController.scrollIntoView(x, y, left, top, right, bottom)
    }
    
    // auto scroll when dragging a cursor / selection handle
    override fun scrollCursorIntoView(extraLeft: Int, extraRight: Int): Boolean {
        return scrollIntoView(
            left = autoScrollMargin + extraLeft, 
            top = getLineHeight(), 
            right = visibleWidth - paddingHorizontal - autoScrollMargin - extraRight, 
            bottom = visibleHeight - paddingVertical - getLineHeight() * 3
        )
    }
    
    fun scrollToRange(range: Range) {        
        val dx = getGutterWidth() + getLineRangeWidth(range.startLine, 1, range.startColumn)
        val dy = range.startLine * getLineHeight()
        smoothScrollTo(
            Math.max(0, dx - getWidth() / 2),
            Math.max(0, dy - getHeight() / 2)
        )
        invalidate()
    }
    
    fun gotoLine(lineNumber: Int) {
        val targetLine = Math.max(1, Math.min(lineNumber, getLineCount()))
        cursor.setPosition(targetLine, 1)
        
        val dy = Math.min(maxScrollY, targetLine * getLineHeight() - getHeight() / 2)
        smoothScrollTo(0, Math.max(0, dy))
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        if (gestureController.isScaling) {
            // pinch-zoom moves the content too, but should not bring up the scrollbars
            return
        }
        scrollbarController.onScrollChanged(l, t, oldl, oldt)
    }
    
    // #endregion
    
    // #region view lifecycle and touch
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        setFocusable(true)
        setFocusableInTouchMode(true)
        // cursor start blink
        cursor.setPosition(1, 1)
        cursor.startBlink()    
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // no point toggling the cursor of a view that is not on screen
        cursor.cancelBlinkCallbacks()
    }
    
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // the DrawerLayout conflict with the vertical scrollbar
        requestDisallowInterceptTouchEvent(scrollbarController.isDraggingVertically)
        return super.dispatchTouchEvent(e)
    }
    
    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        getParent()?.requestDisallowInterceptTouchEvent(disallow)
    }
    
    override fun onTouchEvent(e: MotionEvent): Boolean {
        return gestureController.onTouchEvent(e)
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
        if(changed && revealCursorOnLayout) {
            scrollIntoView(cursor.getX(), cursor.getY())
            revealCursorOnLayout = false
        }
    }
    
    // #endregion
    
    // #region gesture callbacks (GestureController.Host)
    
    override fun focusEditor() {
        requestFocus()
    }
    
    override fun onCursorTapped() {
        revealCursorOnLayout = true
        // update position
        scrollIntoView(cursor.getX(), cursor.getY())
    }
    
    override fun onWordSelected(range: Range) {
        matchResult = range
    }
    
    // show the magnifier over the cursor
    override fun showMagnifier() {
        showMagnifierAt(cursor.getX() - getScrollX(), cursor.getY() - getScrollY())
    }
    
    // x, y are relative to the view
    fun showMagnifierAt(x: Int, y: Int) {
        magnifier?.show(x.toFloat(), y.toFloat())
        post { magnifier?.update() }
    }
    
    override fun dismissMagnifier() {
        magnifier?.dismiss()
    }
    
    override fun onZoomBegin() {
        // in scale state, editing not allowed
        setEditable(false)
    }

    override fun onZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        val pxSize = textPaint.textSize * scaleFactor

        if (pxSize >= minTextSize && pxSize <= maxTextSize) {
            // before changed
            val originLineHeight = getLineHeight().toFloat()
            
            // set the text size
            textPaint.textSize = pxSize
            invalidateRenderNodes()
            updateDrawableBounds()
            
            // after changed, keep the content under the fingers in place
            val heightFactor = getLineHeight() / originLineHeight
            scrollController.rescale(focusX, focusY, scaleFactor, heightFactor)
        }
    }

    override fun onZoomEnd() {
        // finished callback
        relayoutAfterZoom()
        // scale finished, now can edit
        setEditable(true)
    }
    
    protected fun relayoutAfterZoom(
        isCancelled: () -> Boolean = { false }, 
        onCompleted: () -> Unit = { }
    ) {
        // re-measure the text widths and breaks
        // this should be running on backgroud thread       
        textLayout.relayout(
            isCancelled = isCancelled,
            onCompleted = onCompleted
        )
        // update...
        updateDrawableBounds()
        invalidateRenderNodes()
    }
    
    // #endregion
    
    // #region draw
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun recycleRenderNodes() = renderer.recycleRenderNodes()
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun invalidateRenderNodes() = renderer.invalidateRenderNodes()
    
    // re-position the cursor and the handles after the text moved on screen
    protected fun updateDrawableBounds() = selectionController.updateDrawableBounds()
    
    // draw rect with int values
    protected fun<L, T, R, B> Canvas.drawRect(left: L, top: T, right: R, bottom: B, paint: Paint = textPaint) 
    where L: Number, T: Number, R: Number, B: Number {
        drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }
    
    /**
     * @bufferLine buffer line number
     * @visualLine visual line number
     * @start start column 
     * @end end column 
     * @indent start indent spacing
     */
    protected open fun drawMatchesBackground(
        canvas: Canvas, bufferLine: Int, visualLine: Int, startColumn: Int, endColumn: Int
    ) {        
        // here requests buffer line number
        searchMatches.findOverlapping(bufferLine, startColumn, bufferLine, endColumn).forEach { range ->       
            val result = textLayout.getLineResult(visualLine)
            val startLine = textLayout.getIndexAt(range.startLine, range.startColumn) + 1
            // note here the end column need to decrease 1
            val endLine = textLayout.getIndexAt(range.endLine, range.endColumn - 1) + 1           
            // highlight selection background color
            textPaint.color = Color.CYAN
            
            if (visualLine >= startLine && visualLine <= endLine) {
                if (startLine == endLine) {
                    canvas.drawRect(
                        getGutterWidth() + getLineRangeWidth(bufferLine, result.start + 1, range.startColumn), 
                        (visualLine - 1) * getLineHeight(), 
                        getGutterWidth() + getLineRangeWidth(bufferLine, result.start + 1, range.endColumn), 
                        visualLine * getLineHeight()
                    )
                } else if (visualLine == startLine) {
                    canvas.drawRect(
                        getGutterWidth() + getLineRangeWidth(bufferLine, result.start + 1, range.startColumn), 
                        (visualLine - 1) * getLineHeight(), 
                        getGutterWidth() + result.width, 
                        visualLine * getLineHeight()
                    )
                } else if (visualLine == endLine) {
                    canvas.drawRect(
                        getGutterWidth(), 
                        (visualLine - 1) * getLineHeight(), 
                        getGutterWidth() + getLineRangeWidth(bufferLine, result.start + 1, range.endColumn), 
                        visualLine * getLineHeight()
                    )
                } else {
                    canvas.drawRect(
                        getGutterWidth(), 
                        (visualLine - 1) * getLineHeight(), 
                        getGutterWidth() + result.width, 
                        visualLine * getLineHeight()
                    )
                }
            }            
            
            // restore to default color
            textPaint.color = defaultTextColor
        }        
    }
     
    protected open fun drawSelectionBackground(canvas: Canvas, line: Int) {
        // highlight selection background color
        textPaint.color = Color.argb(136, 204, 204, 204)
        
        // the selection is painted between its two handles
        val startBounds = selectionController.startHandleBounds
        val endBounds = selectionController.endHandleBounds
        
        val result = textLayout.getLineResult(line)
        val startLine = textLayout.getIndexAt(selection.startLine, selection.startColumn) + 1
        // note here the end column need to decrease 1
        val endLine = textLayout.getIndexAt(selection.endLine, selection.endColumn - 1) + 1
        
        // check if in selection rect
        if (line >= startLine && line <= endLine) {
             if (startLine == endLine) {
                 canvas.drawRect(
                    startBounds.right, 
                    startBounds.top - getLineHeight(),
                    endBounds.left,
                    endBounds.top
                )
             } else if (line == startLine) {
                canvas.drawRect(
                    startBounds.right, 
                    startBounds.top - getLineHeight(),
                    getGutterWidth() + result.width + spacingWidth, // +spacing extra width
                    startBounds.top
                )
            } else if (line == endLine) {
                canvas.drawRect(
                    getGutterWidth(), 
                    endBounds.top - getLineHeight(),
                    endBounds.left,
                    endBounds.top
                )
            } else {
                canvas.drawRect(
                    getGutterWidth(), 
                    (line - 1) * getLineHeight(),
                    getGutterWidth() + result.width + spacingWidth, // +spacing extra width
                    line * getLineHeight()
                )
            }
        }
                
        // restore to default color
        textPaint.color = defaultTextColor
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
        textPaint.color = defaultTextColor
    }
    
    // override this method to implement your own logic
    protected open fun drawLineNumber(
        canvas: Canvas, line: Int, x: Float, y: Float
    ) {
        // align line numbers with respect to font
        // line number fixed width => (line length) * (character 0 width)
        val fixed = line.digitCount * measureText("0")
        // scale = character width / text size
        val space = (fixed - getLineNumberWidth(line)) / line.digitCount / textPaint.textSize
        val startX = getGutterWidth() - fixed - spacingWidth * 3.0f
        // set the letter spacing
        textPaint.setLetterSpacing(space)
        // line number color
        textPaint.color = Color.GRAY
        
        // draw the line number
        canvas.drawText(line.toString(), startX, y, textPaint)
        
        // restore to default color and letter spacing
        textPaint.setLetterSpacing(0f)
        textPaint.color = defaultTextColor
    }
    
    // override this method to implement your own logic
    // draw the text of one visual line, in both wrap and unwrap mode
    protected open fun drawText(
        canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, xPaint: Float, yPaint: Float
    ) {
        canvas.drawText(text, startOffset, endOffset, xPaint, yPaint, textPaint)
    } 
    
    /**
     * @bufferLine buffer line number, which equals the cursor line number
     * @visualLine visual line number, which equals the scrolled distance
     */
    protected open fun drawRegionHighlight(
        canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int
    ) {
        if (!isSelected()) {
            // highlight the current line background
            // note here needs visual line at word wrap mode
            // at single line mode bufferLine == visualLine
            if (bufferLine == cursor.lineNumber) {
                drawLineBackground(canvas, visualLine)
            }
        } else {
            // highlight the selection region
            if (bufferLine >= selection.startLine && bufferLine <= selection.endLine) {
                drawSelectionBackground(canvas, visualLine)
            }
        }
        
        // highlight the match items
        drawMatchesBackground(canvas, bufferLine, visualLine, start + 1, end + 1)
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
        renderer.draw(canvas)
        // draw the edge effect
        if (scrollController.drawOverscroll(canvas)) {
            postInvalidateOnAnimation()
        }
        canvas.restore()
    }

    // #endregion

    // #region input text

    override fun showSoftKeyboard() {
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
                        KeyEvent.KEYCODE_ENTER -> insert(getEOL())
                        KeyEvent.KEYCODE_DEL -> delete()
                    }
                }
                return true
            }
        }
    }
    
    // #endregion
    
    // #region edit pipeline
    
    protected open fun beforeTextChanged(
        operations: List<SingleEditOperation>
    ) {
        // hold back the pending undo step, this edit may be merged into it
        history.cancelPendingCommit()

        cursor.stopBlink(true, false)
        selection.deselect()
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
        if (renderer.hasRenderNodes) {
            invalidateRenderNodes()
        }
                
        // set cursor position
        cursor.setPosition(lastLineNumber, lastColumn)
        scrollIntoView(cursor.getX(), cursor.getY())        
        // cursor start blink
        cursor.startBlink()      
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
            val finalLineNumber = change.range.startLine + insertingLinesCnt
            val finalColumn: Int
            
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
            recordUndoEdits(result.reverseEdits)            
        }
        
        // after text changed
        afterTextChanged(
            result.changes, lastLineNumber, lastColumn
        )
    }
    
    // Combine multiple edit operations within a specified time
    protected open fun onMergeStarted() {
        // Implement your own logic
        // TODO        
    }
    
    // Combine multiple edit operations within a specified time
    protected open fun onMergeCommitted() {
        // Implement your own logic
        // TODO
    }

    protected fun recordUndoEdits(
        reverseEdits: List<ReverseEditOperation>?, 
        delay: Long = EditHistory.DEFAULT_MERGE_DELAY_MS
    ) {
        history.record(reverseEdits, delay)
    }

    open fun undo(): Boolean {
        val operations = history.undo().map {
            val posStart = getPosition(it.newPosition)
            val posEnd = getPosition(it.newEnd)
            val range = Range(
                posStart.lineNumber, posStart.column,
                posEnd.lineNumber, posEnd.column
            )
            SingleEditOperation(range, it.oldText)
        }

        applyEdits(operations, false)           
        
        return history.canUndo()
    }

    open fun redo(): Boolean {
        val operations = history.redo().map {
            val posStart = getPosition(it.oldPosition)
            val posEnd = getPosition(it.oldEnd)
            val range = Range(
                posStart.lineNumber, posStart.column,
                posEnd.lineNumber, posEnd.column
            )
            SingleEditOperation(range, it.newText)
        }

        applyEdits(operations, false)
        
        return history.canRedo()
    }
    
    // editor action
    protected enum class EditAction { INSERT, DELETE }

    protected fun getDefaultEditRange(action: EditAction): Range {
        if (isSelected()) {
            // on select mode
            return selection.clone()
        }
        
        // get the default range for edit text
        return when(action) { // text op action
            // action insert text
            EditAction.INSERT -> Range.fromPositions(cursor)
            // action delete text
            EditAction.DELETE -> {
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
                        startColumn = getGraphemeStart(getValueInRange(this), endColumn - 1) + 1
                    }
                }
            }
        }
    }

    // insert text
    open fun insert(
        text: String, // not allow null string
        range: Range = getDefaultEditRange(EditAction.INSERT)
    ) {
        if (isEditable()) {
            applyEdits(listOf(SingleEditOperation(range, text)))
        }
    }

    // delete text
    open fun delete(
        range: Range = getDefaultEditRange(EditAction.DELETE)
    ) {
        if (isEditable() && !range.isEmpty()) {
            applyEdits(listOf(SingleEditOperation(range, null)))
        }
    }
    
    fun replace(text: String, range: Range) {
        if (isEditable() && !range.isEmpty()) {
            val operation = SingleEditOperation(range, text)
            applyEdits(listOf(operation), true)
        }
    }
    
    fun replaceAll(text: String, matches: MutableList<Range>) {
        if (isEditable() && matches.size > 0) {
            val operations = matches.map {
                SingleEditOperation(it, text)
            }
            applyEdits(operations, true)
        }
    }
    
    // #endregion
    
    // #region clipboard and selection commands

    // the binder transaction buffer has a limited fixed size, currently 1MB
    // see the android TransactionTooLargeException
    open fun copy(): Boolean {       
        val text = getValueInRange(selection)        
        if(!TextUtils.isEmpty(text)) {
            try {
                val content = ClipData.newPlainText("content", text)
                clipboard.setPrimaryClip(content)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                return true
            } catch(e: Exception) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
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
        cursor.stopBlink(false, false)
        cursor.setPosition(getLineCount(), 1)
        
        selection.select(
            startLineNumber = 1, 
            startColumn = 1, 
            endLineNumber = getLineCount(), 
            endColumn = getLineEndColumn()
        )

        smoothScrollTo(0, maxScrollY)
        
        postDelayed({ 
            showActionMode()
        }, 300L)
    }
    
    // #endregion
    
    // #region search

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

    // #endregion
    
    // #region action mode
    
    fun setActionModeCallback(actionCallback: ActionMode.Callback) {
        this.actionCallback = actionCallback
    }
    
    override fun showActionMode() {
        actionCallback?.let {
            actionMode = startActionMode(it, ActionMode.TYPE_FLOATING)
        }
    }

    override fun dismissActionMode() {
        actionMode?.let { it.finish() }
    }

    // #endregion
   
    // #region text cursor
    
    val cursorX: Int get() = cursor.getX()
    
    val cursorY: Int get() = cursor.getY()
    
    fun getLocationForPosition(lineNumber: Int, column: Int): IntArray {
        return selectionController.getLocationForPosition(lineNumber, column)
    }
    
    fun getLocationForPosition(pos: Position): IntArray {
        return selectionController.getLocationForPosition(pos)
    }

    // #endregion
}
