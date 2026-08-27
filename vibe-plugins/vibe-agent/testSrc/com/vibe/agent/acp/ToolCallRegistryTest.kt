// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallRegistryTest {
  private val json = Json { ignoreUnknownKeys = true }
  private fun frame(s: String) = json.parseToJsonElement(s).jsonObject

  @Test
  fun toolCallCreatesEntry() {
    val reg = ToolCallRegistry()
    val call = reg.onToolCall(frame("""{"toolCallId":"t1","title":"Bash","kind":"execute","status":"pending","name":"Bash","rawInput":{"command":"ls"}}"""))!!
    assertEquals("t1", call.id)
    assertEquals("Bash", call.toolName)
    assertEquals("execute", call.kind)
    assertTrue(call.isRunning)
    assertEquals("ls", call.rawParamsFlat()["command"])
  }

  @Test
  fun updateMovesToCompleted() {
    val reg = ToolCallRegistry()
    reg.onToolCall(frame("""{"toolCallId":"t1","title":"Bash","status":"in_progress","name":"Bash"}"""))
    val call = reg.onToolCallUpdate(frame("""{"toolCallId":"t1","status":"completed"}"""))!!
    assertTrue(call.isDone)
    assertEquals(ToolCall.STATUS_COMPLETED, call.status)
    // Title/name survive an update that omits them.
    assertEquals("Bash", call.toolName)
  }

  @Test
  fun updateBeforeToolCallCreatesLazily() {
    val reg = ToolCallRegistry()
    val call = reg.onToolCallUpdate(frame("""{"toolCallId":"t9","status":"failed"}"""))!!
    assertEquals("t9", call.id)
    assertEquals(ToolCall.STATUS_FAILED, call.status)
  }

  @Test
  fun terminalIdCapturedFromMeta() {
    val reg = ToolCallRegistry()
    reg.onToolCall(frame("""{"toolCallId":"t1","name":"Bash","status":"pending","_meta":{"terminal_info":{"terminal_id":"term-7"}}}"""))
    assertEquals("term-7", reg["t1"]?.terminalId)
    // A later output frame keeps the same terminal id.
    reg.onToolCallUpdate(frame("""{"toolCallId":"t1","status":"in_progress","_meta":{"terminal_output":{"terminal_id":"term-7","data":"hi"}}}"""))
    assertEquals("term-7", reg["t1"]?.terminalId)
  }

  @Test
  fun resetClears() {
    val reg = ToolCallRegistry()
    reg.onToolCall(frame("""{"toolCallId":"t1","name":"Bash","status":"pending"}"""))
    reg.reset()
    assertNull(reg["t1"])
    assertTrue(reg.snapshot().isEmpty())
  }

  @Test
  fun missingIdIgnored() {
    val reg = ToolCallRegistry()
    assertNull(reg.onToolCall(frame("""{"title":"x"}""")))
  }
}
