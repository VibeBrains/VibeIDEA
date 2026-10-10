// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
  fun `a round of tool results is not a question, mid-loop or in the history`() {
    val round = listOf(answer("low"), ChatMessage(ToolCalls.ROLE, ""), answer("low"))
    // Mid-loop: the last message is tool results, and the update still stands before the person's question
    val midLoop = EffortUpdates.plan(listOf(ChatMessage("user", "q1")) + round + ChatMessage("user", "q2") +
                                     listOf(answer(null), ChatMessage(ToolCalls.ROLE, "")), key, "high")
    assertEquals("low", midLoop.requestEffort)
    assertEquals(listOf("user", "assistant", "tool", "assistant", "update:high", "user", "assistant", "tool"), roles(midLoop))
    // Later: the question keeps the effort of the first answer that followed it, tool rounds do not shift the count
    val later = EffortUpdates.plan(listOf(ChatMessage("user", "q1")) + round + ChatMessage("user", "q2") +
                                   listOf(answer("high"), ChatMessage(ToolCalls.ROLE, ""), answer("high"), ChatMessage("user", "q3")),
                                   key, "high")
    assertEquals(listOf("user", "assistant", "tool", "assistant", "update:high", "user", "assistant", "tool", "assistant", "user"),
                 roles(later))
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

  @Test
  fun `on anthropic's wire the update is a system message with no content and the new effort`() {
    val wire = LlmMessages.anthropic(ChatMessage(EffortUpdates.ROLE, "high"))
    assertEquals("""{"role":"system","content":[],"output_config":{"effort":"high"}}""", wire.toString())
  }

  @Test
  fun `an anthropic request moves its effort by an update only in adaptive thinking`() {
    fun fields(text: String) = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    assertEquals("high", EffortUpdates.anthropicEffort(fields("""{"thinking":{"type":"adaptive"},"output_config":{"effort":"high"}}""")))
    // Thinking on by default with only the effort sent is adaptive too
    assertEquals("low", EffortUpdates.anthropicEffort(fields("""{"output_config":{"effort":"low"}}""")))
    // between_tools (Sonnet 5.5) and disabled (Haiku 5.5) refuse a mid-conversation change with a 400
    assertNull(EffortUpdates.anthropicEffort(fields("""{"thinking":{"type":"between_tools"},"output_config":{"effort":"low"}}""")))
    assertNull(EffortUpdates.anthropicEffort(fields("""{"thinking":{"type":"disabled"}}""")))
    assertNull(EffortUpdates.anthropicEffort(fields("""{"thinking":{"type":"adaptive"}}""")))
  }

  @Test
  fun `updates go to anthropic's own api, for the models the vendor names`() {
    val api = "https://api.anthropic.com/v1"
    for (id in listOf("claude-fable-5-1", "claude-mythos-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-haiku-5-5")) {
      assertTrue(EffortUpdates.anthropicSupported(api, id), id)
    }
    for (id in listOf("claude-fable-5", "claude-mythos-5", "claude-sonnet-5", "claude-opus-4-8", "gpt-6.1-sol")) {
      assertFalse(EffortUpdates.anthropicSupported(api, id), id)
    }
    // Another address on the same wire never said it takes the field
    assertFalse(EffortUpdates.anthropicSupported("https://openrouter.ai/api/v1", "claude-opus-5-5"))
    // And a Claude behind a Responses wire gets no configuration_update: that quirk is another one
    assertFalse(ModelQuirks.has("claude-opus-5-5", ModelQuirks.Quirk.EFFORT_BY_UPDATE))
  }
}
