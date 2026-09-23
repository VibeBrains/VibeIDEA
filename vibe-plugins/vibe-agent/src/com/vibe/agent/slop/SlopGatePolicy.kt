// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The turn gate for prose: after an agent's turn that wrote text for people, the detector reads what it wrote.
 *
 * Modelled on the design gate: off, notify (a line in the feed, the turn ends either way) and enforce (the agent goes
 * back to its text while a file does not pass, up to a ceiling). A text that passes is silence — a gate that speaks on
 * every clean turn stops being read by the time it matters.
 *
 * Pure: the reports come in, the decision and the words go out; reading the files is the caller's.
 */
object SlopGatePolicy {
  enum class Mode { OFF, NOTIFY, ENFORCE }

  enum class Decision { SKIP, REPORT, BOUNCE, STOP }

  data class FileReport(val path: String, val report: SlopReport)

  /** The changed files the gate reads: prose only, each once. Code has its own gates, and a comment is not a post. */
  fun prosePaths(paths: Collection<String>): List<String> = paths.filter { SlopCheck.isProse(it) }.distinct()

  /**
   * @param attempt how many times this turn has already been sent back for its text
   * @param maxAttempts ceiling: a model that cannot write the text plainly must not loop forever
   */
  fun decide(mode: Mode, reports: List<FileReport>, attempt: Int, maxAttempts: Int): Decision {
    if (mode == Mode.OFF || reports.none { !it.report.passed }) return Decision.SKIP
    if (mode == Mode.NOTIFY) return Decision.REPORT
    return if (attempt < maxAttempts) Decision.BOUNCE else Decision.STOP
  }

  /**
   * What the agent is sent back with: the failing files and their findings, and the two limits of the rewrite. Said
   * in the message itself because a model told «remove the tells» fills the gaps with invented specifics — the worst
   * outcome a rewrite can have, and one the detector cannot see.
   */
  fun corrective(reports: List<FileReport>, attempt: Int, maxAttempts: Int): String = buildString {
    appendLine(t("slop.gate.header", "attempt" to attempt, "max" to maxAttempts))
    for (file in reports.filter { !it.report.passed }) {
      appendLine(file.path)
      appendLine(SlopRender.render(file.report, SlopLabels, MAX_FINDINGS_PER_FILE).prependIndent("  "))
    }
    append(t("slop.gate.limits"))
  }

  /** One line for the feed: which files did not pass and why. */
  fun summary(reports: List<FileReport>): String =
    reports.filter { !it.report.passed }.joinToString("; ") { file ->
      t("slop.gate.file", "path" to file.path.substringAfterLast('/'), "score" to SlopRender.number(file.report.score),
        "rules" to (file.report.blocking.ifEmpty { file.report.findings.map { it.rule }.distinct().take(MAX_RULES_NAMED) })
          .joinToString(", "))
    }

  private const val MAX_FINDINGS_PER_FILE = 15
  private const val MAX_RULES_NAMED = 5
}
