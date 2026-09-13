// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.skills

import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import com.vibe.agent.providers.ProvidersService

/**
 * The model a skill eval runs on — one answer for the menu action and for the command line.
 *
 * Two copies of «which model» would disagree the day a default changes, and a number from CI would
 * then describe a different model than the one the person ran by hand.
 */
object SkillEvalTarget {
  /** The model marked `default`, otherwise the first active one; null when nothing is configured. */
  fun defaultModel(projectBase: String?): Pair<ProviderEntry, ModelEntry>? {
    val pairs = ProvidersService.load(projectBase) { }.flatMap { provider ->
      provider.models.filter { it.active }.map { provider to it }
    }
    return pairs.firstOrNull { it.second.default } ?: pairs.firstOrNull()
  }
}
