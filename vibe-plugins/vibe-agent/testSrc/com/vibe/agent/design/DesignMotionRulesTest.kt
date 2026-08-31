// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignMotionRulesTest {
  private fun element(
    selector: String = ".card",
    animation: String = "",
    animationMs: Double = 0.0,
    iterations: String = "1",
    timing: String = "ease-out",
    transitionProperty: String = "opacity",
    transitionMs: Double = 200.0,
    interactive: Boolean = false,
    hover: Boolean = false,
    active: Boolean = false,
    classes: List<String> = emptyList(),
  ) = ElementSnapshot(
    selector = selector, tag = "div", classes = classes, animationName = animation,
    animationDurationMs = animationMs, animationIterationCount = iterations, animationTimingFunction = timing,
    transitionProperty = transitionProperty, transitionDurationMs = transitionMs,
    interactive = interactive, hasHoverRule = hover, hasActiveRule = active,
    widthPx = 200.0, heightPx = 100.0,
  )

  private fun page(vararg elements: ElementSnapshot, reducedMotion: Boolean = true) = DocumentSnapshot(
    viewportWidthPx = 1280.0, viewportHeightPx = 800.0, elements = elements.toList(),
    hasReducedMotionRule = reducedMotion,
  )

  private fun rules(vararg elements: ElementSnapshot) = DesignMotionRules.all(page(*elements)).map { it.rule }

  @Test
  fun `спокойный элемент без интерактива не даёт находок`() {
    assertEquals(emptyList(), rules(element()))
  }

  @Test
  fun `слишком долгое и слишком быстрое движение`() {
    assertTrue(DesignRuleCatalog.ANIMATION_TOO_SLOW in rules(element(animation = "fade", animationMs = 900.0)))
    assertTrue(DesignRuleCatalog.TRANSITION_TOO_SLOW in rules(element(transitionMs = 900.0)))
    assertTrue(DesignRuleCatalog.ANIMATION_TOO_FAST in rules(element(transitionMs = 40.0)))
    // Ноль — это «анимации нет», решение, а не дефект.
    assertTrue(DesignRuleCatalog.ANIMATION_TOO_FAST !in rules(element(transitionMs = 0.0)))
  }

  @Test
  fun `бесконечная анимация — находка, но не у индикатора ожидания`() {
    assertTrue(DesignRuleCatalog.INFINITE_ANIMATION in
               rules(element(animation = "float", animationMs = 3000.0, iterations = "infinite")))
    assertTrue(DesignRuleCatalog.INFINITE_ANIMATION !in
               rules(element(animation = "spin", animationMs = 1000.0, iterations = "infinite")))
    assertTrue(DesignRuleCatalog.INFINITE_ANIMATION !in
               rules(element(animation = "rotate", animationMs = 1000.0, iterations = "infinite", classes = listOf("loader"))))
  }

  @Test
  fun `у крутилки не придираемся ни к длительности, ни к линейности`() {
    val spinner = element(animation = "spin", animationMs = 1200.0, iterations = "infinite", timing = "linear")
    val found = rules(spinner)
    assertTrue(DesignRuleCatalog.ANIMATION_TOO_SLOW !in found)
    assertTrue(DesignRuleCatalog.LINEAR_EASING !in found)
  }

  @Test
  fun `линейное ускорение у разовой анимации`() {
    assertTrue(DesignRuleCatalog.LINEAR_EASING in rules(element(animation = "fadeIn", animationMs = 300.0, timing = "linear")))
    assertTrue(DesignRuleCatalog.LINEAR_EASING !in rules(element(animation = "fadeIn", animationMs = 300.0)))
  }

  @Test
  fun `transition all и наведение без перехода`() {
    assertTrue(DesignRuleCatalog.TRANSITION_ALL in rules(element(transitionProperty = "all")))
    // Без длительности transition: all ничего не анимирует — придираться не к чему.
    assertTrue(DesignRuleCatalog.TRANSITION_ALL !in rules(element(transitionProperty = "all", transitionMs = 0.0)))
    val snapping = element(interactive = true, hover = true, active = true, transitionMs = 0.0)
    assertTrue(DesignRuleCatalog.HOVER_WITHOUT_TRANSITION in rules(snapping))
  }

  @Test
  fun `кнопка без отклика на нажатие`() {
    val button = element(selector = "button", interactive = true, active = false)
    assertTrue(DesignRuleCatalog.NO_PRESS_FEEDBACK in rules(button))
    assertTrue(DesignRuleCatalog.NO_PRESS_FEEDBACK !in rules(button.copy(hasActiveRule = true)))
  }

  @Test
  fun `правило нечитаемых стилей молчит, а не обвиняет`() {
    val unreadable = element(interactive = true).copy(styleRulesUnreadable = true)
    val found = rules(unreadable)
    assertTrue(DesignRuleCatalog.NO_PRESS_FEEDBACK !in found, "стили не прочитаны — это «не посмотрели», а не «нет правила»")
    assertTrue(DesignMotionRules.reducedMotion(page(unreadable, reducedMotion = false)).isEmpty())
  }

  @Test
  fun `движение без ответа на prefers-reduced-motion`() {
    val moving = element(transitionMs = 300.0)
    assertTrue(DesignMotionRules.reducedMotion(page(moving, reducedMotion = false)).isNotEmpty())
    assertTrue(DesignMotionRules.reducedMotion(page(moving, reducedMotion = true)).isEmpty())
    // Страница без движения ничего не должна: правило не о наличии медиазапроса ради него самого.
    assertTrue(DesignMotionRules.reducedMotion(page(element(transitionMs = 0.0), reducedMotion = false)).isEmpty())
  }

  @Test
  fun `все выданные правила есть в каталоге`() {
    val produced = rules(element(animation = "float", animationMs = 900.0, iterations = "infinite",
                                 timing = "linear", transitionProperty = "all", transitionMs = 30.0,
                                 interactive = true, hover = true)).toSet()
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced), "вне каталога: " + (produced - DesignRuleCatalog.ALL.toSet()))
  }
}
