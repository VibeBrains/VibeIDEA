// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t
import kotlin.math.abs

/**
 * The tells of a machine-made page.
 *
 * None of these is a defect: a gradient headline is a choice, a glass card is a choice. They are
 * listed because together they are the look a model reaches for when nobody told it what the
 * product looks like — and a product that looks generated reads as one nobody designed.
 *
 * Because they are taste and not law, they NEVER block a turn, and a project may declare any of
 * them its own identity with a stated reason. The reason matters more than the acceptance: a month
 * later it is the only thing that explains why the page looks like this.
 */
object DesignStyleRules {
  /** The hue band people mean by «фиолетовый AI-градиент». */
  private const val PURPLE_HUE_FROM = 255.0
  private const val PURPLE_HUE_TO = 290.0
  private const val PURPLE_MIN_SATURATION = 0.35

  /** A radius past this stops being a rounding and becomes a shape. */
  private const val EXTREME_RADIUS_PX = 40.0

  /** Cards that repeat this many times with the same child structure read as a template. */
  private const val CLONE_THRESHOLD = 3

  /** Timing functions that overshoot — the bounce a page does not need. */
  private val OVERSHOOT = listOf("cubic-bezier(0.68", "back", "elastic", "bounce")

  /** Properties whose animation moves the layout of everything around them. */
  private val LAYOUT_PROPERTIES = listOf("width", "height", "top", "left", "right", "bottom", "margin", "padding")


  fun all(doc: DocumentSnapshot): List<Finding> =
    gradientText(doc) + glow(doc) + glass(doc) + purple(doc) + eyebrow(doc) + clones(doc) +
    radiusDrift(doc) + extremeRadius(doc) + animatedLayout(doc) + overshoot(doc) +
    hangingPreposition(doc) + orphanWord(doc) + marketing(doc) + hoverResponse(doc)

  fun gradientText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    if (!element.backgroundClip.contains("text")) return@mapNotNull null
    if (!element.backgroundImage.contains("gradient")) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GRADIENT_TEXT, Severity.HINT, element, doc,
      message = t("design.rule.gradientText.message"),
      why = t("design.rule.gradientText.why"),
      evidence = element.backgroundImage.take(80),
    )
  }

  fun glow(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val shadow = element.boxShadow
    if (shadow.isBlank() || shadow == "none") return@mapNotNull null
    // A glow is a shadow with no offset: light coming from nowhere.
    if (!Regex("0px 0px").containsMatchIn(shadow)) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GLOW_INSTEAD_OF_SHADOW, Severity.HINT, element, doc,
      message = t("design.rule.glow.message"),
      why = t("design.rule.glow.why"),
      evidence = shadow.take(80),
    )
  }

  fun glass(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.backdropFilter.contains("blur")) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.GLASSMORPHISM, Severity.HINT, element, doc,
      message = t("design.rule.glass.message"),
      why = t("design.rule.glass.why"),
      evidence = element.backdropFilter.take(60),
    )
  }

  fun purple(doc: DocumentSnapshot): List<Finding> {
    val colored = doc.elements.filter { it.ownBackgroundAlpha > 0.5 && DesignColor.saturation(it.backgroundColor) >= PURPLE_MIN_SATURATION }
    if (colored.size < 3) return emptyList()
    val purple = colored.filter { DesignColor.hue(it.backgroundColor) in PURPLE_HUE_FROM..PURPLE_HUE_TO }
    // Not "purple exists" but "purple is the palette": a single accent is a choice, not a tell.
    if (purple.size * 2 < colored.size) return emptyList()
    val worst = purple.first()
    return listOf(
      DesignFloorRules.finding(
        DesignRuleCatalog.PURPLE_PALETTE, Severity.HINT, worst, doc,
        message = t("design.rule.purple.message", "purple" to purple.size, "colored" to colored.size),
        why = t("design.rule.purple.why"),
        evidence = t("design.rule.purple.evidence", "hue" to DesignFloorRules.format(DesignColor.hue(worst.backgroundColor))),
      )
    )
  }

  fun eyebrow(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank() || element.text.length > 40) return@mapNotNull null
    if (element.textTransform != "uppercase" && element.letterSpacingPx < 1.0) return@mapNotNull null
    if (element.borderRadiusPx < 8.0 || element.ownBackgroundAlpha < 0.5) return@mapNotNull null
    if (element.fontSizePx > 16.0) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.EYEBROW_CHIP, Severity.HINT, element, doc,
      message = t("design.rule.eyebrow.message"),
      why = t("design.rule.eyebrow.why"),
      evidence = t("design.rule.eyebrow.evidence", "text" to element.text.take(40), "radius" to DesignFloorRules.format(element.borderRadiusPx)),
    )
  }

  fun clones(doc: DocumentSnapshot): List<Finding> {
    val groups = doc.elements
      .filter { it.childTags.isNotEmpty() && it.ownBackgroundAlpha > 0.3 }
      .groupBy { it.parentId to it.childTags.joinToString(",") }
    return groups.values.mapNotNull { siblings ->
      if (siblings.size < CLONE_THRESHOLD) return@mapNotNull null
      val sizes = siblings.map { it.widthPx to it.heightPx }.distinct()
      if (sizes.size > 1) return@mapNotNull null
      DesignFloorRules.finding(
        DesignRuleCatalog.CLONED_CARDS, Severity.HINT, siblings.first(), doc,
        message = t("design.rule.clones.message", "count" to siblings.size),
        why = t("design.rule.clones.why"),
        evidence = t("design.rule.clones.evidence", "structure" to siblings.first().childTags.joinToString(",")),
      )
    }
  }

  fun radiusDrift(doc: DocumentSnapshot): List<Finding> {
    val radii = doc.elements.map { it.borderRadiusPx }.filter { it > 0 }.distinct().sorted()
    if (radii.size <= 3) return emptyList()
    val element = doc.elements.first { it.borderRadiusPx > 0 }
    return listOf(
      DesignFloorRules.finding(
        DesignRuleCatalog.RADIUS_SCALE_DRIFT, Severity.HINT, element, doc,
        message = t("design.rule.radiusDrift.message", "count" to radii.size),
        why = t("design.rule.radiusDrift.why"),
        evidence = radii.joinToString(", ") { DesignFloorRules.format(it) + "px" },
      )
    )
  }

  fun extremeRadius(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.borderRadiusPx < EXTREME_RADIUS_PX) return@mapNotNull null
    // A pill button and a circular avatar are legitimate: the radius is half the height there.
    if (element.heightPx > 0 && element.borderRadiusPx >= element.heightPx / 2 - 1) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.EXTREME_RADIUS, Severity.HINT, element, doc,
      message = t("design.rule.extremeRadius.message", "radius" to DesignFloorRules.format(element.borderRadiusPx)),
      why = t("design.rule.extremeRadius.why"),
      evidence = t("design.rule.extremeRadius.evidence", "radius" to DesignFloorRules.format(element.borderRadiusPx), "height" to DesignFloorRules.format(element.heightPx)),
    )
  }

  fun animatedLayout(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val property = element.transitionProperty.lowercase()
    if (property.isBlank() || property == "none") return@mapNotNull null
    val offender = LAYOUT_PROPERTIES.firstOrNull { property.contains(it) } ?: return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ANIMATED_LAYOUT_PROPERTY, Severity.HINT, element, doc,
      message = t("design.rule.animatedLayout.message", "property" to offender),
      why = t("design.rule.animatedLayout.why"),
      evidence = element.transitionProperty.take(80),
    )
  }

  fun overshoot(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val timing = (element.animationTimingFunction + " " + element.transitionProperty).lowercase()
    if (OVERSHOOT.none { timing.contains(it) }) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.OVERSHOOT_ANIMATION, Severity.HINT, element, doc,
      message = t("design.rule.overshoot.message"),
      why = t("design.rule.overshoot.why"),
      evidence = element.animationTimingFunction.take(60),
    )
  }

  fun hangingPreposition(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    // Measured line breaking, not a guess from the source: only the page knows where the line broke.
    if (element.textLineCount < 2 || element.linesEndingWithShortWord <= 0) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.HANGING_PREPOSITION, Severity.HINT, element, doc,
      message = t("design.rule.hangingPreposition.message", "count" to element.linesEndingWithShortWord),
      why = t("design.rule.hangingPreposition.why"),
      evidence = t("design.rule.hangingPreposition.evidence", "count" to element.linesEndingWithShortWord, "total" to element.textLineCount),
    )
  }

  fun orphanWord(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.textLineCount < 2 || element.lastLineWordCount != 1) return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.ORPHAN_WORD, Severity.HINT, element, doc,
      message = t("design.rule.orphan.message"),
      why = t("design.rule.orphan.why"),
      evidence = t("design.rule.orphan.evidence", "total" to element.textLineCount),
    )
  }

  fun marketing(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    val hit = DesignPhrases.MARKETING.firstOrNull { it.containsMatchIn(element.text) } ?: return@mapNotNull null
    DesignFloorRules.finding(
      DesignRuleCatalog.MARKETING_PROMISE, Severity.HINT, element, doc,
      message = t("design.rule.marketing.message"),
      why = t("design.rule.marketing.why"),
      evidence = "«" + hit.find(element.text)?.value.orEmpty() + "»",
    )
  }

  fun hoverResponse(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    if (element.styleRulesUnreadable) return@mapNotNull null
    if (element.hasHoverRule) return@mapNotNull null
    // A hint, never a defect: on a touch device there is no hover at all, so this is taste.
    DesignFloorRules.finding(
      DesignRuleCatalog.NO_HOVER_RESPONSE, Severity.HINT, element, doc,
      message = t("design.rule.hover.message"),
      why = t("design.rule.hover.why"),
      evidence = t("design.rule.hover.evidence"),
    )
  }

  internal fun close(a: Double, b: Double, tolerance: Double = 0.5): Boolean = abs(a - b) <= tolerance
}
