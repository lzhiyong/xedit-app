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

package x.editor.app.console

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import x.editor.app.AppSettings
import x.editor.app.R
import x.editor.app.lsp.Termux
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The console, as VS Code's panel: a bottom sheet with
 *
 *  - **Output**: the lines of a channel (chips to choose it): X Editor, the language
 *    servers, each server's own log, builds; coloured by level, followed as they come;
 *  - **Errors**: the diagnostics of the language servers in the open documents, and
 *    the errors of the last build; a tap goes there;
 *  - **Build**: a command run in Termux in the directory of the file (compile it, run it),
 *    what it printed, its errors into the errors tab.
 *
 * [lspProblems] gives the diagnostics of the open documents; [open] goes to a place.
 */
internal class ConsoleSheet(
    private val activity: Activity,
    private val currentFile: File?,
    private val lspProblems: () -> List<Problem>,
    private val open: (file: File, line: Int, column: Int) -> Unit,
    /** A command to run at once, in the build tab ("compile and run"). */
    private val runNow: String? = null,
) : ConsoleLog.Listener {

    private val view = LayoutInflater.from(activity).inflate(R.layout.sheet_console, null)
    private val header = view.findViewById<View>(R.id.console_header)

    // The sheet moves, and closes, only with a drag that starts on its header. Dragging
    // anywhere moved it before, so scrolling the log back to its top went on to pull the
    // sheet down and close it. Whether a gesture may drag is decided on its ACTION_DOWN,
    // here, before the sheet's behavior sees it (it intercepts in its CoordinatorLayout, a
    // parent of the header, so the header's own touch listener would come too late). A
    // gesture that does not start on the header can neither drag the sheet nor hide it.
    private val dialog = object : BottomSheetDialog(activity) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val drag = onHeader(ev)
                behavior.isDraggable = drag
                behavior.isHideable = drag
            }
            return super.dispatchTouchEvent(ev)
        }
    }
    private val tabs = view.findViewById<TabLayout>(R.id.console_tabs)
    private val outputPage = view.findViewById<View>(R.id.console_output_page)
    private val channelChips = view.findViewById<ChipGroup>(R.id.console_channels)
    private val outputScroll = view.findViewById<NestedScrollView>(R.id.console_output_scroll)
    private val outputText = view.findViewById<TextView>(R.id.console_output_text)
    private val problemsPage = view.findViewById<View>(R.id.console_problems_page)
    private val problemsList = view.findViewById<ListView>(R.id.console_problems)
    private val problemsEmpty = view.findViewById<TextView>(R.id.console_problems_empty)
    private val buildPage = view.findViewById<View>(R.id.console_build_page)
    private val commandField = view.findViewById<TextInputEditText>(R.id.console_command)
    private val runButton = view.findViewById<MaterialButton>(R.id.console_run)
    private val buildScroll = view.findViewById<NestedScrollView>(R.id.console_build_scroll)
    private val buildText = view.findViewById<TextView>(R.id.console_build_text)

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dim = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF8B949E.toInt())
    private val errorColor = 0xFFE5534B.toInt()
    private val warningColor = 0xFFD4A72C.toInt()
    private val infoColor = 0xFF4FA3E0.toInt()

    // The lines of the output, a little darker than the text of the app (a log is read
    // less than it is scanned), their times darker still; from the theme's text colour, so
    // that it holds in a light theme too.
    private val onSurface = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, 0xFFE6E6E6.toInt())
    private val lineColor = ColorUtils.setAlphaComponent(onSurface, 0xB3)
    private val stampColor = ColorUtils.setAlphaComponent(onSurface, 0x73)
    private val lineErrorColor = ColorUtils.setAlphaComponent(errorColor, 0xD9)
    private val lineWarningColor = ColorUtils.setAlphaComponent(warningColor, 0xD9)

    // the console's font, JetBrains Mono (as in the layout), else the system's monospace
    private val mono: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(activity, R.font.jetbrains_mono_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    private var problems: List<Problem> = emptyList()

    fun show() {
        // the pages share the height a sheet expanded over half the screen has
        val height = (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        for (part in listOf(outputScroll, problemsList, buildScroll)) part.layoutParams.height = height
        // The pages scroll on their own: they do not hand the scroll they cannot use (pulling
        // down at the top) to the sheet, which would move it. Only the header moves it.
        for (scroll in listOf(outputScroll, buildScroll)) scroll.isNestedScrollingEnabled = false

        for (title in listOf(R.string.console_output, R.string.console_problems, R.string.console_build)) {
            tabs.addTab(tabs.newTab().setText(title))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        for (channel in ConsoleLog.channels()) addChip(channel)
        render(outputText, outputScroll, ConsoleLog.entries(shownChannel))
        render(buildText, buildScroll, ConsoleLog.entries(ConsoleLog.BUILD))
        refreshProblems()
        problemsList.setOnItemClickListener { _, _, position, _ ->
            val problem = problems.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            open(problem.file, problem.line, problem.column)
        }

        // the command of the file's kind ("compile and run"), else the last one typed here
        commandField.setText(runNow ?: currentFile?.let { RunCommands.commandFor(activity, it) }
            ?: AppSettings.preferences(activity).getString(PREF_COMMAND, null).orEmpty())
        commandField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runCommand()
                true
            } else {
                false
            }
        }
        runButton.setOnClickListener { runCommand() }
        runButton.isEnabled = !BuildRunner.running
        view.findViewById<View>(R.id.console_copy).setOnClickListener { copyPage() }
        view.findViewById<View>(R.id.console_clear).setOnClickListener { clearPage() }

        if (runNow != null) lastTab = 2
        showPage(lastTab)
        tabs.getTabAt(lastTab)?.select()
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // neither dragged nor hidden until a gesture starts on the header (see dialog)
        dialog.behavior.isDraggable = false
        dialog.behavior.isHideable = false
        ConsoleLog.addListener(this)
        dialog.setOnDismissListener { ConsoleLog.removeListener(this) }
        dialog.show()
        if (runNow != null) runCommand()
    }

    // whether [ev] (on the screen) is on the header
    private fun onHeader(ev: MotionEvent): Boolean {
        if (!header.isShown) return false
        val at = IntArray(2)
        header.getLocationOnScreen(at)
        return ev.rawX >= at[0] && ev.rawX < at[0] + header.width && ev.rawY >= at[1] && ev.rawY < at[1] + header.height
    }

    private fun showPage(index: Int) {
        lastTab = index
        outputPage.visibility = if (index == 0) View.VISIBLE else View.GONE
        problemsPage.visibility = if (index == 1) View.VISIBLE else View.GONE
        buildPage.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 1) refreshProblems()
    }

    // #region output

    private fun addChip(channel: String) {
        for (i in 0 until channelChips.childCount) if ((channelChips.getChildAt(i) as? Chip)?.text == channel) return
        val chip = Chip(activity).apply {
            text = channel
            isCheckable = true
            isChecked = channel == shownChannel
            setOnClickListener {
                shownChannel = channel
                for (i in 0 until channelChips.childCount) (channelChips.getChildAt(i) as? Chip)?.let { it.isChecked = it.text == channel }
                render(outputText, outputScroll, ConsoleLog.entries(channel))
            }
        }
        channelChips.addView(chip)
    }

    override fun onAppended(channel: String, entry: ConsoleLog.Entry) {
        addChip(channel)
        if (channel == shownChannel) append(outputText, outputScroll, entry)
        if (channel == ConsoleLog.BUILD) append(buildText, buildScroll, entry)
    }

    override fun onCleared(channel: String) {
        if (channel == shownChannel) render(outputText, outputScroll, emptyList())
        if (channel == ConsoleLog.BUILD) render(buildText, buildScroll, emptyList())
    }

    private fun render(text: TextView, scroll: NestedScrollView, entries: List<ConsoleLog.Entry>) {
        val out = SpannableStringBuilder()
        for (entry in entries) out.append(line(entry))
        if (entries.isEmpty()) {
            text.text = activity.getString(R.string.console_empty)
            text.setTextColor(dim)
        } else {
            text.setText(out, TextView.BufferType.EDITABLE)
            text.setTextColor(lineColor)
        }
        scroll.post { scroll.scrollTo(0, text.height) }
    }

    // a line added at the end, followed if the end was in sight
    private fun append(text: TextView, scroll: NestedScrollView, entry: ConsoleLog.Entry) {
        val atEnd = scroll.scrollY + scroll.height >= text.height - text.lineHeight * 2
        if (text.text.toString() == activity.getString(R.string.console_empty)) {
            render(text, scroll, listOf(entry))
            return
        }
        text.append(line(entry))
        if (atEnd) scroll.post { scroll.scrollTo(0, text.height) }
    }

    private fun line(entry: ConsoleLog.Entry): CharSequence {
        val out = SpannableStringBuilder()
        val stamp = synchronized(time) { time.format(Date(entry.time)) } + "  "
        out.append(stamp)
        out.setSpan(ForegroundColorSpan(stampColor), 0, stamp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = out.length
        // the lines after the first (a stack trace, what a build printed) start under the
        // text, not under the time: in a monospace font, the width of the time in spaces
        out.append(entry.text.trimEnd('\n').replace("\n", "\n" + " ".repeat(stamp.length))).append('\n')
        val color = when (entry.level) {
            ConsoleLog.Level.ERROR -> lineErrorColor
            ConsoleLog.Level.WARNING -> lineWarningColor
            ConsoleLog.Level.DEBUG -> stampColor
            ConsoleLog.Level.INFO -> null
        }
        if (color != null) out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    // #endregion

    // #region errors (the problems: diagnostics and build errors)

    private fun refreshProblems() {
        problems = (lspProblems() + BuildRunner.problems)
            .sortedWith(compareBy<Problem> { it.severity }.thenBy { it.file.path }.thenBy { it.line })
        problemsList.adapter = object : ArrayAdapter<Problem>(activity, 0, problems) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = problemRow(problems[position])
        }
        problemsEmpty.visibility = if (problems.isEmpty()) View.VISIBLE else View.GONE
        problemsList.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        val tab = tabs.getTabAt(1) ?: return
        if (problems.isEmpty()) tab.removeBadge() else tab.orCreateBadge.number = problems.size
    }

    private fun problemRow(problem: Problem): View {
        val density = activity.resources.displayMetrics.density
        val (mark, color) = when (problem.severity) {
            1 -> "✖" to errorColor
            2 -> "▲" to warningColor
            3 -> "ℹ" to infoColor
            else -> "•" to dim
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }
        val icon = TextView(activity).apply {
            text = mark
            setTextColor(color)
            gravity = Gravity.CENTER
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val message = TextView(activity).apply {
            text = problem.message
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val place = TextView(activity).apply {
            text = "${problem.file.name}:${problem.line}:${problem.column} · ${problem.source}"
            textSize = 12f
            setTextColor(dim)
            typeface = mono
        }
        texts.addView(message)
        texts.addView(place)
        row.addView(icon, LinearLayout.LayoutParams((24 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    // #endregion

    // #region build

    private fun runCommand() {
        val command = commandField.text?.toString()?.trim().orEmpty()
        if (command.isEmpty() || BuildRunner.running) return
        AppSettings.preferences(activity).edit().putString(PREF_COMMAND, command).apply()
        if (!Termux.isInstalled(activity)) {
            ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.ERROR, activity.getString(R.string.console_no_termux))
            return
        }
        if (!Termux.hasPermission(activity)) {
            ActivityCompat.requestPermissions(activity, arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
            ConsoleLog.append(ConsoleLog.BUILD, ConsoleLog.Level.WARNING, activity.getString(R.string.console_termux_permission))
            return
        }
        try {
            BuildRunner.run(activity, command, currentFile?.parentFile) { result ->
                runButton.isEnabled = true
                refreshProblems()
                if (result.exitCode != 0 && BuildRunner.problems.isNotEmpty()) tabs.getTabAt(1)?.orCreateBadge?.number = problems.size
            }
            runButton.isEnabled = false
        } catch (e: IOException) {
            runButton.isEnabled = true
        }
    }

    // #endregion

    private fun copyPage() {
        val text = when (lastTab) {
            0 -> ConsoleLog.entries(shownChannel).joinToString("\n") { it.text }
            1 -> problems.joinToString("\n") { "${it.file.path}:${it.line}:${it.column}: ${it.message} (${it.source})" }
            else -> ConsoleLog.entries(ConsoleLog.BUILD).joinToString("\n") { it.text }
        }
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("console", text))
        Toast.makeText(activity, R.string.console_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearPage() {
        when (lastTab) {
            0 -> ConsoleLog.clear(shownChannel)
            2 -> ConsoleLog.clear(ConsoleLog.BUILD)
        }
    }

    private companion object {
        const val PREF_COMMAND = "console_build_command"
        const val REQUEST_TERMUX = 0x160
        // what was shown last, shown again
        var shownChannel = ConsoleLog.LSP
        var lastTab = 0
    }
}
