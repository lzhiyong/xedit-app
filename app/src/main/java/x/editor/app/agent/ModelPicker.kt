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

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import x.editor.app.R
import x.editor.app.view.MaxHeightRecyclerView
import com.google.android.material.R as MaterialR

/**
 * The model picker of the AI assistant: a drop-down under the model in the head of
 * AgentSheet ([anchor], the row of the model), opened by ▾ only. A search field, then the
 * models of the provider in use, and of the other one if it is set up too, each under its
 * name: first the ones used last (the one in use marked, and scrolled to), then the ones the
 * provider lists (ModelCatalog: asked for each time it opens; what it listed last time is
 * shown meanwhile). What is typed is matched against the API names and the names the
 * provider gives (also inside them), the matching part in bold. Picking one makes it, and
 * its provider, the one the assistant asks from the next request on ([onPicked]); a model
 * the list does not have is set in the settings (⚙). As wide as the row of the model, from
 * where the name begins to where ▾ ends; a long press on a model shows its whole name (cut
 * short in the row) in a bubble.
 *
 * It is a view over the sheet, in the sheet's window ([host]), and not a window of its own: a
 * focusable window takes the keyboard over from the sheet's, and gives it back when it goes,
 * and in between each is told the keyboard is gone (the sheet went down and up again, the
 * keyboard might not come back at all). In one window the keyboard only sees the focus go
 * from one field to another. It is never put away for the user: up when this opens, it stays
 * up, for the search field; up when this closes (called up here or not), it stays up, for the
 * field of the sheet ([focusBack]). Down when this opens, it stays down until the search field
 * is tapped. A tap outside, or Back ([back]), closes it. Main thread.
 */
internal class ModelPicker(
    private val activity: Activity,
    private val scope: CoroutineScope,
    /** What the drop-down is put in, over everything of the sheet. */
    private val host: ViewGroup,
    private val anchor: View,
    /** The field that takes the focus (and so the keyboard) from the search field when this closes. */
    private val focusBack: View,
    back: OnBackPressedDispatcher,
    private val onPicked: (AgentSettings.ProviderKind, String) -> Unit,
) {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = dismiss()
    }.also { back.addCallback(it) }

    // a row of the list
    private sealed interface Row {
        data class Header(val text: String) : Row
        data class Status(val text: String) : Row
        data class Model(
            val kind: AgentSettings.ProviderKind,
            val id: String,
            val displayName: String?,
            val current: Boolean,
            val recent: Boolean,
        ) : Row
    }

    // where the list of a provider is
    private sealed interface ListState {
        data object Loading : ListState
        data object Loaded : ListState
        data class Failed(val message: String) : ListState
    }

    // A provider of the picker: what is set up for it (without the API key: that is read
    // off the main thread, only when its list of models is asked for), the models it listed
    // (null: none yet), how the asking went
    private class Section(
        val settings: AgentSettings.Summary,
        val recent: List<String>,
        var models: List<ModelCatalog.Model>?,
        var state: ListState,
    )

    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(activity, attr, fallback)

    private val onSurface = color(MaterialR.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, 0xFFA0A0A0.toInt())
    private val primary = color(androidx.appcompat.R.attr.colorPrimary, 0xFF82AAFF.toInt())
    private val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer, 0xFF3D4A5C.toInt())
    private val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer, 0xFFE6E6E6.toInt())
    private val mono: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    private val sections = ArrayList<Section>()
    private val jobs = ArrayList<Job>()
    private var current: AgentSettings.Summary? = null
    private var shown: List<Row> = emptyList()
    private var query = ""

    // all over the window, catching a tap outside the card; the card ([content]) in it
    private var overlay: FrameLayout? = null
    private var content: View? = null
    private var list: MaxHeightRecyclerView? = null
    private var empty: TextView? = null
    private var search: TextInputEditText? = null

    // the head of the sheet laid out again (the keyboard came or went, the sheet changed
    // size): the drop-down kept under the row, and within the room it has
    private val onAnchorLayout = ViewTreeObserver.OnGlobalLayoutListener { refit() }

    /** Whether the drop-down is on screen. */
    val isShowing: Boolean get() = overlay != null

    fun show() {
        if (isShowing || !anchor.isAttachedToWindow) return
        // the keyboard up (for the sheet's field): kept up, for the search field
        val keyboardUp = ViewCompat.getRootWindowInsets(anchor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val inUse = AgentSettings.summary(activity)
        current = inUse
        // the provider in use, and the other if it is set up too (a model, a key)
        val providers = listOf(inUse) + AgentSettings.ProviderKind.entries
            .filter { it != inUse.kind }
            .map { AgentSettings.summary(activity, it) }
            .filter { it.isUsable }
        sections.clear()
        for (settings in providers) {
            sections += Section(
                settings = settings,
                recent = AgentSettings.recentModels(activity, settings.kind),
                models = AgentSettings.cachedModels(activity, settings.kind, settings.baseUrl),
                state = if (settings.isUsable) ListState.Loading else ListState.Failed(activity.getString(R.string.agent_not_set_up)),
            )
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.popup_model_picker, null)
        content = view
        val rows = view.findViewById<MaxHeightRecyclerView>(R.id.model_list)
        list = rows
        empty = view.findViewById(R.id.model_empty)
        val field = view.findViewById<TextInputEditText>(R.id.model_search)
        search = field
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        // Search on the keyboard: the one model left, picked; else the keyboard goes
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val models = shown.filterIsInstance<Row.Model>()
            // (the user's own act: the keyboard may go)
            if (models.size == 1) pick(models[0]) else hideKeyboard()
            true
        }
        rows.layoutManager = LinearLayoutManager(activity)
        rows.adapter = adapter
        // any touch on the list, a drag: the bubble of a whole name goes (the keyboard stays:
        // only the user puts it away)
        rows.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) hideFullName()
                return false
            }
        })
        rows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideFullName()
            }
        })
        filter("")
        rows.setMaxHeight(listMaxHeight())
        // a tap on the card (between its rows) is not a tap outside it
        view.isClickable = true
        val shade = FrameLayout(activity).apply {
            setOnClickListener { dismiss() }
            addView(view, FrameLayout.LayoutParams(popupWidth(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        host.addView(shade, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = shade
        moveUnderRow()
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_IN_MS).start()
        backCallback.isEnabled = true
        // the keyboard was up: the search field takes it over, and it does not so much as
        // flicker (asking for it again would restart it)
        if (keyboardUp) field.requestFocus()
        placed = IntArray(2).also(anchor::getLocationInWindow).let { Triple(it[0], it[1], anchor.width) }
        anchor.viewTreeObserver.addOnGlobalLayoutListener(onAnchorLayout)

        // the one in use in sight, a row above it shown too
        val at = shown.indexOfFirst { it is Row.Model && it.current }
        if (at > 0) (rows.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, dp(44))

        for (section in sections) if (section.state == ListState.Loading) jobs += scope.launch { load(section) }
    }

    fun dismiss() {
        val shade = overlay ?: return
        // The focus across first, and only then the field taken away: a window with no view
        // in focus is one the keyboard has nothing to work for.
        if (search?.hasFocus() == true) focusBack.requestFocus()
        overlay = null
        backCallback.isEnabled = false
        backCallback.remove()
        hideFullName()
        for (job in jobs) job.cancel()
        jobs.clear()
        if (anchor.viewTreeObserver.isAlive) anchor.viewTreeObserver.removeOnGlobalLayoutListener(onAnchorLayout)
        host.removeView(shade)
        content = null
        list = null
        empty = null
        search = null
    }

    // the list of [section]'s provider, asked for; the drop-down shows it once it is there
    private suspend fun load(section: Section) {
        try {
            // the key is read here, off the main thread (the Android Keystore is another process)
            val config = withContext(Dispatchers.IO) { AgentSettings.load(activity, section.settings.kind) }
            val models = ModelCatalog.fetch(config)
            section.models = models
            section.state = ListState.Loaded
            AgentSettings.cacheModels(activity, config.kind, config.baseUrl, models)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            section.state = ListState.Failed(e.message ?: e.toString())
        }
        if (isShowing) filter(query)
    }

    // The room the list has: from under the row down to the keyboard (or the navigation
    // bar), less the search field and the card around it; never less than a few rows.
    private fun listMaxHeight(): Int {
        val root = anchor.rootView
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val rootBottom = at[1] + root.height
        anchor.getLocationOnScreen(at)
        val anchorBottom = at[1] + anchor.height
        val ime = maxOf(imeOf(anchor), content?.let(::imeOf) ?: 0)
        val bars = ViewCompat.getRootWindowInsets(anchor)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val bottom = rootBottom - maxOf(ime, bars) - dp(MARGIN_DP)
        // the card's margins and paddings, and the search field
        val searchHeight = content?.findViewById<View>(R.id.model_search_layout)?.height?.takeIf { it > 0 } ?: dp(48)
        val chrome = dp(8 + 8 + 4 + 6 + 8) + searchHeight
        // at most a few rows (MAX_LIST_DP, and no more than MAX_LIST_SCREEN of the screen):
        // half a row cut off at the foot says there is more to scroll to
        val most = minOf(dp(MAX_LIST_DP), (activity.resources.displayMetrics.heightPixels * MAX_LIST_SCREEN).toInt())
        return (bottom - anchorBottom - chrome).coerceAtMost(most).coerceAtLeast(dp(MIN_LIST_DP))
    }

    private fun imeOf(view: View): Int =
        ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun refit() {
        if (!isShowing) return
        if (!anchor.isAttachedToWindow || !anchor.isShown) {
            dismiss()
            return
        }
        list?.setMaxHeight(listMaxHeight())
        // moved only if the row moved (the head is laid out again with every bit of an answer)
        val at = IntArray(2)
        anchor.getLocationInWindow(at)
        val place = Triple(at[0], at[1], anchor.width)
        if (place != placed) {
            placed = place
            hideFullName()
            moveUnderRow()
        }
    }

    // The card as wide as it should be and under the row: its margin keeps it a little under
    // the row, its left edge under the model's name. Moved rather than laid out again: within
    // a layout of the window (onAnchorLayout), so with the row, in the same frame.
    private fun moveUnderRow() {
        val shade = overlay ?: return
        val card = content ?: return
        val width = popupWidth()
        if (card.layoutParams.width != width) card.layoutParams = card.layoutParams.apply { this.width = width }
        val row = IntArray(2).also(anchor::getLocationInWindow)
        val origin = IntArray(2).also(shade::getLocationInWindow)
        card.translationX = (row[0] - origin[0] + xOffset()).toFloat()
        card.translationY = (row[1] + anchor.height - origin[1] - dp(4)).toFloat()
    }

    // The card as wide as the row of the model holds (the row less the padding on either
    // side: from where the name begins to where ▾ ends, which is under ⚙ in the title row),
    // plus the card's margins (room for the shadow).
    private fun popupWidth(): Int {
        val row = anchor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val name = row - anchor.paddingStart - anchor.paddingEnd
        return (name + dp(2 * CARD_MARGIN_DP)).coerceAtLeast(dp(160))
    }

    // the card's left edge where the name begins: the row's padding, less the card's margin
    private fun xOffset(): Int = anchor.paddingStart - dp(CARD_MARGIN_DP)

    // where the row was when the drop-down was put under it last (x, y in its window, width)
    private var placed: Triple<Int, Int, Int>? = null

    private fun hideKeyboard() {
        val field = search ?: return
        if (!field.hasFocus()) return
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    private fun pick(row: Row.Model) {
        dismiss()
        onPicked(row.kind, row.id)
    }

    // the models of a section in their order: the one in use, the ones used last, the ones listed
    private fun modelsOf(section: Section): List<ModelCatalog.Model> {
        val listed = section.models.orEmpty()
        val names = listed.associate { it.id to it.displayName }
        val first = buildList {
            val inUse = section.settings.model.trim()
            if (inUse.isNotEmpty() && section.settings.kind == current?.kind) add(inUse)
            addAll(section.recent)
        }.distinct()
        return first.map { ModelCatalog.Model(it, names[it]) } + listed.filter { it.id !in first }
    }

    // The rows for [text]: all of them for nothing; else the models whose names have it in
    // them, those whose API name starts with it first, under the name of their provider.
    private fun filter(text: String) {
        query = text.trim()
        val inUse = current
        shown = buildList {
            for (section in sections) {
                val kind = section.settings.kind
                var models = modelsOf(section).filter { matches(it) }
                if (query.isNotEmpty()) models = models.sortedBy { if (it.id.startsWith(query, ignoreCase = true)) 0 else 1 }
                val status = when (val state = section.state) {
                    ListState.Loading -> activity.getString(R.string.agent_models_loading)
                    // while searching, only what is still to come
                    is ListState.Failed -> if (query.isEmpty()) activity.getString(R.string.agent_models_failed, state.message) else null
                    ListState.Loaded -> if (query.isEmpty() && section.models.isNullOrEmpty()) activity.getString(R.string.agent_models_none) else null
                }
                if (models.isEmpty() && status == null) continue
                add(Row.Header(ProviderNames.of(activity, kind, section.settings.baseUrl)))
                for (model in models) {
                    add(
                        Row.Model(
                            kind = kind,
                            id = model.id,
                            displayName = model.displayName,
                            current = inUse != null && kind == inUse.kind && model.id == inUse.model.trim(),
                            recent = model.id in section.recent,
                        )
                    )
                }
                if (status != null) add(Row.Status(status))
            }
        }
        adapter.notifyDataSetChanged()
        val nothing = shown.none { it is Row.Model || it is Row.Status }
        empty?.isVisible = nothing
        list?.isVisible = !nothing
    }

    private fun matches(model: ModelCatalog.Model): Boolean =
        query.isEmpty() || model.id.contains(query, ignoreCase = true) ||
            model.displayName?.contains(query, ignoreCase = true) == true

    // what was searched for, in bold and the accent color
    private fun highlighted(text: String): CharSequence {
        if (query.isEmpty()) return text
        val at = text.indexOf(query, ignoreCase = true)
        if (at < 0) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(primary), at, at + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size

        override fun getItemViewType(position: Int) = when (shown[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Status -> TYPE_STATUS
            is Row.Model -> TYPE_MODEL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_MODEL -> ModelHolder(inflater.inflate(R.layout.item_model_option, parent, false))
                else -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_picker_header, parent, false)) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = shown[position]) {
                is Row.Header -> (holder.itemView as TextView).apply {
                    text = row.text
                    setTextColor(primary)
                    setPadding(dp(12), dp(10), dp(12), dp(2))
                }
                is Row.Status -> (holder.itemView as TextView).apply {
                    text = row.text
                    setTextColor(onSurfaceVariant)
                    setPadding(dp(12), dp(6), dp(12), dp(8))
                }
                is Row.Model -> (holder as ModelHolder).bind(row)
            }
        }
    }

    private inner class ModelHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.model_title)
        private val subtitle = view.findViewById<TextView>(R.id.model_subtitle)
        private val tag = view.findViewById<TextView>(R.id.model_tag)

        fun bind(row: Row.Model) {
            title.text = highlighted(row.id)
            title.typeface = mono
            title.setTextColor(onSurface)
            val name = row.displayName?.takeIf { it.isNotBlank() && it != row.id }
            subtitle.isVisible = name != null
            subtitle.text = name?.let(::highlighted)

            val label = when {
                row.current -> activity.getString(R.string.agent_model_current)
                row.recent -> activity.getString(R.string.agent_model_recent)
                else -> null
            }
            tag.isVisible = label != null
            tag.text = label
            tag.setTextColor(onSecondaryContainer)
            tag.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(secondaryContainer)
            }

            // the one in use on a tinted, rounded ground; all with a rounded ripple
            val shape = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (row.current) ColorUtils.setAlphaComponent(secondaryContainer, 0xCC) else 0)
            }
            val mask = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFFFFFFFF.toInt())
            }
            itemView.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x1F)), shape, mask)
            itemView.setOnClickListener { pick(row) }
            // the whole name (the row cuts it short in the middle); the haptic tick comes with it
            itemView.setOnLongClickListener {
                showFullName(itemView, row)
                true
            }
        }
    }

    // #region the whole name of a model (long press)

    private var bubble: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideBubble = Runnable { hideFullName() }

    // A bubble over [row] (under it if there is no room above) with the whole API name of the
    // model and the name its provider gives it, wrapped rather than cut, as wide as the screen
    // allows; gone after FULL_NAME_MS, or at the next touch on the list. A sub-panel of the
    // sheet's window, so above the drop-down, which is in that window.
    private fun showFullName(row: View, model: Row.Model) {
        hideFullName()
        if (!anchor.isAttachedToWindow) return
        val root = anchor.rootView
        val maxWidth = root.width - dp(2 * BUBBLE_SIDE_DP)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(MaterialR.attr.colorSurfaceInverse, 0xFFE6E6E6.toInt()))
            }
            elevation = dp(4).toFloat()
        }
        val onInverse = color(MaterialR.attr.colorOnSurfaceInverse, 0xFF1E1E1E.toInt())
        box.addView(TextView(activity).apply {
            text = model.id
            typeface = mono
            textSize = 13f
            setTextColor(onInverse)
            setTextIsSelectable(false)
        })
        model.displayName?.takeIf { it.isNotBlank() && it != model.id }?.let { name ->
            box.addView(TextView(activity).apply {
                text = name
                textSize = 12f
                setTextColor(ColorUtils.setAlphaComponent(onInverse, 0xC0))
                setPadding(0, dp(2), 0, 0)
            })
        }
        box.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = box.measuredWidth
        val height = box.measuredHeight

        // where the row is in the sheet's window
        val rootAt = IntArray(2).also(root::getLocationOnScreen)
        val rowAt = IntArray(2).also(row::getLocationOnScreen)
        val left = rowAt[0] - rootAt[0]
        val top = rowAt[1] - rootAt[1]
        val topBars = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        val x = (left + dp(4)).coerceAtMost(root.width - dp(BUBBLE_SIDE_DP) - width).coerceAtLeast(dp(BUBBLE_SIDE_DP))
        val above = top - height - dp(4)
        val y = if (above >= topBars + dp(4)) above else top + row.height + dp(4)

        bubble = PopupWindow(box, width, height, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isTouchable = false
            isClippingEnabled = false
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            animationStyle = android.R.style.Animation_Toast
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
        handler.postDelayed(hideBubble, FULL_NAME_MS)
    }

    private fun hideFullName() {
        handler.removeCallbacks(hideBubble)
        bubble?.dismiss()
        bubble = null
    }

    // #endregion

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_STATUS = 1
        const val TYPE_MODEL = 2

        // the list: never shorter than about two rows and a half, never taller than about
        // four and a half (a row 44dp, 56 with the provider's name of the model)
        const val MIN_LIST_DP = 120
        const val MAX_LIST_DP = 240

        // nor taller than this part of the screen (a small phone, landscape)
        const val MAX_LIST_SCREEN = 0.32f

        // kept off the keyboard (or the bottom of the screen)
        const val MARGIN_DP = 8

        // the margin around the card (room for its shadow): popupWidth and xOffset put the
        // card itself over where the model's name is
        const val CARD_MARGIN_DP = 8

        // how long the card takes to fade in
        const val FADE_IN_MS = 120L

        // the bubble with a model's whole name: how long it stays, how far off the screen's sides
        const val FULL_NAME_MS = 3000L
        const val BUBBLE_SIDE_DP = 16
    }
}
