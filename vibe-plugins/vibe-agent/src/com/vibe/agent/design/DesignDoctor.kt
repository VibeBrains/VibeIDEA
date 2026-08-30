// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

/**
 * Can the design machinery actually work here, and what is missing?
 *
 * Without this the failures are silent and indistinguishable: `design_review` says «страница вне
 * досягаемости» whether the preview is closed, the page is on another port or JCEF is unavailable;
 * accepted drift silently applies to nothing when its rule id has a typo — the project believes it
 * switched a rule off, and nothing was switched off.
 *
 * Every answer here is a fact the caller already has; the value is having them in one place, phrased
 * as «чего не хватает» rather than as a status page.
 */
object DesignDoctor {
  data class Report(
    val contextFiles: List<String>,
    val pageReachable: Boolean,
    val unreachableReason: String?,
    val totalRules: Int,
    val floorRules: Int,
    val acceptedDrift: List<String>,
    /** Accepted-drift ids that match no rule: a typo here switches nothing off, silently. */
    val unknownDrift: List<String>,
    val hookMode: String,
  ) {
    val ready: Boolean get() = contextFiles.isNotEmpty() && pageReachable && unknownDrift.isEmpty()
  }

  /**
   * The one arithmetic rule of this report: the total counts RULE IDS, and floor plus style must
   * equal it. Printing «53 rules (10 floor, 45 style)» — numbers that do not add up — is the
   * fastest way to lose trust in the whole report, and it is exactly what happens when one number
   * counts rule functions and the other counts ids.
   */
  fun styleRules(totalRules: Int, floorRules: Int): Int = (totalRules - floorRules).coerceAtLeast(0)

  fun unknownDrift(accepted: List<String>, knownRules: Set<String>): List<String> =
    accepted.filter { it !in knownRules }

  fun render(report: Report, labels: Labels): String = buildString {
    appendLine(if (report.contextFiles.isEmpty()) labels.noContext else labels.context(report.contextFiles))
    appendLine(if (report.pageReachable) labels.pageReachable else labels.pageUnreachable(report.unreachableReason))
    appendLine(labels.rules(report.totalRules, report.floorRules, styleRules(report.totalRules, report.floorRules)))
    if (report.acceptedDrift.isNotEmpty()) appendLine(labels.drift(report.acceptedDrift.size))
    if (report.unknownDrift.isNotEmpty()) appendLine(labels.unknownDrift(report.unknownDrift))
    append(labels.hook(report.hookMode))
  }

  interface Labels {
    val noContext: String
    val pageReachable: String
    fun context(files: List<String>): String
    fun pageUnreachable(reason: String?): String
    fun rules(total: Int, floor: Int, style: Int): String
    fun drift(count: Int): String
    fun unknownDrift(ids: List<String>): String
    fun hook(mode: String): String
  }
}
