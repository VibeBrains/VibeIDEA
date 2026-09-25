// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.schemas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

  @Test
  fun `the step context values in the schema are the ones the parser knows`() {
    // An editor suggesting a value the parser does not know is a typo delivered by autocomplete.
    val enum = schema("pipelines")["properties"]!!.jsonObject["pipelines"]!!.jsonObject["items"]!!.jsonObject["properties"]!!
      .jsonObject["steps"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["context"]!!.jsonObject["enum"]!!
      .jsonArray.map { it.jsonPrimitive.content }.toSet()
    assertEquals(com.vibe.agent.pipelines.StepContext.entries.map { it.wire }.toSet(), enum)
  }

  private fun enumAt(schema: JsonObject, vararg path: String): Set<String> {
    var node: JsonObject = schema
    for (key in path) node = node[key]!!.jsonObject
    return node["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
  }

  @Test
  fun `the enumerations of the providers schema are the ones the product reads`() {
    // Each of these lists lives in the code; a schema that drifted from it offers a value the parser reads as another
    val providers = schema("providers")
    assertEquals(com.vibe.agent.providers.ProvidersService.PROTOCOLS.toSet(), enumAt(providers, "definitions", "protocol"))
    assertEquals(com.vibe.agent.providers.AuthSpec.KNOWN, enumAt(providers, "definitions", "authType"))
    val provider = arrayOf("definitions", "provider", "properties")
    assertEquals(com.vibe.agent.providers.ReasoningMode.DIALECTS.toSet(), enumAt(providers, *provider, "reasoningDialect"))
    assertEquals(com.vibe.agent.providers.SubscriptionQuota.FORMATS, enumAt(providers, *provider, "quota", "properties", "format"))
    // Both places a protocol is written point at the one list
    val model = providers["definitions"]!!.jsonObject["model"]!!.jsonObject["properties"]!!.jsonObject
    assertEquals("#/definitions/protocol", model["protocol"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the providers schema never closes an object to unknown keys`() {
    // The file is shared with VibeIDE and with future builds: a key this build does not know must stay allowed
    fun walk(node: JsonElement, path: String) {
      when (node) {
        is JsonObject -> node.forEach { (key, child) ->
          if (key == "additionalProperties") assertTrue(child !is JsonPrimitive || child.booleanOrNull != false, "$path закрыт")
          walk(child, "$path/$key")
        }
        is JsonArray -> node.forEach { walk(it, path) }
        else -> Unit
      }
    }
    walk(schema("providers"), "#")
  }

  @Test
  fun `the seeded provider catalogue passes the schema key by key`() {
    val files = com.vibe.agent.defaults.VibeDefaults.manifestResourceNames().filter { it.startsWith("providers/") && it.endsWith(".jsonc") }
    assertTrue(files.size >= MIN_SEEDED_PROVIDER_FILES, "сидов провайдеров стало меньше: $files")
    val problems = files.flatMap { name ->
      val text = javaClass.getResource("/vibeDefaults/$name")?.readText() ?: error("нет $name в classpath")
      SchemaCheck(schema("providers")).problems(Json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(text)), name)
    }
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
  }

  @Test
  fun `the format example of the spec passes the schema key by key`() {
    val spec = javaClass.getResource("/help/manuals/providersSpec.md")?.readText() ?: error("нет спеки провайдеров в справке")
    val example = spec.substringAfter("## Формат").substringAfter("```jsonc\n").substringBefore("```")
    assertTrue(example.contains("\"providers\""), "образец формата не найден")
    val problems = SchemaCheck(schema("providers")).problems(Json.parseToJsonElement(com.vibe.agent.util.VibeJsonc.strip(example)), "спека")
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
  }

  @Test
  fun `the check sees a models list where the format has an object`() {
    // The mistake this schema carried: a valid file highlighted as a type error, and the reverse must be caught too
    val wrong = Json.parseToJsonElement("""{ "providers": [ { "id": "x", "models": [ { "id": "m" } ], "colour": "red" } ] }""")
    val problems = SchemaCheck(schema("providers")).problems(wrong, "пример")
    assertTrue(problems.any { "models" in it }, problems.toString())
    assertTrue(problems.any { "colour" in it }, problems.toString())
  }

  /**
   * The subset of JSON Schema our schemas use — type, properties, items, enum, anyOf, required, `$ref` into definitions
   * and additionalProperties as a map's value schema — plus one rule of our own: a key under `properties` the schema
   * does not declare is a finding, so a field the parser reads cannot go missing from the editor's completion
   */
  private class SchemaCheck(private val root: JsonObject) {
    private val out = ArrayList<String>()

    fun problems(value: JsonElement, source: String): List<String> {
      out.clear()
      check(value, root, source)
      return out.toList()
    }

    private fun resolve(schema: JsonObject): JsonObject {
      val ref = schema["\$ref"]?.jsonPrimitive?.content ?: return schema
      return ref.removePrefix("#/").split('/').fold(root) { node, key -> node[key]!!.jsonObject }
    }

    private fun check(value: JsonElement, raw: JsonObject, path: String) {
      val schema = resolve(raw)
      schema["anyOf"]?.let { alternatives ->
        val fits = alternatives.jsonArray.any { alt -> SchemaCheck(root).also { it.check(value, alt.jsonObject, path) }.out.isEmpty() }
        if (!fits) out.add("$path: не подходит ни один вариант anyOf")
        return
      }
      val types = when (val t = schema["type"]) {
        null -> null
        is JsonArray -> t.map { it.jsonPrimitive.content }
        else -> listOf(t.jsonPrimitive.content)
      }
      if (types != null && types.none { fits(value, it) }) {
        out.add("$path: ожидался ${types.joinToString("|")}, а в файле ${value::class.simpleName}")
        return
      }
      schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.let { allowed ->
        if (value is JsonPrimitive && value.content !in allowed) out.add("$path: «${value.content}» нет среди $allowed")
      }
      when (value) {
        is JsonObject -> {
          val properties = schema["properties"]?.jsonObject
          val additional = schema["additionalProperties"] as? JsonObject
          schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.filter { it !in value }?.forEach {
            out.add("$path: нет обязательного $it")
          }
          for ((key, child) in value) {
            when {
              properties?.containsKey(key) == true -> check(child, properties[key]!!.jsonObject, "$path.$key")
              additional != null -> check(child, additional, "$path.$key")
              properties != null && key !in NOT_OURS -> out.add("$path.$key: ключа нет в схеме")
            }
          }
        }
        is JsonArray -> schema["items"]?.jsonObject?.let { items -> value.forEachIndexed { i, item -> check(item, items, "$path[$i]") } }
        else -> Unit
      }
    }

    private fun fits(value: JsonElement, type: String): Boolean = when (type) {
      "object" -> value is JsonObject
      "array" -> value is JsonArray
      "null" -> value is JsonNull
      "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
      "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
      "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
      "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
      else -> false
    }
  }

  private companion object {
    const val MIN_SEEDED_PROVIDER_FILES = 10

    /** VibeIDE's field in the shared seeds; here the wire decides the tool format, and the parser does not read it */
    val NOT_OURS = setOf("toolFormat")
  }
}
