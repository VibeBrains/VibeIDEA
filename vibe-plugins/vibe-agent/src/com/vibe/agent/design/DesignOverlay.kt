// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns findings into what the overlay script needs, and back.
 *
 * Only what the page must draw goes over the bridge — the rule id, the message, the number and
 * whether it is a floor finding. Colour and wording are decided here rather than in the page: the
 * page is a display, and a display that decides severity would be a second source of truth.
 */
object DesignOverlay {
  const val CONTAINER_ID = "__vibe_design_overlay__"

  /** Marker attribute of the overlay, so a later measurement can tell its own drawing apart. */
  const val PICK_CALLBACK = "__vibeDesignPick"

  fun encode(findings: List<Finding>): String = JsonArray(
    findings.filter { it.acceptedReason == null }.map { finding ->
      buildJsonObject {
        put("rule", finding.rule)
        put("selector", finding.selector)
        put("message", finding.message)
        put("evidence", finding.evidence)
        put("floor", finding.ruleClass == RuleClass.FLOOR)
      }
    }
  ).toString()

  /** The message a picked finding turns into when it lands in the chat composer. */
  fun asChatNote(finding: Finding): String = buildString {
    append("Дизайн-находка «").append(finding.rule).append("»: ").append(finding.message).append(".\n")
    append("Где: ").append(finding.selector).append("\n")
    append("Измерено: ").append(finding.evidence).append("\n")
    append("Почему это важно: ").append(finding.why)
    if (finding.ruleClass == RuleClass.STYLE) {
      // Said out loud so the agent does not "fix" what the project may have chosen on purpose.
      append("\nЭто вкусовая находка, а не дефект: если так задумано, впишите правило с причиной в .vibe/design/design.md.")
    }
  }
}
