// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRequestFileTest {
  @Test
  fun `три запроса в файле разделяются решётками и получают имена`() {
    val parsed = HttpRequestFile.parse(
      """
      ### Список пользователей
      GET https://api.example.com/users
      Accept: application/json

      ### Создать
      POST https://api.example.com/users
      Content-Type: application/json

      {"name": "Иван"}

      ### Удалить
      DELETE https://api.example.com/users/1
      """.trimIndent()
    )
    assertEquals(listOf("Список пользователей", "Создать", "Удалить"), parsed.requests.map { it.name })
    assertEquals(listOf("GET", "POST", "DELETE"), parsed.requests.map { it.method })
    assertEquals(HttpRequestFile.Body.Inline("""{"name": "Иван"}"""), parsed.requests[1].body)
    assertTrue(parsed.problems.isEmpty(), "чистый файл не должен порождать замечаний")
  }

  @Test
  fun `метод можно не писать — это GET`() {
    // Спека это разрешает, и так записывают чаще всего: адрес и есть весь запрос.
    val parsed = HttpRequestFile.parse("https://example.com/health")
    assertEquals("GET", parsed.requests.single().method)
    assertEquals("https://example.com/health", parsed.requests.single().target)
  }

  @Test
  fun `тело берётся из файла, ответ сохраняется, обработчик распознан но не выполняется`() {
    val parsed = HttpRequestFile.parse(
      """
      POST https://example.com/upload
      Content-Type: application/json

      < payload.json
      >> out/response.json
      > handler.js
      """.trimIndent()
    )
    val request = parsed.requests.single()
    assertEquals(HttpRequestFile.Body.FromFile("payload.json"), request.body)
    assertEquals("out/response.json", request.saveResponseTo)
    // Обработчик мы НЕ выполняем, но обязаны его увидеть: иначе человек будет искать ошибку в скрипте.
    assertEquals("handler.js", request.responseHandler)
  }

  @Test
  fun `пометки управляют запросом, а незнакомая пометка не ошибка`() {
    val parsed = HttpRequestFile.parse(
      """
      ### C пометками
      # @name Проверка
      # @no-redirect
      # @timeout 12
      # @tag smoke
      GET https://example.com/r
      """.trimIndent()
    )
    val request = parsed.requests.single()
    assertEquals("Проверка", request.name, "@name сильнее имени из разделителя")
    assertTrue(request.noRedirect)
    assertEquals(12, request.timeoutSeconds)
    assertTrue(parsed.problems.isEmpty(), "спека растёт — незнакомая пометка не должна ломать файл")
  }

  @Test
  fun `переменные файла собираются отдельно`() {
    val parsed = HttpRequestFile.parse(
      """
      @host = https://api.example.com
      @version = v2

      GET {{host}}/{{version}}/users
      """.trimIndent()
    )
    assertEquals(mapOf("host" to "https://api.example.com", "version" to "v2"), parsed.variables)
    assertEquals("{{host}}/{{version}}/users", parsed.requests.single().target)
  }

  @Test
  fun `непонятая строка называется номером, а разбор продолжается`() {
    // Файл правят руками: половина правки не должна прятать остальные запросы.
    val parsed = HttpRequestFile.parse(
      """
      GET https://example.com/a
      это не заголовок

      ### Второй
      GET https://example.com/b
      """.trimIndent()
    )
    assertEquals(2, parsed.requests.size)
    assertEquals(listOf(HttpRequestFile.Trouble.NOT_A_HEADER), parsed.problems.map { it.trouble })
    assertEquals(1, parsed.problems.single().line)
  }

  @Test
  fun `запрос находится по строке курсора`() {
    val parsed = HttpRequestFile.parse(
      """
      ### Первый
      GET https://example.com/a

      ### Второй
      GET https://example.com/b
      """.trimIndent()
    )
    assertEquals("Первый", HttpRequestFile.requestAt(parsed, 1)?.name)
    assertEquals("Второй", HttpRequestFile.requestAt(parsed, 4)?.name)
    assertNull(HttpRequestFile.requestAt(parsed, 99))
  }

  @Test
  fun `SQL и проза не притворяются запросами`() {
    // Строка «SELECT * FROM users» состоит из заглавных букв и пробела — без списка методов она
    // разобралась бы как запрос методом SELECT.
    val parsed = HttpRequestFile.parse("SELECT * FROM users")
    assertTrue(parsed.requests.isEmpty())
    assertEquals(listOf(HttpRequestFile.Trouble.NOT_A_REQUEST), parsed.problems.map { it.trouble })
  }

  @Test
  fun `перевод строки Windows не ломает разбор`() {
    val parsed = HttpRequestFile.parse("### Имя\r\nGET https://example.com/a\r\nAccept: */*\r\n")
    assertEquals("Имя", parsed.requests.single().name)
    assertEquals(listOf(HttpRequestFile.Header("Accept", "*/*")), parsed.requests.single().headers)
  }
}
