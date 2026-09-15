// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The remembered agent session survives the rename of the agent it belongs to. */
class AcpSessionMemoryTest {
  private val store = HashMap<String, String>()

  private fun recall(name: String, project: String? = "/work/app") =
    AcpSessionMemory.recall(name, project, store::get, { k, v -> store[k] = v }, { store.remove(it) })

  @Test
  fun `a session remembered as Claude Code is found as Claude Agent and moved once`() {
    store[AcpSessionMemory.key("Claude Code", "/work/app")] = "sess-1"
    assertEquals("sess-1", recall("Claude Agent"))
    assertEquals("sess-1", store[AcpSessionMemory.key("Claude Agent", "/work/app")])
    assertNull(store[AcpSessionMemory.key("Claude Code", "/work/app")], "the old key is gone after the move")
  }

  @Test
  fun `the new name wins over a leftover old one`() {
    store[AcpSessionMemory.key("Claude Code", "/work/app")] = "old"
    store[AcpSessionMemory.key("Claude Agent", "/work/app")] = "new"
    assertEquals("new", recall("Claude Agent"))
  }

  @Test
  fun `another project and an agent without a rename find nothing`() {
    store[AcpSessionMemory.key("Claude Code", "/work/other")] = "sess-2"
    assertNull(recall("Claude Agent"))
    assertNull(recall("Codex"))
    assertTrue(store.containsKey(AcpSessionMemory.key("Claude Code", "/work/other")))
  }

  @Test
  fun `the key did not change when the separator became an escape`() {
    // The hash the IDE stored before: project, a NUL character, the name.
    val before = "vibe.acp.session." + ("/work/app" + 0.toChar() + "Claude Code").hashCode().toString(16)
    assertEquals(before, AcpSessionMemory.key("Claude Code", "/work/app"))
  }
}
