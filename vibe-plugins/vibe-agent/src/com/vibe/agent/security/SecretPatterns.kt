// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.security

/**
 * Shapes of credentials, in one place for both directions:
 * outbound (`TurnChecks.scanSecretLeak` — what the agent WROTE) and inbound
 * ([ContextSanitizer] — what we are about to SEND to a model).
 *
 * Anchored and conservative on purpose: a false positive here nags the user on every turn, and a
 * gate nobody believes is a gate nobody keeps.
 */
object SecretPatterns {
  data class Pattern(val label: String, val regex: Regex)

  val ALL: List<Pattern> = listOf(
    Pattern("приватный ключ", Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    Pattern("AWS access key", Regex("\\bAKIA[0-9A-Z]{16}\\b")),
    Pattern("ключ Anthropic", Regex("\\bsk-ant-[A-Za-z0-9_-]{20,}")),
    // Covers legacy sk-, and modern sk-proj-/sk-svcacct-/sk-admin- (hyphens in the body); no trailing \b (can't follow '-').
    Pattern("ключ OpenAI", Regex("\\bsk-[A-Za-z0-9_-]{20,}")),
    // ghp_/gho_/ghu_/ghs_/ghr_ personal, OAuth, user, server and refresh tokens.
    Pattern("GitHub token", Regex("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")),
    Pattern("Slack token", Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    Pattern("Google API key", Regex("\\bAIza[0-9A-Za-z_-]{35}\\b")),
  )

  /** First matching shape, or null. */
  fun firstMatch(text: String): Pattern? = ALL.firstOrNull { it.regex.containsMatchIn(text) }

  /** Every shape present in the text, without duplicates, in declaration order. */
  fun labels(text: String): List<String> = ALL.filter { it.regex.containsMatchIn(text) }.map { it.label }

  /** Replaces the secret itself with a marker, keeping the surrounding text intact. */
  fun redact(text: String): String {
    var result = text
    for (pattern in ALL) result = pattern.regex.replace(result) { "«$MASK: ${pattern.label}»" }
    return result
  }

  const val MASK = "скрыто VibeIDEA"
}
