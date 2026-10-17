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

package x.editor.app.view

import x.editor.app.R
import x.github.module.editor.input.EditorClipboard
import android.widget.Toast
import android.content.Context
import android.util.AttributeSet
import android.view.View

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner

import x.editor.app.highlight.TreeSitterEngine
import x.editor.app.highlight.TreeSitterHighlighter
import x.editor.app.viewmodel.EditorViewModel

import x.github.module.editor.model.EditorDocument
import x.github.module.editor.view.EditorView

/**
 * The editor of this app: an [EditorView] with tree-sitter highlighting attached and
 * its state mirrored into the [EditorViewModel].
 *
 * The highlighting itself is not in here. It lives in [TreeSitterHighlighter], which
 * is attached to the editor like to any other EditorView; this class only creates
 * the two and connects them.
 */
class CodeEditorView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : EditorView(context, attrs, defStyleAttr) {
      
    public val syntaxEngine by lazy { TreeSitterEngine(context) }
    
    // styles the lines from the syntax tree, and keeps the tree in step with the text
    private val highlighter = TreeSitterHighlighter(
        syntaxEngine = { syntaxEngine },
        textBuffer = { getTextBuffer() }
    )
    
    // The view model of the activity the view is in. The activity sets itself as
    // the ViewModelStoreOwner of the view tree before inflating (see BaseActivity).
    private val editorViewModel by lazy {
        val owner = checkNotNull(findViewTreeViewModelStoreOwner()) {
            "CodeEditorView needs a ViewModelStoreOwner in its view tree, and must be attached to it"
        }
        ViewModelProvider(owner).get(EditorViewModel::class.java)
    }
    
    init {
        // hardware accelerated
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        highlighter.attachTo(this)
        // no pair typed together in a string or a comment (the pairs come from the settings)
        setAutoPairFilter(highlighter.structure.autoPairFilter)
    }    

    // #region one editor of several (one per tab)

    /**
     * Whether this is the editor that is shown. There is an editor for each open
     * document, and they share the view model of the activity (menu, progress bar,
     * search): only the one that is shown writes to it. Made active, it writes its
     * whole state at once.
     */
    var isActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) reportState()
        }

    /** false while the text is being laid out again in the background. */
    var isLayoutReady: Boolean = true
        private set

    /**
     * Told whenever this editor's text becomes modified or unmodified (also while
     * it is not the active one): for the dot on its tab.
     */
    var onModifiedListener: ((Boolean) -> Unit)? = null

    // the whole state of this editor, for the view model
    private fun reportState() {
        editorViewModel.setCanUndo(canUndo())
        editorViewModel.setCanRedo(canRedo())
        editorViewModel.setModified(isModified())
        editorViewModel.setLayoutReady(isLayoutReady)
    }

    // #endregion

    /**
     * Turns the parts of the code structure from the syntax tree on or off: the
     * highlight of matching brackets, the indent guides and code folding (turning
     * it off unfolds everything). Main thread.
     */
    fun setBracketMatchingEnabled(enabled: Boolean) {
        val matcher = if (enabled) highlighter.structure.bracketMatcher else null
        if (getBracketMatcher() !== matcher) setBracketMatcher(matcher)
    }

    fun setIndentGuidesEnabled(enabled: Boolean) {
        val provider = if (enabled) highlighter.structure else null
        if (getIndentGuideProvider() !== provider) setIndentGuideProvider(provider)
    }

    /**
     * Code folding on or off. On: the fold markers next to the line numbers, and a tap
     * on one of them or on the ⋯ of folded lines folds or unfolds. Off: no markers,
     * and whatever was folded is unfolded.
     */
    fun setFoldingEnabled(enabled: Boolean) {
        val provider = if (enabled) highlighter.structure else null
        if (getFoldingRangeProvider() !== provider) setFoldingRangeProvider(provider)
        setFoldPlaceholderClickable(enabled)
    }

    /** Whether colors written in hex (`#D4D4D4`) are shown in their own color. */
    fun setColorPreviewEnabled(enabled: Boolean) {
        highlighter.colorPreview = enabled
    }

    // whether code blocks get a background, and in which color
    private var codeBlockBackgroundEnabled = false
    private var codeBlockColor: Int? = null

    /** Whether the lines of code blocks (Markdown's) get a background across the whole width. */
    fun setCodeBlockBackgroundEnabled(enabled: Boolean) {
        codeBlockBackgroundEnabled = enabled
        updateCodeBlockBackground()
    }

    /** The color of that background, see EditorColorScheme.codeBlockColor. */
    fun setCodeBlockColor(color: Int?) {
        codeBlockColor = color
        updateCodeBlockBackground()
    }

    private fun updateCodeBlockBackground() {
        val color = if (codeBlockBackgroundEnabled) codeBlockColor else null
        if (highlighter.codeBlockBackground == color) return
        highlighter.codeBlockBackground = color
        // backgrounds are drawn on every frame, nothing recorded has to go
        invalidate()
    }

    /**
     * Paints the text again with what [syntaxEngine] has now. Call after
     * [TreeSitterEngine.prepare] (or [TreeSitterEngine.release]): lines drawn while
     * it ran were drawn plain, and are kept as they were drawn until told otherwise.
     * Any thread.
     */
    fun refreshHighlighting() = highlighter.refresh()
    
    // The text is being laid out again in the background, after a pinch-zoom or
    // (with word wrap) after the view got another width. The editor refuses every
    // change to the text until that is done. This shows it: progress bar on.
    // Scrolling and reading stay possible.
    override fun onBackgroundRelayoutStarted() {
        super.onBackgroundRelayoutStarted()
        isLayoutReady = false
        if (isActive) editorViewModel.setLayoutReady(false)
    }
    
    // the layout is right again and the text may change again: progress bar off
    override fun onBackgroundRelayoutFinished() {
        super.onBackgroundRelayoutFinished()
        isLayoutReady = true
        if (isActive) editorViewModel.setLayoutReady(true)
    }

    override fun afterTextChanged(
        changes: List<EditorDocument.AppliedChange>,
        lastLineNumber: Int,
        lastColumn: Int
    ) {
        // keep the undo / redo buttons in step with every kind of edit, including
        // the ones that empty the history (setText)
        syncUndoRedoState()
               
        // update text and cursor state
        super.afterTextChanged(
            changes, lastLineNumber, lastColumn
        )
        // and then whoever follows the text (the language server of the document)
        onTextChangesListener?.invoke(changes)
    }

    /** Told about every edit, after the editor took it in (see LspController). */
    var onTextChangesListener: ((List<EditorDocument.AppliedChange>) -> Unit)? = null
    
    // The editor says itself whether the text differs from the saved one: undoing
    // back to the saved text makes it unmodified again, and loading a text
    // (setBuffer, setText) or markSaved does too.
    override fun onModifiedChanged(modified: Boolean) {
        super.onModifiedChanged(modified)
        onModifiedListener?.invoke(modified)
        if (isActive) editorViewModel.setModified(modified)
    }
    
    override fun onMergeStarted() {
        // to change the redo state
        if (isActive) editorViewModel.setCanRedo(false)
    }
    
    override fun onMergeCommitted() {
        // to change the undo state 
        if (isActive) editorViewModel.setCanUndo(true)
    }
    
    override fun undo(): Boolean {
        if (isEditable() && canUndo()) {
            super.undo()
            syncUndoRedoState()
        }
        return canUndo()
    }
    
    override fun redo(): Boolean {
        if (isEditable() && canRedo()) {
            super.redo()
            syncUndoRedoState()
        }
        return canRedo()
    }

    private fun syncUndoRedoState() {
        if (!isActive) return
        editorViewModel.setCanUndo(canUndo())
        editorViewModel.setCanRedo(canRedo())
    }

    // #region copy and cut: a message

    // a cut is a copy that removes the text: which of the two it was, for the message
    private var cutting = false

    override fun cut(): Boolean {
        cutting = true
        try {
            return super.cut()
        } finally {
            cutting = false
        }
    }

    // What came of a copy or a cut (the floating bar, Ctrl+C / Ctrl+X): copied, cut, too
    // much for the clipboard, refused. Nothing selected: nothing to say.
    override fun onCopyResult(result: EditorClipboard.CopyResult) {
        val message = when (result) {
            EditorClipboard.CopyResult.Copied ->
                context.getString(if (cutting) R.string.clipboard_cut else R.string.clipboard_copied)
            is EditorClipboard.CopyResult.TooLarge ->
                context.getString(R.string.clipboard_too_large, result.length, result.maxLength)
            is EditorClipboard.CopyResult.Failed ->
                context.getString(R.string.clipboard_failed, result.error.localizedMessage ?: result.error.toString())
            EditorClipboard.CopyResult.NothingSelected -> return
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // #endregion
}
