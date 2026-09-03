// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpCallTest {
  private fun request(text: String) = HttpRequestFile.parse(text).requests.single()

  @Test
  fun `заголовки и метод доезжают до настоящего запроса`() {
    val prepared = HttpCall.prepare(
      request("POST https://api.example.com/users\nX-Trace: 42\n\n{\"a\":1}"), baseDir = null)
    val ready = prepared as HttpCall.Prepared.Ready
    assertEquals("POST", ready.request.method())
    assertEquals("https://api.example.com/users", ready.request.uri().toString())
    assertEquals("42", ready.request.headers().firstValue("X-Trace").orElse(null))
    assertEquals(7, ready.bodyBytes)
  }

  @Test
  fun `запрещённый заголовок пропускается, а не роняет запрос`() {
    // «Host» и «Content-Length» человек копирует из вкладки Network вместе с остальными;
    // java.net.http на них бросает исключение, и запрос не ушёл бы вовсе.
    val prepared = HttpCall.prepare(request("GET https://example.com/a\nHost: example.com\nContent-Length: 10\nAccept: */*"), null)
    val ready = prepared as HttpCall.Prepared.Ready
    assertEquals("*/*", ready.request.headers().firstValue("Accept").orElse(null))
  }

  @Test
  fun `адрес без схемы и неподставленная переменная — отказ с причиной`() {
    val noScheme = HttpCall.prepare(request("GET example.com/a"), null)
    // Строка «example.com/a» не начинается с http, поэтому и запросом-то не считается.
    assertTrue(noScheme is HttpCall.Prepared.Refused || HttpRequestFile.parse("GET example.com/a").requests.isEmpty())

    // Неподставленная переменная — своя причина: человеку нужно услышать про забытое окружение,
    // а не про синтаксис URI.
    val unresolved = HttpCall.prepare(
      HttpRequestFile.Request(null, "GET", "{{host}}/users", null, emptyList(), null, 0, 0), null)
    val refused = unresolved as HttpCall.Prepared.Refused
    assertEquals(HttpCall.Reason.UNRESOLVED_VARIABLE, refused.reason)
    assertEquals("{{host}}", refused.detail, "в отказе названа сама переменная")

    val noHost = HttpCall.prepare(
      HttpRequestFile.Request(null, "GET", "/users", null, emptyList(), null, 0, 0), null)
    assertEquals(HttpCall.Reason.NO_SCHEME, (noHost as HttpCall.Prepared.Refused).reason)
  }

  @Test
  fun `тело из файла читается относительно папки запроса`() {
    val dir = Files.createTempDirectory("vibe-http")
    Files.writeString(dir.resolve("payload.json"), """{"из":"файла"}""")
    val ready = HttpCall.prepare(request("POST https://e.com/u\n\n< payload.json"), dir) as HttpCall.Prepared.Ready
    assertEquals("""{"из":"файла"}""".toByteArray(Charsets.UTF_8).size, ready.bodyBytes)

    val missing = HttpCall.prepare(request("POST https://e.com/u\n\n< нет-такого.json"), dir)
    assertEquals(HttpCall.Reason.BODY_FILE_MISSING, (missing as HttpCall.Prepared.Refused).reason)
  }

  @Test
  fun `таймаут из пометки сильнее общего`() {
    val ready = HttpCall.prepare(request("# @timeout 5\nGET https://e.com/a"), null) as HttpCall.Prepared.Ready
    assertEquals(5, ready.request.timeout().get().seconds)
    val default = HttpCall.prepare(request("GET https://e.com/a"), null) as HttpCall.Prepared.Ready
    assertEquals(HttpCall.DEFAULT_TIMEOUT_SECONDS.toLong(), default.request.timeout().get().seconds)
  }
}
