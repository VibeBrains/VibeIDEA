// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import java.util.Locale

/**
 * A report as text: the verdict first, then the findings, most severe first.
 *
 * One layout for every surface; the words come from [Labels], because the MCP tool answers a model in the protocol's
 * fixed language while the chat and the editor speak the interface language.
 */
object SlopRender {
  interface Labels {
    fun verdict(score: String, passScore: String, passed: Boolean, findings: Int): String
    fun finding(finding: SlopFinding): String
    fun blocking(rules: List<String>): String
    fun more(count: Int): String
  }

  fun render(report: SlopReport, labels: Labels, maxFindings: Int = DEFAULT_MAX_FINDINGS): String = buildString {
    appendLine(labels.verdict(number(report.score), number(report.passScore), report.passed, report.findings.size))
    if (report.blocking.isNotEmpty()) appendLine(labels.blocking(report.blocking))
    val ordered = ordered(report.findings)
    ordered.take(maxFindings).forEach { appendLine(labels.finding(it)) }
    if (ordered.size > maxFindings) appendLine(labels.more(ordered.size - maxFindings))
  }.trimEnd()

  /** Most severe first, then in text order: what blocks the text is what has to be read first. */
  fun ordered(findings: List<SlopFinding>): List<SlopFinding> =
    findings.sortedWith(compareBy({ -it.severity.ordinal }, { it.line }, { it.column }))

  fun number(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else String.format(Locale.ROOT, "%.1f", rounded)
  }

  const val DEFAULT_MAX_FINDINGS = 30
}
