// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.design

import com.vibe.agent.i18n.VibeI18n.t
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rhythm and typography: the defects that make a page feel wrong before anyone can say why.
 *
 * None of these is a bug in the usual sense — the page works, the text is readable, nothing
 * overlaps. They are the difference between «сделано» and «сделано аккуратно», and they are the
 * things a model never notices because it does not look at the result.
 *
 * Every rule here is a MEASUREMENT with a number in the finding, and every one is style rather than
 * floor: a project may deliberately break any of them, and then it says so in its design file
 * instead of arguing with the tool.
 */
object DesignRhythmRules {
  /** Text of this many characters per line stops being comfortable — the classic measure. */
  const val MAX_LINE_CHARS = 90
  const val MIN_LINE_CHARS = 30

  /** Line height below this makes a paragraph a wall; above it, a scatter of unrelated lines. */
  const val MIN_LINE_HEIGHT_RATIO = 1.2
  const val MAX_LINE_HEIGHT_RATIO = 2.0

  /** Sizes on a page: more than this many distinct ones is not a scale, it is an accident. */
  const val MAX_FONT_SIZES = 7

  /** Spacing values that do not fit a step are the ones that were typed by hand. */
  const val SPACING_STEP_PX = 4.0
  const val SPACING_TOLERANCE_PX = 0.6

  fun all(doc: DocumentSnapshot): List<Finding> =
    lineLength(doc) + lineHeight(doc) + fontScale(doc) + spacingGrid(doc) + fontFamilies(doc) + fontWeights(doc) +
    upperCaseText(doc) + threeDotEllipsis(doc) + textSliver(doc) + capsHeadUnderOne(doc) + smallSectionHeads(doc)

  /** A paragraph wider than the comfortable measure: the eye loses the line it was reading. */
  fun lineLength(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.textLineCount < 2 || element.text.length < MAX_LINE_CHARS) return@mapNotNull null
    if (element.fontSizePx <= 0 || element.widthPx <= 0) return@mapNotNull null
    // Rough but honest: average glyph width is about half the font size for body text.
    val charsPerLine = (element.widthPx / (element.fontSizePx * 0.5)).roundToInt()
    if (charsPerLine <= MAX_LINE_CHARS) return@mapNotNull null
    finding(DesignRuleCatalog.LINE_TOO_LONG, element, doc,
            t("design.rule.lineLong.message", "chars" to charsPerLine, "max" to MAX_LINE_CHARS),
            t("design.rule.lineLong.why"), charsPerLine.toString())
  }

  fun lineHeight(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank() || element.fontSizePx <= 0 || element.lineHeightPx <= 0) return@mapNotNull null
    if (element.textLineCount < 2) return@mapNotNull null
    val ratio = element.lineHeightPx / element.fontSizePx
    val message = when {
      ratio < MIN_LINE_HEIGHT_RATIO -> t("design.rule.lineHeight.tight", "ratio" to format(ratio))
      ratio > MAX_LINE_HEIGHT_RATIO -> t("design.rule.lineHeight.loose", "ratio" to format(ratio))
      else -> return@mapNotNull null
    }
    finding(DesignRuleCatalog.LINE_HEIGHT_OFF, element, doc, message,
            t("design.rule.lineHeight.why"), format(ratio))
  }

  /**
   * Too many distinct font sizes.
   *
   * A scale is a decision; a dozen sizes is what happens when every component picked its own. The
   * count is of DISTINCT rounded sizes, because 15.98px and 16px are the same intention.
   */
  fun fontScale(doc: DocumentSnapshot): List<Finding> {
    val sizes = doc.elements.filter { it.text.isNotBlank() && it.fontSizePx > 0 }
      .map { it.fontSizePx.roundToInt() }.distinct()
    if (sizes.size <= MAX_FONT_SIZES) return emptyList()
    return listOf(pageFinding(DesignRuleCatalog.FONT_SCALE_DRIFT, doc,
                              t("design.rule.fontScale.message", "count" to sizes.size, "max" to MAX_FONT_SIZES),
                              t("design.rule.fontScale.why"), sizes.sorted().joinToString(", ")))
  }

  /** Padding values off the step: the ones typed by hand while everything else came from a token. */
  fun spacingGrid(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val values = listOf(element.paddingPx.top, element.paddingPx.right, element.paddingPx.bottom, element.paddingPx.left)
    val offGrid = values.filter { it > 0 && !onStep(it) }
    if (offGrid.isEmpty()) return@mapNotNull null
    finding(DesignRuleCatalog.SPACING_OFF_GRID, element, doc,
            t("design.rule.spacing.message", "values" to offGrid.joinToString(", ") { format(it) },
              "step" to format(SPACING_STEP_PX)),
            t("design.rule.spacing.why"), offGrid.joinToString(", ") { format(it) })
  }

  /** More than three families on a page is a page assembled from different designs. */
  fun fontFamilies(doc: DocumentSnapshot): List<Finding> {
    val families = doc.elements.filter { it.text.isNotBlank() && it.fontFamily.isNotBlank() }
      .map { it.fontFamily.substringBefore(',').trim().trim('"', '\'').lowercase() }
      .filter { it.isNotEmpty() }
      .distinct()
    if (families.size <= MAX_FONT_FAMILIES) return emptyList()
    return listOf(pageFinding(DesignRuleCatalog.TOO_MANY_FONTS, doc,
                              t("design.rule.fonts.message", "count" to families.size, "max" to MAX_FONT_FAMILIES),
                              t("design.rule.fonts.why"), families.joinToString(", ")))
  }

  /** The same count for weights: bold, medium and light on one page are three designs agreeing by accident. */
  fun fontWeights(doc: DocumentSnapshot): List<Finding> {
    val weights = doc.elements.filter { it.text.isNotBlank() && it.fontWeight > 0 }
      .map { it.fontWeight }.distinct().sorted()
    if (weights.size <= MAX_FONT_WEIGHTS) return emptyList()
    return listOf(pageFinding(DesignRuleCatalog.TOO_MANY_WEIGHTS, doc,
                              t("design.rule.weights.message", "count" to weights.size, "max" to MAX_FONT_WEIGHTS),
                              t("design.rule.weights.why"), weights.joinToString(", ")))
  }

  /** A whole sentence in capitals: shouting that also reads slower, letter by letter. */
  fun upperCaseText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val text = element.text.trim()
    if (text.length < MIN_UPPERCASE_CHARS) return@mapNotNull null
    val letters = text.filter { it.isLetter() }
    if (letters.length < MIN_UPPERCASE_CHARS) return@mapNotNull null
    val upper = letters.count { it.isUpperCase() }
    if (upper * 100 / letters.length < UPPERCASE_SHARE_PERCENT) return@mapNotNull null
    finding(DesignRuleCatalog.SHOUTING_TEXT, element, doc,
            t("design.rule.shouting.message", "length" to text.length),
            t("design.rule.shouting.why"), text.take(60))
  }

  /**
   * Three dots typed instead of the ellipsis character.
   *
   * The two are not the same: `...` is three full stops that break at a line end, `…` is one glyph that does not.
   * Code samples are exempt — `...` in code means something of its own.
   */
  fun threeDotEllipsis(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.tag == "code" || element.tag == "pre") return@mapNotNull null
    if (!element.text.contains("...")) return@mapNotNull null
    finding(DesignRuleCatalog.THREE_DOT_ELLIPSIS, element, doc,
            t("design.rule.ellipsis.message"),
            t("design.rule.ellipsis.why"), element.text.take(60))
  }

  /** Four lines squeezed into a column narrower than sixteen font sizes: the eye has nowhere to go. */
  fun textSliver(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.textLineCount < MIN_SLIVER_LINES) return@mapNotNull null
    if (element.fontSizePx <= 0 || element.widthPx <= 0) return@mapNotNull null
    val ratio = element.widthPx / element.fontSizePx
    if (ratio >= MIN_TEXT_WIDTH_PER_FONT) return@mapNotNull null
    finding(DesignRuleCatalog.TEXT_SLIVER, element, doc,
            t("design.rule.sliver.message", "ratio" to format(ratio), "lines" to element.textLineCount),
            t("design.rule.sliver.why"), format(ratio))
  }

  /** Capitals have no descenders, so a line height under the font size makes two heading lines touch. */
  fun capsHeadUnderOne(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    val tag = element.tag.lowercase()
    if (tag != "h1" && tag != "h2") return@mapNotNull null
    if (element.textTransform != "uppercase") return@mapNotNull null
    if (element.fontSizePx <= 0 || element.lineHeightPx <= 0) return@mapNotNull null
    val ratio = element.lineHeightPx / element.fontSizePx
    if (ratio >= CAPS_LINE_HEIGHT_RATIO) return@mapNotNull null
    finding(DesignRuleCatalog.CAPS_HEAD_UNDER_1_0, element, doc,
            t("design.rule.capsHead.message", "ratio" to format(ratio)),
            t("design.rule.capsHead.why"), format(ratio))
  }

  /** Section headings that read as body text: on a phone this is a choice, on a desktop it is a lost hierarchy. */
  fun smallSectionHeads(doc: DocumentSnapshot): List<Finding> {
    if (doc.viewport != Viewport.DESKTOP) return emptyList()
    val sizes = doc.elements.filter { it.tag == "h2" && it.fontSizePx > 0 }.map { it.fontSizePx }.sorted()
    if (sizes.size < MIN_SECTION_HEADS) return emptyList()
    val median = sizes[sizes.size / 2]
    if (median >= MIN_SECTION_HEAD_PX) return emptyList()
    return listOf(pageFinding(DesignRuleCatalog.SMALL_SECTION_HEADS, doc,
                              t("design.rule.sectionHeads.message", "size" to format(median)),
                              t("design.rule.sectionHeads.why"), format(median)))
  }

  private fun onStep(value: Double): Boolean {
    val steps = value / SPACING_STEP_PX
    return abs(steps - steps.roundToInt()) * SPACING_STEP_PX <= SPACING_TOLERANCE_PX
  }

  private fun finding(rule: String, element: ElementSnapshot, doc: DocumentSnapshot,
                      message: String, why: String, evidence: String) =
    DesignFloorRules.finding(rule, Severity.WARNING, element, doc, message, why, evidence)

  private fun pageFinding(rule: String, doc: DocumentSnapshot, message: String, why: String, evidence: String) = Finding(
    rule = rule, severity = Severity.WARNING, message = message, why = why,
    selector = "html", evidence = evidence, ruleClass = DesignRuleCatalog.classOf(rule), viewport = doc.viewport,
  )

  private fun format(value: Double): String = DesignFloorRules.format(value)

  const val MAX_FONT_FAMILIES = 3

  /** Weights on one page: regular, medium, semibold and bold are already four intentions. */
  const val MAX_FONT_WEIGHTS = 3

  /** Line height under the font size: capitals touch, because they have no descenders to keep the gap. */
  const val CAPS_LINE_HEIGHT_RATIO = 1.0

  /** Text narrower than sixteen font sizes per line is a sliver rather than a column. */
  const val MIN_TEXT_WIDTH_PER_FONT = 16.0

  /** A sliver is only a defect once the column holds enough lines to read. */
  const val MIN_SLIVER_LINES = 4

  /** Section headings below this median stop reading as section headings on a desktop. */
  const val MIN_SECTION_HEAD_PX = 40.0

  /** One heading is not a set; the median only says something from a pair onwards. */
  private const val MIN_SECTION_HEADS = 2

  private const val MIN_UPPERCASE_CHARS = 25
  private const val UPPERCASE_SHARE_PERCENT = 80
}
