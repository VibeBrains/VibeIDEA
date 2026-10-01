// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The files a call writes are the ones a step's boundary checks before the agent writes them itself */
class ToolWritesTest {
  @Test
  fun `the agent's own edit tool names its file in the input and the locations`() {
    val input = buildJsonObject { put("file_path", JsonPrimitive("/p/src/a.kt")) }
    assertEquals(listOf("/p/src/a.kt", "/p/src/b.kt"),
                 ToolWrites.writtenPaths("edit", "Edit", input, listOf("/p/src/a.kt", "/p/src/b.kt")))
  }

  @Test
  fun `a read or a command names no written file, even with locations`() {
    assertEquals(emptyList(), ToolWrites.writtenPaths("read", "Read", null, listOf("/p/.env")))
    assertEquals(emptyList(), ToolWrites.writtenPaths("execute", "Bash", null, listOf("/p/.env")))
  }

  @Test
  fun `a delete or a move is a write by kind alone`() {
    assertEquals(listOf("/p/old.kt"), ToolWrites.writtenPaths("delete", null, null, listOf("/p/old.kt")))
    assertEquals(listOf("/p/new.kt"), ToolWrites.writtenPaths("move", "mv", buildJsonObject { put("path", JsonPrimitive("/p/new.kt")) }, emptyList()))
  }
}
