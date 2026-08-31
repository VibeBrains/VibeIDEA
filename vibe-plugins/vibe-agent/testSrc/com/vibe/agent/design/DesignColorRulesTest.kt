// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignColorRulesTest {
  private val ink = Rgb(30, 34, 40)
  private val paper = Rgb(255, 255, 255)

  private fun text(
    selector: String = "p",
    tag: String = "p",
    color: Rgb = ink,
    background: Rgb = paper,
    parentId: Int = -1,
    decoration: String = "none",
    weight: Int = 400,
    body: String = "текст",
  ) = ElementSnapshot(
    selector = selector, tag = tag, text = body, color = color, backgroundColor = background,
    parentId = parentId, textDecorationLine = decoration, fontWeight = weight,
    widthPx = 400.0, heightPx = 40.0,
  )

  private fun page(vararg elements: ElementSnapshot) = DocumentSnapshot(
    viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList(),
  )

  private fun rules(vararg elements: ElementSnapshot) = DesignColorRules.all(page(*elements)).map { it.rule }

  @Test
  fun `обычный абзац не даёт находок`() {
    assertEquals(emptyList(), rules(text()))
  }

  @Test
  fun `палитра считается, а не оценивается на глаз`() {
    val many = (0..9).map { text(color = Rgb(10 * it, 20 + it, 30 + it)) }
    assertTrue(DesignRuleCatalog.TOO_MANY_TEXT_COLORS in DesignColorRules.textColorCount(page(*many.toTypedArray())).map { it.rule })
    assertTrue(DesignColorRules.textColorCount(page(text(), text(color = Rgb(90, 90, 90)))).isEmpty())
  }

  @Test
  fun `много насыщенных оттенков — акцентом не является ни один`() {
    val hues = listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 255, 0), Rgb(255, 0, 255), Rgb(0, 255, 255))
    val elements = hues.map { text(color = it) }
    assertTrue(DesignColorRules.accentHues(page(*elements.toTypedArray())).isNotEmpty())
    // Серые не считаются: у них нет оттенка, который стоило бы считать.
    val greys = (0..9).map { text(color = Rgb(20 * it, 20 * it, 20 * it)) }
    assertTrue(DesignColorRules.accentHues(page(*greys.toTypedArray())).isEmpty())
  }

  @Test
  fun `чистый чёрный на чистом белом`() {
    assertTrue(DesignRuleCatalog.PURE_BLACK_ON_WHITE in rules(text(color = Rgb(0, 0, 0))))
    assertTrue(DesignRuleCatalog.PURE_BLACK_ON_WHITE !in rules(text(color = Rgb(17, 17, 17))))
  }

  @Test
  fun `насыщенная заливка на пол-экрана`() {
    val hero = ElementSnapshot(selector = ".hero", tag = "div", backgroundColor = Rgb(255, 0, 90),
                               ownBackgroundAlpha = 1.0, widthPx = 1280.0, heightPx = 500.0)
    assertTrue(DesignRuleCatalog.SATURATED_LARGE_AREA in rules(hero))
    // Тот же цвет на кнопке — это акцент, а не вибрирующее полотно.
    assertTrue(DesignRuleCatalog.SATURATED_LARGE_AREA !in rules(hero.copy(widthPx = 120.0, heightPx = 40.0)))
  }

  @Test
  fun `ссылка в тексте, отличающаяся только цветом`() {
    val paragraph = text(selector = "p", tag = "p")
    val link = text(selector = "p > a", tag = "a", color = Rgb(0, 90, 200), parentId = 0)
    val found = rules(paragraph, link)
    assertTrue(DesignRuleCatalog.LINK_BY_COLOR_ONLY in found)
    assertTrue(DesignRuleCatalog.LINK_BY_COLOR_ONLY !in rules(paragraph, link.copy(textDecorationLine = "underline")))
    // Пункт меню находят по месту, а не по цвету, — придираться незачем.
    val nav = text(selector = "nav", tag = "nav")
    assertTrue(DesignRuleCatalog.LINK_BY_COLOR_ONLY !in rules(nav, link.copy(selector = "nav > a")))
  }

  @Test
  fun `ссылка цвета обычного текста не находится вообще никем`() {
    val paragraph = text(selector = "p", tag = "p")
    val link = text(selector = "p > a", tag = "a", parentId = 0)
    assertTrue(DesignRuleCatalog.LINK_SAME_COLOR_AS_TEXT in rules(paragraph, link))
    assertTrue(DesignRuleCatalog.LINK_SAME_COLOR_AS_TEXT !in rules(paragraph, link.copy(color = Rgb(0, 90, 200))))
  }

  @Test
  fun `невидимая рамка и бледная иконка`() {
    val card = ElementSnapshot(selector = ".card", tag = "div", backgroundColor = paper,
                               borderColor = Rgb(252, 252, 252), borderWidthPx = 1.0, widthPx = 200.0, heightPx = 100.0)
    assertTrue(DesignRuleCatalog.BORDER_INVISIBLE in rules(card))
    assertTrue(DesignRuleCatalog.BORDER_INVISIBLE !in rules(card.copy(borderColor = Rgb(180, 180, 180))))
    // Рамки нет вовсе — правило молчит, а не считает контраст с null.
    assertTrue(DesignRuleCatalog.BORDER_INVISIBLE !in rules(card.copy(borderColor = null)))

    val icon = ElementSnapshot(selector = "button > svg", tag = "svg", svgShapeCount = 2,
                               color = Rgb(215, 215, 215), backgroundColor = paper, widthPx = 24.0, heightPx = 24.0)
    assertTrue(DesignRuleCatalog.ICON_LOW_CONTRAST in rules(icon))
    assertTrue(DesignRuleCatalog.ICON_LOW_CONTRAST !in rules(icon.copy(color = Rgb(60, 60, 60))))
  }

  @Test
  fun `текст на фотографии без подложки`() {
    val hero = ElementSnapshot(selector = ".hero", tag = "div", backgroundImage = "url(\"/hero.jpg\")",
                               widthPx = 1280.0, heightPx = 400.0)
    val caption = text(selector = ".hero h1", tag = "h1", parentId = 0)
    assertTrue(DesignRuleCatalog.TEXT_ON_IMAGE_WITHOUT_SCRIM in rules(hero, caption))
    assertTrue(DesignRuleCatalog.TEXT_ON_IMAGE_WITHOUT_SCRIM !in
               rules(hero, caption.copy(ownBackgroundAlpha = 0.7)))
  }

  @Test
  fun `рамка фокуса, которую видно только в спецификации`() {
    val button = ElementSnapshot(selector = "button", tag = "button", interactive = true,
                                 backgroundColor = paper, outlineStyle = "solid", outlineWidthPx = 2.0,
                                 outlineColor = Rgb(245, 245, 245), widthPx = 120.0, heightPx = 40.0)
    assertTrue(DesignRuleCatalog.FOCUS_RING_LOW_CONTRAST in rules(button))
    assertTrue(DesignRuleCatalog.FOCUS_RING_LOW_CONTRAST !in rules(button.copy(outlineColor = Rgb(20, 90, 220))))
    // Рамки нет вовсе — это другое правило, и здесь мы молчим, а не обвиняем дважды.
    assertTrue(DesignRuleCatalog.FOCUS_RING_LOW_CONTRAST !in rules(button.copy(outlineStyle = "none")))
  }

  @Test
  fun `все выданные правила есть в каталоге`() {
    val produced = rules(text(color = Rgb(0, 0, 0))).toSet()
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced), "вне каталога: " + (produced - DesignRuleCatalog.ALL.toSet()))
  }
}
