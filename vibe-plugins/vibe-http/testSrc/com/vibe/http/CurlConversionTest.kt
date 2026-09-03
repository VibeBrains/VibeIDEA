// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurlConversionTest {
  @Test
  fun `команда из чужого тикета превращается в запрос`() {
    val request = CurlConversion.fromCurl(
      """curl -X POST 'https://api.example.com/users' -H 'Content-Type: application/json' -H "X-Trace: 42" --data-raw '{"name":"Иван"}'"""
    )!!
    assertEquals("POST", request.method)
    assertEquals("https://api.example.com/users", request.target)
    assertEquals(listOf("Content-Type" to "application/json", "X-Trace" to "42"),
                 request.headers.map { it.name to it.value })
    assertEquals("""{"name":"Иван"}""", (request.body as HttpRequestFile.Body.Inline).text)
  }

  @Test
  fun `тело без метода означает POST — так же считает сам curl`() {
    val request = CurlConversion.fromCurl("curl https://example.com/a -d hello")!!
    assertEquals("POST", request.method)
    assertEquals("GET", CurlConversion.fromCurl("curl https://example.com/a")!!.method)
  }

  @Test
  fun `перенос строки обратным слэшем и незнакомые флаги не мешают`() {
    // Команду копируют из документации целиком, вместе с -s и -o.
    val request = CurlConversion.fromCurl("curl -s \\\n  -H 'Accept: application/json' \\\n  https://example.com/b")!!
    assertEquals("https://example.com/b", request.target)
    assertEquals("Accept", request.headers.single().name)
  }

  @Test
  fun `логин с паролем превращается в заголовок, который видно при чтении файла`() {
    val request = CurlConversion.fromCurl("curl -u user:secret https://example.com/c")!!
    assertEquals("Authorization", request.headers.single().name)
    assertEquals("Basic dXNlcjpzZWNyZXQ=", request.headers.single().value)
  }

  @Test
  fun `не curl — не запрос`() {
    assertNull(CurlConversion.fromCurl("wget https://example.com"))
    assertNull(CurlConversion.fromCurl(""))
    assertNull(CurlConversion.fromCurl("curl -X POST"), "команда без адреса запросом не является")
  }

  @Test
  fun `запрос превращается в команду, которую можно вставить в терминал`() {
    val request = HttpRequestFile.parse(
      """
      POST https://api.example.com/users
      Content-Type: application/json

      {"name": "Иван"}
      """.trimIndent()
    ).requests.single()
    val curl = CurlConversion.toCurl(request)
    assertTrue(curl.startsWith("curl -X POST https://api.example.com/users"), curl)
    assertTrue(curl.contains("-H 'Content-Type: application/json'"), curl)
    assertTrue(curl.contains("--data-raw '{\"name\": \"Иван\"}'"), curl)
  }

  @Test
  fun `кавычка внутри значения не разрывает команду`() {
    val request = HttpRequestFile.Request(
      name = null, method = "POST", target = "https://e.com", httpVersion = null,
      headers = emptyList(), body = HttpRequestFile.Body.Inline("""{"q":"it's"}"""),
      startLine = 0, endLine = 0,
    )
    val curl = CurlConversion.toCurl(request)
    assertTrue(curl.contains("""'{"q":"it'\''s"}'"""), curl)
    // И обратно: то, что мы напечатали, мы обязаны уметь прочитать.
    assertEquals("""{"q":"it's"}""", (CurlConversion.fromCurl(curl)!!.body as HttpRequestFile.Body.Inline).text)
  }

  @Test
  fun `импортированный запрос записывается в файл целиком`() {
    val request = CurlConversion.fromCurl("curl -H 'A: b' https://example.com/d")!!
    val text = CurlConversion.toHttpFile(request, name = "Из тикета")
    val parsed = HttpRequestFile.parse(text).requests.single()
    assertEquals("Из тикета", parsed.name)
    assertEquals("https://example.com/d", parsed.target)
    assertEquals(listOf(HttpRequestFile.Header("A", "b")), parsed.headers)
  }
}
