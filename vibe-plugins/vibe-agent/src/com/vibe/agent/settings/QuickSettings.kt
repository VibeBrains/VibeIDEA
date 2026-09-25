// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.minimalism.MinimalismPolicy
import com.vibe.agent.terse.TerseReplies

/**
 * The composer's quick-settings menu: the settings people change while working, next to the message they are writing
 *
 * A second door to the same settings, not a copy of them: every item reads and writes the one value the settings
 * page shows, so the two can never disagree
 * Pure: the values come through [Store], so what the menu offers and what a click writes is testable without the IDE
 */
object QuickSettings {
  /** The settings the menu touches; [VibeAgentSettings] in the IDE, a map in a test */
  interface Store {
    var terseMode: String
    var reasoningLevel: String
    var offline: Boolean
    var minimalismMode: String
  }

  /** One line of the menu: a choice opens its values with the current one checked, a switch flips on click */
  sealed interface Item {
    val id: String

    data class Choice(override val id: String, val options: List<String>, val selected: String) : Item
    data class Switch(override val id: String, val on: Boolean) : Item
  }

  const val TERSE = "terse"
  const val REASONING = "reasoning"
  const val OFFLINE = "offline"
  const val MINIMALISM = "minimalism"

  /** The values of the reasoning setting, the same the settings page offers */
  val REASONING_LEVELS: List<String> = listOf("off", "low", "medium", "high")

  private val MINIMALISM_MODES: List<String> = MinimalismPolicy.Mode.entries.map { it.name.lowercase() }

  /**
   * The menu, in order: the reply style first — it is what the menu was made for — then reasoning, offline, minimalism
   * A stored value outside the list shows as the value it acts as, so exactly one option is always checked
   */
  fun items(store: Store): List<Item> = listOf(
    Item.Choice(TERSE, TerseReplies.Level.entries.map { it.id }, TerseReplies.Level.of(store.terseMode).id),
    Item.Choice(REASONING, REASONING_LEVELS, store.reasoningLevel.takeIf { it in REASONING_LEVELS } ?: REASONING_LEVELS.first()),
    Item.Switch(OFFLINE, store.offline),
    Item.Choice(MINIMALISM, MINIMALISM_MODES, MinimalismPolicy.modeOf(store.minimalismMode).name.lowercase()),
  )

  /** Writes [value] of the item [id]; a value the item does not offer is refused, so a stale menu cannot store garbage */
  fun choose(store: Store, id: String, value: String) {
    val item = items(store).firstOrNull { it.id == id } as? Item.Choice ?: return
    if (value !in item.options) return
    when (id) {
      TERSE -> store.terseMode = value
      REASONING -> store.reasoningLevel = value
      MINIMALISM -> store.minimalismMode = value
    }
  }

  /** Flips the switch [id] */
  fun flip(store: Store, id: String) {
    if (id == OFFLINE) store.offline = !store.offline
  }

  /** The IDE's settings behind the menu */
  object Ide : Store {
    override var terseMode: String by VibeAgentSettings::terseMode
    override var reasoningLevel: String by VibeAgentSettings::reasoningLevel
    override var offline: Boolean by VibeAgentSettings::offline
    override var minimalismMode: String by VibeAgentSettings::minimalismMode
  }
}
