// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * Which quirk catalogue applies, per project.
 *
 * The first version of this was one global variable, and it was wrong for the same reason plans on
 * disk once were: two projects open at once, each with its own `.vibe/modelQuirks.json`, and
 * whichever loaded last decided how BOTH of them talked to their models. The failure is quiet —
 * requests keep working, one project's temperature simply disappears — which is the worst kind.
 *
 * The key is the project's base path; the global `~/.vibe` file is already merged into each
 * project's list by [ProvidersService.loadQuirks], so a lookup never needs two answers.
 */
object ModelQuirksRegistry {
  /** Rules for work outside any project — the settings pages and the catalogue probe. */
  private const val NO_PROJECT = "<global>"

  private val byProject = ConcurrentHashMap<String, List<ModelQuirks.Rule>>()

  fun install(projectBase: String?, rules: List<ModelQuirks.Rule>) {
    byProject[projectBase ?: NO_PROJECT] = rules
  }

  fun rulesFor(projectBase: String?): List<ModelQuirks.Rule> =
    byProject[projectBase ?: NO_PROJECT] ?: emptyList()

  /** A closed project must not keep deciding how models are called. */
  fun forget(projectBase: String?) {
    byProject.remove(projectBase ?: NO_PROJECT)
  }
}
