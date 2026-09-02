// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.VibeChatSettings
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * Today's spending, in the corner of the window.
 *
 * A report answers the question when it is asked, and nobody asks it while the work is going well.
 * A number that is simply there changes behaviour — the same reason a car shows fuel rather than
 * offering a fuel report — and the moment worth catching is the one where a routine turn costs
 * more than the whole morning did.
 *
 * Deliberately quiet: empty while nothing has been spent, so an ordinary editing session sees no
 * widget at all. It shows money when a price is known and tokens otherwise, because a made-up
 * dollar figure would be worse than an honest token count.
 */
private const val WIDGET_ID = "VibeSpendStatus"

class VibeSpendStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
  private var statusBar: StatusBar? = null

  /**
   * Polled rather than pushed.
   *
   * The ledger is written from several threads — the chat, the ACP usage stream, background
   * roles — and a listener on each of them would be four places to forget. A widget that repaints
   * once a few seconds costs nothing and cannot go stale.
   */
  private val ticker = Timer(REFRESH_MS) { statusBar?.updateWidget(WIDGET_ID) }.apply { isRepeats = true }

  override fun ID(): String = WIDGET_ID
  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun install(statusBar: StatusBar) {
    this.statusBar = statusBar
    ticker.start()
  }

  override fun dispose() {
    ticker.stop()
    statusBar = null
  }

  override fun getText(): String {
    val entries = VibeSpendService.getInstance().cachedEntries(SpendLedger.DAY_MS) ?: return ""
    if (entries.isEmpty()) return ""
    val cost = entries.sumOf { it.costAmount ?: 0.0 }
    val tokens = entries.sumOf { it.tokens }
    // Money when it is known, tokens when it is not: an invented dollar figure would be worse
    // than an honest token count.
    return if (cost > 0) "%.2f".format(cost) else "%,d".format(tokens) + " " + t("status.spend.tokens")
  }

  override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

  override fun getTooltipText(): String {
    val entries = VibeSpendService.getInstance().cachedEntries(SpendCeiling.MONTH_MS) ?: return t("status.spend.tooltip")
    val limits = VibeChatSettings.spendLimits()
    if (!limits.any) return t("status.spend.tooltip")
    val now = System.currentTimeMillis()
    // The windows a ceiling is actually set for — showing untracked ones would be noise.
    return SpendCeiling.check(entries, now, limits).joinToString("; ") { verdict ->
      t("spend.ceiling.line", "window" to verdict.window.id,
        "spent" to "%.2f".format(verdict.spent), "limit" to "%.2f".format(verdict.limit),
        "left" to "%.2f".format(verdict.left))
    }
  }

  override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
    // The full report, not a settings page: a click on a number asks «из чего оно сложилось».
    ActionManager.getInstance().getAction(SPEND_REPORT_ACTION)?.let { action ->
      ActionManager.getInstance().tryToExecute(action, it, null, null, true)
    }
  }

  private companion object {
    /** Seconds, not milliseconds: the number is a background fact, not a live counter. */
    const val REFRESH_MS = 5_000
    const val SPEND_REPORT_ACTION = "Vibe.SpendReport"
  }
}

class VibeSpendStatusWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = WIDGET_ID
  override fun getDisplayName(): String = t("status.spend.name")
  override fun createWidget(project: Project): StatusBarWidget = VibeSpendStatusWidget(project)
  override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
}
