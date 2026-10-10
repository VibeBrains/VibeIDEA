// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
 * «Asked → answered»: whether another model answered, and which id the quirks are looked up by
 * Both products read the shared vectors (`testVectors/modelEcho.json`, VibeBrains):
 * Two hand-kept ports drifted once — a floating alias was a substitution on one side and the same model on the other
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ModelEchoVectorsTest {
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
  fun `every case reads the substitution and the quirk model as the vectors name`() {
    val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - CASE_FIELDS, "незнакомые поля кейса")
        val asked = case.text("asked")!!
        val answered = case.text("answered")
        assertEquals(case["substituted"]!!.jsonPrimitive.boolean, ModelEcho.substituted(asked, answered), "подмена")
        assertEquals(case.text("quirkModel"), ModelEcho.quirkId(asked, answered), "id для причуд")
      }.exceptionOrNull()?.let { "${case.text("asked")} → ${case.text("answered")} (${case.text("why")}): ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/modelEcho.json"
    const val MIN_CASES = 21
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("asked", "answered", "substituted", "why", "quirkModel")
  }
}
