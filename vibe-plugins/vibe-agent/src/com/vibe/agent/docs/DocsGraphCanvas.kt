// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.docs

import com.intellij.ui.JBColor
import com.vibe.agent.graphview.CanvasEdge
import com.vibe.agent.graphview.CanvasGraph
import com.vibe.agent.graphview.CanvasNode
import com.vibe.agent.graphview.GraphSizing
import java.awt.Color

/**
 * The docs graph in the terms of the shared canvas
 *
 * The canvas does not know what a document is: here a document becomes a node, and its state and folder become a colour key
 */
object DocsGraphCanvas {
  const val ORPHAN = "orphan"
  const val BROKEN = "broken"
  private const val CATEGORY_PREFIX = "category:"

  fun toCanvas(graph: DocsGraphLayout.Graph): CanvasGraph = CanvasGraph(
    nodes = graph.nodes.map { node ->
      CanvasNode(
        id = node.path,
        label = node.name,
        tooltip = node.title + "   " + node.path,
        // A line break between the two, so a query cannot match across the seam of title and path
        searchText = (node.title + "\n" + node.path).lowercase(),
        colorKey = colorKeyOf(node),
        radius = GraphSizing.radiusOf(node.degree),
      )
    },
    edges = graph.edges.map { CanvasEdge(it.from, it.to) },
  )

  /**
   * State outweighs membership: a broken link and a lost document are what eyes look for first
   * It is for their sake that the graph is opened most often
   */
  fun colorKeyOf(node: DocsGraphLayout.Node): String = when {
    !node.reachable -> ORPHAN
    node.brokenLinks > 0 -> BROKEN
    else -> CATEGORY_PREFIX + node.category
  }

  fun colorOf(colorKey: String): Color = when {
    colorKey == ORPHAN -> ORPHAN_FILL
    colorKey == BROKEN -> BROKEN_FILL
    else -> DocsGraphPalette.colorOf(colorKey.removePrefix(CATEGORY_PREFIX))
  }

  private val ORPHAN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.orphanNode", JBColor(0xC27D04, 0xD6AE58))
  private val BROKEN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.brokenNode", JBColor(0xDB3B4B, 0xDB5C5C))
}
