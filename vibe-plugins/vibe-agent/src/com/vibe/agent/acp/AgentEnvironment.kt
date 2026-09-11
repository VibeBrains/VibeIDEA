// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

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
  /** [resolve] turns a configured value into the real one — `${secret:NAME}` references included. */
  fun apply(target: MutableMap<String, String>, entry: Map<String, String>, resolve: (String) -> String) {
    for ((name, value) in entry) {
      val resolved = resolve(value)
      if (resolved.isEmpty()) target.remove(name) else target[name] = resolved
    }
  }
}
