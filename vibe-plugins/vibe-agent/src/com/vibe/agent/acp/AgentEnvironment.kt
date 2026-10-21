// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.acp

import com.vibe.agent.security.SecretRefs

/**
 * The agent's environment: the IDE's own, with the entry's `env` laid on top.
 *
 * An empty value REMOVES the variable. The seed advises `"ANTHROPIC_API_KEY": ""` so that the
 * subscription pays, and the spec promised the variable would be taken away — but the empty string
 * was passed on, leaving the variable SET. Whether the agent reads an empty key as absent is
 * documented nowhere (research 11.09.2026); removing it is the answer that does not depend on the
 * agent. A secret reference that resolves to nothing is removed the same way.
 */
object AgentEnvironment {
  /**
   * [lookup] gives a secret's value by name. A value with ANY reference the lookup cannot provide (absent or empty) is dropped
   * whole, the variable removed from [target]: a half-substituted string or the reference text itself would reach the agent
   * as a bogus credential instead of absence. [onUnresolved] gets the variable and the secret names, never a value.
   */
  fun apply(
    target: MutableMap<String, String>,
    entry: Map<String, String>,
    lookup: (String) -> String?,
    onUnresolved: (variable: String, secrets: List<String>) -> Unit = { _, _ -> },
  ) {
    for ((name, value) in entry) {
      val missing = SecretRefs.missing(value, lookup)
      if (missing.isNotEmpty()) {
        target.remove(name)
        onUnresolved(name, missing)
        continue
      }
      val resolved = SecretRefs.substitute(value, lookup)
      if (resolved.isEmpty()) target.remove(name) else target[name] = resolved
    }
  }

  /**
   * Variables a running Claude Code session sets for its own children
   *
   * An IDE started from such a session (a build run by an agent, a terminal of the Claude app) hands them on, and the
   * agent then takes itself for a nested session: VibeIDE saw `session/new` fail with them, and with
   * `CLAUDE_CODE_SIMPLE=1` the agent starts bare — no CLAUDE.md, no user rules, no sign-in (checked 21.10.2026:
   * «Authentication required»)
   * The list is VibeIDE's (`NESTED_SESSION_MARKERS`) plus the bare-mode switch
   */
  val FOREIGN_SESSION: List<String> = listOf(
    "CLAUDECODE",
    "CLAUDE_CODE_ENTRYPOINT",
    "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_HOST_SESSION_ID",
    "CLAUDE_CODE_CHILD_SESSION",
    "CLAUDE_CODE_MESSAGING_SOCKET",
    "CLAUDE_CODE_MESSAGING_TOKEN",
    "CLAUDE_CODE_EXECPATH",
    "CLAUDE_AGENT_SDK_VERSION",
    "CLAUDE_CODE_SIMPLE",
  )

  /**
   * Takes the marks of a foreign Claude Code session out of [target], so the agent starts as it does from a terminal
   * Called before the entry's `env` is applied: a variable the entry sets by hand is the person's final word
   */
  fun dropForeignSession(target: MutableMap<String, String>) {
    FOREIGN_SESSION.forEach { target.remove(it) }
  }
}
