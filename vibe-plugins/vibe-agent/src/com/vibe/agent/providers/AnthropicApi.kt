// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import java.net.URI

/**
 * Anthropic's own API among the addresses a person may give the Anthropic wire
 *
 * Betas are documented for it; the vendors that speak the same wire (MiniMax, Kimi, MiMo) are not bound to accept
 * them, and a field they do not know is a guess about their parser
 */
object AnthropicApi {
  private const val HOST = "api.anthropic.com"

  fun official(baseUrl: String): Boolean = runCatching { URI(baseUrl.trim()).host?.lowercase() == HOST }.getOrDefault(false)
}
