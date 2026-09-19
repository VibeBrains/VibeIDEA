// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Правила кэша навигации — те самые, на которых держится «подчёркиваем только резолвимое».
 *
 * Каждое ломает возможность по-своему, и ни одно не видно глазами сразу: ошибка проявится как
 * «иногда не подчёркивает» или «ведёт не туда после правки».
 */
class LspDefinitionCacheTest {
  private val key = LspDefinitionCache.Key("file:///a.ts", 42)

  @Test
  fun `неспрошенная позиция — это «не знаю», а не «нет»`() {
    // Считать промах отказом значит не подчеркнуть НИКОГДА: запроса не будет, потому что ответ
    // уже «известен».
    assertEquals(LspDefinitionCache.Answer.UNKNOWN, LspDefinitionCache().answer(key, stamp = 1))
  }

  @Test
  fun `ответ переживает повторный вопрос, но не правку документа`() {
    val cache = LspDefinitionCache()
    cache.put(key, stamp = 1, answer = LspDefinitionCache.Answer.RESOLVED)
    assertEquals(LspDefinitionCache.Answer.RESOLVED, cache.answer(key, stamp = 1))
    // Другая отметка версии — смещения уехали, и старый ответ теперь про другое место.
    assertEquals(LspDefinitionCache.Answer.UNKNOWN, cache.answer(key, stamp = 2))
  }

  @Test
  fun `ответ протухает по времени`() {
    var clock = 0L
    val cache = LspDefinitionCache(ttlMs = 100, now = { clock })
    cache.put(key, stamp = 1, answer = LspDefinitionCache.Answer.NONE)
    clock = 99
    assertEquals(LspDefinitionCache.Answer.NONE, cache.answer(key, stamp = 1))
    clock = 101
    assertEquals(LspDefinitionCache.Answer.UNKNOWN, cache.answer(key, stamp = 1))
  }

  @Test
  fun `один и тот же вопрос не уходит на сервер дважды`() {
    val cache = LspDefinitionCache()
    assertTrue(cache.claim(key), "первый запрос нужен")
    assertFalse(cache.claim(key), "второй — уже в полёте, спрашивать незачем")
    cache.put(key, stamp = 1, answer = LspDefinitionCache.Answer.RESOLVED)
    assertTrue(cache.claim(key), "после ответа позицию можно спросить снова")
  }

  @Test
  fun `неудавшийся запрос не запирает позицию навсегда`() {
    val cache = LspDefinitionCache()
    cache.claim(key)
    cache.release(key)
    assertTrue(cache.claim(key), "иначе упавший сервер оставил бы позицию без ответа до конца сеанса")
  }

  @Test
  fun `кэш не растёт бесконечно и выбрасывает самое старое`() {
    val cache = LspDefinitionCache(capacity = 2)
    repeat(3) { i ->
      cache.put(LspDefinitionCache.Key("file:///a.ts", i), stamp = 1, answer = LspDefinitionCache.Answer.RESOLVED)
    }
    assertEquals(2, cache.size())
    assertEquals(LspDefinitionCache.Answer.UNKNOWN,
                 cache.answer(LspDefinitionCache.Key("file:///a.ts", 0), stamp = 1))
  }

  @Test
  fun `закрытый файл забывается целиком`() {
    val cache = LspDefinitionCache()
    cache.put(key, stamp = 1, answer = LspDefinitionCache.Answer.RESOLVED)
    cache.put(LspDefinitionCache.Key("file:///b.ts", 1), stamp = 1, answer = LspDefinitionCache.Answer.RESOLVED)
    cache.forget("file:///a.ts")
    assertEquals(LspDefinitionCache.Answer.UNKNOWN, cache.answer(key, stamp = 1))
    assertEquals(1, cache.size())
  }
}
