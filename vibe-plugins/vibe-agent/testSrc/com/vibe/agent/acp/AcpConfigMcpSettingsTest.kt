// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `default_mcp_settings.use_custom_mcp` of `~/.jetbrains/acp.json`: the person's word, or nothing said. */
class AcpConfigMcpSettingsTest {
  @Test
  fun `false and true are read from the block`() {
    assertEquals(false, AcpConfig.useCustomMcp("""{ "agent_servers": {}, "default_mcp_settings": { "use_custom_mcp": false } }"""))
    assertEquals(true, AcpConfig.useCustomMcp("""{ "default_mcp_settings": { "use_custom_mcp": true, "use_idea_mcp": false } }"""))
  }

  @Test
  fun `no file, no block, no field or a broken file say nothing`() {
    assertNull(AcpConfig.useCustomMcp(null))
    assertNull(AcpConfig.useCustomMcp("""{ "agent_servers": {} }"""))
    assertNull(AcpConfig.useCustomMcp("""{ "default_mcp_settings": { "use_idea_mcp": true } }"""))
    assertNull(AcpConfig.useCustomMcp("{ not json"))
  }
}
