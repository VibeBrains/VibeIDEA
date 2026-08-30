// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

/**
 * The project's shape as a diagram someone can look at.
 *
 * The graph already answers «кто кого импортирует» in queries, but a list of edges is something one
 * reads with a finger on the screen. A picture answers the question people actually have —
 * «как этот проект устроен» — in one glance, and mermaid is the format that renders in the IDE, in
 * GitHub and in a chat without a single dependency.
 *
 * Grouping is by top-level folder rather than by file: a diagram with four hundred nodes is a
 * diagram nobody opens twice, while «ui → providers → http» is a picture that fits in the head.
 */
object GraphDiagram {
  data class Edge(val from: String, val to: String, val weight: Int)

  /** Deliberately small: past this a diagram stops being read and starts being scrolled. */
  const val MAX_NODES = 20
  const val MAX_EDGES = 40

  /** The module a path belongs to: the first meaningful folder, not the file. */
  fun moduleOf(path: String, roots: List<String> = COMMON_ROOTS): String {
    val clean = path.replace('\\', '/').trimStart('/')
    val parts = clean.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 1) return ROOT
    // `src/main/kotlin/...` says nothing about the project: skip the wrappers everyone has.
    val meaningful = parts.dropWhile { it.lowercase() in roots }
    return meaningful.firstOrNull()?.takeIf { !it.contains('.') } ?: parts.first()
  }

  /** Collapses file-to-file edges into module-to-module ones, dropping self-references. */
  fun modules(edges: List<Pair<String, String>>): List<Edge> {
    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for ((from, to) in edges) {
      val a = moduleOf(from)
      val b = moduleOf(to)
      if (a == b) continue
      counts[a to b] = (counts[a to b] ?: 0) + 1
    }
    return counts.entries
      .map { Edge(it.key.first, it.key.second, it.value) }
      .sortedWith(compareByDescending<Edge> { it.weight }.thenBy { it.from })
  }

  /**
   * A mermaid flowchart. Node ids are sanitised — a folder named `my-app` breaks mermaid's syntax,
   * and a diagram that does not render is worse than no diagram at all.
   */
  fun mermaid(edges: List<Edge>, maxNodes: Int = MAX_NODES, maxEdges: Int = MAX_EDGES): String {
    if (edges.isEmpty()) return ""
    val kept = ArrayList<Edge>()
    val nodes = LinkedHashSet<String>()
    for (edge in edges) {
      if (kept.size >= maxEdges) break
      val projected = nodes.toMutableSet().apply { add(edge.from); add(edge.to) }
      if (projected.size > maxNodes) continue
      nodes.addAll(projected)
      kept.add(edge)
    }
    return buildString {
      appendLine("flowchart LR")
      for (node in nodes) appendLine("  " + id(node) + "[\"" + node + "\"]")
      for (edge in kept) {
        val label = if (edge.weight > 1) "|" + edge.weight + "|" else ""
        appendLine("  " + id(edge.from) + " -->" + label + " " + id(edge.to))
      }
    }.trimEnd()
  }

  fun id(module: String): String {
    val cleaned = module.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    // A name made only of separators («.» for the project root) collapses to underscores, which is
    // a legal id and an unreadable one — it gets a name instead.
    if (cleaned.none { it.isLetterOrDigit() }) return "root"
    // A mermaid id may not start with a digit; prefixing is cheaper than explaining a broken render.
    return if (cleaned.first().isDigit()) "m$cleaned" else cleaned
  }

  const val ROOT = "."
  private val COMMON_ROOTS = listOf("src", "main", "kotlin", "java", "app", "lib", "packages")
}
