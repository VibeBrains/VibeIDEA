// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

/**
 * The product's own documentation, shipped inside the build.
 *
 * The question "как в этой IDE устроен дизайн-гейт?" is asked of the agent, not of a search engine —
 * and an agent that has to guess answers from the model's memory of some other product. Since the
 * docs are ours and small, they travel with the plugin: the agent reads the real text of the real
 * version it is running in.
 *
 * The index is a FILE generated next to the copies rather than a list in this class. The list was
 * the first version, and it drifted within a week: two manuals were added to the repository and
 * nobody remembered the array here, so `/help` simply could not find them — a failure that looks
 * like «в справке про это ничего нет».
 *
 * Titles come from each document's own first heading for the same reason: a title kept anywhere
 * else is a second place to forget.
 */
object HelpBundle {
  const val ROOT = "/help"
  const val INDEX = "$ROOT/index.txt"

  /** One bundled document: the resource path and the title read from its first heading. */
  data class Doc(val resource: String, val title: String)

  private val HEADING = Regex("(?m)^#\\s+(.+)$")

  private val docs: List<Doc> by lazy { load() }

  fun list(): List<Doc> = docs

  fun read(resource: String): String? =
    HelpBundle::class.java.getResourceAsStream(resource)?.bufferedReader()?.readText()

  /**
   * Documents whose title or path matches the question. Deliberately crude: the bundle is under two
   * dozen files with descriptive names, and a real index would be maintenance for no gain.
   */
  fun find(question: String, limit: Int = MAX_HITS): List<Doc> {
    val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_WORD }
    if (words.isEmpty()) return emptyList()
    return docs.map { doc ->
      val haystack = (doc.title + " " + doc.resource).lowercase()
      doc to words.count { haystack.contains(it) }
    }.filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(limit)
      .map { it.first }
  }

  /** Title of a document, or its file name when it has no heading — never an empty label. */
  fun titleOf(text: String?, resource: String): String =
    text?.let { HEADING.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }
      ?: resource.substringAfterLast('/')

  private fun load(): List<Doc> {
    val index = read(INDEX) ?: return emptyList()
    return index.lines().mapNotNull { line ->
      val relative = line.trim()
      if (relative.isEmpty()) return@mapNotNull null
      val resource = "$ROOT/$relative"
      Doc(resource, titleOf(read(resource), resource))
    }
  }

  const val MAX_HITS = 3
  private const val MIN_WORD = 4
}
