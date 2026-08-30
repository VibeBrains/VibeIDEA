// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.schemas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VibeSchemasTest {
  private fun schema(name: String): JsonObject {
    val text = VibeSchemaProviderFactory::class.java.getResourceAsStream("/schemas/$name.json")
      ?.bufferedReader()?.readText()
    assertNotNull(text, "схема $name не попала в ресурсы плагина")
    return Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `every declared config has a schema in the build`() {
    // Схема, объявленная и не собранная, — это красная подсветка на пустом месте у пользователя.
    for (name in listOf("commands", "pipelines", "hooks", "servers", "providers")) {
      assertTrue(schema(name).containsKey("title"), "у схемы $name нет заголовка")
    }
  }

  @Test
  fun `the pipeline roles in the schema are the roles the product enforces`() {
    // Список ролей в схеме, разошедшийся с кодом, подсказывает роль, которую парсер отвергнет.
    val enum = schema("pipelines")["properties"]!!.jsonObject["pipelines"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
      .jsonObject["steps"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["role"]!!.jsonObject["enum"]!!
      .jsonArray.map { it.jsonPrimitive.content }.toSet()
    assertEquals(com.vibe.agent.pipelines.PipelinesFile.ROLES, enum)
  }

  @Test
  fun `the hook events in the schema are the events the product knows`() {
    val enum = schema("hooks")["properties"]!!.jsonObject["hooks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
      .jsonObject["event"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    assertEquals(com.vibe.agent.hooks.HookEvent.entries.map { it.wire }.toSet(), enum)
  }

  @Test
  fun `the commands schema forbids fields the parser would ignore`() {
    // Молча проигнорированное поле — это час на выяснение, почему настройка не действует.
    val command = schema("commands")["definitions"]!!.jsonObject["command"]!!.jsonObject
    assertEquals(false, command["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
  }
}
