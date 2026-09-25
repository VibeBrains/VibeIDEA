// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

/**
 * Whether a provider's address is this machine:
 * Such a provider works without a key, and its outage reads «not running»
 * Whether its models run here is a separate question with its own answer — [ResolvedProvider.runsLocally]
 *
 * The list is shared with VibeIDE (`testVectors/providerAuth.json` of the set), so one entry behaves the same in both
 * `0.0.0.0` is in it: servers print it as the address they listen on, and a request to it lands on this machine
 * The host is compared without case and without IPv6 brackets: `URI.host` keeps both, and `[::1]` never matched `::1`
 *
 * Narrower than the proxy's loopback on purpose ([com.vibe.agent.resilience.ProxySettings.bypasses]):
 * That one answers «may this leave the machine», this one «does this provider need a key»
 */
object LocalAddress {
  val HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")

  fun isLocal(baseUrl: String?): Boolean {
    val host = runCatching { java.net.URI(baseUrl.orEmpty().trim()).host }.getOrNull() ?: return false
    return host.removePrefix("[").removeSuffix("]").lowercase() in HOSTS
  }
}
