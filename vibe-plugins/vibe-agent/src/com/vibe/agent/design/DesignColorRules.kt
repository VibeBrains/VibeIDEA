// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t
import kotlin.math.roundToInt

/**
 * Colour: where «на глаз» is most confident and most often wrong.
 *
 * A palette drifts one hex at a time — a card copied from another page, a button coloured to match
 * a screenshot — and each step looks fine. Only counting says how many colours are actually on the
 * page, and counting is exactly what a person will not do.
 *
 * Two rules here are floor rather than taste, and for the same reason: they are about people who
 * cannot see the colour at all. A link told apart by colour alone, or an icon at the contrast of
 * decoration, is not a matter of taste for eight percent of men.
 */
object DesignColorRules {
  /** Distinct text colours above which the page has a set of colours instead of a palette. */
  const val MAX_TEXT_COLORS = 8

  /** Distinct saturated hues: more than this and nothing is the accent any more. */
  const val MAX_ACCENT_HUES = 4

  /** Hues within this many degrees are the same colour with a different shade. */
  private const val HUE_BUCKET_DEGREES = 30.0

  /** Below this a colour is a grey and carries no hue worth counting. */
  private const val ACCENT_SATURATION = 0.35

  /** WCAG: a non-text graphic needs 3:1 to be seen at all. */
  const val ICON_CONTRAST = 3.0

  /** A border less visible than this is a border that was declared and never drawn. */
  const val BORDER_CONTRAST = 1.5

  /** Share of the viewport above which a saturated fill stops being an accent and starts vibrating. */
  private const val LARGE_AREA_SHARE = 0.25

  fun all(doc: DocumentSnapshot): List<Finding> =
    textColorCount(doc) + accentHues(doc) + pureBlackText(doc) + saturatedLargeArea(doc) +
    linkByColorOnly(doc) + linkSameAsText(doc) + invisibleBorder(doc) + lowContrastIcons(doc) +
    textOnImage(doc) + focusRingContrast(doc)

  // --- how many colours there are ---

  fun textColorCount(doc: DocumentSnapshot): List<Finding> {
    val colors = doc.elements.filter { it.text.isNotBlank() }.map { it.color }.toSet()
    if (colors.size <= MAX_TEXT_COLORS) return emptyList()
    return listOf(
      page(DesignRuleCatalog.TOO_MANY_TEXT_COLORS, doc,
           message = t("design.rule.textColors.message", "count" to colors.size, "limit" to MAX_TEXT_COLORS),
           why = t("design.rule.textColors.why"),
           evidence = t("design.rule.textColors.evidence", "count" to colors.size))
    )
  }

  fun accentHues(doc: DocumentSnapshot): List<Finding> {
    val hues = doc.elements
      .flatMap { listOf(it.color, it.backgroundColor) }
      .filter { DesignColor.saturation(it) >= ACCENT_SATURATION }
      .map { (DesignColor.hue(it) / HUE_BUCKET_DEGREES).toInt() }
      .toSet()
    if (hues.size <= MAX_ACCENT_HUES) return emptyList()
    return listOf(
      page(DesignRuleCatalog.TOO_MANY_ACCENT_HUES, doc,
           message = t("design.rule.accentHues.message", "count" to hues.size, "limit" to MAX_ACCENT_HUES),
           why = t("design.rule.accentHues.why"),
           evidence = t("design.rule.accentHues.evidence", "count" to hues.size))
    )
  }

  // --- which colours they are ---

  /** Pure black on pure white: maximum contrast is not maximum readability. */
  fun pureBlackText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    if (element.color != Rgb(0, 0, 0)) return@mapNotNull null
    if (element.backgroundColor != Rgb(255, 255, 255)) return@mapNotNull null
    finding(DesignRuleCatalog.PURE_BLACK_ON_WHITE, Severity.HINT, element, doc,
            message = t("design.rule.pureBlack.message"),
            why = t("design.rule.pureBlack.why"),
            evidence = "#000000 / #ffffff")
  }

  /** A big saturated fill: the eye cannot settle on it, and everything on top loses. */
  fun saturatedLargeArea(doc: DocumentSnapshot): List<Finding> {
    val viewportArea = doc.viewportWidthPx * doc.viewportHeightPx
    if (viewportArea <= 0) return emptyList()
    return doc.elements.mapNotNull { element ->
      if (element.ownBackgroundAlpha < 0.9) return@mapNotNull null
      val share = (element.widthPx * element.heightPx) / viewportArea
      if (share < LARGE_AREA_SHARE) return@mapNotNull null
      val saturation = DesignColor.saturation(element.backgroundColor)
      if (saturation < 0.85) return@mapNotNull null
      finding(DesignRuleCatalog.SATURATED_LARGE_AREA, Severity.HINT, element, doc,
              message = t("design.rule.saturatedArea.message",
                          "share" to (share * 100).roundToInt(), "saturation" to (saturation * 100).roundToInt()),
              why = t("design.rule.saturatedArea.why"),
              evidence = t("design.rule.saturatedArea.evidence", "share" to (share * 100).roundToInt()))
    }
  }

  // --- colour as the only signal ---

  /**
   * A link inside a paragraph told apart by colour alone.
   *
   * Floor, not taste: for red-green colour blindness the link is plain text, and the page simply
   * has no links in it. Only links INSIDE text are judged — a navigation item or a button is found
   * by its place, not by its colour.
   */
  fun linkByColorOnly(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.tag != "a" || element.text.isBlank()) return@mapNotNull null
    val parent = doc.elements.getOrNull(element.parentId) ?: return@mapNotNull null
    if (parent.tag !in INLINE_TEXT_PARENTS) return@mapNotNull null
    if (element.textDecorationLine.contains("underline")) return@mapNotNull null
    if (element.fontWeight >= parent.fontWeight + 100) return@mapNotNull null
    finding(DesignRuleCatalog.LINK_BY_COLOR_ONLY, Severity.ERROR, element, doc,
            message = t("design.rule.linkColorOnly.message"),
            why = t("design.rule.linkColorOnly.why"),
            evidence = element.text.take(60))
  }

  /** A link the same colour as the text around it: not even a sighted person can find it. */
  fun linkSameAsText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.tag != "a" || element.text.isBlank()) return@mapNotNull null
    val parent = doc.elements.getOrNull(element.parentId) ?: return@mapNotNull null
    if (parent.tag !in INLINE_TEXT_PARENTS) return@mapNotNull null
    if (element.color != parent.color) return@mapNotNull null
    if (element.textDecorationLine.contains("underline")) return@mapNotNull null
    finding(DesignRuleCatalog.LINK_SAME_COLOR_AS_TEXT, Severity.ERROR, element, doc,
            message = t("design.rule.linkSameColor.message"),
            why = t("design.rule.linkSameColor.why"),
            evidence = element.text.take(60))
  }

  /** A border with no contrast against its background — declared, paid for in markup, never seen. */
  fun invisibleBorder(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val border = element.borderColor ?: return@mapNotNull null
    if (element.borderWidthPx <= 0) return@mapNotNull null
    val ratio = DesignColor.contrast(border, element.backgroundColor)
    if (ratio >= BORDER_CONTRAST) return@mapNotNull null
    finding(DesignRuleCatalog.BORDER_INVISIBLE, Severity.HINT, element, doc,
            message = t("design.rule.borderInvisible.message", "ratio" to DesignFloorRules.format(ratio)),
            why = t("design.rule.borderInvisible.why"),
            evidence = t("design.rule.borderInvisible.evidence",
                         "ratio" to DesignFloorRules.format(ratio), "min" to DesignFloorRules.format(BORDER_CONTRAST)))
  }

  /** An icon at decoration contrast: it carries meaning and cannot be made out. */
  fun lowContrastIcons(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.svgShapeCount <= 0) return@mapNotNull null
    if (element.text.isNotBlank()) return@mapNotNull null   // the word carries the meaning already
    val ratio = DesignColor.contrast(element.color, element.backgroundColor)
    if (ratio >= ICON_CONTRAST) return@mapNotNull null
    finding(DesignRuleCatalog.ICON_LOW_CONTRAST, Severity.ERROR, element, doc,
            message = t("design.rule.iconContrast.message",
                        "ratio" to DesignFloorRules.format(ratio), "min" to DesignFloorRules.format(ICON_CONTRAST)),
            why = t("design.rule.iconContrast.why"),
            evidence = DesignFloorRules.format(ratio))
  }

  /**
   * Text laid straight on a photograph.
   *
   * The contrast here cannot be computed at all: it depends on the pixel behind each letter, and
   * the photograph may be replaced by a lighter one tomorrow. A scrim — an overlay or a shadow — is
   * what turns an unknown into a known.
   */
  fun textOnImage(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank()) return@mapNotNull null
    val parent = doc.elements.getOrNull(element.parentId)
    val image = element.backgroundImage.takeIf { it.contains("url(") }
      ?: parent?.backgroundImage?.takeIf { it.contains("url(") }
      ?: return@mapNotNull null
    if (element.boxShadow.isNotBlank() && element.boxShadow != "none") return@mapNotNull null
    if (element.ownBackgroundAlpha >= 0.5) return@mapNotNull null    // its own fill IS the scrim
    finding(DesignRuleCatalog.TEXT_ON_IMAGE_WITHOUT_SCRIM, Severity.WARNING, element, doc,
            message = t("design.rule.textOnImage.message"),
            why = t("design.rule.textOnImage.why"),
            evidence = image.take(80))
  }

  /**
   * A focus ring that exists and cannot be seen.
   *
   * The floor rule next door catches an outline that was REMOVED; this one catches the subtler
   * version — an outline drawn in a colour a hair away from the background. For someone navigating
   * by keyboard the effect is identical: nothing on the screen says where they are.
   */
  fun focusRingContrast(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    if (element.outlineStyle == "none" || element.outlineWidthPx <= 0) return@mapNotNull null
    val outline = element.outlineColor ?: return@mapNotNull null
    val ratio = DesignColor.contrast(outline, element.backgroundColor)
    if (ratio >= ICON_CONTRAST) return@mapNotNull null
    finding(DesignRuleCatalog.FOCUS_RING_LOW_CONTRAST, Severity.ERROR, element, doc,
            message = t("design.rule.focusContrast.message",
                        "ratio" to DesignFloorRules.format(ratio), "min" to DesignFloorRules.format(ICON_CONTRAST)),
            why = t("design.rule.focusContrast.why"),
            evidence = DesignFloorRules.format(ratio))
  }

  // --- helpers ---

  private val INLINE_TEXT_PARENTS = setOf("p", "li", "span", "td", "dd", "blockquote", "figcaption")

  private fun finding(
    rule: String, severity: Severity, element: ElementSnapshot, doc: DocumentSnapshot,
    message: String, why: String, evidence: String,
  ) = DesignFloorRules.finding(rule, severity, element, doc, message, why, evidence)

  private fun page(rule: String, doc: DocumentSnapshot, message: String, why: String, evidence: String) = Finding(
    rule = rule,
    severity = Severity.HINT,
    message = message,
    why = why,
    selector = "html",
    evidence = evidence,
    ruleClass = DesignRuleCatalog.classOf(rule),
    viewport = doc.viewport,
  )
}
