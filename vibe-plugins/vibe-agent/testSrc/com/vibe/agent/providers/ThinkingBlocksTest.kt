// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Thinking blocks on Anthropic's wire: captured exactly as they streamed, sent back only to the models that take them. */
class ThinkingBlocksTest {
  private fun events(vararg lines: String) = ThinkingAccumulator().apply {
    lines.forEach { anthropicEvent(Json.parseToJsonElement(it).jsonObject) }
  }.blocks()

  @Test
  fun `a claude block keeps its text and its signature`() {
    val blocks = events(
      """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check the "}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"schema first."}}""",
      """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
      """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"read"}}""",
    )
    assertEquals(listOf(ThinkingBlock("Check the schema first.", "sig-1")), blocks)
  }

  @Test
  fun `a redacted block keeps its payload, and a vendor without signatures leaves none`() {
    val blocks = events(
      """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque"}}""",
      """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}""",
      """{"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"mimo thought"}}""",
    )
    assertEquals(listOf(ThinkingBlock(redactedData = "opaque"), ThinkingBlock("mimo thought", null)), blocks)
    assertEquals("redacted_thinking", blocks[0].toWire()["type"]!!.jsonPrimitive.content)
    assertNull(blocks[1].toWire()["signature"])
  }

  @Test
  fun `the blocks go first in the assistant message, before the text and the tool calls`() {
    val m = ChatMessage("assistant", "Reading it.", toolCalls = listOf(ToolCall("t1", "read", "{}")),
                        thinking = listOf(ThinkingBlock("why", "sig")))
    val content = ToolCalls.anthropicAssistant(m, m.thinking)["content"]!!.jsonArray.map {
      it.jsonObject["type"]!!.jsonPrimitive.content
    }
    assertEquals(listOf("thinking", "text", "tool_use"), content)
    assertEquals(listOf("text", "tool_use"),
                 ToolCalls.anthropicAssistant(m)["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
  }

  @Test
  fun `an answer that kept its reasoning only as text goes back with one unsigned block, to models that require it`() {
    val m = ChatMessage("assistant", "Done.", reasoning = "checked the file")
    val wire = LlmMessages.anthropic(m, thinking = ThinkingReplay.ALL.blocksFor(m, "key"))
    val content = wire["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("thinking", "text"), content.map { it["type"]!!.jsonPrimitive.content })
    assertEquals("checked the file", content[0]["thinking"]!!.jsonPrimitive.content)
    assertNull(content[0]["signature"])
    // Claude takes back only what it signed, and a model that did not ask gets nothing
    assertEquals(emptyList(), ThinkingReplay.SAME_PREFIX.blocksFor(m, "key"))
    assertEquals(emptyList(), ThinkingReplay.NONE.blocksFor(m, "key"))
    assertEquals(emptyList(), ThinkingReplay.ALL.blocksFor(ChatMessage("user", "q", reasoning = "x"), "key"))
  }

  @Test
  fun `models that require their reasoning get every block, claude only the blocks of the same prefix`() {
    val all = ThinkingReplay.of("mimo-v2.6-pro")
    assertEquals(ThinkingReplay.ALL, all)
    assertTrue(all.admits(null, "k"))
    assertEquals(ThinkingReplay.ALL, ThinkingReplay.of("kimi-k3"))

    val claude = ThinkingReplay.of("claude-opus-5-5")
    assertEquals(ThinkingReplay.SAME_PREFIX, claude)
    assertTrue(claude.admits("k", "k"))
    // A changed system prompt or tool set, or a block read back from the thread: never replayed to Claude.
    assertFalse(claude.admits("old", "k"))
    assertFalse(claude.admits(null, "k"))

    // MiniMax asks for the whole content back, thinking blocks included, on its Anthropic-compatible endpoint
    assertEquals(ThinkingReplay.ALL, ThinkingReplay.of("minimax-m3"))
    // A vendor on this wire that never asked for its blocks back gets none.
    assertEquals(ThinkingReplay.NONE, ThinkingReplay.of("qwen3.8-max"))
  }

  @Test
  fun `the prefix key follows the system prompt and the tool set`() {
    val key = ThinkingBlock.prefixKey("system", "[tools]")
    assertEquals(key, ThinkingBlock.prefixKey("system", "[tools]"))
    assertNotEquals(key, ThinkingBlock.prefixKey("system", "[tools, search]"))
    assertNotEquals(key, ThinkingBlock.prefixKey("system 2", "[tools]"))
  }

  @Test
  fun `the blocks survive the thread file and come back without a key`() {
    val round = ToolRound("text", listOf(ToolCall("t1", "read", "{}")), listOf(ToolResult("t1", "read", "ok")),
                          thinking = listOf(ThinkingBlock("why", "sig"), ThinkingBlock(redactedData = "opaque")))
    val stored = ToolRounds.fromJson(ToolRounds.toJson(listOf(round))).single()
    assertEquals(round.thinking, stored.thinking)
    val expanded = ToolRounds.expand(listOf(ChatMessage("assistant", "textanswer", toolRounds = listOf(stored))))
    assertEquals(round.thinking, expanded.first().thinking)
    assertNull(expanded.first().thinkingKey)
  }
}
