// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The effort moved mid-thread goes as an update in place, and the request keeps the thread's first effort */
class EffortUpdatesTest {
  private val key = "openai/gpt-6.1-sol"

  private fun answer(effort: String?, by: String = key) =
    ChatMessage("assistant", "a", effortMark = effort?.let { EffortUpdates.mark(by, it) })

  private fun roles(plan: EffortUpdates.Plan) = plan.messages.map { if (it.role == EffortUpdates.ROLE) "update:${it.text}" else it.role }

  @Test
  fun `a fresh thread sends its effort and no update`() {
    val plan = EffortUpdates.plan(listOf(ChatMessage("user", "q")), key, "low")
    assertEquals("low", plan.requestEffort)
    assertEquals(listOf("user"), roles(plan))
    assertEquals("$key#low", plan.mark)
  }

  @Test
  fun `a moved slider keeps the first effort in the request and puts the update before the new question`() {
    val plan = EffortUpdates.plan(listOf(ChatMessage("user", "q1"), answer("low"), ChatMessage("user", "q2")), key, "high")
    assertEquals("low", plan.requestEffort)
    assertEquals(listOf("user", "assistant", "update:high", "user"), roles(plan))
  }

  @Test
  fun `every earlier update is replayed at its place, the same on every later request`() {
    val history = listOf(
      ChatMessage("user", "q1"), answer("low"),
      ChatMessage("user", "q2"), answer("high"),
      ChatMessage("user", "q3"), answer("high"),
      ChatMessage("user", "q4"),
    )
    val plan = EffortUpdates.plan(history, key, "medium")
    assertEquals("low", plan.requestEffort)
    assertEquals(listOf("user", "assistant", "update:high", "user", "assistant", "user", "assistant", "update:medium", "user"), roles(plan))
  }

  @Test
  fun `answers of another model say nothing about this one`() {
    val history = listOf(ChatMessage("user", "q1"), answer("max", by = "openai/gpt-6-astra"), ChatMessage("user", "q2"))
    val plan = EffortUpdates.plan(history, key, "low")
    assertEquals("low", plan.requestEffort)
    assertEquals(listOf("user", "assistant", "user"), roles(plan))
  }

  @Test
  fun `the update is an input item of its own on the wire`() {
    val (_, input) = ResponsesWire.input(listOf(ChatMessage(EffortUpdates.ROLE, "high"), ChatMessage("user", "q")), key)
    val item = input.first().jsonObject
    assertEquals("configuration_update", item["type"]?.jsonPrimitive?.content)
    assertEquals("high", item["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
  }
}
