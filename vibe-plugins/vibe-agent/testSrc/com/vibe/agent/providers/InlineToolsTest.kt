// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tools found mid-conversation: offered in place where the model takes it, and through `tools` everywhere else */
class InlineToolsTest {
  private val api = "https://api.anthropic.com/v1"
  private val spec = ToolSpec("db_query", "Run a query", buildJsonObject { put("type", "object") })

  @Test
  fun `only anthropic's own api and the models that take mid-conversation system messages`() {
    for (id in listOf("claude-opus-5-5", "claude-opus-5", "claude-opus-4-8", "claude-fable-5-1", "claude-mythos-5-1")) {
      assertTrue(InlineTools.supported(api, "anthropic", id), id)
    }
    for (id in listOf("claude-sonnet-5", "claude-fable-5", "claude-mythos-5", "claude-opus-4-7")) {
      assertFalse(InlineTools.supported(api, "anthropic", id), id)
    }
    assertFalse(InlineTools.supported("https://api.minimax.io/anthropic/v1", "anthropic", "claude-opus-5-5"))
    assertFalse(InlineTools.supported(api, "openai", "claude-opus-5-5"))
  }

  @Test
  fun `the message carries each definition exactly as tools would`() {
    val message = LlmMessages.anthropic(ChatMessage("system", "", toolAdditions = listOf(spec)))
    assertEquals("system", message["role"]!!.jsonPrimitive.content)
    val block = message["content"]!!.jsonArray.single().jsonObject
    assertEquals("tool_addition", block["type"]!!.jsonPrimitive.content)
    val tool = block["tool"]!!.jsonObject
    assertEquals("tool_definition", tool["type"]!!.jsonPrimitive.content)
    assertEquals(ToolCalls.anthropicTools(listOf(spec)).single(), tool["definition"])
  }

  @Test
  fun `a round's found tools follow its results in the thread, and survive the thread file`() {
    val call = ToolCall("c1", "tool_search", "{}")
    val round = ToolRound("", listOf(call), listOf(ToolResult("c1", "tool_search", "found")), addedTools = listOf("db_query", "gone"))
    val stored = ToolRounds.fromJson(ToolRounds.toJson(listOf(round))).single()
    assertEquals(listOf("db_query", "gone"), stored.addedTools)
    val expanded = ToolRounds.expand(listOf(ChatMessage("assistant", "done", toolRounds = listOf(stored)))) { name ->
      spec.takeIf { name == it.name }
    }
    assertEquals(listOf("assistant", ToolCalls.ROLE, "system", "assistant"), expanded.map { it.role })
    // A tool that is gone since is not offered; the rest is
    assertEquals(listOf(spec), expanded[2].toolAdditions)
    // Without a way to name the tools, nothing is added
    assertEquals(3, ToolRounds.expand(listOf(ChatMessage("assistant", "done", toolRounds = listOf(stored)))).size)
  }

  @Test
  fun `an adding message stays among the messages, and its prefix key covers it`() {
    val wire = listOf(ChatMessage("user", "q"), ChatMessage("system", "", toolAdditions = listOf(spec)))
    val built = LlmMessages.anthropicMessages(wire, "sys", "[]", ThinkingReplay.NONE, boundary = null, ttl = null)
    assertEquals(listOf("user", "system"), built.messages.map { (it as JsonObject)["role"]!!.jsonPrimitive.content })
    val without = LlmMessages.anthropicMessages(wire.take(1), "sys", "[]", ThinkingReplay.NONE, boundary = null, ttl = null)
    assertTrue(built.answerKey != without.answerKey)
  }
}
