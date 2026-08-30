// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.specs

/**
 * Optimisation against a NUMBER, for tasks that have no «правильно».
 *
 * «Ускорь», «урежь размер сборки», «подними покрытие» have no threshold at which they are done, so
 * a model working on them declares victory by adjective: «стало заметно быстрее». The only honest
 * answer is a measurement before and a measurement after — and the discipline that makes it honest
 * is deciding, in advance, which direction counts as better.
 */
object MetricRun {
  enum class Direction { LOWER_IS_BETTER, HIGHER_IS_BETTER }

  data class Result(val value: Double, val raw: String)

  data class Comparison(val before: Double, val after: Double, val direction: Direction) {
    val delta: Double get() = after - before
    val improved: Boolean get() = if (direction == Direction.LOWER_IS_BETTER) after < before else after > before
    /** Percent of change, signed the way people read it: negative means «стало меньше». */
    val percent: Double get() = if (before == 0.0) 0.0 else (after - before) / before * 100.0
  }

  /**
   * Pulls the number out of the command output.
   *
   * The LAST match rather than the first: a build prints its own numbers along the way, and the
   * summary — the line people actually read — comes at the end.
   */
  fun extract(output: String, pattern: String?): Result? {
    val regex = runCatching { Regex(pattern?.takeIf { it.isNotBlank() } ?: DEFAULT_PATTERN) }.getOrNull() ?: return null
    val matches = regex.findAll(output).toList()
    if (matches.isEmpty()) return null
    val match = matches.last()
    val text = (match.groups.takeIf { it.size > 1 }?.get(1)?.value ?: match.value).replace(',', '.')
    val value = text.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: return null
    return Result(value, match.value.trim())
  }

  fun compare(before: Double, after: Double, direction: Direction): Comparison = Comparison(before, after, direction)

  fun directionOf(name: String?): Direction =
    if (name?.trim()?.lowercase() in setOf("higher", "up", "больше", "выше")) Direction.HIGHER_IS_BETTER
    else Direction.LOWER_IS_BETTER

  /** Any number, including decimals — enough for seconds, bytes and percents alike. */
  const val DEFAULT_PATTERN = "([0-9]+(?:[.,][0-9]+)?)"
}
