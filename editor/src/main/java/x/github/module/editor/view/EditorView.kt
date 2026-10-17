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

import android.content.ClipboardManager
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.text.InputType
import android.text.Spanned
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import com.google.android.material.color.MaterialColors
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Magnifier

import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import kotlin.text.Regex

import x.github.module.editor.R

import x.github.module.piecetable.PieceTreeTextBuffer
import x.github.module.piecetable.SliceEdit
import x.github.module.piecetable.common.Position
import x.github.module.piecetable.common.Range
import x.github.module.piecetable.common.ReverseEditOperation
import x.github.module.piecetable.common.SingleEditOperation
import x.github.module.piecetable.common.TextChange

import x.github.module.editor.EditorGeometry
import x.github.module.editor.input.AutoPair
import x.github.module.editor.input.AutoPairFilter
import x.github.module.editor.input.AutoPairs
import x.github.module.editor.input.CaretMove
import x.github.module.editor.input.EditorAccessibility
import x.github.module.editor.input.EditorClipboard
import x.github.module.editor.input.EdgeAutoScroller
import x.github.module.editor.input.EditorInputConnection
import x.github.module.editor.input.GestureController
import x.github.module.editor.input.ImeController
import x.github.module.editor.input.Indenter
import x.github.module.editor.input.InputMethodBridge
import x.github.module.editor.input.KeyboardController
import x.github.module.editor.input.MagnifierAnimator
import x.github.module.editor.input.TextHandleDrawable
import x.github.module.editor.input.ScrollController
import x.github.module.editor.input.ScrollbarController
import x.github.module.editor.input.SelectionController
import x.github.module.editor.layout.FoldedLayout
import x.github.module.editor.layout.LayoutCoordinator
import x.github.module.editor.layout.TextLayout
import x.github.module.editor.layout.TextMetrics
import x.github.module.editor.layout.TabStops
import x.github.module.editor.layout.UnwrappedLayout
import x.github.module.editor.layout.digitCount
import x.github.module.editor.model.EditHistory
import x.github.module.editor.highlight.SyntaxHighlighter
import x.github.module.editor.input.BackspaceRepeat
import x.github.module.editor.model.EditorDocument
import x.github.module.editor.model.PieceTreeBuffer
import x.github.module.editor.model.EditorBuffer
import x.github.module.editor.model.EditSession
import x.github.module.editor.model.HistoryChange
import x.github.module.editor.model.UndoStep
import x.github.module.editor.model.SearchResults
import x.github.module.editor.model.UndoManager
import x.github.module.editor.render.FrameBudget
import x.github.module.editor.render.Gutter
import x.github.module.editor.render.LineDecorationPainter
import x.github.module.editor.render.LineStyler
import x.github.module.editor.render.StyledTextPainter
import x.github.module.editor.render.TextRenderer
import x.github.module.editor.render.DefaultFoldStyle
import x.github.module.editor.render.DrawableFoldStyle
import x.github.module.editor.render.FoldStyle
import x.github.module.editor.structure.BracketMatcher
import x.github.module.editor.structure.BracketPair
import x.github.module.editor.structure.FoldingRange
import x.github.module.editor.structure.FoldingModel
import x.github.module.editor.structure.FoldingRangeProvider
import x.github.module.editor.structure.IndentGuide
import x.github.module.editor.structure.IndentGuideProvider
import x.github.module.editor.util.TextBoundaries


/**
 * The editor widget.
 *
 * EditorView itself is a thin shell. It keeps three jobs:
 *
 *  - the public API (set/get text, edit, search, undo/redo, clipboard ...), most of it
 *    one-line forwards to the object that does the work;
 *  - the `protected open` hooks subclasses customise the editor through: the draw*
 *    methods for painting, and before/on/afterTextChanged for reacting to edits;
 *  - the Android View plumbing (measure, layout, draw, touch, overlay).
 *
 * Everything else belongs to a collaborator that owns exactly one concern:
 *
 *  | concern                                     | owner                     |
 *  |---------------------------------------------|---------------------------|
 *  | the buffer, its queries, the edit pipeline  | [EditorDocument]          |
 *  | undo/redo stacks, merging of rapid edits    | [EditHistory]             |
 *  | font measurements, gutter width (cached)    | [TextMetrics]             |
 *  | line breaking / visual lines                | [TextLayout]              |
 *  | full relayouts, their threads, the zoom lock| [LayoutCoordinator]       |
 *  | visible-line loop, RenderNode cache         | [TextRenderer]            |
 *  | line backgrounds, selection, line numbers   | [LineDecorationPainter]   |
 *  | smooth scroll, fling, overscroll glow       | [ScrollController]        |
 *  | scrollbar thumbs                            | [ScrollbarController]     |
 *  | cursor, selection and their handles         | [SelectionController]     |
 *  | touch routing (tap, long press, drag, zoom) | [GestureController]       |
 *  | soft keyboard visibility and insets         | [ImeController]           |
 *  | copy, cut, paste                            | [EditorClipboard]         |
 *  | what a screen reader is told                | [EditorAccessibility]     |
 *  | how the text is stored                      | [EditorBuffer]              |
 *  | syntax highlighting engines                 | [SyntaxHighlighter]       |
 *  | what the soft keyboard types into           | [EditorInputConnection]   |
 *  | composing text (pinyin, swipe, completion)  | [InputComposer]           |
 *  | offsets of the input method <-> positions   | [InputMethodBridge]       |
 *  | hardware keyboard keys                      | [KeyboardController]      |
 *
 * [EditorDocument] and [TextMetrics] do not depend on the view at all and are handed
 * directly to whoever needs text or measurements. The collaborators that do need
 * something from the view reach it only through their own narrow `Host` interface.
 * Those interfaces are implemented by a private adapter (see Environment at the end
 * of this class), not by the view itself, so none of that plumbing shows up among
 * the view's public methods.
 */
open class EditorView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        // how long the floating bar waits, after the text stopped moving, to come back
        const val SELECTION_MENU_SETTLE_DELAY = 250L

        // the severities of a Diagnostic, as the protocol numbers them
        const val SEVERITY_ERROR = 1
        const val SEVERITY_WARNING = 2
        const val SEVERITY_INFORMATION = 3
        const val SEVERITY_HINT = 4

        // the line a jump landed on: how long it lights up, how much the band covers the text
        const val LINE_FLASH_DURATION = 1400L
        const val LINE_FLASH_BAND_ALPHA = 0x3A

        /** How many undo/redo steps an edit session keeps by default, see setSessionLimits. */
        const val DEFAULT_SESSION_MAX_STEPS = 200
        /** How many chars of text an edit session keeps by default, see setSessionLimits. */
        const val DEFAULT_SESSION_MAX_CHARS = 1_000_000

        // What a frame may spend on styling new rows and asking the structure
        // providers, in milliseconds; the rest is done on the next frames.
        private const val FRAME_BUDGET_MILLIS = 6f
    }
    
    protected var defaultTextColor: Int = Color.GRAY
    
    protected val textPaint: TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { 
        // the default text size and font and color                        
        color = defaultTextColor
        typeface = Typeface.DEFAULT
        textSize = 15f.sp
        setSubpixelText(true)
        setLinearText(true)            
    }
    
    // #region collaborators
    
    // what the collaborators below see of this view, see Environment at the end
    // matching brackets, indent guides and folding: see CodeStructure
    private val structure: CodeStructure = CodeStructure(StructureHost())
    
    private val environment: Environment = Environment()
    // (declaration order matters: each one is built from the ones above it)
    
    protected val textMetrics: TextMetrics = TextMetrics(textPaint)
    
    protected val undoManager: UndoManager = UndoManager()
    
    private val history: EditHistory = EditHistory(
        host = environment,
        undoManager = undoManager,
        onMergeStarted = { onMergeStarted() },
        onMergeCommitted = { onMergeCommitted() },
        onModifiedChanged = { modified -> onModifiedChanged(modified) }
    )
    
    // Forwards the document's edit pipeline to the protected hooks below, so subclasses
    // keep overriding them exactly as before without them becoming public.
    private val editCallback: EditorDocument.Callback = object : EditorDocument.Callback {
        override fun beforeTextChanged(edits: List<SliceEdit>) {
            // what afterTextChanged compares with, to know which rows still show what
            // they did (see invalidateEditedRows)
            rowCountBeforeEdit = textLayout.count()
            lineCountBeforeEdit = document.getLineCount()
            // Search matches follow single edits (see onTextChanged below). An edit
            // made of several changes at once (replace all) cannot be followed change
            // by change, and usually is about those very matches: they are dropped.
            if (edits.size > 1) {
                searchResults.clear()
            }
            this@EditorView.beforeTextChanged(edits)
            for (index in editCallbacks.indices) {
                editCallbacks[index].beforeTextChanged(edits)
            }
        }
        
        override fun onTextChanged(change: EditorDocument.AppliedChange) {
            // keep the matches on the text they belong to
            searchResults.onTextChanged(change.range, change.finalLineNumber, change.finalColumn)
            // folds move with their lines, or open when the edit reaches into them
            structure.onTextChanged(change)
            this@EditorView.onTextChanged(
                range = change.range,
                rangeOffset = change.rangeOffset,
                insertedLinesCnt = change.insertedLinesCnt,
                insertedTextLength = change.insertedTextLength,
                deletedLinesCnt = change.deletedLinesCnt,
                deletedTextLength = change.deletedTextLength,
                finalLineNumber = change.finalLineNumber,
                finalColumn = change.finalColumn
            )
            syntaxHighlighter?.onTextChanged(change)
            for (index in editCallbacks.indices) {
                editCallbacks[index].onTextChanged(change)
            }
            // lets a screen reader speak what was typed or removed
            accessibility.notifyTextChanged(
                change.rangeOffset, change.deletedTextLength, change.insertedTextLength
            )
        }
        
        override fun afterTextChanged(changes: List<EditorDocument.AppliedChange>, caretLineNumber: Int, caretColumn: Int) {
            // counted here, not in the protected hook, which a subclass may override
            // without calling super
            saveTracker.onTextEdited()
            // A large edit was laid out roughly: lay the text out properly in the
            // background. Edits wait meanwhile, and the app hears
            // onBackgroundRelayoutStarted / Finished (its progress bar), as when zooming.
            if (largeEditLaidOutRoughly) {
                largeEditLaidOutRoughly = false
                layoutCoordinator.relayoutAfterLargeEdit()
            }
            growLineNumberRoom()
            this@EditorView.afterTextChanged(changes, caretLineNumber, caretColumn)
            syntaxHighlighter?.onEditFinished()
            for (index in editCallbacks.indices) {
                editCallbacks[index].afterTextChanged(changes, caretLineNumber, caretColumn)
            }
        }
    }
    
    // others who want to follow the edits, see addEditCallback
    private val editCallbacks = ArrayList<EditorDocument.Callback>()
    
    protected val document: EditorDocument = EditorDocument(history, editCallback)
    
    // owns the layout, every full relayout of it, and the zoom lock
    private val layoutCoordinator: LayoutCoordinator = LayoutCoordinator(environment, document, textMetrics)
    
    /**
     * The layout in use: rows as they are on screen. Replaced as a whole when word
     * wrap is switched; while code is folded, the folded lines have no rows in it
     * (see FoldedLayout).
     */
    val textLayout: TextLayout
        get() = structure.layout()
    
    // Forwards TextRenderer's paint requests to the protected draw* hooks below, for
    // the same reason as editCallback.
    private val linePainter: TextRenderer.Painter = object : TextRenderer.Painter {
        override fun drawRegionHighlight(canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int) =
            this@EditorView.drawRegionHighlight(canvas, bufferLine, visualLine, start, end)
        
        override fun drawLineNumber(canvas: Canvas, line: Int, x: Float, y: Float) {
            // with the gutter the numbers are drawn there, where they stay put; in
            // the rows they would scroll away with the text; hidden, nowhere
            if (!gutterEnabled && textMetrics.lineNumbersVisible) {
                this@EditorView.drawLineNumber(canvas, line, x, y)
            }
        }
        
        override fun drawText(
            canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, x: Float, y: Float,
            mayDefer: Boolean
        ): Boolean {
            rowStylingMayDefer = mayDefer
            rowStylingDeferred = false
            try {
                this@EditorView.drawText(canvas, text, line, startOffset, endOffset, x, y)
            } finally {
                rowStylingMayDefer = false
            }
            return !rowStylingDeferred
        }

        override fun mayStyleNow(line: Int, startOffset: Int, endOffset: Int): Boolean {
            if (frameBudget.isSpent) {
                // no time left: the row keeps what it shows, and is styled on a later frame
                frameBudget.defer()
                return false
            }
            // Styles still being worked out (in the background): the row keeps what it
            // shows; the styler has it styled again when they are there.
            val styler = lineStyler ?: return true
            return styler.isStyleReady(line, document.getLineStartOffset(line), startOffset, endOffset)
        }
    }

    // What one frame may spend on styling rows that are kept, and on asking the
    // structure providers; the rest waits for the next frames (see FrameBudget).
    // Dragging the scrollbar thumb through a large file styled by tree-sitter brings
    // a screen of new rows on every frame, far more than a frame can style.
    private val frameBudget = FrameBudget(FRAME_BUDGET_MILLIS)

    // while a row is painted: whether its styling may wait (it is recorded and kept,
    // and shows nothing yet), and whether it did
    private var rowStylingMayDefer = false
    private var rowStylingDeferred = false
    
    private val renderer: TextRenderer = TextRenderer(environment, document, textMetrics, linePainter, textPaint)
    
    // the default look of the draw*Background / drawLineNumber hooks; its colors can be changed
    protected val decorationPainter: LineDecorationPainter = LineDecorationPainter(environment, document, textMetrics, textPaint) { textLayout }
    
    private val scrollController: ScrollController = ScrollController(context, environment)
    private val scrollbarController: ScrollbarController = ScrollbarController(context, environment)
    private val selectionController: SelectionController = SelectionController(context, environment, document, textMetrics, textPaint)
    private val gestureController: GestureController = GestureController(
        context, environment, selectionController, scrollbarController, scrollController
    )
    private val imeController: ImeController = ImeController(this) { grew -> onKeyboardOverlapChanged(grew) }
    // what the keys of a hardware keyboard mean
    private val keyboardController: KeyboardController = KeyboardController(environment)
    // the input method's view of the text: offsets instead of lines and columns,
    // and the text it is composing
    private val inputBridge: InputMethodBridge = InputMethodBridge(document, environment)
    // what a screen reader is told about the editor
    private val accessibility: EditorAccessibility = EditorAccessibility(this, document, inputBridge) { visibleBufferLines() }
    
    // the system clipboard, and the editor's copy / cut / paste on top of it
    protected val clipboard: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val editorClipboard: EditorClipboard = EditorClipboard(clipboard, environment)
    
    protected val cursor: SelectionController.TextCursor get() = selectionController.cursor
    protected val selection: SelectionController.TextSelector get() = selectionController.range
    
    // #endregion
    
    // The piece tree being edited, for subclasses that work on it directly. It lives
    // in the document, behind the EditorBuffer interface; this is only valid while the
    // buffer is the piece tree one. Setting another tree forgets the undo history: its
    // steps refer to the storage of the old tree, and belong to its text.
    protected var pieceTreeBuffer: PieceTreeTextBuffer
        get() = getTextBuffer()
        set(value) {
            document.replaceBuffer(PieceTreeBuffer(value))
            runOnMainThread { onTextLoaded() }
        }
    
    
    // the floating bar over the selection, and what it offers (see SelectionMenu)
    private var selectionMenu: SelectionMenu? = null
    private var floatingBar: FloatingBar? = null
    // whether the bar is to be shown: it is hidden while the text scrolls, and comes back
    private var selectionMenuWanted = false
    private val showSelectionMenuRunnable = Runnable { showSelectionMenuNow() }
    
    protected var overlayDrawables = mutableSetOf<Drawable>()
    
    // the matches of the last search, and the one the user is at
    private val searchResults: SearchResults = SearchResults()
    protected val searchMatches: List<Range> get() = searchResults.matches
    protected val matchResult: Range? get() = searchResults.current
    
    // the space currently taken up by the soft keyboard
    protected val imeInsets: Insets get() = imeController.insets
    
    // whether the user may edit the text (false = read-only editor)
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
    
    // see setInputType
    private var inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    
    // the text given in the layout file (android:text), until it has been applied
    private var initialText: String? = null
    
    // Must stay below every property declaration above: an initializer that runs
    // after this block would overwrite what an attribute has just set.
    init {
        applyAttributes(attrs, defStyleAttr)
        // Only declares that the editor *can* take the focus; it never asks for it by
        // itself. The focus comes from the user touching the text (onDown -> focusEditor).
        // A text editor that grabs the focus on its own makes the system show the soft
        // keyboard every time the screen is entered.
        isFocusable = true
        isFocusableInTouchMode = true
    }
    
    // #region attributes from the layout file
    
    // Reads what was set on the <EditorView> tag, in its style or in the theme (see
    // res/values/editor_view_attrs.xml). Runs inside the constructor, where the text
    // is still empty and a subclass is not constructed yet: so it sets fields
    // directly, lays nothing out, and calls nothing a subclass can override.
    private fun applyAttributes(attrs: AttributeSet?, defStyleAttr: Int) {
        val array = context.obtainStyledAttributes(attrs, R.styleable.EditorView, defStyleAttr, 0)
        try {
            if (array.hasValue(R.styleable.EditorView_android_textSize)) {
                textPaint.textSize = array.getDimension(
                    R.styleable.EditorView_android_textSize, textPaint.textSize
                )
            }
            
            if (array.hasValue(R.styleable.EditorView_android_textColor)) {
                defaultTextColor = array.getColor(
                    R.styleable.EditorView_android_textColor, defaultTextColor
                )
                textPaint.color = defaultTextColor
            }
            
            typefaceFromAttributes(array)?.let { textPaint.typeface = it }
            
            with(decorationPainter) {
                selectionColor = array.getColor(
                    R.styleable.EditorView_android_textColorHighlight, selectionColor
                )
                lineNumberColor = array.getColor(
                    R.styleable.EditorView_lineNumberColor, lineNumberColor
                )
                currentLineColor = array.getColor(
                    R.styleable.EditorView_currentLineColor, currentLineColor
                )
                searchMatchColor = array.getColor(
                    R.styleable.EditorView_searchMatchColor, searchMatchColor
                )
                currentMatchColor = array.getColor(
                    R.styleable.EditorView_currentMatchColor, currentMatchColor
                )
            }
            
            editable = array.getBoolean(R.styleable.EditorView_editable, editable)
            
            if (array.getBoolean(R.styleable.EditorView_wordwrap, false)) {
                // instant here: there is no text yet
                layoutCoordinator.setWordwrap(true)
                scrollbarController.setHorizontalThumbVisible(true)
            }
            
            // Not applied here: setting text runs the edit hooks, which a subclass
            // overrides and which must not run before its constructor has.
            initialText = array.getString(R.styleable.EditorView_android_text)
        } finally {
            array.recycle()
        }
    }
    
    // android:fontFamily (a family name or a @font resource), else android:typeface,
    // both combined with android:textStyle. Null when none of the three is set.
    private fun typefaceFromAttributes(array: TypedArray): Typeface? {
        val hasFamily = array.hasValue(R.styleable.EditorView_android_fontFamily)
        val hasTypeface = array.hasValue(R.styleable.EditorView_android_typeface)
        val hasStyle = array.hasValue(R.styleable.EditorView_android_textStyle)
        if (!hasFamily && !hasTypeface && !hasStyle) {
            return null
        }
        
        var family: Typeface? = null
        if (hasFamily) {
            val fontResource = array.getResourceId(R.styleable.EditorView_android_fontFamily, 0)
            family = if (fontResource != 0) {
                try {
                    ResourcesCompat.getFont(context, fontResource)
                } catch (e: Exception) {
                    // not a usable font (or the layout preview cannot load it)
                    null
                }
            } else {
                array.getString(R.styleable.EditorView_android_fontFamily)?.let {
                    Typeface.create(it, Typeface.NORMAL)
                }
            }
        }
        if (family == null && hasTypeface) {
            // the values of android:typeface
            family = when (array.getInt(R.styleable.EditorView_android_typeface, 0)) {
                1 -> Typeface.SANS_SERIF
                2 -> Typeface.SERIF
                3 -> Typeface.MONOSPACE
                else -> null
            }
        }
        
        // android:textStyle has the same values as the Typeface style constants
        val style = array.getInt(R.styleable.EditorView_android_textStyle, Typeface.NORMAL)
        return Typeface.create(family ?: textPaint.typeface, style)
    }
    
    // Applies android:text, once, when the view gets its size for the first time. By
    // then a subclass is fully constructed and its edit hooks can run, and the width
    // that word wrap breaks lines at is known. Text that was put in from code in the
    // meantime wins.
    private fun applyInitialText() {
        val text = initialText ?: return
        initialText = null
        
        val isEmpty = getLineCount() == 1 && getLineLength(1) == 0
        if (isEmpty && text.isNotEmpty() && setText(text)) {
            // start at the top, like a freshly loaded file
            cursor.setPosition(1, 1)
            scrollTo(0, 0)
        }
    }
    
    // #endregion
    
    // #region loading text
    
    /**
     * Replaces the whole text. Not undoable. The new text counts as saved (see
     * [isModified]).
     *
     * @return false if refused because the layout is being rebuilt (see [isLayoutBusy])
     */
    fun setText(text: String): Boolean {
        if (!layoutCoordinator.runEdit { document.setText(text) }) return false
        runOnMainThread {
            onTextLoaded()
            resetInput()
            // if the view was recreated and this is the text it had, go back to
            // where the user was
            applyPendingViewportState()
        }
        return true
    }
    
    /**
     * Puts a piece tree under the editor: the buffer the editor comes with, and what
     * a file is usually loaded into. See the other setBuffer.
     */
    @AnyThread
    fun setBuffer(textBuffer: PieceTreeTextBuffer, onDone: (() -> Unit)? = null): Boolean =
        setBuffer(PieceTreeBuffer(textBuffer), onDone)
    
    /**
     * Puts another text under the editor, in any structure that implements
     * [EditorBuffer]. The new text counts as saved (see [isModified]).
     *
     * Laying the new text out takes seconds for a large one; see "text size,
     * typeface, word wrap" below for how that is done on each thread. [onDone] is
     * called on the main thread once the text is laid out and shown, for example
     * to restore its edit session there.
     *
     * @return false if refused because a zoom is in progress (see [isZoomInProgress])
     */
    @AnyThread
    fun setBuffer(buffer: EditorBuffer, onDone: (() -> Unit)? = null): Boolean {
        if (layoutCoordinator.isZoomInProgress) return false
        
        // Until the last of the steps below has run on the main thread. Counted
        // here, before the work may go off to another thread: from now on a
        // session must not be restored.
        textReplacementsInProgress.incrementAndGet()
        runLayoutWork(notifyRelayout = false) {
            replaceBufferNow(buffer)
            onDone?.let { runOnMainThread(it) }
        }
        return true
    }
    
    // setBuffer, on the thread that lays out
    private fun replaceBufferNow(buffer: EditorBuffer) {
        try {
            layoutCoordinator.relayout {
                // room for the line numbers of this text, not of the one before
                textMetrics.minLineNumberDigits = 1
                document.replaceBuffer(buffer)
                // show the new text now, not when the layout is done
                runOnMainThread {
                    showReplacedText()
                    // nothing of the old text can be undone into the new one, and
                    // a text just loaded is the text that is saved
                    onTextLoaded()
                }
            }
        } catch (e: Throwable) {
            runOnMainThread { textReplacementsInProgress.decrementAndGet() }
            throw e
        }
        
        runOnMainThread {
            placeCursorInReplacedText(Position(1, 1))
            // if the view was recreated and this is the text it had, go back to
            // where the user was
            applyPendingViewportState()
            textReplacementsInProgress.decrementAndGet()
        }
    }
    
    // How many setBuffer calls have started and not yet run their last step on the
    // main thread. Until then a session must not be restored: one of those steps
    // clears the history.
    private val textReplacementsInProgress = AtomicInteger(0)
    
    // Loading another text takes two steps on the main thread, one on each side of
    // the layout, because the layout can take seconds for a large text and the text
    // should not stay invisible for that long.
    
    // Step 1, as soon as the buffer is swapped: put the new text on screen. Drawing
    // needs the text, not the layout (which, until it is done, only limits how far
    // one can scroll sideways, or with word wrap shows the lines unevenly broken).
    // What must go right now is everything that still shows the old text: the
    // recorded lines, the selection, the cursor.
    private fun showReplacedText() {
        scrollTo(0, 0)
        recycleRenderNodes()
        selection.deselect()
        // they were matches in the old text
        searchResults.clear()
        // and what the highlighting engine knew was about the old text
        syntaxHighlighter?.onTextReplaced(document.buffer)
        // Hidden for now, and parked at the one position every text has: where it
        // was in the old text may not exist in the new one.
        cursor.stopBlink(false, false)
        cursor.setPosition(1, 1)
        resetInput()
        invalidate()
    }
    
    // Step 2, when the layout is done: now a position can be turned into a place on
    // screen, so the cursor can come back, and everything is drawn once more with
    // the right measurements.
    private fun placeCursorInReplacedText(position: Position) {
        cursor.setPosition(position)
        cursor.startBlink()
        refreshTextGeometry()
    }

    
    // #endregion
    
    // #region modified or saved
    
    // which text is the saved one, and the sessions that belong to it: SaveTracker
    private val saveTracker = SaveTracker(history, document) { runOnMainThread(it) }
    
    // The room for line numbers only grows while a text is edited: a line count
    // going back and forth across 999 / 1000 does not move the text each time. With
    // word wrap a wider room means other line breaks: laid out again in the
    // background, once per extra digit.
    private fun growLineNumberRoom() {
        val digits = getLineCount().digitCount
        if (digits <= textMetrics.minLineNumberDigits) return
        textMetrics.minLineNumberDigits = digits
        layoutCoordinator.syncWrapWidth()
    }
    
    // a text was loaded (setBuffer, setText, another piece tree): it is the saved
    // one, nothing from before can be undone into it, and its folds are not the old ones
    private fun onTextLoaded() {
        structure.onTextLoaded()
        saveTracker.onTextLoaded()
    }
    
    /**
     * A number that changes with every edit (undo and redo included) and with
     * nothing else. Take it together with the text you are about to write to a
     * file, and hand it to [markSaved] once the file is written.
     *
     * Take it on the main thread, where the text is read anyway: there it also
     * notes where the history stands, so that markSaved knows which text was saved
     * even when the user typed on while the file was being written. (It also ends
     * the current typing burst: a save point lies between two undo steps.) Taken on
     * another thread it is only good as long as nothing is edited until markSaved.
     */
    fun getTextVersion(): Long = saveTracker.takeTextVersion()
    
    /**
     * The text of [version] was written to its file (see [getTextVersion]). Pass the
     * version that was taken with the text that was written: edits made while the
     * file was being written are then still counted as unsaved. Any thread; takes
     * effect on the main thread.
     *
     * A version that cannot be placed in the history (taken on another thread, and
     * edited since) leaves the text modified until it is saved again: better than
     * calling a text saved that is not.
     */
    fun markSaved(version: Long = saveTracker.textVersion) = saveTracker.markSaved(version)
    
    /**
     * Whether the text differs from the saved one: the one loaded last (setBuffer,
     * setText) or last marked saved ([markSaved]).
     *
     * It follows the undo history: undoing back to the saved text makes the text
     * unmodified again, and so does redoing up to it after undoing past it; typing a
     * char and deleting it again within one typing burst too. An edit that is not
     * undoable (applyEdits with computeUndoEdits = false) leaves the text modified
     * until it is saved again. Any thread.
     */
    fun isModified(): Boolean = saveTracker.isModified
    
    /** Called on the main thread with a new value of [isModified]. */
    fun interface OnModifiedChangeListener {
        fun onModifiedChanged(modified: Boolean)
    }
    
    private var modifiedChangeListener: OnModifiedChangeListener? = null
    
    /** Is told whenever the text becomes modified or unmodified, see [isModified]. Main thread. */
    fun setOnModifiedChangeListener(listener: OnModifiedChangeListener?) {
        modifiedChangeListener = listener
    }
    
    // the text became modified or unmodified (from the history, on the main thread)
    protected open fun onModifiedChanged(modified: Boolean) {
        modifiedChangeListener?.onModifiedChanged(modified)
    }
    
    // #endregion
    
    // #region edit sessions
    
    /**
     * How much of the history [saveSession] keeps: at most [maxSteps] steps and
     * [maxChars] chars of text (old and new together), counted from the saved text
     * outwards, the redo side (unsaved edits) first. A step that does not fit ends
     * its side: whatever lies beyond it could not be reached without it. 0 keeps
     * nothing of that kind. Main thread.
     *
     * The text of the kept steps is copied into the session as Strings, on the main
     * thread: the limits keep that quick. Defaults: 200 steps, 1 000 000 chars.
     */
    fun setSessionLimits(maxSteps: Int = DEFAULT_SESSION_MAX_STEPS, maxChars: Int = DEFAULT_SESSION_MAX_CHARS) {
        saveTracker.sessionMaxSteps = maxSteps
        saveTracker.sessionMaxChars = maxChars
    }
    
    /**
     * The editing session of the text that is open: what can be undone and redone,
     * where the cursor goes, and what is folded. The text itself is not part of it
     * (see [EditSession]).
     *
     * The session belongs to the **saved** text, the one in the file, whatever is
     * on screen. Edits made since the text was saved are on its redo stack: restored
     * on the file, they come back with redo. So it can be taken at any time, e.g.
     * when the app goes to the background, also with unsaved edits.
     *
     * [contentHash] is a hash of the saved text (the file), computed by the app; the
     * editor only stores it, and compares it again in [restoreSession].
     *
     * Main thread: it reads the cursor and the undo history, and copies the text of
     * the steps it keeps (see [setSessionLimits]). The session it returns is a copy
     * and can be serialized and written on another thread.
     *
     * @return the session; null while a text is being loaded, and when it cannot be
     *         told which text is the saved one (an edit that is not undoable, or the
     *         saved text dropped from the history: undone past, then edited over, or
     *         beyond the undo limit). Save the file then, and take the session after.
     */
    @MainThread
    open fun saveSession(contentHash: String): EditSession? {
        checkMainThread("saveSession")
        if (textReplacementsInProgress.get() > 0) return null
        return saveTracker.buildSession(
            contentHash,
            cursorOffset = document.getOffset(cursor.lineNumber, cursor.column),
            folds = foldsToArray(structure.foldedRanges())
        )
    }
    
    /**
     * Puts a session back on top of the text that is open: its undo and redo
     * history (unsaved edits of last time are on the redo stack), the cursor
     * (brought into view) and the folds.
     *
     * [contentHash] is the hash of the file the text was just loaded from, computed
     * the same way as when the session was saved. The session is only restored if
     * the text is the one it was saved for: the same hash, the same length and
     * number of lines, and not edited since it was loaded. Undo steps replayed on
     * any other text would garble it.
     *
     * Main thread, once setBuffer is through: for example `setBuffer` on a worker
     * thread, then `withContext(Dispatchers.Main) { restoreSession(...) }`; or, when
     * setBuffer was called on the main thread, in its `onDone`.
     *
     * @return what happened; see [EditSession.RestoreResult] for what to do in each case
     */
    @MainThread
    open fun restoreSession(session: EditSession, contentHash: String): EditSession.RestoreResult {
        checkMainThread("restoreSession")
        if (textReplacementsInProgress.get() > 0 || layoutCoordinator.isBusy) {
            return EditSession.RestoreResult.BUSY
        }
        saveTracker.checkSession(session, contentHash)?.let { return it }
        saveTracker.restoreHistory(session)
        
        // a session says itself where the cursor belongs
        pendingViewportState = null
        dismissSelectionMenu()
        selection.deselect()
        val position = document.getPosition(session.cursorOffset.coerceIn(0, document.length))
        cursor.setPosition(position.lineNumber, position.column)
        structure.restoreFolds(foldsFromArray(session.foldedLines.toIntArray()))
        cursor.stopBlink(true, false)
        cursor.startBlink()
        scrollCursorToCenter()
        return EditSession.RestoreResult.RESTORED
    }
    
    private fun checkMainThread(method: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "$method must be called on the main thread" }
    }
    
    // #endregion
    
    // #region instance state (rotation, the activity being recreated)
    
    // The text is not part of it: it can be tens of megabytes, and saving and loading
    // files is the app's business. Nor is the undo history, which goes with the file
    // (see saveSession / restoreSession). What the view saves is what is the view's
    // own: the text size the user zoomed to, whether lines are wrapped, and where in
    // the text the cursor and the viewport were.
    
    // where the cursor and the viewport were, waiting for the text to come back
    private class ViewportState(
        val cursorLine: Int, val cursorColumn: Int,
        val scrollX: Int, val scrollY: Int,
        // what the text looked like, to recognise it when it is back
        val lineCount: Int, val length: Int,
        // what was folded, as pairs of start and end line
        val folds: IntArray
    )
    
    private var pendingViewportState: ViewportState? = null
    
    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return Bundle().apply {
            putParcelable(STATE_SUPER, superState)
            putFloat(STATE_TEXT_SIZE, textPaint.textSize)
            putBoolean(STATE_WORDWRAP, layoutCoordinator.isWordwrap)
            putInt(STATE_CURSOR_LINE, cursor.lineNumber)
            putInt(STATE_CURSOR_COLUMN, cursor.column)
            putInt(STATE_SCROLL_X, getScrollX())
            putInt(STATE_SCROLL_Y, getScrollY())
            putInt(STATE_LINE_COUNT, document.getLineCount())
            putInt(STATE_LENGTH, document.length)
            putIntArray(STATE_FOLDS, foldsToArray(structure.foldedRanges()))
        }
    }
    
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle || !state.containsKey(STATE_SUPER)) {
            super.onRestoreInstanceState(state)
            return
        }
        @Suppress("DEPRECATION")
        super.onRestoreInstanceState(state.getParcelable(STATE_SUPER))
        
        // Text size and word wrap are put back only into an editor that is still
        // empty, which is what a freshly recreated one is. Then it costs nothing. An
        // editor that already has its text was set up by the app in the meantime,
        // and changing either would mean laying the whole text out again right here.
        val isEmpty = document.getLineCount() == 1 && document.length == 0
        if (isEmpty && !layoutCoordinator.isBusy) {
            textPaint.textSize = state.getFloat(STATE_TEXT_SIZE, textPaint.textSize)
            val wordwrap = state.getBoolean(STATE_WORDWRAP, layoutCoordinator.isWordwrap)
            if (wordwrap != layoutCoordinator.isWordwrap) {
                layoutCoordinator.setWordwrap(wordwrap).recycle()
                scrollbarController.setHorizontalThumbVisible(wordwrap)
            }
            refreshTextGeometry()
        }
        
        pendingViewportState = ViewportState(
            state.getInt(STATE_CURSOR_LINE, 1), state.getInt(STATE_CURSOR_COLUMN, 1),
            state.getInt(STATE_SCROLL_X, 0), state.getInt(STATE_SCROLL_Y, 0),
            state.getInt(STATE_LINE_COUNT, 0), state.getInt(STATE_LENGTH, 0),
            state.getIntArray(STATE_FOLDS) ?: IntArray(0)
        )
        // the text may be there already; if not, setBuffer will do this when it is
        applyPendingViewportState()
    }
    
    // Puts the cursor and the viewport back where they were before the view was
    // recreated, once: as soon as the text that was open then is there again. It is
    // recognised by its size; for any other text the saved place means nothing, and
    // is dropped.
    private fun applyPendingViewportState() {
        val saved = pendingViewportState ?: return
        val lineCount = document.getLineCount()
        if (lineCount == 1 && document.length == 0) {
            // no text yet: keep waiting
            return
        }
        pendingViewportState = null
        if (lineCount != saved.lineCount || document.length != saved.length) {
            return
        }
        
        val line = saved.cursorLine.coerceIn(1, lineCount)
        cursor.setPosition(line, saved.cursorColumn.coerceIn(1, document.getLineEndColumn(line)))
        // folded before scrolling: folds change how far the text goes down
        structure.restoreFolds(foldsFromArray(saved.folds))
        scrollTo(saved.scrollX.coerceIn(0, maxScrollX), saved.scrollY.coerceIn(0, maxScrollY))
        invalidate()
    }
    
    // folds as start and end lines one after the other, for a Bundle or a session
    private fun foldsToArray(folds: List<FoldingRange>): IntArray {
        val array = IntArray(folds.size * 2)
        folds.forEachIndexed { index, fold ->
            array[index * 2] = fold.startLine
            array[index * 2 + 1] = fold.endLine
        }
        return array
    }
    
    private fun foldsFromArray(array: IntArray): List<FoldingRange> =
        (0 until array.size / 2).map { FoldingRange(array[it * 2], array[it * 2 + 1]) }
    
    // #endregion
    
    // #region text size, typeface, word wrap
    
    /**
     * The setters in this region re-run the text layout, and so does setBuffer.
     * That takes seconds for a large text, and must not happen on the main thread:
     * the app would stop answering (ANR). So where it happens depends on where they
     * are called:
     *
     *  - **on a worker thread**: the layout runs right there, and the call returns
     *    when it is done. The way to go when the caller wants to wait for it, e.g.
     *    `withContext(Dispatchers.Default) { editor.setWordwrap(true) }`.
     *  - **on the main thread** (a click handler, `lifecycleScope.launch { }`,
     *    which runs on the main thread too): the layout goes off to a thread of the
     *    editor's and the call returns at once. Calls made this way are carried
     *    out one after the other, in the order they were made. [onDone] says when
     *    one is through, on the main thread; in between, [onBackgroundRelayoutStarted]
     *    and [onBackgroundRelayoutFinished] are called as for a zoom.
     *
     * Only one layout runs at a time (a second one waits for the first), and for as
     * long as one is under way the editor refuses edits (see [isLayoutBusy]); it
     * can be scrolled and read meanwhile. Whatever touches the view afterwards is
     * handed over to the main thread.
     */
    @AnyThread
    fun setTextSize(pxSize: Float, onDone: (() -> Unit)? = null) {
        whenDone(runLayoutWork { setTextSizeNow(pxSize) }, onDone)
    }
    
    private fun setTextSizeNow(pxSize: Float) {
        layoutCoordinator.relayout {
            val factor = pxSize / textPaint.textSize
            textPaint.textSize = pxSize
            // Without word wrap the widths simply grow with the text size, so the
            // layout can be scaled on the spot and the text shown in its new size
            // right now. Measuring it again exactly, below, then only corrects a
            // pixel here and there. With word wrap there is no such shortcut.
            if (layoutCoordinator.rescaleLayout(factor)) {
                runOnMainThread { refreshTextGeometry() }
            }
        }
        runOnMainThread { refreshTextGeometry() }
    }
    
    /** Another typeface. See above for threads. */
    @AnyThread
    fun setTypeface(typeface: Typeface, onDone: (() -> Unit)? = null) {
        val offloaded = runLayoutWork {
            layoutCoordinator.relayout { textPaint.typeface = typeface }
            runOnMainThread { refreshTextGeometry() }
        }
        whenDone(offloaded, onDone)
    }
    
    /**
     * How many spaces wide a tab stop is (4 by default): a tab reaches to the next
     * multiple of that, counted from the start of its row (see TabStops). Every
     * width can change, so the text is laid out again. See above for threads.
     */
    @AnyThread
    fun setTabSize(size: Int, onDone: (() -> Unit)? = null) {
        require(size >= 1) { "a tab stop at least one space wide: $size" }
        val offloaded = runLayoutWork {
            layoutCoordinator.relayout { textMetrics.tabSize = size }
            runOnMainThread { refreshTextGeometry() }
        }
        whenDone(offloaded, onDone)
    }
    
    fun getTabSize(): Int = textMetrics.tabSize
    
    /** Wraps long lines at the width of the view, or not. See above for threads. */
    @AnyThread
    fun setWordwrap(value: Boolean, onDone: (() -> Unit)? = null) {
        val offloaded = runLayoutWork {
            val previousLayout = layoutCoordinator.setWordwrap(value)
            
            runOnMainThread {
                scrollbarController.setHorizontalThumbVisible(value)
                // free memory. Done on the main thread, where drawing happens, so the old
                // layout cannot be emptied in the middle of a frame that still uses it
                previousLayout.recycle()
                refreshTextGeometry()
            }
        }
        whenDone(offloaded, onDone)
    }
    
    /**
     * Whether long lines are wrapped: whether the layout in use is the wrapping
     * one. After setWordwrap on the main thread it changes once the new layout is
     * ready, not at once. Any thread.
     */
    @AnyThread
    fun isWordwrap(): Boolean = layoutCoordinator.isWordwrap
    
    // [work] lays the text out, which takes seconds for a large one. Off the main
    // thread it runs right here. On the main thread it goes to the layout thread,
    // and the editor reports it like a relayout of its own (unless [notifyRelayout]
    // is false). Returns whether it went off to the layout thread.
    private fun runLayoutWork(notifyRelayout: Boolean = true, work: () -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            work()
            return false
        }
        if (notifyRelayout) {
            onBackgroundRelayoutStarted()
        }
        EditorLayoutThread.executor.execute {
            try {
                work()
            } finally {
                if (notifyRelayout) {
                    runOnMainThread { onBackgroundRelayoutFinished() }
                }
            }
        }
        return true
    }
    
    // Calls [onDone] on the main thread after the layout and the steps it queued
    // there: if the layout went [offloaded] to the layout thread, once that thread
    // has gone through what was handed to it before; otherwise right after (the
    // steps were queued on the main thread already, and come first).
    private fun whenDone(offloaded: Boolean, onDone: (() -> Unit)?) {
        onDone ?: return
        if (offloaded) {
            EditorLayoutThread.executor.execute { runOnMainThread(onDone) }
        } else {
            runOnMainThread(onDone)
        }
    }
    
    /**
     * Runs the relayout that follows a pinch-zoom on [executor] instead of a thread
     * of the editor's own, e.g. `Dispatchers.Default.asExecutor()` to share the
     * coroutine thread pool. Null goes back to the editor's own thread.
     *
     * The executor must not run tasks on the calling thread, and should get to them
     * soon: the editor stays locked until the relayout is done. Takes effect from
     * the next zoom.
     */
    fun setLayoutExecutor(executor: Executor?) {
        layoutCoordinator.backgroundExecutor = executor
    }
    
    // #endregion
    
    // #region colors and margins
    
    // The four colors below can also be set in the layout file, see
    // res/values/editor_view_attrs.xml. Main thread.
    
    /** The color of the cursor and selection handles (by default the theme's accent color). */
    fun setHandleColor(color: Int) {
        selectionController.setHandleColor(color)
        invalidate()
    }
    
    /** The background of selected text (android:textColorHighlight). */
    fun setSelectionColor(color: Int) {
        decorationPainter.selectionColor = color
        invalidate()
    }
    
    /** The background of the line the cursor is on (app:currentLineColor). */
    fun setCurrentLineColor(color: Int) {
        decorationPainter.currentLineColor = color
        invalidate()
    }
    
    /** The background of search matches (app:searchMatchColor). */
    fun setSearchMatchColor(color: Int) {
        decorationPainter.searchMatchColor = color
        invalidate()
    }
    
    /** The background of the current search match (app:currentMatchColor). */
    fun setCurrentMatchColor(color: Int) {
        decorationPainter.currentMatchColor = color
        invalidate()
    }
    
    /** The color of the line numbers (app:lineNumberColor). */
    fun setLineNumberColor(color: Int) {
        decorationPainter.lineNumberColor = color
        // line numbers are part of the recorded lines
        invalidateRenderNodes()
        invalidate()
    }
    
    // --- the gutter ---
    
    // the strip with the line numbers that stays put while the text scrolls sideways
    private val gutter: Gutter = Gutter(environment, textPaint)
    
    // whether the line numbers are in the gutter (true) or in the rows of text
    private var gutterEnabled = false
    
    // the room between the gutter and the text, see setGutterTextGap
    private var gutterTextGap = 8f.dp
    
    /**
     * Whether the line numbers are drawn in a gutter that stays put while the text
     * scrolls sideways under it, or in the rows of text, scrolling away with them
     * (the default). See [Gutter]. Main thread.
     */
    fun setGutterEnabled(enabled: Boolean) {
        if (enabled == gutterEnabled) return
        gutterEnabled = enabled
        updateGutterGap()
        // the recorded rows hold their line numbers, or not
        invalidateRenderNodes()
        invalidate()
    }
    
    fun isGutterEnabled(): Boolean = gutterEnabled
    
    /**
     * Shows or hides the line numbers (shown by default). Hidden, their room goes:
     * the text moves to the left, up to a small margin, the fold markers (with
     * folding on) and the gap of the gutter (with the gutter on). The gutter itself
     * stays if it is on, for its decorations and fold markers. Main thread.
     */
    fun setLineNumbersVisible(visible: Boolean) {
        if (visible == textMetrics.lineNumbersVisible) return
        textMetrics.lineNumbersVisible = visible
        // where the text starts changes: with word wrap, where lines break too
        layoutCoordinator.syncWrapWidth()
        // the recorded rows hold their line numbers, or not
        invalidateRenderNodes()
        updateDrawableBounds()
        invalidate()
    }
    
    fun isLineNumbersVisible(): Boolean = textMetrics.lineNumbersVisible
    
    /**
     * The room between the gutter and the text, in pixels: 8dp by default. Without
     * it the text would start right at the edge of the gutter, and a handle at the
     * start of a line would hang out over the gutter. Only with the gutter on; the
     * line numbers in the rows have margins of their own. Main thread.
     */
    fun setGutterTextGap(gap: Int) {
        gutterTextGap = Math.max(0, gap)
        updateGutterGap()
    }
    
    fun getGutterTextGap(): Int = gutterTextGap
    
    // The gap is part of where the text starts: it changes where the rows are drawn,
    // and with word wrap where lines break.
    private fun updateGutterGap() {
        val gap = if (gutterEnabled) gutterTextGap else 0
        if (gap == textMetrics.gutterGap) return
        textMetrics.gutterGap = gap
        layoutCoordinator.syncWrapWidth()
        invalidateRenderNodes()
        updateDrawableBounds()
        invalidate()
    }
    
    // the width of the gutter itself: where the text starts, less the gap
    private val gutterPanelWidth: Int
        get() = getGutterWidth() - textMetrics.gutterGap
    
    /** The background of the gutter; null takes the editor's background. Main thread. */
    fun setGutterBackgroundColor(color: Int?) {
        gutter.backgroundColor = color
        invalidate()
    }
    
    /** The line between gutter and text; null is the line number color, faint. Main thread. */
    fun setGutterDividerColor(color: Int?) {
        gutter.dividerColor = color
        invalidate()
    }
    
    /** The number of the cursor's line; null is the text color. Main thread. */
    fun setCurrentLineNumberColor(color: Int?) {
        gutter.currentLineNumberColor = color
        decorationPainter.currentLineNumberColor = color
        invalidate()
    }
    
    /**
     * Something of the app's drawn on the gutter for each line on screen, under its
     * number: a breakpoint, a changed-line marker. Null for none. Main thread.
     */
    fun setGutterDecoration(decoration: Gutter.Decoration?) {
        gutter.decoration = decoration
        invalidate()
    }
    
    /** A tap on the gutter, on [line]. Return true if it was taken care of. */
    fun interface OnGutterClickListener {
        fun onGutterClick(line: Int): Boolean
    }
    
    private var gutterClickListener: OnGutterClickListener? = null
    
    /**
     * Is told about taps on the gutter. A tap it does not take care of (or every
     * tap, without a listener) is a tap on the text, at the start of what can be
     * seen of that row. Main thread.
     */
    fun setOnGutterClickListener(listener: OnGutterClickListener?) {
        gutterClickListener = listener
    }
    
    // How much of the viewport on the left is gutter: text that scrolls under it
    // cannot be seen (the gap before the text can). 0 when the line numbers are in
    // the rows.
    protected val textInsetLeft: Int
        get() = if (gutterEnabled) gutterPanelWidth else 0
    
    // the editor's background, for a gutter without a color of its own
    private val themeBackgroundColor: Int by lazy {
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorBackground, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            value.data
        } else {
            Color.WHITE
        }
    }
    
    private fun editorBackgroundColor(): Int =
        (background as? ColorDrawable)?.color ?: themeBackgroundColor
    
    // A tap on the gutter at content y [y] (fold markers were looked at before, see
    // onFoldMarkerTapped): it goes to the gutter listener.
    @Suppress("UNUSED_PARAMETER")
    private fun onGutterTapped(x: Int, y: Int): Boolean {
        val listener = gutterClickListener ?: return false
        return listener.onGutterClick(textLayout.getBufferLine(rowAtY(y)))
    }
    
    // the row at content y
    private fun rowAtY(y: Int): Int =
        (Math.max(0, y) / Math.max(1, getLineHeight()) + 1).coerceIn(1, Math.max(1, textLayout.count()))
    
    // #endregion
    
    // #region code structure: matching brackets, indent guides, folding
    
    // Which brackets match, where the indent guides go and what can be folded depends
    // on the language, and is worked out by the providers set below: a tree-sitter
    // engine, a language server, or the simple ones of the structure package that go
    // by the text alone. Asking them, keeping their answers, folding and the drawing
    // are CodeStructure's; what is here is the API, and the hooks a subclass overrides
    // (drawFoldMarker, drawBracketText, ... in the draw region).
    
    /**
     * Highlights the bracket at the cursor and its partner, as found by [matcher]
     * (see [BracketMatcher]); null for none, the default. [PlainBracketMatcher] goes
     * by the text alone. How they are marked: [setBracketMatchStyle]. Main thread.
     */
    fun setBracketMatcher(matcher: BracketMatcher?) {
        structure.bracketMatcher = matcher
    }
    
    fun getBracketMatcher(): BracketMatcher? = structure.bracketMatcher
    
    /**
     * Draws indent guides where [provider] says (see [IndentGuideProvider]); null for
     * none, the default. [IndentationGuideProvider] goes by the indentation. Main thread.
     */
    fun setIndentGuideProvider(provider: IndentGuideProvider?) {
        structure.indentGuideProvider = provider
    }
    
    fun getIndentGuideProvider(): IndentGuideProvider? = structure.indentGuideProvider
    
    /**
     * Code folding, with the ranges [provider] says can be folded (see
     * [FoldingRangeProvider]); null for none, the default. A fold marker goes next to
     * the number of each line a range starts on (in a column of its own, in the
     * gutter or in the rows), and a tap on it folds or unfolds the range; see
     * [fold]. [IndentationFoldingProvider] goes by the indentation.
     *
     * Without a provider nothing can be folded, and whatever is folded is unfolded.
     * Main thread.
     */
    fun setFoldingRangeProvider(provider: FoldingRangeProvider?) {
        structure.foldingRangeProvider = provider
    }
    
    fun getFoldingRangeProvider(): FoldingRangeProvider? = structure.foldingRangeProvider
    
    /**
     * What the providers would answer has changed although the text has not (an
     * engine finished parsing, settings changed): asks them again and redraws. Any
     * thread.
     */
    fun invalidateStructure() = structure.invalidate()
    
    /** The background of a bracket at the cursor and its partner. Main thread. */
    fun setBracketMatchColor(color: Int) {
        decorationPainter.bracketMatchColor = color
        invalidate()
    }
    
    /**
     * How the bracket at the cursor and its partner are marked: a [highlight] behind
     * them ([setBracketMatchColor]), drawn [bold], and a line [underline] them
     * ([setBracketUnderlineColor]). All three by default. Main thread.
     */
    fun setBracketMatchStyle(highlight: Boolean = true, bold: Boolean = true, underline: Boolean = true) {
        structure.bracketHighlight = highlight
        structure.bracketBold = bold
        structure.bracketUnderline = underline
        invalidate()
    }
    
    /** The line under a bracket at the cursor and its partner; null is the color of the text. Main thread. */
    fun setBracketUnderlineColor(color: Int?) {
        decorationPainter.bracketUnderlineColor = color
        invalidate()
    }
    
    /**
     * The indent guides, and the guide of the block the cursor is in; null takes the
     * line number color, faint and full. Main thread.
     */
    fun setIndentGuideColors(color: Int?, activeColor: Int?) {
        decorationPainter.indentGuideColor = color
        decorationPainter.activeIndentGuideColor = activeColor
        invalidate()
    }
    
    /** The fold markers and placeholders; null is the line number color. Main thread. */
    fun setFoldMarkerColor(color: Int?) {
        gutter.foldMarkerColor = color
        invalidate()
    }
    
    /**
     * How folding looks: the fold markers and the placeholder of folded lines (see
     * [FoldStyle]). [DefaultFoldStyle], the default, draws them like VS Code: a
     * chevron, and three dots. Main thread.
     */
    fun setFoldStyle(style: FoldStyle) {
        structure.foldStyle = style
    }
    
    fun getFoldStyle(): FoldStyle = structure.foldStyle
    
    /**
     * Fold markers from drawables: [expanded] for an open range, [folded] for a
     * folded one, tinted in the marker color unless [tint] is false. The placeholder
     * stays as it is. See [DrawableFoldStyle]. Main thread.
     */
    @JvmOverloads
    fun setFoldMarkerDrawables(expanded: Drawable, folded: Drawable, tint: Boolean = true) {
        structure.foldStyle = DrawableFoldStyle(expanded, folded, currentPlaceholderDrawable, tint)
        currentMarkerDrawables = Pair(expanded, folded)
        currentDrawableTint = tint
    }
    
    /**
     * The placeholder of folded lines from a drawable, as wide as it is at the height
     * of a row; null goes back to the three dots. The markers stay as they are.
     * Main thread.
     */
    fun setFoldPlaceholderDrawable(placeholder: Drawable?) {
        currentPlaceholderDrawable = placeholder
        val markers = currentMarkerDrawables
        structure.foldStyle = when {
            markers != null -> DrawableFoldStyle(markers.first, markers.second, placeholder, currentDrawableTint)
            placeholder != null -> PlaceholderDrawableFoldStyle(placeholder, currentDrawableTint)
            else -> DefaultFoldStyle()
        }
    }
    
    // the drawables set so far, so that markers and placeholder can be set one by one
    private var currentMarkerDrawables: Pair<Drawable, Drawable>? = null
    private var currentPlaceholderDrawable: Drawable? = null
    private var currentDrawableTint = true
    
    // the default markers with a drawable placeholder
    private class PlaceholderDrawableFoldStyle(placeholder: Drawable, tint: Boolean) : FoldStyle {
        private val markers = DefaultFoldStyle()
        private val drawables = DrawableFoldStyle(placeholder, placeholder, placeholder, tint)
        override fun drawMarker(
            canvas: Canvas, foldedFraction: Float, left: Float, top: Float, right: Float, bottom: Float, color: Int
        ) = markers.drawMarker(canvas, foldedFraction, left, top, right, bottom, color)
        override fun placeholderWidth(lineHeight: Int): Int = drawables.placeholderWidth(lineHeight)
        override fun drawPlaceholder(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) =
            drawables.drawPlaceholder(canvas, left, top, right, bottom, color)
    }
    
    /**
     * Whether a tap on the placeholder of folded lines ("⋯") unfolds them; on by
     * default. Off, as in VS Code, a tap there is a tap on the text: the cursor goes
     * to the end of the line the fold starts on, which is easy to aim at right next to
     * the placeholder. The fold marker still folds and unfolds. Main thread.
     */
    fun setFoldPlaceholderClickable(clickable: Boolean) {
        structure.placeholderClickable = clickable
    }
    
    fun isFoldPlaceholderClickable(): Boolean = structure.placeholderClickable
    
    /** A tap on a fold marker. Return true if it was taken care of. */
    fun interface OnFoldMarkerClickListener {
        fun onFoldMarkerClick(range: FoldingRange): Boolean
    }
    
    private var foldMarkerClickListener: OnFoldMarkerClickListener? = null
    
    /**
     * Is told about taps on fold markers first. One it does not take care of folds or
     * unfolds the range (see [onFoldMarkerClick]). Main thread.
     */
    fun setOnFoldMarkerClickListener(listener: OnFoldMarkerClickListener?) {
        foldMarkerClickListener = listener
    }
    
    // A tap on the fold marker of [range]: the listener's, or else folds or unfolds
    // it. Override to do something else.
    protected open fun onFoldMarkerClick(range: FoldingRange): Boolean {
        if (foldMarkerClickListener?.onFoldMarkerClick(range) == true) return true
        toggleFold(range.startLine)
        return true
    }
    
    /**
     * Folds [range]: the lines after its start line, through its end line, are
     * hidden, and a placeholder at the end of the start line stands in for them.
     * Folds can nest. A cursor or selection in the folded lines moves to the end of
     * the start line. Main thread.
     *
     * Folds follow the edits: they move with their lines, and open when an edit
     * reaches into them (undo, replace all). They also open when the cursor gets
     * into them (search, go to line, arrow keys at the end of the start line). They
     * are kept with the instance state and in edit sessions.
     *
     * @return whether anything was folded
     */
    fun fold(range: FoldingRange): Boolean = structure.fold(range)
    
    /**
     * Unfolds the fold that starts on [line].
     *
     * @return whether there was one
     */
    fun unfold(line: Int): Boolean = structure.unfold(line)
    
    /**
     * Folds the range that starts on [line] (as the provider says), or unfolds it if
     * it is folded.
     *
     * @return whether anything changed
     */
    fun toggleFold(line: Int): Boolean = structure.toggleFold(line)
    
    /**
     * Folds every range the provider knows of in the whole text, nested ones too.
     * Asks the provider about all lines at once: it may take a moment on a large file.
     */
    fun foldAll() = structure.foldAll()
    
    /** Unfolds everything. */
    fun unfoldAll() = structure.unfoldAll()
    
    /** Whether the range that starts on [line] is folded. */
    fun isFolded(line: Int): Boolean = structure.isFolded(line)
    
    /** Whether [line] is hidden in a fold. */
    fun isLineHidden(line: Int): Boolean = structure.isLineHidden(line)
    
    /** The folded ranges, by start line. */
    fun getFoldedRanges(): List<FoldingRange> = structure.foldedRanges()
    
    /** Is told whenever something was folded or unfolded, also by an edit. Main thread. */
    fun interface OnFoldChangeListener {
        fun onFoldChanged(folded: List<FoldingRange>)
    }
    
    private var foldChangeListener: OnFoldChangeListener? = null
    
    fun setOnFoldChangeListener(listener: OnFoldChangeListener?) {
        foldChangeListener = listener
    }
    
    // something was folded or unfolded; override to keep a list of folds elsewhere
    protected open fun onFoldChanged(folded: List<FoldingRange>) {
        foldChangeListener?.onFoldChanged(folded)
    }
    
    /**
     * The bracket at the cursor and its partner, as the [BracketMatcher] finds them;
     * null without a matcher, while text is selected, or if there is no pair. Main thread.
     */
    fun getBracketPair(): BracketPair? = structure.bracketPair()
    
    /**
     * Moves the cursor to the partner of the bracket at the cursor: in front of a
     * closing bracket, behind an opening one, the way the cursor stands at a pair
     * it has just been through. Main thread.
     *
     * @return false if there is no pair at the cursor
     */
    fun jumpToMatchingBracket(): Boolean {
        val pair = getBracketPair() ?: return false
        val atOpen = cursor.lineNumber == pair.open.startLine &&
            (cursor.column == pair.open.startColumn || cursor.column == pair.open.endColumn)
        val target = if (atOpen) pair.close.getStartPosition() else pair.open.getEndPosition()
        cursor.setPosition(target.lineNumber, target.column)
        scrollIntoView(cursor.getX(), cursor.getY())
        cursor.stopBlink(true, false)
        cursor.startBlink()
        invalidate()
        return true
    }
    
    /**
     * The range that can be folded from [line], as the [FoldingRangeProvider] says (or
     * the folded one); null if there is none or no provider. Meant for the lines on
     * screen: the provider is asked about the block of lines around [line]. Main thread.
     */
    fun foldingRangeAt(line: Int): FoldingRange? = structure.foldingRangeAt(line)
    
    // What the structure needs from the view.
    private inner class StructureHost : CodeStructure.Host {
        override val view: View get() = this@EditorView
        override val document: EditorDocument get() = this@EditorView.document
        override val coordinatorLayout: TextLayout get() = layoutCoordinator.layout
        override val metrics: TextMetrics get() = textMetrics
        override val textVersion: Long get() = saveTracker.textVersion
        override val cursorLine: Int get() = cursor.lineNumber
        override val cursorColumn: Int get() = cursor.column
        override val selectionLines: IntRange?
            get() = if (isSelected()) selection.startLine..selection.endLine else null
        override fun moveCursor(line: Int, column: Int) {
            selection.deselect()
            cursor.setPosition(line, column)
        }
        override fun scrollCursorIntoView() {
            // whether it had to scroll does not matter here
            scrollIntoView(cursor.getX(), cursor.getY())
        }
        override val gutterEnabled: Boolean get() = this@EditorView.gutterEnabled
        override val gutterPanelWidth: Int get() = this@EditorView.gutterPanelWidth
        override val textLeft: Int get() = getGutterWidth()
        override val lineHeight: Int get() = getLineHeight()
        override fun baselineOf(row: Int): Int = getLineBaseline(row)
        override val scrollX: Int get() = getScrollX()
        override val scrollY: Int get() = getScrollY()
        override val viewportHeight: Int get() = this@EditorView.viewportHeight
        override val maxScrollY: Int get() = this@EditorView.maxScrollY
        override fun scrollTo(x: Int, y: Int) = this@EditorView.scrollTo(x, y)
        override val rowFoldMarkerLeft: Int get() = decorationPainter.rowFoldMarkerLeft()
        override val lineNumberColor: Int get() = decorationPainter.lineNumberColor
        override val foldMarkerColor: Int? get() = gutter.foldMarkerColor
        override fun refreshTextGeometry() = this@EditorView.refreshTextGeometry()
        override fun syncWrapWidth() = layoutCoordinator.syncWrapWidth()
        override fun invalidateRenderNodes() = this@EditorView.invalidateRenderNodes()
        override fun updateDrawableBounds() = this@EditorView.updateDrawableBounds()
        override fun runOnMainThread(action: () -> Unit) = this@EditorView.runOnMainThread(action)
        override fun addOverlay(drawable: Drawable) = this@EditorView.addOverlay(drawable)
        override val frameBudget: FrameBudget get() = this@EditorView.frameBudget
        override fun drawBracketText(canvas: Canvas, bracket: Range, visualLine: Int, x: Float, baseline: Float) =
            this@EditorView.drawBracketText(canvas, bracket, visualLine, x, baseline)
        override fun drawBracketUnderline(
            canvas: Canvas, bracket: Range, visualLine: Int, left: Float, right: Float, baseline: Float
        ) = this@EditorView.drawBracketUnderline(canvas, bracket, visualLine, left, right, baseline)
        override fun drawIndentGuide(
            canvas: Canvas, guide: IndentGuide, x: Float, top: Float, bottom: Float, active: Boolean
        ) = this@EditorView.drawIndentGuide(canvas, guide, x, top, bottom, active)
        override fun drawFoldMarker(
            canvas: Canvas, range: FoldingRange, folded: Boolean, left: Float, top: Float, right: Float, bottom: Float
        ) = this@EditorView.drawFoldMarker(canvas, range, folded, left, top, right, bottom)
        override fun drawFoldPlaceholder(
            canvas: Canvas, range: FoldingRange, left: Float, top: Float, right: Float, bottom: Float
        ) = this@EditorView.drawFoldPlaceholder(canvas, range, left, top, right, bottom)
        override fun onFoldMarkerClick(range: FoldingRange): Boolean = this@EditorView.onFoldMarkerClick(range)
        override fun onFoldChanged(folded: List<FoldingRange>) = this@EditorView.onFoldChanged(folded)
    }
    
    // #endregion
    
    // #region text color and margins
    
    // the default paint color (android:textColor)
    fun setTextColor(color: Int) {
        defaultTextColor = color
        textPaint.color = color
        runOnMainThread {
            // the recorded lines still have the old color
            invalidateRenderNodes()
            invalidate()
        }
    }
    
    fun setMargin(
        leftMargin: Int = 0, 
        topMargin: Int = 0, 
        rightMargin: Int = 0, 
        bottomMargin: Int = 0
    ) {
        // not every parent gives its children margins
        val params = layoutParams as? MarginLayoutParams ?: return
        params.leftMargin = leftMargin
        params.topMargin = topMargin
        params.rightMargin = rightMargin
        params.bottomMargin = bottomMargin
        requestLayout()
    }
    
    // #endregion
    
    // #region selection
    
    /**
     * Whether the two selection handles sway when the text is scrolled under them:
     * they hang back behind the text for a moment, sideways or up and down, and
     * swing into place. On by default. The insertion handle under the cursor never
     * sways. Main thread.
     */
    fun setHandleSwayEnabled(enabled: Boolean) {
        selectionController.isHandleSwayEnabled = enabled
    }
    
    fun isHandleSwayEnabled(): Boolean = selectionController.isHandleSwayEnabled
    
    /**
     * How far the selection handles sway at most, in pixels; 0 keeps them still.
     * The sway grows with the speed of the scrolling up to this distance, and this
     * scales it at every speed: half the amplitude, half the sway. The default is
     * [SelectionController.DEFAULT_HANDLE_SWAY_AMPLITUDE_DP] dp. Main thread.
     */
    fun setHandleSwayAmplitude(px: Float) {
        selectionController.handleSwayAmplitude = px
    }
    
    fun getHandleSwayAmplitude(): Float = selectionController.handleSwayAmplitude
    
    /**
     * What the handles look like: an image for each, or null for the handle the
     * editor draws itself (a stem with a round knob), which is the default. Main
     * thread.
     *
     * The images are taken to follow Android's convention, like the theme's own
     * (see [useSystemHandles]): all three hang below the row, the start handle
     * pointing up and to the right, the end handle up and to the left, and they
     * keep their own colors (see [setHandleColor]). The handles the editor draws
     * itself differ in one point: their start handle stands above its row.
     * Touching, dragging and the animations work the same for both looks.
     */
    fun setHandleDrawables(start: Drawable?, end: Drawable?, insertion: Drawable?) {
        selectionController.setHandleDrawables(start, end, insertion)
        invalidate()
    }
    
    /** Like the other setHandleDrawables, with drawable resources. Main thread. */
    fun setHandleDrawables(@DrawableRes start: Int, @DrawableRes end: Int, @DrawableRes insertion: Int) {
        setHandleDrawables(
            context.getDrawable(start),
            context.getDrawable(end),
            context.getDrawable(insertion)
        )
    }
    
    /**
     * Uses the handles of the theme, the ones Android's own text fields have
     * (android:textSelectHandleLeft, textSelectHandleRight, textSelectHandle).
     * Main thread.
     *
     * @return false if the theme has none of them; the handles are then left as
     *         they were
     */
    fun useSystemHandles(): Boolean {
        val start = themeDrawable(android.R.attr.textSelectHandleLeft)
        val end = themeDrawable(android.R.attr.textSelectHandleRight)
        val insertion = themeDrawable(android.R.attr.textSelectHandle)
        if (start == null || end == null || insertion == null) {
            return false
        }
        setHandleDrawables(start, end, insertion)
        return true
    }
    
    // a drawable the theme names by [attr], or null if it names none
    private fun themeDrawable(attr: Int): Drawable? {
        val array = context.obtainStyledAttributes(intArrayOf(attr))
        try {
            return array.getDrawable(0)
        } finally {
            array.recycle()
        }
    }
    
    /**
     * What the cursor looks like: an image, as high as a row and as wide as the
     * image says (2dp if it does not say), or null for the bar the editor draws
     * itself, the default, in the handle color (see [setHandleColor]). Main thread.
     */
    fun setCursorDrawable(drawable: Drawable?) {
        selectionController.setCursorDrawable(drawable)
        invalidate()
    }
    
    /** Like the other setCursorDrawable, with a drawable resource. Main thread. */
    fun setCursorDrawable(@DrawableRes resId: Int) {
        setCursorDrawable(context.getDrawable(resId))
    }
    
    /**
     * Uses the cursor of the theme, the one Android's own text fields have
     * (android:textCursorDrawable of the edit text style). Main thread.
     *
     * @return false if the theme has none; the cursor is then left as it was
     */
    fun useSystemCursor(): Boolean {
        val array = context.obtainStyledAttributes(
            null, intArrayOf(android.R.attr.textCursorDrawable), android.R.attr.editTextStyle, 0
        )
        val drawable = try {
            array.getDrawable(0)
        } finally {
            array.recycle()
        }
        drawable ?: return false
        setCursorDrawable(drawable)
        return true
    }
    
    /** How wide the cursor the editor draws is, in pixels. An image keeps its own width. Main thread. */
    fun setCursorWidth(px: Int) {
        selectionController.setCursorWidth(px)
        invalidate()
    }
    
    /** How wide the cursor is, whatever it looks like. */
    fun getCursorWidth(): Int = selectionController.getCursorWidth()
    
    /**
     * How fast the view scrolls when a finger that is selecting (dragging a handle,
     * or extending what a long press selected) is held at an edge, in lines per
     * second; slower where the finger is not quite at the edge. 0 switches scrolling
     * at the edges off. The default is
     * [EdgeAutoScroller.DEFAULT_SPEED_LINES_PER_SECOND]. Main thread.
     */
    fun setAutoScrollSpeed(linesPerSecond: Float) {
        gestureController.autoScrollSpeed = linesPerSecond
    }
    
    fun getAutoScrollSpeed(): Float = gestureController.autoScrollSpeed
    
    /**
     * How many words a second the delete key takes away once it has been held for
     * a while (it deletes characters before that). 0 never goes over to words. The
     * default is [BackspaceRepeat.DEFAULT_WORDS_PER_SECOND]. Main thread.
     */
    fun setFastDeleteSpeed(wordsPerSecond: Float) {
        backspaceRepeat.wordsPerSecond = wordsPerSecond
    }
    
    fun getFastDeleteSpeed(): Float = backspaceRepeat.wordsPerSecond
    
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
    
    override fun isSelected() = selectionController.isSelected()
    
    // #endregion
    
    // #region search results
    
    // this method should be run on main thread
    // use find to get the matches
    // The matches must be in document order. They are copied: the list can be reused
    // or changed by the caller afterwards without affecting the highlights, and the
    // editor moves its copies along when the text is edited.
    fun setSearchResults(matches: List<Range>?) {
        searchResults.setMatches(matches)
        invalidate()
    }
    
    /**
     * The matches as they are now, i.e. moved along with any edits since they were
     * set. Read only: to change them use [setSearchResults], [clearSearchResults] or
     * [removeSearchResult], so that the highlights are redrawn as well.
     */
    fun getSearchResults(): List<Range> = searchResults.matches
    
    /** Forgets all matches and the current one, and takes their highlights away. */
    fun clearSearchResults() {
        searchResults.clear()
        invalidate()
    }
    
    /**
     * Takes one match out of the results.
     *
     * @return the match that was removed, or null if there is none at [index]
     */
    fun removeSearchResult(index: Int): Range? {
        val removed = searchResults.removeAt(index)
        invalidate()
        return removed
    }
    
    @JvmName("setMatchedResult")
    fun setMatchResult(range: Range?) {
        searchResults.setCurrent(range)
        range?.let { scrollToRange(it) }
        invalidate()
    }
    
    @JvmName("getMatchedResult")
    fun getMatchResult() = searchResults.current
    
    /**
     * Which of the search results the current match is (0-based), for showing
     * "3 of 17". -1 if there is no current match or it is not one of the results.
     */
    fun getMatchResultIndex(): Int = searchResults.currentIndex
    
    /**
     * Makes the match after the current one the current match and scrolls to it.
     * Without a current match it starts from the cursor. After the last match it
     * goes on with the first.
     *
     * @return the new current match, or null if there are no matches
     */
    fun gotoNextMatch(): Range? {
        val current = searchResults.current
        val next = if (current != null) {
            searchResults.firstFrom(current.endLine, current.endColumn)
        } else {
            searchResults.firstFrom(cursor.lineNumber, cursor.column)
        }
        setMatchResult(next)
        return searchResults.current
    }
    
    /** Like [gotoNextMatch], backwards. */
    fun gotoPreviousMatch(): Range? {
        val current = searchResults.current
        val previous = if (current != null) {
            searchResults.lastBefore(current.startLine, current.startColumn)
        } else {
            searchResults.lastBefore(cursor.lineNumber, cursor.column)
        }
        setMatchResult(previous)
        return searchResults.current
    }
    
    // #endregion
    
    // #region editable, busy, and bringing the cursor into view
    
    fun setEditable(value: Boolean) {
        editable = value
    }
    
    // never while the layout is being rebuilt
    open fun isEditable() = editable && !layoutCoordinator.isBusy
    
    /**
     * Whether the whole text is being laid out again right now: a zoom is in progress,
     * or setTextSize / setTypeface / setWordwrap / setBuffer is running on some
     * thread. Until that is over every edit is refused (typing, paste,
     * undo/redo, setText).
     */
    fun isLayoutBusy() = layoutCoordinator.isBusy
    
    /**
     * Whether a pinch-zoom has started and the text has not been laid out again for
     * the new text size yet. Until then every change to the text is refused: edits,
     * undo/redo, setText, setBuffer and restoreSession.
     */
    fun isZoomInProgress() = layoutCoordinator.isZoomInProgress
    
    /**
     * Scrolls so that the cursor is in the middle of what can be seen, and draws
     * everything afresh. For after something that may have left the cursor far from
     * view: a file was loaded, a setting that moves the text was changed.
     */
    fun scrollCursorToCenter() {
        val dx = cursor.getX() - viewportWidth / 2
        val dy = cursor.getY() - visibleHeight / 2
        smoothScrollTo(
            dx.coerceIn(0, maxScrollX),
            dy.coerceIn(0, maxScrollY)
        )
        // update the render node display datas
        invalidateRenderNodes()
        // refresh the editor view
        invalidate()
    }
    
    @Deprecated("Renamed: the cursor is scrolled to the center.", ReplaceWith("scrollCursorToCenter()"))
    fun centerOnCursor() = scrollCursorToCenter()
    
    @Deprecated("The name said nothing about what it does.", ReplaceWith("scrollCursorToCenter()"))
    fun refresh() = scrollCursorToCenter()
    
    // #endregion
    
    // #region helpers for the above
    
    // Runs [action] now when already on the main thread, otherwise queues it there.
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            // a Handler of the main thread, not View.post: on a view that is not
            // attached to a window (yet) View.post waits for it to be attached,
            // and so would the end of loading a text, its onDone and the rest
            MAIN_HANDLER.post { action() }
        }
    }
    
    // The text moved on screen without the cursor moving in the document (text size,
    // typeface, wrap mode): re-record the lines, re-place cursor and handles, redraw.
    // Main thread only.
    private fun refreshTextGeometry() {
        invalidateRenderNodes()
        updateDrawableBounds()
        invalidate()
    }
    
    // #endregion
    
    // #region text queries (forwarded to the document)
    
    // \n or \r\n
    fun getEOL() = document.getEOL()

    fun getLineCount(): Int = document.getLineCount()
    
    fun getLineLength(lineNumber: Int) = document.getLineLength(lineNumber)
    
    fun getBufferLength() = document.length
    
    fun getRangeLength(range: Range) = document.getRangeLength(range)
    
    fun getPosition(offset: Int): Position = document.getPosition(offset)
    
    fun getOffset(lineNumber: Int, column: Int): Int = document.getOffset(lineNumber, column)
    
    fun getOffset(pos : Position) = getOffset(pos.lineNumber, pos.column)
    
    fun getCharAt(lineNumber: Int, column: Int) = document.getCharAt(lineNumber, column)
    
    fun getLineCharAt(lineNumber: Int, offset: Int): Char = document.getLineCharAt(lineNumber, offset)
    
    fun getLine(lineNumber: Int): String = document.getLine(lineNumber)

    fun getLineWithEOL(lineNumber: Int) = document.getLineWithEOL(lineNumber)

    fun getLineStartOffset(lineNumber: Int) = document.getLineStartOffset(lineNumber)

    fun getLineEndOffset(lineNumber: Int) = document.getLineEndOffset(lineNumber)
    
    fun getLineStartColumn(lineNumber: Int): Int = document.getLineStartColumn(lineNumber)

    // lineNumber defaults to the last line
    fun getLineEndColumn(lineNumber: Int = getLineCount()): Int = document.getLineEndColumn(lineNumber)
    
    fun getValueInRange(range: Range) = document.getValueInRange(range)
    
    fun getText() = document.getText()
    
    // empty when nothing is selected
    open fun getSelectedText(): String = document.getValueInRange(selection)
    
    /** The text, in whatever structure it is stored. */
    fun getBuffer(): EditorBuffer = document.buffer
    
    /**
     * The piece tree the text is stored in. Only for an editor whose buffer is the
     * piece tree one (the default, and what [setBuffer] with a piece tree gives);
     * with another kind of buffer there is no piece tree to hand out.
     */
    fun getTextBuffer(): PieceTreeTextBuffer {
        val buffer = document.buffer
        check(buffer is PieceTreeBuffer) {
            "The text is not stored in a piece tree but in a ${buffer.javaClass.name}; use getBuffer()."
        }
        return buffer.pieceTree
    }
    
    // find matches by regex
    fun find(
        regex: Regex,
        searchRange: Range = document.getFullRange(),
        limitResultCount: Int = Int.MAX_VALUE, 
        isCancelled: () -> Boolean = { false }
    ) = document.find(regex, searchRange, limitResultCount, isCancelled)
    
    // find matches by word
    fun find(
        searchText: String,
        searchRange: Range = document.getFullRange(),
        limitResultCount: Int = Int.MAX_VALUE,
        isCancelled: () -> Boolean = { false }
    ) = document.find(searchText, searchRange, limitResultCount, isCancelled)
    
    // #endregion
    
    // #region text measurements (forwarded to the metrics)
    
    // text start indent
    fun getGutterWidth(): Int = textMetrics.gutterWidth(getLineCount())
    // text end indent
    fun getEndPadding(): Int = textMetrics.endPadding
    
    fun getLineHeight(): Int = textMetrics.lineHeight
    
    // the baseline of a visual line
    fun getLineBaseline(lineNumber: Int): Int = textMetrics.baselineOf(lineNumber)
    
    // x of the end of the line text
    fun getLineEndX(lineNumber: Int): Int = getGutterWidth() + textLayout.getLineWidth(lineNumber)
    
    fun getLineTextWidth(lineNumber: Int) = textLayout.getLineWidth(lineNumber)
    
    fun getLineNumberWidth(lineNumber: Int = getLineCount()) = textMetrics.lineNumberWidth(lineNumber)
    
    fun getLineRangeWidth(lineNumber: Int, startColumn: Int, endColumn: Int): Int = measureText(
        document.getLineSegment(lineNumber, startColumn, endColumn)
    )
    
    fun getTextWidths(text: String, start: Int = 0, end: Int = text.length): FloatArray =
        textMetrics.getTextWidths(text, start, end)
    
    fun measureText(text: String): Int = textMetrics.measureText(text)
    
    // whitespace character width
    val spacingWidth: Int
        get() = textMetrics.spacingWidth
    
    // the width a wrapped line can take up
    val wordwrapWidth: Int
        get() = viewportWidth - getGutterWidth() - getEndPadding()
    
    // get the number of grapheme for emoji character
    fun getGraphemeLength(text: String, offset: Int) = TextBoundaries.graphemeLength(text, offset)
    
    // find the start boundary of a character
    fun getGraphemeStart(text: String, endOffset: Int) = TextBoundaries.graphemeStart(text, endOffset)
    
    // find the end boundary of a character
    fun getGraphemeEnd(text: String, startOffset: Int) = TextBoundaries.graphemeEnd(text, startOffset)
    
    // get the selected word boundary
    fun findWordBoundary(text: String, offset: Int) = TextBoundaries.findWordBoundary(text, offset)
    
    // #endregion
    
    // #region dimensions and limits
    
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
    // Never fewer than the rows on screen: with fewer, rows share a node and each
    // of them is recorded again on every frame, which is worse than no cache.
    open val nodeCacheSize: Int
        get() = Math.max(64, getHeight() / Math.max(1, getLineHeight()) + 2)
    
    // the minimum text size
    open val minTextSize: Float get() = 10f.sp
    
    // the maximum text size
    open val maxTextSize: Float get() = 25f.sp
    
    // how close to the left/right edge a point may get before the view auto scrolls
    open val autoScrollMargin: Int get() = 30f.dp
    
    // #endregion
    
    // #region geometry and overlay (EditorGeometry)
    
    // the viewport: the view without its padding, the window the text shows through
    val viewportWidth: Int get() = getWidth() - paddingHorizontal
    
    val viewportHeight: Int get() = getHeight() - paddingVertical
    
    val paddingVertical: Int
        get() = paddingTop + paddingBottom

    val paddingHorizontal: Int
        get() = paddingLeft + paddingRight

    val maxScrollX: Int
        get() = Math.max(0, paddingHorizontal + getGutterWidth() + textLayout.width() + getEndPadding() - getWidth())

    // The part of the view under the soft keyboard counts as not there: the end of
    // the text can be scrolled up to above the keyboard, not just to the bottom edge.
    val maxScrollY: Int
        get() = Math.max(
            0,
            paddingVertical + textLayout.height() + 2 * getLineHeight() - getHeight() + keyboardOverlap
        )
    
    // the part of the viewport not covered by the soft keyboard
    protected val visibleWidth: Int
        get() = viewportWidth
    
    protected val visibleHeight: Int
        get() = viewportHeight - keyboardOverlap
    
    // How much of the viewport is covered from below: by the soft keyboard, and by
    // the bottom overlay (see setBottomOverlay) if there is one. Zero when the
    // window (or the layout around the editor) makes room for both; in an
    // edge-to-edge window it is the part of the editor they lie on top of. The
    // bottom padding is not part of the viewport, so what is covered of it does not
    // count.
    private val keyboardOverlap: Int
        get() = Math.max(0, imeController.overlap - getPaddingBottom())
    
    /**
     * Tells the editor about a view that lies on top of its bottom edge, typically a
     * bar of extra keys (symbols, tab, arrows) that rides up on top of the soft
     * keyboard. The editor then keeps the cursor above that bar, and lets the end of
     * the text be scrolled up to above it. Null takes it back.
     *
     * Nothing has to be said about where the bar is or how it is moved: the editor
     * looks at where it is on screen. If the bar is moved by something that is
     * neither a layout pass nor the keyboard animation (say, an animation of its
     * own), call [updateBottomOverlay] when it has arrived.
     */
    fun setBottomOverlay(view: View?) {
        imeController.bottomOverlay = view
    }
    
    /** The bottom overlay was moved: look again at how much of the editor it covers. */
    fun updateBottomOverlay() {
        imeController.updateOverlap()
    }
    
    // The keyboard came up, went away or changed its height, or the bottom overlay did.
    private fun onKeyboardOverlapChanged(grew: Boolean) {
        if (grew && hasFocus()) {
            // it may have come up right over the cursor: bring the cursor back in view
            scrollIntoView(cursor.getX(), cursor.getY())
            revealCursorOnLayout = false
        } else if (getScrollY() > maxScrollY) {
            // the room it made for scrolling past the end of the text is gone
            scrollTo(getScrollX(), maxScrollY)
        }
        // the scrollbars end where the covered part begins
        scrollbarController.onViewportChanged()
        invalidate()
    }
    
    // The cursor, the handles and the scrollbar thumbs are drawn by onDraw, right
    // after the text and in the same (content) coordinates. View's own getOverlay()
    // is not used for them: it knows nothing about padding, and would draw them
    // shifted by the left/top padding relative to the text.
    
    private fun addOverlay(drawable: Drawable) {
        if (overlayDrawables.add(drawable)) {
            // so the drawable can ask for a redraw when it changes (see invalidateDrawable)
            drawable.callback = this
            invalidate()
        }
    }
    
    private fun removeOverlay(drawable: Drawable) {
        if (overlayDrawables.remove(drawable)) {
            drawable.callback = null
            invalidate()
        }
    }
    
    private fun hasOverlay(drawable: Drawable): Boolean {
        return overlayDrawables.contains(drawable)
    }
    
    override fun verifyDrawable(who: Drawable): Boolean {
        return hasOverlay(who) || super.verifyDrawable(who)
    }
    
    // A drawable on top of the text changed (moved, faded, got pressed). View would
    // work out the dirty area from its bounds as if they were view coordinates, which
    // they are not; redrawing the view is simpler and always right.
    override fun invalidateDrawable(drawable: Drawable) {
        if (hasOverlay(drawable)) {
            invalidate()
        } else {
            super.invalidateDrawable(drawable)
        }
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
        left: Int = textInsetLeft + autoScrollMargin,
        top: Int = getLineHeight(),
        right: Int = visibleWidth - autoScrollMargin,
        bottom: Int = visibleHeight - getLineHeight() * 2,
    ): Boolean {
        return scrollController.scrollIntoView(x, y, left, top, right, bottom)
    }
    
    /** Scrolls so that the start of [range] is in the middle of what can be seen. */
    fun scrollToRange(range: Range) {
        // Where the position is on screen, asked of the layout: with word wrap a
        // line number says nothing about how far down a line is, and a column
        // nothing about how far across.
        val (x, bottom) = getLocationForPosition(range.startLine, range.startColumn)
        smoothScrollTo(
            (x - viewportWidth / 2).coerceIn(0, maxScrollX),
            (bottom - visibleHeight / 2).coerceIn(0, maxScrollY)
        )
        invalidate()
    }
    
    /**
     * Puts the cursor at [column] of [lineNumber] (both clamped to the text) and scrolls
     * it to the middle of the view. [flash]: the line lights up for a moment and fades,
     * so that the eye finds where it landed (see flashLine).
     */
    @JvmOverloads
    fun gotoLine(lineNumber: Int, column: Int = 1, flash: Boolean = false) {
        val targetLine = Math.max(1, Math.min(lineNumber, getLineCount()))
        val targetColumn = column.coerceIn(1, Math.max(1, getLineEndColumn(targetLine)))
        cursor.setPosition(targetLine, targetColumn)

        // the cursor now knows where that line is on screen, wrapped or not; a column
        // past the start is brought into view across too
        val bottom = cursor.getY() + getLineHeight()
        val x = if (targetColumn > 1) (cursor.getX() - viewportWidth / 2).coerceIn(0, maxScrollX) else 0
        smoothScrollTo(x, (bottom - visibleHeight / 2).coerceIn(0, maxScrollY))
        if (flash) flashLine(targetLine)
        invalidate()
    }

    /** The line the cursor is on (1-based). */
    fun getCursorLine(): Int = cursor.lineNumber

    /** The column of the cursor in its line (1-based, in UTF-16 chars). */
    fun getCursorColumn(): Int = cursor.column

    /** The text of [lineNumber] (1-based), without its line break. */
    fun getLineText(lineNumber: Int): String = document.getLine(lineNumber.coerceIn(1, getLineCount()))

    // #region line flash

    // the line that lights up after a jump, and how much (0 to 1, animated)
    private var flashedLine = 0
    private var flashAmount = 0f
    private var flashAnimator: ValueAnimator? = null
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Lights the rows of [lineNumber] up for a moment, then lets them fade: a band over
     * the text in the theme's primary color, and a bar of it at the edge of the text.
     */
    fun flashLine(lineNumber: Int) {
        flashAnimator?.cancel()
        flashedLine = lineNumber
        // (colorPrimary is AppCompat's attribute: Material's own R does not have it)
        flashPaint.color = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        flashAnimator = ValueAnimator.ofFloat(0f, 1f, 1f, 0.6f, 0f).apply {
            duration = LINE_FLASH_DURATION
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                flashAmount = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flashedLine = 0
                    flashAmount = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    // Over the text and the gutter: the band from the edge of the text across the view,
    // and the bar at that edge. In content coordinates.
    private fun drawLineFlash(canvas: Canvas, textLeft: Int) {
        val line = flashedLine
        if (line <= 0 || flashAmount <= 0f || line > getLineCount()) return
        val layout = textLayout
        val lineHeight = getLineHeight()
        val top = (layout.getStartIndex(line) * lineHeight).toFloat()
        val bottom = ((layout.getEndIndex(line) + 1) * lineHeight).toFloat()
        val left = (scrollX + textLeft).toFloat()
        val color = flashPaint.color
        flashPaint.alpha = (LINE_FLASH_BAND_ALPHA * flashAmount).toInt()
        canvas.drawRect(left, top, (scrollX + width).toFloat(), bottom, flashPaint)
        flashPaint.alpha = (255 * flashAmount).toInt()
        val bar = 3f * resources.displayMetrics.density
        canvas.drawRect(left, top, left + bar, bottom, flashPaint)
        flashPaint.color = color
    }

    // #endregion

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        if (gestureController.isScaling) {
            // pinch-zoom moves the content too, but should not bring up the scrollbars
            return
        }
        scrollbarController.onScrollChanged(l, t, oldl, oldt)
        // the selection handles sway, and the knob of the start handle changes sides
        // when its row reaches the top
        selectionController.onViewportScrolled(l - oldl, t - oldt)
        // the completion list and the info card follow the cursor
        if (isShowingCompletions || isShowingInfo) {
            val host = rootView as? ViewGroup
            val anchor = host?.let { cursorAnchorIn(it) }
            if (host != null && anchor != null) {
                completionWindow?.moveTo(host, anchor)
                infoWindow?.moveTo(host, anchor)
            } else {
                dismissCompletions()
                dismissInfo()
            }
        }
        // the floating bar goes while the text moves, and comes back over it where it
        // stops, as the system's does
        if (selectionMenuWanted) {
            floatingBar?.hide()
            removeCallbacks(showSelectionMenuRunnable)
            postDelayed(showSelectionMenuRunnable, SELECTION_MENU_SETTLE_DELAY)
        }
    }
    
    // #endregion
    
    // #region view lifecycle and touch
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // No requestFocus() here: see init. And the cursor stays where the user left
        // it; only its bounds are worked out again, inside the text as it is now (it
        // may have become shorter while the view was detached).
        val line = cursor.lineNumber.coerceIn(1, document.getLineCount())
        val column = cursor.column.coerceIn(1, document.getLineEndColumn(line))
        cursor.setPosition(line, column)
        resumeCursorBlink()
        ViewCompat.requestApplyInsets(this)
        // a relayout that was called off when the view was detached is still due
        layoutCoordinator.resumeBackgroundRelayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // the bar is in the window's decor view, not in this view: it goes with it
        removeCallbacks(showSelectionMenuRunnable)
        floatingBar?.dismiss()
        // and so do the completion list and the info card
        completionWindow?.dismiss()
        infoWindow?.dismiss()
        completionPick = null
        flashAnimator?.cancel()
        // no point toggling the cursor of a view that is not on screen
        cursor.cancelBlinkCallbacks()
        // nor in laying out for it in the background: that reads the whole text and
        // keeps hold of the view until it is done
        layoutCoordinator.pauseBackgroundRelayout()
    }
    
    // Called when the editor stops (or starts) being visible to the user: its fragment
    // was hidden or replaced, the tab was switched, the view was set GONE, it is being
    // detached, or its window went to the background.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) {
            // Leaving with the keyboard up would have the system bring it back when
            // the editor is shown again. Only our own keyboard is put away: one that
            // serves another input field is left alone.
            imeController.hideIfActive()
            // a hidden fragment is not detached: stop the blink here as well
            cursor.cancelBlinkCallbacks()
        } else if (isAttachedToWindow) {
            resumeCursorBlink()
        }
    }
    
    // The editor is on screen again (attached again, shown again, back from the
    // background), and the blink that was stopped when it went away starts again:
    // unless the cursor was hidden then, and still has to be. That is the case while
    // text is selected (a long press, select all, Shift + arrows hide the cursor
    // behind the selection), and the selection is still there when the user comes
    // back. Blinking regardless would put the bar back on top of it.
    private fun resumeCursorBlink() {
        if (isSelected()) {
            cursor.stopBlink(false, false)
        } else {
            cursor.startBlink()
        }
    }
    
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // a touch on the text (a tap moves the cursor): the completion list and the info card go
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isShowingCompletions) dismissCompletions()
            if (isShowingInfo) dismissInfo()
        }
        // the DrawerLayout conflict with the vertical scrollbar
        requestDisallowInterceptTouchEvent(scrollbarController.isDragging)
        return super.dispatchTouchEvent(e)
    }
    
    private fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        getParent()?.requestDisallowInterceptTouchEvent(disallow)
    }
    
    // a hardware keyboard
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // the completion list, while it is shown, takes the keys that move through it
        if (isShowingCompletions && onCompletionKey(keyCode)) return true
        return keyboardController.onKeyDown(event) || super.onKeyDown(keyCode, event)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            applyInitialText()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // scroll to visible region
        if(changed && revealCursorOnLayout) {
            scrollIntoView(cursor.getX(), cursor.getY())
            revealCursorOnLayout = false
        }
        // the view may have moved or changed size relative to the soft keyboard
        imeController.updateOverlap()
        // With word wrap, another width (rotation, split screen, padding) means the
        // lines break elsewhere. Does nothing if the width is the one already laid
        // out for, so it is fine to ask on every layout pass.
        layoutCoordinator.syncWrapWidth()
    }
    
    // #endregion
    
    // #region gesture callbacks (GestureController.Host)
    
    private fun onCursorTapped() {
        revealCursorOnLayout = true
        // update position
        scrollIntoView(cursor.getX(), cursor.getY())
    }
    
    // A long press selected text and the finger lifted. Pressed at an edge, the
    // selection may be half off the screen or under the keyboard: it comes into view
    // as the cursor does after a tap, by where the finger was last (the cursor is
    // there), with room around it for the handles.
    // A dragged handle was let go. It springs back to its place on the text, which
    // may be off screen: held beside the text (past the end of a short line, below
    // the last line) while the view scrolled on at an edge, it went with the finger
    // and the text it belongs to was left behind. The view follows it back, as it
    // follows the cursor after a tap; where the place is in view already, nothing
    // moves.
    private fun revealHandle(handle: SelectionController.Handle) {
        val (x, bottom) = selectionController.getHandleAnchor(handle)
        scrollIntoView(x, bottom - getLineHeight())
        invalidate()
    }
    
    // A drag went onto a row whose text is all off to the left (a short line, after
    // the view was scrolled sideways for a long one). The view jumps there, no
    // animation, since the finger is still dragging: [x] ends up two thirds across
    // the text area, so the line shows up to it.
    private fun revealX(x: Int): Boolean {
        val inset = textInsetLeft
        val target = (x - inset - (viewportWidth - inset) * 2 / 3).coerceIn(0, maxScrollX)
        if (target == getScrollX()) return false
        scrollController.abortAnimation()
        scrollTo(target, getScrollY())
        return true
    }
    
    private fun revealSelection() {
        scrollIntoView(cursor.getX(), cursor.getY())
        invalidate()
    }
    
    // Moves the magnifier like EditText moves its own: sliding, not jumping, from
    // one row of text to the next. Null where there is no magnifier.
    private val magnifierAnimator: MagnifierAnimator? by lazy {
        val magnifier = this.magnifier
        if (magnifier != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            MagnifierAnimator(magnifier)
        } else {
            null
        }
    }
    
    private val magnifierPosition = PointF()
    
    /**
     * Shows the magnifier where the user is working: over the handle that is being
     * dragged, following the finger along the row, or else over the cursor.
     */
    open fun showMagnifier() {
        selectionController.getMagnifierPosition(magnifierPosition)
        // content -> view coordinates
        showMagnifierAt(
            magnifierPosition.x - getScrollX() + getPaddingLeft(),
            magnifierPosition.y - getScrollY() + getPaddingTop()
        )
    }
    
    // x, y are relative to the view
    fun showMagnifierAt(x: Int, y: Int) {
        showMagnifierAt(x.toFloat(), y.toFloat())
    }
    
    // x, y are relative to the view
    fun showMagnifierAt(x: Float, y: Float) {
        val animator = magnifierAnimator ?: return
        animator.show(x, y)
        // once more when this frame has been drawn: what is under the magnifier has
        // usually just changed (the cursor moved, the selection grew)
        post { animator.update() }
    }
    
    open fun dismissMagnifier() {
        magnifierAnimator?.dismiss()
    }
    
    protected open fun onZoomBegin() {
        // from here on the text must not change, until the zoom is over (see onZoomEnd)
        layoutCoordinator.beginZoom()
    }

    protected open fun onZoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        // Not while a relayout is measuring the text on another thread (a file is
        // being loaded, the text size was just set): it would measure some lines
        // at one size and the rest at another. The pinch simply has no effect until
        // that relayout is done, which the busy indicator of the app shows anyway.
        if (layoutCoordinator.isRelayoutRunning) {
            return
        }
        
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

    /**
     * The fingers were lifted. If the text size changed, the layout is brought up
     * to date in the background, see [onBackgroundRelayoutStarted]. If not, the
     * zoom is simply over.
     */
    protected open fun onZoomEnd() {
        layoutCoordinator.endZoom()
    }
    
    // #endregion
    
    // #region background relayout (LayoutCoordinator.Host)
    
    // the layout got new numbers without an edit: it was scaled, or laid out again
    private fun onLayoutUpdated() {
        refreshTextGeometry()
    }
    
    /**
     * The layout no longer fits and is about to be rebuilt in the background: a
     * pinch-zoom changed the text size, or (with word wrap) the view got another
     * width. Until [onBackgroundRelayoutFinished] the text is locked, but scrolling
     * works.
     *
     * Override (and call super) to show that the editor is busy.
     */
    protected open fun onBackgroundRelayoutStarted() {
        rememberScrollAnchor()
    }
    
    /**
     * The layout is right again and the text may change again. Always follows
     * [onBackgroundRelayoutStarted], exactly once.
     *
     * Override (and call super) to hide what was shown there.
     */
    protected open fun onBackgroundRelayoutFinished() {
        restoreScrollAnchor()
    }
    
    // When lines are wrapped differently, the same scroll position shows a different
    // part of the text. So what is kept across a relayout is not the position but
    // the text at the top of the viewport: which buffer line, from which offset on,
    // and how far the view was scrolled into that row.
    private var anchorBufferLine = 1
    private var anchorLineOffset = 0
    private var anchorRowScroll = 0
    
    private fun rememberScrollAnchor() {
        val lineHeight = getLineHeight()
        val topRow = getScrollY() / lineHeight + 1
        anchorBufferLine = textLayout.getBufferLine(topRow)
        anchorLineOffset = textLayout.getLineStart(topRow)
        anchorRowScroll = getScrollY() - (topRow - 1) * lineHeight
    }
    
    private fun restoreScrollAnchor() {
        // the row that shows the remembered text now (0-based)
        val topRowIndex = textLayout.getIndexAt(anchorBufferLine, anchorLineOffset + 1)
        val y = topRowIndex * getLineHeight() + anchorRowScroll
        scrollTo(
            getScrollX().coerceIn(0, maxScrollX),
            y.coerceIn(0, maxScrollY)
        )
    }
    
    // #endregion
    
    // #region draw
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun recycleRenderNodes() = renderer.recycleRenderNodes()
    
    @RequiresApi(Build.VERSION_CODES.Q)
    public fun invalidateRenderNodes() = renderer.invalidateRenderNodes()
    
    // re-position the cursor and the handles after the text moved on screen
    protected fun updateDrawableBounds() = selectionController.updateDrawableBounds()
    
    // draw rect with int values (a helper for subclasses' own draw* overrides)
    protected fun<L, T, R, B> Canvas.drawRect(left: L, top: T, right: R, bottom: B, paint: Paint = textPaint) 
    where L: Number, T: Number, R: Number, B: Number {
        drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }
    
    // The draw* hooks. Override one to paint that piece yourself; the defaults are
    // carried out by decorationPainter. In all of them `bufferLine` is the real line
    // number in the buffer and `visualLine` the visual (on-screen) line; the two only
    // differ in word wrap mode.
    
    /**
     * Decides which backgrounds one visual line gets, and paints them.
     *
     * @bufferLine buffer line number, which equals the cursor line number
     * @visualLine visual line number, which equals the scrolled distance
     * @start @end the 0-based offsets of the line text shown on this visual line
     */
    protected open fun drawRegionHighlight(
        canvas: Canvas, bufferLine: Int, visualLine: Int, start: Int, end: Int
    ) {
        // the background of a block the line is in (a code block in Markdown), under
        // everything else
        lineStyler?.lineBackground(bufferLine)?.let { color ->
            drawBlockBackground(canvas, bufferLine, visualLine, color)
        }

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
        
        // the bracket at the cursor and its partner
        if (structure.bracketHighlight) {
            structure.bracketPair()?.let { pair ->
                if (bufferLine == pair.open.startLine) drawBracketHighlight(canvas, pair.open, visualLine)
                if (bufferLine == pair.close.startLine) drawBracketHighlight(canvas, pair.close, visualLine)
            }
        }
        
        // underline what the input method is composing
        inputBridge.composingRange?.let {
            if (bufferLine >= it.startLine && bufferLine <= it.endLine) {
                drawComposingUnderline(canvas, it, visualLine)
            }
        }
    }
    
    // underline the text the input method is still composing, on one visual line
    protected open fun drawComposingUnderline(canvas: Canvas, composing: Range, line: Int) {
        decorationPainter.drawComposingUnderline(canvas, composing, line)
    }
    
    /**
     * The background the styler gives [bufferLine] (see LineStyler.lineBackground), on
     * one of its rows, [visualLine]. The default fills the row from where the text
     * starts to the right edge of the view.
     */
    protected open fun drawBlockBackground(canvas: Canvas, bufferLine: Int, visualLine: Int, color: Int) {
        decorationPainter.drawLineFill(canvas, visualLine, color)
    }

    // highlight the line the cursor is on; line is a visual line
    protected open fun drawLineBackground(canvas: Canvas, line: Int) {
        decorationPainter.drawCurrentLine(canvas, line)
    }
    
    // highlight the selected text on one visual line
    protected open fun drawSelectionBackground(canvas: Canvas, line: Int) {
        decorationPainter.drawSelection(
            canvas, selection, line,
            // a selection made by a long press grows out from under the finger
            selectionController.selectionRevealFraction, selectionController.selectionRevealPivotX
        )
    }
    
    // highlight the search matches on one visual line; columns are 1-based
    protected open fun drawMatchesBackground(
        canvas: Canvas, bufferLine: Int, visualLine: Int, startColumn: Int, endColumn: Int
    ) {
        decorationPainter.drawSearchMatches(canvas, searchMatches, bufferLine, visualLine, startColumn, endColumn)
        // the one the user is at, on top of the others
        searchResults.current?.let {
            if (bufferLine >= it.startLine && bufferLine <= it.endLine) {
                decorationPainter.drawCurrentMatch(canvas, it, visualLine)
            }
        }
    }
    
    /**
     * The background of [bracket] (one char, a bracket at the cursor or its partner)
     * on one visual line, under the text. Called for both brackets of the pair, on
     * each visual line; the default fills the bracket's cell when it is on that line.
     */
    protected open fun drawBracketHighlight(canvas: Canvas, bracket: Range, visualLine: Int) {
        decorationPainter.drawBracketMatch(canvas, bracket, visualLine)
    }
    
    /**
     * The bracket itself, over the text: [bracket] (one char) on its [visualLine],
     * starting at [x] on [baseline]. The default draws it once more in bold, in the
     * color it has (its style from the highlighter), over the plain one: bold is
     * drawn by thickening the same glyph, so it covers the plain one exactly.
     */
    protected open fun drawBracketText(canvas: Canvas, bracket: Range, visualLine: Int, x: Float, baseline: Float) {
        val line = bracket.startLine
        val text = document.getLine(line)
        val start = bracket.startColumn - 1
        if (start < 0 || start >= text.length) return
        val spans = bracketStyle(text, line, start)
        if (spans == null) {
            boldTextPaint.set(textPaint)
            boldTextPaint.isFakeBoldText = true
            canvas.drawText(text, start, start + 1, x, baseline, boldTextPaint)
        } else {
            val rowTop = baseline - getLineBaseline(1)
            styledTextPainter.draw(
                canvas, text, spans, start, start + 1,
                x, baseline, rowTop, rowTop + getLineHeight(), textPaint, forceBold = true
            )
        }
    }
    
    // the text paint, bold, for drawBracketText
    private val boldTextPaint = TextPaint()
    
    // The style of a bracket at the cursor, from the styler, kept while the text, the
    // bracket, the styler and its styles stay the same: drawBracketText runs every
    // frame (the cursor blinks), and a styler may run a query for each call.
    private class BracketStyle(
        val textVersion: Long, val line: Int, val index: Int,
        val styler: LineStyler?, val stylesGeneration: Int, val spans: Spanned?
    )
    private val bracketStyles = arrayOfNulls<BracketStyle>(2)
    private var stylesGeneration = 0
    
    private fun bracketStyle(text: String, line: Int, index: Int): Spanned? {
        val styler = lineStyler
        for (kept in bracketStyles) {
            if (kept != null && kept.textVersion == saveTracker.textVersion && kept.line == line && kept.index == index &&
                kept.styler === styler && kept.stylesGeneration == stylesGeneration
            ) {
                return kept.spans
            }
        }
        val spans = styler?.styleLine(text, line, document.getLineStartOffset(line), index, index + 1)
        // the two brackets of the pair take turns in two places
        bracketStyles[1] = bracketStyles[0]
        bracketStyles[0] = BracketStyle(saveTracker.textVersion, line, index, styler, stylesGeneration, spans)
        return spans
    }
    
    /**
     * The line under [bracket] (one char) on its [visualLine], from [left] to
     * [right], below the [baseline]. The default draws it in the bracket underline
     * color (see [setBracketUnderlineColor]).
     */
    protected open fun drawBracketUnderline(
        canvas: Canvas, bracket: Range, visualLine: Int, left: Float, right: Float, baseline: Float
    ) {
        decorationPainter.drawBracketUnderline(canvas, left, right, baseline)
    }
    
    
    /**
     * Draws [guide] at [x], from [top] to [bottom] (the part on screen, content
     * coordinates); [active] for the guide of the block the cursor is in.
     */
    protected open fun drawIndentGuide(
        canvas: Canvas, guide: IndentGuide, x: Float, top: Float, bottom: Float, active: Boolean
    ) {
        decorationPainter.drawIndentGuide(canvas, x, top, bottom, active)
    }
    
    /**
     * Draws the fold marker of [range] in its place next to the line number, the
     * rectangle given (content coordinates); [folded] if the range is folded. The
     * default is a small chevron, see Gutter.drawDefaultFoldMarker.
     */
    protected open fun drawFoldMarker(
        canvas: Canvas, range: FoldingRange, folded: Boolean, left: Float, top: Float, right: Float, bottom: Float
    ) {
        // turning, if it was just tapped
        val fraction = structure.markerFoldedFraction(range.startLine, folded)
        structure.foldStyle.drawMarker(canvas, fraction, left, top, right, bottom, structure.markerColor)
    }
    
    /**
     * Draws what stands in for the folded lines of [range]: at the end of its start
     * line, in the rectangle given (content coordinates). A tap there unfolds it.
     */
    protected open fun drawFoldPlaceholder(
        canvas: Canvas, range: FoldingRange, left: Float, top: Float, right: Float, bottom: Float
    ) {
        structure.foldStyle.drawPlaceholder(canvas, left, top, right, bottom, structure.markerColor)
    }
    
    /**
     * The number of [line] in the rows (line numbers without the gutter), on the
     * baseline [y]; drawn on every frame. The number of the cursor's line stands out
     * (see setCurrentLineNumberColor), as it does in the gutter.
     */
    protected open fun drawLineNumber(
        canvas: Canvas, line: Int, x: Float, y: Float
    ) {
        decorationPainter.drawLineNumber(canvas, line, y, current = line == cursor.lineNumber)
    }
    
    /**
     * Draws the text of one row (a whole line, or with word wrap a piece of one):
     * text[startOffset, endOffset) of buffer line [line], starting at [xPaint] on the
     * baseline [yPaint].
     *
     * Plain by default, or styled by the [LineStyler] if one is set, which is the
     * way to add syntax highlighting. Override only to paint text in a way a styler
     * cannot express.
     */
    protected open fun drawText(
        canvas: Canvas, text: String, line: Int, startOffset: Int, endOffset: Int, xPaint: Float, yPaint: Float
    ) {
        val styler = lineStyler
        val spans = if (styler == null || endOffset <= startOffset) {
            null
        } else if (rowStylingMayDefer && frameBudget.isSpent) {
            // A row that is recorded and kept, has nothing on screen yet, in a frame
            // that has no time left: plain for now, styled on one of the next frames
            // (see frameBudget).
            rowStylingDeferred = true
            frameBudget.defer()
            null
        } else {
            // Styled now. Counted against the frame also when it cannot wait (an
            // edited row): what is left of the frame is what can wait.
            frameBudget.measure {
                val lineStart = document.getLineStartOffset(line)
                // Styles still being worked out in the background: painted with what the
                // styler has (the colors of before an edit, or none), and styled again
                // when they are there (it says so): no frame is asked for meanwhile.
                if (!styler.isStyleReady(line, lineStart, startOffset, endOffset)) rowStylingDeferred = true
                styler.styleLine(text, line, lineStart, startOffset, endOffset)
            }
        }
        
        if (spans == null) {
            if (TabStops.hasTab(text, startOffset, endOffset)) {
                // tabs up to their stops, from the start of the row
                TabStops.draw(canvas, text, startOffset, endOffset, xPaint, yPaint, textPaint, textMetrics.tabWidth)
            } else {
                canvas.drawText(text, startOffset, endOffset, xPaint, yPaint, textPaint)
            }
        } else {
            styledTextPainter.tabWidth = textMetrics.tabWidth
            // The top of the row, from its baseline. Not from the line number: with
            // word wrap that is not the row, and a cached row is recorded at row 1.
            val rowTop = yPaint - getLineBaseline(1)
            styledTextPainter.draw(
                canvas, text, spans, startOffset, endOffset,
                xPaint, yPaint, rowTop, rowTop + getLineHeight(), textPaint
            )
        }
    }
    
    // how the text is styled (syntax highlighting); null for plain text
    private var lineStyler: LineStyler? = null
    private val styledTextPainter: StyledTextPainter = StyledTextPainter()
    
    /**
     * Has the text styled by [styler]: colors, bold, italic, underline. This is how
     * syntax highlighting is added, see [LineStyler]. Null goes back to plain text.
     */
    fun setLineStyler(styler: LineStyler?) {
        setSyntaxHighlighter(null)
        lineStyler = styler
        invalidateStyles()
    }
    
    // the highlighting engine, if the styler is one: it is also told about the text
    private var syntaxHighlighter: SyntaxHighlighter? = null
    
    // the editor as a highlighting engine sees it
    private val highlighterHost = object : SyntaxHighlighter.Host {
        override val buffer: EditorBuffer get() = document.buffer
        override fun invalidateStyles() = this@EditorView.invalidateStyles()
        override fun invalidateStyles(startLine: Int, endLine: Int) =
            this@EditorView.invalidateStyles(startLine, endLine)
        override fun invalidateStructure() = this@EditorView.invalidateStructure()
    }
    
    /**
     * Plugs in a syntax highlighting engine. It styles the text, like a
     * [LineStyler], and on top of that is told whenever the text is replaced or
     * edited, so that it can keep what it knows about the text up to date. See
     * [SyntaxHighlighter]. Null takes the engine out; the text is plain again.
     */
    fun setSyntaxHighlighter(highlighter: SyntaxHighlighter?) {
        if (highlighter === syntaxHighlighter) {
            return
        }
        syntaxHighlighter?.onDetached()
        syntaxHighlighter = highlighter
        lineStyler = highlighter
        highlighter?.onAttached(highlighterHost)
        invalidateStyles()
    }
    
    fun getSyntaxHighlighter(): SyntaxHighlighter? = syntaxHighlighter
    
    /**
     * What the styler would answer has changed (it finished parsing, another theme
     * was picked): paints the text again with the new styles.
     */
    fun invalidateStyles() {
        runOnMainThread {
            stylesGeneration++
            // The recorded rows keep showing their text, with the styles they have,
            // until they are styled again: on this frame, or on the next ones if it has
            // no time left. Recorded plainly in between, they would flicker.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderer.hasRenderNodes) {
                renderer.invalidateAllRowStyles()
            }
            invalidate()
        }
    }

    /**
     * Like [invalidateStyles], for the lines [startLine] to [endLine] (1-based,
     * inclusive) only: the other lines keep what was drawn of them. Any thread.
     */
    fun invalidateStyles(startLine: Int, endLine: Int) {
        runOnMainThread {
            stylesGeneration++
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && renderer.hasRenderNodes) {
                // as above: what they show stays until the new styles are there
                invalidateRowsOfLines(startLine, endLine, stylesOnly = true)
            }
            invalidate()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // a frame of its own: the budget for styling and structure starts again
        frameBudget.reset()
        // the cursor may have got into folded lines since the last frame
        structure.revealCursor()
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
        
        // The canvas is now in content coordinates and clipped to the viewport.
        
        // draw the text content
        renderer.draw(canvas)
        // over the rows: the indent guides, the brackets at the cursor (bold,
        // underlined), the placeholders of folded lines (and the fold markers in the rows)
        structure.drawOverText(canvas)
        // the diagnostics, under the text they are about (the gutter goes over them)
        drawDiagnostics(canvas)
        // the gutter, over the text that has scrolled under it
        val inset = textInsetLeft
        if (gutterEnabled) {
            gutter.draw(canvas)
        }
        // the line a jump landed on, lighting up and fading
        drawLineFlash(canvas, inset)
        // On top of it the cursor, the handles and the scrollbar thumbs. The cursor
        // only where the text can be seen, not over the gutter. A handle whose tip is
        // on text that can be seen is drawn whole, also where it hangs out over the
        // gutter; one whose tip has scrolled under the gutter is not drawn at all.
        for (drawable in overlayDrawables) {
            if (inset > 0 && selectionController.isTextOverlay(drawable)) {
                if (drawable is TextHandleDrawable) {
                    if (drawable.tip >= scrollX + inset) {
                        drawable.draw(canvas)
                    }
                    continue
                }
                canvas.save()
                canvas.clipRect(scrollX + inset, scrollY, scrollX + viewportWidth, scrollY + viewportHeight)
                drawable.draw(canvas)
                canvas.restore()
            } else {
                drawable.draw(canvas)
            }
        }
        
        // The edge effect belongs to the edges of the viewport, wherever the content
        // is scrolled to: move the origin to the viewport's top left corner.
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        if (scrollController.drawOverscroll(canvas)) {
            postInvalidateOnAnimation()
        }
        canvas.restore()
        
        reportSelectionToInputMethod()
        updateGestureExclusion()

        // rows painted plainly, structure not asked for: on the next frame
        if (frameBudget.hasDeferred) {
            postInvalidateOnAnimation()
        }
    }
    
    private val gestureExclusion = Rect()
    private val reportedGestureExclusion = Rect()
    
    // With gesture navigation, a swipe that starts at the left or right edge of the
    // screen means "back", and the system takes it before the view sees it. The
    // vertical scrollbar thumb sits right on that edge. So while the thumb is shown,
    // its touch area is declared off limits for that gesture; the rest of the edge
    // keeps working as usual.
    //
    // Called at the end of every frame, like the selection report above: the thumb
    // appears, moves and fades through redraws, so this follows it without anybody
    // having to remember. Only a change is passed on to the system.
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        if (scrollbarController.getVerticalTouchBounds(gestureExclusion)) {
            // content -> view coordinates
            gestureExclusion.offset(getPaddingLeft() - getScrollX(), getPaddingTop() - getScrollY())
        } else {
            gestureExclusion.setEmpty()
        }
        if (gestureExclusion == reportedGestureExclusion) {
            return
        }
        reportedGestureExclusion.set(gestureExclusion)
        systemGestureExclusionRects =
            if (gestureExclusion.isEmpty) emptyList() else listOf(Rect(gestureExclusion))
    }

    // #endregion

    // #region soft keyboard

    open fun showSoftKeyboard() = imeController.show()

    fun hideSoftKeyboard() = imeController.hide()
    
    /**
     * What kind of text the soft keyboard is told to expect, as InputType flags.
     * The default suits code: several lines, no suggestions and no auto-correction.
     * For prose, leave out TYPE_TEXT_FLAG_NO_SUGGESTIONS.
     */
    fun setInputType(type: Int) {
        inputType = type
        resetInput()
    }
    
    fun getInputType() = inputType
    
    // without this the system does not treat the view as something to type into
    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = inputType
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        // where the input method starts from
        outAttrs.initialSelStart = inputBridge.selectionStartOffset
        outAttrs.initialSelEnd = inputBridge.selectionEndOffset
        
        // a new connection knows nothing of what an old one was composing
        inputBridge.composer.reset()
        return EditorInputConnection(this, inputBridge.composer, environment)
    }
    
    // The text was replaced as a whole, or an edit of the input method was refused:
    // what it believes about the text is no longer true, so it has to start over.
    // Main thread.
    private fun resetInput() {
        inputBridge.composer.reset()
        imeController.restartInput()
    }
    
    // Keeps the input method informed about the selection and the composing region.
    // Called at the end of every frame: whatever moves the cursor or the selection
    // also redraws, so this catches every change without each of them having to
    // remember to report it. Only a change is actually passed on.
    private fun reportSelectionToInputMethod() {
        // in the middle of a group of edits, or of a relayout, the numbers are in flux
        val composer = inputBridge.composer
        if (composer.isBatchEditing || layoutCoordinator.isBusy) {
            return
        }
        // Right after another buffer was put in, and before the cursor was moved into
        // it, the cursor can point past the end of the text. There is no offset to
        // report for that; the next frame will have one.
        if (!inputBridge.isSelectionInsideDocument()) {
            return
        }
        val changed = imeController.reportSelection(
            inputBridge.selectionStartOffset, inputBridge.selectionEndOffset,
            composer.composingStart, composer.composingEnd
        )
        // a screen reader follows the cursor by the same report
        if (changed) {
            accessibility.notifySelectionChanged()
        }
    }
    
    // the selection as the input method bridge wants it: an empty range at the
    // cursor when nothing is selected
    private val selectionRange: Range
        get() = if (isSelected()) {
            Range(selection.startLine, selection.startColumn, selection.endLine, selection.endColumn)
        } else {
            Range(cursor.lineNumber, cursor.column, cursor.lineNumber, cursor.column)
        }
    
    // what the keyboard asks for besides text (EditorInputConnection.Host)
    
    // a key that came through the input connection is a key like any other
    private fun onKeyEvent(event: KeyEvent): Boolean {
        return event.action == KeyEvent.ACTION_DOWN && keyboardController.onKeyDown(event)
    }
    
    // what the keys ask for (KeyboardController.Host)
    
    // tells a tap on the delete key from the key being held down
    private val backspaceRepeat = BackspaceRepeat()
    
    // Backspace. Held down, it deletes character by character at first and then,
    // if it is still held, word by word at a pace of its own (see setFastDeleteSpeed).
    private fun onBackspacePressed() {
        if (isSelected()) {
            // the selection goes, whole, whatever the key is doing
            backspaceRepeat.reset()
            delete()
            return
        }
        when (backspaceRepeat.next(SystemClock.uptimeMillis())) {
            // in an empty pair, both chars go
            BackspaceRepeat.Step.CHARACTER -> if (!autoPairs.onBackspace()) delete()
            BackspaceRepeat.Step.WORD -> delete(document.wordBackspaceRange(cursor))
            // a repeat between two words
            BackspaceRepeat.Step.NONE -> { }
        }
    }
    
    private fun onForwardDeletePressed() {
        delete(if (isSelected()) selection.clone() else document.forwardDeleteRange(cursor))
    }
    
    private fun onCaretMove(move: CaretMove, extendSelection: Boolean) {
        dismissSelectionMenu()
        selectionController.moveCaret(move, extendSelection, pageHeight = visibleHeight)
        // the cursor is at the caret also while a selection hides it
        scrollIntoView(cursor.getX(), cursor.getY())
        if (!isSelected()) {
            cursor.startBlink()
        }
        invalidate()
    }
    
    private fun onEscapePressed(): Boolean {
        if (!isSelected()) {
            return false
        }
        selection.deselect()
        dismissSelectionMenu()
        cursor.stopBlink(true, false)
        cursor.startBlink()
        invalidate()
        return true
    }
    
    private fun onContextMenuAction(id: Int): Boolean = when (id) {
        android.R.id.selectAll -> {
            selectAll()
            true
        }
        android.R.id.cut -> cut()
        android.R.id.copy -> copy()
        android.R.id.paste -> paste()
        else -> false
    }
    
    // #endregion
    
    // #region accessibility
    
    // the first and the last buffer line that is (partly) on screen
    private fun visibleBufferLines(): IntRange {
        val lineHeight = Math.max(1, getLineHeight())
        val firstRow = getScrollY() / lineHeight + 1
        val lastRow = firstRow + Math.max(0, visibleHeight / lineHeight)
        return textLayout.getBufferLine(firstRow)..textLayout.getBufferLine(lastRow)
    }
    
    // a screen reader treats the editor as what it is: a text field
    override fun getAccessibilityClassName(): CharSequence = "android.widget.EditText"
    
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        accessibility.populateNodeInfo(info, isEditable())
    }
    
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_COPY -> copy()
        AccessibilityNodeInfo.ACTION_CUT -> cut()
        AccessibilityNodeInfo.ACTION_PASTE -> paste()
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> accessibility.setSelection(arguments)
        else -> super.performAccessibilityAction(action, arguments)
    }
    
    // #endregion
    
    // #region edit callbacks
    
    /**
     * Has [callback] told about every edit, in the three stages of the edit pipeline
     * (before, once per change, after), right after the editor itself has dealt with
     * each stage. For whatever has to keep in step with the text without being part
     * of the editor: a syntax tree, a "modified" marker, a line count in the toolbar.
     *
     * Called on the main thread, in the middle of the edit: a callback must not
     * edit the text itself, and should be quick.
     */
    fun addEditCallback(callback: EditorDocument.Callback) {
        if (!editCallbacks.contains(callback)) {
            editCallbacks.add(callback)
        }
    }
    
    fun removeEditCallback(callback: EditorDocument.Callback) {
        editCallbacks.remove(callback)
    }
    
    // #endregion
    
    // #region edit hooks
    // The three stages of the edit pipeline run by EditorDocument.applyEdits.
    // Overrides should call super.
    
    protected open fun beforeTextChanged(
        edits: List<SliceEdit>
    ) {
        cursor.stopBlink(true, false)
        selection.deselect()
        dismissSelectionMenu()
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
        // update the text layout (its width and height follow by themselves); the
        // coordinator's, folded lines or not: folding is laid over it
        val layout = layoutCoordinator.layout
        if (insertedLinesCnt >= LARGE_EDIT_LINES) {
            // Measuring that many lines here would stall the main thread for seconds
            // (an undo that puts back half a large file): they get rough rows now,
            // and are measured in the background once the edit is done.
            layout.updateRoughly(range.startLine, insertedLinesCnt, deletedLinesCnt)
            largeEditLaidOutRoughly = true
        } else {
            layout.update(range.startLine, insertedLinesCnt, deletedLinesCnt)
        }
    }
    
    // an edit that is under way was laid out roughly, see onTextChanged
    private var largeEditLaidOutRoughly = false

    // the rows and lines before the edit under way, see invalidateEditedRows
    private var rowCountBeforeEdit = 0
    private var lineCountBeforeEdit = 0

    // The cached rows that no longer show what they were recorded with after [changes].
    // They are recorded again at once, styled (see TextRenderer: plain for a frame, they
    // would flicker), so as few as possible:
    //  - one change (typing, deleting, Enter, a line joined): the rows of the edited
    //    lines. The rows below them show what they did, moved up or down with their
    //    lines: their recordings move along (TextRenderer.onRowsEdited).
    //  - several (replace all, a multi-line indent): those of the edited lines when every
    //    change kept its number of lines and the rows are as many as before, otherwise
    //    everything from the first edited line down, which moved.
    // The rows above the first edited line are as they were.
    private fun invalidateEditedRows(changes: List<EditorDocument.AppliedChange>) {
        if (!renderer.hasRenderNodes || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (changes.isEmpty()) return
        val lineCount = document.getLineCount()
        val layout = textLayout
        val change = changes.singleOrNull()
        if (change != null && lineCount - lineCountBeforeEdit == change.insertedLinesCnt - change.deletedLinesCnt) {
            // the lines the edit put in place of the ones it took away, as they are now
            val firstLine = change.range.startLine.coerceIn(1, lineCount)
            val lastLine = (change.range.startLine + change.insertedLinesCnt).coerceIn(firstLine, lineCount)
            val firstRow = layout.getStartIndex(firstLine) + 1
            val lastRowAfter = layout.getEndIndex(lastLine) + 1
            // the rows above them did not change: the edit took away as many rows fewer
            // or more than it put in as the layout has now
            val lastRowBefore = lastRowAfter - (layout.count() - rowCountBeforeEdit)
            if (lastRowAfter >= firstRow && lastRowBefore >= firstRow - 1) {
                renderer.onRowsEdited(firstRow, lastRowBefore, lastRowAfter)
                return
            }
        }
        val firstLine = changes.minOf { it.range.startLine }
        val sameShape = changes.all { it.insertedLinesCnt == it.deletedLinesCnt } &&
            lineCount == lineCountBeforeEdit &&
            layout.count() == rowCountBeforeEdit
        val lastLine = if (sameShape) changes.maxOf { it.finalLineNumber } else Int.MAX_VALUE
        invalidateRowsOfLines(firstLine, lastLine, stylesOnly = false)
    }

    // The cached rows of the lines [startLine] to [endLine] are to be recorded again; an
    // [endLine] past the last line: down to the end. [stylesOnly]: their text is the same,
    // only its styles may have changed (they keep showing what they do meanwhile).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun invalidateRowsOfLines(startLine: Int, endLine: Int, stylesOnly: Boolean) {
        val lineCount = document.getLineCount()
        if (startLine > lineCount || endLine < startLine) return
        val layout = textLayout
        val firstRow = layout.getStartIndex(startLine.coerceIn(1, lineCount)) + 1
        val lastRow = if (endLine >= lineCount) Int.MAX_VALUE else layout.getEndIndex(endLine) + 1
        if (stylesOnly) {
            renderer.invalidateRowStyles(firstRow, lastRow)
        } else {
            renderer.invalidateRows(firstRow, lastRow)
        }
    }

    protected open fun afterTextChanged(
        changes: List<EditorDocument.AppliedChange>,
        lastLineNumber: Int,
        lastColumn: Int
    ) {
        // an edit that did not come from the input method ends what it was composing
        inputBridge.composer.onDocumentEdited()
        
        // record again the rows whose text changed
        invalidateEditedRows(changes)
                
        // set cursor position: where the edit ended, or where a completion wants it
        // (in its snippet: a tab stop, its placeholder selected)
        val caret = completionCaret
        completionCaret = null
        if (caret != null) {
            placeCompletionCaret(caret)
        } else {
            cursor.setPosition(lastLineNumber, lastColumn)
        }
        scrollIntoView(cursor.getX(), cursor.getY())        
        // cursor start blink
        cursor.startBlink()      
        invalidate()
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
    
    // #endregion
    
    // #region indentation
    
    // Enter, Tab and Shift+Tab as indenting: see Indenter
    private val indenter = Indenter(object : Indenter.Host {
        override val isEditable: Boolean get() = this@EditorView.isEditable()
        override val selection: Range get() = selectionRange
        override val hasSelection: Boolean get() = isSelected()
        override val eol: String get() = getEOL()
        override val tabSize: Int get() = textMetrics.tabSize
        override fun line(lineNumber: Int): String = getLine(lineNumber)
        override fun insert(text: String) = this@EditorView.insert(text)
        override fun applyAsOwnStep(edits: List<SingleEditOperation>) {
            // not merged with the typing before or after it: one thing to take back
            history.commitPending()
            applyEdits(edits)
            history.commitPending()
        }
        override fun select(range: Range) = setSelection(range)
    })
    
    /**
     * One level of indentation: four spaces (the default), another number of spaces,
     * or "\t" for tab characters. The Tab key (of a keyboard or of the input method)
     * types it instead of a tab; with lines selected, Tab and Shift+Tab
     * ([indentLines], [outdentLines]) add or take away one level on each. Backspace
     * takes spaces away one at a time. Tabs already in a text stay tabs. Main thread.
     */
    fun setIndentUnit(unit: String) {
        indenter.unit = unit
    }
    
    fun getIndentUnit(): String = indenter.unit
    
    /**
     * Whether Enter starts the new line with the indentation of the line it was
     * pressed on (the default). Main thread.
     */
    fun setAutoIndent(enabled: Boolean) {
        indenter.autoIndent = enabled
    }
    
    fun isAutoIndent(): Boolean = indenter.autoIndent
    
    /**
     * Adds one level of indentation ([setIndentUnit]) to the lines of the selection,
     * or to the cursor's line; one undo step. The selection stays on the text it was on.
     *
     * @return false if refused (not editable, busy)
     */
    fun indentLines(): Boolean = indenter.indent()

    /**
     * What the Tab key does (a key of the app's own, the row of extra keys): the lines of
     * a selection over several lines get one level of indentation more; otherwise one
     * indentation unit at the cursor (a tab, or spaces, see [setIndentUnit]).
     */
    fun pressTab() {
        indenter.onTab()
    }
    
    /**
     * Takes one level of indentation away from the lines of the selection, or from
     * the cursor's line, where they have one; one undo step.
     *
     * @return false if refused (not editable, busy) or if no line had any indentation
     */
    fun outdentLines(): Boolean = indenter.outdent()
    
    // #endregion

    // #region auto pairs

    // pairs typed together: see AutoPairs
    private val autoPairs = AutoPairs(object : AutoPairs.Host {
        override val isEditable: Boolean get() = this@EditorView.isEditable()
        override val selection: Range get() = selectionRange
        override val hasSelection: Boolean get() = isSelected()
        override val eol: String get() = getEOL()
        override val indentUnit: String get() = indenter.unit
        override val autoIndent: Boolean get() = indenter.autoIndent
        override fun line(lineNumber: Int): String = getLine(lineNumber)
        override fun offsetAt(lineNumber: Int, column: Int): Int = document.getOffset(lineNumber, column)
        override fun textIn(range: Range): String = document.getValueInRange(range)
        override fun replace(range: Range, text: String) = this@EditorView.insert(text, range)
        override fun select(range: Range) = setSelection(range)
    })

    /**
     * The pairs that are typed together, as nvim-autopairs does it (see [AutoPairs]):
     * `(` types `()` with the cursor in between, `)` before a `)` steps over it,
     * Backspace in `(|)` takes both, Enter in `{|}` opens an indented line. Empty for
     * none, the default; [AutoPair.DEFAULTS] for `() [] {} "" '' ``. Main thread.
     */
    fun setAutoPairs(pairs: List<AutoPair>) {
        autoPairs.pairs = pairs
    }

    fun getAutoPairs(): List<AutoPair> = autoPairs.pairs

    /**
     * Says no to a pair at some places, beyond the rules (not in a string or a
     * comment, which a parser knows); null for none. Main thread.
     */
    fun setAutoPairFilter(filter: AutoPairFilter?) {
        autoPairs.filter = filter
    }

    /** Whether the open char of a pair typed over a selection puts the pair around it (the default). */
    fun setAutoPairWrapSelection(wrap: Boolean) {
        autoPairs.wrapSelection = wrap
    }

    /**
     * Types [text] as the keyboard does: in place of the selection, or at the cursor.
     * A single char may complete a pair ([setAutoPairs]). For keys of an app's own
     * (a row of symbols); [insert] puts text in as it is.
     */
    fun typeText(text: String) {
        if (!autoPairs.onTyped(text)) insert(text)
    }

    // #endregion
    
    // #region editing
    
    // every edit of the editor ends up here (or in undo/redo below)
    protected fun applyEdits(
        operations: List<SingleEditOperation>,
        computeUndoEdits: Boolean = true
    ) {
        // Through the coordinator: it refuses while the layout is being rebuilt, and
        // keeps a relayout on another thread from starting in the middle of the edit.
        layoutCoordinator.runEdit { document.applyEdits(operations, computeUndoEdits) }
    }

    // for edits a subclass applied to the piece tree itself (applyEdits with
    // computeUndoEdits = true), bypassing the editor: puts them in the undo history
    protected fun recordUndoEdits(
        reverseEdits: List<ReverseEditOperation>?, 
        delay: Long = EditHistory.DEFAULT_MERGE_DELAY_MS
    ) {
        history.recordReverseEdits(reverseEdits, delay)
    }

    /** Whether there is anything to undo, including what was typed a moment ago. */
    fun canUndo() = history.canUndo()
    
    fun canRedo() = history.canRedo()
    
    // returns whether there is more to undo; does nothing while the layout is busy
    open fun undo(): Boolean {
        // not while read-only, and not while the layout is busy
        if (!isEditable()) return history.canUndo()
        layoutCoordinator.runEdit { document.undo() }
        return history.canUndo()
    }

    // returns whether there is more to redo; does nothing while the layout is busy
    open fun redo(): Boolean {
        if (!isEditable()) return history.canRedo()
        layoutCoordinator.runEdit { document.redo() }
        return history.canRedo()
    }
    
    // editor action
    protected enum class EditAction { INSERT, DELETE }

    // the range an edit acts on when the caller did not give one:
    // the selection if there is one, otherwise the cursor
    protected fun getDefaultEditRange(action: EditAction): Range {
        if (isSelected()) {
            // on select mode
            return selection.clone()
        }
        
        return when(action) {
            // insert at the cursor
            EditAction.INSERT -> Range.fromPositions(cursor)
            // delete the character (or line break) before the cursor
            EditAction.DELETE -> document.backspaceRange(cursor)
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
    
    fun replaceAll(text: String, matches: List<Range>) {
        if (isEditable() && matches.size > 0) {
            val operations = matches.map {
                SingleEditOperation(it, text)
            }
            applyEdits(operations, true)
        }
    }
    
    // #endregion
    
    // #region clipboard and selection commands

    /**
     * Copies the selected text to the clipboard.
     *
     * @return whether text was copied; the details go to [onCopyResult]
     */
    open fun copy(): Boolean {
        val result = editorClipboard.copy()
        onCopyResult(result)
        return result is EditorClipboard.CopyResult.Copied
    }
    
    /**
     * Copies the selected text to the clipboard and removes it. Nothing is removed
     * unless the copy worked.
     *
     * @return whether text was cut; the details go to [onCopyResult]
     */
    open fun cut(): Boolean {
        val result = editorClipboard.cut()
        onCopyResult(result)
        return result is EditorClipboard.CopyResult.Copied
    }
    
    /**
     * Inserts the plain text on the clipboard.
     *
     * @return false if the clipboard holds no plain text
     */
    open fun paste(): Boolean = editorClipboard.paste()
    
    /**
     * How a copy or a cut ended. The editor itself tells the user nothing: override
     * this to show a message for the outcomes you care about (copied, selection too
     * large for the clipboard, refused by the system).
     */
    protected open fun onCopyResult(result: EditorClipboard.CopyResult) {
        // nothing by default
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
            showSelectionMenu()
        }, 300L)
    }
    
    // #endregion
    
    // #region diagnostics, info, edits from elsewhere

    /**
     * Something a checker (a language server) says of [range] (1-based): its [severity]
     * ([SEVERITY_ERROR] to [SEVERITY_HINT]) and [message]. Drawn as a wavy line under the text.
     */
    data class Diagnostic(val range: Range, val severity: Int, val message: String)

    private var diagnostics: List<Diagnostic> = emptyList()
    private val diagnosticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * resources.displayMetrics.density
    }
    private val diagnosticPath = android.graphics.Path()

    /** What a checker says of the text now (an empty list: nothing). */
    fun setDiagnostics(list: List<Diagnostic>) {
        diagnostics = list.sortedWith(compareBy<Diagnostic> { it.range.startLine }.thenBy { it.range.startColumn })
        invalidate()
    }

    /** The diagnostics whose range holds [line]:[column] (1-based), the worst first. */
    fun diagnosticsAt(line: Int, column: Int): List<Diagnostic> = diagnostics.filter { d ->
        val r = d.range
        val afterStart = line > r.startLine || (line == r.startLine && column >= r.startColumn)
        val beforeEnd = line < r.endLine || (line == r.endLine && column <= r.endColumn)
        afterStart && beforeEnd
    }.sortedBy { it.severity }

    // the wavy lines of the diagnostics on screen (content coordinates); for each line of a
    // range: from where it starts (or the start of the line) to where it ends (or the end)
    private fun drawDiagnostics(canvas: Canvas) {
        if (diagnostics.isEmpty()) return
        val lineHeight = getLineHeight()
        val top = scrollY - lineHeight
        val bottom = scrollY + height + lineHeight
        val lineCount = getLineCount()
        val density = resources.displayMetrics.density
        for (d in diagnostics) {
            val r = d.range
            if (r.startLine > lineCount) break
            val (_, startBottom) = getLocationForPosition(r.startLine.coerceIn(1, lineCount), 1)
            if (startBottom > bottom) break
            diagnosticPaint.color = when (d.severity) {
                SEVERITY_ERROR -> 0xFFE5534B.toInt()
                SEVERITY_WARNING -> 0xFFD4A72C.toInt()
                SEVERITY_INFORMATION -> 0xFF4FA3E0.toInt()
                else -> 0xFF8B949E.toInt()
            }
            val last = Math.min(r.endLine, Math.min(lineCount, r.startLine + 200))
            for (line in r.startLine..last) {
                val from = if (line == r.startLine) r.startColumn else 1
                var to = if (line == r.endLine) r.endColumn else getLineEndColumn(line)
                // an empty range: under one char
                if (line == r.endLine && line == r.startLine && to <= from) to = Math.min(from + 1, getLineEndColumn(line))
                val (x1, b1) = getLocationForPosition(line, from.coerceAtLeast(1))
                val (x2, b2) = getLocationForPosition(line, to.coerceAtLeast(1))
                if (b2 < top || b1 > bottom) continue
                val end = if (b1 == b2) Math.max(x2, x1 + (4 * density).toInt()) else scrollX + width
                waves(canvas, x1.toFloat(), end.toFloat(), b1 - 1.5f * density, d.severity == SEVERITY_HINT)
            }
        }
    }

    // a wavy line from [x1] to [x2] at [y]; a hint gets a straight dotted one
    private fun waves(canvas: Canvas, x1: Float, x2: Float, y: Float, flat: Boolean) {
        val density = resources.displayMetrics.density
        diagnosticPath.reset()
        if (flat) {
            var x = x1
            while (x < x2) {
                diagnosticPath.moveTo(x, y)
                diagnosticPath.lineTo(Math.min(x + 2 * density, x2), y)
                x += 4 * density
            }
        } else {
            val half = 2f * density
            val amplitude = 1.5f * density
            diagnosticPath.moveTo(x1, y)
            var x = x1
            var up = true
            while (x < x2) {
                val next = Math.min(x + half, x2)
                diagnosticPath.quadTo((x + next) / 2, if (up) y - amplitude else y + amplitude, next, y)
                x = next
                up = !up
            }
        }
        canvas.drawPath(diagnosticPath, diagnosticPaint)
    }

    private var infoWindow: InfoWindow? = null

    /** Whether the info card is shown. */
    val isShowingInfo: Boolean get() = infoWindow?.isShowing == true

    /** Shows [text] in a card at the cursor (hover, a signature, diagnostics); empty takes it away. */
    fun showInfo(text: CharSequence) {
        if (text.isEmpty()) {
            dismissInfo()
            return
        }
        val host = rootView as? ViewGroup ?: return
        val anchor = cursorAnchorIn(host) ?: return
        val window = infoWindow ?: InfoWindow(context).also { infoWindow = it }
        window.show(host, anchor, text)
    }

    /** Takes the info card away. */
    fun dismissInfo() {
        infoWindow?.dismiss()
    }

    /**
     * Replaces each range of [edits] (1-based, in the text as it is, not overlapping) with
     * its text, as one step to undo (a formatting, a rename). The cursor stays with the text
     * it was in. Returns whether the edits were made.
     */
    fun applyTextEdits(edits: List<Pair<Range, String>>): Boolean {
        if (!isEditable() || edits.isEmpty()) return false
        val buffer = getTextBuffer()
        val caret = buffer.getOffsetAt(cursor.lineNumber, cursor.column)
        var shift = 0
        var inside: Int? = null
        val operations = ArrayList<SingleEditOperation>(edits.size)
        for ((range, text) in edits) {
            val s = buffer.getOffsetAt(range.startLine, range.startColumn)
            val e = buffer.getOffsetAt(range.endLine, range.endColumn)
            when {
                e <= caret -> shift += text.length - (e - s)
                s < caret -> inside = s + shift + text.length
            }
            operations += SingleEditOperation(range, text)
        }
        completionCaret = CompletionCaret(inside ?: (caret + shift), 0)
        applyEdits(operations)
        val made = completionCaret == null
        completionCaret = null
        return made
    }

    // #endregion

    // #region completion

    /**
     * What a completion does to the text: replaces [range] (1-based, in the text as it
     * is) with [text], applies [additionalEdits] (an `#include`, an `import`; in the text
     * as it is, apart from [range]), all as one step to undo, then puts the cursor at
     * [cursor] in [text] and selects [selectionLength] chars from there (a snippet's
     * placeholder).
     */
    data class CompletionEdit(
        val range: Range,
        val text: String,
        val cursor: Int = text.length,
        val selectionLength: Int = 0,
        val additionalEdits: List<Pair<Range, String>> = emptyList(),
    )

    // where the cursor goes once the edit of a completion is in (see afterTextChanged)
    private class CompletionCaret(val offset: Int, val selectionLength: Int)

    private var completionWindow: CompletionWindow? = null
    private var completionPick: ((CompletionWindow.Item) -> Unit)? = null
    private var completionCaret: CompletionCaret? = null

    /** Whether the completion list is shown. */
    val isShowingCompletions: Boolean get() = completionWindow?.isShowing == true

    /**
     * Shows [items] in a list at the cursor (or updates the one shown); [onPick] is
     * called with the one taken, after the list went. An empty list takes it away. The
     * editor does not ask for completions itself: whoever has them calls this as the
     * user types (see the app's LSP controller).
     */
    fun showCompletions(items: List<CompletionWindow.Item>, onPick: (CompletionWindow.Item) -> Unit) {
        if (items.isEmpty()) {
            dismissCompletions()
            return
        }
        val host = rootView as? ViewGroup ?: return
        val anchor = cursorAnchorIn(host) ?: run {
            dismissCompletions()
            return
        }
        val window = completionWindow ?: CompletionWindow(context).also { created ->
            created.onPick = { item ->
                val pick = completionPick
                dismissCompletions()
                pick?.invoke(item)
            }
            completionWindow = created
        }
        completionPick = onPick
        window.show(host, anchor, items)
    }

    /** Takes the completion list away. */
    fun dismissCompletions() {
        completionPick = null
        completionWindow?.dismiss()
    }

    // the keys of a hardware keyboard while the list is shown
    private fun onCompletionKey(keyCode: Int): Boolean {
        val window = completionWindow ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { window.moveSelection(1); true }
            KeyEvent.KEYCODE_DPAD_UP -> { window.moveSelection(-1); true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> window.pickSelected()
            KeyEvent.KEYCODE_ESCAPE -> { dismissCompletions(); true }
            else -> false
        }
    }

    /**
     * Applies [edit]: see CompletionEdit. Additional edits that overlap the range are left
     * out. Returns whether the edit was made (not while read-only or the layout is busy).
     */
    fun applyCompletion(edit: CompletionEdit): Boolean {
        if (!isEditable()) return false
        val buffer = getTextBuffer()
        val start = buffer.getOffsetAt(edit.range.startLine, edit.range.startColumn)
        val end = buffer.getOffsetAt(edit.range.endLine, edit.range.endColumn)
        // what moves the start of the range: the additional edits before it
        var shift = 0
        val additional = ArrayList<SingleEditOperation>()
        for ((range, text) in edit.additionalEdits) {
            val s = buffer.getOffsetAt(range.startLine, range.startColumn)
            val e = buffer.getOffsetAt(range.endLine, range.endColumn)
            if (e > start && s < end) continue
            if (e <= start) shift += text.length - (e - s)
            additional += SingleEditOperation(range, text)
        }
        completionCaret = CompletionCaret(
            start + shift + edit.cursor.coerceIn(0, edit.text.length),
            edit.selectionLength.coerceAtLeast(0)
        )
        applyEdits(listOf(SingleEditOperation(edit.range, edit.text)) + additional)
        // still there: the edit was not made (afterTextChanged takes it)
        val made = completionCaret == null
        completionCaret = null
        return made
    }

    private fun placeCompletionCaret(caret: CompletionCaret) {
        val buffer = getTextBuffer()
        val offset = caret.offset.coerceIn(0, buffer.length)
        val at = buffer.getPositionAt(offset)
        if (caret.selectionLength > 0) {
            val to = buffer.getPositionAt((offset + caret.selectionLength).coerceAtMost(buffer.length))
            cursor.setPosition(to.lineNumber, to.column)
            setSelection(Range(at.lineNumber, at.column, to.lineNumber, to.column))
        } else {
            cursor.setPosition(at.lineNumber, at.column)
        }
    }

    // The row of the cursor, in [host]; null if it is not in the view.
    private fun cursorAnchorIn(host: View): Rect? {
        val lineHeight = getLineHeight()
        val (x, bottom) = getLocationForPosition(cursor.lineNumber, cursor.column)
        val rect = Rect(x, bottom - lineHeight, x + 1, bottom)
        rect.offset(-scrollX, -scrollY)
        if (rect.left < 0 || rect.left > width || rect.bottom < 0 || rect.top > height) return null
        val self = IntArray(2).also { getLocationInWindow(it) }
        val root = IntArray(2).also { host.getLocationInWindow(it) }
        rect.offset(self[0] - root[0], self[1] - root[1])
        return rect
    }

    // #endregion

    // #region selection menu

    /**
     * What the floating bar over the selection (or the cursor) offers, and what its items
     * do: the bar is a FloatingBar, in the window of the editor; it replaces the floating
     * ActionMode, whose overflow menu grew with its items, beyond the screen.
     */
    interface SelectionMenu {
        /** The items, for [editor] as it is now (text selected or not, editable, ...). */
        fun onCreateItems(editor: EditorView): List<FloatingBar.Item>

        /** [item] was tapped. Returns whether the bar goes away. */
        fun onItemClick(editor: EditorView, item: FloatingBar.Item): Boolean
    }

    /** What the floating bar over the selection offers; null: no bar. */
    fun setSelectionMenu(menu: SelectionMenu?) {
        selectionMenu = menu
        if (menu == null) dismissSelectionMenu()
    }

    /** Shows the floating bar over the selection, or over the cursor if there is none. */
    open fun showSelectionMenu() {
        selectionMenuWanted = true
        removeCallbacks(showSelectionMenuRunnable)
        showSelectionMenuNow()
    }

    /** Takes the floating bar away. */
    open fun dismissSelectionMenu() {
        selectionMenuWanted = false
        removeCallbacks(showSelectionMenuRunnable)
        floatingBar?.hide()
    }

    private fun showSelectionMenuNow() {
        if (!selectionMenuWanted || !isAttachedToWindow) return
        val menu = selectionMenu ?: return
        val host = rootView as? ViewGroup ?: return
        // what it is about is off screen (scrolled away): no bar until it comes back
        val anchor = selectionMenuAnchorIn(host) ?: run {
            floatingBar?.hide()
            return
        }
        val bar = floatingBar ?: FloatingBar(context).also { created ->
            created.onItemClick = { item ->
                if (selectionMenu?.onItemClick(this, item) != false) dismissSelectionMenu()
            }
            floatingBar = created
        }
        val items = menu.onCreateItems(this)
        if (items.isEmpty()) {
            bar.hide()
            return
        }
        bar.setItems(items)
        bar.show(host, anchor)
    }

    // Where the selection is, in [host] (the bar goes above or below it): from the top of
    // its first row to the bottom of its last; across the view if it spans rows. The
    // cursor when nothing is selected. Null if none of it is in the view.
    private fun selectionMenuAnchorIn(host: View): Rect? {
        val lineHeight = getLineHeight()
        val rect = Rect()
        if (isSelected()) {
            val range = selectionController.range
            val (startX, startBottom) = getLocationForPosition(range.getStartPosition())
            val (endX, endBottom) = getLocationForPosition(range.getEndPosition())
            if (startBottom == endBottom) {
                rect.set(Math.min(startX, endX), startBottom - lineHeight, Math.max(startX, endX), endBottom)
            } else {
                rect.set(scrollX, startBottom - lineHeight, scrollX + width, endBottom)
            }
        } else {
            val (x, bottom) = getLocationForPosition(cursor.lineNumber, cursor.column)
            rect.set(x, bottom - lineHeight, x, bottom)
        }
        // in the view: the content scrolls under it
        rect.offset(-scrollX, -scrollY)
        val visible = Rect(0, 0, width, height)
        if (rect.width() == 0) {
            if (rect.left < 0 || rect.left > width || rect.bottom < 0 || rect.top > height) return null
            rect.top = rect.top.coerceAtLeast(0)
            rect.bottom = rect.bottom.coerceAtMost(height)
        } else if (!rect.intersect(visible)) {
            return null
        }
        val self = IntArray(2).also { getLocationInWindow(it) }
        val root = IntArray(2).also { host.getLocationInWindow(it) }
        rect.offset(self[0] - root[0], self[1] - root[1])
        return rect
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

    // #region environment
    
    /**
     * EditorView as its collaborators see it.
     *
     * Every collaborator asks for a narrow `Host`; together they make up
     * [EditorEnvironment]. The view does not implement those interfaces itself: an
     * interface member is public, and that would turn every private arrangement
     * between the view and one collaborator ("the keyboard typed this", "replace the
     * text between these offsets", "put this drawable on top") into a method anybody
     * can call, and any subclass can override by accident.
     *
     * So this object implements them instead. Where a request is simply one of the
     * view's own operations under another name ("undo was requested" is undo(), "text
     * was typed" is insert()), it says so right here. Where carrying it out takes
     * more than that, it passes the call on to a private member of the view, which
     * can then be as private as it should be. Being an inner class, it reaches them
     * all the same.
     *
     * Adding a collaborator means adding its Host to EditorEnvironment and its
     * members here.
     */
    private inner class Environment : EditorEnvironment {
        
        // --- EditorGeometry ---
        
        override val viewWidth: Int get() = getWidth()
        override val viewHeight: Int get() = getHeight()
        override val viewportWidth: Int get() = this@EditorView.viewportWidth
        override val viewportHeight: Int get() = this@EditorView.viewportHeight
        override val visibleHeight: Int get() = this@EditorView.visibleHeight
        override val paddingHorizontal: Int get() = this@EditorView.paddingHorizontal
        override val paddingVertical: Int get() = this@EditorView.paddingVertical
        override val viewScrollX: Int get() = getScrollX()
        override val viewScrollY: Int get() = getScrollY()
        override val maxScrollX: Int get() = this@EditorView.maxScrollX
        override val maxScrollY: Int get() = this@EditorView.maxScrollY
        
        override fun addOverlay(drawable: Drawable) = this@EditorView.addOverlay(drawable)
        override fun removeOverlay(drawable: Drawable) = this@EditorView.removeOverlay(drawable)
        override fun hasOverlay(drawable: Drawable): Boolean = this@EditorView.hasOverlay(drawable)
        
        // --- what View itself provides: scrolling, redrawing, the message queue ---
        // (ScrollController, ScrollbarController, SelectionController, EditHistory,
        //  LayoutCoordinator)
        
        override fun scrollTo(x: Int, y: Int) = this@EditorView.scrollTo(x, y)
        override fun scrollBy(x: Int, y: Int) = this@EditorView.scrollBy(x, y)
        override fun invalidate() = this@EditorView.invalidate()
        override fun postInvalidateOnAnimation() = this@EditorView.postInvalidateOnAnimation()
        override fun post(action: Runnable): Boolean = this@EditorView.post(action)
        override fun postDelayed(action: Runnable, delayMillis: Long): Boolean =
            this@EditorView.postDelayed(action, delayMillis)
        override fun removeCallbacks(action: Runnable): Boolean = this@EditorView.removeCallbacks(action)
        override fun performHapticFeedback(feedbackConstant: Int): Boolean =
            this@EditorView.performHapticFeedback(feedbackConstant)
        override fun requestDisallowInterceptTouchEvent(disallow: Boolean) =
            this@EditorView.requestDisallowInterceptTouchEvent(disallow)
        
        // --- TextRenderer, SelectionController ---
        
        override val textLayout: TextLayout get() = this@EditorView.textLayout
        override val nodeCacheSize: Int get() = this@EditorView.nodeCacheSize
        
        // --- GestureController ---
        
        override val contentPaddingLeft: Int get() = getPaddingLeft()
        override val contentPaddingTop: Int get() = getPaddingTop()
        
        override fun isEditable(): Boolean = this@EditorView.isEditable()
        override fun focusEditor() { requestFocus() }
        override fun showSoftKeyboard() = this@EditorView.showSoftKeyboard()
        override fun showSelectionMenu() = this@EditorView.showSelectionMenu()
        override fun dismissSelectionMenu() = this@EditorView.dismissSelectionMenu()
        override fun showMagnifier() = this@EditorView.showMagnifier()
        override fun dismissMagnifier() = this@EditorView.dismissMagnifier()
        override fun onCursorTapped() = this@EditorView.onCursorTapped()
        override fun revealSelection() = this@EditorView.revealSelection()
        override fun revealHandle(handle: SelectionController.Handle) = this@EditorView.revealHandle(handle)
        override val textInsetLeft: Int get() = this@EditorView.textInsetLeft
        override fun revealX(x: Int): Boolean = this@EditorView.revealX(x)
        override fun onGutterTapped(x: Int, y: Int): Boolean = this@EditorView.onGutterTapped(x, y)
        override fun onFoldMarkerTapped(x: Int, y: Int): Boolean = structure.onTap(x, y)
        
        // --- Gutter ---
        
        override val gutterWidth: Int get() = gutterPanelWidth
        override val lineNumberWidth: Int get() = getLineNumberWidth()
        override val digitWidth: Int get() = textMetrics.digitWidth
        override val lineNumbersVisible: Boolean get() = textMetrics.lineNumbersVisible
        override fun getLineBaseline(row: Int): Int = this@EditorView.getLineBaseline(row)
        override val cursorLineNumber: Int get() = cursor.lineNumber
        override val lineNumberColor: Int get() = decorationPainter.lineNumberColor
        override val defaultGutterBackground: Int get() = editorBackgroundColor()
        override val foldMarkerWidth: Int get() = textMetrics.foldMarkerWidth
        // for drawing the markers: may wait for a later frame (see frameBudget)
        override fun foldingRangeAt(line: Int): FoldingRange? = structure.foldingRangeAt(line, drawing = true)
        override fun isFolded(line: Int): Boolean = this@EditorView.isFolded(line)
        override fun drawFoldMarker(
            canvas: Canvas, range: FoldingRange, folded: Boolean,
            left: Float, top: Float, right: Float, bottom: Float
        ) = this@EditorView.drawFoldMarker(canvas, range, folded, left, top, right, bottom)
        override fun getLineHeight(): Int = this@EditorView.getLineHeight()
        override fun onZoomBegin() = this@EditorView.onZoomBegin()
        override fun onZoom(scaleFactor: Float, focusX: Float, focusY: Float) =
            this@EditorView.onZoom(scaleFactor, focusX, focusY)
        override fun onZoomEnd() = this@EditorView.onZoomEnd()
        
        // --- LayoutCoordinator ---
        
        override val wordwrapWidth: Int get() = this@EditorView.wordwrapWidth
        override fun onLayoutUpdated() = this@EditorView.onLayoutUpdated()
        override fun onBackgroundRelayoutStarted() = this@EditorView.onBackgroundRelayoutStarted()
        override fun onBackgroundRelayoutFinished() = this@EditorView.onBackgroundRelayoutFinished()
        
        // --- EditorInputConnection, KeyboardController ---
        
        override fun onKeyEvent(event: KeyEvent): Boolean = this@EditorView.onKeyEvent(event)
        override fun onCaretMove(move: CaretMove, extendSelection: Boolean) =
            this@EditorView.onCaretMove(move, extendSelection)
        override fun onTextTyped(text: String) = typeText(text)
        override fun onCharTyped(text: String): Boolean = autoPairs.onTyped(text)
        override fun onEnterPressed() {
            // between a bracket pair: the close bracket on a line of its own
            if (!autoPairs.onEnter()) indenter.onEnter()
        }
        override fun onTabPressed() = indenter.onTab()
        // the cursor got into folded lines: unfold them now, not at the next frame
        override fun onCursorMoved(lineNumber: Int) {
            if (structure.isLineHidden(lineNumber)) structure.revealCursor()
        }
        override fun onOutdentPressed() { outdentLines() }
        override fun onBackspacePressed() = this@EditorView.onBackspacePressed()
        override fun onForwardDeletePressed() = this@EditorView.onForwardDeletePressed()
        override fun onContextMenuAction(id: Int): Boolean = this@EditorView.onContextMenuAction(id)
        override fun onUndoRequested() { undo() }
        override fun onRedoRequested() { redo() }
        override fun onEscapePressed(): Boolean = this@EditorView.onEscapePressed()
        
        // --- InputMethodBridge ---
        
        override val selectionRange: Range get() = this@EditorView.selectionRange
        override fun applyInputEdit(range: Range, text: String) =
            applyEdits(listOf(SingleEditOperation(range, text)))
        override fun selectRange(range: Range) = setSelection(range)
        override fun onComposingRangeChanged() = this@EditorView.invalidate()
        override fun onInputEditRefused() = resetInput()
        
        // --- EditorClipboard ---
        
        override fun getSelectedText(): String = this@EditorView.getSelectedText()
        override fun onSelectionCut() = delete()
        override fun onTextPasted(text: String) = insert(text)
    }
    
    // #endregion
}

// An edit that inserts at least this many lines is laid out roughly at once and
// measured in the background (see EditorView.onTextChanged): about what can be
// measured on the main thread without a noticeable stall.
private const val LARGE_EDIT_LINES = 20_000

// posts to the main thread whether or not the view is attached, see runOnMainThread
private val MAIN_HANDLER: Handler by lazy { Handler(Looper.getMainLooper()) }

// keys of the view's saved instance state
private const val STATE_SUPER = "editor:super"
private const val STATE_TEXT_SIZE = "editor:textSize"
private const val STATE_WORDWRAP = "editor:wordwrap"
private const val STATE_CURSOR_LINE = "editor:cursorLine"
private const val STATE_CURSOR_COLUMN = "editor:cursorColumn"
private const val STATE_SCROLL_X = "editor:scrollX"
private const val STATE_SCROLL_Y = "editor:scrollY"
private const val STATE_LINE_COUNT = "editor:lineCount"
private const val STATE_LENGTH = "editor:length"
private const val STATE_FOLDS = "editor:folds"

// Layouts asked for on the main thread are done here (see EditorView.setWordwrap):
// one thread for all editors, so that they are done one after the other, in the
// order asked for. A pool could run "wrap" and "do not wrap" the wrong way round.
// A daemon thread: it does not keep the process alive.
private object EditorLayoutThread {
    val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "EditorView-layout").apply { isDaemon = true }
        }
    }
}
