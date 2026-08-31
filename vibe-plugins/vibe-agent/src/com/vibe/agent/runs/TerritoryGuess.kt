// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

/**
 * Which corner of the project a run is about, read from the words it was started with.
 *
 * [TerritoryLock] can already tell whether two corners collide; what was missing is somebody to
 * name the corner. Asking a person to declare it before every unattended run is asking for the
 * thing they will skip, and a lock that is never claimed protects nothing.
 *
 * The guess is deliberately TIMID. An unknown territory claims nothing at all — it does not claim
 * the whole project, because «весь проект» collides with everything and would make a second run
 * impossible for the ordinary reason that its goal was phrased without a path. A missed collision
 * costs one merge; a lock that blocks every second run costs the feature.
 */
object TerritoryGuess {
  /** Directory depth of a claim: `src/ui/panel/Foo.kt` becomes `src/ui`, not the whole `src`. */
  const val PREFIX_DEPTH = 2

  /**
   * Path-looking tokens: a slash and an extension, or a folder followed by a slash.
   *
   * Deliberately not «любое слово с точкой»: `версии 1.2` and `README.md — читать` both contain a
   * dot, and claiming a corner named `1.2` is how a guesser starts blocking runs at random.
   */
  private val PATH = Regex("(?<![\\w/@.-])([\\w.-]+/)+[\\w.-]+")

  /** Folders that mean nothing about ownership: everybody's run touches them. */
  private val UNINTERESTING = setOf("", ".", "..", "src", "app", "lib", "test", "tests", "docs")

  /**
   * The corners named by this text, without duplicates and without one prefix nested in another.
   *
   * Nesting is removed because a claim on `src/ui` already covers `src/ui/panel`: keeping both
   * would report the same collision twice and make the chat line say it twice too.
   */
  fun prefixes(text: String, maxPrefixes: Int = 4): List<String> {
    val found = PATH.findAll(text).map { it.value }.mapNotNull { prefixOf(it) }.distinct().toList()
    val kept = found.filter { candidate -> found.none { it != candidate && TerritoryLock.overlaps(it, candidate) && it.length < candidate.length } }
    return kept.take(maxPrefixes)
  }

  /** The claimable corner of one path, or null when the path says nothing worth claiming. */
  fun prefixOf(path: String): String? {
    val normalized = TerritoryLock.normalize(path)
    if (normalized.isEmpty()) return null
    val parts = normalized.split('/').filter { it.isNotEmpty() }
    // A bare file name in the project root claims nothing: the root is everybody's corner.
    if (parts.size < 2) return null
    val folders = parts.dropLast(1).take(PREFIX_DEPTH)
    val prefix = folders.joinToString("/")
    if (folders.all { it in UNINTERESTING }) return null
    return prefix.ifEmpty { null }
  }

  /** The runs standing in the way of this territory; empty means the corner is free. */
  fun conflicts(runs: List<AgentRunLedger.Run>, runId: String, prefixes: List<String>): List<AgentRunLedger.Run> {
    if (prefixes.isEmpty()) return emptyList()
    val claims = runs
      .filter { it.status == AgentRunLedger.Status.RUNNING && it.runId != runId && it.territory.isNotEmpty() }
      .map { TerritoryLock.Claim(it.runId, it.territory) }
    val blocking = TerritoryLock.conflicts(claims, runId, prefixes).map { it.runId }.toSet()
    return runs.filter { it.runId in blocking }
  }
}
