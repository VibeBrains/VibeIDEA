// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A call written as text in any family's markup is read the same here and in VibeIDE
 * The shared vectors (`testVectors/textToolCalls.json`, VibeBrains) are read whole and through the streaming filter,
 * cut into single characters: an opener split between chunks is the usual case, not the rare one
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class TextToolCallsVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText()
               ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  private val cases by lazy { vectors["cases"]!!.jsonArray.map { it.jsonObject } }

  private val tools by lazy { vectors["tools"]!!.jsonObject.mapValues { it.value.jsonObject } }

  private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

  private fun JsonObject.format(): String? = this["format"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int)
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    cases.forEach { assertEquals(emptySet(), it.keys - CASE_FIELDS, "незнакомые поля кейса ${it["name"]}") }
    val formats = TextToolCalls.FORMATS.map { it.id }.toSet()
    assertEquals(formats, cases.mapNotNull { it.format() }.toSet(), "у каждого формата свои векторы, и чужих нет")
  }

  @Test
  fun `every case reads into the outcome, the calls and the answer the vectors name`() {
    val failures = cases.mapNotNull { case ->
      val name = case.string("name")
      val text = case.string("text")
      runCatching {
        val read = TextToolCalls.parse(text, tools)
        assertEquals(case.string("outcome"), read.outcome.name.lowercase(), "$name: outcome")
        assertEquals(case.format(), read.format, "$name: format")
        val expected = case["calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.string("name") }, read.calls.map { it.name }, "$name: names")
        assertEquals(expected.map { it["arguments"]!!.jsonObject }, read.calls.map { it.arguments }, "$name: arguments")
        assertEquals(case.string("answer"), read.answer, "$name: answer")
      }.exceptionOrNull()?.message
    }
    assertEquals(emptyList(), failures)
  }

  @Test
  fun `streamed a character at a time, no markup reaches the text and none of the answer is held`() {
    val failures = cases.mapNotNull { case ->
      val name = case.string("name")
      val text = case.string("text")
      runCatching {
        val shown = StringBuilder()
        val filter = ToolMarkupFilter { shown.append(it) }
        text.forEach { filter.accept(it.toString()) }
        val held = filter.finish()
        assertEquals(case.format() != null, held != null, "$name: held")
        // What was shown plus what was held is the whole answer, nothing lost and nothing twice
        assertEquals(text, shown.toString() + held.orEmpty(), "$name: whole")
        if (held != null) {
          assertTrue(TextToolCalls.start(shown) < 0, "$name: markup reached the feed: $shown")
          val heldOutcome = TextToolCalls.parse(held, tools).outcome.name.lowercase()
          assertEquals(case.string("outcome"), heldOutcome, "$name: held reads")
        }
      }.exceptionOrNull()?.message
    }
    assertEquals(emptyList(), failures)
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/textToolCalls.json"
    const val MIN_CASES = 30
    val FILE_FIELDS = setOf("_comment", "version", "tools", "cases")
    val CASE_FIELDS = setOf("name", "format", "text", "outcome", "calls", "answer")
  }
}
