// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Where each document sits when the documentation is drawn rather than listed.
 *
 * A list answers «что есть» and hides the only thing worth seeing: the SHAPE. A folder of thirty
 * files where four are orphans and one page links to everything looks, in a list, exactly like a
 * folder of thirty tidy files. Drawn by distance from the entry point, it does not.
 *
 * The layout is pure arithmetic — no Swing, no project, no I/O — because the placement is the whole
 * feature and it is what breaks silently: an overlap or a lost node is invisible in a screenshot
 * review and obvious in a test.
 */
object DocsGraphLayout {
  data class Node(
    val path: String,
    val title: String,
    /** Distance from the entry point in links; unreachable documents get [ORPHAN_LAYER]. */
    val layer: Int,
    val column: Int,
    val x: Int,
    val y: Int,
    val reachable: Boolean,
    val brokenLinks: Int,
  )

  /** An edge between two documents that both exist; a broken link is a property of its source. */
  data class Edge(val from: String, val to: String)

  data class Graph(val nodes: List<Node>, val edges: List<Edge>, val width: Int, val height: Int)

  /** Sizes in unscaled pixels; the view scales them for the current display. */
  const val NODE_WIDTH = 190
  const val NODE_HEIGHT = 34
  const val COLUMN_GAP = 24
  const val ROW_GAP = 56
  const val MARGIN = 20

  /** Orphans are drawn in their own band below everything reachable, not mixed into the tree. */
  const val ORPHAN_LAYER = Int.MAX_VALUE

  /**
   * A drawing bounded by a node limit.
   *
   * A three-hundred-page handbook drawn in full is a grey cloud, and a grey cloud tells less than
   * the list did. The limit keeps the nearest layers — the ones a reader actually walks — and the
   * caller says out loud how many were dropped rather than showing a picture that pretends to be
   * complete.
   */
  fun layout(analysis: DocsIndex.Analysis, entryPoint: String = DocsIndex.ENTRY_POINT, maxNodes: Int = 120): Graph {
    val depths = depths(analysis, entryPoint)
    val ordered = analysis.docs.sortedWith(
      compareBy({ depths[it.path] ?: ORPHAN_LAYER }, { it.path })
    ).take(maxNodes.coerceAtLeast(1))
    val kept = ordered.map { it.path }.toSet()

    val byLayer = ordered.groupBy { depths[it.path] ?: ORPHAN_LAYER }
    // Layers are numbered densely for drawing: an empty depth in the middle would leave a blank
    // band, and the orphan layer sits right below the last reachable one instead of at infinity.
    val layerOrder = byLayer.keys.sorted()
    val rowOf = layerOrder.withIndex().associate { (index, layer) -> layer to index }
    val widest = byLayer.values.maxOfOrNull { it.size } ?: 0
    val width = MARGIN * 2 + (widest.coerceAtLeast(1) * NODE_WIDTH) + ((widest - 1).coerceAtLeast(0) * COLUMN_GAP)

    val nodes = ArrayList<Node>(ordered.size)
    for (layer in layerOrder) {
      val row = byLayer.getValue(layer)
      val rowWidth = row.size * NODE_WIDTH + (row.size - 1).coerceAtLeast(0) * COLUMN_GAP
      val left = MARGIN + ((width - MARGIN * 2) - rowWidth) / 2
      row.forEachIndexed { column, doc ->
        nodes.add(
          Node(
            path = doc.path,
            title = doc.title,
            layer = layer,
            column = column,
            x = left + column * (NODE_WIDTH + COLUMN_GAP),
            y = MARGIN + rowOf.getValue(layer) * (NODE_HEIGHT + ROW_GAP),
            reachable = doc.path in analysis.reachable,
            brokenLinks = doc.outgoing.count { it.broken },
          )
        )
      }
    }

    val edges = ordered.flatMap { doc ->
      doc.outgoing.filter { !it.broken && it.to in kept && it.to != doc.path }.map { Edge(doc.path, it.to) }
    }.distinct()

    val height = MARGIN * 2 + layerOrder.size * NODE_HEIGHT + (layerOrder.size - 1).coerceAtLeast(0) * ROW_GAP
    return Graph(nodes, edges, width, height)
  }

  /** How many documents the drawing left out, so the panel can say so instead of implying nothing. */
  fun droppedCount(analysis: DocsIndex.Analysis, maxNodes: Int = 120): Int =
    (analysis.docs.size - maxNodes).coerceAtLeast(0)

  /**
   * Distance from the entry point in links.
   *
   * Breadth-first on purpose: the depth that matters is the SHORTEST path, because that is the one
   * a reader walks. A depth-first walk would place a page under whichever long chain happened to
   * reach it first and draw a tidy tree that nobody navigates.
   */
  fun depths(analysis: DocsIndex.Analysis, entryPoint: String = DocsIndex.ENTRY_POINT): Map<String, Int> {
    val byPath = analysis.docs.associateBy { it.path }
    val start = if (entryPoint in byPath) entryPoint else analysis.docs.firstOrNull()?.path ?: return emptyMap()
    val depths = LinkedHashMap<String, Int>()
    depths[start] = 0
    val queue = ArrayDeque(listOf(start))
    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      val depth = depths.getValue(current)
      for (link in byPath[current]?.outgoing.orEmpty()) {
        if (link.broken || link.to in depths) continue
        depths[link.to] = depth + 1
        queue.addLast(link.to)
      }
    }
    return depths
  }
}
