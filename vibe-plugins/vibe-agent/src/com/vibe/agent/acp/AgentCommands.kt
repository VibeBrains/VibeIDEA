// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The agent's own slash commands, announced with `available_commands_update` (ACP v1 slash commands)
 *
 * A command runs by being the prompt's text: `/name arguments`. The list may come again at any time and replaces the
 * previous one — the agent learns its commands as it loads, and they differ per session
 */
data class AgentCommand(val name: String, val description: String, val hint: String?) {
  /** The command as typed: `/name` */
  val word: String get() = "/$name"

  companion object {
    /** The commands of an `available_commands_update`; an entry without a name is skipped, not the list */
    fun parse(update: JsonObject): List<AgentCommand> = (update["availableCommands"] as? JsonArray).orEmpty().mapNotNull { entry ->
      val o = entry as? JsonObject ?: return@mapNotNull null
      val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() }
                 ?: return@mapNotNull null
      val hint = ((o["input"] as? JsonObject)?.get("hint") as? JsonPrimitive)?.contentOrNull
      AgentCommand(name, (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), hint)
    }

    /**
     * The agent command [text] runs, or null when it is not one of [commands]
     * Only the whole first word counts: `/reviewed it` is a sentence, not `/review`
     */
    fun of(text: String, commands: List<AgentCommand>): AgentCommand? {
      val word = text.trim().substringBefore(' ').substringBefore('\n')
      return commands.firstOrNull { it.word == word }
    }
  }
}
