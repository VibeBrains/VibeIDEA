// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.minimalism

/**
 * How much code the agent is allowed to add for a given result.
 *
 * Left alone, a model writes more than the task needs: a wrapper around one call, a flag nobody
 * sets, a comment restating the line below it, an abstraction for a second case that does not
 * exist. Each addition is defensible on its own, and together they are the reason a codebase
 * written with an agent becomes unreadable faster than one written by hand.
 *
 * The modes are a ladder rather than a switch, because the right amount differs by task: a
 * prototype and a payment path do not want the same discipline.
 */
object MinimalismPolicy {
  enum class Mode { OFF, LIGHT, FULL, ULTRA }

  fun modeOf(name: String?): Mode = when (name?.trim()?.lowercase()) {
    "light", "лайт" -> Mode.LIGHT
    "full", "фулл" -> Mode.FULL
    "ultra", "ультра" -> Mode.ULTRA
    else -> Mode.OFF
  }

  /**
   * The rules added to the prompt. [rules] comes from the catalogue so the discipline is stated in
   * the user's language — a rule the model reads in one language and the human in another is a rule
   * nobody can check.
   */
  fun preamble(mode: Mode, rules: Rules): String = when (mode) {
    Mode.OFF -> ""
    Mode.LIGHT -> rules.light
    Mode.FULL -> rules.light + "\n" + rules.full
    Mode.ULTRA -> rules.light + "\n" + rules.full + "\n" + rules.ultra
  }

  /**
   * What an agent session must receive before this prompt, given the mode it was last given ([sent], null — nothing yet)
   * An agent keeps its own history, so the rules go once and again only when the mode changes, as the terse style does
   * Switched off after being sent, the session hears [off]; null — nothing to send
   */
  fun forAgent(sent: Mode?, current: Mode, rules: Rules, off: String): String? = when {
    current == (sent ?: Mode.OFF) -> null
    current == Mode.OFF -> off
    else -> preamble(current, rules)
  }

  data class Rules(val light: String, val full: String, val ultra: String)
}
