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

package x.editor.app.activity

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.recyclerview.widget.RecyclerView

import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils

import kotlin.math.roundToInt

import x.editor.app.R
import x.editor.app.settings.AppSettingsFragment
import x.editor.app.settings.BaseSettingsFragment

import com.google.android.material.R as MaterialR

/**
 * The settings, in Material 3: a large top app bar over the screen of preferences
 * that is shown ([BaseSettingsFragment]: [AppSettingsFragment], and the screens it
 * opens, see [openScreen]).
 *
 *  - The app bar: while the screen is longer than the room under it, scrolling
 *    the list up collapses the large title into the bar, which takes the "lifted"
 *    color while the list is under it. A screen that fits leaves the bar as it is.
 *  - The screens are stacked, each over the one before. Going from one to the
 *    other ([ScreenTransition]), the two lie side by side and move together, by
 *    the same distance: the screen on top toward the end, fading out, the one
 *    before it after it, fading in once the top one has gone far enough. The title
 *    of each goes along with its screen.
 *  - Back out of a screen, the predictive back gesture: that transition follows
 *    the finger. Let go, it runs to the end and the screen is popped; cancelled,
 *    it runs back. The back key and the arrow in the toolbar run it on their own.
 *    Opening a screen runs it the other way round.
 *  - Back out of the settings: the system animates back to the editor
 *    (android:enableOnBackInvokedCallback).
 */
class SettingsActivity : BaseActivity() {

    private lateinit var coordinator: CoordinatorLayout
    private lateinit var appBar: AppBarLayout
    private lateinit var collapsingToolbar: CollapsingToolbarLayout

    // the list of the screen that is shown, which the app bar follows
    private var scrollingList: RecyclerView? = null

    private val density by lazy { resources.displayMetrics.density }

    // the margins of the large title at rest; a transition moves it from there
    private var titleMarginStart = 0
    private var titleMarginEnd = 0

    private val listLayoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        // not in the middle of a layout pass: the flags change the layout
        view.post { updateAppBarScrolling(view as RecyclerView) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        coordinator = findViewById(R.id.coordinator)
        appBar = findViewById(R.id.app_bar)
        collapsingToolbar = findViewById(R.id.collapsing_toolbar)
        titleMarginStart = collapsingToolbar.expandedTitleMarginStart
        titleMarginEnd = collapsingToolbar.expandedTitleMarginEnd

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            // the title is the collapsing toolbar's, not the toolbar's
            setDisplayShowTitleEnabled(false)
        }

        collapsingToolbar.title = savedInstanceState?.getCharSequence(STATE_TITLE)
            ?: getString(R.string.settings_title)

        supportFragmentManager.addOnBackStackChangedListener(backStackListener)
        // after the FragmentManager's own callback: this one is asked first
        onBackPressedDispatcher.addCallback(this, screenBackCallback)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.fragment_container, AppSettingsFragment())
            }
        }
        screenBackCallback.isEnabled = supportFragmentManager.backStackEntryCount > 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(STATE_TITLE, transition?.topTitle ?: collapsingToolbar.title)
    }

    override fun onDestroy() {
        stopTransitionAnimator()
        scrollingList?.removeOnLayoutChangeListener(listLayoutListener)
        supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroy()
    }

    // The arrow in the toolbar goes back like the back key: out of a sub screen
    // first, then out of the settings.
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** Lays [fragment], a screen of the settings, over the one shown now. */
    fun openScreen(fragment: BaseSettingsFragment) {
        // not in the middle of going from one screen to another
        if (transition != null) return
        // the screen that is covered gets its app bar back like this when it is gone back to
        currentScreen?.appBarOffset = appBarOffset()
        pendingEnterScreen = fragment
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(R.id.fragment_container, fragment)
            addToBackStack(null)
        }
    }

    // #region screens

    // the screen that was just opened, to come in once its view is there
    private var pendingEnterScreen: BaseSettingsFragment? = null

    // the screens, from the first to the one on top
    private val screens: List<BaseSettingsFragment>
        get() = supportFragmentManager.fragments.filterIsInstance<BaseSettingsFragment>()

    // the screen on top, the one that is shown
    private val currentScreen: BaseSettingsFragment?
        get() = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BaseSettingsFragment

    // the screen under the one on top
    private val previousScreen: BaseSettingsFragment?
        get() = screens.let { if (it.size >= 2) it[it.size - 2] else null }

    /** Called by a screen once its view is there. */
    fun onScreenViewCreated(fragment: BaseSettingsFragment) {
        if (currentScreen !== fragment) return
        showScreen(fragment)
        if (pendingEnterScreen === fragment) {
            pendingEnterScreen = null
            enterScreen(fragment)
        }
    }

    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        screenBackCallback.isEnabled = supportFragmentManager.backStackEntryCount > 0
        currentScreen?.takeIf { it.view != null }?.let { showScreen(it) }
    }

    // [fragment] is the screen that is shown now: its title (unless a transition is
    // showing the titles), its list for the app bar; the screens under it are left
    // out for accessibility.
    private fun showScreen(fragment: BaseSettingsFragment) {
        val list = fragment.listView ?: return
        if (transition == null) {
            collapsingToolbar.title = getString(fragment.titleRes)
            setTitleAlpha(1f)
        }
        // the app bar was moved by the transition (see ScreenTransition): not here,
        // where it would move the page again once it is shown
        bindScrollingList(list)

        supportFragmentManager.fragments.forEach { other ->
            other.view?.importantForAccessibility = if (other === fragment) {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        updateScreenVisibility()
    }

    /**
     * Only the screen on top is drawn, and while a transition runs the one it goes
     * to as well; the screens further down are hidden, as they are. A transition
     * fades the two it moves: a third one under them would show through (going back
     * from a screen three deep, the first one through the second).
     */
    private fun updateScreenVisibility() {
        val stacked = screens
        val top = stacked.lastOrNull()
        val moving = transition
        stacked.forEach { screen ->
            val view = screen.view ?: return@forEach
            val shown = screen === top || (moving != null && (view === moving.top || view === moving.below))
            view.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        }
    }

    // #endregion

    // #region going from one screen to another

    /**
     * Between the screen on top ([top]) and the one under it ([below]), at a
     * progress from 0 (the top one shown) to 1 (the one under it shown).
     *
     * The two screens lie side by side on one strip, the one under it [TRAVEL] of
     * the width before the top one (toward the start), and the strip moves as one:
     * the top screen goes from 0 to TRAVEL toward the end, and the one under it
     * from -TRAVEL to 0, by the same distance at every moment.
     *
     *  - The top screen fades out as it goes.
     *  - The one under it stays invisible until the top one has gone [FADE_IN_START]
     *    of the way, and fades in over the rest of it.
     *  - Each title moves with its screen: the top one fades out over the first
     *    [TITLE_SWITCH] of the way, the other one fades in after that.
     *  - The app bar goes from where it is for the top screen to where it was for
     *    the other one (expanded or collapsed, or in between), on the way: so the
     *    page does not move up or down once it is shown. The pages themselves only
     *    move sideways (see show).
     */
    private inner class ScreenTransition(
        val top: View,
        val below: View?,
        val topTitle: CharSequence,
        val belowTitle: CharSequence,
        // where the app bar is for each screen (0 expanded, down to -totalScrollRange)
        private val topBarOffset: Int,
        private val belowBarOffset: Int
    ) {
        var progress = 0f
            private set

        // toward the end: right, or left from right to left
        private val direction = if (coordinator.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f

        fun show(p: Float) {
            progress = p
            val width = (if (top.width > 0) top.width else coordinator.width).toFloat()
            val travel = width * TRAVEL
            // where the strip is: how far the top screen has gone toward the end
            val offset = travel * p

            top.translationX = direction * offset
            top.alpha = 1f - p
            below?.let {
                it.translationX = direction * (offset - travel)
                it.alpha = ((p - FADE_IN_START) / (1f - FADE_IN_START)).coerceIn(0f, 1f)
            }

            // The app bar goes from where it is for one screen to where it is for the
            // other. The pages are in the container under it, which moves with it; each
            // page takes that vertical move back, so the pages only move sideways: the
            // top one stays where it was, the other one is where it will be.
            val range = appBar.totalScrollRange
            val topOffset = topBarOffset.coerceIn(-range, 0)
            val belowOffset = belowBarOffset.coerceIn(-range, 0)
            moveAppBar((topOffset + (belowOffset - topOffset) * p).roundToInt())
            val barOffset = appBarOffset()
            top.translationY = (topOffset - barOffset).toFloat()
            below?.translationY = (belowOffset - barOffset).toFloat()

            // the large title goes along with its screen; the margins count from the
            // start, so they need no direction
            if (p < TITLE_SWITCH) {
                setTitleText(topTitle)
                setTitleAlpha(1f - p / TITLE_SWITCH)
                setTitleShift(offset)
            } else {
                setTitleText(belowTitle)
                setTitleAlpha((p - TITLE_SWITCH) / (1f - TITLE_SWITCH))
                setTitleShift(offset - travel)
            }
        }

        /**
         * The screens and the title as they are at rest, with the title [shownTitle]
         * of the one that is left. [resetTop] false leaves the top screen as it is,
         * gone: it is about to be popped, which takes a frame or two, and shown again
         * meanwhile it would flash over the other one.
         */
        fun finish(shownTitle: CharSequence, resetTop: Boolean = true) {
            if (resetTop) {
                top.translationX = 0f
                top.translationY = 0f
                top.alpha = 1f
            }
            below?.translationX = 0f
            below?.translationY = 0f
            below?.alpha = 1f
            setTitleText(shownTitle)
            setTitleAlpha(1f)
            setTitleShift(0f)
        }

        private fun setTitleText(text: CharSequence) {
            // setting it lays the title out again: only when it changes
            if (collapsingToolbar.title?.toString() != text.toString()) {
                collapsingToolbar.title = text
            }
        }

        // Moves the large title by [shift] toward the end. Both margins move, so the
        // room for the title stays as wide as it was and it is not cut short with "…".
        private fun setTitleShift(shift: Float) {
            val value = shift.roundToInt()
            val start = titleMarginStart + value
            val end = titleMarginEnd - value
            if (collapsingToolbar.expandedTitleMarginStart != start) {
                collapsingToolbar.expandedTitleMarginStart = start
            }
            if (collapsingToolbar.expandedTitleMarginEnd != end) {
                collapsingToolbar.expandedTitleMarginEnd = end
            }
        }
    }

    // the transition that is shown, from the start of a gesture or animation to its end
    private var transition: ScreenTransition? = null

    private var transitionAnimator: Animator? = null

    // from the moment a back is committed until its screen is popped: a second back
    // meanwhile must not pop another screen
    private var backCommitting = false

    // [topBarOffset]: where the app bar is for the top screen; the one under it
    // gets it where it was when it was covered
    private fun transitionFor(
        top: BaseSettingsFragment,
        below: BaseSettingsFragment?,
        topBarOffset: Int
    ): ScreenTransition? {
        val topView = top.view ?: return null
        // both screens may need the bar to move while they are on the move
        setAppBarScrollable(true)
        return ScreenTransition(
            topView,
            below?.view,
            getString(top.titleRes),
            below?.let { getString(it.titleRes) } ?: getString(top.titleRes),
            topBarOffset,
            below?.appBarOffset ?: topBarOffset
        )
    }

    // the transition is over: the app bar scrolls as the screen shown needs
    private fun endTransition() {
        transition = null
        updateScreenVisibility()
        scrollingList?.let { updateAppBarScrolling(it) }
    }

    // A screen just opened: the transition the other way round, from the screen
    // under it to this one.
    private fun enterScreen(fragment: BaseSettingsFragment) {
        // a screen opened starts at its top, under the bar in full
        val enter = transitionFor(fragment, previousScreen, topBarOffset = 0) ?: return
        transition = enter
        updateScreenVisibility()
        enter.show(1f)
        animateTransition(
            to = 0f,
            duration = duration(MaterialR.attr.motionDurationMedium4, 400),
            interpolatorAttr = MaterialR.attr.motionEasingEmphasizedDecelerateInterpolator
        ) {
            enter.finish(enter.topTitle)
            endTransition()
        }
    }

    private val screenBackCallback = object : OnBackPressedCallback(false) {

        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            if (backCommitting) return
            startBackTransition()
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            if (backCommitting) return
            // the finger moves the screens
            transition?.show(backEvent.progress)
        }

        override fun handleOnBackCancelled() {
            if (backCommitting) return
            val back = transition ?: return
            animateTransition(
                to = 0f,
                duration = duration(MaterialR.attr.motionDurationShort4, 200),
                interpolatorAttr = MaterialR.attr.motionEasingStandardDecelerateInterpolator
            ) {
                back.finish(back.topTitle)
                endTransition()
            }
        }

        override fun handleOnBackPressed() {
            if (backCommitting) return
            // the back key, the arrow in the toolbar: no gesture before
            val back = transition ?: startBackTransition()
            if (back == null) {
                supportFragmentManager.popBackStack()
                return
            }
            backCommitting = true
            animateTransition(
                to = 1f,
                duration = duration(MaterialR.attr.motionDurationMedium2, 300),
                interpolatorAttr = MaterialR.attr.motionEasingStandardDecelerateInterpolator
            ) {
                // The screen on top is gone: popping it leaves the one under it. Not
                // once the state is saved (the app went to the back meanwhile): then
                // the screen stays, shown as it was.
                val canPop = !supportFragmentManager.isStateSaved
                back.finish(if (canPop) back.belowTitle else back.topTitle, resetTop = !canPop)
                backCommitting = false
                if (canPop) {
                    // the bar of the screen under it is where that one had it: the screen
                    // shown after the pop is bound to it then (backStackListener)
                    transition = null
                    supportFragmentManager.popBackStack()
                } else {
                    endTransition()
                }
            }
        }
    }

    // takes the screens from wherever an animation left them
    private fun startBackTransition(): ScreenTransition? {
        val running = transition
        stopTransitionAnimator()
        val top = currentScreen ?: return null
        // a transition taken over keeps the bar where its own start was
        val back = transitionFor(top, previousScreen, appBarOffset()) ?: return null
        transition = back
        updateScreenVisibility()
        back.show(running?.takeIf { it.top === back.top }?.progress ?: 0f)
        return back
    }

    // runs the transition from where it is to [to], then [onEnd] (also when cancelled)
    private fun animateTransition(
        to: Float,
        duration: Long,
        interpolatorAttr: Int,
        onEnd: () -> Unit
    ) {
        val running = transition ?: return onEnd()
        val from = running.progress
        // taken over from where it is: what the one before would have done at its end
        // is not done
        stopTransitionAnimator()
        transitionAnimator = ValueAnimator.ofFloat(from, to).apply {
            this.duration = (duration * kotlin.math.abs(to - from)).toLong().coerceAtLeast(MIN_DURATION_MS)
            interpolator = MotionUtils.resolveThemeInterpolator(
                this@SettingsActivity, interpolatorAttr, PathInterpolator(0f, 0f, 0f, 1f)
            )
            addUpdateListener { running.show(it.animatedValue as Float) }
            doOnEnd {
                if (transitionAnimator === this) transitionAnimator = null
                onEnd()
            }
            start()
        }
    }

    // Stops the animation of the transition without running what it would at its end.
    private fun stopTransitionAnimator() {
        transitionAnimator?.let {
            transitionAnimator = null
            it.removeAllListeners()
            it.cancel()
        }
    }

    // #endregion

    // #region app bar

    private fun setTitleAlpha(alpha: Float) {
        val color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(collapsingToolbar, MaterialR.attr.colorOnSurface),
            (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        )
        collapsingToolbar.setExpandedTitleColor(color)
        collapsingToolbar.setCollapsedTitleTextColor(color)
    }

    // The app bar follows [list]: it lifts while the list is scrolled under it, and
    // collapses with it while the list can scroll at all.
    private fun bindScrollingList(list: RecyclerView) {
        if (scrollingList !== list) {
            scrollingList?.removeOnLayoutChangeListener(listLayoutListener)
            scrollingList = list
            list.addOnLayoutChangeListener(listLayoutListener)
            appBar.setLiftOnScrollTargetView(list)
        }
        list.post {
            updateAppBarScrolling(list)
            appBar.setLifted(list.canScrollVertically(-1))
        }
    }

    private val appBarBehavior: AppBarLayout.Behavior?
        get() = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? AppBarLayout.Behavior

    // where the app bar is: 0 expanded, down to -totalScrollRange collapsed
    private fun appBarOffset(): Int = appBarBehavior?.topAndBottomOffset ?: 0

    /**
     * Moves the app bar to [offset] the way scrolling does: the bar, the page under
     * it and the large title (collapsing) all follow. Scrolling is the only public
     * way to set the offset that also tells the title.
     */
    private fun moveAppBar(offset: Int) {
        val behavior = appBarBehavior ?: return
        val list = scrollingList ?: return
        val target = offset.coerceIn(-appBar.totalScrollRange, 0)
        val delta = target - behavior.topAndBottomOffset
        val consumed = IntArray(2)
        when {
            // up: the bar collapses as the content scrolls up under it
            delta < 0 -> behavior.onNestedPreScroll(
                coordinator, appBar, list, 0, -delta, consumed, ViewCompat.TYPE_TOUCH
            )
            // down: the content is at its top, so the bar takes the scroll
            delta > 0 -> behavior.onNestedScroll(
                coordinator, appBar, list, 0, 0, 0, -delta, ViewCompat.TYPE_TOUCH, consumed
            )
        }
    }

    private fun setAppBarScrollable(scrollable: Boolean) {
        val params = collapsingToolbar.layoutParams as AppBarLayout.LayoutParams
        val flags = if (scrollable) {
            AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED or
                AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
        } else {
            0 // no scrolling
        }
        if (params.scrollFlags != flags) {
            params.scrollFlags = flags
            collapsingToolbar.layoutParams = params
        }
    }

    // Collapsing the bar only makes sense while the list can scroll: when it is
    // longer than the room under the expanded bar. Otherwise the bar stays put.
    // While a transition runs, it decides (both screens may need the bar to move).
    private fun updateAppBarScrolling(list: RecyclerView) {
        if (transition != null) return
        if (list !== scrollingList || !list.isAttachedToWindow || list.adapter == null) return
        val content = list.computeVerticalScrollRange() + list.paddingTop + list.paddingBottom
        val room = coordinator.height - appBar.height
        val scrollable = content > room

        if (!scrollable && appBarOffset() != 0) {
            // a screen that fits has the bar out in full
            appBar.setExpanded(true, true)
        }
        setAppBarScrollable(scrollable)
    }

    // #endregion

    private fun duration(attr: Int, fallback: Int): Long =
        MotionUtils.resolveThemeDuration(this, attr, fallback).toLong()

    private companion object {
        const val STATE_TITLE = "settings:title"

        // How far apart the two screens lie on the strip, of the width: how far the
        // top one moves before it is gone, and how far the one under it comes from.
        // 1 lays them edge to edge.
        const val TRAVEL = 0.5f

        // How far of the way the top screen has to go before the one under it starts
        // to fade in (0.3: a third of TRAVEL); until then it is not seen at all.
        const val FADE_IN_START = 0.3f

        // where on the way the titles take turns: the top one is gone by then
        const val TITLE_SWITCH = 0.5f

        // even a transition that has little way to go takes this long
        const val MIN_DURATION_MS = 80L
    }
}
