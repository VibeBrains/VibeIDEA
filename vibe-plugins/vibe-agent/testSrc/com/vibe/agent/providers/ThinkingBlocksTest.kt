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
    val wire = LlmMessages.anthropic(m, thinking = ThinkingReplay.ALL.blocksFor(m, "key", "p/m"))
    val content = wire["content"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("thinking", "text"), content.map { it["type"]!!.jsonPrimitive.content })
    assertEquals("checked the file", content[0]["thinking"]!!.jsonPrimitive.content)
    assertNull(content[0]["signature"])
    // Claude takes back only what it signed, and a model that did not ask gets nothing
    assertEquals(emptyList(), ThinkingReplay.SAME_PREFIX.blocksFor(m, "key", "p/m"))
    assertEquals(emptyList(), ThinkingReplay.NONE.blocksFor(m, "key", "p/m"))
    assertEquals(emptyList(), ThinkingReplay.ALL.blocksFor(ChatMessage("user", "q", reasoning = "x"), "key", "p/m"))
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

  private fun key(system: String, tools: String, vararg messages: String) =
    ThinkingBlock.PrefixKey(system, tools).apply { messages.forEach { add(it) } }.current()

  @Test
  fun `the prefix key follows the system prompt, the tool set and every message before the block`() {
    val k = key("system", "[tools]", "a")
    assertEquals(k, key("system", "[tools]", "a"))
    assertNotEquals(k, key("system", "[tools, search]", "a"))
    assertNotEquals(k, key("system 2", "[tools]", "a"))
    assertNotEquals(k, key("system", "[tools]", "b"))
    // Boundaries count: the same bytes split differently are a different prefix
    assertNotEquals(key("s", "t", "ab"), key("s", "t", "a", "b"))
  }

  private val call = ToolCall("t1", "read", "{}")
  private val blocks = listOf(ThinkingBlock("why", "sig"))

  /** One turn of a tool loop: the question, the round with its blocks produced after [before], the results, the answer */
  private fun turn(question: String, keyBefore: String?, by: String? = null) = listOf(
    ChatMessage("user", question),
    ChatMessage("assistant", "", toolCalls = listOf(call), thinking = blocks, thinkingKey = keyBefore, thinkingBy = by),
    ChatMessage(ToolCalls.ROLE, "", toolResults = listOf(ToolResult("t1", "read", "ok"))),
    ChatMessage("assistant", "done"),
  )

  private fun request(wire: List<ChatMessage>, system: String = "sys", replay: ThinkingReplay = ThinkingReplay.SAME_PREFIX,
                      requester: String = CLAUDE) =
    LlmMessages.anthropicMessages(wire, system, "[tools]", replay, requester, boundary = null, ttl = null)

  private fun hasBlocks(message: kotlinx.serialization.json.JsonObject) =
    (message["content"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { it.jsonObject["type"]?.jsonPrimitive?.content == "thinking" }

  @Test
  fun `claude gets the blocks of an earlier turn while the prefix they were produced after is unchanged`() {
    // The key a round is produced after is the answer key of the request that produced it
    val first = request(listOf(ChatMessage("user", "q1")))
    val history = turn("q1", first.answerKey) + ChatMessage("user", "q2")
    val next = request(history)
    assertTrue(hasBlocks(next.messages[1]), "блок прошлого хода не вернулся при том же префиксе")
    // A changed system prompt: the same history, the blocks stay out
    assertFalse(hasBlocks(request(history, system = "sys 2").messages[1]))
    // A changed earlier message (a compacted or cut history): the blocks stay out
    val edited = listOf(ChatMessage("user", "q1 edited")) + history.drop(1)
    assertFalse(hasBlocks(request(edited).messages[1]))
    // No key: never to Claude
    assertFalse(hasBlocks(request(turn("q1", null) + ChatMessage("user", "q2")).messages[1]))
  }

  @Test
  fun `the blocks survive the thread file with their key`() {
    val round = ToolRound("text", listOf(ToolCall("t1", "read", "{}")), listOf(ToolResult("t1", "read", "ok")),
                          thinking = listOf(ThinkingBlock("why", "sig"), ThinkingBlock(redactedData = "opaque")), thinkingKey = "k1")
    val stored = ToolRounds.fromJson(ToolRounds.toJson(listOf(round))).single()
    assertEquals(round.thinking, stored.thinking)
    val expanded = ToolRounds.expand(listOf(ChatMessage("assistant", "textanswer", toolRounds = listOf(stored))))
    assertEquals(round.thinking, expanded.first().thinking)
    assertEquals("k1", expanded.first().thinkingKey)
  }

  @Test
  fun `a thread that changed model sends a signature back only to the vendor that made it`() {
    val first = request(listOf(ChatMessage("user", "q1")))
    // MiniMax on Anthropic's wire signed the round; the thread then moved to Claude with the same system and tools
    val fromMiniMax = turn("q1", first.answerKey, by = MINIMAX) + ChatMessage("user", "q2")
    assertFalse(hasBlocks(request(fromMiniMax).messages[1]), "подпись MiniMax ушла к Claude")
    // Another Claude model drops a block it cannot read without an error, and keeps one it can
    assertTrue(hasBlocks(request(turn("q1", first.answerKey, by = "anthropic/claude-sonnet-5-5") + ChatMessage("user", "q2")).messages[1]))
    // The other way: Claude's blocks reach a model that requires its reasoning back as text, without the signature
    val fromClaude = turn("q1", first.answerKey, by = CLAUDE) + ChatMessage("user", "q2")
    val content = request(fromClaude, replay = ThinkingReplay.ALL, requester = MINIMAX).messages[1]["content"]!!.jsonArray
    val thinking = content.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "thinking" }
    assertEquals("why", thinking["thinking"]?.jsonPrimitive?.content)
    assertFalse("signature" in thinking, "подпись Claude ушла чужому вендору")
    // Its own blocks go back to their producer as they came
    val own = request(turn("q1", first.answerKey, by = MINIMAX) + ChatMessage("user", "q2"), replay = ThinkingReplay.ALL, requester = MINIMAX)
    assertTrue(own.messages[1]["content"]!!.jsonArray.any { it.jsonObject["signature"]?.jsonPrimitive?.content == "sig" })
  }

  @Test
  fun `the producer survives the thread file`() {
    val round = ToolRound("text", listOf(ToolCall("t1", "read", "{}")), listOf(ToolResult("t1", "read", "ok")),
                          thinking = listOf(ThinkingBlock("why", "sig")), thinkingKey = "k1", thinkingBy = CLAUDE)
    val stored = ToolRounds.fromJson(ToolRounds.toJson(listOf(round))).single()
    assertEquals(CLAUDE, ToolRounds.expand(listOf(ChatMessage("assistant", "textanswer", toolRounds = listOf(stored)))).first().thinkingBy)
  }

  private companion object {
    const val CLAUDE = "anthropic/claude-opus-5-5"
    const val MINIMAX = "minimax-anthropic/MiniMax-M3"
  }
}
