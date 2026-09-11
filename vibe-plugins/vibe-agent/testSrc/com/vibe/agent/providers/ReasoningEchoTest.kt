// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.history.ChatMessageRecord
import com.vibe.agent.history.ChatThread
import com.vibe.agent.history.ChatTranscriptCodec
import com.vibe.agent.history.Role
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A model that requires its reasoning back gets it; every other wire never sees the field. */
class ReasoningEchoTest {
  private val answer = ChatMessage("assistant", "ответ", reasoning = "сперва подумаю")

  @Test
  fun `kimi-k3 asks for its reasoning back, router prefix included, other models do not`() {
    assertTrue(ModelQuirks.has("kimi-k3", ModelQuirks.Quirk.ECHO_REASONING))
    assertTrue(ModelQuirks.has("moonshotai/kimi-k3", ModelQuirks.Quirk.ECHO_REASONING))
    assertFalse(ModelQuirks.has("kimi-k2.6", ModelQuirks.Quirk.ECHO_REASONING))
    assertFalse(ModelQuirks.has("deepseek-flash", ModelQuirks.Quirk.ECHO_REASONING))
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
