// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

/**
 * Credentials an ACP agent was handed in `session/new`, kept out of what it prints
 *
 * The HTTP servers we offer carry their token in a header, in the clear: the agent needs it to connect. Its stderr
 * goes to the chat, and an agent that logs its MCP configuration would print the token there — so every value we
 * gave is masked in that stream, on top of the known shapes of credentials ([com.vibe.agent.security.SecretPatterns])
 */
object AgentSecrets {
  /** The values of every `headers` pair of the offered HTTP servers; a bearer token alone as well as its whole header */
  fun headerValues(servers: List<Map<String, Any>>): Set<String> =
    servers.flatMap { server ->
      (server["headers"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("value") as? String }
    }.flatMap { value -> listOfNotNull(value, value.substringAfter("Bearer ", "").takeIf { it.isNotEmpty() }) }
      .filter { it.length >= MIN_SECRET_LENGTH }.toSet()

  /** [text] with each of [values] replaced; the longest first, so a header is not half-masked by its own token */
  fun maskValues(text: String, values: Collection<String>): String =
    values.sortedByDescending { it.length }.fold(text) { acc, value -> acc.replace(value, MASK) }

  /** Shorter values are not secrets but words, and masking them would eat ordinary text */
  private const val MIN_SECRET_LENGTH = 12
  const val MASK = "***"
}
