// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import com.vibe.agent.ui.FeedSelectionModel.Anchor
import com.vibe.agent.ui.FeedSelectionModel.Range
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Выделение, идущее через границы сообщений.
 *
 * Арифметику держим под тестом, потому что проверить её иначе можно только мышью: в Swing выделение
 * живёт внутри одного компонента, и ошибка в диапазоне выглядит не исключением, а «скопировалось не
 * то» — то есть замечается уже после вставки.
 */
class FeedSelectionModelTest {
  private val lengths = listOf(10, 20, 30)

  @Test
  fun `a selection inside one piece is that piece alone`() {
    assertEquals(listOf(Range(1, 3, 8)), FeedSelectionModel.ranges(Anchor(1, 3), Anchor(1, 8), lengths))
  }

  @Test
  fun `a selection across pieces takes the tail of the first and the head of the last`() {
    assertEquals(
      listOf(Range(0, 4, 10), Range(1, 0, 20), Range(2, 0, 7)),
      FeedSelectionModel.ranges(Anchor(0, 4), Anchor(2, 7), lengths))
  }

  @Test
  fun `dragging upwards selects the same as dragging downwards`() {
    val down = FeedSelectionModel.ranges(Anchor(0, 4), Anchor(2, 7), lengths)
    val up = FeedSelectionModel.ranges(Anchor(2, 7), Anchor(0, 4), lengths)
    assertEquals(down, up)
  }

  @Test
  fun `a point past the end of a piece is clamped to it`() {
    // Точка в промежутке между пузырями приходит как «конец куска выше», и она не должна
    // выделять дальше, чем в этом куске есть текст.
    assertEquals(listOf(Range(0, 0, 10), Range(1, 0, 20)), FeedSelectionModel.ranges(Anchor(0, -5), Anchor(1, 999), lengths))
  }

  @Test
  fun `select all covers every piece whole`() {
    assertEquals(listOf(Range(0, 0, 10), Range(1, 0, 20), Range(2, 0, 30)), FeedSelectionModel.all(lengths))
    assertEquals(Anchor(0, 0) to Anchor(2, 30), FeedSelectionModel.wholeFeed(lengths))
  }

  @Test
  fun `an empty feed has nothing to select and does not throw`() {
    assertEquals(emptyList(), FeedSelectionModel.ranges(Anchor(0, 0), Anchor(3, 5), emptyList()))
    assertEquals(emptyList(), FeedSelectionModel.all(emptyList()))
    assertEquals(null, FeedSelectionModel.wholeFeed(emptyList()))
  }
}
