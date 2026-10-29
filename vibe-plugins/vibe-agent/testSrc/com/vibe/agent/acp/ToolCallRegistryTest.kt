// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
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
  fun `the end of a turn closes the calls the agent never finished`() {
    val reg = ToolCallRegistry()
    reg.onToolCall(frame("""{"toolCallId":"open","title":"Bash","status":"in_progress"}"""))
    reg.onToolCall(frame("""{"toolCallId":"done","title":"Read","status":"completed"}"""))
    reg.onToolCall(frame("""{"toolCallId":"bad","title":"Edit","status":"failed"}"""))

    assertEquals(listOf("open"), reg.settleOpen().map { it.id })
    assertEquals(ToolCall.STATUS_CANCELLED, reg["open"]?.status)
    assertTrue(reg["open"]!!.isDone)
    assertTrue(!reg["open"]!!.isOpen)
    // A reported end is a fact of the agent and is left as it was
    assertEquals(ToolCall.STATUS_COMPLETED, reg["done"]?.status)
    assertEquals(ToolCall.STATUS_FAILED, reg["bad"]?.status)
    // Nothing is left to settle on a second call, so the end of a turn cannot rewrite the record twice
    assertTrue(reg.settleOpen().isEmpty())
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

  @Test
  fun locationsParsedIntoPaths() {
    val reg = ToolCallRegistry()
    val call = reg.onToolCall(frame("""{"toolCallId":"t1","name":"Edit","status":"pending","locations":[{"path":"/a.kt","line":3},{"path":"/b.kt"}]}"""))!!
    assertEquals(listOf("/a.kt", "/b.kt"), call.locations)
    // A later update without locations keeps them.
    reg.onToolCallUpdate(frame("""{"toolCallId":"t1","status":"completed"}"""))
    assertEquals(listOf("/a.kt", "/b.kt"), reg["t1"]?.locations)
  }

  @Test
  fun updatesAreNormalisedAndCarryTheName() {
    val reg = ToolCallRegistry()
    reg.onToolCall(frame("""{"toolCallId":"t1","title":"Read","kind":"read"}"""))
    val call = reg.onToolCallUpdate(frame("""{"toolCallId":"t1","title":"Read\nfile\u202E","name":"Read"}"""))!!
    assertEquals("Read file", call.title)
    assertEquals("Read", call.toolName)
    // ACP v1: an update without the name keeps it
    reg.onToolCallUpdate(frame("""{"toolCallId":"t1","status":"completed"}"""))
    assertEquals("Read", reg["t1"]!!.toolName)
  }

  @Test
  fun theLabelAddsTheNameOnlyWhenTheTitleDoesNotSayIt() {
    assertEquals("Bash", ToolCall.label("Bash", "Bash"))
    assertEquals("Run `bash ls`", ToolCall.label("Run `bash ls`", "bash"))
    assertEquals("List files", ToolCall.label("List files", null))
    val labelled = ToolCall.label("List files", "Glob")
    assertTrue(labelled.contains("List files") && labelled.contains("Glob") && labelled != "List files", labelled)
  }
}
