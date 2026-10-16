// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.history

import com.vibe.agent.providers.ChatMessage
import com.vibe.agent.providers.LlmMessages
import com.vibe.agent.providers.ToolCall
import com.vibe.agent.providers.ToolCalls
import com.vibe.agent.providers.ToolLoopCheckpoint
import com.vibe.agent.providers.ToolResult
import com.vibe.agent.providers.ToolRound
import com.vibe.agent.providers.ToolRounds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scenario from the field: a direct-chat model reads files until the client stops the turn, never writing a word
 * The person says to continue, and the next request must carry what the stopped turn's tools returned
 */
class TranscriptWireTest {
  private val at = "2026-10-16T00:00:00Z"
  private val read = ToolCall("toolu_1", "vibe_read_file", """{"path":"config.ts"}""")
  private val grep = ToolCall("toolu_2", "vibe_run_command", """{"command":"grep -rn baseUrl src"}""")
  private val rounds = listOf(
    ToolRound("", listOf(read), listOf(ToolResult("toolu_1", "vibe_read_file", "export default { baseUrl: '/api' }"))),
    ToolRound("", listOf(grep), listOf(ToolResult("toolu_2", "vibe_run_command", "src/app.ts:3: baseUrl"))),
  )
  private val marker = ToolLoopCheckpoint.stoppedMarker(2)

  /** As the thread keeps it: the task, the client's stop line, the wordless turn with its rounds, then the request to continue */
  private val transcript = listOf(
    ChatMessageRecord(Role.USER, "выполни эту правку", at = at),
    ChatMessageRecord(Role.OTHER, "Ход остановлен после 2 раундов инструментов", at = at),
    ChatMessageRecord(Role.OTHER, "ход закончился без единого слова", at = at, wireText = marker, toolRounds = rounds),
    ChatMessageRecord(Role.USER, "продолжи", at = at),
  )

  private fun wire(): List<ChatMessage> = ToolRounds.expand(TranscriptWire.messages(transcript, emptySet()))

  @Test
  fun `the stopped turn's tool results travel into the next request`() {
    val wire = wire()
    assertEquals(listOf("user", "assistant", ToolCalls.ROLE, "assistant", ToolCalls.ROLE, "assistant", "user"), wire.map { it.role })
    assertEquals(listOf(read), wire[1].toolCalls)
    assertEquals("export default { baseUrl: '/api' }", wire[2].toolResults.single().text)
    assertEquals("src/app.ts:3: baseUrl", wire[4].toolResults.single().text)
    assertEquals(marker, wire[5].text, "the model is told the client stopped it, not that it had nothing to say")
    assertEquals("продолжи", wire.last().text)
  }

  @Test
  fun `the feed's own lines do not reach the model`() {
    val texts = TranscriptWire.messages(transcript, emptySet()).map { it.text }
    assertTrue(texts.none { "без единого слова" in it || "Ход остановлен" in it })
  }

  @Test
  fun `on the Anthropic wire the calls are tool_use blocks and the results one tool_result message`() {
    val wire = wire()
    val assistant = LlmMessages.anthropic(wire[1])
    val block = (assistant["content"] as JsonArray).single() as JsonObject
    assertEquals("tool_use", (block["type"] as JsonPrimitive).content)
    assertEquals("toolu_1", (block["id"] as JsonPrimitive).content)
    val results = LlmMessages.anthropic(wire[2])
    assertEquals("user", (results["role"] as JsonPrimitive).content)
    val result = (results["content"] as JsonArray).single() as JsonObject
    assertEquals("tool_result", (result["type"] as JsonPrimitive).content)
    assertEquals("toolu_1", (result["tool_use_id"] as JsonPrimitive).content)
  }

  @Test
  fun `on the OpenAI-compatible wire the calls ride the assistant message and each result is a tool message`() {
    val wire = wire()
    val assistant = LlmMessages.openAi(wire[1])
    assertEquals("toolu_1", (((assistant["tool_calls"] as JsonArray).single() as JsonObject)["id"] as JsonPrimitive).content)
    val result = ToolCalls.openAiResults(wire[2]).single()
    assertEquals("tool", (result["role"] as JsonPrimitive).content)
    assertEquals("toolu_1", (result["tool_call_id"] as JsonPrimitive).content)
    assertEquals("export default { baseUrl: '/api' }", (result["content"] as JsonPrimitive).content)
  }

  @Test
  fun `a wordless turn without a marker still closes with an answer`() {
    val plain = listOf(ChatMessageRecord(Role.OTHER, "ход закончился без единого слова", at = at, toolRounds = rounds))
    assertEquals(ToolRounds.NO_ANSWER, ToolRounds.expand(TranscriptWire.messages(plain, emptySet())).last().text)
  }

  @Test
  fun `a stopped answer with words keeps them and adds the marker after`() {
    val said = ChatMessageRecord(Role.ASSISTANT, "Сейчас поправлю.", at = at, wireText = "Сейчас поправлю.\n\n$marker", toolRounds = rounds)
    assertEquals("Сейчас поправлю.\n\n$marker", ToolRounds.expand(TranscriptWire.messages(listOf(said), emptySet())).last().text)
  }

  @Test
  fun `compaction counts the same records the wire carries`() {
    assertEquals(listOf(true, false, true, true), transcript.map(TranscriptWire::carried))
  }

  @Test
  fun `tools the thread loaded come back from its rounds`() {
    val found = transcript.mapIndexed { index, record ->
      if (index == 2) record.copyRounds(rounds.map { it.copy(addedTools = listOf("vibe_read_file", "vibe_run_command")) }) else record
    }
    assertEquals(setOf("vibe_read_file", "vibe_run_command"), TranscriptWire.loadedTools(found))
  }

  @Test
  fun `the wordless turn with its marker survives the thread file`() {
    val thread = ChatThread("t1", at, at, null, null, transcript)
    val back = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(thread))!!.messages
    assertEquals(rounds, back[2].toolRounds)
    assertEquals(marker, back[2].wireText)
  }

  private fun ChatMessageRecord.copyRounds(toolRounds: List<ToolRound>) =
    ChatMessageRecord(role, text, images, at, wireText, pinned, reasoning, toolRounds, responses, effortMark)
}
