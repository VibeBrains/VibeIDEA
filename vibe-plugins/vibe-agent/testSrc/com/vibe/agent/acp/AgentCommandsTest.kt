// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The agent's own slash commands reach the menu and run only as the whole first word of the prompt */
class AgentCommandsTest {
  private val update = Json.parseToJsonElement("""
    {"sessionUpdate": "available_commands_update", "availableCommands": [
      {"name": "review", "description": "Review the changes", "input": {"hint": "what to look at"}},
      {"name": "/compact", "description": "Compact the conversation"},
      {"description": "no name"}
    ]}
  """).jsonObject

  @Test
  fun `the list is read with hints, a leading slash dropped and a nameless entry skipped`() {
    assertEquals(
      listOf(AgentCommand("review", "Review the changes", "what to look at"), AgentCommand("compact", "Compact the conversation", null)),
      AgentCommand.parse(update))
  }

  @Test
  fun `only the whole first word runs a command`() {
    val commands = AgentCommand.parse(update)
    assertEquals("review", AgentCommand.of("/review src/a.kt", commands)?.name)
    assertEquals("compact", AgentCommand.of("  /compact", commands)?.name)
    assertNull(AgentCommand.of("/reviewed it already", commands))
    assertNull(AgentCommand.of("please /review", commands))
  }
}
