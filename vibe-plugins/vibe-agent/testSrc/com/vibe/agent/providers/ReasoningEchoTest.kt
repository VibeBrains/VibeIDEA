// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.ChatTranscriptCodec
import com.vibe.agent.history.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A model that requires its reasoning back gets it; every other wire never sees the field. */
class ReasoningEchoTest {
  private val answer = ChatMessage("assistant", "ответ", reasoning = "сперва подумаю")

  @Test
  fun `reasoning Kimi models ask for their reasoning back, router prefix included, other models do not`() {
    for (id in listOf("kimi-k3", "moonshotai/kimi-k3", "kimi-k2.6", "kimi-k2.7-code", "kimi-k2.7-code-highspeed",
                      "kimi-for-coding", "kimi-for-coding-highspeed", "k3", "k3-256k")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.ECHO_REASONING), id)
    }
    // Kimi's own reference names K2.6 and K2.7; an older K2 and look-alike ids stay out.
    for (id in listOf("kimi-k2.5", "k30", "gpt-6-astra")) assertFalse(ModelQuirks.has(id, ModelQuirks.Quirk.ECHO_REASONING), id)
    // DeepSeek puts the same requirement and ties it to tools just as Kimi does: «with `tools`, the
    // `reasoning_content` of all previous turns should be passed back»
    // (api-docs.deepseek.com/guides/thinking_mode, checked 18.09.2026). It used to sit in the list
    // above as a stand-in for «some other model», which was a stand-in and not a decision.
    for (id in listOf("deepseek-flash", "deepseek-v4-pro", "deepseek-anthropic")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.ECHO_REASONING), id)
    }
  }

  @Test
  fun `MiniMax gets its reasoning back inside the content, as the tags it streamed`() {
    for (id in listOf("MiniMax-M3", "minimax-m2.7", "minimax/minimax-m2.5")) {
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.ECHO_REASONING), id)
      assertTrue(ModelQuirks.has(id, ModelQuirks.Quirk.REASONING_AS_THINK_TAGS), id)
    }
    val wire = LlmMessages.openAi(answer, echoReasoning = true, thinkTags = true)
    assertEquals("<think>\nсперва подумаю\n</think>\n\nответ", wire["content"]!!.jsonPrimitive.content)
    assertNull(wire["reasoning_content"])
    // An answer that only called tools still carries its reasoning: the content is the tags, not null
    val call = ChatMessage("assistant", "", reasoning = "нужен файл", toolCalls = listOf(ToolCall("t1", "read", "{}")))
    assertEquals("<think>\nнужен файл\n</think>\n\n", LlmMessages.openAi(call, echoReasoning = true, thinkTags = true)["content"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the assistant's reasoning goes out as reasoning_content only when echoing`() {
    assertEquals(JsonPrimitive("сперва подумаю"), LlmMessages.openAi(answer, echoReasoning = true)["reasoning_content"])
    assertNull(LlmMessages.openAi(answer)["reasoning_content"])
    // A user message never carries it, whatever the flag says.
    assertNull(LlmMessages.openAi(ChatMessage("user", "вопрос", reasoning = "x"), echoReasoning = true)["reasoning_content"])
  }

  @Test
  fun `the reasoning survives the history file, and a record without it stays without it`() {
    val thread = ChatThread("t", "2026-09-11T00:00:00Z", "2026-09-11T00:00:00Z", null, null, listOf(
      ChatMessageRecord(Role.USER, "вопрос", at = "2026-09-11T00:00:00Z"),
      ChatMessageRecord(Role.ASSISTANT, "ответ", at = "2026-09-11T00:00:01Z", reasoning = "сперва подумаю"),
    ))
    val back = ChatTranscriptCodec.fromJson(ChatTranscriptCodec.toJson(thread))!!
    assertNull(back.messages[0].reasoning)
    assertEquals("сперва подумаю", back.messages[1].reasoning)
    assertEquals("сперва подумаю", back.messages[1].withPinned(true).reasoning, "закрепление не теряет рассуждение")
  }
}
