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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible

import kotlin.math.PI
import kotlin.math.sin

import com.google.android.material.color.MaterialColors

import x.editor.app.R
import x.editor.app.databinding.ViewDownloadPanelBinding

/**
 * The download panel of the editor (view_download_panel.xml), replacing the Alerter
 * banner: a Material card that slides in under the toolbar.
 *
 * - downloading: a progress ring around a download arrow that keeps falling into its
 *   tray, like the download animation of Chrome; the file name,
 *   the percentage, "2 / 3" and a thin bar with the progress over all files;
 * - done: the ring bursts and fades, the icon pops into a check mark, the card turns
 *   primary-container; it slides away by itself after a moment;
 * - failed: the badge shakes and turns into an error icon, the card turns
 *   error-container, with a retry button. It stays until closed or retried.
 *
 * The close button only hides the panel; a download keeps running. Main thread only.
 *
 * @param onRetry the retry button of the failed state was tapped
 */
internal class DownloadPanelController(
    private val views: ViewDownloadPanelBinding,
    private val onRetry: () -> Unit
) {

    private val card = views.root
    private val density = card.resources.displayMetrics.density

    // closed by the user while running: progress and success stay hidden, errors not
    private var closedByUser = false

    private var fallAnimator: ValueAnimator? = null
    private var colorAnimator: Animator? = null
    private val autoHide = Runnable { hide() }

    init {
        views.downloadClose.setOnClickListener {
            closedByUser = true
            hide()
        }
        views.downloadRetry.setOnClickListener {
            onRetry()
        }
    }

    /** A run of [count] downloads starts: shows the panel in its downloading state. */
    fun start(count: Int) {
        closedByUser = false
        this.count = count.coerceAtLeast(1)
        resetToDownloading()
        show()
    }

    private var count = 1

    /** File [index] (0-based) of the run, at [url], is [progress] percent done. */
    fun update(index: Int, url: String, progress: Int) {
        val percent = progress.coerceIn(0, 100)
        views.downloadSubtitle.text = fileNameOf(url)
        views.downloadStep.text = card.context.getString(R.string.download_step, index + 1, count)
        views.downloadStep.isVisible = count > 1
        views.downloadPercent.text = card.context.getString(R.string.download_percent, percent)
        views.downloadRing.setProgressCompat(percent, true)
        val total = ((index * 100 + percent) * 1000L / (count * 100L)).toInt()
        views.downloadTotal.setProgressCompat(total, true)
    }

    /** Everything is downloaded and installed. */
    fun showSuccess() {
        stopFalling()
        views.downloadRing.setProgressCompat(100, true)
        views.downloadTotal.setProgressCompat(1000, true)

        views.downloadTitle.setText(R.string.download_title_done)
        views.downloadSubtitle.setText(R.string.download_subtitle_done)
        views.downloadPercent.isVisible = false
        views.downloadStep.isVisible = false

        burstRing()
        fadeOut(views.downloadTotal)
        animateColors(
            card = color(com.google.android.material.R.attr.colorPrimaryContainer),
            badge = color(androidx.appcompat.R.attr.colorPrimary),
            text = color(com.google.android.material.R.attr.colorOnPrimaryContainer)
        )
        swapIcon(R.drawable.ic_download_done, color(com.google.android.material.R.attr.colorOnPrimary))

        card.removeCallbacks(autoHide)
        if (closedByUser) {
            hide()
        } else {
            card.postDelayed(autoHide, AUTO_HIDE_DELAY_MS)
        }
    }

    /** A download failed with [message]; shown even if the panel was closed. */
    fun showError(message: String) {
        closedByUser = false
        stopFalling()
        card.removeCallbacks(autoHide)

        views.downloadTitle.setText(R.string.download_title_failed)
        views.downloadSubtitle.text = message
        views.downloadPercent.isVisible = false
        views.downloadStep.isVisible = false
        views.downloadRetry.isVisible = true

        fadeOut(views.downloadRing)
        fadeOut(views.downloadTotal)
        animateColors(
            card = color(com.google.android.material.R.attr.colorErrorContainer),
            badge = color(androidx.appcompat.R.attr.colorError),
            text = color(com.google.android.material.R.attr.colorOnErrorContainer)
        )
        swapIcon(R.drawable.ic_error_outline, color(com.google.android.material.R.attr.colorOnError))
        shake(views.downloadBadge)
        show()
    }

    /** Slides the panel away. */
    fun hide() {
        card.removeCallbacks(autoHide)
        stopFalling()
        if (!card.isVisible) return
        card.animate().cancel()
        card.animate()
            .translationY(-hiddenOffset())
            .alpha(0f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction { card.visibility = View.GONE }
            .start()
    }

    // #region states

    private fun show() {
        if (card.isVisible && card.translationY == 0f && card.alpha == 1f) return
        card.animate().cancel()
        card.visibility = View.VISIBLE
        // the height is known only after a layout pass
        card.post {
            if (card.translationY == 0f) card.translationY = -hiddenOffset()
            if (card.alpha == 1f) card.alpha = 0f
            card.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(SHOW_DURATION_MS)
                .setInterpolator(EMPHASIZED_DECELERATE)
                .start()
        }
    }

    private fun resetToDownloading() {
        card.removeCallbacks(autoHide)
        colorAnimator?.cancel()

        val textColor = color(com.google.android.material.R.attr.colorOnSurface)
        card.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        views.downloadBadgeBackground.backgroundTintList =
            ColorStateList.valueOf(color(com.google.android.material.R.attr.colorPrimaryContainer))
        views.downloadTitle.setTextColor(textColor)
        views.downloadSubtitle.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))

        views.downloadTitle.setText(R.string.download_title_running)
        views.downloadSubtitle.text = ""
        views.downloadPercent.isVisible = true
        views.downloadPercent.text = card.context.getString(R.string.download_percent, 0)
        views.downloadRetry.isVisible = false

        for (view in listOf(views.downloadRing, views.downloadTotal)) {
            view.animate().cancel()
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.isVisible = true
        }
        views.downloadRing.setProgressCompat(0, false)
        views.downloadTotal.setProgressCompat(0, false)

        // the falling arrow, not the state icon
        views.downloadIcon.animate().cancel()
        views.downloadIcon.isVisible = false
        views.downloadGlyph.animate().cancel()
        views.downloadGlyph.isVisible = true
        views.downloadGlyph.scaleX = 1f
        views.downloadGlyph.scaleY = 1f
        val primary = ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary))
        views.downloadArrow.imageTintList = primary
        views.downloadTray.imageTintList = primary
        views.downloadBadge.translationX = 0f

        startFalling()
    }

    // #endregion

    // #region animations

    // Chrome-like: the arrow falls into the tray and is gone, drops in again from the
    // top, rests a moment, and so on. The tray gives a little as the arrow goes in.
    private fun startFalling() {
        stopFalling()
        val arrow = views.downloadArrow
        val tray = views.downloadTray
        // from the rest position to below the clip box: out of sight
        val distance = 16 * density
        val trayDip = 1.2f * density
        val exit = AccelerateInterpolator(1.6f)
        val entry = DecelerateInterpolator(1.6f)

        fallAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FALL_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val f = animator.animatedValue as Float
                arrow.translationY = when {
                    // falls out through the bottom, faster and faster
                    f < EXIT_END -> distance * exit.getInterpolation(f / EXIT_END)
                    // drops in from the top and slows down into place
                    f < ENTRY_END -> -distance + distance *
                        entry.getInterpolation((f - EXIT_END) / (ENTRY_END - EXIT_END))
                    // rests
                    else -> 0f
                }
                // the tray dips as the arrow goes into it
                tray.translationY = if (f in TRAY_START..TRAY_END) {
                    trayDip * sin(PI * (f - TRAY_START) / (TRAY_END - TRAY_START)).toFloat()
                } else {
                    0f
                }
            }
            start()
        }
    }

    private fun stopFalling() {
        fallAnimator?.cancel()
        fallAnimator = null
        views.downloadArrow.translationY = 0f
        views.downloadTray.translationY = 0f
    }

    // the full ring grows a little and fades away
    private fun burstRing() {
        views.downloadRing.animate()
            .scaleX(1.25f)
            .scaleY(1.25f)
            .alpha(0f)
            .setStartDelay(180L)
            .setDuration(420L)
            .setInterpolator(EMPHASIZED_DECELERATE)
            .start()
    }

    private fun fadeOut(view: View) {
        view.animate().alpha(0f).setStartDelay(0L).setDuration(220L).start()
    }

    // the arrow (or the previous icon) shrinks away, the new icon pops in with an overshoot
    private fun swapIcon(@DrawableRes icon: Int, @ColorInt tint: Int) {
        val iconView = views.downloadIcon
        val outgoing = if (views.downloadGlyph.isVisible) views.downloadGlyph else iconView
        outgoing.animate().cancel()
        iconView.animate().cancel()
        outgoing.animate()
            .scaleX(0f)
            .scaleY(0f)
            .setDuration(140L)
            .setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction {
                views.downloadGlyph.isVisible = false
                iconView.setImageResource(icon)
                iconView.imageTintList = ColorStateList.valueOf(tint)
                iconView.scaleX = 0f
                iconView.scaleY = 0f
                iconView.isVisible = true
                iconView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(380L)
                    .setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    private fun shake(view: View) {
        val d = density
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X, 0f, 9 * d, -9 * d, 6 * d, -6 * d, 3 * d, -3 * d, 0f
        ).apply {
            duration = 480L
            start()
        }
    }

    // card, badge and text colors move to those of the new state together
    private fun animateColors(@ColorInt card: Int, @ColorInt badge: Int, @ColorInt text: Int) {
        colorAnimator?.cancel()
        val cardFrom = this.card.cardBackgroundColor.defaultColor
        val badgeFrom = views.downloadBadgeBackground.backgroundTintList?.defaultColor ?: badge
        val textFrom = views.downloadTitle.currentTextColor

        val cardAnimator = ValueAnimator.ofArgb(cardFrom, card).apply {
            addUpdateListener { this@DownloadPanelController.card.setCardBackgroundColor(it.animatedValue as Int) }
        }
        val badgeAnimator = ValueAnimator.ofArgb(badgeFrom, badge).apply {
            addUpdateListener {
                views.downloadBadgeBackground.backgroundTintList =
                    ColorStateList.valueOf(it.animatedValue as Int)
            }
        }
        val textAnimator = ValueAnimator.ofArgb(textFrom, text).apply {
            addUpdateListener {
                val value = it.animatedValue as Int
                views.downloadTitle.setTextColor(value)
                views.downloadSubtitle.setTextColor(value)
            }
        }
        colorAnimator = AnimatorSet().apply {
            playTogether(cardAnimator, badgeAnimator, textAnimator)
            duration = COLOR_DURATION_MS
            interpolator = EMPHASIZED_DECELERATE
            start()
        }
    }

    // #endregion

    private fun hiddenOffset(): Float {
        val margin = (card.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        return (card.height + margin).toFloat().coerceAtLeast(64 * density)
    }

    @ColorInt
    private fun color(@AttrRes attr: Int): Int = MaterialColors.getColor(card, attr)

    private fun fileNameOf(url: String): String =
        Uri.parse(url).lastPathSegment?.takeIf { it.isNotEmpty() } ?: url

    private companion object {
        // Material 3 motion: emphasized decelerate / accelerate
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

        const val SHOW_DURATION_MS = 450L
        const val HIDE_DURATION_MS = 250L
        const val COLOR_DURATION_MS = 400L
        // one fall of the arrow: out (0..EXIT_END), in (..ENTRY_END), rest (..1)
        const val FALL_CYCLE_MS = 1300L
        const val EXIT_END = 0.38f
        const val ENTRY_END = 0.72f
        const val TRAY_START = 0.26f
        const val TRAY_END = 0.46f
        const val AUTO_HIDE_DELAY_MS = 2600L
    }
}
