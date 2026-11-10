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

package x.editor.app.agent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.text.SpannableString
import android.text.StaticLayout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.editor.app.R
import x.editor.app.agent.AgentCodeBlocks.Segment
import x.editor.app.view.CodeEditorView
import x.editor.app.view.BusyDotsDrawable
import x.editor.app.view.MaxHeightLinearLayout
import x.editor.app.view.SelectableTextView
import x.editor.app.view.showSettled
import x.editor.app.view.MaxHeightRecyclerView
import x.editor.app.view.setBusyDots
import x.editor.app.view.setBusyDotsAtStart
import x.github.module.editor.view.EditorView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

/**
 * The AI assistant (Developer tools → AI assistant): a conversation with the agent of
 * [session] (AgentSession), which goes on behind the sheet once it is closed. The user's
 * messages in bubbles, the model's answers across the width (a little Markdown, and each
 * code block in an editor of the app that cannot be edited, highlighted: AgentCodeBlock),
 * each tool call a card: what and where, how it is going, what it changes (a diff, the
 * command) with Allow / Allow all in this chat / Deny while it waits, what it came to (a tap
 * on its head unfolds it; what read_file, list_files, search_files and run_command came to
 * in a code block too), View (CodeViewer) and Open for a file it changed or read. A code
 * block expanded, or a file viewed, is shown whole and only to look at (CodeViewer):
 * selected, copied, selected all, never changed. ⚙: the provider, the model, the key, what
 * is asked first (AgentSettings).
 *
 * @param scope where the code blocks are loaded and highlighted (the screen's)
 * @param styleEditor gives an editor the look of the editors of the sheets (font, size, colors, floating bar)
 * @param highlight highlights an editor as a file of the name given
 */
internal class AgentSheet(
    private val activity: Activity,
    private val session: AgentSession,
    private val scope: CoroutineScope,
    private val styleEditor: (EditorView) -> Unit,
    private val highlight: suspend (CodeEditorView, String) -> Unit,
    private val openFile: (File) -> Unit,
) : AgentSession.Listener {

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_agent, null) as MaxHeightLinearLayout
    private val header = view.findViewById<View>(R.id.agent_header)

    // only a drag that starts on the header moves (or closes) the sheet: the conversation
    // scrolls on its own (as the console's, ConsoleSheet)
    private val dialog = object : BottomSheetDialog(activity) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // a touch beside the field (and what goes with it: Send, 📎, the files
                // picked): the field lets go of the focus, the keyboard stays up
                if (input.hasFocus() && !isOn(inputRow, ev) && !isOn(attachmentsScroll, ev)) releaseInput()
                val drag = onHeader(ev)
                behavior.isDraggable = drag
                behavior.isHideable = drag
                touchingList = isOn(list, ev)
                if (touchingList) {
                    followId = null
                    startHold(ev)
                } else {
                    holdId = null
                }
                tapDownX = ev.rawX
                tapDownY = ev.rawY
                tapping = true
            } else if (ev.actionMasked == MotionEvent.ACTION_MOVE && tapping) {
                if (abs(ev.rawX - tapDownX) > touchSlop || abs(ev.rawY - tapDownY) > touchSlop) tapping = false
            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchingList = false
                // (the keyboard still going: held until it is where it goes, keepHeld)
                if (slidingIme == null) holdId = null
                // A tap anywhere but on the text holding a selection lets the selection go,
                // as the focus going there would have with a plain TextView (a tap on that
                // text lets it go itself: SelectableTextView). A drag keeps it (the list
                // scrolled under it).
                val held = heldText
                if (ev.actionMasked == MotionEvent.ACTION_UP && tapping && held != null && !isOn(held, ev)) {
                    held.releaseSelection()
                }
                tapping = false
            }
            return handled
        }

        // From Android 11 on, the sheet follows the keyboard as it slides (imeFollower). For
        // that the window must not be made smaller for the keyboard, nor anything above the
        // sheet padded for it, all at once: the window edge to edge, and the container and
        // the coordinator of BottomSheetDialog not fitting the system windows (they do when
        // the dialog is not edge to edge). The sheet itself still keeps clear of the
        // navigation bar (BottomSheetBehavior pads it), fitWindow of the status bar.
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val window = window ?: return
            WindowCompat.setDecorFitsSystemWindows(window, false)
            for (id in intArrayOf(MaterialR.id.container, MaterialR.id.coordinator)) {
                findViewById<View>(id)?.apply {
                    fitsSystemWindows = false
                    setPadding(0, 0, 0, 0)
                }
            }
            ViewCompat.requestApplyInsets(window.decorView)
        }
    }

    private val list = view.findViewById<RecyclerView>(R.id.agent_messages)
    // newest at the bottom, as position 0 (Adapter: the session's items the other way round)
    private val layoutManager = ChatLayoutManager().apply { reverseLayout = true }

    // a finger is on the conversation (from its going down there to its going up): the list
    // does not scroll to what a view in it asks to show meanwhile (ChatLayoutManager)
    private var touchingList = false

    // The text of the chat holding a selection (SelectableTextView: it selects without taking
    // the focus, which stays with the field at the bottom, with the keyboard), and the touch
    // going on: where it went down, whether it is still a tap (a tap elsewhere lets the
    // selection go). Back lets it go too, before it would close the sheet.
    private var heldText: SelectableTextView? = null
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapping = false
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            heldText?.releaseSelection()
        }
    }
    private val holdWatcher: (SelectableTextView, Boolean) -> Unit = { text, holding ->
        if (holding) {
            heldText = text
        } else if (heldText === text) {
            heldText = null
        }
        selectionBack.isEnabled = heldText != null
    }

    // The row the finger went down on (its id) and where its top was on the screen then,
    // less what the list has been scrolled since (by the finger, a fling): held there while
    // the finger is down (keepHeld), whatever lays the list out again meanwhile.
    private var holdId: Long? = null
    private var holdTop = 0
    private var holdScrolling = false
    private val holdKeeper = ViewTreeObserver.OnPreDrawListener {
        keepTop()
        keepHeld()
        revealPending()
        true
    }

    // The message being edited whose box is to be brought into sight (revealEditor), once
    // the list is laid out as it will be shown: done before the frame is drawn, after the
    // layout that put the box there and with the keyboard where it goes.
    private var revealId: Long? = null
    private var revealCenter = false

    // The message just opened for editing, kept in the middle of the list as the list changes
    // height (the keyboard coming up under it, sliding or at once, pushing the sheet up): at
    // every height the list is laid out at, the box is put in the middle again. Until a
    // finger goes down on the list (it is the user's to scroll then) or the editing ends.
    private var followId: Long? = null
    private var followHeight = -1
    private val inputRow = view.findViewById<ViewGroup>(R.id.agent_input_row)
    private val empty = view.findViewById<TextView>(R.id.agent_empty)
    private val input = view.findViewById<TextInputEditText>(R.id.agent_input)
    private val send = view.findViewById<MaterialButton>(R.id.agent_send)
    private val inputLayout = view.findViewById<TextInputLayout>(R.id.agent_input_layout)
    private val attachmentsScroll = view.findViewById<View>(R.id.agent_attachments_scroll)
    private val attachmentsRow = view.findViewById<LinearLayout>(R.id.agent_attachments)

    // A file picked to go with what is being written: made into an attachment off the main
    // thread (job), [attachment] once it is ready.
    private class Pending(val job: Job? = null) {
        var attachment: AgentAttachment? = null
    }

    // the files picked for what is being written, in the order picked
    private val pending = ArrayList<Pending>()

    // the system's picker of files, while the sheet is shown
    private var picker: ActivityResultLauncher<Array<String>>? = null

    // the thumbnails of the images attached, by path (decoded off the main thread)
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val modelText = view.findViewById<TextView>(R.id.agent_model)
    private val modelRow = view.findViewById<View>(R.id.agent_model_row)
    private val modelDropdown = view.findViewById<View>(R.id.agent_model_dropdown)

    // the drop-down of the models, while it is open
    private var modelPicker: ModelPicker? = null

    // the sheet going down to what is left after a new chat (animateHeightFrom), while it goes
    private var heightAnimator: ValueAnimator? = null
    private val workspaceText = view.findViewById<TextView>(R.id.agent_workspace)
    private val adapter = Adapter()

    private val mono: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }
    private val onSurface = MaterialColors.getColor(activity, MaterialR.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val onSurfaceVariant = MaterialColors.getColor(activity, MaterialR.attr.colorOnSurfaceVariant, 0xFF8B949E.toInt())
    // the status of a turn (what the agent is doing, how long it took): quieter than the
    // text around it, as a line of what went on beside the answer
    private val statusColor = ColorUtils.setAlphaComponent(onSurfaceVariant, (Color.alpha(onSurfaceVariant) * STATUS_ALPHA).toInt())
    private val primary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF4FA3E0.toInt())
    private val errorColor = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorError, 0xFFE5534B.toInt())
    private val codeBackground = MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainerHighest, 0x22888888)
    private val bubble = MaterialColors.getColor(activity, MaterialR.attr.colorSecondaryContainer, 0x33888888)
    private val onBubble = MaterialColors.getColor(activity, MaterialR.attr.colorOnSecondaryContainer, onSurfaceVariant)
    private val added = 0xFF2DA44E.toInt()

    // every code block of the conversation that is shown (AgentCodeBlock): released with the sheet
    private val codeBlocks = HashSet<AgentCodeBlock>()

    // A question being asked (sendInput, saveEdit, resend): what it puts at the end of the
    // conversation is scrolled to, wherever the list was (onItemInserted).
    private var asking = false

    // The sheet at its full height (fitWindow): with a conversation in it, whatever its
    // length; only an empty one (a new chat) at its least. Set by what comes and goes in the
    // conversation (setTall), not by a resend that takes it all away to ask again at once.
    private var tall = false

    // A view of the list (a row, or a code block in one) whose top is to stay where it was
    // ([keepTopAt], in the list) once the list is laid out again with what it changed to
    // (changeKeepingTop). Done before the frame is drawn: keepTop.
    private var keepTopView: View? = null
    private var keepTopAt = 0
    // the list laid out since keepTopView was set (a view in it may already ask for the next
    // layout: the top is put back all the same, before this frame is drawn)
    private var keepTopLaidOut = false

    // the end of the conversation asked to be shown (scrollToBottom), until the list is laid
    // out: not laid out from its top row meanwhile (pinTopRow), it would stay there
    private var bottomPending = false

    // the layout going on is laid out from the top row (pinTopRow): no row slides
    // (ChatItemAnimator), those under one that grew only go down by as much
    private var pinned = false

    // The message of the user being edited where it is (its id; one at a time), what its box
    // says now (kept here, not in the box: the row may be bound again, or recycled when
    // scrolled away and back), and whether the box is still to take the focus and bring the
    // keyboard once it is bound.
    private var editingId: Long? = null

    // the messages of the user unfolded past their first BUBBLE_MAX_LINES lines (Show all), by id
    private val expandedMessages = HashSet<Long>()

    // the times under the messages of the user (10 minutes ago) said again every so often
    // while the sheet is shown
    private val timeTicker = object : Runnable {
        override fun run() {
            for (i in 0 until list.childCount) {
                (list.getChildViewHolder(list.getChildAt(i)) as? UserHolder)?.updateTime()
            }
            list.postDelayed(this, TIME_TICK_MS)
        }
    }
    private var editDraft = ""
    private var editFocusPending = false

    // the files that go with what the box says, out of those of the message being edited
    // (✕ takes one off: the message keeps its own)
    private var editAttachments: List<AgentAttachment> = emptyList()

    // the bottom padding of the sheet's content in the layout, before the keyboard adds to it
    private val basePaddingBottom = view.paddingBottom

    // any layout of the window: the keyboard came or went, the window changed size
    private val onWindowLayout = ViewTreeObserver.OnGlobalLayoutListener { fitWindow() }

    // the height of the keyboard fitWindow went by last, and while the keyboard slides, how
    // high it is in this frame (null: it is not sliding)
    private var lastIme = 0
    private var slidingIme: Int? = null

    // The keyboard sliding in or out, frame by frame: the sheet fitted to where it is in each
    // frame, so the field rides on its top edge (as the activity's extra keys do,
    // ImeAnimationInsetsHandler). The insets of the end, which come before the slide starts,
    // do not make it jump there meanwhile: fitWindow keeps to slidingIme.
    private val imeFollower = object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onPrepare(animation: WindowInsetsAnimationCompat) {
            if (animation.isIme) slidingIme = lastIme
        }

        override fun onProgress(
            insets: WindowInsetsCompat,
            runningAnimations: MutableList<WindowInsetsAnimationCompat>,
        ): WindowInsetsCompat {
            if (slidingIme != null && runningAnimations.any { it.isIme }) {
                slidingIme = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                fitWindow()
            }
            return insets
        }

        override fun onEnd(animation: WindowInsetsAnimationCompat) {
            if (!animation.isIme) return
            slidingIme = null
            fitWindow()
            // typing into a message edited where it is: that message in sight above the
            // keyboard, not the end of the chat
            // (the end of the chat, if it was shown, still is: the list keeps its bottom)
            if (editingId != null && editorFocused()) revealEditor(center = followId == editingId)
        }

        private val WindowInsetsAnimationCompat.isIme
            get() = typeMask and WindowInsetsCompat.Type.ime() != 0
    }

    fun show() {
        list.layoutManager = layoutManager
        adapter.setHasStableIds(true)
        list.adapter = adapter
        // a streaming answer is bound again and again: no fade each time
        list.itemAnimator = ChatItemAnimator().apply { supportsChangeAnimations = false }
        list.addItemDecoration(StatusFade())
        // Rows scrolled off the screen kept as they are (not bound to other messages) for a
        // while: a selection held in one comes back with it (SelectableTextView), as do the
        // state of its code blocks and how far its tool output was scrolled.
        list.setItemViewCacheSize(ROW_CACHE)
        list.isNestedScrollingEnabled = false
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            // scrolled (dragged, flung, by the field of a message being edited): the row held
            // goes with it; not when keepHeld scrolls it back
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (holdId != null && !holdScrolling) holdTop -= dy
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // the floating bar of a code block's selection is a window of its own that
                // does not move with the list: it goes (a tap on the selection brings it back)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) for (block in codeBlocks) block.dismissMenu()
            }
        })

        send.setOnClickListener {
            if (session.isBusy) session.stop() else sendInput()
        }
        // 📎: the system's picker, any number of files of any kind (the screen's registry: a
        // dialog has none of its own; let go of when the sheet is)
        picker = (activity as? ComponentActivity)?.activityResultRegistry?.register(
            "agent_attach_" + System.identityHashCode(this),
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris -> addAttachments(uris) }
        // (📎: the field's trailing icon)
        inputLayout.setEndIconOnClickListener {
            if (pending.size >= AgentAttachments.MAX_PER_MESSAGE) {
                Toast.makeText(activity, activity.getString(R.string.agent_attachments_max, AgentAttachments.MAX_PER_MESSAGE), Toast.LENGTH_SHORT).show()
            } else {
                runCatching { picker?.launch(arrayOf("*/*")) }
            }
        }
        // nothing typed (or only spaces): nothing to send, the button greyed out
        input.doAfterTextChanged { updateSendEnabled() }
        view.findViewById<View>(R.id.agent_new_chat).setOnClickListener {
            if (session.isBusy) {
                session.stop()
            } else {
                // what was said is not lost: it is kept in the history first
                session.keep()
                session.clear()
            }
        }
        // the chats kept (AgentHistory): one of them taken up again where it stopped
        view.findViewById<View>(R.id.agent_history).setOnClickListener { showHistory() }
        view.findViewById<View>(R.id.agent_settings).setOnClickListener { showSettings() }
        // ▾ by the model in the head (only it, not the name): another model (set up first if nothing is)
        modelDropdown.setOnClickListener { showModelPicker() }

        updateHeader()
        onBusyChanged(session.isBusy)
        updateEmpty()
        session.listener = this

        // at its full height with a conversation in it, at its least without (fitWindow: set
        // here too, so the sheet does not open at another height and then jump to it)
        tall = session.items.isNotEmpty()
        val screen = activity.resources.displayMetrics.heightPixels
        view.maxHeight = (screen * SHEET_HEIGHT).toInt()
        view.minimumHeight = if (tall) view.maxHeight else (screen * SHEET_MIN_HEIGHT).toInt()
        dialog.setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.isDraggable = false
        dialog.behavior.isHideable = false
        // (deprecated from Android 11 on, for windows that fit the system windows themselves;
        // still what makes the keyboard's insets, and their animation, come to a window that
        // is edge to edge: imeFollower)
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // the keyboard: edge to edge (forced from Android 15 on) the window is not made smaller
        // for it, it only says how high it is (the insets); the sheet fits itself above it
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.post { fitWindow() }
            insets
        }
        // (before Android 11 the window is made smaller for the keyboard, at once; and there
        // a callback would take the place of the listener above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewCompat.setWindowInsetsAnimationCallback(view, imeFollower)
        }
        inputRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> centerSend() }
        dialog.onBackPressedDispatcher.addCallback(selectionBack)
        list.postDelayed(timeTicker, TIME_TICK_MS)
        dialog.setOnDismissListener {
            list.removeCallbacks(timeTicker)
            heldText?.releaseSelection()
            // the chat shown is kept: the sheet is letting go of it
            session.keep()
            modelPicker?.dismiss()
            heightAnimator?.cancel()
            if (session.listener === this) session.listener = null
            dialog.window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(onWindowLayout)
            dialog.window?.decorView?.viewTreeObserver?.removeOnPreDrawListener(holdKeeper)
            stopAllDots()
            releaseCodeBlocks()
            picker?.unregister()
            picker = null
            // what was picked and not sent goes with what was written
            dropPending()
        }
        // The sheet takes the focus as it opens, and a focus let go of (a box of a message
        // edited going away, a touch beside the field: releaseInput), not the field at the
        // bottom, the first view that would: the field only once it is tapped.
        takesFocus()
        view.requestFocus()
        // (the first frame only once the sheet is at the height fitWindow gives it)
        dialog.showSettled()
        dialog.window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(onWindowLayout)
        dialog.window?.decorView?.viewTreeObserver?.addOnPreDrawListener(holdKeeper)
        scrollToBottom()
    }

    /**
     * The most the sheet may be high, from where it really is on screen (so it does not
     * matter whether the dialog's window was made smaller for the keyboard, whether its
     * container is padded for the system bars or the sheet itself is): [SHEET_HEIGHT] of the
     * window, no higher than the status bar, and with the keyboard up the field above the
     * keyboard; the least [SHEET_MIN_HEIGHT] of the window (no more than the most). The
     * bottom padding lifts the content over the keyboard while the sheet's background goes
     * on down behind it. With a conversation in it ([tall]) the sheet is at the most however
     * short the conversation is, the list of it as high as what the header and the field
     * leave: a list of a height of its own, which lays itself out from its bottom the same
     * way at every layout (a list as high as what was in it, measured more than once at
     * other heights, did not come back to where it was, and went up by itself). Empty, at
     * the least (the empty text in it).
     * Called on every layout of the window, and on every frame while the keyboard slides
     * (imeFollower): it changes nothing once it fits.
     */
    private fun fitWindow() {
        val decor = dialog.window?.decorView ?: return
        val sheet = view.parent as? View ?: return
        val container = sheet.parent as? View ?: return
        if (decor.height == 0 || container.height == 0) return

        val at = IntArray(2)
        decor.getLocationOnScreen(at)
        val windowTop = at[1]
        val windowBottom = windowTop + decor.height
        container.getLocationOnScreen(at)
        val containerTop = at[1]
        val containerBottom = containerTop + container.height

        val insets = ViewCompat.getRootWindowInsets(decor)
        val barsTop = windowTop + (insets?.getInsets(TOP_BARS)?.top ?: 0)
        // before Android 11 the keyboard is not told apart from the navigation bar this way:
        // there the window is made smaller for it (adjustResize), the container with it.
        // While it slides: where it is in this frame (imeFollower)
        val own = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val ime = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 0 else slidingIme ?: own
        lastIme = ime
        val imeTop = if (ime > 0) windowBottom - ime else Int.MAX_VALUE
        // the navigation bar: the sheet's padding normally keeps the content above it already
        val navTop = windowBottom - (insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0)

        // where the content ends with the sheet down at the bottom (the sheet's own padding,
        // for the navigation bar, below it), and how much of that the keyboard (or the
        // navigation bar) covers
        val contentBottom = containerBottom - sheet.paddingBottom
        val lift = max(0, contentBottom - min(imeTop, navTop))
        // from the status bar (or the top of the container) to the bottom of the content
        val room = contentBottom - max(containerTop, barsTop)
        val shown = min((decor.height * SHEET_HEIGHT).toInt(), room - lift).coerceAtLeast(0)
        val most = shown + lift
        // with a conversation, the most; empty, SHEET_MIN_HEIGHT of the window (no more than
        // the most): the conversation takes the rest (sheet_agent)
        val least = if (tall) most else min((decor.height * SHEET_MIN_HEIGHT).toInt(), shown) + lift

        if (view.paddingBottom != basePaddingBottom + lift) view.updatePadding(bottom = basePaddingBottom + lift)
        // (setMinimumHeight lays out again even when it is the same: only when it changes)
        if (view.minimumHeight != least) view.minimumHeight = least
        // (the list keeps its bottom where it is as it changes height: laid out from there)
        if (view.maxHeight != most) view.maxHeight = most
    }

    // the send button centred on the field's box: the box starts below the top of the field,
    // whose label sits on its outline (in the row, the button is centred on the whole field).
    // Where the field is in the row from the tops of it and its parents only, not from
    // offsetDescendantRectToMyCoords: that takes off the field's own scroll too, so with more
    // lines than it shows (the field scrolled) the button went up by as much, out of the row,
    // and was cut off on the next layout (typing, the keyboard coming or going). And never
    // out of the row whatever happens: kept between its top and bottom.
    private fun centerSend() {
        var top = input.top
        var parent = input.parent
        while (parent !== inputRow) {
            val v = parent as? View ?: return
            top += v.top
            parent = v.parent
        }
        val dy = (top + input.height / 2 - (send.top + send.height / 2))
            .coerceIn(-send.top, max(-send.top, inputRow.height - send.bottom))
            .toFloat()
        if (send.translationY != dy) send.translationY = dy
    }

    private fun onHeader(ev: MotionEvent): Boolean = isOn(header, ev)

    // The field at the bottom (or the box of a message being edited) without the focus (its
    // cursor gone), the keyboard where it is: the focus goes to the sheet itself, which is
    // no text field (the keyboard is not taken down for a focus moving within the window;
    // hiding it is hideKeyboard's). A tap on the field takes the focus back, the keyboard up
    // already. The sheet has the focus as it opens too, and a focus let go of anywhere (show).
    private fun releaseInput() {
        takesFocus()
        view.requestFocus()
    }

    // the sheet able to take the focus itself (releaseInput), without a highlight
    private fun takesFocus() {
        if (view.isFocusableInTouchMode) return
        view.defaultFocusHighlightEnabled = false
        view.isFocusable = true
        view.isFocusableInTouchMode = true
    }

    // whether [ev] is on [target] (shown)
    private fun isOn(target: View, ev: MotionEvent): Boolean {
        if (!target.isShown) return false
        val at = IntArray(2)
        target.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + target.width && ev.rawY >= at[1] && ev.rawY < at[1] + target.height
    }

    // What is written sent, with the files picked for it (not while one is still being made
    // ready: Send is greyed out then)
    private fun sendInput() {
        val text = input.text?.toString().orEmpty()
        if (pending.any { it.attachment == null }) return
        val attachments = pending.mapNotNull { it.attachment }
        if (text.isBlank() && attachments.isEmpty()) return
        ask { session.send(text, attachments) }
        // sent (not refused for want of settings): the field is cleared, the files are the
        // message's now
        if (session.isBusy) {
            input.setText("")
            pending.clear()
            showPending()
        }
    }

    // #region attachments

    // The files [uris] picked (📎) made into attachments, each off the main thread, shown
    // as they are being made ready; no more than AgentAttachments.MAX_PER_MESSAGE.
    private fun addAttachments(uris: List<Uri>) {
        for (uri in uris) {
            if (pending.size >= AgentAttachments.MAX_PER_MESSAGE) {
                Toast.makeText(activity, activity.getString(R.string.agent_attachments_max, AgentAttachments.MAX_PER_MESSAGE), Toast.LENGTH_SHORT).show()
                break
            }
            val holder = arrayOfNulls<Pending>(1)
            val job = scope.launch {
                val made = try {
                    withContext(Dispatchers.IO) { AgentAttachments.prepare(activity, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    holder[0]?.let { pending.remove(it) }
                    showPending()
                    Toast.makeText(activity, e.message ?: e.toString(), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val entry = holder[0]
                if (entry == null || entry !in pending) {
                    // taken off while it was being made ready
                    withContext(Dispatchers.IO) { AgentAttachments.delete(activity, made) }
                    return@launch
                }
                entry.attachment = made
                showPending()
            }
            val entry = Pending(job)
            holder[0] = entry
            pending += entry
        }
        showPending()
    }

    // The files picked shown above the field (a spinner for one still being made ready, ✕ to
    // take one off), and Send as it now can be.
    private fun showPending() {
        attachmentsRow.removeAllViews()
        for (entry in pending) {
            val chip = LayoutInflater.from(activity).inflate(R.layout.item_agent_attachment, attachmentsRow, false)
            bindAttachment(chip, entry.attachment, onRemove = { removePending(entry) })
            attachmentsRow.addView(chip)
        }
        attachmentsScroll.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        updateSendEnabled()
    }

    private fun removePending(entry: Pending) {
        if (!pending.remove(entry)) return
        entry.job?.cancel()
        entry.attachment?.let { made -> scope.launch(Dispatchers.IO) { AgentAttachments.delete(activity, made) } }
        showPending()
    }

    // every file picked and not sent gone (the sheet closed)
    private fun dropPending() {
        val dropped = pending.toList()
        pending.clear()
        for (entry in dropped) {
            entry.job?.cancel()
            entry.attachment?.let { made -> scope.launch(Dispatchers.IO) { AgentAttachments.delete(activity, made) } }
        }
    }

    /**
     * [chip] (item_agent_attachment) showing [attachment] in its card: an image as its
     * thumbnail over all of it; anything else (and an image until its thumbnail is read, or
     * if it cannot be) its name, how many lines (a text; how large, anything else) and its
     * type in a label; null: a file still being made ready (a spinner). A tap on the card
     * opens it; ✕ on the card's top right corner only with [onRemove], the card set in from
     * the top and the end for it (half of ✕ out of the card, as nothing else of the row is
     * under it), and the name kept clear of it.
     */
    private fun bindAttachment(chip: View, attachment: AgentAttachment?, onRemove: (() -> Unit)?) {
        val card = chip.findViewById<View>(R.id.attachment_card)
        val thumb = chip.findViewById<ImageView>(R.id.attachment_thumb)
        val progress = chip.findViewById<View>(R.id.attachment_progress)
        val texts = chip.findViewById<View>(R.id.attachment_texts)
        val name = chip.findViewById<TextView>(R.id.attachment_name)
        val detail = chip.findViewById<TextView>(R.id.attachment_detail)
        val type = chip.findViewById<TextView>(R.id.attachment_type)
        val remove = chip.findViewById<View>(R.id.attachment_remove)
        // (the thumbnail clipped to the card's rounded outline)
        card.clipToOutline = true
        val removable = onRemove != null
        remove.visibility = if (removable) View.VISIBLE else View.GONE
        remove.setOnClickListener { onRemove?.invoke() }
        val density = activity.resources.displayMetrics.density
        val inset = if (removable) (REMOVE_INSET_DP * density).toInt() else 0
        (card.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (lp.topMargin != inset || lp.marginEnd != inset) {
                lp.topMargin = inset
                lp.marginEnd = inset
                card.layoutParams = lp
            }
        }
        // (the gap to the next one is the card's inset then)
        if (removable) (chip.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
        texts.updatePaddingRelative(end = ((if (removable) NAME_CLEAR_DP else NAME_END_DP) * density).toInt())
        thumb.tag = attachment?.path
        if (attachment == null) {
            progress.visibility = View.VISIBLE
            thumb.visibility = View.GONE
            texts.visibility = View.INVISIBLE
            card.setOnClickListener(null)
            card.isClickable = false
            card.contentDescription = null
            return
        }
        progress.visibility = View.GONE
        texts.visibility = View.VISIBLE
        card.setOnClickListener { openAttachment(attachment) }
        name.text = attachment.name
        detail.text = attachmentDetail(attachment)
        type.text = attachmentType(attachment)
        card.contentDescription = activity.getString(R.string.agent_attachment_description, attachment.name, type.text, detail.text)
        if (attachment.kind != AgentAttachment.Kind.IMAGE) {
            thumb.visibility = View.GONE
            thumb.setImageDrawable(null)
            return
        }
        // the thumbnail, read off the main thread (the card shows the name meanwhile)
        val cached = thumbnails.get(attachment.path)
        if (cached != null) {
            thumb.setImageBitmap(cached)
            thumb.visibility = View.VISIBLE
            return
        }
        thumb.visibility = View.GONE
        val px = activity.resources.getDimensionPixelSize(R.dimen.agent_attachment_card)
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { AgentAttachments.thumbnail(attachment.path, px) } ?: return@launch
            thumbnails.put(attachment.path, bitmap)
            if (thumb.tag != attachment.path) return@launch
            thumb.setImageBitmap(bitmap)
            thumb.visibility = View.VISIBLE
        }
    }

    // under the name of [attachment]: how many lines (a text, as counted when it was
    // attached), otherwise how large (that it is an archive, its label says: ZIP)
    private fun attachmentDetail(attachment: AgentAttachment): String {
        val size = AgentAttachments.sizeText(attachment.size)
        return when {
            attachment.kind == AgentAttachment.Kind.TEXT && attachment.lines >= 0 ->
                activity.resources.getQuantityString(R.plurals.agent_attachment_lines, attachment.lines, attachment.lines)
            else -> size
        }
    }

    // the label of [attachment]'s type: its extension in capitals (PY, ZIP, GZ, PNG); with
    // none (or none that reads as one), what kind of file it is
    private fun attachmentType(attachment: AgentAttachment): String {
        val extension = attachment.name.substringAfterLast('.', "")
        if (attachment.name.lastIndexOf('.') > 0 && extension.length in 1..MAX_TYPE_CHARS && extension.all { it.isLetterOrDigit() }) {
            return extension.uppercase(Locale.ROOT)
        }
        return activity.getString(
            when (attachment.kind) {
                AgentAttachment.Kind.IMAGE -> R.string.agent_attachment_image
                AgentAttachment.Kind.TEXT -> R.string.agent_attachment_type_text
                AgentAttachment.Kind.ARCHIVE -> R.string.agent_attachment_type_archive
                AgentAttachment.Kind.FILE -> R.string.agent_attachment_type_file
            }
        )
    }

    // [attachment] opened by an app that shows its kind (through the app's FileProvider)
    private fun openAttachment(attachment: AgentAttachment) {
        val file = File(attachment.path)
        if (!file.isFile) {
            Toast.makeText(activity, R.string.agent_attachment_missing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, attachment.mediaType.ifEmpty { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(intent, attachment.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.agent_attachment_no_app, Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(activity, R.string.agent_attachment_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // #endregion

    // The provider, model and level of thinking (unless the model's own) in the head, and
    // the workspace under them. The settings are read without the API key
    // (AgentSettings.summary): reading it is work for the Android Keystore, in another
    // process, and this runs on a tap (a model picked) and at both ends of every turn. Each
    // line is set only when it says something else: setText lays the whole sheet out again
    // whether the text changed or not. The parts of the model's line are joined by dots with
    // as much room on either side of each (DotSeparator).
    private fun updateHeader() {
        val config = AgentSettings.summary(activity)
        if (config.isUsable) {
            // (the service it is set up at: DeepSeek's Anthropic-compatible API is not Claude)
            val provider = ProviderNames.of(activity, config.kind, config.baseUrl)
            val thinking = config.thinking.takeIf { it != ThinkingLevel.DEFAULT }
                ?.let { activity.getString(R.string.agent_thinking_level_shown, thinkingLabel(it)) }
            val line = DotSeparator.join(listOfNotNull(provider, config.model, thinking))
            if (modelText.text?.toString() != line.plain) modelText.text = line.text
        } else {
            val model = activity.getString(R.string.agent_not_set_up)
            if (modelText.text?.toString() != model) modelText.text = model
        }
        val workspace = session.workspaceRoot?.path?.let { activity.getString(R.string.agent_workspace, it) }
            ?: activity.getString(R.string.agent_workspace_unknown)
        if (workspaceText.text?.toString() != workspace) workspaceText.text = workspace
    }

    // what a level of thinking is called (the settings, the head)
    private fun thinkingLabel(level: ThinkingLevel): String = activity.getString(
        when (level) {
            ThinkingLevel.DEFAULT -> R.string.agent_thinking_level_default
            ThinkingLevel.OFF -> R.string.agent_thinking_level_off
            ThinkingLevel.MINIMAL -> R.string.agent_thinking_level_minimal
            ThinkingLevel.LOW -> R.string.agent_thinking_level_low
            ThinkingLevel.MEDIUM -> R.string.agent_thinking_level_medium
            ThinkingLevel.HIGH -> R.string.agent_thinking_level_high
            ThinkingLevel.XHIGH -> R.string.agent_thinking_level_xhigh
            ThinkingLevel.MAX -> R.string.agent_thinking_level_max
        }
    )

    private fun updateEmpty() {
        empty.visibility = if (session.items.isEmpty()) View.VISIBLE else View.GONE
    }

    // The end of the conversation (its newest row, at the bottom: position 0) shown, the
    // bottom of a row higher than the list at the bottom of it (a long answer: its end).
    private fun scrollToBottom() {
        if (adapter.itemCount == 0) return
        bottomPending = true
        keepTopView = null
        list.scrollToPosition(0)
    }

    // [question] asked: what it puts at the end of the conversation is scrolled to (asking)
    private inline fun ask(question: () -> Unit) {
        asking = true
        try {
            question()
        } finally {
            asking = false
        }
    }

    // Whether the end of the conversation is shown (nothing more to scroll to below), as
    // the list was laid out last.
    private fun atBottom(): Boolean = !list.canScrollVertically(1)

    // The sheet at its full height ([value]: a conversation in it) or at its least (a new
    // chat), from the height it is at now over RESET_ANIMATION_MS (animateHeightFrom).
    private fun setTall(value: Boolean) {
        if (tall == value) return
        // the height the sheet is at now (in the middle of going somewhere already: where it is)
        val from = when {
            !view.isLaidOut -> -1
            heightAnimator != null -> view.pinnedHeight
            else -> view.height
        }
        heightAnimator?.cancel()
        tall = value
        fitWindow()
        if (from > 0) animateHeightFrom(from)
    }

    // #region AgentSession.Listener

    // The session's items are the list's the other way round (the newest at position 0, at
    // the bottom: ChatLayoutManager). Told after each change: [index] in the items as they
    // are now (of one taken away: where it was, the items one fewer).
    private fun positionOf(index: Int) = session.items.size - 1 - index

    // What is added at the end (a question, the status of its turn) scrolled to if the end
    // was shown, or if it is what was just asked. What is added before the status of a turn
    // going on (what the turn says and does) needs nothing: the list keeps its bottom, the
    // rows above it pushed up; scrolled up through the conversation, it stays where it is.
    // A row that grows (an answer streaming in) is the same.
    override fun onItemInserted(index: Int) {
        val position = positionOf(index)
        val shown = atBottom()
        adapter.notifyItemInserted(position)
        updateEmpty()
        setTall(true)
        if (position == 0 && (asking || (shown && holdId == null))) scrollToBottom()
    }

    override fun onItemChanged(index: Int) {
        adapter.notifyItemChanged(positionOf(index))
    }

    override fun onItemRemoved(index: Int) {
        adapter.notifyItemRemoved(session.items.size - index)
        updateEmpty()
    }

    @Suppress("NotifyDataSetChanged")
    override fun onReset() {
        // a new chat, or one of the history: nothing of the old one is being edited (the
        // focus of its box to the sheet, not to the field at the bottom)
        if (editorFocused()) releaseInput()
        editingId = null
        editDraft = ""
        editAttachments = emptyList()
        editFocusPending = false
        revealId = null
        followId = null
        expandedMessages.clear()
        holdId = null
        keepTopView = null
        adapter.notifyDataSetChanged()
        updateEmpty()
        updateHeader()
        // a chat of the history taken up again: at the full height, shown at its end, as it
        // was left; a new chat: down to the least
        setTall(session.items.isNotEmpty())
        scrollToBottom()
    }

    // The sheet from [from] to the height it now is to be at (its full height, or its least
    // with the empty text in it: setTall) over RESET_ANIMATION_MS rather than at once, the
    // empty text coming in as it goes down. Held at each height of the way
    // (MaxHeightLinearLayout.pinnedHeight), the bottom sheet keeping it at the foot of the
    // screen; let go at the end.
    private fun animateHeightFrom(from: Int) {
        view.pinnedHeight = -1
        if (view.width == 0) return
        // the height it is to be at, as the sheet will lay it out
        view.measure(
            View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.maxHeight, View.MeasureSpec.AT_MOST),
        )
        val to = view.measuredHeight
        if (to == from) {
            view.requestLayout()
            return
        }
        val down = to < from
        view.pinnedHeight = from
        if (down) empty.alpha = 0f
        heightAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = RESET_ANIMATION_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animator ->
                view.pinnedHeight = animator.animatedValue as Int
                // the empty text in once the sheet is well on its way down
                if (down) empty.alpha = ((animator.animatedFraction - 0.3f) / 0.7f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                // at the end, and when cancelled (another new chat, the sheet closed)
                override fun onAnimationEnd(animation: Animator) {
                    if (heightAnimator === animation) heightAnimator = null
                    view.pinnedHeight = -1
                    empty.alpha = 1f
                }
            })
            start()
        }
    }

    override fun onBusyChanged(busy: Boolean) {
        send.setIconResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        val label = activity.getString(if (busy) R.string.agent_stop else R.string.agent_send)
        send.contentDescription = label
        send.tooltipText = label
        updateSendEnabled(busy)
        updateHeader()
        for (i in 0 until list.childCount) {
            (list.getChildViewHolder(list.getChildAt(i)) as? UserHolder)?.setBusy(busy)
        }
    }

    // Send only with something in the field or a file picked, and none still being made
    // ready; while an answer comes the button is Stop, and can always be pressed.
    private fun updateSendEnabled(busy: Boolean = session.isBusy) {
        val ready = pending.none { it.attachment == null }
        send.isEnabled = busy || (ready && (!input.text.isNullOrBlank() || pending.isNotEmpty()))
    }

    // #endregion

    // #region the conversation

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = session.items.size

        override fun getItemId(position: Int) = itemAt(position).id

        // (the newest at position 0: positionOf)
        private fun itemAt(position: Int) = session.items[session.items.size - 1 - position]

        override fun getItemViewType(position: Int) = when (itemAt(position)) {
            is ChatItem.User -> TYPE_USER
            is ChatItem.Assistant -> TYPE_ASSISTANT
            is ChatItem.Tool -> TYPE_TOOL
            is ChatItem.Notice -> TYPE_NOTICE
            is ChatItem.Progress -> TYPE_PROGRESS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) {
                TYPE_USER -> R.layout.item_agent_user
                TYPE_ASSISTANT -> R.layout.item_agent_assistant
                TYPE_TOOL -> R.layout.item_agent_tool
                TYPE_PROGRESS -> R.layout.item_agent_progress
                else -> R.layout.item_agent_notice
            }
            val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            watchSelections(itemView)
            return when (viewType) {
                TYPE_TOOL -> ToolHolder(itemView)
                TYPE_ASSISTANT -> AssistantHolder(itemView)
                TYPE_USER -> UserHolder(itemView)
                TYPE_PROGRESS -> ProgressHolder(itemView)
                else -> TextHolder(itemView)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item: ChatItem = itemAt(position)
            when (item) {
                is ChatItem.User -> (holder as UserHolder).bind(item)
                is ChatItem.Assistant -> (holder as AssistantHolder).bind(item)
                is ChatItem.Notice -> {
                    val textView = (holder as TextHolder).text
                    val color: Int = if (item.isError) errorColor else onSurfaceVariant
                    textView.text = item.text
                    textView.setTextColor(color)
                }
                is ChatItem.Tool -> (holder as ToolHolder).bind(item)
                is ChatItem.Progress -> (holder as ProgressHolder).bind(item)
            }
        }

        // the dots go round only while their item is on screen, and so does the clock of a turn
        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            (holder as? AssistantHolder)?.resumeDots()
            (holder as? ProgressHolder)?.resume()
            // A row may come back on screen without being bound again (RecyclerView keeps a
            // few that went off it, as they were): bound while an answer came, its Ask again
            // and Edit were left greyed out once it ended (onBusyChanged reaches only the rows
            // on screen), and could not be pressed. As things are now, and its time too.
            (holder as? UserHolder)?.let {
                it.setBusy(session.isBusy)
                it.updateTime()
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            (holder as? AssistantHolder)?.pauseDots()
            (holder as? ProgressHolder)?.pause()
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? AssistantHolder)?.pauseDots()
            (holder as? ProgressHolder)?.pause()
        }
    }

    // The list's animations: a row coming in fades in where it goes; one going away goes at
    // once, and the rows a layout put elsewhere are there at once too, not slid there. Slid
    // from where they were the layout before, a row that grew (an answer streaming in, at
    // the end of the chat) was drawn lower than it is for a moment at every bit, over the
    // status of the turn under it; and while a top is held (pinTopRow, keepTop, keepHeld)
    // rows were seen going down and coming back up. In a chat that streams, moving at once
    // is what reads.
    private inner class ChatItemAnimator : DefaultItemAnimator() {
        override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
            // (one sliding still from the layout before: where it goes, at once)
            endAnimation(holder)
            dispatchMoveFinished(holder)
            return false
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            // (fading out where it was, it would lie over the rows already moved into its place)
            endAnimation(holder)
            dispatchRemoveFinished(holder)
            return false
        }
    }

    // The status of a turn going on, the last row: what is above it fades into the sheet's
    // ground over STATUS_FADE_DP up to the top of the row, as the foot of a folded code block
    // does (CodeBlockFrame), so that what the agent is doing reads clear of the answer
    // coming in above it. Drawn over the rows; once the turn is over, not.
    private inner class StatusFade : RecyclerView.ItemDecoration() {
        private val paint = Paint()
        private val height = (STATUS_FADE_DP * activity.resources.displayMetrics.density).roundToInt()
        // the ground and the top the gradient of paint was made for
        private var shaderGround = 0
        private var shaderTop = Int.MIN_VALUE

        override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val holder = parent.getChildViewHolder(child) as? ProgressHolder ?: continue
                if (!holder.isRunning) continue
                val bottom = (child.top + child.translationY).roundToInt()
                val top = bottom - height
                if (bottom <= 0 || top >= parent.height) continue
                val ground = sheetGround()
                if (ground != shaderGround || top != shaderTop) {
                    paint.shader = LinearGradient(
                        0f, top.toFloat(), 0f, bottom.toFloat(),
                        ColorUtils.setAlphaComponent(ground, 0), ground, Shader.TileMode.CLAMP,
                    )
                    shaderGround = ground
                    shaderTop = top
                }
                canvas.drawRect(0f, top.toFloat(), parent.width.toFloat(), bottom.toFloat(), paint)
            }
        }
    }

    // the color of the sheet behind the conversation (its background, as the bottom sheet
    // paints it), for what fades into it
    private fun sheetGround(): Int {
        val sheet = view.parent as? View
        (sheet?.background as? MaterialShapeDrawable)?.fillColor?.defaultColor?.let { return it }
        return MaterialColors.getColor(activity, MaterialR.attr.colorSurfaceContainerLow, Color.BLACK)
    }

    // The list of the conversation, the newest at the bottom (reverseLayout, position 0): laid
    // out from its bottom, which stays where it is as rows grow or come (pushed up, what is
    // above goes up with them), as the list changes height (the keyboard), and once scrolled
    // up through the conversation, the rows shown stay where they are. It scrolls only when
    // told to (the finger, scrollToBottom, revealEditor, keepTop) and for the cursor of the
    // box of a message being edited, as
    // it is typed into (TextView.bringPointIntoView asking to show it). Not for a view in it
    // that takes the focus (the box's text scrolled within it was counted against it: the
    // box was taken for higher than it is), nor for what any other view asks to show (the
    // text of a message selected, a tap in it), nor for anything while a finger is on the
    // list (touchingList): what is under the finger stays there.
    // Nor does a row with the focus in it (the box being edited; the text of a message never
    // takes it, SelectableTextView) anchor its layouts (getFocusedChild): LinearLayoutManager
    // would lay the list out from that row then, not from the bottom as it is with no focus
    // (the box kept in sight is revealEditor's).
    private inner class ChatLayoutManager : LinearLayoutManager(activity) {
        override fun getFocusedChild(): View? = null

        override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
            // (the rows still where the layout before put them, at their positions now)
            if (!state.isPreLayout) pinTopRow(this)
            super.onLayoutChildren(recycler, state)
        }

        override fun onLayoutCompleted(state: RecyclerView.State) {
            super.onLayoutCompleted(state)
            // (after the animations of this layout were asked for: ChatItemAnimator)
            pinned = false
            bottomPending = false
            if (keepTopView != null) keepTopLaidOut = true
        }

        override fun onRequestChildFocus(
            parent: RecyclerView,
            state: RecyclerView.State,
            child: View,
            focused: View?,
        ): Boolean = parent.isInTouchMode || super.onRequestChildFocus(parent, state, child, focused)

        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean,
        ): Boolean {
            if (touchingList || !typingIn(child)) return false
            return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        }
    }

    // whether [row] is the message being edited, and the keyboard types into its box
    private fun typingIn(row: View): Boolean {
        val id = editingId ?: return false
        return editorFocused() && list.getChildItemId(row) == id
    }

    // every text of the chat in [view] that can be selected (SelectableTextView) watched for
    // the selection it holds (holdWatcher)
    private fun watchSelections(view: View) {
        if (view is SelectableTextView) view.onSelectionHeld = holdWatcher
        if (view is ViewGroup) for (i in 0 until view.childCount) watchSelections(view.getChildAt(i))
    }

    private fun stopAllDots() {
        for (i in 0 until list.childCount) {
            val holder = list.getChildViewHolder(list.getChildAt(i))
            (holder as? AssistantHolder)?.pauseDots()
            (holder as? ProgressHolder)?.pause()
        }
    }

    // a word of the assistant itself
    private inner class TextHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.agent_text)
    }

    // What the user said, when, and what can be done with it. Asking it again or changing it
    // waits for the turn going on to end (onBusyChanged); copying it does not. Changed where
    // it is (editingId): the bubble and the line under it give way to a box with the text,
    // and Cancel and Save under it; Save asks what it now says at once, at the end of the
    // chat, the message itself and the rest of the chat staying as they were (saveEdit). From one
    // to the other at once, the row keeping its top where it is (changeKeepingTop).
    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        // The text of the message (SelectableTextView): a tap does nothing, a long press
        // selects a word without taking the focus from the field at the bottom (nor the
        // keyboard with it), a tap on it then lets the selection go and does nothing else.
        private val text: TextView = view.findViewById(R.id.agent_text)
        private val actions: View = view.findViewById(R.id.agent_user_actions)
        private val time: TextView = view.findViewById(R.id.agent_user_time)
        private val retry: View = view.findViewById(R.id.agent_user_retry)
        private val edit: View = view.findViewById(R.id.agent_user_edit)
        private val copy: View = view.findViewById(R.id.agent_user_copy)
        private val editor: View = view.findViewById(R.id.agent_user_editor)
        private val field: EditText = view.findViewById(R.id.agent_user_edit_text)
        private val note: View = view.findViewById(R.id.agent_user_edit_note)
        private val save: View = view.findViewById(R.id.agent_user_edit_save)
        private val attachmentsScroll: View = view.findViewById(R.id.agent_user_attachments_scroll)
        private val attachmentsRow: LinearLayout = view.findViewById(R.id.agent_user_attachments)
        private val more: TextView = view.findViewById(R.id.agent_user_more)

        // How many lines the text of the message takes in its bubble: worked out in bind
        // for the widest the bubble may be (wrappedLines; for which text, at which width),
        // then taken from the text as laid out (the same, unless the list was not laid out
        // yet). Past BUBBLE_MAX_LINES the bubble shows that many, and Show all under it.
        private var linesFor: String? = null
        private var linesWidth = -1
        private var lineCount = 0

        // the message bound, and whether the box is being filled by bind (not typed into)
        private var item: ChatItem.User? = null
        private var filling = false

        // the files shown above it (bound again only when they are others)
        private var shownAttachments: List<AgentAttachment>? = null
        private var shownRemovable = false

        init {
            more.setOnClickListener { item?.let { toggleExpanded(this, it) } }
            text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val laid = text.layout?.lineCount ?: return@addOnLayoutChangeListener
                if (text.visibility == View.VISIBLE && laid != lineCount) {
                    lineCount = laid
                    // (not in the middle of this layout)
                    text.post { updateMore() }
                }
            }
            field.doAfterTextChanged {
                val bound = item ?: return@doAfterTextChanged
                if (filling || bound.id != editingId) return@doAfterTextChanged
                editDraft = it?.toString().orEmpty()
                updateSave()
            }
            // what saving does: under the buttons, until ⓘ is pressed again
            view.findViewById<View>(R.id.agent_user_edit_info).setOnClickListener {
                changeKeepingTop(this@UserHolder) {
                    note.visibility = if (note.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                revealEditor(center = false)
            }
            view.findViewById<View>(R.id.agent_user_edit_cancel).setOnClickListener { endEdit() }
            save.setOnClickListener { item?.let { saveEdit(it) } }
        }

        fun bind(item: ChatItem.User) {
            this.item = item
            // Only when it says something else (a selection held in it stays), laid out again
            // then: in its frame (item_agent_user) the text is match_parent, so that a
            // selection changing is not taken for its width changing (TextView lays the
            // whole list out again for that when it is wrap_content); and for a new text of
            // as many lines TextView would keep the width it had.
            if (text.text?.toString() != item.text) {
                text.text = item.text
                text.requestLayout()
            }
            // its files above it; while it is edited, those that go with what the box says,
            // each with ✕ to take it off
            val editingNow = item.id == editingId
            val files = if (editingNow) editAttachments else item.attachments
            if (shownAttachments != files || shownRemovable != editingNow) {
                shownAttachments = files
                shownRemovable = editingNow
                attachmentsRow.removeAllViews()
                for ((index, attachment) in files.withIndex()) {
                    val chip = LayoutInflater.from(activity).inflate(R.layout.item_agent_attachment, attachmentsRow, false)
                    bindAttachment(chip, attachment, onRemove = if (editingNow) ({ removeEditAttachment(item, attachment) }) else null)
                    // (the last one flush with the bubble's edge under it)
                    if (index == files.lastIndex) {
                        (chip.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
                    }
                    attachmentsRow.addView(chip)
                }
                attachmentsScroll.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
            }
            text.setTextColor(onBubble)
            text.backgroundTintList = ColorStateList.valueOf(bubble)
            time.visibility = if (item.time > 0L) View.VISIBLE else View.GONE
            updateTime()
            // the first BUBBLE_MAX_LINES lines, or all of it once unfolded (Show all)
            val width = bubbleTextWidth()
            if (item.text != linesFor || (width > 0 && width != linesWidth)) {
                linesFor = item.text
                linesWidth = width
                lineCount = if (width > 0) wrappedLines(text, item.text, width) else item.text.lines().size
            }
            val shownLines = if (item.id in expandedMessages) Int.MAX_VALUE else BUBBLE_MAX_LINES
            if (text.maxLines != shownLines) text.maxLines = shownLines
            retry.setOnClickListener { retryMessage(item) }
            edit.setOnClickListener { startEdit(item) }
            copy.setOnClickListener { AgentCodeBlock.copyText(activity, item.text) }

            val editing = item.id == editingId
            // (files sent alone: no bubble)
            text.visibility = if (editing || item.text.isEmpty()) View.GONE else View.VISIBLE
            actions.visibility = if (editing) View.GONE else View.VISIBLE
            editor.visibility = if (editing) View.VISIBLE else View.GONE
            if (editing) {
                // (only when it says something else: setText would put the cursor back)
                if (field.text?.toString() != editDraft) {
                    filling = true
                    field.setText(editDraft)
                    field.setSelection(editDraft.length)
                    filling = false
                }
                if (editFocusPending) {
                    editFocusPending = false
                    field.post { focusEditor(field) }
                }
            } else {
                note.visibility = View.GONE
            }
            updateMore()
            setBusy(session.isBusy)
        }

        // Show all (how many lines in all) under a bubble of more than BUBBLE_MAX_LINES lines,
        // Show less once it is unfolded; nothing while it is edited
        fun updateMore() {
            val bound = item ?: return
            val shown = bound.id != editingId && bound.text.isNotEmpty() && lineCount > BUBBLE_MAX_LINES
            val visibility = if (shown) View.VISIBLE else View.GONE
            if (more.visibility != visibility) more.visibility = visibility
            if (!shown) return
            val label = if (bound.id in expandedMessages) {
                activity.getString(R.string.agent_message_less)
            } else {
                activity.getString(R.string.agent_message_more, lineCount)
            }
            if (more.text?.toString() != label) more.text = label
        }

        // when the message was sent, as long ago as it is now (10 minutes ago)
        fun updateTime() {
            val bound = item ?: return
            if (bound.time <= 0L) return
            val label = sentTimeLabel(bound.time)
            if (time.text?.toString() != label) time.text = label
        }

        // The widest the text of the bubble may be: the list's width less its padding, the
        // room the row leaves before the bubble (item_agent_user) and the bubble's own
        // padding; 0 while the list has not been laid out.
        private fun bubbleTextWidth(): Int {
            if (list.width == 0) return 0
            return (list.width - list.paddingLeft - list.paddingRight -
                itemView.paddingLeft - itemView.paddingRight -
                text.compoundPaddingLeft - text.compoundPaddingRight).coerceAtLeast(0)
        }

        // What of the box being edited to bring into sight, in the list's coordinates (top
        // to bottom), the most wanted last: the whole box with the buttons (and the note);
        // from the cursor's line down to the buttons; the cursor's line, with a little room
        // above and below it. Null while it is not shown.
        fun revealSpans(): List<IntArray>? {
            if (editor.visibility != View.VISIBLE || !editor.isLaidOut) return null
            val boxTop = itemView.top + editor.top
            val boxBottom = itemView.top + editor.bottom
            val whole = intArrayOf(boxTop, boxBottom)
            val layout = field.layout ?: return listOf(whole)
            val offset = field.selectionEnd.takeIf { it >= 0 } ?: field.length()
            val line = layout.getLineForOffset(offset)
            // where the line is in the field, as the field shows its lines now; and where it
            // will be once the field has scrolled its own lines to the cursor (it does so
            // before it is next drawn), if it is out of them now
            val shownTop = field.totalPaddingTop
            val shownBottom = field.height - field.totalPaddingBottom
            var lineTop = shownTop + layout.getLineTop(line) - field.scrollY
            var lineBottom = shownTop + layout.getLineBottom(line) - field.scrollY
            if (lineBottom > shownBottom) {
                lineTop -= lineBottom - shownBottom
                lineBottom = shownBottom
            }
            if (lineTop < shownTop) {
                lineBottom += shownTop - lineTop
                lineTop = shownTop
            }
            val at = boxTop + field.top
            val room = (CURSOR_ROOM_DP * activity.resources.displayMetrics.density).toInt()
            val cursorTop = at + lineTop - room
            val cursorBottom = at + lineBottom + room
            return listOf(whole, intArrayOf(cursorTop, boxBottom), intArrayOf(cursorTop, cursorBottom))
        }

        fun setBusy(busy: Boolean) {
            retry.isEnabled = !busy
            edit.isEnabled = !busy
            updateSave(busy)
        }

        // Save only with something to ask (some text, or files alone) that is not what was
        // asked already (grey until the text or the files change), and not while an answer
        // comes.
        fun updateSave(busy: Boolean = session.isBusy) {
            val bound = item
            val draft = editDraft.trim()
            save.isEnabled = !busy && bound != null && bound.id == editingId &&
                (draft.isNotEmpty() || editAttachments.isNotEmpty()) &&
                (draft != bound.text.trim() || editAttachments != bound.attachments)
        }
    }

    // How many lines [value] takes in [view] (its font, spacing, line breaking) at [width]
    // (that of its text): as TextView lays it out, all of it (a TextView of fewer maxLines
    // still lays out all its lines, and shows only those).
    private fun wrappedLines(view: TextView, value: CharSequence, width: Int): Int {
        val builder = StaticLayout.Builder.obtain(value, 0, value.length, view.paint, width)
            .setBreakStrategy(view.breakStrategy)
            .setHyphenationFrequency(view.hyphenationFrequency)
            .setLineSpacing(view.lineSpacingExtra, view.lineSpacingMultiplier)
            .setIncludePad(view.includeFontPadding)
            .setJustificationMode(view.justificationMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUseLineSpacingFromFallbacks(view.isFallbackLineSpacing)
        return builder.build().lineCount
    }

    // A message of the user unfolded past its first lines (Show all), or folded back (Show
    // less), its top where it is. Folded back from below its top (scrolled down through
    // it): its top brought to the top of the list, the bubble shown from its start.
    private fun toggleExpanded(holder: UserHolder, item: ChatItem.User) {
        val folding = !expandedMessages.add(item.id)
        if (folding) expandedMessages.remove(item.id)
        changeKeepingTop(holder, notAbove = folding) { holder.bind(item) }
    }

    // the label of the time [time] a message was sent at (sentAgo): 10 minutes ago, 06-12
    private fun sentTimeLabel(time: Long): String {
        val res = activity.resources
        return when (val ago = sentAgo(time, System.currentTimeMillis())) {
            SentAgo.JustNow -> res.getString(R.string.agent_time_just_now)
            is SentAgo.Minutes -> res.getQuantityString(R.plurals.agent_time_minutes_ago, ago.count, ago.count)
            is SentAgo.Hours -> res.getQuantityString(R.plurals.agent_time_hours_ago, ago.count, ago.count)
            is SentAgo.Days -> res.getQuantityString(R.plurals.agent_time_days_ago, ago.count, ago.count)
            is SentAgo.Date -> ago.text
        }
    }

    // how many questions the user asked after [item]: they go too (with their answers) if
    // [item] is asked again
    private fun laterQuestions(item: ChatItem.User): Int =
        session.items.dropWhile { it !== item }.drop(1).count { it is ChatItem.User }

    // [item] asked again, its answer in place of the one it had. Asked first if more would
    // go: "Ask again?" with ↻ and the title centered, how many questions go, the question
    // itself in a card (and how many files go with it), and that the files the assistant
    // changed stay as they are.
    private fun retryMessage(item: ChatItem.User) {
        if (session.isBusy) return
        val later = laterQuestions(item)
        if (later == 0) {
            resend(item, item.text)
            return
        }
        val res = activity.resources
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_agent_retry, null)
        content.findViewById<TextView>(R.id.agent_retry_message).text =
            res.getQuantityString(R.plurals.agent_retry_removes, later, later)
        content.findViewById<TextView>(R.id.agent_retry_question).apply {
            text = item.text
            visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
        }
        content.findViewById<TextView>(R.id.agent_retry_files).apply {
            val files = item.attachments.size
            visibility = if (files > 0) View.VISIBLE else View.GONE
            if (files > 0) text = res.getQuantityString(R.plurals.agent_retry_files, files, files)
        }
        MaterialAlertDialogBuilder(activity, MaterialR.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
            .setIcon(R.drawable.ic_refresh)
            .setTitle(R.string.agent_retry_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_message_retry) { _, _ -> resend(item, item.text) }
            .show()
    }

    // [item] edited where it is, its text in the box, the keyboard up (another message being
    // edited goes back to its bubble, what was typed there dropped)
    private fun startEdit(item: ChatItem.User) {
        if (session.isBusy) return
        val before = editingId
        editingId = item.id
        editDraft = item.text
        editAttachments = item.attachments
        editFocusPending = true
        // (the keyboard coming up keeps this message in sight, not the end of the chat:
        // focusEditor, revealEditor)
        if (before != null && before != item.id) rebind(before, keepTop = false)
        rebind(item.id, keepTop = true)
    }

    // The box of the message being edited given the focus and the keyboard, and its cursor
    // brought into sight. The list does not scroll for the focus itself (ChatLayoutManager):
    // revealEditor does.
    private fun focusEditor(field: EditText) {
        if (!field.isAttachedToWindow) return
        field.requestFocus()
        field.setSelection(field.length())
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        // in the middle of the list, and kept there while the keyboard comes up under it
        followId = editingId
        followHeight = -1
        revealEditor(center = true)
    }

    // The message being edited back to its bubble, unchanged (Cancel), its top where the
    // box's was. The keyboard stays as it is; the focus goes to the sheet, not to the field
    // at the bottom (releaseInput: before the box goes, which would otherwise hand it on to
    // the first view that takes it, the field).
    private fun endEdit() {
        val id = editingId ?: return
        if (editorFocused()) releaseInput()
        editingId = null
        editDraft = ""
        editAttachments = emptyList()
        editFocusPending = false
        revealId = null
        followId = null
        rebind(id, keepTop = true)
    }

    // [attachment] taken off what the box of [item] is to ask (✕): [item] keeps it
    private fun removeEditAttachment(item: ChatItem.User, attachment: AgentAttachment) {
        if (item.id != editingId) return
        editAttachments = editAttachments - attachment
        rebind(item.id, keepTop = true)
    }

    // What the box says asked at once, as a new question at the end of the chat, as if
    // typed into the field at the bottom, with the files of [item] not taken off: nothing of the chat
    // goes, [item] stays as it was (back to its bubble), the box only spared retyping it.
    // Not sent (nothing set up to ask with: a notice says so), the box stays as it is.
    private fun saveEdit(item: ChatItem.User) {
        val text = editDraft
        val files = editAttachments
        if (session.isBusy || (text.isBlank() && files.isEmpty()) || item.id != editingId) return
        val typing = editorFocused()
        ask { session.send(text, files) }
        if (!session.isBusy) return
        // (the sheet takes the focus before the box goes, not the field at the bottom, then
        // the keyboard goes: the question is asked, the answer to be read)
        if (typing) {
            releaseInput()
            hideKeyboard()
        }
        editingId = null
        editDraft = ""
        editAttachments = emptyList()
        editFocusPending = false
        revealId = null
        followId = null
        // back to its bubble where it is; the list follows the new question to the end
        rebind(item.id, keepTop = false)
        scrollToBottom()
    }

    // whether what has the focus is the box of a message being edited (not the field at the
    // bottom; the text of a message never has it, even holding a selection)
    private fun editorFocused(): Boolean = list.findFocus()?.id == R.id.agent_user_edit_text

    private fun hideKeyboard() {
        val token = dialog.window?.decorView?.windowToken ?: return
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(token, 0)
    }

    // The box of the message being edited brought into sight: as it opens (and while the
    // keyboard comes up under it: followId), when the keyboard has come up, when ⓘ shows the
    // note. Of UserHolder.revealSpans, the first that fits in the list: the whole box (with
    // its buttons) put in the middle of the list with [center], otherwise scrolled to by the
    // least; a box too high for that, from its cursor to its buttons, at the bottom.
    private fun revealEditor(center: Boolean) {
        revealId = editingId ?: return
        revealCenter = center
        // (a frame to come, for the reveal before it is drawn, even with nothing else to draw)
        list.invalidate()
    }

    // The reveal asked for (revealId), before a frame is drawn, once the list is laid out as
    // it is to be shown (not while a layout is still to come: the row just changed to its
    // box, scrolled to keep its top, changeKeepingTop). Following the message just opened
    // (followId), again at each new height of the list, in the middle. At once, by as much as
    // it takes: an animated scroll was cut short, or went by where the box was before the
    // list was laid out.
    private fun revealPending() {
        val following = followId
        if (following != null && following != editingId) followId = null
        if (followId != null && list.height != followHeight && !list.isLayoutRequested) {
            revealId = followId
            revealCenter = true
        }
        val id = revealId ?: return
        if (id != editingId) {
            revealId = null
            return
        }
        if (list.isLayoutRequested) return
        revealId = null
        val holder = list.findViewHolderForItemId(id) as? UserHolder ?: return
        val spans = holder.revealSpans() ?: return
        if (followId == id) followHeight = list.height
        val top = list.paddingTop
        val bottom = list.height - list.paddingBottom
        val fits = spans.indexOfFirst { it[1] - it[0] <= bottom - top }
        val span = if (fits >= 0) spans[fits] else spans.last()
        val dy = when {
            // the whole box, in the middle
            revealCenter && fits == 0 -> (span[0] + span[1]) / 2 - (top + bottom) / 2
            span[0] < top -> span[0] - top
            span[1] > bottom -> span[1] - bottom
            else -> 0
        }
        if (dy != 0) {
            list.stopScroll()
            list.scrollBy(0, dy)
        }
    }

    // The row of the message with [id] bound again where it is, at once (to its box, back to
    // its bubble): not told to the adapter, which would lay the list out from its bottom and
    // slide the rows to where they go. With [keepTop], its top stays where it is
    // (changeKeepingTop). A row not on screen is told to the adapter.
    private fun rebind(id: Long, keepTop: Boolean) {
        val index = session.items.indexOfFirst { it.id == id }
        val item = session.items.getOrNull(index) as? ChatItem.User ?: return
        val holder = list.findViewHolderForItemId(id) as? UserHolder
        val position = positionOf(index)
        if (holder == null || holder.bindingAdapterPosition != position) {
            adapter.notifyItemChanged(position)
            return
        }
        if (keepTop) changeKeepingTop(holder) { holder.bind(item) } else holder.bind(item)
    }

    private fun changeKeepingTop(holder: RecyclerView.ViewHolder, notAbove: Boolean = false, change: () -> Unit) {
        if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
            change()
            return
        }
        changeKeepingTop(holder.itemView, notAbove, change)
    }

    // [change] made to [view] (a row of the list, or a code block in one: what it shows, so
    // how high it is: unfolded, folded, a box opened), and its top kept where it is now:
    // what is above it stays, what is under it follows, as a tap on it expects. Laid out from
    // its bottom as the list is, it would grow upwards instead, what is above pushed up
    // (keepTop scrolls that back before the frame is drawn). Only at the end of the
    // conversation does it come down by what it gives up in height, there being nothing
    // under it to fill that. With [notAbove], a top above the list's is brought down to it.
    private fun changeKeepingTop(view: View, notAbove: Boolean = false, change: () -> Unit) {
        val before = if (view.isLaidOut) topInList(view) else null
        change()
        if (before == null) return
        list.stopScroll()
        keepTopView = view
        keepTopLaidOut = false
        keepTopAt = if (notAbove) max(before, list.paddingTop) else before
        // (a frame to come, for keepTop, even if it stayed as high)
        list.invalidate()
    }

    // The top of [view] (a row of the list, or a view in one) in the list, as laid out (not
    // where a row sliding to its place is drawn); null: not in the list.
    private fun topInList(view: View): Int? {
        if (!view.isAttachedToWindow) return null
        if (view.parent === list) return view.top
        val rect = Rect()
        return try {
            list.offsetDescendantRectToMyCoords(view, rect)
            rect.top
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // The view of keepTopView with its top back where it was, once the list is laid out
    // again (not while that layout is still to come, unless it has been laid out once since
    // and a view in it asks for another). Before the frame is drawn: it never shows elsewhere.
    private fun keepTop() {
        val view = keepTopView ?: return
        if (!keepTopLaidOut && list.isLayoutRequested) return
        keepTopView = null
        val top = topInList(view) ?: return
        val dy = top - keepTopAt
        if (dy != 0) list.scrollBy(0, dy)
    }

    /**
     * The list about to be laid out (ChatLayoutManager.onLayoutChildren, the layout that
     * counts, not the pre-layout of an animation), scrolled up through the conversation: laid
     * out from its top row, where it is, rather than from its bottom row. Laid out from its
     * bottom, a row that grows there (the thinking of a turn unfolded at the end of the chat,
     * streaming in; an answer coming) grows upwards, pushing up what is shown: read from
     * above, it is what is on top that should stay, as in any list. Asked of the layout
     * itself (a position to lay it out from, LinearLayoutManager.scrollToPositionWithOffset:
     * in a list laid out from its end, where the bottom of that row goes), so that nothing
     * above it moves (scrolled back afterwards, a row pushed off the top was at times not
     * found to scroll by, and it jumped); only that row growing itself is scrolled back. At
     * the end of the conversation, not: there the bottom stays, what comes pushing the rest
     * up. Nor when the end is to be shown (bottomPending).
     */
    private fun pinTopRow(manager: LinearLayoutManager) {
        if (bottomPending || manager.childCount == 0) return
        val end = list.height - list.paddingBottom
        // at the end: the newest row there, its bottom at the list's (or above it)
        val newest = manager.findViewByPosition(0)
        if (newest != null && bottomOf(manager, newest) <= end + 1) return
        var top: View? = null
        for (i in 0 until manager.childCount) {
            val child = manager.getChildAt(i) ?: continue
            val params = child.layoutParams as RecyclerView.LayoutParams
            // (one gone above the list, or going away: not)
            if (params.isItemRemoved || params.viewLayoutPosition == RecyclerView.NO_POSITION) continue
            if (manager.getDecoratedBottom(child) <= list.paddingTop) continue
            if (top == null || manager.getDecoratedTop(child) < manager.getDecoratedTop(top)) top = child
        }
        val row = top ?: return
        pinned = true
        // (within the layout: it asks for no other)
        manager.scrollToPositionWithOffset(manager.getPosition(row), end - bottomOf(manager, row))
        // That row's bottom is where the layout puts it, so the row itself, grown (the answer
        // streaming in, read inside it: it is the top row on screen), would grow upwards:
        // its top put back where it is, before the frame is drawn (keepTop), unless a view's
        // top is held already (changeKeepingTop).
        if (keepTopView == null) {
            keepTopView = row
            keepTopLaidOut = false
            keepTopAt = row.top
        }
    }

    // the bottom of [child] as the layout places it (its margin under it)
    private fun bottomOf(manager: LinearLayoutManager, child: View): Int =
        manager.getDecoratedBottom(child) + (child.layoutParams as RecyclerView.LayoutParams).bottomMargin

    // The row held under the finger (holdId) back where it was on the screen, before every
    // frame: the list laid out again under it (an answer streaming in at the end, the sheet
    // changing height with the keyboard) would move what is under the finger, which, while
    // it selects, then selects what comes under it. As far as the list can scroll. Let go
    // once the finger is up and the keyboard is where it goes.
    private fun keepHeld() {
        val id = holdId ?: return
        val holder = list.findViewHolderForItemId(id)
        if (holder == null) {
            holdId = null
            return
        }
        val dy = rowTop(holder.itemView) - holdTop
        if (dy != 0) {
            holdScrolling = true
            list.scrollBy(0, dy)
            holdScrolling = false
        }
        if (!touchingList && slidingIme == null) holdId = null
    }

    // the row under [ev] (its going down on the list) held there (keepHeld)
    private fun startHold(ev: MotionEvent) {
        val at = IntArray(2)
        list.getLocationOnScreen(at)
        val child = list.findChildViewUnder(ev.rawX - at[0], ev.rawY - at[1])
        val id = child?.let { list.getChildItemId(it) } ?: RecyclerView.NO_ID
        if (child == null || id == RecyclerView.NO_ID) {
            holdId = null
            return
        }
        holdId = id
        holdTop = rowTop(child)
    }

    // Where the top of [row] (a row of the list) is laid out on the screen: where the list is
    // (the sheet moving with the keyboard) and where the row is in it, not where a row sliding
    // to its place (the list's item animations) is drawn on the way.
    private fun rowTop(row: View): Int {
        val at = IntArray(2)
        list.getLocationOnScreen(at)
        return at[1] + row.top
    }

    private fun resend(item: ChatItem.User, text: String) {
        ask { session.resend(item, text) }
    }

    // What the model said: Thinking while it has said nothing yet, then its parts in order,
    // each text a TextView and each code block an AgentCodeBlock. Bound again at every bit
    // that streams in: the parts there are stay, changed only if they changed (a block
    // takes in its new code, a text is rendered again), what is new is added after them.
    private inner class AssistantHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thinkingText: TextView = view.findViewById(R.id.agent_thinking)
        private val blocks: LinearLayout = view.findViewById(R.id.agent_blocks)
        // what is in blocks, a part each: its TextView, or its AgentCodeBlock
        private val parts = ArrayList<Any>()

        // the model thinking (nothing said yet): the toolchain sheet's busy dots before a word
        private var dots: BusyDotsDrawable? = null
        private var thinking = false

        fun bind(item: ChatItem.Assistant) {
            thinking = item.text.isEmpty()
            if (thinking) {
                val busy = dots ?: BusyDotsDrawable.ofDp(activity).also {
                    it.setTintList(ColorStateList.valueOf(primary))
                    dots = it
                }
                thinkingText.compoundDrawablePadding = (THINKING_GAP_DP * activity.resources.displayMetrics.density).toInt()
                thinkingText.setTextColor(onSurfaceVariant)
                thinkingText.setBusyDotsAtStart(true, busy)
                thinkingText.visibility = View.VISIBLE
                blocks.visibility = View.GONE
                removeParts(from = 0)
                return
            }
            dots?.let { thinkingText.setBusyDotsAtStart(false, it) }
            thinkingText.visibility = View.GONE
            blocks.visibility = View.VISIBLE

            val segments = AgentCodeBlocks.split(item.text.toString())
            for ((index, segment) in segments.withIndex()) {
                when (segment) {
                    is Segment.Text -> {
                        val view = textPart(index)
                        // rendered again only if it changed (the text before a block that streams in does not)
                        if (view.tag != segment.text) {
                            view.text = AgentMarkdown.render(segment.text, mono, codeBackground)
                            view.tag = segment.text
                        }
                    }
                    is Segment.Code -> codePart(index).bind(
                        AgentCodeBlock.Content(
                            label = segment.language.ifEmpty { activity.getString(R.string.agent_code_plain) },
                            code = segment.code,
                            highlightName = AgentCodeBlocks.fileNameOf(segment.language),
                            // highlighted once it is all there
                            isComplete = segment.closed || !item.streaming,
                        )
                    )
                }
            }
            removeParts(from = segments.size)
        }

        // the TextView of part [index], made if that part is not one yet
        private fun textPart(index: Int): TextView {
            (parts.getOrNull(index) as? TextView)?.let { return it }
            removeParts(from = index)
            val view = LayoutInflater.from(activity).inflate(R.layout.view_agent_text, blocks, false) as TextView
            watchSelections(view)
            blocks.addView(view)
            parts += view
            return view
        }

        // the code block of part [index], made if that part is not one yet
        private fun codePart(index: Int): AgentCodeBlock {
            (parts.getOrNull(index) as? AgentCodeBlock)?.let { return it }
            removeParts(from = index)
            val block = newCodeBlock(blocks)
            blocks.addView(block.view)
            parts += block
            return block
        }

        // the parts from [from] on taken away (a code block released)
        private fun removeParts(from: Int) {
            while (parts.size > from) {
                when (val part = parts.removeAt(parts.lastIndex)) {
                    is AgentCodeBlock -> {
                        releaseCodeBlock(part)
                        blocks.removeView(part.view)
                    }
                    is View -> blocks.removeView(part)
                }
            }
        }

        fun resumeDots() {
            if (thinking) dots?.start()
        }

        fun pauseDots() {
            dots?.stop()
        }
    }

    // The status of a turn (ChatItem.Progress): while it goes on, the busy dots, what the
    // agent is doing and how long the turn has taken, the clock said again every second
    // while the row is on screen (not off it: resume, pause); once over, how it ended and
    // how long it took. With › when the model said what it thought: a tap on the row
    // unfolds that under it (Markdown, as an answer), and folds it again.
    private inner class ProgressHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val head: View = view.findViewById(R.id.agent_progress_head)
        private val icon: ImageView = view.findViewById(R.id.agent_progress_icon)
        private val text: TextView = view.findViewById(R.id.agent_progress_text)
        private val time: TextView = view.findViewById(R.id.agent_progress_time)
        private val chevron: ImageView = view.findViewById(R.id.agent_progress_chevron)
        private val thought: TextView = view.findViewById(R.id.agent_progress_thought)
        private var dots: BusyDotsDrawable? = null
        private var item: ChatItem.Progress? = null

        /** Whether the turn of this row goes on (StatusFade). */
        val isRunning: Boolean get() = item?.state == ProgressState.RUNNING

        // the clock, at each whole second of the turn
        private val tick = object : Runnable {
            override fun run() {
                val bound = item ?: return
                updateTime(bound)
                if (bound.state == ProgressState.RUNNING && itemView.isAttachedToWindow) schedule(bound)
            }
        }

        fun bind(item: ChatItem.Progress) {
            this.item = item
            val running = item.state == ProgressState.RUNNING
            if (running) {
                val busy = dots ?: BusyDotsDrawable.ofDp(activity).also { dots = it }
                icon.imageTintList = ColorStateList.valueOf(primary)
                icon.setBusyDots(true, busy)
                if (!itemView.isAttachedToWindow) busy.stop()
            } else {
                val (image, color) = when (item.state) {
                    ProgressState.DONE -> R.drawable.ic_check_circle to statusColor
                    ProgressState.STOPPED -> R.drawable.ic_stop to statusColor
                    else -> R.drawable.ic_error_outline to errorColor
                }
                dots?.let { icon.setBusyDots(false, it, image) }
                icon.setImageResource(image)
                icon.imageTintList = ColorStateList.valueOf(color)
            }
            // (set only when it says something else: setText lays the row out again)
            val said = progressText(item)
            if (text.text?.toString() != said) text.text = said
            text.setTextColor(if (item.state == ProgressState.INCOMPLETE) errorColor else statusColor)
            time.setTextColor(statusColor)
            updateTime(item, exact = true)

            val thinking = item.thought.isNotBlank()
            chevron.visibility = if (thinking) View.VISIBLE else View.GONE
            chevron.imageTintList = ColorStateList.valueOf(statusColor)
            chevron.rotation = if (item.expanded) 90f else 0f
            chevron.contentDescription = activity.getString(
                if (item.expanded) R.string.agent_progress_hide_thinking else R.string.agent_progress_show_thinking
            )
            val unfolded = thinking && item.expanded
            thought.visibility = if (unfolded) View.VISIBLE else View.GONE
            if (unfolded) renderThought(item) else itemView.removeCallbacks(renderLater)
            head.setOnClickListener {
                if (item.thought.isBlank()) return@setOnClickListener
                item.expanded = !item.expanded
                // (unfolded downwards, the row's top where it is)
                changeKeepingTop(this@ProgressHolder) { bind(item) }
            }
            // (after the listener, which makes it clickable: no ripple with nothing to unfold)
            head.isClickable = thinking
            itemView.removeCallbacks(tick)
            if (running && itemView.isAttachedToWindow) schedule(item)
        }

        // The time as it is now. Every second while the turn goes on: so as not to lay the
        // list out again each time for nothing (the row stays as high), the time is as wide as it has to be, set (a TextView of a set width and the same height
        // takes another text without asking for a layout): wider when it has to be (9s, 10s;
        // the digits are all as wide: tnum), as wide as it is when the row is bound ([exact]).
        private fun updateTime(item: ChatItem.Progress, exact: Boolean = false) {
            val shown = AgentProgress.elapsedText(item.elapsedAt(System.currentTimeMillis()))
            if (!exact && time.text?.toString() == shown) return
            val needed = ceil(time.paint.measureText(shown)).toInt() + time.compoundPaddingLeft + time.compoundPaddingRight
            val params = time.layoutParams
            if (params.width < 0 || needed > params.width || (exact && needed != params.width)) {
                params.width = needed
                time.layoutParams = params
            }
            time.text = shown
        }

        private fun schedule(item: ChatItem.Progress) {
            itemView.removeCallbacks(tick)
            itemView.postDelayed(tick, AgentProgress.untilNextSecond(item.elapsedAt(System.currentTimeMillis())))
        }

        fun resume() {
            val bound = item ?: return
            if (bound.state != ProgressState.RUNNING) return
            dots?.start()
            updateTime(bound)
            schedule(bound)
        }

        fun pause() {
            dots?.stop()
            itemView.removeCallbacks(tick)
            itemView.removeCallbacks(renderLater)
        }

        // What the model thought, unfolded, as it now is. The row is bound again at every bit
        // that streams in, and the thinking of a turn grows long: rendered again only if it
        // changed; while it streams, no more than once every THOUGHT_RENDER_MS (what came
        // meanwhile then: renderLater), and only its last part, the parts before it (each
        // ended by a blank line, outside of a code block) rendered once (renderedHead).
        // Rendering it all again at every bit, on the main thread, was felt in the scrolling.
        private fun renderThought(item: ChatItem.Progress, now: Boolean = false) {
            val all = item.thought.toString()
            if (thought.tag == all) return
            val time = SystemClock.uptimeMillis()
            val since = time - renderedAt
            if (!now && item.state == ProgressState.RUNNING && since < THOUGHT_RENDER_MS) {
                itemView.removeCallbacks(renderLater)
                itemView.postDelayed(renderLater, THOUGHT_RENDER_MS - since)
                return
            }
            itemView.removeCallbacks(renderLater)
            renderedAt = time
            thought.text = renderInParts(all)
            thought.tag = all
        }

        private var renderedAt = 0L
        private val renderLater = Runnable { item?.let { if (it.expanded) renderThought(it, now = true) } }

        // the parts of the thinking before its last one, as rendered last (their text, and it)
        private var renderedHead: Pair<String, CharSequence>? = null

        // [all] rendered: its last part (after the last blank line not inside a code block)
        // each time, the rest as it was rendered before if it is the same
        private fun renderInParts(all: String): CharSequence {
            val cut = lastPartStart(all)
            if (cut <= 0) return AgentMarkdown.render(all, mono, codeBackground)
            val head = all.substring(0, cut).trimEnd()
            val rendered = renderedHead?.takeIf { it.first == head }?.second
                ?: AgentMarkdown.render(head, mono, codeBackground).also { renderedHead = head to it }
            return SpannableStringBuilder(rendered).append("\n\n").append(AgentMarkdown.render(all.substring(cut), mono, codeBackground))
        }

        // where the last part of [text] begins: after its last blank line outside of a code
        // block (```); 0: one part
        private fun lastPartStart(text: String): Int {
            var fenced = false
            var start = 0
            var lineStart = 0
            var previousBlank = false
            while (lineStart < text.length) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val line = text.substring(lineStart, lineEnd)
                val blank = line.isBlank()
                // (a part may begin with a code block: as it was before this line)
                if (!fenced && previousBlank && !blank) start = lineStart
                if (line.trimStart().startsWith("```")) fenced = !fenced
                previousBlank = blank && !fenced
                lineStart = lineEnd + 1
            }
            return if (fenced) 0 else start
        }
    }

    // what the status of a turn says: what the agent is doing, or how the turn ended
    private fun progressText(item: ChatItem.Progress): String = when (item.state) {
        ProgressState.DONE -> activity.getString(R.string.agent_progress_done)
        ProgressState.STOPPED -> activity.getString(R.string.agent_progress_stopped)
        ProgressState.INCOMPLETE -> activity.getString(R.string.agent_progress_incomplete)
        ProgressState.RUNNING -> when (val doing = item.activity) {
            TurnActivity.Thinking -> activity.getString(R.string.agent_progress_thinking)
            is TurnActivity.Thought -> doing.text
            TurnActivity.Answering -> activity.getString(R.string.agent_progress_answering)
            is TurnActivity.Preparing -> activity.getString(R.string.agent_progress_preparing, labelOf(doing.tool))
            is TurnActivity.Waiting -> activity.getString(
                R.string.agent_progress_waiting,
                listOf(labelOf(doing.tool), doing.summary).filter { it.isNotBlank() }.joinToString(" "),
            )
            is TurnActivity.Running -> if (doing.summary.isBlank()) {
                activity.getString(R.string.agent_progress_tool, labelOf(doing.tool))
            } else {
                activity.getString(runningOf(doing.tool), doing.summary)
            }
            TurnActivity.Compacting -> activity.getString(R.string.agent_compacting)
        }
    }

    // what a call of [tool] running is said to be doing, on what (Reading src/Main.kt)
    private fun runningOf(tool: String) = when (tool) {
        "list_files" -> R.string.agent_progress_list_files
        "read_file" -> R.string.agent_progress_read_file
        "search_files" -> R.string.agent_progress_search_files
        "write_file" -> R.string.agent_progress_write_file
        "edit_file" -> R.string.agent_progress_edit_file
        "run_command" -> R.string.agent_progress_run_command
        else -> R.string.agent_progress_tool
    }

    // #region code blocks

    // A code block of this sheet, in [parent]: Expand shows it whole (CodeViewer)
    private fun newCodeBlock(parent: ViewGroup): AgentCodeBlock {
        val block = AgentCodeBlock(
            activity, parent, scope, styleEditor, highlight, codeBackground,
            onExpand = { content -> newViewer().showText(content.label, content.code, content.highlightName) },
            // unfolded, folded where it is: its top stays (folded from below its top: brought down)
            keepingTop = { view, folding, change -> changeKeepingTop(view, notAbove = folding, change = change) },
            // one selection in the sheet at a time: the other blocks drop theirs
            onSelectionStarted = { selecting -> for (other in codeBlocks) if (other !== selecting) other.clearSelection() },
        )
        codeBlocks += block
        return block
    }

    private fun releaseCodeBlock(block: AgentCodeBlock) {
        block.release()
        codeBlocks -= block
    }

    private fun releaseCodeBlocks() {
        for (block in codeBlocks) block.release()
        codeBlocks.clear()
    }

    private fun newViewer() = CodeViewer(activity, scope, styleEditor, highlight)

    // what a tool came to, in a code block: what read_file read without its line numbers
    // (highlighted as the file), the output of a command, a list or the matches (plain)
    private fun resultContent(item: ChatItem.Tool, result: String): AgentCodeBlock.Content = when (item.name) {
        "read_file" -> {
            val shown = AgentCodeBlocks.unnumbered(result)
            val name = item.viewFile?.name ?: item.summary
            AgentCodeBlock.Content(
                label = shown.note?.let { "$name · $it" } ?: name,
                code = shown.code,
                highlightName = item.viewFile?.name?.takeIf { shown.firstLine != null },
                isComplete = true,
            )
        }
        "run_command" -> AgentCodeBlock.Content(activity.getString(R.string.agent_code_output), result, null, true)
        else -> AgentCodeBlock.Content(activity.getString(R.string.agent_code_result), result, null, true)
    }

    // #endregion

    private inner class ToolHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val head = view.findViewById<View>(R.id.agent_tool_head)
        private val icon = view.findViewById<ImageView>(R.id.agent_tool_icon)
        private val name = view.findViewById<TextView>(R.id.agent_tool_name)
        private val summary = view.findViewById<TextView>(R.id.agent_tool_summary)
        private val state = view.findViewById<TextView>(R.id.agent_tool_state)
        private val previewScroll = view.findViewById<View>(R.id.agent_tool_preview_scroll)
        private val preview = view.findViewById<TextView>(R.id.agent_tool_preview)
        private val result = view.findViewById<TextView>(R.id.agent_tool_result)
        private val codeFrame = view.findViewById<ViewGroup>(R.id.agent_tool_code)
        private var codeBlock: AgentCodeBlock? = null
        private val fileActions = view.findViewById<View>(R.id.agent_tool_file_actions)
        private val viewFile = view.findViewById<View>(R.id.agent_tool_view)
        private val actions = view.findViewById<View>(R.id.agent_tool_actions)
        private val allow = view.findViewById<View>(R.id.agent_tool_allow)
        private val allowInChat = view.findViewById<View>(R.id.agent_tool_allow_chat)
        private val deny = view.findViewById<View>(R.id.agent_tool_deny)
        private val open = view.findViewById<View>(R.id.agent_tool_open)

        fun bind(item: ChatItem.Tool) {
            icon.setImageResource(iconOf(item.name))
            icon.imageTintList = ColorStateList.valueOf(onSurfaceVariant)
            name.text = labelOf(item.name)
            summary.text = item.summary
            val (stateText, stateColor) = when (item.state) {
                ToolState.WAITING -> R.string.agent_tool_waiting to primary
                ToolState.RUNNING -> R.string.agent_tool_running to onSurfaceVariant
                ToolState.DONE -> R.string.agent_tool_done to onSurfaceVariant
                ToolState.FAILED -> R.string.agent_tool_failed to errorColor
                ToolState.DENIED -> R.string.agent_tool_denied to errorColor
                ToolState.STOPPED -> R.string.agent_tool_stopped to onSurfaceVariant
            }
            state.setText(stateText)
            state.setTextColor(stateColor)

            val waiting = item.state == ToolState.WAITING
            val shownPreview = item.preview?.takeIf { waiting || item.expanded }
            previewScroll.visibility = if (shownPreview != null) View.VISIBLE else View.GONE
            if (shownPreview != null) {
                previewScroll.backgroundTintList = ColorStateList.valueOf(codeBackground)
                preview.text = colorDiff(shownPreview)
            }
            // While a command runs, the last lines of what it prints so far, as they come (in
            // place of what it came to, which it does not have yet); open or not.
            val live = item.live?.takeIf { item.state == ToolState.RUNNING && it.isNotBlank() }?.let { lastLines(it) }
            val shownResult = live ?: item.result?.takeIf { item.expanded || item.state == ToolState.FAILED }
            // what read_file, list_files, search_files and run_command came to: in a code block
            val codeResult = shownResult?.takeIf { live == null && item.state == ToolState.DONE && item.name in CODE_RESULTS }
            val asCode = codeResult != null
            codeFrame.visibility = if (asCode) View.VISIBLE else View.GONE
            if (codeResult != null) {
                val block = codeBlock ?: newCodeBlock(codeFrame).also {
                    codeFrame.addView(it.view)
                    codeBlock = it
                }
                block.bind(resultContent(item, codeResult))
            }
            result.visibility = if (shownResult != null && !asCode) View.VISIBLE else View.GONE
            if (shownResult != null && !asCode) {
                result.text = if (shownResult.length > MAX_SHOWN_RESULT) shownResult.take(MAX_SHOWN_RESULT) + "\n…" else shownResult
                result.setTextColor(if (item.state == ToolState.FAILED) errorColor else onSurfaceVariant)
            }

            actions.visibility = if (waiting) View.VISIBLE else View.GONE
            allow.setOnClickListener { session.decide(item, Decision.ALLOW) }
            allowInChat.setOnClickListener { session.decide(item, Decision.ALLOW_IN_CHAT) }
            deny.setOnClickListener { session.decide(item, Decision.DENY) }

            val file = item.file
            val done = item.state == ToolState.DONE
            open.visibility = if (file != null && done) View.VISIBLE else View.GONE
            // the file it changed or read, only to look at
            val viewed = item.viewFile ?: file
            viewFile.visibility = if (viewed != null && done) View.VISIBLE else View.GONE
            fileActions.visibility = if (done && (file != null || viewed != null)) View.VISIBLE else View.GONE
            viewFile.setOnClickListener { if (viewed != null) newViewer().showFile(viewed) }
            open.setOnClickListener {
                if (file != null) {
                    dialog.dismiss()
                    openFile(file)
                }
            }
            head.setOnClickListener {
                if (item.preview == null && item.result == null) return@setOnClickListener
                item.expanded = !item.expanded
                // (unfolded downwards, the row's top where it is)
                changeKeepingTop(this@ToolHolder) { bind(item) }
            }
        }
    }

    // The last LIVE_LINES lines of what a command prints, as a terminal shows them: a line
    // written over (\r, a progress bar) as its last writing, the empty end left out.
    private fun lastLines(text: String): String {
        val lines = text.trimEnd().split('\n').map { it.trimEnd('\r').substringAfterLast('\r') }
        return lines.takeLast(LIVE_LINES).joinToString("\n")
    }

    // the lines of a diff in colour: added green, removed red, a command in the accent
    private fun colorDiff(text: String): CharSequence {
        val out = SpannableStringBuilder()
        for ((index, line) in text.split('\n').withIndex()) {
            if (index > 0) out.append('\n')
            val start = out.length
            out.append(line)
            val color = when {
                line.startsWith("+ ") -> added
                line.startsWith("- ") -> errorColor
                line.startsWith("$ ") -> primary
                else -> onSurfaceVariant
            }
            out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun iconOf(tool: String) = when (tool) {
        "list_files" -> R.drawable.ic_folder
        "read_file" -> R.drawable.ic_file
        "search_files" -> R.drawable.ic_search
        "write_file" -> R.drawable.ic_add_file
        "edit_file" -> R.drawable.ic_edit
        "run_command" -> R.drawable.ic_console
        else -> R.drawable.ic_agent
    }

    private fun labelOf(tool: String): String = when (tool) {
        "list_files" -> activity.getString(R.string.agent_tool_list_files)
        "read_file" -> activity.getString(R.string.agent_tool_read_file)
        "search_files" -> activity.getString(R.string.agent_tool_search_files)
        "write_file" -> activity.getString(R.string.agent_tool_write_file)
        "edit_file" -> activity.getString(R.string.agent_tool_edit_file)
        "run_command" -> activity.getString(R.string.agent_tool_run_command)
        else -> tool
    }

    // #endregion

    // #region settings

    // the models to pick from (ModelPicker, a drop-down under the model); the settings while nothing is set up
    private fun showModelPicker() {
        if (!AgentSettings.summary(activity).isUsable) {
            showSettings()
            return
        }
        if (modelPicker?.isShowing == true) return
        // over the sheet, in the same window: the keyboard stays where it is (ModelPicker)
        val host = dialog.findViewById<ViewGroup>(MaterialR.id.container) ?: return
        modelPicker = ModelPicker(
            activity,
            scope,
            host = host,
            anchor = modelRow,
            // (into the message being edited, if that is where the keyboard was typing)
            focusBack = list.findFocus() as? EditText ?: input,
            back = dialog.onBackPressedDispatcher,
            onPicked = { kind, model ->
                AgentSettings.selectModel(activity, kind, model)
                updateHeader()
                // a turn going on keeps the model it started with
                if (session.isBusy) {
                    Toast.makeText(activity, activity.getString(R.string.agent_model_next_request, model), Toast.LENGTH_SHORT).show()
                }
            },
        ).also { it.show() }
    }

    // #region the chats kept (AgentHistory)

    // The chats kept, the pinned ones at the top and then the one written to last: a tap
    // takes one up again where it stopped, a long press opens its menu (pin, rename,
    // delete: showHistoryMenu), and the button at the foot deletes them all. The chat shown
    // now is marked.
    @Suppress("NotifyDataSetChanged")
    private fun showHistory() {
        val context = activity.applicationContext
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_agent_history, null)
        val rows = content.findViewById<MaxHeightRecyclerView>(R.id.agent_history_list)
        val none = content.findViewById<TextView>(R.id.agent_history_empty)
        rows.setMaxHeight((activity.resources.displayMetrics.heightPixels * HISTORY_HEIGHT).toInt())
        rows.layoutManager = LinearLayoutManager(activity)

        val entries = ArrayList<AgentHistory.Entry>()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_history)
            .setView(content)
            .setNegativeButton(R.string.agent_history_close, null)
            .setNeutralButton(R.string.agent_history_clear, null)
            .create()
        // the list read again from the files (set below: it needs the adapter)
        var reload: () -> Unit = {}

        val adapter = HistoryAdapter(
            entries,
            onOpen = { entry ->
                when {
                    session.isBusy -> Toast.makeText(activity, R.string.agent_history_busy, Toast.LENGTH_SHORT).show()
                    // the one shown already: nothing to take up
                    entry.id == session.historyId -> dialog.dismiss()
                    else -> openChat(entry) { dialog.dismiss() }
                }
            },
            // a long press: pin, rename, delete (the row itself has no button)
            onMenu = { entry, anchor -> showHistoryMenu(entry, anchor) { reload() } },
        )
        rows.adapter = adapter

        reload = {
            scope.launch {
                val found = withContext(Dispatchers.IO) { AgentHistory.list(context) }
                if (!dialog.isShowing) return@launch
                entries.clear()
                entries += found
                adapter.notifyDataSetChanged()
                rows.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
                none.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                // how many chats are kept, in the title (none: the title alone)
                dialog.setTitle(
                    if (entries.isEmpty()) activity.getString(R.string.agent_history)
                    else activity.getString(R.string.agent_history_count, entries.size)
                )
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = entries.isNotEmpty()
            }
        }

        dialog.show()
        // Clear all asks first, and the list stays open whatever the answer (the dialog's own
        // listener would close it)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            isEnabled = false
            // in the error colour, as what deletes everywhere in the app (faint while there
            // is nothing to clear, as a disabled button is)
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(ColorUtils.setAlphaComponent(errorColor, (255 * DISABLED_TEXT_ALPHA).toInt()), errorColor),
                )
            )
            setOnClickListener { confirmClearHistory(reload) }
        }
        reload()
    }

    // [entry] taken up in place of the chat shown, which is kept first; [done] once it is
    private fun openChat(entry: AgentHistory.Entry, done: () -> Unit) {
        val context = activity.applicationContext
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { AgentHistory.load(context, entry.id) }
            if (snapshot == null) {
                Toast.makeText(activity, R.string.agent_history_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // the chat shown is kept before the other takes its place
            session.keep()
            if (!session.restore(snapshot, entry.id)) {
                // a turn began while the file was read
                Toast.makeText(activity, R.string.agent_history_busy, Toast.LENGTH_SHORT).show()
                return@launch
            }
            done()
        }
    }

    private fun confirmClearHistory(reload: () -> Unit) {
        val context = activity.applicationContext
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.agent_history_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_history_clear) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { AgentHistory.clear(context) }
                    // what is shown stays shown, but is no longer one of the chats kept
                    session.historyId = null
                    reload()
                }
            }
            .show()
    }

    /**
     * The menu of a row of the history, on a long press: the chat pinned (or no longer),
     * renamed, deleted. [reload] reads the list again once it is one of those. Delete is in
     * the error colour, as everywhere else in the app (the menu of the file tree).
     */
    @SuppressLint("RestrictedApi")
    private fun showHistoryMenu(entry: AgentHistory.Entry, anchor: View, reload: () -> Unit) {
        val context = activity.applicationContext
        val popup = PopupMenu(activity, anchor)
        val menu = popup.menu
        val pin = activity.getString(if (entry.pinned) R.string.agent_history_unpin else R.string.agent_history_pin)
        menu.add(Menu.NONE, MENU_HISTORY_PIN, 0, pin)
            .setIcon(ContextCompat.getDrawable(activity, R.drawable.ic_push_pin)?.mutate())
        menu.add(Menu.NONE, MENU_HISTORY_RENAME, 1, activity.getString(R.string.agent_history_rename))
            .setIcon(ContextCompat.getDrawable(activity, R.drawable.ic_rename)?.mutate())
        val red = SpannableString(activity.getString(R.string.agent_history_delete_action)).apply {
            setSpan(ForegroundColorSpan(errorColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        menu.add(Menu.NONE, MENU_HISTORY_DELETE, 2, red)
            .setIcon(ContextCompat.getDrawable(activity, R.drawable.ic_delete)?.mutate()?.apply { setTint(errorColor) })
        // the icons before the items (as in the menus of the tabs and of the file tree)
        (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HISTORY_PIN -> scope.launch {
                    withContext(Dispatchers.IO) { AgentHistory.setPinned(context, entry.id, !entry.pinned) }
                    reload()
                }
                MENU_HISTORY_RENAME -> askHistoryName(entry, reload)
                MENU_HISTORY_DELETE -> scope.launch {
                    withContext(Dispatchers.IO) { AgentHistory.delete(context, entry.id) }
                    // the chat shown was that one: it is no longer kept (the next save makes a new one)
                    if (session.historyId == entry.id) session.historyId = null
                    reload()
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    // The name of a chat of the history, asked for with what it is called now in the field
    // (the question it began with, while it was never renamed). Left empty, or set back to
    // that question: it is called by the question again, and follows it as the chat goes on.
    private fun askHistoryName(entry: AgentHistory.Entry, reload: () -> Unit) {
        val context = activity.applicationContext
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_new_name, null)
        val field = content.findViewById<TextInputEditText>(R.id.new_name)
        content.findViewById<TextInputLayout>(R.id.new_name_layout).hint =
            activity.getString(R.string.agent_history_rename_hint)
        field.setText(entry.shown)
        field.setSelection(field.text?.length ?: 0)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_history_rename)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_history_rename_save) { _, _ ->
                val name = field.text?.toString()?.trim().orEmpty()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AgentHistory.rename(context, entry.id, if (name == entry.title) "" else name)
                    }
                    reload()
                }
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        field.requestFocus()
    }

    private inner class HistoryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.agent_history_icon)
        val title: TextView = view.findViewById(R.id.agent_history_title)
        val subtitle: TextView = view.findViewById(R.id.agent_history_subtitle)
        val pin: View = view.findViewById(R.id.agent_history_pin)
    }

    private inner class HistoryAdapter(
        private val entries: List<AgentHistory.Entry>,
        private val onOpen: (AgentHistory.Entry) -> Unit,
        private val onMenu: (AgentHistory.Entry, View) -> Unit,
    ) : RecyclerView.Adapter<HistoryHolder>() {

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HistoryHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_agent_history, parent, false))

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val entry = entries[position]
            holder.title.text = entry.shown.ifBlank { activity.getString(R.string.agent_history_untitled) }
            // the one shown now: its icon and name in the accent colour, on a surface of its own
            val current = entry.id == session.historyId
            holder.title.setTextColor(if (current) primary else onSurface)
            holder.icon.imageTintList = ColorStateList.valueOf(if (current) primary else onSurfaceVariant)
            holder.itemView.setBackgroundResource(
                if (current) R.drawable.bg_agent_history_row_current else R.drawable.bg_agent_history_row
            )
            holder.subtitle.text = subtitleOf(entry)
            holder.pin.visibility = if (entry.pinned) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onOpen(entry) }
            holder.itemView.setOnLongClickListener {
                onMenu(entry, holder.itemView)
                true
            }
        }
    }

    // when the chat was last written to, how long it is, and the model of its last turn
    private fun subtitleOf(entry: AgentHistory.Entry): String {
        val time = DateUtils.getRelativeTimeSpanString(
            entry.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
        val count = activity.getString(R.string.agent_history_messages, entry.messages)
        return listOfNotNull(time, count, entry.model?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    // #endregion

    private fun showSettings() {
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_agent_settings, null)
        val providers = content.findViewById<RadioGroup>(R.id.agent_provider_group)
        val baseUrl = content.findViewById<TextInputEditText>(R.id.agent_base_url)
        val model = content.findViewById<TextInputEditText>(R.id.agent_model_name)
        val apiKey = content.findViewById<TextInputEditText>(R.id.agent_api_key)
        val note = content.findViewById<TextView>(R.id.agent_provider_note)
        val maxTokens = content.findViewById<TextInputEditText>(R.id.agent_max_tokens_value)
        val contextWindowLayout = content.findViewById<TextInputLayout>(R.id.agent_context_window_layout)
        val contextWindow = content.findViewById<TextInputEditText>(R.id.agent_context_window_value)
        val approvals = content.findViewById<RadioGroup>(R.id.agent_approval_group)
        val thinking = content.findViewById<MaterialAutoCompleteTextView>(R.id.agent_thinking_level)
        val thinkingNote = content.findViewById<TextView>(R.id.agent_thinking_level_note)

        // the levels of thinking the provider shown offers, and the one picked of them
        var thinkingLevels = emptyList<ThinkingLevel>()
        var thinkingLevel = ThinkingLevel.DEFAULT
        thinking.setOnItemClickListener { _, _, position, _ ->
            thinkingLevels.getOrNull(position)?.let { thinkingLevel = it }
        }

        fun fill(config: AgentSettings.Config) {
            baseUrl.setText(config.baseUrl)
            model.setText(config.model)
            apiKey.setText(config.apiKey)
            val anthropic = config.kind == AgentSettings.ProviderKind.ANTHROPIC
            note.setText(if (anthropic) R.string.agent_note_anthropic else R.string.agent_note_openai)
            thinkingLevels = config.kind.thinkingLevels
            thinkingLevel = config.kind.offered(config.thinking)
            thinking.setSimpleItems(thinkingLevels.map(::thinkingLabel).toTypedArray())
            // (false: not filtered by what it says, the whole list stays offered)
            thinking.setText(thinkingLabel(thinkingLevel), false)
            thinkingNote.setText(if (anthropic) R.string.agent_thinking_level_note_anthropic else R.string.agent_thinking_level_note_openai)
            // the provider's own, or empty; under it, what it comes to when empty
            contextWindow.setText(if (config.contextWindow > 0) config.contextWindow.toString() else "")
            val found = AgentSettings.contextWindow(activity, config.copy(contextWindow = 0))
            contextWindowLayout.helperText = if (config.model.isBlank()) {
                activity.getString(R.string.agent_context_window_auto_no_model)
            } else {
                activity.getString(R.string.agent_context_window_auto, String.format(Locale.getDefault(), "%,d", found), config.model.trim())
            }
        }

        val initial = AgentSettings.load(activity)
        var kind = initial.kind
        providers.check(if (kind == AgentSettings.ProviderKind.ANTHROPIC) R.id.agent_provider_anthropic else R.id.agent_provider_openai)
        fill(initial)
        maxTokens.setText(initial.maxTokens.toString())
        approvals.check(
            when (initial.approval) {
                AgentSettings.Approval.ASK -> R.id.agent_approval_ask
                AgentSettings.Approval.AUTO_EDITS -> R.id.agent_approval_auto_edits
                AgentSettings.Approval.AUTO_ALL -> R.id.agent_approval_auto_all
            }
        )
        // another provider: its own address, model and key
        providers.setOnCheckedChangeListener { _, id ->
            kind = if (id == R.id.agent_provider_anthropic) AgentSettings.ProviderKind.ANTHROPIC else AgentSettings.ProviderKind.OPENAI
            fill(AgentSettings.load(activity, kind))
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.agent_settings_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.agent_save) { _, _ ->
                val approval = when (approvals.checkedRadioButtonId) {
                    R.id.agent_approval_auto_edits -> AgentSettings.Approval.AUTO_EDITS
                    R.id.agent_approval_auto_all -> AgentSettings.Approval.AUTO_ALL
                    else -> AgentSettings.Approval.ASK
                }
                AgentSettings.save(
                    activity,
                    AgentSettings.Config(
                        kind = kind,
                        baseUrl = baseUrl.text?.toString().orEmpty().ifBlank { kind.defaultBaseUrl },
                        model = model.text?.toString().orEmpty(),
                        apiKey = apiKey.text?.toString().orEmpty(),
                        maxTokens = maxTokens.text?.toString()?.toIntOrNull()?.coerceIn(MIN_TOKENS, MAX_TOKENS)
                            ?: AgentSettings.DEFAULT_MAX_TOKENS,
                        approval = approval,
                        thinking = thinkingLevel,
                        // (empty, or not a number: found out)
                        contextWindow = contextWindow.text?.toString()?.trim()?.toIntOrNull()
                            ?.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW) ?: 0,
                    ),
                )
                updateHeader()
            }
            .show()
    }

    // #endregion

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_TOOL = 2
        const val TYPE_NOTICE = 3
        const val TYPE_PROGRESS = 4
        // of the window: the most the sheet gets, and the least (a short conversation: so the
        // sheet does not grow at each line of an answer, nor shrink on a new chat)
        // the menu of a row of the history, on a long press (showHistoryMenu)
        const val MENU_HISTORY_PIN = 1
        const val MENU_HISTORY_RENAME = 2
        const val MENU_HISTORY_DELETE = 3

        const val SHEET_HEIGHT = 0.85f
        const val SHEET_MIN_HEIGHT = 0.5f

        // how long the sheet takes to go down after a new chat
        const val RESET_ANIMATION_MS = 400L

        // the most of the screen the list of kept chats takes (it scrolls beyond that)
        const val HISTORY_HEIGHT = 0.5f
        const val THINKING_GAP_DP = 8f
        // how much of the thumbnails of the images attached (item_agent_attachment) is kept decoded
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024

        // A file attached to what is being written: its card set in from the top and the end
        // by this, ✕ (a 22dp circle in the middle of a 30dp button) on the card's corner, its
        // centre 4dp inside it; the name ending this far from the card's end (as far as ✕
        // reaches into the card), or less with no ✕.
        const val REMOVE_INSET_DP = 11f
        const val NAME_CLEAR_DP = 16f
        const val NAME_END_DP = 10f

        // a disabled button's text (Material's disabled opacity)
        const val DISABLED_TEXT_ALPHA = 0.38f

        // how many of the last lines a command printed its card shows while it runs
        const val LIVE_LINES = 12

        // how many rows scrolled off the screen are kept as they are (RecyclerView keeps 2)
        const val ROW_CACHE = 16

        // the thinking of a turn going on, unfolded, rendered again no more often than this
        const val THOUGHT_RENDER_MS = 250L

        // how much of colorOnSurfaceVariant the status of a turn has (statusColor)
        const val STATUS_ALPHA = 0.72f

        // how far above the status of a turn going on what is there fades out (StatusFade)
        const val STATUS_FADE_DP = 18f

        // the lines a message of the user shows before Show all
        const val BUBBLE_MAX_LINES = 15

        // how often the times under the messages of the user are said again (10 minutes ago)
        const val TIME_TICK_MS = 30_000L

        // the longest extension shown as a file's type (longer: what kind of file it is)
        const val MAX_TYPE_CHARS = 6

        // the room kept above and below the cursor of a message being edited when its box is
        // brought into sight (revealEditor)
        const val CURSOR_ROOM_DP = 8f
        val TOP_BARS = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        const val MAX_SHOWN_RESULT = 4000
        // the tools whose result is shown in a code block (the rest: a line of text)
        val CODE_RESULTS = setOf("read_file", "list_files", "search_files", "run_command")
        const val MIN_TOKENS = 256
        const val MAX_TOKENS = 128_000
        // the context window the user may set (AgentSettings.Config.contextWindow)
        const val MIN_CONTEXT_WINDOW = 2_000
        const val MAX_CONTEXT_WINDOW = 10_000_000
    }
}
