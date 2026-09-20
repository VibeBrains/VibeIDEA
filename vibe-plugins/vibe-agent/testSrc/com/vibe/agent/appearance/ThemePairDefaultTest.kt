// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.appearance

import kotlin.test.Test
import kotlin.test.assertEquals

/** Пара засевается один раз и только на чистой: иначе мы спорили бы с выбором человека. */
class ThemePairDefaultTest {
  @Test
  fun `на чистой паре засеваем свои темы`() {
    assertEquals(
      ThemePairDefault.Action.SEED,
      ThemePairDefault.decide(alreadySeeded = false, lightSet = false, darkSet = false),
    )
  }

  @Test
  fun `второй раз не приходим`() {
    assertEquals(
      ThemePairDefault.Action.LEAVE_ALONE,
      ThemePairDefault.decide(alreadySeeded = true, lightSet = false, darkSet = false),
    )
  }

  @Test
  fun `заданной половины достаточно, чтобы не вмешиваться`() {
    assertEquals(
      ThemePairDefault.Action.LEAVE_ALONE,
      ThemePairDefault.decide(alreadySeeded = false, lightSet = true, darkSet = false),
    )
    assertEquals(
      ThemePairDefault.Action.LEAVE_ALONE,
      ThemePairDefault.decide(alreadySeeded = false, lightSet = false, darkSet = true),
    )
  }
}
