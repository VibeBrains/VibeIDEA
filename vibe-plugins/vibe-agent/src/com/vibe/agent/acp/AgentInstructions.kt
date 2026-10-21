// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.context.ProjectRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the IDE tells an external agent about the project, and how often
 *
 * An agent like Claude Code reads CLAUDE.md, AGENTS.md and its user's own rules itself and keeps its own history
 * Rules prepended to every message were paid twice, then once more for every model call of the turn
 * Such an agent gets only what it cannot read on its own: the IDE's rule folders, where the notes live, the design
 * context, and gets it once per session
 *
 * Pure: rules and worded lines come in, text goes out, so the decisions are tested without a project
 */
object AgentInstructions {
  /**
   * Whether [config]'s agent reads the project's instruction files itself
   * The entry decides when it says so; otherwise the Claude adapter does and any other agent does not
   */
  fun readsInstructions(config: AgentServerConfig): Boolean = config.readsInstructions ?: AgentReadiness.usesClaude(config)

  /** A rule read from AGENTS.md: the agent reads that file itself, so the IDE does not repeat it */
  fun agentOwnFile(rule: ProjectRules.Rule): Boolean =
    rule.source == ProjectRules.AGENTS_FILE || rule.source.endsWith("/" + ProjectRules.AGENTS_FILE)

  /** The words of the block, from the catalogue: the model reads them in the language the person set */
  data class Words(
    val header: String,
    val always: String,
    val onDemand: String,
    val nested: String,
  )

  /** The session's instructions and the rules they carry, by name, for the line in the feed */
  data class Block(val text: String, val rules: List<String>)

  /**
   * The once-per-session instructions, or null when there is nothing the agent could not read itself
   *
   * Always-on rules go in full; the rest go as an index of paths, which is how the agent reads the notes too:
   * a rule that applies to PHP files costs nothing in a turn about CSS
   * [rules] are the project's root rules with bodies already passed through the context guard
   * [pointers] are worded lines naming where the notes live; [design] is the design context block, if any
   */
  fun block(rules: List<ProjectRules.Rule>, pointers: List<String>, design: String?, words: Words): Block? {
    val own = rules.filter { it.dir.isEmpty() && !agentOwnFile(it) }
    val always = own.filter { it.alwaysApply }
    val onDemand = own.filter { !it.alwaysApply }
    val notes = pointers.filter { it.isNotBlank() }
    val designText = design?.trim()?.takeIf { it.isNotEmpty() }
    if (always.isEmpty() && onDemand.isEmpty() && notes.isEmpty() && designText == null) return null
    val parts = buildList {
      add(words.header)
      if (always.isNotEmpty()) add(ProjectRules.promptBlock(always, words.always))
      if (onDemand.isNotEmpty()) add(words.onDemand + "\n" + onDemand.joinToString("\n") { indexLine(it) })
      if (own.isNotEmpty()) add(words.nested)
      if (notes.isNotEmpty()) add(notes.joinToString("\n") { "- $it" })
      designText?.let { add(it) }
    }
    return Block(parts.joinToString("\n\n"), own.map { it.name })
  }

  /** `- @name — description [globs] «trigger» → path`: what the rule is about, when it applies, where to read it */
  private fun indexLine(rule: ProjectRules.Rule): String = buildString {
    append("- @").append(rule.name)
    rule.description?.let { append(" — ").append(it) }
    if (rule.globs.isNotEmpty()) append(" [").append(rule.globs.joinToString(", ")).append("]")
    if (rule.triggers.isNotEmpty()) append(" ").append(rule.triggers.joinToString(", ") { "«$it»" })
    if (rule.source.isNotEmpty()) append(" → ").append(rule.source)
  }

  /**
   * What this prompt must carry: [current] when the session has not been given exactly that text, otherwise null
   * The session got it in its system prompt or in an earlier message; a project whose rules changed since gets the new
   * text once more, in the next message
   */
  fun due(current: String?, delivered: String?): String? = current?.takeIf { it != delivered }

  /** The ACP `systemPrompt` of `session/new` and `session/resume`: appended to the agent's own, never replacing it */
  fun systemPrompt(text: String): JsonObject = buildJsonObject {
    put("mode", "append")
    put("content", JsonArray(listOf(buildJsonObject {
      put("type", "text")
      put("text", text)
    })))
  }
}
