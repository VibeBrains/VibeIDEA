// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.safety

import com.vibe.agent.acp.ToolCallRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopAnnounceTest {
  private fun frame(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  /** Replays the frames the way the panel does: announce, then updates, noting the call after each. */
  private fun replay(history: LoopDetector.History, registry: ToolCallRegistry, frames: List<Pair<Boolean, String>>) {
    for ((announce, json) in frames) {
      val u = frame(json)
      val call = (if (announce) registry.onToolCall(u) else registry.onToolCallUpdate(u)) ?: continue
      history.noteCall(call.id, call.toolName ?: call.kind, call.rawInput?.toString())
    }
  }

  @Test
  fun `four different writes announced with empty input are not a repeat`() {
    val history = LoopDetector.History()
    val registry = ToolCallRegistry()
    val frames = (1..4).flatMap { i ->
      listOf(
        true to """{"toolCallId":"t$i","title":"Preparing file","kind":"edit","name":"write","rawInput":{}}""",
        false to """{"toolCallId":"t$i","rawInput":{"file_path":"/p/f$i.txt","content":"c$i"}}""",
        false to """{"toolCallId":"t$i","status":"completed"}""",
      )
    }
    replay(history, registry, frames)
    assertEquals(4, history.size())
    assertEquals(LoopDetector.Verdict.OK, LoopDetector.check(history.snapshot()).verdict)
  }

  @Test
  fun `a call is recorded once however many updates carry its input`() {
    val history = LoopDetector.History()
    assertTrue(history.noteCall("a", "write", """{"p":1}"""))
    assertFalse(history.noteCall("a", "write", """{"p":1}"""))
    assertEquals(1, history.size())
  }

  @Test
  fun `empty input is never recorded`() {
    val history = LoopDetector.History()
    for (empty in listOf(null, "", "  ", "{}", "{ }", "null")) assertFalse(history.noteCall("a", "write", empty))
    assertEquals(0, history.size())
  }

  @Test
  fun `the same real arguments four times still trip the guard`() {
    val history = LoopDetector.History()
    val registry = ToolCallRegistry()
    val frames = (1..4).flatMap { i ->
      listOf(
        true to """{"toolCallId":"t$i","title":"Preparing file","kind":"edit","name":"write","rawInput":{}}""",
        false to """{"toolCallId":"t$i","rawInput":{"file_path":"/p/same.txt","content":"x"}}""",
      )
    }
    replay(history, registry, frames)
    assertEquals(LoopDetector.Verdict.REPEAT, LoopDetector.check(history.snapshot()).verdict)
  }
}
