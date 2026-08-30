// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

/**
 * Two agents must not edit the same files at the same time.
 *
 * Running several agents at once is the point of pipelines and of external tasks, and the failure
 * mode is silent: two runs read the same file, both write it, and the second write wins with half
 * of the first one's work missing. Nothing errors — the result simply does not compile, or worse,
 * compiles.
 *
 * A territory is a set of path prefixes. Prefixes rather than exact files because a run does not
 * know in advance which files it will touch, but it always knows which corner of the project it is
 * working in — and a corner is exactly what can be claimed before the work starts.
 */
object TerritoryLock {
  data class Claim(val runId: String, val prefixes: List<String>)

  /** Normalised: trailing slashes and leading `./` make two spellings of the same corner. */
  fun normalize(prefix: String): String =
    prefix.replace('\\', '/').removePrefix("./").trim('/').lowercase()

  /**
   * Do two territories overlap?
   *
   * Overlap is not equality: `src` contains `src/ui`, and claiming the parent while a child is
   * claimed is the same collision seen from the other side. The empty prefix is the whole project
   * and therefore collides with everything — that is what makes «весь проект» honest rather than
   * a way to slip past the check.
   */
  fun overlaps(a: String, b: String): Boolean {
    val left = normalize(a)
    val right = normalize(b)
    if (left.isEmpty() || right.isEmpty()) return true
    return left == right || left.startsWith("$right/") || right.startsWith("$left/")
  }

  /** The claims that stand in the way, or empty when the corner is free. */
  fun conflicts(claims: List<Claim>, runId: String, prefixes: List<String>): List<Claim> =
    claims.filter { claim ->
      claim.runId != runId && claim.prefixes.any { existing -> prefixes.any { overlaps(existing, it) } }
    }

  /**
   * Idempotency: the same key while a run is still going returns THAT run rather than starting a
   * second one.
   *
   * The case is ordinary and expensive: a retry after a network hiccup, a button pressed twice, a
   * webhook delivered twice. Without a key each of them is a full second run — the same work, the
   * same writes, the same money, and two agents in the same files.
   */
  fun existingRun(runs: List<AgentRunLedger.Run>, idempotencyKey: String?): AgentRunLedger.Run? {
    if (idempotencyKey.isNullOrBlank()) return null
    return runs.firstOrNull { it.status == AgentRunLedger.Status.RUNNING && it.idempotencyKey == idempotencyKey }
  }
}
