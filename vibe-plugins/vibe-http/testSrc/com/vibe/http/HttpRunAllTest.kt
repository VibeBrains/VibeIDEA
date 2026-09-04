// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRunAllTest {
  @Test
  fun `успех — только 2xx, остальное попадает в список упавших`() {
    // Зелёная сводка при пяти пятисотках хуже, чем её отсутствие.
    val summary = HttpRunAll.summarize(listOf(
      HttpRunAll.Outcome.Answered("Здоровье", 200, 10),
      HttpRunAll.Outcome.Answered("Список", 404, 20),
      HttpRunAll.Outcome.Answered("Создание", 500, 30),
      HttpRunAll.Outcome.Answered("Редирект", 302, 5),
    ))
    assertEquals(4, summary.total)
    assertEquals(1, summary.ok)
    assertEquals(listOf("Список — 404", "Создание — 500", "Редирект — 302"), summary.failed)
    assertEquals(65, summary.totalMs)
    assertFalse(summary.allOk)
  }

  @Test
  fun `не ушедший запрос отличается от упавшего, и оба видны`() {
    val summary = HttpRunAll.summarize(listOf(
      HttpRunAll.Outcome.Refused("Список", "{{host}}"),
      HttpRunAll.Outcome.Failed("Создание", "connect timed out"),
    ))
    assertEquals(0, summary.ok)
    assertEquals(listOf("Список — {{host}}", "Создание — connect timed out"), summary.failed)
  }

  @Test
  fun `пустой файл не считается успешным прогоном`() {
    // «Всё хорошо» на нуле запросов — самый обидный вид зелёного.
    assertFalse(HttpRunAll.summarize(emptyList()).allOk)
    assertTrue(HttpRunAll.summarize(listOf(HttpRunAll.Outcome.Answered("A", 204, 1))).allOk)
  }

  @Test
  fun `изменяющие запросы называются до прогона`() {
    // POST пять раз — это пять пользователей; предупреждаем, но не запрещаем: файл писал человек.
    val requests = HttpRequestFile.parse(
      """
      ### Читаем
      GET https://e.com/a

      ### Создаём
      POST https://e.com/a

      ### Удаляем
      DELETE https://e.com/a/1
      """.trimIndent()
    ).requests
    assertEquals(listOf("POST", "DELETE"), HttpRunAll.changingRequests(requests).map { it.method })
  }
}
