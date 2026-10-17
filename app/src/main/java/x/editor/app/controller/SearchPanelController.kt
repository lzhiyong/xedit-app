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

package x.editor.app.controller

import android.animation.TimeInterpolator
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope

import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import com.google.android.material.textfield.TextInputLayout

import java.util.regex.PatternSyntaxException

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import x.editor.app.R
import x.editor.app.view.CodeEditorView
import x.editor.app.adapter.GenericArrayAdapter
import x.editor.app.databinding.ActivityEditorBinding
import x.editor.app.search.SearchMode
import x.editor.app.search.SearchQuery
import x.editor.app.util.AppUtils
import x.editor.app.viewmodel.EditorViewModel

import com.google.android.material.R as MaterialR

/**
 * The search and replace panel of [EditorActivity]: a Material card along the bottom
 * edge (view_search_panel.xml) with
 *  - a header: the replace row toggle, the result line, the options (match case,
 *    regular expression) as toggle icon buttons, and close
 *  - the search box with its history (end icon), previous and next
 *  - the replace row, folded away until toggled: the replace box with its history,
 *    replace and replace all
 * Every button is an icon button, its content description shown as a tooltip.
 *
 * Motion, with the easing and durations of the Material theme: the card slides up
 * and fades in, its rows following one after the other, and slides down when hidden;
 * the replace row unfolds with the card growing upwards and the toggle turning; an
 * option pops when switched; a thin progress bar shows while a search takes a while.
 * Back closes the panel, following the predictive back gesture.
 *
 * A search is requested through [EditorViewModel.requestSearch] (by typing, by an
 * option, or by the text changing while the panel is open), runs off the main
 * thread when typing has paused, and a newer request cancels the one running.
 *
 * Keyboard and navigation bar insets are applied by ImeAnimationInsetsHandler. The
 * editor is never resized for the panel: while open, the panel is the editor's bottom
 * overlay (EditorView.setBottomOverlay), instead of the extra keys.
 *
 * Main thread only, except where noted.
 */
internal class SearchPanelController(
    private val activity: AppCompatActivity,
    private val binding: ActivityEditorBinding,
    private val editorViewModel: EditorViewModel,
    // the editor that is shown (there is one per open document); the panel is
    // hidden before another one is shown, so a search never spans two
    private val activeEditor: () -> CodeEditorView
) {

    private val panel get() = binding.searchPanel

    private val resultLabel: String by lazy {
        activity.getString(R.string.search_result_count)
    }

    // The options of the next search. Written on the main thread, read by the
    // search on a background thread.
    @Volatile
    private var searchMode = SearchMode.REGEX
    @Volatile
    private var ignoreCase = false

    // what was searched for and replaced with before, newest last
    private lateinit var searchPopup: ListPopupWindow
    private lateinit var searchHistory: ArrayAdapter<String>
    private lateinit var replacePopup: ListPopupWindow
    private lateinit var replaceHistory: ArrayAdapter<String>

    // the height of one row of the drop down lists
    private var historyItemHeight = 0

    /** Whether the panel is open or opening; false from the moment [hide] is called. */
    var isShowing = false
        private set

    private var isReplaceRowShown = false

    private val searchText: String
        get() = panel.tvSearchBox.text?.toString().orEmpty()

    private val replaceText: String
        get() = panel.tvReplaceBox.text?.toString().orEmpty()

    // #region motion

    private val density = activity.resources.displayMetrics.density

    // entering: emphasized decelerate; leaving: emphasized accelerate; changing in place:
    // emphasized (Material motion, with the theme's own values when it has them)
    private val enterInterpolator = interpolator(
        MaterialR.attr.motionEasingEmphasizedDecelerateInterpolator,
        PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    )
    private val exitInterpolator = interpolator(
        MaterialR.attr.motionEasingEmphasizedAccelerateInterpolator,
        PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    )
    private val changeInterpolator = interpolator(
        MaterialR.attr.motionEasingEmphasizedInterpolator,
        PathInterpolator(0.2f, 0f, 0f, 1f)
    )
    private val enterDuration = duration(MaterialR.attr.motionDurationMedium4, 400)
    private val exitDuration = duration(MaterialR.attr.motionDurationShort4, 200)
    private val changeDuration = duration(MaterialR.attr.motionDurationMedium2, 300)

    private fun interpolator(attr: Int, fallback: TimeInterpolator): TimeInterpolator =
        MotionUtils.resolveThemeInterpolator(activity, attr, fallback)

    private fun duration(attr: Int, fallback: Int): Long =
        MotionUtils.resolveThemeDuration(activity, attr, fallback).toLong()

    private fun dp(value: Float): Float = value * density

    // #endregion

    // #region setup

    /** Connects the views of the panel. Call once, when they are laid out. */
    fun setup() {
        createHistoryPopup(panel.searchInputLayout, panel.tvSearchBox).also {
            searchPopup = it.first
            searchHistory = it.second
        }
        createHistoryPopup(panel.replaceInputLayout, panel.tvReplaceBox).also {
            replacePopup = it.first
            replaceHistory = it.second
        }
        historyItemHeight = measureHistoryItemHeight()

        collectSearchRequests()

        showResultCount(0)
        updateActions()

        panel.tvSearchBox.addTextChangedListener(searchBoxWatcher)

        setUpTooltips()
        setUpHistory()
        setUpOptions()
        setUpNavigation()
        setUpReplace()

        // close button: the open drop down list first, then the panel
        panel.buttonClose.setOnClickListener {
            when {
                searchPopup.isShowing -> searchPopup.dismiss()
                replacePopup.isShowing -> replacePopup.dismiss()
                else -> hide()
            }
        }

        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    private fun collectSearchRequests() {
        // the searches themselves
        activity.lifecycleScope.launch(Dispatchers.Default) {
            @OptIn(FlowPreview::class)
            editorViewModel.searchRequests
                .debounce(SEARCH_DEBOUNCE_MS) // add a debounce to wait for user to stop typing
                .collectLatest { text ->
                    val query = SearchQuery(text, searchMode, ignoreCase)
                    if (query.isBlank) {
                        runSearch(query)
                        return@collectLatest
                    }
                    // The progress bar has a show delay: a quick search never shows it.
                    // A newer request cancels this one, and waits for its finally.
                    withContext(Dispatchers.Main) { panel.searchProgress.show() }
                    try {
                        runSearch(query)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            panel.searchProgress.hide()
                        }
                    }
                }
        }

        // the text changed while the panel is open: the matches are out of date
        activity.lifecycleScope.launch {
            @OptIn(FlowPreview::class)
            editorViewModel.textChangeEvents.debounce(SEARCH_DEBOUNCE_MS).collectLatest {
                if (isShowing) {
                    val text = searchText
                    if (text.isNotEmpty()) {
                        // re-perform query
                        editorViewModel.requestSearch(text)
                    }
                }
            }
        }
    }

    // icon buttons only: what each one does shows on a long press
    private fun setUpTooltips() {
        listOf(
            panel.buttonToggleReplace, panel.buttonMatchCase, panel.buttonRegex,
            panel.buttonClose, panel.buttonPrev, panel.buttonNext,
            panel.buttonReplace, panel.buttonReplaceAll
        ).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }
    }

    private fun setUpHistory() {
        panel.searchInputLayout.setEndIconOnClickListener {
            toggleHistory(searchPopup, searchHistory, panel.searchInputLayout)
        }
        panel.replaceInputLayout.setEndIconOnClickListener {
            toggleHistory(replacePopup, replaceHistory, panel.replaceInputLayout)
        }
    }

    private fun toggleHistory(
        popup: ListPopupWindow,
        history: ArrayAdapter<String>,
        anchor: TextInputLayout
    ) {
        if (popup.isShowing || history.count == 0) {
            popup.dismiss()
            return
        }
        // as wide as the box; more rows than fit: a fixed height, and the list scrolls
        popup.width = anchor.width
        popup.height = if (history.count >= VISIBLE_HISTORY_ITEMS) {
            historyItemHeight * VISIBLE_HISTORY_ITEMS
        } else {
            ListPopupWindow.WRAP_CONTENT
        }
        popup.show()
    }

    private fun setUpOptions() {
        // match case: on while the search is case sensitive
        with(panel.buttonMatchCase) {
            isCheckable = true
            isChecked = !ignoreCase
            addOnCheckedChangeListener { button, isChecked ->
                ignoreCase = !isChecked
                pop(button)
                // re-perform query
                editorViewModel.requestSearch(searchText)
            }
        }

        // regular expression: on for a regular expression, off for the literal text
        with(panel.buttonRegex) {
            isCheckable = true
            isChecked = searchMode == SearchMode.REGEX
            addOnCheckedChangeListener { button, isChecked ->
                searchMode = if (isChecked) SearchMode.REGEX else SearchMode.LITERAL
                pop(button)
                // re-perform query
                editorViewModel.requestSearch(searchText)
            }
        }
    }

    // a switched option shrinks for a moment and springs back
    private fun pop(button: MaterialButton) {
        button.animate().cancel()
        button.scaleX = POP_SCALE
        button.scaleY = POP_SCALE
        button.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(changeDuration)
            .setInterpolator(OvershootInterpolator(POP_TENSION))
            .start()
    }

    private fun setUpNavigation() {
        // previous button
        panel.buttonPrev.setOnClickListener {
            // the match before the current one (or before the cursor, if there is no
            // current one), wrapping around from the first to the last
            if (activeEditor().gotoPreviousMatch() != null) {
                showResultPosition()
            }
        }

        // next button
        panel.buttonNext.setOnClickListener {
            // the match after the current one (or after the cursor, if there is no
            // current one), wrapping around from the last to the first
            if (activeEditor().gotoNextMatch() != null) {
                showResultPosition()
            }
        }
    }

    private fun setUpReplace() {
        panel.buttonToggleReplace.setOnClickListener {
            setReplaceRowShown(!isReplaceRowShown, animate = true)
        }
        panel.buttonReplace.setOnClickListener { replaceCurrentMatch() }
        panel.buttonReplaceAll.setOnClickListener { replaceAllMatches() }
    }

    // #endregion

    // #region showing and hiding

    /** Opens the panel, with the selected text (if any) as what to search for. */
    fun show() {
        var queryText = activeEditor().getSelectedText()
        if (queryText.isEmpty()) {
            queryText = searchText
        }

        if (queryText.isNotEmpty()) {
            with(panel.tvSearchBox) {
                if (text?.toString() != queryText) setText(queryText)
                selectAll()
                requestFocus()
            }
        }

        if (isShowing) return
        isShowing = true
        backCallback.isEnabled = true

        // The editor keeps its size and lets the panel lie on top of its bottom: it
        // keeps the cursor and the matches above the panel, and lets the end of the
        // text scroll up above it. Resizing the editor instead (a margin) uncovers
        // the window behind it, wherever the panel and the editor do not move in step:
        // while the replace row unfolds or folds, while the keyboard slides.
        activeEditor().setBottomOverlay(panel.root)

        // the matches were forgotten when the panel was closed: look for them again
        if (queryText.isNotEmpty()) {
            editorViewModel.requestSearch(queryText)
        }
        animateIn()
    }

    /** Closes the panel and forgets the matches. Does nothing if it is closed. */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        backCallback.isEnabled = false

        searchPopup.dismiss()
        replacePopup.dismiss()
        hideKeyboard()

        // forgets the matches and the current one, and redraws
        activeEditor().clearSearchResults()
        // the extra keys are what lies on top of the editor again
        activeEditor().setBottomOverlay(binding.extraKeysPanel)

        animateOut()
    }

    // Slides the card up from below the bottom edge and fades it in; the rows follow
    // one after the other. Also turns a hide that is under way around.
    private fun animateIn() {
        val root = panel.root
        val card = panel.searchCard
        card.animate().cancel()

        if (root.isVisible) {
            // still on its way out: back up from where it is
            enterCard()
            return
        }

        // its height is known once it is laid out: start right before it is drawn
        card.alpha = 0f
        root.isVisible = true
        root.doOnPreDraw {
            if (!isShowing) return@doOnPreDraw
            card.translationY = card.height.toFloat()
            staggerRows()
            enterCard()
        }
    }

    private fun enterCard() {
        panel.searchCard.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(enterDuration)
            .setInterpolator(enterInterpolator)
            .start()
    }

    private fun staggerRows() {
        val rows = listOf(panel.searchHeader, panel.searchRow, panel.replaceRow).filter { it.isVisible }
        rows.forEachIndexed { index, row ->
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = dp(ROW_OFFSET_DP)
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(ROW_DELAY_MS + index * ROW_STAGGER_MS)
                .setDuration(enterDuration)
                .setInterpolator(enterInterpolator)
                .start()
        }
    }

    // Slides the card down below the bottom edge; once there the panel is gone.
    private fun animateOut() {
        val card = panel.searchCard
        card.animate().cancel()
        card.animate()
            .translationY(card.height.toFloat())
            .alpha(0f)
            .setDuration(exitDuration)
            .setInterpolator(exitInterpolator)
            // not run when cancelled: by a show() while it slides down
            .withEndAction { onHidden() }
            .start()
    }

    private fun onHidden() {
        panel.root.isVisible = false
        panel.root.translationY = 0f
        listOf(panel.searchHeader, panel.searchRow, panel.replaceRow).forEach { row ->
            row.animate().cancel()
            row.alpha = 1f
            row.translationY = 0f
        }
        setReplaceRowShown(false, animate = false)
        panel.searchProgress.hide()
        showResultCount(0)
        updateActions()
    }

    // the keyboard goes with the panel, if it was typing into it
    private fun hideKeyboard() {
        val focused = panel.root.findFocus() ?: return
        WindowCompat.getInsetsController(activity.window, focused)
            .hide(WindowInsetsCompat.Type.ime())
        focused.clearFocus()
    }

    // Unfolds or folds the replace row below the search row. Animated, the card grows
    // or shrinks (upwards: it is anchored to the bottom), the row fades and the toggle
    // turns to point down while the row is there.
    private fun setReplaceRowShown(shown: Boolean, animate: Boolean) {
        if (isReplaceRowShown == shown) return
        isReplaceRowShown = shown

        val rotation = if (shown) 90f else 0f
        if (animate) {
            TransitionManager.beginDelayedTransition(panel.root.parent as ViewGroup, panelTransition())
            panel.buttonToggleReplace.animate()
                .rotation(rotation)
                .setDuration(changeDuration)
                .setInterpolator(changeInterpolator)
                .start()
        } else {
            panel.buttonToggleReplace.animate().cancel()
            panel.buttonToggleReplace.rotation = rotation
        }

        if (!shown) {
            replacePopup.dismiss()
            // the focus may not stay in a box that is going away
            if (panel.tvReplaceBox.hasFocus()) panel.tvSearchBox.requestFocus()
        }
        panel.replaceRow.isVisible = shown
        if (shown && animate && panel.tvSearchBox.hasFocus()) {
            panel.tvReplaceBox.requestFocus()
        }
    }

    private fun panelTransition() = TransitionSet().apply {
        setOrdering(TransitionSet.ORDERING_TOGETHER)
        addTransition(ChangeBounds())
        addTransition(Fade(Fade.IN or Fade.OUT))
        setDuration(changeDuration)
        setInterpolator(changeInterpolator)
        // Only the panel moves: the editor keeps its size underneath (resizing it on
        // every frame would lay out its text on every frame). It hears of the new
        // size of the panel from the layout pass that starts the transition.
        excludeTarget(binding.editorContainer, true)
        excludeTarget(binding.extraKeysPanel, true)
        excludeTarget(binding.downloadPanel.root, true)
    }

    // Back closes the panel. With predictive back the card sinks with the gesture, and
    // comes back up if it is cancelled.
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            panel.searchCard.animate().cancel()
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            panel.searchCard.translationY = dp(BACK_PEEK_DP) * backEvent.progress
        }

        override fun handleOnBackCancelled() {
            panel.searchCard.animate()
                .translationY(0f)
                .setDuration(changeDuration)
                .setInterpolator(changeInterpolator)
                .start()
        }

        override fun handleOnBackPressed() {
            hide()
        }
    }

    // #endregion

    // #region searching and replacing

    private val searchBoxWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // nothing to do
        }

        override fun afterTextChanged(s: Editable) {
            editorViewModel.requestSearch(s.toString())
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            // nothing to do
        }
    }

    // Runs on a background thread. Cancelled when a newer search is requested.
    private suspend fun runSearch(query: SearchQuery) {
        val context = currentCoroutineContext()
        val isCancelled = { !context.isActive }
        // the editor searched; another one shown meanwhile does not get its matches
        val editor = activeEditor()

        var isInvalidPattern = false
        val results = try {
            when {
                query.isBlank -> null
                query.mode == SearchMode.REGEX ->
                    editor.find(query.toRegex(), isCancelled = isCancelled)
                else ->
                    editor.find(query.text, isCancelled = isCancelled)
            }
        } catch (e: PatternSyntaxException) {
            // Not a regular expression (yet): it matches nothing.
            isInvalidPattern = true
            null
        }

        // a search that was cancelled half way has only some of the matches
        context.ensureActive()

        // update the UI states
        withContext(Dispatchers.Main) {
            // closed meanwhile: the matches went with the panel
            if (!isShowing || activeEditor() !== editor) return@withContext
            if (results != null) {
                addToHistory(searchHistory, query.text)
            }
            editor.setSearchResults(results)
            if (isInvalidPattern) {
                showInvalidPattern()
            } else {
                showResultCount(results?.size ?: 0)
            }
            updateActions()
        }
    }

    private fun replaceCurrentMatch() {
        val searchResult = activeEditor().getSearchResults()
        if (searchResult.isEmpty()) return

        // the current match, if it is one of the results
        val resultIndex = activeEditor().getMatchResultIndex()
        if (resultIndex < 0) return

        val replacement = replaceText
        addToHistory(replaceHistory, replacement)

        // Replacing it changes the text it stood for, so the editor
        // takes it out of the results by itself and moves the matches
        // behind it along. Nothing to remove here.
        activeEditor().replace(replacement, searchResult[resultIndex])
        showResultCount(searchResult.size)
        updateActions()
    }

    private fun replaceAllMatches() {
        val searchResult = activeEditor().getSearchResults()
        if (searchResult.isEmpty()) return

        val replacement = replaceText
        addToHistory(replaceHistory, replacement)

        activeEditor().replaceAll(replacement, searchResult)
        // none of them is a match any more
        activeEditor().clearSearchResults()
        showResultCount(searchResult.size)
        updateActions()
    }

    // previous, next and the replace buttons need a match to act on
    private fun updateActions() {
        val hasMatches = activeEditor().getSearchResults().isNotEmpty()
        panel.buttonPrev.isEnabled = hasMatches
        panel.buttonNext.isEnabled = hasMatches
        panel.buttonReplace.isEnabled = hasMatches
        panel.buttonReplaceAll.isEnabled = hasMatches
    }

    private fun showResultCount(count: Int) {
        showResult(SearchQuery.formatCount(resultLabel, count), isError = false)
    }

    private fun showResultPosition() {
        showResult(
            SearchQuery.formatPosition(
                resultLabel,
                activeEditor().getMatchResultIndex(),
                activeEditor().getSearchResults().size
            ),
            isError = false
        )
    }

    private fun showInvalidPattern() {
        showResult(activity.getString(R.string.search_invalid_regex), isError = true)
    }

    private fun showResult(text: String, isError: Boolean) {
        with(panel.tvSearchResult) {
            this.text = text
            setTextColor(
                MaterialColors.getColor(
                    this,
                    if (isError) androidx.appcompat.R.attr.colorError
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    // #endregion

    // #region drop down lists

    // adds a text to a drop down list, unless it is empty or listed already
    private fun addToHistory(history: ArrayAdapter<String>, text: String) {
        if (text.isNotEmpty() && history.getPosition(text) < 0) {
            history.add(text)
        }
    }

    /**
     * A drop down list of texts for [editView], shown at [anchorView] (the box around
     * it; it is shown as wide as that): tapping a row puts its text into the box,
     * tapping the icon of a row removes the row.
     */
    private fun createHistoryPopup(
        anchorView: View,
        editView: EditText
    ): Pair<ListPopupWindow, ArrayAdapter<String>> {
        val closeIconTint = ColorStateList.valueOf(
            AppUtils.resolveColor(activity, android.R.attr.colorControlNormal)
        )

        val popupBackground = ResourcesCompat.getDrawable(
            activity.resources, R.drawable.popup_window_background, activity.theme
        )?.mutate()?.apply {
            setTint(
                AppUtils.resolveColor(
                    activity, MaterialR.attr.colorSurfaceContainerHigh
                )
            )
        }

        val popupWindow = ListPopupWindow(activity)
        val baseAdapter = GenericArrayAdapter<String>(
            activity,
            R.layout.simple_item_list_dropdown
        ) { position, holder ->
            with(holder.getView<TextView>(R.id.text_view)) {
                setText(getItem(position))
            }

            holder.itemView.setOnClickListener {
                with(editView) {
                    if (getText().toString() != getItem(position)) {
                        setText(getItem(position))
                        selectAll()
                        requestLayout()
                        requestFocus()
                    }
                }
                popupWindow.dismiss()
            }

            with(holder.getView<ImageView>(R.id.image_view)) {
                ImageViewCompat.setImageTintList(this, closeIconTint)
                setOnClickListener {
                    remove(getItem(position))
                    if (getCount() == 0) {
                        popupWindow.dismiss()
                    } else if (getCount() <= VISIBLE_HISTORY_ITEMS) {
                        popupWindow.setHeight(
                            ListPopupWindow.WRAP_CONTENT
                        )
                    }
                }
            }
        }

        with(popupWindow) {
            setAnchorView(anchorView)
            setModal(true)
            setAdapter(baseAdapter)
            setHeight(ListPopupWindow.WRAP_CONTENT)

            setBackgroundDrawable(popupBackground)
            setPromptPosition(ListPopupWindow.POSITION_PROMPT_ABOVE)
            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED)
        }

        return Pair(popupWindow, baseAdapter)
    }

    // The height of one row, measured with a text that is put in for a moment.
    // Call while the search list is still empty.
    private fun measureHistoryItemHeight(): Int {
        val sample = activity.getString(R.string.search_box_prompt)
        searchHistory.add(sample)
        try {
            val itemView = searchHistory.getView(0, null, FrameLayout(activity))
            itemView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return itemView.measuredHeight
        } finally {
            searchHistory.remove(sample)
        }
    }

    // #endregion

    private companion object {
        // how long typing must pause before a search starts
        const val SEARCH_DEBOUNCE_MS = 300L

        // the drop down lists show this many rows and scroll beyond
        const val VISIBLE_HISTORY_ITEMS = 7

        // the rows of the panel rise this far as they fade in, one after the other
        const val ROW_OFFSET_DP = 16f
        const val ROW_DELAY_MS = 60L
        const val ROW_STAGGER_MS = 40L

        // how far the card sinks at the end of a predictive back gesture
        const val BACK_PEEK_DP = 64f

        // a switched option starts at this size and overshoots back to its own
        const val POP_SCALE = 0.8f
        const val POP_TENSION = 3f
    }
}
