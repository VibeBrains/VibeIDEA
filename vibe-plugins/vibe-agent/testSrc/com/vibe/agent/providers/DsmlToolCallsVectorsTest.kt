// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A call written as text in DeepSeek's markup is read the same here and in VibeIDE
 * The shared vectors (`testVectors/dsmlToolCalls.json`, VibeBrains) are read whole and through the streaming filter,
 * cut into single characters: a tag split between chunks is the usual case, not the rare one
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class DsmlToolCallsVectorsTest {
  private val vectors: JsonObject by lazy {
    val text = javaClass.getResource(VECTORS)?.readText() ?: error("нет $VECTORS в classpath — указатель набора не поднят?")
    Json.parseToJsonElement(text).jsonObject
  }

  private val cases by lazy { vectors["cases"]!!.jsonArray.map { it.jsonObject } }

  @Test
  fun `the file is a version this test reads`() {
    assertEquals(emptySet(), vectors.keys - FILE_FIELDS, "незнакомые поля файла векторов")
    assertEquals(1, vectors["version"]?.jsonPrimitive?.int)
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    cases.forEach { assertEquals(emptySet(), it.keys - CASE_FIELDS, "незнакомые поля кейса ${it["name"]}") }
  }

  @Test
  fun `every case parses into the calls and the answer the vectors name`() {
    val failures = cases.mapNotNull { case ->
      val name = case["name"]!!.jsonPrimitive.content
      val text = case["text"]!!.jsonPrimitive.content
      runCatching {
        assertEquals(case["markup"]!!.jsonPrimitive.boolean, DsmlToolCalls.start(text) >= 0, "$name: markup")
        val parsed = DsmlToolCalls.parse(text)
        assertEquals(case["parsed"]!!.jsonPrimitive.boolean, parsed.parsed, "$name: parsed")
        val expected = case["calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it["name"]!!.jsonPrimitive.content }, parsed.calls.map { it.name }, "$name: names")
        assertEquals(expected.map { it["arguments"]!!.jsonObject }, parsed.calls.map { it.arguments }, "$name: arguments")
        assertEquals(case["answer"]!!.jsonPrimitive.content, parsed.answer, "$name: answer")
      }.exceptionOrNull()?.message
    }
    assertEquals(emptyList(), failures)
  }

  @Test
  fun `streamed a character at a time, no markup reaches the text and none of the answer is held`() {
    val failures = cases.mapNotNull { case ->
      val name = case["name"]!!.jsonPrimitive.content
      val text = case["text"]!!.jsonPrimitive.content
      runCatching {
        val shown = StringBuilder()
        val filter = ToolMarkupFilter { shown.append(it) }
        text.forEach { filter.accept(it.toString()) }
        val held = filter.finish()
        assertEquals(case["markup"]!!.jsonPrimitive.boolean, held != null, "$name: held")
        // What was shown plus what was held is the whole answer, nothing lost and nothing twice
        assertEquals(text, shown.toString() + held.orEmpty(), "$name: whole")
        if (held != null) assertTrue(DsmlToolCalls.start(shown) < 0, "$name: markup reached the feed: $shown")
      }.exceptionOrNull()?.message
    }
    assertEquals(emptyList(), failures)
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/dsmlToolCalls.json"
    const val MIN_CASES = 9
    val FILE_FIELDS = setOf("_comment", "version", "cases")
    val CASE_FIELDS = setOf("name", "text", "markup", "parsed", "calls", "answer")
  }
}
