// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.resilience.RetryPause
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import kotlin.math.ceil

/**
 * What the chat says about a pause before a request is sent again: the words and the arithmetic
 *
 * The cause is named in plain words with the HTTP status beside it, the countdown says how long and which repeat of how many
 * So the pause does not look like a hang
 */
internal object RetryPauseText {
  enum class Reason { RATE_LIMIT, SERVER, TIMEOUT, NETWORK }

  /** No status at all means the provider was not reached */
  fun reasonOf(statusCode: Int?): Reason = when (statusCode) {
    null -> Reason.NETWORK
    HTTP_TOO_MANY_REQUESTS -> Reason.RATE_LIMIT
    HTTP_REQUEST_TIMEOUT -> Reason.TIMEOUT
    else -> Reason.SERVER
  }

  /** Whole seconds until [deadlineMs], rounded up so the card never shows 0 while there is still time to wait */
  fun secondsLeft(deadlineMs: Long, nowMs: Long): Long = ceil((deadlineMs - nowMs).coerceAtLeast(0) / MS_PER_SECOND).toLong()

  fun reason(pause: RetryPause): String = when (reasonOf(pause.statusCode)) {
    Reason.RATE_LIMIT -> t("retry.reason.rateLimit", "code" to pause.statusCode)
    Reason.SERVER -> t("retry.reason.server", "code" to pause.statusCode)
    Reason.TIMEOUT -> t("retry.reason.timeout", "code" to pause.statusCode)
    Reason.NETWORK -> t("retry.reason.network")
  }

  fun countdown(pause: RetryPause, secondsLeft: Long): String =
    t("retry.countdown", "seconds" to secondsLeft, "attempt" to pause.attempt, "retries" to pause.retries)

  /** The whole pause as one line, for a place that cannot show a live card */
  fun line(pause: RetryPause): String =
    t("retry.line", "reason" to reason(pause), "countdown" to countdown(pause, secondsLeft(pause.delayMs, 0)))

  private const val HTTP_TOO_MANY_REQUESTS = 429
  private const val HTTP_REQUEST_TIMEOUT = 408
  private const val MS_PER_SECOND = 1000.0
}

/**
 * The card in the feed while the agent waits to send a request again
 *
 * One card for all the repeats of a request, reused from pause to pause: the cause and the status, a countdown that ticks
 * Which repeat of how many, and a stop that ends the wait at once
 * It is taken off the feed when the request goes out again, so no card is left behind for each attempt
 */
internal class RetryPauseCard(private val onStop: () -> Unit) : JPanel(BorderLayout()) {
  private val reason = JTextArea().apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = JBFont.label().deriveFont(Font.PLAIN, REASON_FONT)
    foreground = UIUtil.getLabelForeground()
    border = JBUI.Borders.empty()
  }
  private val countdown = JLabel().apply {
    font = JBFont.label().deriveFont(Font.PLAIN, COUNTDOWN_FONT)
    foreground = ChatTheme.META_FG
  }
  private var pause: RetryPause? = null
  private var deadlineMs = 0L

  private val ticker = Timer(TICK_MS) { refresh(System.currentTimeMillis()) }.apply { isRepeats = true }

  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(ROW_PAD_V, ROW_PAD_H, ROW_PAD_V, ROW_PAD_H)
    val inner = JPanel(BorderLayout(0, JBUI.scale(INNER_GAP))).apply {
      isOpaque = false
      border = JBUI.Borders.empty(CARD_PAD_V, CARD_PAD_H)
      add(reason, BorderLayout.NORTH)
      add(JPanel(BorderLayout()).apply {
        isOpaque = false
        add(countdown, BorderLayout.WEST)
        add(ChatTheme.actionLabel(t("retry.stop")) { onStop() }, BorderLayout.EAST)
      }, BorderLayout.SOUTH)
    }
    add(inner, BorderLayout.CENTER)
  }

  /** The next pause of the request: the card shows it and starts counting */
  fun show(next: RetryPause, nowMs: Long = System.currentTimeMillis()) {
    pause = next
    deadlineMs = nowMs + next.delayMs
    reason.text = RetryPauseText.reason(next)
    // The provider's own message is for whoever wants to see it, not for the line
    toolTipText = next.detail
    refresh(nowMs)
    if (!ticker.isRunning) ticker.start()
  }

  /** Stops the clock: a running timer keeps the card, and the panel with it, alive after the tab is closed */
  fun dispose() = ticker.stop()

  private fun refresh(nowMs: Long) {
    val current = pause ?: return
    countdown.text = RetryPauseText.countdown(current, RetryPauseText.secondsLeft(deadlineMs, nowMs))
  }

  /** A card never stretches vertically */
  override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = ChatTheme.CARD_BG
      val inset = insets
      val arc = JBUI.scale(CARD_RADIUS) * 2
      g2.fillRoundRect(inset.left, inset.top, width - inset.left - inset.right, height - inset.top - inset.bottom, arc, arc)
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  private companion object {
    const val REASON_FONT = 12f
    const val COUNTDOWN_FONT = 11f
    const val TICK_MS = 250
    const val ROW_PAD_V = 2
    const val ROW_PAD_H = 4
    const val CARD_PAD_V = 6
    const val CARD_PAD_H = 8
    const val CARD_RADIUS = 4
    const val INNER_GAP = 2
  }
}
