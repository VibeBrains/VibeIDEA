// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlin.test.Test
import kotlin.test.assertEquals

/** Время жизни кэша: то, из-за чего лимиты «кончаются сами». */
class CacheWindowTest {
  private val start = 1_000_000L

  @Test
  fun `таймер идёт от НАЧАЛА хода, а не от ответа`() {
    // Ход, который думал четыре минуты, оставляет от пятиминутного кэша одну — ровно этим и
    // объясняется «отошёл на минуту, а контекст перечитался».
    val fourMinutes = start + 4 * 60_000
    assertEquals(60_000, CacheWindow.leftMs(start, "5m", fourMinutes))
    assertEquals(CacheWindow.State.EXPIRING, CacheWindow.state(start, "5m", fourMinutes))
  }

  @Test
  fun `после срока кэш холодный`() {
    assertEquals(CacheWindow.State.COLD, CacheWindow.state(start, "5m", start + 5 * 60_000 + 1))
    assertEquals(0, CacheWindow.leftMs(start, "5m", start + 10 * 60_000))
  }

  @Test
  fun `часовой кэш переживает паузу, которая убивает пятиминутный`() {
    val tenMinutes = start + 10 * 60_000
    assertEquals(CacheWindow.State.COLD, CacheWindow.state(start, "5m", tenMinutes))
    assertEquals(CacheWindow.State.WARM, CacheWindow.state(start, "1h", tenMinutes))
  }

  @Test
  fun `до первого хода кэша нет, и это не «холодный»`() {
    // «Ещё не писали» и «протух» — разные ответы: во втором случае человек потерял деньги.
    assertEquals(CacheWindow.State.NONE, CacheWindow.state(0, "1h", start))
  }

  @Test
  fun `окупаемость считается по НАЗВАННЫМ ценам`() {
    // Fable 5.1: вход 10, запись часового 20, чтение 0.25. Надбавка 10, экономия 9.75 за
    // попадание — значит одно попадание почти окупает, и это третий запрос вместе с записью.
    val fable = ModelPricing(input = 10.0, output = 50.0, cacheRead = 0.25, cacheWrite = 20.0)
    assertEquals(3, CacheWindow.paysOffFromRequest(fable, "1h"))
  }

  @Test
  fun `без цен берутся ставки вендора, а не выдумка`() {
    assertEquals(2, CacheWindow.paysOffFromRequest(null, "5m"))
    assertEquals(3, CacheWindow.paysOffFromRequest(null, "1h"))
  }

  @Test
  fun `дешёвая запись окупается сразу`() {
    // Если вендор берёт за запись столько же, сколько за вход, платить вперёд не за что.
    val flat = ModelPricing(input = 10.0, output = 50.0, cacheRead = 1.0, cacheWrite = 10.0)
    assertEquals(2, CacheWindow.paysOffFromRequest(flat, "5m"))
  }
}
