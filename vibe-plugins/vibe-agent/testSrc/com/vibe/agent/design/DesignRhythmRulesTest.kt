// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
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
  ) = ElementSnapshot(
    selector = selector, tag = "p", text = text, fontSizePx = fontSize, lineHeightPx = lineHeight,
    widthPx = width, heightPx = 40.0, textLineCount = lines, fontFamily = family, paddingPx = padding,
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
  fun `a third font family is a page assembled from two designs`() {
    val elements = listOf(
      element(selector = "a", family = "Inter"),
      element(selector = "b", family = "Georgia"),
      element(selector = "c", family = "Comic Sans MS"),
    )
    assertTrue(DesignRhythmRules.fontFamilies(page(*elements.toTypedArray())).isNotEmpty())
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
      DesignRuleCatalog.SPACING_OFF_GRID, DesignRuleCatalog.TOO_MANY_FONTS, DesignRuleCatalog.SHOUTING_TEXT,
    )
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced))
    assertTrue(produced.none { DesignRuleCatalog.isFloor(it) })
  }
}
