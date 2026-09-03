// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenApiImportTest {
  private val spec = """
  {
    "openapi": "3.0.0",
    "info": { "title": "Магазин" },
    "servers": [ { "url": "https://api.shop.example" } ],
    "security": [ { "bearer": [] } ],
    "paths": {
      "/users/{id}": {
        "get": {
          "summary": "Пользователь по идентификатору",
          "parameters": [
            { "name": "id", "in": "path", "required": true },
            { "name": "expand", "in": "query" },
            { "name": "X-Trace", "in": "header" }
          ]
        },
        "delete": { "summary": "Удалить пользователя" }
      },
      "/orders": {
        "post": {
          "summary": "Создать заказ",
          "requestBody": { "content": { "application/json": { } } }
        }
      }
    }
  }
  """.trimIndent()

  @Test
  fun `эндпоинты, параметры и тело читаются из описания`() {
    val parsed = OpenApiImport.parse(spec)!!
    assertEquals("Магазин", parsed.title)
    assertEquals("https://api.shop.example", parsed.serverUrl)
    assertEquals(listOf("GET" to "/users/{id}", "DELETE" to "/users/{id}", "POST" to "/orders"),
                 parsed.endpoints.map { it.method to it.path })
    val get = parsed.endpoints.first()
    assertEquals(listOf("id"), get.pathParams)
    assertEquals(listOf("expand"), get.queryParams)
    assertEquals(listOf("X-Trace"), get.headerParams)
    val post = parsed.endpoints.last()
    assertTrue(post.hasBody)
    assertEquals("application/json", post.bodyContentType)
    assertTrue(post.needsAuth, "security на уровне документа — тоже требование авторизации")
  }

  @Test
  fun `параметры пути становятся переменными, а не остаются скобками OpenAPI`() {
    // Иначе адрес уходил бы на сервер с литералом «{id}» и отвечал 404 без объяснения.
    assertEquals("/users/{{id}}/posts/{{postId}}", OpenApiImport.pathWithVariables("/users/{id}/posts/{postId}"))
  }

  @Test
  fun `сгенерированный файл разбирается нашим же разбором`() {
    // Главная проверка: генератор и разбор обязаны говорить на одном языке.
    val text = OpenApiImport.toHttpFile(OpenApiImport.parse(spec)!!)
    val parsed = HttpRequestFile.parse(text)
    assertEquals(3, parsed.requests.size)
    assertTrue(parsed.problems.isEmpty(), "свой же файл не должен порождать замечаний: ${parsed.problems}")
    val first = parsed.requests.first()
    assertEquals("GET", first.method)
    assertEquals("{{host}}/users/{{id}}?expand={{expand}}", first.target)
    assertTrue(first.headers.any { it.name == "Authorization" })
    assertTrue(first.headers.any { it.name == "X-Trace" })
    assertEquals("Создать заказ", parsed.requests.last().name)
    assertTrue(parsed.requests.last().body is HttpRequestFile.Body.Inline)
  }

  @Test
  fun `не OpenAPI — не спецификация`() {
    assertNull(OpenApiImport.parse("""{"paths": {}}"""), "без версии это не описание API")
    assertNull(OpenApiImport.parse("не json"))
    // Swagger 2 отличается ключом и адресом сервера в host.
    val swagger = OpenApiImport.parse("""{"swagger":"2.0","host":"api.example.com","paths":{}}""")!!
    assertEquals("https://api.example.com", swagger.serverUrl)
  }

  @Test
  fun `описание без серверов и без путей не роняет генератор`() {
    val parsed = OpenApiImport.parse("""{"openapi":"3.1.0","info":{"title":"Пусто"}}""")!!
    assertTrue(parsed.endpoints.isEmpty())
    assertTrue(OpenApiImport.toHttpFile(parsed).contains("Пусто"))
  }
}
