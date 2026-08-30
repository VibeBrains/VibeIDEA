// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * The documentation of a project, read as a GRAPH rather than as a folder.
 *
 * A folder answers «какие файлы есть». The two questions people actually have are different:
 * what can be reached from the entry point (a document nobody links to is a document nobody finds,
 * however good it is), and which links are broken (a renamed file leaves a trail of dead links that
 * no compiler ever complains about).
 *
 * Pure: the whole analysis is a function of «путь → содержимое», so it can be tested on a made-up
 * project of five files instead of on somebody's real repository.
 */
object DocsIndex {
  data class Link(val from: String, val to: String, val broken: Boolean)

  data class Doc(val path: String, val title: String, val outgoing: List<Link>)

  data class Analysis(
    val docs: List<Doc>,
    /** Reachable from the entry point by following links. */
    val reachable: Set<String>,
    val brokenLinks: List<Link>,
  ) {
    val unreachable: List<Doc> get() = docs.filter { it.path !in reachable }
  }

  private val LINK = Regex("\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)")
  private val HEADING = Regex("(?m)^#\\s+(.+)$")

  const val ENTRY_POINT = "README.md"

  /**
   * [files] maps a project-relative path to its content. Only markdown participates: a link to a
   * source file is a normal thing to write and is not a documentation edge.
   */
  fun analyse(files: Map<String, String>, entryPoint: String = ENTRY_POINT): Analysis {
    val markdown = files.filterKeys { it.endsWith(".md") || it.endsWith(".mdx") }
    val docs = markdown.map { (path, text) ->
      val links = LINK.findAll(text).mapNotNull { match ->
        val target = match.groupValues[2].trim()
        // External links and in-page anchors are not edges of this graph.
        if (target.isEmpty() || target.startsWith("http") || target.startsWith("#") || target.startsWith("mailto:")) {
          return@mapNotNull null
        }
        val resolved = resolve(path, target.substringBefore('#'))
        if (!resolved.endsWith(".md") && !resolved.endsWith(".mdx")) return@mapNotNull null
        Link(path, resolved, broken = resolved !in markdown)
      }.toList()
      Doc(path, titleOf(text) ?: path.substringAfterLast('/'), links)
    }.sortedBy { it.path }

    val byPath = docs.associateBy { it.path }
    val reachable = LinkedHashSet<String>()
    val entry = if (entryPoint in byPath) entryPoint else docs.firstOrNull()?.path
    if (entry != null) {
      val queue = ArrayDeque(listOf(entry))
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!reachable.add(current)) continue
        byPath[current]?.outgoing?.forEach { link -> if (!link.broken) queue.addLast(link.to) }
      }
    }
    return Analysis(docs, reachable, docs.flatMap { it.outgoing }.filter { it.broken })
  }

  /** A relative link resolves against the folder of the file it is written in, `..` included. */
  fun resolve(from: String, target: String): String {
    if (target.startsWith("/")) return target.trimStart('/')
    val base = from.substringBeforeLast('/', "")
    val parts = ArrayList<String>()
    if (base.isNotEmpty()) parts.addAll(base.split('/'))
    for (segment in target.split('/')) {
      when (segment) {
        "", "." -> {}
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
        else -> parts.add(segment)
      }
    }
    return parts.joinToString("/")
  }

  fun titleOf(text: String): String? = HEADING.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
