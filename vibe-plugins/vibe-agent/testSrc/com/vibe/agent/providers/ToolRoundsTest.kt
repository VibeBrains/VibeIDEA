// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.ChatTranscriptCodec
import com.vibe.agent.history.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tool rounds survive the thread file and come back into the request as they were sent. */
class ToolRoundsTest {
  private val call = ToolCall("c1", "memory_search", """{"query":"roles"}""")
  private val round = ToolRound("Looking. ", listOf(call), listOf(ToolResult("c1", "memory_search", "found roles.json")))

  @Test
  fun `an answer with rounds expands into the exchange and then the answer`() {
    val stored = ChatMessage("assistant", "Looking. It is roles.json.", reasoning = "think", toolRounds = listOf(round))
    val wire = ToolRounds.expand(listOf(ChatMessage("user", "where?"), stored, ChatMessage("user", "and?")))
    assertEquals(listOf("user", "assistant", ToolCalls.ROLE, "assistant", "user"), wire.map { it.role })
    assertEquals(listOf(call), wire[1].toolCalls)
    assertEquals("found roles.json", wire[2].toolResults.single().text)
    assertEquals("It is roles.json.", wire[3].text)
    assertEquals("think", wire[3].reasoning)
    assertTrue(wire[3].toolRounds.isEmpty())
  }

  @Test
  fun `a later turn sends each round's reasoning with its calls, and the answer only its own`() {
    val thinking = round.copy(reasoning = "which file? ")
    val stored = ChatMessage("assistant", "Looking. It is roles.json.", reasoning = "which file? found it", toolRounds = listOf(thinking))
    val wire = ToolRounds.expand(listOf(ChatMessage("user", "where?"), stored, ChatMessage("user", "and?")))
    assertEquals("which file? ", wire[1].reasoning, "the tool call message carries its reasoning")
    assertEquals("found it", wire[3].reasoning, "the answer does not repeat the round's reasoning")
    // What goes out for a model that echoes: reasoning_content on the tool call message of the earlier turn.
    assertEquals("which file? ", (LlmMessages.openAi(wire[1], echoReasoning = true)["reasoning_content"] as kotlinx.serialization.json.JsonPrimitive).content)
  }

  @Test
  fun `the round's reasoning survives the thread codec`() {
    val thinking = round.copy(reasoning = "which file?")
    val record = ChatMessageRecord(Role.ASSISTANT, "Looking. ", at = "2026-09-17T00:00:00Z", toolRounds = listOf(thinking))
    val thread = ChatThread("t1", "2026-09-17T00:00:00Z", "2026-09-17T00:00:00Z", null, null, listOf(record))
    assertEquals(listOf(thinking), ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(thread))!!.messages.single().toolRounds)
  }

  @Test
  fun `a turn that ended on calls still has an assistant answer`() {
    val wire = ToolRounds.expand(listOf(ChatMessage("assistant", "Looking. ", toolRounds = listOf(round))))
    assertEquals(ToolRounds.NO_ANSWER, wire.last().text)
  }

  @Test
  fun `messages without rounds pass untouched`() {
    val plain = listOf(ChatMessage("user", "hi"), ChatMessage("assistant", "hello"))
    assertEquals(plain, ToolRounds.expand(plain))
  }

  @Test
  fun `rounds survive the thread codec`() {
    val record = ChatMessageRecord(Role.ASSISTANT, "Looking. It is roles.json.", at = "2026-09-13T00:00:00Z", toolRounds = listOf(round))
    val thread = ChatThread("t1", "2026-09-13T00:00:00Z", "2026-09-13T00:00:00Z", null, null, listOf(record))
    val back = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(thread))!!
    assertEquals(listOf(round), back.messages.single().toolRounds)
    assertEquals(listOf(round), back.messages.single().withPinned(true).toolRounds)
  }

  @Test
  fun `a long result is stored cut and says so`() {
    val long = round.copy(results = listOf(ToolResult("c1", "memory_search", "x".repeat(ToolRounds.MAX_STORED_RESULT_CHARS + 10))))
    val text = ToolRounds.forStorage(listOf(long)).single().results.single().text
    assertTrue(text.length < ToolRounds.MAX_STORED_RESULT_CHARS + 100)
    assertTrue(text.endsWith("[truncated: ${ToolRounds.MAX_STORED_RESULT_CHARS + 10} chars]"))
  }

  @Test
  fun `a broken stored round is dropped, not the thread`() {
    val parsed = ToolRounds.fromJson(kotlinx.serialization.json.Json.parseToJsonElement("""[{"text":"x"},{"calls":[{"id":"c","name":"n"}]}]"""))
    assertEquals(1, parsed.size)
    assertEquals("n", parsed.single().calls.single().name)
  }

  @Test
  fun `the tool exchange counts towards the window`() {
    val plain = ChatMessage("assistant", "Looking. It is roles.json.")
    val withRounds = plain.copy(toolRounds = listOf(round.copy(results = listOf(ToolResult("c1", "memory_search", "x".repeat(4000))))))
    assertTrue(ToolRounds.estimatedTokens(withRounds) >= ToolRounds.estimatedTokens(plain) + 1000)
  }

  @Test
  fun `old results are dropped, recent ones and the calls stay, and dropping twice changes nothing`() {
    val older = ChatMessage("assistant", "Looking. It is roles.json.", toolRounds = listOf(round))
    val recent = ChatMessage("assistant", "Again.", toolRounds = listOf(round))
    val once = ToolRounds.shrinkResults(listOf(older, ChatMessage("user", "and?"), recent), upTo = 2)
    assertTrue(once[0].toolRounds.single().results.single().text.startsWith("[tool result dropped"))
    assertEquals(listOf(call), once[0].toolRounds.single().calls)
    assertEquals("found roles.json", once[2].toolRounds.single().results.single().text)
    assertEquals(once, ToolRounds.shrinkResults(once, upTo = 2))
  }
}
