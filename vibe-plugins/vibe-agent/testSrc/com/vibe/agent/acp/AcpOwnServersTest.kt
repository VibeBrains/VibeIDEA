// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals

/** The servers handed to an ACP agent: an own entry from `.vibe/mcp.json` replaces a found one of the same name. */
class AcpOwnServersTest {
  @Test
  fun `an own vibememory replaces the found one instead of joining it`() {
    val found = listOf(mapOf<String, Any>("name" to "vibe-ide"), mapOf<String, Any>("name" to "vibememory", "command" to "found"))
    val own = listOf(mapOf<String, Any>("name" to "vibememory", "command" to "own"), mapOf<String, Any>("name" to "db"))
    val servers = AcpClient.withOwnServers(found, own)
    assertEquals(listOf("vibe-ide", "vibememory", "db"), servers.map { it["name"] })
    assertEquals("own", servers.single { it["name"] == "vibememory" }["command"])
  }

  @Test
  fun `without own servers the found ones go as they are`() {
    val found = listOf(mapOf<String, Any>("name" to "vibememory"))
    assertEquals(found, AcpClient.withOwnServers(found, emptyList()))
  }
}
