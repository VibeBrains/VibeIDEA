// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.appearance

import kotlin.test.Test
import kotlin.test.assertEquals

/** Умолчание применяется один раз: иначе выключенная человеком настройка возвращалась бы сама. */
class CompactModeDefaultTest {
  @Test
  fun `на чистом профиле плотный режим включается`() {
    assertEquals(CompactModeDefault.Action.ENABLE, CompactModeDefault.decide(alreadyApplied = false, compactAlready = false))
  }

  @Test
  fun `выключенное человеком не включается заново`() {
    assertEquals(CompactModeDefault.Action.LEAVE_ALONE, CompactModeDefault.decide(alreadyApplied = true, compactAlready = false))
  }

  @Test
  fun `уже плотный интерфейс не трогаем и умолчание не навязываем`() {
    assertEquals(CompactModeDefault.Action.LEAVE_ALONE, CompactModeDefault.decide(alreadyApplied = false, compactAlready = true))
    assertEquals(CompactModeDefault.Action.LEAVE_ALONE, CompactModeDefault.decide(alreadyApplied = true, compactAlready = true))
  }
}
