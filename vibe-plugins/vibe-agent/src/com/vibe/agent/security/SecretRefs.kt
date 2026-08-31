// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

/**
 * `${secret:NAME}` — a secret named where it is needed, and resolved only when it is used.
 *
 * The point is what does NOT happen: the value never lives in a file that travels with the
 * repository, never reaches the model, and never enters the transcript. A config that carries the
 * token itself is the most ordinary way keys leak, and it leaks silently — nobody notices until
 * somebody greps the history a year later.
 *
 * The syntax is shared by every place that runs something on this machine (project commands, the
 * ACP agent's environment), because two spellings of the same idea produce two sets of rules about
 * secrets, and the weaker one is the one that gets used.
 */
object SecretRefs {
  /** The only interpolation we perform. Names are conservative on purpose: a name is not an expression. */
  val PATTERN = Regex("\\$\\{secret:([A-Za-z0-9_]{1,64})}")

  fun names(text: String): List<String> = PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()

  fun has(text: String): Boolean = PATTERN.containsMatchIn(text)

  /**
   * Substitutes what the lookup knows and LEAVES the rest as written.
   *
   * An unresolved reference stays visible so the caller can refuse to run and name what is missing.
   * Replacing it with an empty string would turn «нет секрета» into a request that fails somewhere
   * far away, for a reason nobody can see from the failure.
   */
  fun substitute(text: String, lookup: (String) -> String?): String =
    PATTERN.replace(text) { match -> lookup(match.groupValues[1]) ?: match.value }

  /** Names the text asks for and the lookup cannot provide. */
  fun missing(text: String, lookup: (String) -> String?): List<String> =
    names(text).filter { lookup(it).isNullOrEmpty() }
}
