// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Состояния и исключения: контраст под курсором и в фокусе, зона нажатия по правилам WCAG 2.2,
 * контент, исчезающий при выключенном движении. Взято из разбора чужого набора дизайн-гейтов 18.09.2026.
 */
class DesignStateRulesTest {
  private fun doc(vararg elements: ElementSnapshot) = DocumentSnapshot(
    url = "http://localhost:3000", viewportWidthPx = 1280.0, viewportHeightPx = 800.0,
    viewport = Viewport.DESKTOP, documentScrollWidthPx = 1280.0, elements = elements.toList(),
  )

  private fun button(
    selector: String = "button.primary",
    width: Double = 120.0, height: Double = 40.0, left: Double = 0.0, top: Double = 0.0,
  ) = ElementSnapshot(
    selector = selector, tag = "button", text = "Отправить", fontSizePx = 16.0, interactive = true,
    color = Rgb(255, 255, 255), backgroundColor = Rgb(20, 60, 160),
    widthPx = width, heightPx = height, leftPx = left, topPx = top,
  )

  @Test
  fun `контраст под курсором меряется, хотя в покое элемент читается`() {
    // Вторичная кнопка перехватывает на наведении светлую заливку: в покое 8:1, под курсором 1,5:1.
    val element = button().copy(hasHoverRule = true, hoverColor = Rgb(255, 255, 255), hoverBackgroundColor = Rgb(200, 200, 200))
    val findings = DesignFloorRules.contrastInStates(doc(element))
    assertEquals(listOf(DesignRuleCatalog.CONTRAST_STATE), findings.map { it.rule })
    assertTrue(findings.single().message.contains("hover"), findings.single().message)
    // В покое правило контраста молчит — значит, без нового правила дефект был невидим.
    assertEquals(emptyList(), DesignFloorRules.contrast(doc(element)).map { it.rule })
  }

  @Test
  fun `состояние без снятых цветов молчит, а достаточный контраст не обвиняется`() {
    assertEquals(emptyList(), DesignFloorRules.contrastInStates(doc(button().copy(hasHoverRule = true))))
    val fine = button().copy(focusColor = Rgb(255, 255, 255), focusBackgroundColor = Rgb(10, 10, 10))
    assertEquals(emptyList(), DesignFloorRules.contrastInStates(doc(fine)))
  }

  @Test
  fun `ссылка в строке текста и контрол с подписью не обвиняются в мелкой зоне`() {
    val small = button(selector = "a.inline", width = 18.0, height = 14.0).copy(tag = "a", display = "inline")
    assertEquals(listOf(DesignRuleCatalog.TAP_TARGET_TOO_SMALL), DesignFloorRules.tapTargets(doc(small)).map { it.rule })
    assertEquals(emptyList(), DesignFloorRules.tapTargets(doc(small.copy(insideTextLine = true))))

    val checkbox = button(selector = "input#agree", width = 16.0, height = 16.0).copy(tag = "input")
    assertEquals(1, DesignFloorRules.tapTargets(doc(checkbox)).size)
    assertEquals(emptyList(), DesignFloorRules.tapTargets(doc(checkbox.copy(labelUnionWidthPx = 180.0, labelUnionHeightPx = 26.0))))
  }

  @Test
  fun `исключение «разнос от соседей» намеренно не взято`() {
    // WCAG прощает мелкую цель, стоящую далеко от других. Наш пол — нет: в одинокую кнопку 16×16
    // на телефоне всё равно не попасть, а пол отвечает на вопрос «можно ли попасть».
    val lonely = button(selector = "button.tiny", width = 16.0, height = 16.0)
    assertEquals(listOf(DesignRuleCatalog.TAP_TARGET_TOO_SMALL), DesignFloorRules.tapTargets(doc(lonely)).map { it.rule })
  }

  @Test
  fun `контент, исчезающий при выключенном движении, назван, а видимый — нет`() {
    val hidden = ElementSnapshot(
      selector = "section.reveal", tag = "section", text = "Наши цены", fontSizePx = 18.0,
      opacity = 0.0, animationDurationMs = 400.0, reduceSilencesAnimation = true,
    )
    assertEquals(listOf(DesignRuleCatalog.REDUCED_MOTION_HIDES), DesignMotionRules.reducedMotionHides(doc(hidden)).map { it.rule })
    assertEquals(emptyList(), DesignMotionRules.reducedMotionHides(doc(hidden.copy(opacity = 1.0))))
    assertEquals(emptyList(), DesignMotionRules.reducedMotionHides(doc(hidden.copy(reduceSilencesAnimation = false))))
  }

  @Test
  fun `оба новых правила — пол качества, а не вкус`() {
    assertTrue(DesignRuleCatalog.isFloor(DesignRuleCatalog.CONTRAST_STATE))
    assertTrue(DesignRuleCatalog.isFloor(DesignRuleCatalog.REDUCED_MOTION_HIDES))
    assertTrue(DesignRuleCatalog.CONTRAST_STATE in DesignRuleCatalog.ALL)
    assertTrue(DesignRuleCatalog.REDUCED_MOTION_HIDES in DesignRuleCatalog.ALL)
  }
}
