// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.resilience

/**
 * Which model providers and external agents go straight out, past every proxy
 *
 * One tunnel rarely suits every vendor: a provider reachable directly gets slower or refused through it,
 * while its neighbour is reachable only through it. So the choice is per target, and it is the machine's, not the
 * project's: which route works is a property of this network, and a project file travels to other networks
 *
 * A target is written `provider:<id>` or `agent:<name>`, one per line: a provider and an agent may share a name
 */
object ProxyTargets {
  private const val PROVIDER = "provider:"
  private const val AGENT = "agent:"

  fun provider(id: String): String = PROVIDER + id

  fun agent(name: String): String = AGENT + name

  /** The stored form: one target a line; blank lines and stray spaces mean nothing */
  fun parse(text: String?): Set<String> =
    text.orEmpty().lines().map { it.trim() }.filter { it.startsWith(PROVIDER) || it.startsWith(AGENT) }.toSortedSet()

  fun serialize(targets: Set<String>): String = targets.sorted().joinToString("\n")
}
