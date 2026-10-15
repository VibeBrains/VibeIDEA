// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The composer row reads: the agent, the permissions, the quick settings, the pipeline, then the microphone
 * Each pill is named by its text, so the order is read off the list the composer gets
 */
class ComposerPillOrderTest {
  private fun pill(name: String) = JLabel(name)

  private fun order(voice: JLabel?): List<String> = composerPillOrder(
    mode = pill("mode"),
    config = pill("config"),
    agent = pill("agent"),
    permissions = pill("permissions"),
    quickSettings = pill("quickSettings"),
    pipeline = pill("pipeline"),
    voice = voice,
  ).map { (it as JLabel).text }

  @Test
  fun `the agent comes first, then permissions, quick settings and the pipeline`() {
    assertEquals(
      listOf("mode", "config", "agent", "permissions", "quickSettings", "pipeline", "voice"),
      order(pill("voice")),
    )
  }

  @Test
  fun `without a microphone the row has no gap where it would be`() {
    assertEquals(listOf("mode", "config", "agent", "permissions", "quickSettings", "pipeline"), order(null))
  }
}
