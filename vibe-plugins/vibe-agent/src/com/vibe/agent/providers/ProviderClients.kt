// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.vibe.agent.resilience.ProxyTargets
import com.vibe.agent.settings.VibeAgentSettings
import java.net.http.HttpClient
import java.time.Duration

/**
 * The two ways a request to a provider can leave:
 * The configured route, and straight out for a provider marked «direct»
 *
 * Chosen per provider, not per address:
 * `minimax` and `minimax-anthropic`, `opencode-go` and `opencode-zen` share a host, and a proxy selector sees only it
 *
 * Each client is built on first use: a machine without a single direct provider never builds the second one
 */
class ProviderClients(private val connectTimeout: Duration) {
  private val routed: HttpClient by lazy { LlmClient.defaultClient(connectTimeout) }
  private val direct: HttpClient by lazy { LlmClient.defaultClient(connectTimeout, direct = true) }

  fun of(provider: ResolvedProvider): HttpClient =
    if (VibeAgentSettings.goesDirect(ProxyTargets.provider(provider.entry.id))) direct else routed
}
