// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.vibe.agent.i18n.VibeI18n.t

/**
 * The line that tells a person a server changed its tools after approval.
 *
 * Kept apart from where it is shown because the phrase is built from three optional parts and has to be testable:
 * a warning that does not say WHAT changed gets dismissed unread.
 */
object DriftMessage {
  /** How many names to list: twenty tool names is a wall, not a message. */
  const val NAMES_SHOWN = 3

  fun of(server: String, drift: ToolFingerprint.Drift): String {
    val parts = buildList {
      if (drift.changed.isNotEmpty()) add(t("mcp.servers.driftChanged", "names" to names(drift.changed)))
      if (drift.added.isNotEmpty()) add(t("mcp.servers.driftAdded", "names" to names(drift.added)))
      if (drift.removed.isNotEmpty()) add(t("mcp.servers.driftRemoved", "names" to names(drift.removed)))
    }
    return t("mcp.servers.drift", "server" to server, "what" to parts.joinToString("; "))
  }

  /** Changed tools come first: a swapped description is the attack itself, a changed membership is not. */
  private fun names(all: List<String>): String {
    val shown = all.take(NAMES_SHOWN).joinToString(", ")
    return if (all.size <= NAMES_SHOWN) shown else "$shown … (+${all.size - NAMES_SHOWN})"
  }
}
