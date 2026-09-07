// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Запрос данных: агент спрашивает значение, а не разрешение, и ответ обязан быть типизирован. */
class ElicitationTest {
  private fun params(text: String) = Json.parseToJsonElement(text).jsonObject

  private val form = params("""
    {
      "sessionId": "sess_abc123",
      "mode": "form",
      "message": "Ваш выбор стратегии?",
      "requestedSchema": {
        "type": "object",
        "properties": {
          "strategy": { "enum": ["conservative", "balanced"], "title": "Стратегия" },
          "retries": { "type": "integer", "title": "Попыток", "default": "3" },
          "dryRun": { "type": "boolean", "title": "Только показать" },
          "ticket": { "type": "string", "description": "Номер задачи" }
        },
        "required": ["strategy"]
      }
    }
  """.trimIndent())

  @Test
  fun `поля разбираются с типами и обязательностью`() {
    val request = Elicitation.parse(form)
    assertEquals(Elicitation.Mode.FORM, request.mode)
    assertEquals(listOf("strategy", "retries", "dryRun", "ticket"), request.fields.map { it.name })
    assertEquals(Elicitation.Field.Kind.ENUM, request.fields[0].kind)
    assertEquals(listOf("conservative", "balanced"), request.fields[0].options)
    assertEquals(Elicitation.Field.Kind.NUMBER, request.fields[1].kind)
    assertEquals(Elicitation.Field.Kind.BOOLEAN, request.fields[2].kind)
    assertEquals(Elicitation.Field.Kind.STRING, request.fields[3].kind)
    // Обязательность живёт в required схемы, а не флагом у поля.
    assertTrue(request.fields[0].required)
    assertFalse(request.fields[3].required)
  }

  @Test
  fun `URL-режим разбирается отдельно`() {
    val request = Elicitation.parse(params("""
      { "requestId": 12, "mode": "url", "elicitationId": "oauth-001",
        "url": "https://agent.example.com/connect?elicitationId=oauth-001" }
    """.trimIndent()))
    assertEquals(Elicitation.Mode.URL, request.mode)
    assertEquals("oauth-001", request.elicitationId)
    assertTrue(request.url!!.startsWith("https://"))
  }

  @Test
  fun `неизвестный режим не притворяется формой`() {
    assertEquals(Elicitation.Mode.UNKNOWN, Elicitation.parse(params("""{ "mode": "видеозвонок" }""")).mode)
    assertEquals(Elicitation.Mode.UNKNOWN, Elicitation.parse(params("""{ }""")).mode)
  }

  @Test
  fun `ответ отдаёт типы, которые просила схема`() {
    val fields = Elicitation.parse(form).fields
    val text = Elicitation.response(
      Elicitation.Outcome.ACCEPT,
      mapOf("strategy" to "balanced", "retries" to "5", "dryRun" to "true", "ticket" to "VI-7"),
      fields,
    ).toString()
    assertTrue("\"strategy\":\"balanced\"" in text, text)
    assertTrue("\"retries\":5" in text, "число обязано уехать числом: строка «5» — частая причина отказа")
    assertTrue("\"dryRun\":true" in text, text)
    assertTrue("\"ticket\":\"VI-7\"" in text, text)
  }

  @Test
  fun `отказ и отмена не несут данных`() {
    assertEquals("""{"outcome":"decline"}""", Elicitation.response(Elicitation.Outcome.DECLINE).toString())
    assertEquals("""{"outcome":"cancel"}""", Elicitation.response(Elicitation.Outcome.CANCEL).toString())
    // Согласие в URL-режиме — тоже без content: данных у клиента нет.
    assertEquals("""{"outcome":"accept"}""", Elicitation.response(Elicitation.Outcome.ACCEPT).toString())
  }

  @Test
  fun `незаполненное обязательное называется поимённо`() {
    val fields = Elicitation.parse(form).fields
    assertEquals(listOf("strategy"), Elicitation.missing(fields, mapOf("ticket" to "VI-7")).map { it.name })
    assertEquals(emptyList(), Elicitation.missing(fields, mapOf("strategy" to "balanced")))
    assertEquals(listOf("strategy"), Elicitation.missing(fields, mapOf("strategy" to "   ")).map { it.name },
                 "пробелы — не ответ")
  }

  @Test
  fun `имя метода протокола не переизобретается`() {
    assertEquals("elicitation/create", Elicitation.METHOD)
  }
}
