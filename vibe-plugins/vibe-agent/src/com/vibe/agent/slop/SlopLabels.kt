// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.vibe.agent.i18n.VibeI18n.t

/** The report in the interface language: the chat, the editor action and the command line. */
object SlopLabels : SlopRender.Labels {
  override fun verdict(score: String, passScore: String, passed: Boolean, findings: Int): String =
    t("slop.verdict", "score" to score, "pass" to passScore, "findings" to findings,
      "verdict" to if (passed) t("slop.verdict.pass") else t("slop.verdict.fail"))

  override fun finding(finding: SlopFinding): String {
    val density = finding.density
      ?: return t("slop.finding", "line" to finding.line, "column" to finding.column, "rule" to finding.rule,
                  "name" to finding.name, "severity" to finding.severity.id, "match" to finding.match, "fix" to finding.fix)
    return t("slop.finding.density", "line" to finding.line, "column" to finding.column, "rule" to finding.rule,
             "name" to finding.name, "severity" to finding.severity.id, "count" to density.count,
             "rate" to SlopRender.number(density.perThousand), "lines" to density.lines.joinToString(", "), "fix" to finding.fix)
  }

  override fun blocking(rules: List<String>): String = t("slop.blocking", "rules" to rules.joinToString(", "))

  override fun more(count: Int): String = t("slop.more", "count" to count)

  override fun skipped(rules: List<String>): String = t("slop.skipped", "rules" to rules.joinToString(", "))
}
