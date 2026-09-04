// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpHistoryTest {
  private fun entry(title: String = "Список", status: Int = 200, body: String = "{}", at: Long = 1,
                    target: String = "https://e.com/a") =
    HttpHistory.Entry(title, "GET", target, status, 12, body.length.toLong(), body, at)

  private val labels = object : HttpHistory.Labels {
    override fun statusChanged(before: Int, now: Int) = "статус: было $before, стало $now"
    override fun bodyChanged(comparedWith: Int) = "тело отличается от прошлого (записей: $comparedWith)"
  }

  @Test
  fun `новые ответы идут первыми, старые вытесняются`() {
    var history = emptyList<HttpHistory.Entry>()
    repeat(12) { i -> history = HttpHistory.add(history, entry(status = 200 + i, at = i.toLong())) }
    val mine = HttpHistory.of(history, "Список", "GET", "https://e.com/a")
    assertEquals(HttpHistory.PER_REQUEST, mine.size, "держим ровно предел на запрос")
    assertEquals(211, mine.first().status, "последний ответ — первый в списке")
  }

  @Test
  fun `истории разных запросов не смешиваются`() {
    // Два запроса с именем «Список» в разных файлах — разные запросы; общая история показывала бы
    // человеку чужие ответы.
    var history = emptyList<HttpHistory.Entry>()
    history = HttpHistory.add(history, entry(target = "https://a.example/x"))
    history = HttpHistory.add(history, entry(target = "https://b.example/x"))
    assertEquals(1, HttpHistory.of(history, "Список", "GET", "https://a.example/x").size)
    assertEquals(1, HttpHistory.of(history, "Список", "GET", "https://b.example/x").size)
    assertEquals(2, history.size)
  }

  @Test
  fun `изменение статуса важнее изменения тела`() {
    val entries = listOf(entry(status = 500, body = "err", at = 2), entry(status = 200, body = "{}", at = 1))
    assertEquals("статус: было 200, стало 500", HttpHistory.changeAgainstPrevious(entries, labels))
  }

  @Test
  fun `изменение тела при том же статусе называется отдельно`() {
    val entries = listOf(entry(body = "{\"a\":2}", at = 2), entry(body = "{\"a\":1}", at = 1))
    assertTrue(HttpHistory.changeAgainstPrevious(entries, labels)!!.startsWith("тело отличается"))
  }

  @Test
  fun `одинаковый ответ и единственный ответ ничего не сообщают`() {
    // Сообщение «ничего не изменилось» на каждом прогоне приучает не читать эту строку вовсе.
    assertNull(HttpHistory.changeAgainstPrevious(listOf(entry(at = 2), entry(at = 1)), labels))
    assertNull(HttpHistory.changeAgainstPrevious(listOf(entry()), labels))
    assertNull(HttpHistory.changeAgainstPrevious(emptyList(), labels))
  }
}
