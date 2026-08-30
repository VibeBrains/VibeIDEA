// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

import com.vibe.agent.i18n.VibeI18n.t

/**
 * Deterministic turn checks over the files a turn changed, VibeIDE contract
 * (trimmed to the two the ACP client can actually enforce): `no-secret-leak` and
 * `no-protected-path`. An LLM judge is forbidden by design — a turn check is a
 * fact, not an opinion, so it stays a pure function over paths and contents.
 */
enum class TurnCheckId { NO_SECRET_LEAK, NO_PROTECTED_PATH }

data class TurnFinding(val check: TurnCheckId, val path: String, val detail: String)

enum class TurnChecksDecision { COMPLETE, NOTIFY_COMPLETE, BOUNCE, STOP }

object TurnChecks {
  /** Scan limits mirror VibeIDE so a huge turn cannot stall the gate. */
  const val MAX_FILES_SCANNED = 40

  /** Secret shapes live in [com.vibe.agent.security.SecretPatterns] — the same list guards the
   *  inbound direction (what we send to a model), and two copies would drift apart. */

  /** Path fragments never safe to write from an agent turn. */
  private val PROTECTED_FRAGMENTS = listOf("/.git/", "/.ssh/", "/id_rsa", "/id_ed25519")
  private val PROTECTED_SUFFIXES = listOf(".pem", ".key", ".env")
  private val PROTECTED_NAMES = listOf(".env", "credentials", ".npmrc", ".pypirc")

  /**
   * @param files (path, content) of the turn's changed files (caller caps the count and size).
   * @param maxFiles defensive ceiling on how many are scanned (caller may cap lower).
   */
  fun scanSecretLeak(files: List<Pair<String, String>>, maxFiles: Int = MAX_FILES_SCANNED): List<TurnFinding> {
    val findings = ArrayList<TurnFinding>()
    for ((path, content) in files.take(maxFiles)) {
      com.vibe.agent.security.SecretPatterns.firstMatch(content)?.let { pattern ->
        findings.add(TurnFinding(TurnCheckId.NO_SECRET_LEAK, path, pattern.label))
      }
    }
    return findings
  }

  fun scanProtectedPath(paths: List<String>): List<TurnFinding> {
    val findings = ArrayList<TurnFinding>()
    for (path in paths) {
      // Prepend '/' so a fragment like "/.git/" also matches a RELATIVE path ".git/config".
      val normalized = "/" + path.replace('\\', '/').removePrefix("/")
      val name = normalized.substringAfterLast('/')
      val hit = PROTECTED_FRAGMENTS.any { normalized.contains(it) } ||
        PROTECTED_SUFFIXES.any { normalized.endsWith(it) } ||
        PROTECTED_NAMES.any { name == it }
      if (hit) findings.add(TurnFinding(TurnCheckId.NO_PROTECTED_PATH, path, t("gates.protectedPath")))
    }
    return findings
  }

  /**
   * @param mode off/notify/enforce
   * @param findings combined findings for the turn
   * @param attemptsUsed prior bounces this turn
   * @param maxAttempts enforce ceiling (floored at 1)
   */
  fun decide(mode: String, findings: List<TurnFinding>, attemptsUsed: Int, maxAttempts: Int): TurnChecksDecision {
    if (mode == "off" || findings.isEmpty()) return TurnChecksDecision.COMPLETE
    if (mode == "notify") return TurnChecksDecision.NOTIFY_COMPLETE
    val ceiling = maxOf(1, maxAttempts)
    return if (attemptsUsed < ceiling) TurnChecksDecision.BOUNCE else TurnChecksDecision.STOP
  }

  fun renderCorrective(findings: List<TurnFinding>, attempt: Int, maxAttempts: Int): String =
    "⛔ " + t("gates.turnChecks.header", "attempt" to attempt, "max" to maxAttempts) + "\n" +
      findings.joinToString("\n") { "• ${it.detail}: ${it.path}" } +
      "\n" + t("gates.turnChecks.footer")
}
