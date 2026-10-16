// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalEditCaptureTest {
  private val json = Json { ignoreUnknownKeys = true }
  private fun frame(s: String) = json.parseToJsonElement(s).jsonObject

  /** A disk that changes under the test: null — no file */
  private val disk = HashMap<String, String?>()
  private val capture = ExternalEditCapture { disk[it] }

  // Frames as Claude Code ACP sends them for its Edit and Write tools
  private val editAnnounce = """{"sessionUpdate":"tool_call","toolCallId":"toolu_1","title":"Edit /p/a.kt","kind":"edit","status":"pending",
    "content":[{"type":"diff","path":"/p/a.kt","oldText":"val x = 1","newText":"val x = 2"}],"locations":[{"path":"/p/a.kt"}],
    "rawInput":{"file_path":"/p/a.kt","old_string":"val x = 1","new_string":"val x = 2"}}"""
  private val writeAnnounce = """{"sessionUpdate":"tool_call","toolCallId":"toolu_2","title":"Write /p/new.kt","kind":"edit","status":"pending",
    "content":[{"type":"diff","path":"/p/new.kt","oldText":null,"newText":"fun a() {}\n"}],"locations":[{"path":"/p/new.kt"}]}"""

  @Test
  fun `registry parses diff entries and keeps them across a text-only update`() {
    val reg = ToolCallRegistry()
    val call = reg.onToolCall(frame(writeAnnounce))!!
    assertEquals(listOf(ToolDiff("/p/new.kt", null, "fun a() {}\n")), call.diffs)
    reg.onToolCallUpdate(frame("""{"sessionUpdate":"tool_call_update","toolCallId":"toolu_2","status":"completed","content":[{"type":"content","content":{"type":"text","text":"ok"}}]}"""))
    assertEquals(1, call.diffs.size)
  }

  @Test
  fun `a snapshot before the write is the baseline and the text after is read from the disk`() {
    disk["/p/a.kt"] = "fun f() {\n  val x = 1\n}\n"
    val call = ToolCallRegistry().onToolCall(frame(editAnnounce))!!
    capture.snapshot(listOf("/p/a.kt"))
    disk["/p/a.kt"] = "fun f() {\n  val x = 2\n}\n"
    val edits = capture.settle(listOf("/p/a.kt"), call.diffs)
    assertEquals(listOf(ExternalEditCapture.Edit("/p/a.kt", "fun f() {\n  val x = 1\n}\n", "fun f() {\n  val x = 2\n}\n")), edits)
  }

  @Test
  fun `a file absent at the snapshot is a created file`() {
    val call = ToolCallRegistry().onToolCall(frame(writeAnnounce))!!
    capture.snapshot(listOf("/p/new.kt"))
    disk["/p/new.kt"] = "fun a() {}\n"
    assertNull(capture.settle(listOf("/p/new.kt"), call.diffs).single().before)
  }

  @Test
  fun `the first snapshot wins and is spent by the settle`() {
    disk["/p/a.kt"] = "one"
    capture.snapshot(listOf("/p/a.kt"))
    disk["/p/a.kt"] = "two"
    capture.snapshot(listOf("/p/a.kt"))
    assertEquals("one", capture.settle(listOf("/p/a.kt"), emptyList()).single().before)
    capture.snapshot(listOf("/p/a.kt"))
    disk["/p/a.kt"] = "three"
    assertEquals("two", capture.settle(listOf("/p/a.kt"), emptyList()).single().before)
  }

  @Test
  fun `without a snapshot an edit fragment rebuilds the baseline`() {
    val call = ToolCallRegistry().onToolCall(frame(editAnnounce))!!
    disk["/p/a.kt"] = "fun f() {\n  val x = 2\n}\n"
    assertEquals("fun f() {\n  val x = 1\n}\n", capture.settle(listOf("/p/a.kt"), call.diffs).single().before)
  }

  @Test
  fun `without a snapshot a whole-file write has no known baseline and is skipped`() {
    val call = ToolCallRegistry().onToolCall(frame(writeAnnounce))!!
    disk["/p/new.kt"] = "fun a() {}\n"
    assertTrue(capture.settle(listOf("/p/new.kt"), call.diffs).isEmpty())
  }

  @Test
  fun `an unchanged or deleted file records nothing`() {
    disk["/p/a.kt"] = "same"
    capture.snapshot(listOf("/p/a.kt", "/p/gone.kt"))
    assertTrue(capture.settle(listOf("/p/a.kt", "/p/gone.kt"), emptyList()).isEmpty())
  }

  @Test
  fun `the permission preview lists removed and added lines and is capped`() {
    val text = ExternalEditCapture.previewText(listOf(ToolDiff("/p/a.kt", "val x = 1", "val x = 2")))
    assertEquals("/p/a.kt\n- val x = 1\n+ val x = 2", text)
    val long = ExternalEditCapture.previewText(listOf(ToolDiff("/p/b", null, "x".repeat(10_000))))
    assertTrue(long.length <= ExternalEditCapture.MAX_PREVIEW_CHARS)
  }
}
