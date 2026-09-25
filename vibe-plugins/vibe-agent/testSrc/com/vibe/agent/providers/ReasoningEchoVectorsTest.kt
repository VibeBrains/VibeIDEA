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
 * Which models get their reasoning back, and in which form, must be one list for VibeIDE and here
 * The shared vectors (`testVectors/reasoningEcho.json`, VibeBrains) name the model and the form on each wire;
 * the openai form is checked on the serialized message, not only on the quirk
 * A field this test does not know fails it: a contract that grew a field has to be read, not skipped
 */
class ReasoningEchoVectorsTest {
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
  fun `every model gets its reasoning back in the form the vectors name`() {
    val cases = vectors["cases"]!!.jsonArray.map { it.jsonObject }
    assertTrue(cases.size >= MIN_CASES, "векторов стало меньше: ${cases.size}")
    val failures = cases.mapNotNull { case ->
      runCatching {
        assertEquals(emptySet(), case.keys - CASE_FIELDS, "незнакомые поля кейса")
        val model = case["model"]!!.jsonPrimitive.content
        assertEquals(case["openai"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.content, openAiForm(model), "openai")
        assertEquals(case["anthropic"]!!.jsonPrimitive.content, anthropicForm(model), "anthropic")
      }.exceptionOrNull()?.let { "${case["model"]}: ${it.message}" }
    }
    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
  }

  /** The form read off the message the openai wire would send for this model */
  private fun openAiForm(model: String): String? {
    val echo = ModelQuirks.has(model, ModelQuirks.Quirk.ECHO_REASONING)
    val tags = ModelQuirks.has(model, ModelQuirks.Quirk.REASONING_AS_THINK_TAGS)
    val wire = LlmMessages.openAi(ChatMessage("assistant", "answer", reasoning = "thought"), echo, tags)
    return when {
      wire["reasoning_content"] != null -> REASONING_CONTENT
      wire["content"]!!.jsonPrimitive.content.startsWith("<think>") -> THINK_TAGS
      else -> null
    }
  }

  private fun anthropicForm(model: String): String = when (ThinkingReplay.of(model)) {
    ThinkingReplay.ALL -> "all"
    ThinkingReplay.SAME_PREFIX -> "same-prefix"
    ThinkingReplay.NONE -> "none"
  }

  private companion object {
    const val VECTORS = "/vibeDefaults/testVectors/reasoningEcho.json"
    const val MIN_CASES = 16
    const val REASONING_CONTENT = "reasoning_content"
    const val THINK_TAGS = "think-tags"
    val FILE_FIELDS = setOf("_comment", "version", "sources", "cases")
    val CASE_FIELDS = setOf("model", "openai", "anthropic")
  }
}
