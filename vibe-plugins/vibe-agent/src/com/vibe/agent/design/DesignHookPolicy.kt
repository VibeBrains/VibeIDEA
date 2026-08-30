// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The design hook: measure the page after the agent touched the interface, without being asked.
 *
 * Why a hook at all — the detector only helps if it runs. Asked politely, a model checks its work
 * when it remembers to; hooked to the end of a turn that changed UI files, it checks every time.
 *
 * Why strict mode is floor-only — bouncing a model over taste («ореол — штамп») would turn an
 * opinion into a blocker and produce loops about style. Contrast below AA, a target too small to
 * hit, clipped content: those are defects at any taste, and a run should not end on them.
 *
 * Pure decision logic; the measuring and the running live elsewhere.
 */
object DesignHookPolicy {
  enum class Mode {
    /** Never runs. */
    OFF,

    /** Measures and reports; the turn ends either way. */
    NOTIFY,

    /** Measures and sends the model back while unaccepted FLOOR findings remain. */
    ENFORCE_FLOOR,
  }

  enum class Decision { SKIP, REPORT, BOUNCE, STOP }

  /** Extensions whose change can move pixels. Editing a service or a test cannot. */
  private val UI_EXTENSIONS = listOf(
    ".css", ".scss", ".sass", ".less", ".styl",
    ".html", ".htm", ".svg",
    ".tsx", ".jsx", ".vue", ".svelte", ".astro",
  )

  private val TEST_FILE = Regex("\\.(test|spec)\\.[a-z]+$")

  /** True when at least one changed path could have changed what the page looks like. */
  fun touchesUi(paths: Collection<String>): Boolean = paths.any { path ->
    val lower = path.lowercase()
    // A test file ending in .tsx is still a test: it renders nothing the user sees.
    if (TEST_FILE.containsMatchIn(lower)) false else UI_EXTENSIONS.any { lower.endsWith(it) }
  }

  /**
   * @param attempt how many times this turn has already been bounced for design
   * @param maxAttempts ceiling, so a model that cannot fix the page does not loop forever
   */
  fun decide(mode: Mode, findings: List<Finding>, attempt: Int, maxAttempts: Int): Decision {
    if (mode == Mode.OFF) return Decision.SKIP
    val blocking = findings.filter { it.ruleClass == RuleClass.FLOOR && it.acceptedReason == null }
    if (mode == Mode.NOTIFY) return if (findings.isEmpty()) Decision.SKIP else Decision.REPORT
    if (blocking.isEmpty()) return if (findings.isEmpty()) Decision.SKIP else Decision.REPORT
    return if (attempt < maxAttempts) Decision.BOUNCE else Decision.STOP
  }

  /** The message sent back to the model — floor findings only, with the numbers to fix them by. */
  fun corrective(findings: List<Finding>, attempt: Int, maxAttempts: Int): String {
    val blocking = findings.filter { it.ruleClass == RuleClass.FLOOR && it.acceptedReason == null }
    return buildString {
      appendLine(t("design.gate.header", "attempt" to attempt, "max" to maxAttempts))
      for (finding in blocking.take(MAX_LISTED)) {
        append("- ").append(finding.message)
        append(" — ").append(finding.selector)
        append(" (").append(finding.evidence).append(")")
        append(if (finding.viewport == Viewport.MOBILE) " [" + t("design.viewport.mobile") + "]" else " [" + t("design.viewport.desktop") + "]")
        append("\n")
      }
      if (blocking.size > MAX_LISTED) appendLine(t("design.gate.more", "count" to (blocking.size - MAX_LISTED)))
      // Said explicitly so the model does not start "fixing" taste to please the gate.
      append(t("design.gate.styleNote"))
    }
  }

  private const val MAX_LISTED = 10
}
