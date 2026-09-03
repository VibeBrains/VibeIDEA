// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpTokensTest {
  private fun kinds(text: String, kind: HttpTokens.Kind) =
    HttpTokens.scan(text).filter { it.kind == kind }.map { text.substring(it.start, it.end) }

  @Test
  fun `куски покрывают текст целиком, без дыр и нахлёстов`() {
    // Платформа именно этого и ждёт от лексера: пропущенный байт — невидимая дыра в подсветке.
    val text = "### Имя\n# @timeout 5\nGET {{host}}/users\nAccept: */*\n\n{\"a\": 1}\n"
    val tokens = HttpTokens.scan(text)
    var cursor = 0
    for (token in tokens) {
      assertEquals(cursor, token.start, "разрыв или нахлёст перед куском $token")
      assertTrue(token.end > token.start, "пустой кусок $token")
      cursor = token.end
    }
    assertEquals(text.length, cursor, "текст покрыт не до конца")
  }

  @Test
  fun `метод, адрес и переменные внутри адреса различаются`() {
    val text = "GET {{host}}/users?id={{id}}"
    assertEquals(listOf("GET"), kinds(text, HttpTokens.Kind.METHOD))
    assertEquals(listOf("{{host}}", "{{id}}"), kinds(text, HttpTokens.Kind.VARIABLE))
    assertTrue(kinds(text, HttpTokens.Kind.TARGET).any { it.contains("/users?id=") })
  }

  @Test
  fun `заголовок делится на имя и значение`() {
    val text = "GET https://e.com/a\nAuthorization: Bearer {{token}}\n"
    assertEquals(listOf("Authorization:"), kinds(text, HttpTokens.Kind.HEADER_NAME))
    assertEquals(listOf("{{token}}"), kinds(text, HttpTokens.Kind.VARIABLE))
    assertTrue(kinds(text, HttpTokens.Kind.HEADER_VALUE).any { it.contains("Bearer") })
  }

  @Test
  fun `разделитель, пометка и комментарий — разные вещи`() {
    val text = "### Первый\n# @name Проверка\n# обычный комментарий\n// тоже\nGET https://e.com/a"
    assertEquals(listOf("### Первый"), kinds(text, HttpTokens.Kind.SEPARATOR))
    assertEquals(listOf("# @name Проверка"), kinds(text, HttpTokens.Kind.META))
    assertEquals(listOf("# обычный комментарий", "// тоже"), kinds(text, HttpTokens.Kind.COMMENT))
  }

  @Test
  fun `тело начинается после пустой строки, и двоеточие в нём не делает заголовка`() {
    // Без перехода в «тело» строка JSON «"a": 1» разбиралась бы как заголовок с именем «"a"».
    val text = "POST https://e.com/a\nContent-Type: application/json\n\n{\n  \"a\": 1\n}\n"
    assertEquals(listOf("Content-Type:"), kinds(text, HttpTokens.Kind.HEADER_NAME))
    assertTrue(kinds(text, HttpTokens.Kind.BODY).any { it.contains("\"a\"") })
  }

  @Test
  fun `директивы тела и ответа подсвечиваются отдельно`() {
    val text = "POST https://e.com/a\n\n< payload.json\n>> out.json\n> handler.js\n"
    assertEquals(listOf("< payload.json", ">> out.json", "> handler.js"), kinds(text, HttpTokens.Kind.DIRECTIVE))
  }

  @Test
  fun `объявление переменной файла не путается с телом`() {
    val text = "@host = https://api.example.com\n\nGET {{host}}/a\n"
    assertEquals(listOf("@host = https://api.example.com"), kinds(text, HttpTokens.Kind.VARIABLE_DECL))
  }

  @Test
  fun `пустой текст и текст без перевода строки не ломают разбор`() {
    assertEquals(emptyList(), HttpTokens.scan(""))
    val text = "GET https://e.com/a"
    assertEquals("GET", HttpTokens.scan(text).first().let { text.substring(it.start, it.end) })
  }
}
