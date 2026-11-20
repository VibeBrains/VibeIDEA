// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignRhythmRulesTest {
  private fun element(
    selector: String = "p",
    text: String = "текст",
    fontSize: Double = 16.0,
    lineHeight: Double = 24.0,
    width: Double = 600.0,
    lines: Int = 3,
    family: String = "Inter, sans-serif",
    padding: Box = Box(8.0, 8.0, 8.0, 8.0),
    tag: String = "p",
    weight: Int = 400,
    transform: String = "none",
  ) = ElementSnapshot(
    selector = selector, tag = tag, text = text, fontSizePx = fontSize, lineHeightPx = lineHeight,
    widthPx = width, heightPx = 40.0, textLineCount = lines, fontFamily = family, paddingPx = padding,
    fontWeight = weight, textTransform = transform,
  )

  private fun page(vararg elements: ElementSnapshot) = DocumentSnapshot(
    viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList(),
  )

  private fun rules(vararg elements: ElementSnapshot) = DesignRhythmRules.all(page(*elements)).map { it.rule }

  @Test
  fun `an ordinary paragraph produces nothing`() {
    assertTrue(rules(element(text = "обычный абзац на несколько строк".repeat(4))).isEmpty())
  }

  @Test
  fun `a paragraph too wide to read comfortably is named with the number`() {
    val wide = element(text = "длинный абзац ".repeat(20), width = 1600.0)
    val finding = DesignRhythmRules.lineLength(page(wide)).single()
    assertEquals(DesignRuleCatalog.LINE_TOO_LONG, finding.rule)
    assertTrue(finding.message.contains("200"), "в сообщении должно быть измеренное число: " + finding.message)
  }

  @Test
  fun `line height is judged only where there is more than one line`() {
    // На одной строке межстрочный интервал не значит ничего, и жаловаться на него — шум.
    assertTrue(rules(element(lineHeight = 16.0, lines = 1)).isEmpty())
    assertTrue(rules(element(lineHeight = 16.0, lines = 3)).contains(DesignRuleCatalog.LINE_HEIGHT_OFF))
    assertTrue(rules(element(lineHeight = 40.0, lines = 3)).contains(DesignRuleCatalog.LINE_HEIGHT_OFF))
  }

  @Test
  fun `a dozen font sizes is not a scale`() {
    val many = (1..9).map { element(selector = "p$it", fontSize = 10.0 + it) }
    assertTrue(DesignRhythmRules.fontScale(page(*many.toTypedArray())).isNotEmpty())
    val few = (1..4).map { element(selector = "p$it", fontSize = 12.0 + it * 4) }
    assertTrue(DesignRhythmRules.fontScale(page(*few.toTypedArray())).isEmpty())
  }

  @Test
  fun `sizes that differ by rounding are one intention`() {
    // 15.98px и 16px — это одно решение, а не два размера в шкале.
    val elements = (1..9).map { element(selector = "p$it", fontSize = 16.0 - it * 0.001) }
    assertTrue(DesignRhythmRules.fontScale(page(*elements.toTypedArray())).isEmpty())
  }

  @Test
  fun `padding off the step is named, and padding on it is not`() {
    assertTrue(rules(element(padding = Box(13.0, 8.0, 8.0, 8.0))).contains(DesignRuleCatalog.SPACING_OFF_GRID))
    assertTrue(rules(element(padding = Box(16.0, 8.0, 4.0, 12.0))).isEmpty())
    // Субпиксельное округление браузера — не ручной отступ.
    assertTrue(rules(element(padding = Box(8.4, 8.0, 8.0, 8.0))).isEmpty())
  }

  @Test
  fun `a fourth font family is a page assembled from different designs`() {
    val three = listOf(
      element(selector = "a", family = "Inter"),
      element(selector = "b", family = "Georgia"),
      element(selector = "c", family = "Comic Sans MS"),
    )
    // Three families are three roles: body, display and code. The threshold from VibeIDE is three, not two.
    assertTrue(DesignRhythmRules.fontFamilies(page(*three.toTypedArray())).isEmpty())
    val four = three + element(selector = "d", family = "Courier New")
    assertTrue(DesignRhythmRules.fontFamilies(page(*four.toTypedArray())).isNotEmpty())
  }

  @Test
  fun `four font weights are a page with no accent`() {
    val three = listOf(400, 500, 700).mapIndexed { i, w -> element(selector = "p$i", weight = w) }
    assertTrue(rules(*three.toTypedArray()).none { it == DesignRuleCatalog.TOO_MANY_WEIGHTS })
    val four = three + element(selector = "p3", weight = 300)
    assertTrue(rules(*four.toTypedArray()).contains(DesignRuleCatalog.TOO_MANY_WEIGHTS))
  }

  @Test
  fun `three dots are an ellipsis only in prose, never in code`() {
    assertTrue(rules(element(text = "Подробнее...")).contains(DesignRuleCatalog.THREE_DOT_ELLIPSIS))
    // «…» is a single glyph and cannot be split by a line break.
    assertTrue(rules(element(text = "Подробнее…")).none { it == DesignRuleCatalog.THREE_DOT_ELLIPSIS })
    assertTrue(rules(element(text = "rest...args", tag = "code")).none { it == DesignRuleCatalog.THREE_DOT_ELLIPSIS })
  }

  @Test
  fun `a sliver is four lines in a column narrower than sixteen font sizes`() {
    // 200px / 16px = 12.5 — already a strip of text.
    assertTrue(rules(element(width = 200.0, lines = 4)).contains(DesignRuleCatalog.TEXT_SLIVER))
    // The same column with three lines is a caption, not a paragraph.
    assertTrue(rules(element(width = 200.0, lines = 3)).none { it == DesignRuleCatalog.TEXT_SLIVER })
    assertTrue(rules(element(width = 400.0, lines = 4)).none { it == DesignRuleCatalog.TEXT_SLIVER })
  }

  @Test
  fun `capitals with a line height below the font size touch each other`() {
    val tight = element(tag = "h2", transform = "uppercase", fontSize = 24.0, lineHeight = 20.0,
                        text = "Заголовок", lines = 1, width = 600.0)
    assertTrue(DesignRhythmRules.capsHeadUnderOne(page(tight)).single().rule == DesignRuleCatalog.CAPS_HEAD_UNDER_1_0)
    val loose = tight.copy(lineHeightPx = 26.0)
    assertTrue(DesignRhythmRules.capsHeadUnderOne(page(loose)).isEmpty())
    val lowerCase = tight.copy(textTransform = "none")
    assertTrue(DesignRhythmRules.capsHeadUnderOne(page(lowerCase)).isEmpty())
  }

  @Test
  fun `small section headings are judged on a desktop only`() {
    val heads = arrayOf(
      element(selector = "h2a", tag = "h2", fontSize = 28.0, text = "Раздел", lines = 1),
      element(selector = "h2b", tag = "h2", fontSize = 30.0, text = "Раздел", lines = 1),
    )
    assertTrue(DesignRhythmRules.smallSectionHeads(page(*heads)).isNotEmpty())
    // On a phone 28px is a heading; the desktop threshold says nothing there.
    val mobile = page(*heads).copy(viewport = Viewport.MOBILE)
    assertTrue(DesignRhythmRules.smallSectionHeads(mobile).isEmpty())
    val big = heads.map { it.copy(fontSizePx = 44.0) }.toTypedArray()
    assertTrue(DesignRhythmRules.smallSectionHeads(page(*big)).isEmpty())
  }

  @Test
  fun `an all-caps sentence is named, a short label is not`() {
    // «SALE» — это метка, а не крик; предложение капслоком — крик.
    assertTrue(rules(element(text = "ЭТО ОЧЕНЬ ВАЖНОЕ СООБЩЕНИЕ ДЛЯ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ"))
                 .contains(DesignRuleCatalog.SHOUTING_TEXT))
    assertTrue(rules(element(text = "SALE")).isEmpty())
  }

  @Test
  fun `every rhythm rule is in the catalogue and is style, not floor`() {
    // Ритм — вкус: проект вправе нарушить его осознанно, и тогда он пишет об этом в своём файле.
    val produced = listOf(
      DesignRuleCatalog.LINE_TOO_LONG, DesignRuleCatalog.LINE_HEIGHT_OFF, DesignRuleCatalog.FONT_SCALE_DRIFT,
      DesignRuleCatalog.SPACING_OFF_GRID, DesignRuleCatalog.TOO_MANY_FONTS, DesignRuleCatalog.TOO_MANY_WEIGHTS,
      DesignRuleCatalog.SHOUTING_TEXT, DesignRuleCatalog.THREE_DOT_ELLIPSIS, DesignRuleCatalog.TEXT_SLIVER,
      DesignRuleCatalog.CAPS_HEAD_UNDER_1_0, DesignRuleCatalog.SMALL_SECTION_HEADS,
    )
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced))
    assertTrue(produced.none { DesignRuleCatalog.isFloor(it) })
  }
}
