// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

/**
 * Finds `/skill:<id>` mentions in a message.
 *
 * Until this existed the token was just text: the composer inserted `/skill:grill `, the model
 * received the literal string and never saw a line of the recipe. A skill that is seeded, listed in
 * a popup and does nothing is worse than no skill at all, because it looks like it worked.
 */
object SkillExpansion {
  /** At the start of the message or after whitespace — the same rule the popup uses to open. */
  private val TOKEN = Regex("(?<=^|\\s)/skill:([a-zA-Z0-9_-]+)")

  /** Ids in order of first appearance, without duplicates. */
  fun mentioned(text: String): List<String> =
    TOKEN.findAll(text).map { it.groupValues[1] }.distinct().toList()

  /** Wrapper for the direct-LLM path; ACP gets the same body as a typed `resource` block. */
  fun wrap(id: String, body: String): String = "<skill ref=\"$id\">\n$body\n</skill>"
}
