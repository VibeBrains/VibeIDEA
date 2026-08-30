// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.rag

import kotlin.math.sqrt

/**
 * The search itself: cosine similarity over stored vectors.
 *
 * Deliberately a flat scan rather than an approximate index. A project is tens of thousands of
 * chunks, a scan over that is milliseconds, and an ANN structure would add a dependency, a build
 * step and a class of bugs («почему оно не нашло очевидное») in exchange for time nobody notices.
 */
object VectorIndex {
  data class Entry(val chunk: Chunker.Chunk, val vector: FloatArray) {
    // Vectors make equals/hashCode of a data class meaningless; identity is the chunk id.
    override fun equals(other: Any?): Boolean = other is Entry && other.chunk.id == chunk.id
    override fun hashCode(): Int = chunk.id.hashCode()
  }

  data class Hit(val chunk: Chunker.Chunk, val score: Float)

  /** Cosine of the angle: length-independent, which is what makes long and short chunks comparable. */
  fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
      dot += a[i] * b[i]
      normA += a[i] * a[i]
      normB += b[i] * b[i]
    }
    if (normA == 0.0 || normB == 0.0) return 0f
    return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
  }

  /**
   * Top matches above [minScore].
   *
   * The floor matters more than the ranking: without it the search always returns something, and a
   * confident answer built on the five least-irrelevant chunks is worse than «ничего не нашёл».
   */
  fun search(entries: List<Entry>, query: FloatArray, limit: Int = DEFAULT_LIMIT, minScore: Float = MIN_SCORE): List<Hit> =
    entries.asSequence()
      .map { Hit(it.chunk, cosine(query, it.vector)) }
      .filter { it.score >= minScore }
      .sortedByDescending { it.score }
      .take(limit)
      .toList()

  /** One hit per file at most: five windows of the same file crowd out everything else. */
  fun spreadAcrossFiles(hits: List<Hit>, perFile: Int = 1): List<Hit> {
    val counts = HashMap<String, Int>()
    return hits.filter { hit ->
      val seen = counts.getOrDefault(hit.chunk.path, 0)
      if (seen >= perFile) false else { counts[hit.chunk.path] = seen + 1; true }
    }
  }

  const val DEFAULT_LIMIT = 8
  const val MIN_SCORE = 0.25f
}
