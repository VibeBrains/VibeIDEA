// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp

import com.vibe.agent.node.NodeSettings
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The agent module opens the Node interpreter's page by an id, because the page lives here and this module depends on that one.
 * The id and the registration must stay together, or the "Open Node settings" button under a missing-npx notice opens nothing.
 */
class NodeSettingsPageTest {
  @Test
  fun `the page the agent opens for the Node interpreter is the one plugin xml registers`() {
    val descriptor = NodeSettingsPageTest::class.java.classLoader.getResources("META-INF/plugin.xml").toList()
      .map { it.readText() }.single { it.contains("<id>com.vibe.lsp</id>") }
    assertTrue(
      descriptor.contains("id=\"${NodeSettings.PAGE_ID}\" instance=\"${VibeLspConfigurable::class.java.name}\""),
      "plugin.xml does not register ${VibeLspConfigurable::class.java.name} under ${NodeSettings.PAGE_ID}",
    )
  }
}
