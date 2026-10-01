// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A model router answers with the model it picked, and both products must read that the same way
 * The shared vectors (`testVectors/modelRouters.json`, VibeBrains) name who answered, whether it is a substitution,
 * and which entry the answer is priced by
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ModelRoutersVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int, "новую версию формата векторов надо прочитать, а не угадать")
  }

  @Test
  fun `every case reads the answering model, the substitution and the price as the vectors name`() {
    val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - CASE_FIELDS, "незнакомые поля кейса")
        val asked = case.text("asked")!!
        // The header wins over the body, as LlmClient.lastAnsweredModel does
        val answered = case.text("header") ?: case.text("body")
        assertEquals(case.text("answered"), answered, "кто ответил")
        assertEquals(case["substituted"]!!.jsonPrimitive.boolean, ModelEcho.substituted(asked, answered), "подмена")
        val catalogue = case["catalogue"]!!.jsonArray.map { ModelEntry(id = it.jsonPrimitive.content) }
        val askedEntry = catalogue.firstOrNull { ModelEcho.tail(it.id) == ModelEcho.tail(asked) } ?: ModelEntry(id = asked)
        assertEquals(case.text("billed"), ModelEcho.billedEntry(askedEntry, answered, catalogue).id, "запись цены")
      }.exceptionOrNull()?.let { "${case.text("asked")}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/modelRouters.json"
    const val MIN_CASES = 6
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("asked", "header", "body", "catalogue", "answered", "substituted", "billed")
  }
}
