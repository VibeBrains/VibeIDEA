// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.ProjectGraphAnalysis.Analysis

/**
 * The subsystems of a project as a mermaid diagram, for the chat command `/map`
 *
 * The picture in the editor tab is the main view; this is its text form, for the one place a tab cannot go:
 * Mermaid renders in the chat, in the repository and in a pull request without a single dependency
 *
 * It used to group files by the top-level folder, and a folder says little about how a project is put together
 * The nodes are now the subsystems the links themselves found, the same ones the tab draws
 *
 * Deliberately small: past a couple of dozen nodes a diagram stops being read and starts being scrolled
 */
object GraphDiagram {
  /** A link between two subsystems; the weight is how many links between their files there are */
  data class Edge(val from: Int, val to: Int, val weight: Int)

  const val MAX_NODES = 20
  const val MAX_EDGES = 40

  /**
   * Links between subsystems, the heaviest first
   *
   * A link inside one subsystem is not drawn: every subsystem has them, and they say nothing
   * A link to a lone file is not drawn either: a lone file is not a subsystem
   */
  fun subsystems(analysis: Analysis): List<Edge> {
    val counts = HashMap<Pair<Int, Int>, Int>()
    for (link in analysis.links) {
      val a = analysis.communityOf.getValue(link.from)
      val b = analysis.communityOf.getValue(link.to)
      if (a == b || a !in analysis.subsystemById || b !in analysis.subsystemById) continue
      val key = if (a < b) a to b else b to a
      counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.entries
      .map { Edge(it.key.first, it.key.second, it.value) }
      .sortedWith(compareByDescending<Edge> { it.weight }.thenBy { it.from }.thenBy { it.to })
  }

  /**
   * A mermaid flowchart
   *
   * Node ids are made of the subsystem number, never of its name: a folder named `my-app` breaks mermaid's syntax
   * And two subsystems may carry one label, and a diagram that does not render is worse than none
   */
  fun mermaid(analysis: Analysis, edges: List<Edge>, maxNodes: Int = MAX_NODES, maxEdges: Int = MAX_EDGES): String {
    if (edges.isEmpty()) return ""
    val kept = ArrayList<Edge>()
    val nodes = LinkedHashSet<Int>()
    for (edge in edges) {
      if (kept.size >= maxEdges) break
      val projected = nodes.toMutableSet().apply { add(edge.from); add(edge.to) }
      if (projected.size > maxNodes) continue
      nodes.addAll(projected)
      kept.add(edge)
    }
    return buildString {
      appendLine("flowchart LR")
      for (node in nodes) {
        val subsystem = analysis.subsystemById.getValue(node)
        appendLine("  " + id(node) + "[\"" + subsystem.label.replace('"', '\'') + " · " + subsystem.files.size + "\"]")
      }
      for (edge in kept) {
        val label = if (edge.weight > 1) "|" + edge.weight + "|" else ""
        appendLine("  " + id(edge.from) + " -->" + label + " " + id(edge.to))
      }
    }.trimEnd()
  }

  fun id(subsystem: Int): String = "s$subsystem"
}
