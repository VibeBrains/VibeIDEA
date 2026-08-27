// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.gates

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

  /** Secret shapes that must never be committed. Anchored, conservative — false positives cost trust. */
  private val SECRET_PATTERNS = listOf(
    "приватный ключ" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    "AWS access key" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
    "ключ Anthropic" to Regex("\\bsk-ant-[A-Za-z0-9_-]{20,}"),
    "ключ OpenAI" to Regex("\\bsk-[A-Za-z0-9]{32,}\\b"),
    "GitHub token" to Regex("\\bghp_[A-Za-z0-9]{36}\\b"),
    "Slack token" to Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
    "Google API key" to Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
  )

  /** Path fragments never safe to write from an agent turn. */
  private val PROTECTED_FRAGMENTS = listOf("/.git/", "/.ssh/", "/id_rsa", "/id_ed25519")
  private val PROTECTED_SUFFIXES = listOf(".pem", ".key", ".env")
  private val PROTECTED_NAMES = listOf(".env", "credentials", ".npmrc", ".pypirc")

  /** @param files (path, content) of the turn's changed files (caller caps the count and size). */
  fun scanSecretLeak(files: List<Pair<String, String>>): List<TurnFinding> {
    val findings = ArrayList<TurnFinding>()
    for ((path, content) in files.take(MAX_FILES_SCANNED)) {
      for ((label, re) in SECRET_PATTERNS) {
        if (re.containsMatchIn(content)) {
          findings.add(TurnFinding(TurnCheckId.NO_SECRET_LEAK, path, label))
          break // one finding per file is enough to bounce
        }
      }
    }
    return findings
  }

  fun scanProtectedPath(paths: List<String>): List<TurnFinding> {
    val findings = ArrayList<TurnFinding>()
    for (path in paths) {
      val normalized = path.replace('\\', '/')
      val name = normalized.substringAfterLast('/')
      val hit = PROTECTED_FRAGMENTS.any { normalized.contains(it) } ||
        PROTECTED_SUFFIXES.any { normalized.endsWith(it) } ||
        PROTECTED_NAMES.any { name == it }
      if (hit) findings.add(TurnFinding(TurnCheckId.NO_PROTECTED_PATH, path, "защищённый путь"))
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

  /** True when any finding is a security violation that should trip a latching breaker. */
  fun hasSecurityFinding(findings: List<TurnFinding>): Boolean = findings.isNotEmpty()

  fun renderCorrective(findings: List<TurnFinding>, attempt: Int, maxAttempts: Int): String =
    "⛔ ПРОВЕРКИ ХОДА нашли проблемы (попытка $attempt из $maxAttempts):\n" +
      findings.joinToString("\n") { "• ${it.detail}: ${it.path}" } +
      "\nИсправь это и продолжай — не завершай ход, пока не станет чисто."
}
