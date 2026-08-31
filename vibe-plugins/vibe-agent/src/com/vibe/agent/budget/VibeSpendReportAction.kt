// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.budget

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * «Куда ушли деньги» — by role and by target, for the last day and the last week.
 *
 * Two windows rather than one: a day answers «что происходит прямо сейчас», a week answers «это
 * норма или сегодня что-то пошло не так», and only the pair is actionable.
 */
class VibeSpendReportAction : AnAction({ t("spend.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val service = VibeSpendService.getInstance()
    val day = service.entries(SpendLedger.DAY_MS)
    val week = service.entries(7 * SpendLedger.DAY_MS)
    if (week.isEmpty()) {
      Messages.showInfoMessage(e.project, t("spend.empty"), t("spend.title"))
      return
    }
    val report = buildString {
      appendLine(t("spend.window.day", "tokens" to "%,d".format(day.sumOf { it.tokens })))
      SpendLedger.byRole(day).forEach { appendLine("  " + line(it)) }
      appendLine()
      appendLine(t("spend.window.week", "tokens" to "%,d".format(week.sumOf { it.tokens })))
      SpendLedger.byRole(week).forEach { appendLine("  " + line(it)) }
      appendLine()
      appendLine(t("spend.byTarget"))
      SpendLedger.byTarget(week).forEach { appendLine("  " + line(it)) }
      val files = FileSpend.top(week)
      if (files.isNotEmpty()) {
        appendLine()
        appendLine(t("spend.byFile"))
        files.forEach {
          appendLine("  " + t("spend.fileLine", "path" to it.path, "tokens" to "%,d".format(it.tokens), "turns" to it.turns))
        }
        // Said in the report itself, not only in the docs: a number that looks measured and is
        // apportioned will be quoted as measured the first time somebody screenshots it.
        appendLine("  " + t("spend.fileNote"))
      }
      appendLine()
      appendLine(t("spend.note"))
    }
    Messages.showInfoMessage(e.project, report, t("spend.title"))
  }

  private fun line(line: SpendLedger.Line): String {
    val money = when {
      line.cost <= 0.0 -> ""
      // A sum of dollars and roubles is not a number: when the currencies differ, the report says
      // «в разных валютах» instead of inventing a total.
      line.currency == null -> " · " + t("spend.mixedCurrency")
      else -> " · %.2f %s".format(line.cost, line.currency)
    }
    return t("spend.line", "name" to line.name, "tokens" to "%,d".format(line.tokens), "runs" to line.runs) + money
  }
}

/** The other half of an honest counter: it can be reset. */
class VibeClearSpendAction : AnAction({ t("spend.clear.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val confirmed = Messages.showYesNoDialog(e.project, t("spend.clear.confirm"), t("spend.title"),
                                             t("spend.clear.yes"), t("common.cancel"), Messages.getWarningIcon())
    if (confirmed != Messages.YES) return
    VibeSpendService.getInstance().clear()
    Messages.showInfoMessage(e.project, t("spend.clear.done"), t("spend.title"))
  }
}
