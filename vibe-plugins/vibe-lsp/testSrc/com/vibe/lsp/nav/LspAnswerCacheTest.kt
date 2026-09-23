// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rules of the per-position answer cache — the ones the navigation, the hint and the trace stand on.
 *
 * Each breaks the feature in its own way, and none of them shows at a glance: it surfaces as «sometimes nothing is
 * underlined» or «jumps to the wrong place after an edit».
 */
class LspAnswerCacheTest {
  private val key = LspAnswerCache.Key("file:///a.ts", 42)

  private fun <V : Any> LspAnswerCache<V>.answer(key: LspAnswerCache.Key, stamp: Long, value: V?) {
    request(key, stamp) { CompletableFuture.completedFuture(value) }
  }

  @Test
  fun `неспрошенная позиция — это «не знаю», а не «нет»`() {
    // Treating a miss as a refusal would mean never asking, so nothing would ever be underlined.
    assertNull(LspAnswerCache<String>().known(key, stamp = 1))
  }

  @Test
  fun `ответ «здесь ничего нет» — тоже знание`() {
    val cache = LspAnswerCache<String>()
    cache.answer(key, stamp = 1, value = null)
    assertEquals(LspAnswerCache.Known<String>(null), cache.known(key, stamp = 1))
  }

  @Test
  fun `ответ переживает повторный вопрос, но не правку документа`() {
    val cache = LspAnswerCache<String>()
    cache.answer(key, stamp = 1, value = "fetchUser")
    assertEquals("fetchUser", cache.known(key, stamp = 1)?.value)
    // Another version stamp: offsets have moved, and the old answer is about another place now.
    assertNull(cache.known(key, stamp = 2))
  }

  @Test
  fun `ответ протухает по времени`() {
    var clock = 0L
    val cache = LspAnswerCache<String>(ttlMs = 100, now = { clock })
    cache.answer(key, stamp = 1, value = "fetchUser")
    clock = 100
    assertEquals("fetchUser", cache.known(key, stamp = 1)?.value)
    clock = 101
    assertNull(cache.known(key, stamp = 1))
  }

  @Test
  fun `один и тот же вопрос не уходит на сервер дважды`() {
    val cache = LspAnswerCache<String>()
    val server = CompletableFuture<String?>()
    var sent = 0
    val first = cache.request(key, stamp = 1) { sent++; server }
    val second = cache.request(key, stamp = 1) { sent++; CompletableFuture.completedFuture("other") }
    assertSame(first, second, "the second asker joins the request in flight")
    assertEquals(1, sent)
    server.complete("fetchUser")
    assertEquals("fetchUser", cache.known(key, stamp = 1)?.value)
  }

  @Test
  fun `после правки вопрос уходит заново`() {
    val cache = LspAnswerCache<String>()
    var sent = 0
    cache.request(key, stamp = 1) { sent++; CompletableFuture<String?>() }
    cache.request(key, stamp = 2) { sent++; CompletableFuture<String?>() }
    assertEquals(2, sent, "a request about the old version answers about another place")
  }

  @Test
  fun `неудавшийся запрос не запирает позицию`() {
    val cache = LspAnswerCache<String>()
    cache.request(key, stamp = 1) { CompletableFuture.failedFuture(IllegalStateException("server is starting")) }
    assertNull(cache.known(key, stamp = 1), "a failure is not an answer")
    var sent = 0
    cache.request(key, stamp = 1) { sent++; CompletableFuture.completedFuture("fetchUser") }
    assertEquals(1, sent, "otherwise a server that was starting would leave the position unanswered for the session")
    assertEquals("fetchUser", cache.known(key, stamp = 1)?.value)
  }

  @Test
  fun `упавшая отправка не оставляет вопрос висеть`() {
    val cache = LspAnswerCache<String>()
    val failed = cache.request(key, stamp = 1) { throw IllegalStateException("no servers") }
    assertTrue(failed.isCompletedExceptionally)
    var sent = 0
    cache.request(key, stamp = 1) { sent++; CompletableFuture.completedFuture("fetchUser") }
    assertEquals(1, sent)
  }

  @Test
  fun `опоздавший ответ о старой версии не затирает новый`() {
    val cache = LspAnswerCache<String>()
    val old = CompletableFuture<String?>()
    cache.request(key, stamp = 1) { old }
    cache.answer(key, stamp = 2, value = "renamed")
    old.complete("fetchUser")
    assertEquals("renamed", cache.known(key, stamp = 2)?.value)
  }

  @Test
  fun `кэш не растёт бесконечно и выбрасывает самое старое`() {
    val cache = LspAnswerCache<String>(capacity = 2)
    repeat(3) { i -> cache.answer(LspAnswerCache.Key("file:///a.ts", i), stamp = 1, value = "n$i") }
    assertEquals(2, cache.size())
    assertNull(cache.known(LspAnswerCache.Key("file:///a.ts", 0), stamp = 1))
  }
}
