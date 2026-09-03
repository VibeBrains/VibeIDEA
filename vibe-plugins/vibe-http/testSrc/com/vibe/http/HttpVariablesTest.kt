// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpVariablesTest {
  @Test
  fun `окружения читаются, приватное перекрывает общее`() {
    val shared = HttpVariables.environments("""{"dev": {"host": "http://localhost:8080", "token": "заглушка"}}""")
    val private = HttpVariables.environments("""{"dev": {"token": "настоящий"}}""")
    val merged = HttpVariables.merge(shared, private)
    assertEquals("http://localhost:8080", merged["dev"]!!["host"])
    // Секрет живёт в приватном файле — он и должен побеждать.
    assertEquals("настоящий", merged["dev"]!!["token"])
  }

  @Test
  fun `битый файл окружений не роняет список запросов`() {
    assertEquals(emptyMap(), HttpVariables.environments("{ это не json"))
    assertEquals(emptyMap(), HttpVariables.environments(null))
  }

  @Test
  fun `неразрешённая переменная остаётся видимой и названа`() {
    // Молчаливое удаление отправило бы запрос на https:///users и спрятало причину.
    val result = HttpVariables.substitute("{{host}}/users?key={{secret}}", mapOf("host" to "https://api.example.com"))
    assertEquals("https://api.example.com/users?key={{secret}}", result.text)
    assertEquals(listOf("secret"), result.unresolved.map { it.name })
  }

  @Test
  fun `динамические переменные приходят снаружи, поэтому тест повторяем`() {
    val result = HttpVariables.substitute("id={{\$uuid}}&t={{\$timestamp}}", emptyMap()) { name ->
      when (name) { "uuid" -> "1111"; "timestamp" -> "1700000000"; else -> null }
    }
    assertEquals("id=1111&t=1700000000", result.text)
    assertTrue(result.unresolved.isEmpty())
  }

  @Test
  fun `подстановка идёт в адрес, заголовки и тело сразу`() {
    // Забытая подстановка в теле — это литерал {{token}}, ушедший на сервер и попавший в чужие логи.
    val request = HttpRequestFile.parse(
      """
      POST {{host}}/login
      Authorization: Bearer {{token}}

      {"code": "{{code}}"}
      """.trimIndent()
    ).requests.single()
    val (applied, missing) = HttpVariables.apply(request, mapOf("host" to "https://a.example", "token" to "t-1"))
    assertEquals("https://a.example/login", applied.target)
    assertEquals("Bearer t-1", applied.headers.single().value)
    assertEquals("""{"code": "{{code}}"}""", (applied.body as HttpRequestFile.Body.Inline).text)
    assertEquals(listOf("code"), missing.map { it.name })
  }
}
