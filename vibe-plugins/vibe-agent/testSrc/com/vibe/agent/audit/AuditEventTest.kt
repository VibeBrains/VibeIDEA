// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.audit

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditEventTest {
  @Test
  fun minimalRecordOmitsEmptyFields() {
    val json = AuditEvent(ts = 42L, action = AuditEvent.Action.PROMPT, ok = true).toJson()
    assertEquals(42L, json.getValue("ts").jsonPrimitive.content.toLong())
    assertEquals("prompt", json.getValue("action").jsonPrimitive.content)
    assertTrue(json.getValue("ok").jsonPrimitive.content.toBoolean())
    assertNull(json["files"])
    assertNull(json["meta"])
    assertNull(json["model"])
  }

  @Test
  fun fullRecordSerializes() {
    val json = AuditEvent(
      ts = 1L, action = AuditEvent.Action.TOOL_CALL_DONE, ok = false,
      files = listOf("/a/b.kt"), model = "acp/claude", latencyMs = 120L,
      meta = mapOf("tool" to "edit_file", "status" to "failed"),
    ).toJson()
    assertEquals("/a/b.kt", json.getValue("files").jsonArray[0].jsonPrimitive.content)
    assertEquals("acp/claude", json.getValue("model").jsonPrimitive.content)
    assertEquals(120L, json.getValue("latencyMs").jsonPrimitive.content.toLong())
    assertEquals("edit_file", json.getValue("meta").jsonObject.getValue("tool").jsonPrimitive.content)
    assertFalse(json.getValue("ok").jsonPrimitive.content.toBoolean())
  }

  @Test
  fun emptyFilesListOmitted() {
    val json = AuditEvent(ts = 1L, action = "x", ok = true, files = emptyList()).toJson()
    assertNull(json["files"])
  }
}
