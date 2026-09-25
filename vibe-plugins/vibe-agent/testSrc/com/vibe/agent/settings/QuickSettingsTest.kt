// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.settings.QuickSettings.Item
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The quick-settings menu is a second door to the same settings: it shows what is stored and writes only what it offers
 */
class QuickSettingsTest {
  private class MapStore(
    override var terseMode: String = "full",
    override var reasoningLevel: String = "off",
    override var offline: Boolean = false,
    override var minimalismMode: String = "off",
  ) : QuickSettings.Store

  @Test
  fun `the menu lists the reply style first and checks what is stored`() {
    val items = QuickSettings.items(MapStore(terseMode = "ultra", reasoningLevel = "high", offline = true, minimalismMode = "light"))
    assertEquals(listOf(QuickSettings.TERSE, QuickSettings.REASONING, QuickSettings.OFFLINE, QuickSettings.MINIMALISM),
                 items.map { it.id })
    assertEquals(Item.Choice(QuickSettings.TERSE, listOf("off", "lite", "full", "ultra"), "ultra"), items[0])
    assertEquals(Item.Choice(QuickSettings.REASONING, QuickSettings.REASONING_LEVELS, "high"), items[1])
    assertEquals(Item.Switch(QuickSettings.OFFLINE, true), items[2])
    assertEquals(Item.Choice(QuickSettings.MINIMALISM, listOf("off", "light", "full", "ultra"), "light"), items[3])
  }

  @Test
  fun `a stored value outside the list shows as the value it acts as`() {
    val items = QuickSettings.items(MapStore(terseMode = "loud", reasoningLevel = "extreme", minimalismMode = "tiny"))
    assertEquals(listOf("full", "off", "off"), items.filterIsInstance<Item.Choice>().map { it.selected })
  }

  @Test
  fun `choosing writes the setting, and a value the menu does not offer is refused`() {
    val store = MapStore()
    QuickSettings.choose(store, QuickSettings.TERSE, "lite")
    QuickSettings.choose(store, QuickSettings.REASONING, "medium")
    QuickSettings.choose(store, QuickSettings.MINIMALISM, "ultra")
    QuickSettings.choose(store, QuickSettings.TERSE, "loud")
    QuickSettings.flip(store, QuickSettings.OFFLINE)
    assertEquals(listOf("lite", "medium", "ultra"), listOf(store.terseMode, store.reasoningLevel, store.minimalismMode))
    assertEquals(true, store.offline)
  }
}
