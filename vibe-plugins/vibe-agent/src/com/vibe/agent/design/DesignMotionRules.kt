// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Motion: the part of an interface that is judged by feel and therefore never measured.
 *
 * Everything here is a number, which is the point. «Анимация тормозит» is an opinion nobody can act
 * on; «переход 900 мс, порог 600» is a line of CSS and a decision. Duration, repetition and the
 * absence of a reduced-motion answer are all readable from the page.
 *
 * Nothing here is a floor except the reduced-motion guard: motion is taste, and blocking a turn
 * over an easing curve produces arguments about style instead of work.
 */
object DesignMotionRules {
  /** Above this a transition stops reading as a response and starts reading as a wait. */
  const val SLOW_MS = 600.0

  /** Below this the change is a flicker: the eye registers that something happened, not what. */
  const val FAST_MS = 80.0

  fun all(doc: DocumentSnapshot): List<Finding> =
    slow(doc) + tooFast(doc) + infinite(doc) + reducedMotion(doc) + transitionAll(doc) +
    hoverWithoutTransition(doc) + linearEasing(doc) + noPressFeedback(doc) + slowTransition(doc)

  // --- duration ---

  fun slow(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.animationDurationMs <= SLOW_MS) return@mapNotNull null
    if (isLoop(element)) return@mapNotNull null   // a spinner is supposed to keep going
    finding(DesignRuleCatalog.ANIMATION_TOO_SLOW, element, doc,
            message = t("design.rule.animationSlow.message",
                        "ms" to element.animationDurationMs.toInt(), "limit" to SLOW_MS.toInt()),
            why = t("design.rule.animationSlow.why"),
            evidence = element.animationName)
  }

  fun slowTransition(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.transitionDurationMs <= SLOW_MS) return@mapNotNull null
    finding(DesignRuleCatalog.TRANSITION_TOO_SLOW, element, doc,
            message = t("design.rule.transitionSlow.message",
                        "ms" to element.transitionDurationMs.toInt(), "limit" to SLOW_MS.toInt()),
            why = t("design.rule.transitionSlow.why"),
            evidence = element.transitionProperty)
  }

  fun tooFast(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val ms = maxOf(element.animationDurationMs, element.transitionDurationMs)
    // Zero means «no animation», which is a decision, not a defect.
    if (ms <= 0.0 || ms >= FAST_MS) return@mapNotNull null
    finding(DesignRuleCatalog.ANIMATION_TOO_FAST, element, doc,
            message = t("design.rule.animationFast.message", "ms" to ms.toInt(), "min" to FAST_MS.toInt()),
            why = t("design.rule.animationFast.why"),
            evidence = ms.toInt().toString() + "ms")
  }

  // --- repetition ---

  /**
   * An endless animation on something that is not a loader.
   *
   * A loop is a claim on attention that never expires: whatever else the page says, the eye goes
   * back to the thing that keeps moving. A loader earns that claim, a decorative card does not.
   */
  fun infinite(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!isLoop(element)) return@mapNotNull null
    if (looksLikeLoader(element)) return@mapNotNull null
    finding(DesignRuleCatalog.INFINITE_ANIMATION, element, doc,
            message = t("design.rule.infinite.message"),
            why = t("design.rule.infinite.why"),
            evidence = element.animationName)
  }

  /**
   * The page moves and no stylesheet answers `prefers-reduced-motion`.
   *
   * This is the one floor rule here: for a person with vestibular disorder a full-screen parallax
   * is nausea, not decoration, and the system setting is them having already asked. Silent when the
   * stylesheets could not be read — «нет правила» would then mean «не посмотрели».
   */
  fun reducedMotion(doc: DocumentSnapshot): List<Finding> {
    if (doc.hasReducedMotionRule) return emptyList()
    if (doc.elements.any { it.styleRulesUnreadable }) return emptyList()
    val moving = doc.elements.count { it.animationDurationMs > 0 || it.transitionDurationMs > 0 }
    if (moving == 0) return emptyList()
    return listOf(
      Finding(
        rule = DesignRuleCatalog.NO_REDUCED_MOTION,
        severity = Severity.WARNING,
        message = t("design.rule.reducedMotion.message", "count" to moving),
        why = t("design.rule.reducedMotion.why"),
        selector = "html",
        evidence = t("design.rule.reducedMotion.evidence", "count" to moving),
        ruleClass = DesignRuleCatalog.classOf(DesignRuleCatalog.NO_REDUCED_MOTION),
        viewport = doc.viewport,
      )
    )
  }

  // --- what exactly moves ---

  /** `transition: all` animates properties nobody chose — including the ones that relayout the page. */
  fun transitionAll(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.transitionProperty.trim() != "all") return@mapNotNull null
    if (element.transitionDurationMs <= 0.0) return@mapNotNull null
    finding(DesignRuleCatalog.TRANSITION_ALL, element, doc,
            message = t("design.rule.transitionAll.message"),
            why = t("design.rule.transitionAll.why"),
            evidence = "transition-property: all")
  }

  /** A hover state that snaps: the change is legible, the cause is not. */
  fun hoverWithoutTransition(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.hasHoverRule || !element.interactive || element.disabled) return@mapNotNull null
    if (element.styleRulesUnreadable) return@mapNotNull null
    if (element.transitionDurationMs > 0.0) return@mapNotNull null
    finding(DesignRuleCatalog.HOVER_WITHOUT_TRANSITION, element, doc,
            message = t("design.rule.hoverSnap.message"),
            why = t("design.rule.hoverSnap.why"),
            evidence = element.selector)
  }

  /** Linear easing on a one-shot animation: nothing in the physical world starts at full speed. */
  fun linearEasing(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.animationDurationMs <= 0.0) return@mapNotNull null
    if (isLoop(element)) return@mapNotNull null   // a spinner SHOULD be linear
    if (!element.animationTimingFunction.trim().startsWith("linear")) return@mapNotNull null
    finding(DesignRuleCatalog.LINEAR_EASING, element, doc,
            message = t("design.rule.linearEasing.message"),
            why = t("design.rule.linearEasing.why"),
            evidence = element.animationTimingFunction)
  }

  /**
   * Nothing happens while the control is being pressed.
   *
   * On a phone there is no hover at all, so `:active` is the only feedback between the tap and the
   * result — and without it a slow handler reads as a button that did not work.
   */
  fun noPressFeedback(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    if (element.styleRulesUnreadable) return@mapNotNull null
    if (element.hasActiveRule) return@mapNotNull null
    finding(DesignRuleCatalog.NO_PRESS_FEEDBACK, element, doc,
            message = t("design.rule.pressFeedback.message"),
            why = t("design.rule.pressFeedback.why"),
            evidence = element.selector)
  }

  // --- helpers ---

  fun isLoop(element: ElementSnapshot): Boolean =
    element.animationIterationCount.trim().startsWith("infinite")

  /** Names that say the loop is a wait indicator rather than decoration. */
  private val LOADER_WORDS = Regex("(?i)(spin|load|progress|pulse|skeleton|shimmer|busy)")

  private fun looksLikeLoader(element: ElementSnapshot): Boolean =
    LOADER_WORDS.containsMatchIn(element.animationName) ||
    element.classes.any { LOADER_WORDS.containsMatchIn(it) } ||
    LOADER_WORDS.containsMatchIn(element.selector)

  private fun finding(
    rule: String, element: ElementSnapshot, doc: DocumentSnapshot,
    message: String, why: String, evidence: String,
  ) = DesignFloorRules.finding(rule, Severity.HINT, element, doc, message, why, evidence)
}
