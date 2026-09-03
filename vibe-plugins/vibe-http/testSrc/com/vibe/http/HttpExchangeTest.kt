// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpExchangeTest {
  private fun response(status: Int = 200, headers: List<Pair<String, String>> = emptyList(), body: String = "") =
    HttpExchange.Response(status, headers.map { HttpRequestFile.Header(it.first, it.second) }, body, 12, body.length.toLong())

  @Test
  fun `класс статуса решается числом, а не текстом`() {
    assertEquals(HttpExchange.Outcome.SUCCESS, HttpExchange.outcome(204))
    assertEquals(HttpExchange.Outcome.REDIRECT, HttpExchange.outcome(301))
    assertEquals(HttpExchange.Outcome.CLIENT_ERROR, HttpExchange.outcome(404))
    assertEquals(HttpExchange.Outcome.SERVER_ERROR, HttpExchange.outcome(503))
    assertEquals(HttpExchange.Outcome.UNKNOWN, HttpExchange.outcome(0))
  }

  @Test
  fun `json опознаётся по типу и по первому символу`() {
    assertTrue(HttpExchange.looksLikeJson(response(headers = listOf("Content-Type" to "application/json; charset=utf-8"))))
    assertTrue(HttpExchange.looksLikeJson(response(headers = listOf("content-type" to "application/problem+json"))))
    // Сервер без Content-Type — обычное дело, и тело всё равно надо показать красиво.
    assertTrue(HttpExchange.looksLikeJson(response(body = "  [1,2]")))
    assertFalse(HttpExchange.looksLikeJson(response(headers = listOf("Content-Type" to "text/html"), body = "<html>")))
  }

  @Test
  fun `отступы расставляются, а строки не трогаются`() {
    // Разбор в объект и обратно потерял бы порядок ключей и точность чисел — то, на что смотрят.
    val pretty = HttpExchange.prettyJson("""{"b":1,"a":{"x":[1,2]},"s":"a, b: {c}"}""")
    assertEquals(
      """
      {
        "b": 1,
        "a": {
          "x": [
            1,
            2
          ]
        },
        "s": "a, b: {c}"
      }
      """.trimIndent(),
      pretty,
    )
  }

  @Test
  fun `экранированная кавычка внутри строки не сбивает форматтер`() {
    val pretty = HttpExchange.prettyJson("""{"q":"он сказал \"да\", и всё"}""")
    assertTrue(pretty.contains("""\"да\""""), pretty)
    assertEquals(1, pretty.lines().count { it.contains("\"q\"") })
  }

  @Test
  fun `размер и время отдаются числом и кодом единицы, а не фразой`() {
    // Слово «КБ» — интерфейс: иначе перевод единиц требовал бы правки кода.
    assertEquals(HttpExchange.Size(512.0, HttpExchange.SizeUnit.BYTES), HttpExchange.size(512))
    assertEquals(HttpExchange.Size(1.5, HttpExchange.SizeUnit.KIB), HttpExchange.size(1536))
    assertEquals(HttpExchange.Size(2.0, HttpExchange.SizeUnit.MIB), HttpExchange.size(2 * 1024 * 1024))
    assertEquals(HttpExchange.Duration(999.0, inSeconds = false), HttpExchange.duration(999))
    assertEquals(HttpExchange.Duration(1.5, inSeconds = true), HttpExchange.duration(1500))
  }
}
