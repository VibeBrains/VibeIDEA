// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

/**
 * One drawn node: everything the canvas needs and nothing about where the node came from
 *
 * The docs graph and the project graph share one canvas and one physics
 * So the canvas must not know what a category, a subsystem or a file is
 * The owner decides colour and size and hands the result over
 */
data class CanvasNode(
  val id: String,
  /** Caption under the circle */
  val label: String,
  /** Hover text */
  val tooltip: String,
  /** Lower-cased text the search box is matched against */
  val searchText: String,
  /** Resolved to a colour by the owner of the canvas */
  val colorKey: String,
  val radius: Double,
  /** Stands for a group of things among single ones (a subsystem among files): drawn with an outer ring */
  val aggregate: Boolean = false,
)

data class CanvasEdge(val from: String, val to: String)

/**
 * What the canvas draws
 *
 * The degree of a node is counted here, from the edges of THIS picture, never taken from the outside
 * A file linked to four hundred others in the project but to three of them in an opened subsystem pulls three springs
 * And the mass that holds it has to match the springs that pull on it
 */
class CanvasGraph(
  val nodes: List<CanvasNode>,
  edges: List<CanvasEdge>,
  /** Scale from which every node is captioned; below it only the best connected ones are */
  val labelsFromScale: Double = DEFAULT_LABELS_FROM_SCALE,
) {
  /** Edges between two distinct nodes of this picture, as given: a spring needs both ends and a length */
  val edges: List<CanvasEdge> = run {
    val known = nodes.mapTo(HashSet()) { it.id }
    edges.filter { it.from != it.to && it.from in known && it.to in known }
  }

  private val indexById: Map<String, Int> = nodes.withIndex().associate { (i, node) -> node.id to i }

  /** The same edges as pairs of node positions, so the hot loops never touch a string */
  val links: List<IntArray> = this.edges.map { intArrayOf(indexById.getValue(it.from), indexById.getValue(it.to)) }

  /** Springs on each node, by node position */
  val degrees: IntArray = IntArray(nodes.size).also { degree ->
    for (link in links) {
      degree[link[0]]++
      degree[link[1]]++
    }
  }

  fun indexOf(id: String): Int = indexById[id] ?: -1

  /** Positions of the nodes whose search text holds the query; an empty query matches none, so nothing is dimmed */
  fun matching(query: String): Set<Int> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptySet()
    return nodes.withIndex().filter { (_, node) -> node.searchText.contains(needle) }.mapTo(HashSet()) { it.index }
  }

  override fun equals(other: Any?): Boolean =
    other is CanvasGraph && nodes == other.nodes && edges == other.edges && labelsFromScale == other.labelsFromScale

  override fun hashCode(): Int = 31 * (31 * nodes.hashCode() + edges.hashCode()) + labelsFromScale.hashCode()

  companion object {
    val EMPTY = CanvasGraph(emptyList(), emptyList())

    /** Every node is captioned from this scale up; the docs graph was tuned on it */
    const val DEFAULT_LABELS_FROM_SCALE = 0.75
  }
}

/** How big a circle is drawn: by connections for a graph of single things, by the logarithm of the count for groups */
object GraphSizing {
  /** The smallest circle, and the largest one a hub or a group may grow to */
  const val MIN_RADIUS = 4.0
  const val MAX_RADIUS = 22.0

  private const val RADIUS_PER_ROOT_LINK = 2.5
  private const val RADIUS_PER_DOUBLING = 1.6

  /** Radius by degree: the difference between a leaf and a hub shows, but a hub does not take half the screen */
  fun radiusOf(degree: Int): Double =
    (MIN_RADIUS + Math.sqrt(degree.toDouble()) * RADIUS_PER_ROOT_LINK).coerceAtMost(MAX_RADIUS)

  /**
   * Radius by the logarithm of how many things a group holds
   *
   * Linear in the count, a subsystem of 1800 files covers the map and its neighbours vanish under it
   * The count itself stays in the hover text and the report
   */
  fun radiusOfCount(count: Int): Double =
    (MIN_RADIUS + log2(count.coerceAtLeast(1).toDouble()) * RADIUS_PER_DOUBLING).coerceAtMost(MAX_RADIUS)

  private fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)
}
