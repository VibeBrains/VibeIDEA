// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The remembered agent session belongs to a thread, and one kept the old ways is found by exactly one thread. */
class AcpSessionMemoryTest {
  private val store = HashMap<String, String>()

  private fun recall(name: String, thread: String, project: String? = "/work/app") =
    AcpSessionMemory.recall(name, thread, project, store::get, { k, v -> store[k] = v }, { store.remove(it) })

  @Test
  fun `two threads keep two sessions of the same agent`() {
    store[AcpSessionMemory.key("Codex", "t-1")] = "sess-1"
    store[AcpSessionMemory.key("Codex", "t-2")] = "sess-2"
    assertEquals("sess-1", recall("Codex", "t-1"))
    assertEquals("sess-2", recall("Codex", "t-2"))
    assertNull(recall("Codex", "t-3"))
  }

  @Test
  fun `a per-folder session moves to the first thread that asks and to no other`() {
    store[AcpSessionMemory.legacyKey("Codex", "/work/app")] = "sess-1"
    assertEquals("sess-1", recall("Codex", "t-1"))
    assertNull(recall("Codex", "t-2"), "the second thread opens its own")
    assertEquals("sess-1", recall("Codex", "t-1"))
    assertNull(store[AcpSessionMemory.legacyKey("Codex", "/work/app")])
  }

  @Test
  fun `a session remembered as Claude Code is found as Claude Agent and moved once`() {
    store[AcpSessionMemory.legacyKey("Claude Code", "/work/app")] = "sess-1"
    assertEquals("sess-1", recall("Claude Agent", "t-1"))
    assertEquals("sess-1", store[AcpSessionMemory.key("Claude Agent", "t-1")])
    assertNull(store[AcpSessionMemory.legacyKey("Claude Code", "/work/app")], "the old key is gone after the move")
  }

  @Test
  fun `the new name wins over a leftover old one`() {
    store[AcpSessionMemory.legacyKey("Claude Code", "/work/app")] = "old"
    store[AcpSessionMemory.legacyKey("Claude Agent", "/work/app")] = "new"
    assertEquals("new", recall("Claude Agent", "t-1"))
  }

  @Test
  fun `another project and an agent without a rename find nothing`() {
    store[AcpSessionMemory.legacyKey("Claude Code", "/work/other")] = "sess-2"
    assertNull(recall("Claude Agent", "t-1"))
    assertNull(recall("Codex", "t-1"))
    assertTrue(store.containsKey(AcpSessionMemory.legacyKey("Claude Code", "/work/other")))
  }

  @Test
  fun `the per-folder key is still the one the IDE stored`() {
    // The hash the IDE stored before: project, a NUL character, the name.
    val before = "vibe.acp.session." + ("/work/app" + 0.toChar() + "Claude Code").hashCode().toString(16)
    assertEquals(before, AcpSessionMemory.legacyKey("Claude Code", "/work/app"))
    assertNotEquals(before, AcpSessionMemory.key("Claude Code", "/work/app"), "a thread key never reads as a folder key")
  }
}
