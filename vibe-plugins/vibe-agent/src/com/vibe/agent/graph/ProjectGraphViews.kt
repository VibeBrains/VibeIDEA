// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

import com.vibe.agent.graph.CodeGraphIndex.Provenance
import com.vibe.agent.graph.ProjectGraphAnalysis.Analysis
import com.vibe.agent.graph.ProjectGraphAnalysis.FileLink
import com.vibe.agent.graph.ProjectGraphAnalysis.basename
import com.vibe.agent.graphview.CanvasEdge
import com.vibe.agent.graphview.CanvasGraph
import com.vibe.agent.graphview.CanvasNode
import com.vibe.agent.graphview.GraphSizing

/**
 * The two levels of the project picture, as graphs for the shared canvas
 *
 * The map shows one node per subsystem: a repository is thousands of files, and a picture of them all is noise
 * And the layout pairs every node with every other, so a subsystem is opened one at a time, up to [SUBSYSTEM_VIEW_LIMIT] files
 * The subsystems around an opened one stay single nodes: the picture says where the subsystem leads without pulling their files in
 *
 * Pure: an analysis in, a graph out
 */
object ProjectGraphViews {
  /** An opened subsystem draws its most connected files up to this many: the layout pairs every node with every other */
  const val SUBSYSTEM_VIEW_LIMIT = 400

  /** Names of the subsystems show from far out on the map: a few dozen of them, and their names are the point of it */
  const val OVERVIEW_LABELS_FROM_SCALE = 0.2

  private const val SUBSYSTEM_NODE_PREFIX = "subsystem:"

  /** Which links a view shows; the subsystems are always found on all of them, so colours do not jump under a filter */
  data class LinkFilter(val facts: Boolean = true, val guesses: Boolean = true) {
    fun passes(link: FileLink): Boolean = if (link.provenance == Provenance.FACT) facts else guesses

    companion object {
      val ALL = LinkFilter()
    }
  }

  /** An opened subsystem and how many of its files the size limit left out */
  class SubsystemView(val graph: CanvasGraph, val hiddenFiles: Int)

  fun subsystemNodeId(id: Int): String = SUBSYSTEM_NODE_PREFIX + id

  /** The subsystem a canvas node stands for, or null for a file node */
  fun subsystemOfNodeId(nodeId: String): Int? =
    if (nodeId.startsWith(SUBSYSTEM_NODE_PREFIX)) nodeId.removePrefix(SUBSYSTEM_NODE_PREFIX).toIntOrNull() else null

  /** Files and the subsystem they belong to share a colour key; the owner of the canvas turns it into a palette colour */
  fun colorKeyOf(subsystem: Int): String = subsystem.toString()

  /** The whole project at a glance: one node per subsystem, sized by its files, joined where any file of one links the other */
  fun overview(analysis: Analysis, filter: LinkFilter = LinkFilter.ALL): CanvasGraph {
    val shown = analysis.subsystems.mapTo(HashSet()) { it.id }
    val nodes = analysis.subsystems.map { subsystem ->
      CanvasNode(
        id = subsystemNodeId(subsystem.id),
        label = subsystem.label,
        tooltip = subsystem.label + " — " + subsystem.files.size + " · " + analysis.relative(subsystem.hub),
        // A search by a file name lights the subsystem the file lives in
        searchText = (subsystem.label + "\n" + subsystem.files.joinToString("\n")).lowercase(),
        colorKey = colorKeyOf(subsystem.id),
        // The logarithm of its size: a subsystem of 1800 files at full scale would cover the map
        // The count itself stays in the hover text and the report
        radius = GraphSizing.radiusOfCount(subsystem.files.size),
      )
    }
    val pairs = LinkedHashSet<Pair<Int, Int>>()
    for (link in analysis.links) {
      if (!filter.passes(link)) continue
      val a = analysis.communityOf.getValue(link.from)
      val b = analysis.communityOf.getValue(link.to)
      if (a == b || a !in shown || b !in shown) continue
      pairs.add(if (a < b) a to b else b to a)
    }
    return CanvasGraph(nodes, pairs.map { (a, b) -> CanvasEdge(subsystemNodeId(a), subsystemNodeId(b)) }, OVERVIEW_LABELS_FROM_SCALE)
  }

  /**
   * One subsystem opened up: its files, plus every other subsystem it reaches drawn as a single node
   * The neighbours keep the picture honest about where the subsystem leads without pulling their files in
   */
  fun subsystemView(analysis: Analysis, id: Int, filter: LinkFilter = LinkFilter.ALL, limit: Int = SUBSYSTEM_VIEW_LIMIT): SubsystemView {
    val subsystem = analysis.subsystemById[id] ?: return SubsystemView(CanvasGraph.EMPTY, 0)
    val files = subsystem.files.take(limit)
    val inside = files.toHashSet()
    val fileNodes = files.map { file ->
      CanvasNode(
        id = file,
        label = basename(file),
        tooltip = analysis.relative(file),
        searchText = file.lowercase(),
        colorKey = colorKeyOf(id),
        // By the logarithm of its links, like the subsystems on the map
        // A hub with thousands of links at full scale covers the whole subsystem it sits in
        radius = GraphSizing.radiusOfCount(analysis.degreeOf.getValue(file) + 1),
      )
    }
    val neighbours = LinkedHashMap<Int, CanvasNode>()
    val seen = HashSet<Pair<String, String>>()
    val edges = ArrayList<CanvasEdge>()
    fun addEdge(from: String, to: String) {
      if (seen.add(if (from < to) from to to else to to from)) edges.add(CanvasEdge(from, to))
    }
    for (link in analysis.links) {
      if (!filter.passes(link)) continue
      val fromInside = link.from in inside
      val toInside = link.to in inside
      if (fromInside && toInside) {
        addEdge(link.from, link.to)
        continue
      }
      if (!fromInside && !toInside) continue
      val (own, other) = if (fromInside) link.from to link.to else link.to to link.from
      val otherId = analysis.communityOf.getValue(other)
      // A file of this subsystem past the size limit is not a neighbour, and a lone file has no subsystem to stand for it
      val neighbour = analysis.subsystemById[otherId]
      if (otherId == id || neighbour == null) continue
      neighbours.getOrPut(otherId) {
        CanvasNode(
          id = subsystemNodeId(otherId),
          label = neighbour.label,
          tooltip = neighbour.label + " — " + neighbour.files.size,
          searchText = neighbour.label.lowercase(),
          colorKey = colorKeyOf(otherId),
          radius = GraphSizing.radiusOfCount(neighbour.files.size),
          aggregate = true,
        )
      }
      addEdge(own, subsystemNodeId(otherId))
    }
    return SubsystemView(CanvasGraph(fileNodes + neighbours.values, edges), subsystem.files.size - files.size)
  }
}
