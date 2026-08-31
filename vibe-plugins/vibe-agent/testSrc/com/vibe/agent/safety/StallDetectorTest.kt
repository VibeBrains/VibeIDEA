// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StallDetectorTest {
  private fun turn(changed: Int = 0, done: Int = 0, total: Int = 0) = StallDetector.Turn(changed, done, total)

  @Test
  fun `изменённый файл — это движение`() {
    assertTrue(StallDetector.moved(turn(changed = 2), previous = turn()))
    assertFalse(StallDetector.isStalled(listOf(turn(changed = 1), turn(changed = 1), turn(changed = 1))))
  }

  @Test
  fun `сдвиг плана — тоже движение, даже без правок файлов`() {
    val history = listOf(turn(done = 1, total = 5), turn(done = 2, total = 5), turn(done = 3, total = 5))
    assertFalse(StallDetector.isStalled(history))
  }

  @Test
  fun `план, стоящий на месте, движением не считается`() {
    // Счётчик не нулевой, но он один и тот же третий ход подряд — это стояние, а не работа.
    val history = listOf(turn(done = 3, total = 7), turn(done = 3, total = 7), turn(done = 3, total = 7), turn(done = 3, total = 7))
    assertEquals(3, StallDetector.stalledTurns(history))
    assertTrue(StallDetector.isStalled(history))
  }

  @Test
  fun `переписанный план — работа, а не застой`() {
    val history = listOf(turn(done = 2, total = 5), turn(done = 2, total = 5), turn(done = 2, total = 9))
    assertEquals(0, StallDetector.stalledTurns(history), "перепланирование — это тоже движение")
    assertFalse(StallDetector.isStalled(history))
  }

  @Test
  fun `два хода без движения — ещё не застой`() {
    val history = listOf(turn(changed = 1), turn(), turn())
    assertEquals(2, StallDetector.stalledTurns(history))
    assertFalse(StallDetector.isStalled(history), "два хода — обычная пауза на подумать")
  }

  @Test
  fun `движение обнуляет счёт`() {
    val history = listOf(turn(), turn(), turn(changed = 1))
    assertEquals(0, StallDetector.stalledTurns(history))
  }

  @Test
  fun `нулевой порог выключает детектор`() {
    val history = List(10) { turn() }
    assertFalse(StallDetector.isStalled(history, threshold = 0))
    assertFalse(StallDetector.isStalled(history, threshold = -1))
  }

  @Test
  fun `история подрезается, но ответ не меняется`() {
    val history = List(50) { turn() }
    val trimmed = StallDetector.trim(history)
    assertTrue(trimmed.size <= StallDetector.DEFAULT_STALL_TURNS + 1)
    assertTrue(StallDetector.isStalled(trimmed))
  }

  @Test
  fun `первый ход без единого признака жизни уже стоит на месте`() {
    assertFalse(StallDetector.moved(turn(), previous = null))
    assertTrue(StallDetector.moved(turn(total = 4), previous = null), "появившийся план — движение")
  }
}
