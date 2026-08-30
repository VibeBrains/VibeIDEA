// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The facade: run every rule over a snapshot, stamp the class from the catalogue, apply the
 * project's accepted drifts.
 *
 * Acceptance is the part that makes the detector survivable. A page that legitimately uses glass
 * cards would otherwise report the same finding forever, and a list that is always wrong in the
 * same place stops being read. So a project may accept a rule WITH A REASON — and the reason, not
 * the acceptance, is the point: a month later it is the only thing that explains the decision.
 */
object DesignReview {
  /** An accepted drift as the project declared it. */
  data class Accepted(val rule: String, val reason: String)

  data class Report(
    val findings: List<Finding>,
    val viewport: Viewport,
  ) {
    /** Findings that may block a turn in strict mode — never the ones about taste. */
    val floor: List<Finding> get() = findings.filter { it.ruleClass == RuleClass.FLOOR && it.acceptedReason == null }
    val style: List<Finding> get() = findings.filter { it.ruleClass == RuleClass.STYLE }
    val accepted: List<Finding> get() = findings.filter { it.acceptedReason != null }
    val isClean: Boolean get() = findings.none { it.acceptedReason == null }
  }

  fun run(doc: DocumentSnapshot, accepted: List<Accepted> = emptyList()): Report {
    val byRule = accepted.associate { it.rule to it.reason }
    val raw = DesignFloorRules.all(doc) + DesignMarkupRules.all(doc) + DesignStyleRules.all(doc) +
      // Findability runs once per page rather than per element, so it lands here rather than in a
      // per-element pass: a title is not a property of a div.
      DesignFindabilityRules.all(doc) + DesignRhythmRules.all(doc)
    val findings = raw.map { finding ->
      // The class always comes from the catalogue, never from the rule that produced the finding.
      val stamped = finding.copy(ruleClass = DesignRuleCatalog.classOf(finding.rule))
      val reason = byRule[finding.rule]
      when {
        reason == null -> stamped
        // A floor finding cannot be accepted away: unreadable text is not an identity.
        stamped.ruleClass == RuleClass.FLOOR -> stamped
        else -> stamped.copy(acceptedReason = reason)
      }
    }
    return Report(findings.sortedWith(compareBy({ it.ruleClass != RuleClass.FLOOR }, { it.rule })), doc.viewport)
  }

  /**
   * Merges the reports of the two viewports.
   *
   * The same defect found on desktop and on the phone is one defect, not two — but a finding that
   * exists only at 390px is its own thing, and the width is part of the answer.
   */
  fun merge(reports: List<Report>): List<Finding> {
    val seen = LinkedHashMap<Triple<String, String, String>, Finding>()
    for (report in reports) {
      for (finding in report.findings) {
        val key = Triple(finding.rule, finding.selector, finding.evidence)
        val existing = seen[key]
        if (existing == null) seen[key] = finding
      }
    }
    return seen.values.toList()
  }

  /** One line for the chat: what was found, in the order a person acts on it. */
  fun summary(findings: List<Finding>): String {
    if (findings.isEmpty()) return t("design.review.clean")
    val floor = findings.count { it.ruleClass == RuleClass.FLOOR && it.acceptedReason == null }
    val style = findings.count { it.ruleClass == RuleClass.STYLE && it.acceptedReason == null }
    val accepted = findings.count { it.acceptedReason != null }
    return buildString {
      append(t("design.review.summary", "floor" to floor, "style" to style))
      if (accepted > 0) append(t("design.review.accepted", "count" to accepted))
      append(".")
    }
  }
}
