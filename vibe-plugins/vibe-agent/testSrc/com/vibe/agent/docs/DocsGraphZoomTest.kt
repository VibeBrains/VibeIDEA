// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Зум и вписывание: то, что ломается молча и видно только в тесте. */
class DocsGraphZoomTest {
  @Test
  fun `вписывание оставляет поля и не растягивает мелкий граф`() {
    // Большой граф ужимается…
    val small = DocsGraphZoom.fit(graphWidth = 2000, graphHeight = 1000, viewWidth = 1000, viewHeight = 800, margin = 20)
    assertTrue(small < 1.0 && small > 0.0, "масштаб: $small")
    assertTrue(2000 * small <= 1000 - 40 + 1, "граф с полями обязан помещаться по ширине")
    // …а маленький остаётся как есть: буквы в палец высотой — не «вписан», а испорчен.
    assertEquals(1.0, DocsGraphZoom.fit(200, 100, 1000, 800, 20))
  }

  @Test
  fun `пустые размеры не делят на ноль`() {
    assertEquals(1.0, DocsGraphZoom.fit(0, 0, 100, 100))
    assertEquals(1.0, DocsGraphZoom.fit(100, 100, 0, 0))
  }

  @Test
  fun `масштаб зажат пределами`() {
    assertEquals(DocsGraphZoom.MAX, DocsGraphZoom.clamp(99.0))
    assertEquals(DocsGraphZoom.MIN, DocsGraphZoom.clamp(0.0001))
    assertEquals(1.5, DocsGraphZoom.clamp(1.5))
  }

  @Test
  fun `точка под курсором остаётся на месте`() {
    // Иначе граф убегает от мыши, и его ловят после каждого щелчка колеса.
    val offset = 100
    val cursor = 300
    val moved = DocsGraphZoom.zoomAt(offset, cursor, oldScale = 1.0, newScale = 2.0)
    // Позиция точки графа под курсором до и после: (cursor - offset)/scale.
    val before = (cursor - offset) / 1.0
    val after = (cursor - moved) / 2.0
    assertEquals(before, after, 1.0)
  }

  @Test
  fun `центрирование ставит граф ровно посередине`() {
    val (x, y) = DocsGraphZoom.center(graphWidth = 400, graphHeight = 200, viewWidth = 1000, viewHeight = 600, scale = 1.0)
    assertEquals(300, x)
    assertEquals(200, y)
  }

  @Test
  fun `граф больше окна центрируется отрицательным сдвигом`() {
    // Не ноль: иначе видно только левый верхний угол, и «вписать» не вписывает.
    val (x, _) = DocsGraphZoom.center(2000, 100, 1000, 600, scale = 1.0)
    assertEquals(-500, x)
  }
}
