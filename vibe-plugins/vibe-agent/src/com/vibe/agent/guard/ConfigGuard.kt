// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

import com.vibe.agent.security.SecretPatterns

/**
 * Reads the project's own config files the way an attacker would, and says what it finds.
 *
 * These files arrive with the repository: `.vibe/providers.json` decides where the model requests
 * go, `.vibe/servers.json` decides what is started on this machine. Cloning someone's project must
 * not mean silently trusting their endpoints — and the ordinary case is not an attack at all, it is
 * a colleague who pasted a key into a file that is about to be committed.
 *
 * Every finding names WHY it matters, because a warning that only says «подозрительно» is a warning
 * people learn to click past.
 */
object ConfigGuard {
  enum class Severity { ERROR, WARNING }

  data class Finding(val file: String, val rule: String, val severity: Severity, val detail: String)

  const val RULE_PLAINTEXT_SECRET = "plaintext-secret"
  const val RULE_INSECURE_ENDPOINT = "insecure-endpoint"
  const val RULE_CREDENTIALS_IN_URL = "credentials-in-url"
  const val RULE_RAW_IP_ENDPOINT = "raw-ip-endpoint"

  private val URL = Regex("\"(https?://[^\"\\s]+)\"")
  private val CREDENTIALS_IN_URL = Regex("https?://[^/@\\s\"]+:[^/@\\s\"]+@")
  private val RAW_IP = Regex("https?://(\\d{1,3}\\.){3}\\d{1,3}")
  private val LOOPBACK = Regex("https?://(localhost|127\\.0\\.0\\.1|\\[::1])([:/]|$)")

  /** [text] is the file content; [name] is what to show the person. */
  fun inspect(name: String, text: String): List<Finding> {
    val findings = ArrayList<Finding>()

    // A key written into a file that is about to be committed is the most common way keys leak,
    // and it is invisible until someone greps the history a year later.
    for (label in SecretPatterns.labels(text)) {
      findings.add(Finding(name, RULE_PLAINTEXT_SECRET, Severity.ERROR, label))
    }

    for (match in URL.findAll(text)) {
      val url = match.groupValues[1]
      when {
        CREDENTIALS_IN_URL.containsMatchIn(url) ->
          // The warning about a password must not repeat the password: everything before the @ is
          // dropped, and what is left is enough to find the line.
          findings.add(Finding(name, RULE_CREDENTIALS_IN_URL, Severity.ERROR, "…@" + url.substringAfter('@')))
        // Loopback over http is normal: a local model server has nothing to encrypt against.
        url.startsWith("http://") && !LOOPBACK.containsMatchIn(url) ->
          findings.add(Finding(name, RULE_INSECURE_ENDPOINT, Severity.ERROR, url))
        RAW_IP.containsMatchIn(url) && !LOOPBACK.containsMatchIn(url) ->
          findings.add(Finding(name, RULE_RAW_IP_ENDPOINT, Severity.WARNING, url))
      }
    }
    return findings
  }

  /** Config files worth reading before the first turn; missing ones are simply skipped. */
  val FILES = listOf(
    ".vibe/providers.json",
    ".vibe/servers.json",
    ".vibe/hooks.json",
    ".vibe/acp.json",
    ".vibe/commands.json",
  )

  fun worst(findings: List<Finding>): Severity? =
    when {
      findings.any { it.severity == Severity.ERROR } -> Severity.ERROR
      findings.isNotEmpty() -> Severity.WARNING
      else -> null
    }
}
