// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.math.abs

/**
 * The quality floor: defects that are defects at any taste.
 *
 * Two disciplines run through every rule here. First, each finding carries the measured number, so
 * it can be disputed by re-measuring instead of by opinion. Second, a rule stays SILENT when it
 * cannot know — an unreadable stylesheet, a missing measurement, an ambiguous case. A detector that
 * accuses on a guess is one people stop reading, and then the floor protects nothing.
 */
object DesignFloorRules {
  /** WCAG AA: 4.5:1 for body text, 3:1 for large text. */
  const val CONTRAST_AA_SMALL = 4.5
  const val CONTRAST_AA_LARGE = 3.0

  /** Below this, body text stops being comfortable on a phone. */
  const val MIN_BODY_FONT_PX = 12.0

  /** Both dimensions of a tap target, in CSS px — the widely used floor. */
  const val MIN_TAP_TARGET_PX = 24.0

  /** Ignore hairline overflow: sub-pixel rounding is not a defect. */
  private const val OVERFLOW_TOLERANCE_PX = 2.0

  fun all(doc: DocumentSnapshot): List<Finding> =
    contrast(doc) + tinyText(doc) + tapTargets(doc) + clipped(doc) + occluded(doc) +
    pageOverflow(doc) + brokenImages(doc) + focusRing(doc) + disabledLook(doc) + headings(doc)

  // --- readability ---

  fun contrast(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank() || element.fontSizePx <= 0) return@mapNotNull null
    // A transparent own background means the collector already resolved what is actually behind.
    val ratio = DesignColor.contrast(element.color, element.backgroundColor)
    val large = DesignColor.isLargeText(element.fontSizePx, element.fontWeight)
    val required = if (large) CONTRAST_AA_LARGE else CONTRAST_AA_SMALL
    if (ratio >= required) return@mapNotNull null
    finding(
      DesignRuleCatalog.CONTRAST_TEXT, Severity.ERROR, element, doc,
      message = "Контраст текста ${format(ratio)}:1 при норме ${format(required)}:1",
      why = "Текст с таким контрастом не читается при ярком свете и людьми со слабым зрением.",
      evidence = "${format(ratio)}:1, кегль ${format(element.fontSizePx)}px, вес ${element.fontWeight}",
    )
  }

  fun tinyText(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.text.isBlank() || element.fontSizePx <= 0 || element.fontSizePx >= MIN_BODY_FONT_PX) return@mapNotNull null
    finding(
      DesignRuleCatalog.TEXT_TOO_SMALL, Severity.ERROR, element, doc,
      message = "Кегль ${format(element.fontSizePx)}px — меньше минимальных ${format(MIN_BODY_FONT_PX)}px",
      why = "Такой текст не прочитать с руки на телефоне без масштабирования.",
      evidence = "${format(element.fontSizePx)}px",
    )
  }

  // --- reachability ---

  fun tapTargets(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    // A zero box is an element that is not laid out (hidden, detached) — not a small target.
    if (element.widthPx <= 0 || element.heightPx <= 0) return@mapNotNull null
    if (element.widthPx >= MIN_TAP_TARGET_PX && element.heightPx >= MIN_TAP_TARGET_PX) return@mapNotNull null
    finding(
      DesignRuleCatalog.TAP_TARGET_TOO_SMALL, Severity.ERROR, element, doc,
      message = "Зона нажатия ${format(element.widthPx)}×${format(element.heightPx)} px",
      why = "В такую цель не попасть пальцем: промах читается как «интерфейс не работает».",
      evidence = "${format(element.widthPx)}×${format(element.heightPx)} px при норме от ${format(MIN_TAP_TARGET_PX)}",
    )
  }

  fun clipped(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.clientWidthPx <= 0) return@mapNotNull null
    val overflow = element.scrollWidthPx - element.clientWidthPx
    if (overflow <= OVERFLOW_TOLERANCE_PX) return@mapNotNull null
    // Scrollable by design is not clipped: the content can be reached.
    if (element.overflowX == "auto" || element.overflowX == "scroll") return@mapNotNull null
    finding(
      DesignRuleCatalog.CONTENT_CLIPPED, Severity.ERROR, element, doc,
      message = "Содержимое шире контейнера на ${format(overflow)}px и обрезано",
      why = "Часть текста или элементов не увидеть и не достать прокруткой.",
      evidence = "scrollWidth ${format(element.scrollWidthPx)} > clientWidth ${format(element.clientWidthPx)}",
    )
  }

  /**
   * Text hidden under an opaque layer.
   *
   * Kinship is checked by [ElementSnapshot.parentId], never by selector prefix: an ancestor may be
   * recorded from `body` and a descendant from another node, so string comparison declared ordinary
   * nesting an occlusion. That bug is the reason the numeric field exists at all.
   */
  fun occluded(doc: DocumentSnapshot): List<Finding> {
    val elements = doc.elements
    return elements.mapIndexedNotNull { index, element ->
      if (element.text.isBlank()) return@mapIndexedNotNull null
      val covering = elements.firstOrNull { other ->
        other !== element &&
        other.ownBackgroundAlpha >= 0.9 &&
        other.zIndex > element.zIndex &&
        !isAncestor(elements, other, index) &&
        overlaps(other, element)
      } ?: return@mapIndexedNotNull null
      finding(
        DesignRuleCatalog.ELEMENT_OCCLUDED, Severity.ERROR, element, doc,
        message = "Текст перекрыт непрозрачным слоем «${covering.selector}»",
        why = "Содержимое есть в разметке, но его не видно — читатель считает, что оно пропало.",
        evidence = "z-index ${covering.zIndex} против ${element.zIndex}, alpha фона ${format(covering.ownBackgroundAlpha)}",
      )
    }
  }

  fun pageOverflow(doc: DocumentSnapshot): List<Finding> {
    if (doc.documentScrollWidthPx <= 0 || doc.viewportWidthPx <= 0) return emptyList()
    val overflow = doc.documentScrollWidthPx - doc.viewportWidthPx
    if (overflow <= OVERFLOW_TOLERANCE_PX) return emptyList()
    return listOf(
      Finding(
        rule = DesignRuleCatalog.PAGE_WIDER_THAN_VIEWPORT,
        severity = Severity.ERROR,
        message = "Страница шире окна на ${format(overflow)}px — появилась горизонтальная прокрутка",
        why = "Горизонтальная прокрутка на телефоне ломает чтение: строки уезжают за край.",
        selector = "html",
        evidence = "документ ${format(doc.documentScrollWidthPx)}px при окне ${format(doc.viewportWidthPx)}px",
        ruleClass = RuleClass.FLOOR,
        viewport = doc.viewport,
      )
    )
  }

  fun brokenImages(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (element.tag != "img") return@mapNotNull null
    if (element.imgSrc.isBlank()) return@mapNotNull null
    if (element.imgNaturalWidthPx > 0) return@mapNotNull null
    finding(
      DesignRuleCatalog.BROKEN_IMAGE, Severity.ERROR, element, doc,
      message = "Изображение не загрузилось",
      why = "На месте картинки пустая рамка или значок ошибки — страница выглядит сломанной.",
      evidence = element.imgSrc.take(120),
    )
  }

  // --- states ---

  fun focusRing(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.interactive || element.disabled) return@mapNotNull null
    // Could not read the stylesheets: "no rule" would mean "did not look", so say nothing.
    if (element.styleRulesUnreadable) return@mapNotNull null
    if (element.outlineStyle != "none" && element.outlineWidthPx > 0) return@mapNotNull null
    if (element.hasFocusRule) return@mapNotNull null
    finding(
      DesignRuleCatalog.FOCUS_RING_REMOVED, Severity.ERROR, element, doc,
      message = "Обводка фокуса снята и ничем не заменена",
      why = "С клавиатуры не видно, где находишься: страница становится непроходимой без мыши.",
      evidence = "outline: ${element.outlineStyle}, правила :focus нет",
    )
  }

  fun disabledLook(doc: DocumentSnapshot): List<Finding> = doc.elements.mapNotNull { element ->
    if (!element.disabled) return@mapNotNull null
    if (element.text.isBlank()) return@mapNotNull null
    val ratio = DesignColor.contrast(element.color, element.backgroundColor)
    // A disabled control must read as disabled: full-strength contrast makes it look clickable.
    if (ratio < CONTRAST_AA_SMALL) return@mapNotNull null
    finding(
      DesignRuleCatalog.DISABLED_LOOKS_ENABLED, Severity.WARNING, element, doc,
      message = "Выключенный элемент выглядит как рабочий (контраст ${format(ratio)}:1)",
      why = "Человек нажимает и ничего не происходит — он решает, что интерфейс сломался.",
      evidence = "контраст ${format(ratio)}:1 при disabled",
    )
  }

  // --- structure ---

  fun headings(doc: DocumentSnapshot): List<Finding> {
    val levels = doc.headings.mapNotNull { heading ->
      heading.tag.lowercase().removePrefix("h").toIntOrNull()?.let { it to heading }
    }
    val findings = ArrayList<Finding>()
    var previous = 0
    for ((level, heading) in levels) {
      if (previous != 0 && level > previous + 1) {
        findings.add(
          Finding(
            rule = DesignRuleCatalog.HEADING_LEVEL_SKIPPED,
            severity = Severity.WARNING,
            message = "Уровень заголовка перепрыгнут: h$previous → h$level",
            why = "Программа чтения строит по заголовкам оглавление — пропуск ломает навигацию по странице.",
            selector = "h$level",
            evidence = "«${heading.text.take(60)}»",
            ruleClass = RuleClass.FLOOR,
            viewport = doc.viewport,
          )
        )
      }
      previous = level
    }
    return findings
  }

  // --- helpers ---

  private fun isAncestor(elements: List<ElementSnapshot>, candidate: ElementSnapshot, childIndex: Int): Boolean {
    var cursor = elements.getOrNull(childIndex)?.parentId ?: -1
    while (cursor >= 0) {
      val parent = elements.getOrNull(cursor) ?: return false
      if (parent === candidate) return true
      cursor = parent.parentId
    }
    return false
  }

  private fun overlaps(a: ElementSnapshot, b: ElementSnapshot): Boolean {
    val aRight = a.leftPx + a.widthPx
    val aBottom = a.topPx + a.heightPx
    val bRight = b.leftPx + b.widthPx
    val bBottom = b.topPx + b.heightPx
    if (a.widthPx <= 0 || a.heightPx <= 0 || b.widthPx <= 0 || b.heightPx <= 0) return false
    return a.leftPx < bRight && aRight > b.leftPx && a.topPx < bBottom && aBottom > b.topPx
  }

  internal fun finding(
    rule: String, severity: Severity, element: ElementSnapshot, doc: DocumentSnapshot,
    message: String, why: String, evidence: String,
  ) = Finding(
    rule = rule,
    severity = severity,
    message = message,
    why = why,
    selector = element.selector,
    evidence = evidence,
    ruleClass = DesignRuleCatalog.classOf(rule),
    viewport = doc.viewport,
  )

  internal fun format(value: Double): String =
    if (abs(value - Math.round(value)) < 0.05) Math.round(value).toString() else String.format("%.1f", value)
}
