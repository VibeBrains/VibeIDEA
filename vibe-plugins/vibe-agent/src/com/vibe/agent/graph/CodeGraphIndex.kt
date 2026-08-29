// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.graph

/**
 * Turns parsed files into a graph that answers the three questions a graph is built for:
 * what does this file pull in, who pulls in this file, and how are these two connected.
 *
 * **Every edge carries its provenance.** An import line matched against a declared qualified name
 * is a fact; an import matched only by the last segment is a guess — two `Utils` in different
 * packages look identical from a bare name. Handing an agent a mixed pile with no such mark is how
 * a plausible answer becomes a wrong refactor: it cannot tell what was read from what was inferred,
 * so it treats both as read.
 *
 * Pure: takes nodes, returns edges and answers. No PSI, no files, no project.
 */
object CodeGraphIndex {
  enum class Provenance {
    /** The import string equals a qualified name declared in the target file. */
    FACT,

    /** Only the last segment matched: the target is plausible, not certain. */
    GUESS,
  }

  data class Edge(val from: String, val to: String, val symbol: String, val provenance: Provenance)

  data class Graph(val nodes: List<GraphNode>, val edges: List<Edge>) {
    private val byFrom: Map<String, List<Edge>> by lazy { edges.groupBy { it.from } }
    private val byTo: Map<String, List<Edge>> by lazy { edges.groupBy { it.to } }

    /** Files this one imports. */
    fun importsOf(path: String): List<Edge> = byFrom[path].orEmpty()

    /** Files that import this one — the question no single file can answer about itself. */
    fun importersOf(path: String): List<Edge> = byTo[path].orEmpty()

    /**
     * Shortest chain between two files, ignoring direction: «как связаны вот эти двое» is asked
     * about relatedness, not about who depends on whom. Empty when there is no path at all —
     * which is an answer, and a useful one.
     */
    fun path(from: String, to: String): List<String> {
      if (from == to) return listOf(from)
      val neighbours = HashMap<String, MutableSet<String>>()
      for (edge in edges) {
        neighbours.getOrPut(edge.from) { LinkedHashSet() }.add(edge.to)
        neighbours.getOrPut(edge.to) { LinkedHashSet() }.add(edge.from)
      }
      val previous = HashMap<String, String>()
      val seen = HashSet<String>().apply { add(from) }
      val queue = ArrayDeque<String>().apply { add(from) }
      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (next in neighbours[current].orEmpty()) {
          if (!seen.add(next)) continue      // a cycle must not loop the search
          previous[next] = current
          if (next == to) return rebuild(previous, from, to)
          queue.addLast(next)
        }
      }
      return emptyList()
    }

    private fun rebuild(previous: Map<String, String>, from: String, to: String): List<String> {
      val chain = ArrayList<String>()
      var cursor: String? = to
      while (cursor != null) {
        chain.add(cursor)
        if (cursor == from) break
        cursor = previous[cursor]
      }
      return chain.reversed()
    }
  }

  /**
   * Builds edges from the imports already parsed out of every file.
   *
   * Matching is done against declared symbols rather than by searching references: a reference
   * search over thousands of files costs minutes and the answer would be no better — an import is
   * exactly the edge we want, and it is already in hand.
   */
  fun build(nodes: List<GraphNode>): Graph {
    val byQualifiedName = HashMap<String, String>()          // fully qualified symbol → file
    val bySimpleName = HashMap<String, MutableList<String>>() // last segment → files
    for (node in nodes) {
      for (symbol in node.symbols) {
        if (symbol.isBlank() || symbol == "?") continue
        byQualifiedName.putIfAbsent(symbol, node.path)
        bySimpleName.getOrPut(symbol.substringAfterLast('.')) { ArrayList() }.add(node.path)
      }
    }
    val edges = ArrayList<Edge>()
    for (node in nodes) {
      for (import in node.imports) {
        val clean = import.trim().removeSuffix(";").removeSuffix(".*")
        if (clean.isEmpty()) continue
        val exact = byQualifiedName[clean]
        if (exact != null) {
          if (exact != node.path) edges.add(Edge(node.path, exact, clean, Provenance.FACT))
          continue
        }
        // Only the tail matched: name it a guess, and only when it is unambiguous — a name that
        // resolves to three files is not a link, it is a coin toss.
        val candidates = bySimpleName[clean.substringAfterLast('.')].orEmpty().filter { it != node.path }
        if (candidates.size == 1) edges.add(Edge(node.path, candidates.single(), clean, Provenance.GUESS))
      }
    }
    return Graph(nodes, edges.distinct())
  }
}
