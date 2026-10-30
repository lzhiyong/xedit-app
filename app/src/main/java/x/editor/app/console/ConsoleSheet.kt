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
import android.os.Handler
import android.os.Looper
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
import x.editor.app.toolchain.Runner
import x.editor.app.toolchain.Toolchain
import x.editor.app.view.ConsoleScrollView
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import x.editor.app.console.BuildRunner.Target

/**
 * The console, as VS Code's panel: a bottom sheet with
 *
 *  - **Output**: the lines of a channel (chips to choose it): X Editor, the language
 *    servers, each server's own log, builds; coloured by level, followed as they come;
 *  - **Errors**: the diagnostics of the language servers for the file shown, and the
 *    errors of the last build if it was of that file; a tap goes there;
 *  - **Build**: a command run in the root of the file's project ([project], see
 *    ProjectRoot; compile it, run it, build the project), with the app's own toolchain
 *    or in Termux, what it printed as it prints it, its errors into the errors tab. The
 *    builds of other projects (BuildRunner.Target) go on side by side: a chip each, with
 *    how long it has been running; a tap shows it, its Stop stops it.
 *
 * [lspProblems] gives the diagnostics of the open documents; [open] goes to a place.
 */
internal class ConsoleSheet(
    private val activity: Activity,
    private val currentFile: File?,
    /** [currentFile]'s project (ProjectRoot): its commands run in its root. */
    private val project: ProjectRoot.Project?,
    private val lspProblems: () -> List<Problem>,
    private val open: (file: File, line: Int, column: Int) -> Unit,
    /** A command to run at once, in the build tab ("compile and run"). */
    private val runNow: String? = null,
    /** Opens on the build tab (its build is going on: "compile and run" while it runs). */
    private val showBuild: Boolean = runNow != null,
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
    private val outputScroll = view.findViewById<ConsoleScrollView>(R.id.console_output_scroll)
    private val outputText = view.findViewById<TextView>(R.id.console_output_text)
    private val problemsPage = view.findViewById<View>(R.id.console_problems_page)
    private val problemsList = view.findViewById<ListView>(R.id.console_problems)
    private val problemsEmpty = view.findViewById<TextView>(R.id.console_problems_empty)
    private val buildPage = view.findViewById<View>(R.id.console_build_page)
    private val commandField = view.findViewById<TextInputEditText>(R.id.console_command)
    private val runButton = view.findViewById<MaterialButton>(R.id.console_run)
    private val buildScroll = view.findViewById<ConsoleScrollView>(R.id.console_build_scroll)
    private val buildText = view.findViewById<TextView>(R.id.console_build_text)
    private val jobChips = view.findViewById<ChipGroup>(R.id.console_jobs)

    // what the file shown builds (its project, or itself), where its commands run
    private val target: Target = BuildRunner.targetOf(currentFile, project)

    // the build the build tab shows: the file's, or another going on (its chip tapped)
    private var shownTarget: Target = target
    private var shownBuildChannel: String = BuildRunner.channelOf(target)

    private val buildListener = BuildRunner.Listener { job -> onJobChanged(job) }

    // While builds go on, their chips say for how long, once a second.
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshJobs()
            if (dialog.isShowing && BuildRunner.jobs().any { it.running }) ticker.postDelayed(this, 1000)
        }
    }

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
        // "compile and run": the build starts on a clean channel, its errors replace the
        // last ones; not while one of the same project goes on (one at a time)
        val fresh = runNow != null && !BuildRunner.isRunning(target)

        // The pages are all as high as a sheet expanded over half the screen: the page as
        // a whole, not its text, so that the sheet keeps its height from tab to tab (the
        // build page has the command above its text, the errors may be just "no errors").
        val height = (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        for (page in listOf(outputPage, problemsPage, buildPage)) page.layoutParams.height = height
        // The pages scroll on their own: they do not hand the scroll they cannot use (pulling
        // down at the top) to the sheet, which would move it. Only the header moves it.
        for (scroll in listOf(outputScroll, buildScroll)) scroll.isNestedScrollingEnabled = false

        for (title in listOf(R.string.console_tab_output, R.string.console_tab_problems, R.string.console_tab_build)) {
            tabs.addTab(tabs.newTab().setText(title))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        for (channel in ConsoleLog.channels()) addChip(channel)
        render(outputText, outputScroll, ConsoleLog.entries(shownChannel), stamped = !ConsoleLog.isBuildChannel(shownChannel))
        render(buildText, buildScroll, ConsoleLog.entries(shownBuildChannel), stamped = false)
        refreshProblems()
        problemsList.setOnItemClickListener { _, _, position, _ ->
            val problem = problems.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            open(problem.file, problem.line, problem.column)
        }

        commandField.setText(commandFor(target))
        commandField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                runCommand()
                true
            } else {
                false
            }
        }
        runButton.setOnClickListener {
            // Run, or Stop the build shown while it goes on
            val job = BuildRunner.job(shownTarget)
            if (job?.running == true) {
                BuildRunner.stop(activity, job)
                updateRunButton()
            } else {
                runCommand()
            }
        }
        updateRunButton()
        refreshJobs()
        view.findViewById<View>(R.id.console_copy).setOnClickListener { copyPage() }
        view.findViewById<View>(R.id.console_clear).setOnClickListener { clearPage() }

        if (showBuild) lastTab = 2
        showPage(lastTab)
        tabs.getTabAt(lastTab)?.select()
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // neither dragged nor hidden until a gesture starts on the header (see dialog)
        dialog.behavior.isDraggable = false
        dialog.behavior.isHideable = false
        ConsoleLog.addListener(this)
        BuildRunner.addListener(buildListener)
        BuildRunner.setShown(shownTarget, true)
        dialog.setOnDismissListener {
            ConsoleLog.removeListener(this)
            BuildRunner.removeListener(buildListener)
            BuildRunner.setShown(shownTarget, false)
            ticker.removeCallbacks(tick)
        }
        dialog.show()
        if (fresh) runCommand(runNow.orEmpty(), fresh = true)
        startTicker()
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
        when (index) {
            0 -> outputScroll.followEnd()
            2 -> buildScroll.followEnd()
        }
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
                render(outputText, outputScroll, ConsoleLog.entries(channel), stamped = !ConsoleLog.isBuildChannel(channel))
            }
        }
        channelChips.addView(chip)
    }

    override fun onAppended(channel: String, entry: ConsoleLog.Entry) {
        addChip(channel)
        if (channel == shownChannel) append(outputText, outputScroll, entry, stamped = !ConsoleLog.isBuildChannel(channel))
        if (channel == shownBuildChannel) append(buildText, buildScroll, entry, stamped = false)
    }

    override fun onCleared(channel: String) {
        if (channel == shownChannel) render(outputText, outputScroll, emptyList())
        if (channel == shownBuildChannel) render(buildText, buildScroll, emptyList())
    }

    // [stamped]: each line with its time; not those of a build (its first and last lines
    // say when it started and ended)
    private fun render(text: TextView, scroll: ConsoleScrollView, entries: List<ConsoleLog.Entry>, stamped: Boolean = true) {
        val out = SpannableStringBuilder()
        for (entry in entries) out.append(line(entry, stamped))
        if (entries.isEmpty()) {
            text.text = activity.getString(R.string.console_empty)
            text.setTextColor(dim)
        } else {
            text.setText(out, TextView.BufferType.EDITABLE)
            text.setTextColor(lineColor)
        }
        // a page shown anew (another channel, another build, cleared) starts at its end
        scroll.followEnd()
    }

    // a line added at the end; the page goes down with it if it follows its end (when the
    // text is laid out again, with its new height: ConsoleScrollView)
    private fun append(text: TextView, scroll: ConsoleScrollView, entry: ConsoleLog.Entry, stamped: Boolean = true) {
        if (text.text.toString() == activity.getString(R.string.console_empty)) {
            render(text, scroll, listOf(entry), stamped)
            return
        }
        text.append(line(entry, stamped))
    }

    private fun line(entry: ConsoleLog.Entry, stamped: Boolean = true): CharSequence {
        val out = SpannableStringBuilder()
        val stamp = if (stamped) synchronized(time) { time.format(Date(entry.time)) } + "  " else ""
        out.append(stamp)
        if (stamp.isNotEmpty()) out.setSpan(ForegroundColorSpan(stampColor), 0, stamp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = out.length
        // the lines after the first (a stack trace) start under the text, not under the
        // time: in a monospace font, the width of the time in spaces. A build's lines (no
        // times) are indented under its start and end lines instead.
        val body = if (stamped) entry.text.trimEnd('\n').replace("\n", "\n" + " ".repeat(stamp.length))
        else ConsoleLog.buildText(entry.copy(text = entry.text.trimEnd('\n')))
        out.append(body).append('\n')
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

    // The problems of the file shown: its diagnostics (not those of the other open
    // documents), and the errors of the last build of what it builds (the file, which may
    // include a header with them; or its project). With no local file shown: all
    // diagnostics, no build.
    private fun refreshProblems() {
        val file = currentFile
        val diagnostics = lspProblems().let { all ->
            if (file == null) all else all.filter { sameFile(it.file, file) }
        }
        val buildProblems = if (file != null) BuildRunner.job(target)?.problems.orEmpty() else emptyList()
        problems = (diagnostics + buildProblems)
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

    private fun sameFile(a: File, b: File): Boolean {
        if (a == b) return true
        val pathA = runCatching { a.canonicalPath }.getOrElse { a.absolutePath }
        val pathB = runCatching { b.canonicalPath }.getOrElse { b.absolutePath }
        return pathA == pathB
    }

    // #endregion

    // #region build

    // The command shown for [t]: the one running; for the file's own, the one "compile
    // and run" started, else that of the file's kind; for another, the one it ran last;
    // else the last typed.
    private fun commandFor(t: Target): String {
        BuildRunner.job(t)?.takeIf { it.running }?.let { return it.command }
        if (t.key == target.key) {
            runNow?.let { return it }
            currentFile?.let { RunCommands.commandFor(activity, it, project) }?.let { return it }
        }
        BuildRunner.job(t)?.command?.takeIf { it.isNotEmpty() }?.let { return it }
        return AppSettings.preferences(activity).getString(PREF_COMMAND, null).orEmpty()
    }

    // Runs the command of the field for the build shown, in its directory, where the
    // toolchain says: with the app's own, or in Termux (which must be there, and let it).
    // [fresh]: on a clean channel. Not while a build of the same project goes on.
    private fun runCommand(command: String = commandField.text?.toString()?.trim().orEmpty(), fresh: Boolean = false) {
        val t = shownTarget
        if (command.isEmpty() || BuildRunner.isRunning(t)) return
        AppSettings.preferences(activity).edit().putString(PREF_COMMAND, command).apply()
        val channel = BuildRunner.channelOf(t)
        if (fresh) ConsoleLog.clear(channel)
        // what this build prints is to be seen as it comes, even if the page was scrolled up
        buildScroll.followEnd()
        val runner = Toolchain.runnerFor(activity, command)
        if (runner == Runner.TERMUX) {
            if (!Termux.isInstalled(activity)) {
                ConsoleLog.append(channel, ConsoleLog.Level.ERROR, activity.getString(R.string.console_termux_missing))
                return
            }
            if (!Termux.hasPermission(activity)) {
                ActivityCompat.requestPermissions(activity, arrayOf(Termux.PERMISSION), REQUEST_TERMUX)
                ConsoleLog.append(channel, ConsoleLog.Level.WARNING, activity.getString(R.string.console_termux_permission))
                return
            }
        }
        try {
            BuildRunner.run(activity, t, command, runner)
        } catch (e: IOException) {
            // said in its channel
        }
        updateRunButton()
        refreshJobs()
        startTicker()
    }

    // Shows the build of [t] (its chip tapped): its lines, its command, its Run or Stop.
    private fun showTarget(t: Target) {
        if (t.key == shownTarget.key) return
        BuildRunner.setShown(shownTarget, false)
        shownTarget = t
        shownBuildChannel = BuildRunner.channelOf(t)
        BuildRunner.setShown(t, true)
        render(buildText, buildScroll, ConsoleLog.entries(shownBuildChannel), stamped = false)
        commandField.setText(commandFor(t))
        updateRunButton()
        refreshJobs()
    }

    // A build started, was asked to stop, or ended.
    private fun onJobChanged(job: BuildRunner.Job) {
        refreshJobs()
        if (job.target.key == shownTarget.key) updateRunButton()
        if (job.target.key == target.key && !job.running) refreshProblems()
        if (job.running) startTicker()
    }

    private fun startTicker() {
        ticker.removeCallbacks(tick)
        if (BuildRunner.jobs().any { it.running }) ticker.postDelayed(tick, 1000)
    }

    // The chips of the builds: the file's own first, then the others built (in the order
    // they first ran), each with whether it runs and for how long, or how it ended.
    private fun refreshJobs() {
        val targets = LinkedHashMap<String, Target>()
        targets[target.key] = target
        for (job in BuildRunner.jobs()) targets.putIfAbsent(job.target.key, job.target)
        targets.putIfAbsent(shownTarget.key, shownTarget)
        val chips = HashMap<String, Chip>()
        for (i in 0 until jobChips.childCount) {
            val chip = jobChips.getChildAt(i) as? Chip ?: continue
            (chip.tag as? String)?.let { chips[it] = chip }
        }
        for ((key, t) in targets) {
            val chip = chips[key] ?: Chip(activity).apply {
                tag = key
                isCheckable = true
                setEnsureMinTouchTargetSize(false)
                setOnClickListener { showTarget(t) }
                jobChips.addView(this)
            }
            val job = BuildRunner.job(t)
            chip.text = when {
                job == null || job.startedAt == 0L -> t.label
                job.running && job.stopping -> "■ ${t.label}  ${BuildRunner.formatElapsed(job.elapsed)}"
                job.running -> "● ${t.label}  ${BuildRunner.formatElapsed(job.elapsed)}"
                job.exitCode == 0 -> "✓ ${t.label}  ${BuildRunner.formatElapsed(job.elapsed)}"
                else -> "✗ ${t.label}  ${BuildRunner.formatElapsed(job.elapsed)}"
            }
            chip.setTextColor(
                when {
                    job == null || job.running || job.startedAt == 0L -> onSurface
                    job.exitCode == 0 -> onSurface
                    else -> errorColor
                }
            )
            chip.isChecked = key == shownTarget.key
        }
    }

    // Run, or Stop while the build shown goes on (a second Stop of one in Termux: no
    // longer waited for); the command is not changed while it runs
    private fun updateRunButton() {
        val job = BuildRunner.job(shownTarget)
        val running = job?.running == true
        runButton.text = activity.getString(
            when {
                !running -> R.string.console_action_run
                job?.stopping == true && job.runner == Runner.TERMUX -> R.string.console_action_give_up
                else -> R.string.console_action_stop
            }
        )
        runButton.isEnabled = true
        commandField.isEnabled = !running
    }

    // #endregion

    private fun copyPage() {
        val text = when (lastTab) {
            0 -> ConsoleLog.entries(shownChannel).joinToString("\n") {
                if (ConsoleLog.isBuildChannel(shownChannel)) ConsoleLog.buildText(it) else it.text
            }
            1 -> problems.joinToString("\n") { "${it.file.path}:${it.line}:${it.column}: ${it.message} (${it.source})" }
            else -> ConsoleLog.entries(shownBuildChannel).joinToString("\n") { ConsoleLog.buildText(it) }
        }
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("console", text))
        Toast.makeText(activity, R.string.console_copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearPage() {
        when (lastTab) {
            0 -> ConsoleLog.clear(shownChannel)
            2 -> ConsoleLog.clear(shownBuildChannel)
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
