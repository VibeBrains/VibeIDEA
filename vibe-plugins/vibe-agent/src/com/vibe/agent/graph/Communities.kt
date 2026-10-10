// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graph

/**
 * Subsystems of a project: groups of files that talk to each other more than to the rest
 *
 * Leiden over modularity, deterministic: every place the paper draws at random takes the best move instead
 * So the same repository gives the same picture on every open and the result can be tested
 * Leiden rather than Louvain for one property: Louvain can leave a community whose files do not connect at all
 * And a subsystem drawn as one colour that falls apart into strangers is a lie the picture tells
 *
 * A port of VibeIDE `communities.ts`, step for step: the shared vectors (`testVectors/codeGraphCommunities.json`) hold both to one result
 * Pure: an undirected weighted graph in, a partition out, no paths and no I/O
 */
object Communities {
  /** One undirected link between two nodes; parallel links add up */
  data class WeightedLink(val a: Int, val b: Int, val weight: Double)

  /** Above 1 favours smaller groups, below 1 larger ones; 1 is plain modularity */
  const val DEFAULT_RESOLUTION = 1.0

  /** A safety stop: real graphs converge in a handful of levels */
  private const val MAX_LEVELS = 32

  private val CANONICAL_ORDER: Comparator<WeightedLink> =
    compareBy<WeightedLink>({ minOf(it.a, it.b) }, { maxOf(it.a, it.b) }, { it.a }, { it.weight })

  /** A move must beat the node staying put by more than rounding noise, or ties would flip on the last digit */
  private const val GAIN_EPSILON = 1e-12

  private class Graph(
    val size: Int,
    /** Neighbours with summed weights, self-loops kept: they carry the weight folded into an aggregated node */
    val adjacency: List<MutableMap<Int, Double>>,
    /** Weighted degree of each node, self-loops counted twice as modularity defines it */
    val degree: DoubleArray,
    /** Sum of all degrees, twice the total edge weight */
    val total: Double,
  )

  private fun graphOf(size: Int, links: List<WeightedLink>): Graph {
    val adjacency = List(size) { LinkedHashMap<Int, Double>() }
    val degree = DoubleArray(size)
    var total = 0.0
    for ((a, b, weight) in links) {
      if (weight <= 0 || a < 0 || b < 0 || a >= size || b >= size) continue
      adjacency[a][b] = (adjacency[a][b] ?: 0.0) + weight
      if (a != b) adjacency[b][a] = (adjacency[b][a] ?: 0.0) + weight
      degree[a] += weight
      degree[b] += weight
      total += 2 * weight
    }
    return Graph(size, adjacency, degree, total)
  }

  /** Weight from `node` into each community it touches, its own loop excluded */
  private fun weightsToCommunities(graph: Graph, node: Int, membership: IntArray): MutableMap<Int, Double> {
    val out = LinkedHashMap<Int, Double>()
    for ((neighbour, weight) in graph.adjacency[node]) {
      if (neighbour == node) continue
      val community = membership[neighbour]
      out[community] = (out[community] ?: 0.0) + weight
    }
    return out
  }

  private fun communityDegrees(graph: Graph, membership: IntArray): MutableMap<Int, Double> {
    val degrees = HashMap<Int, Double>()
    for (node in 0 until graph.size) degrees[membership[node]] = (degrees[membership[node]] ?: 0.0) + graph.degree[node]
    return degrees
  }

  /**
   * Leiden's fast local moving: a queue instead of sweeps, and a node is revisited only when a neighbour left its side
   * Ties keep the node where it is, then go to the lowest community id, so the outcome does not depend on map order
   */
  private fun moveNodes(graph: Graph, membership: IntArray, resolution: Double) {
    val communityDegree = communityDegrees(graph, membership)
    val queue = ArrayList<Int>(graph.size).apply { for (i in 0 until graph.size) add(i) }
    val queued = BooleanArray(graph.size) { true }
    var head = 0
    while (head < queue.size) {
      val node = queue[head++]
      queued[node] = false
      val own = membership[node]
      val degree = graph.degree[node]
      val towards = weightsToCommunities(graph, node, membership)
      // The node is taken out first, so staying is measured on the same footing as leaving
      communityDegree[own] = communityDegree.getValue(own) - degree
      fun gainOf(community: Int): Double =
        (towards[community] ?: 0.0) - resolution * degree * (communityDegree[community] ?: 0.0) / graph.total
      var best = own
      var bestGain = gainOf(own)
      for (community in towards.keys.sorted()) {
        val gain = gainOf(community)
        if (gain > bestGain + GAIN_EPSILON) {
          best = community
          bestGain = gain
        }
      }
      communityDegree[best] = (communityDegree[best] ?: 0.0) + degree
      if (best == own) continue
      membership[node] = best
      for (neighbour in graph.adjacency[node].keys) {
        if (!queued[neighbour] && membership[neighbour] != best) {
          queued[neighbour] = true
          queue.add(neighbour)
        }
      }
    }
  }

  /**
   * Leiden's refinement: inside each community, start from singletons and merge only into parts that stay well connected
   * This is what guarantees that every community is connected; the paper picks the merge at random, we take the best one
   */
  private fun refine(graph: Graph, membership: IntArray, resolution: Double): IntArray {
    val refined = IntArray(graph.size) { it }
    val communityDegree = communityDegrees(graph, membership)
    val partDegree = HashMap<Int, Double>().apply { for (node in 0 until graph.size) put(node, graph.degree[node]) }
    val partSize = HashMap<Int, Int>().apply { for (node in 0 until graph.size) put(node, 1) }
    // Weight from a refined part to the rest of its community: the well-connectedness test reads it
    val partOutside = HashMap<Int, Double>()
    for (node in 0 until graph.size) {
      var outside = 0.0
      for ((neighbour, weight) in graph.adjacency[node]) {
        if (neighbour != node && membership[neighbour] == membership[node]) outside += weight
      }
      partOutside[node] = outside
    }
    fun wellConnected(inside: Double, degree: Double, community: Int): Boolean =
      inside >= resolution * degree * (communityDegree.getValue(community) - degree) / graph.total

    for (node in 0 until graph.size) {
      val community = membership[node]
      // Only a node still alone moves, and only if it belongs in its community at all
      if (partSize[refined[node]] != 1 || !wellConnected(partOutside.getValue(node), graph.degree[node], community)) continue
      val towards = LinkedHashMap<Int, Double>()
      for ((neighbour, weight) in graph.adjacency[node]) {
        if (neighbour != node && membership[neighbour] == community) {
          towards[refined[neighbour]] = (towards[refined[neighbour]] ?: 0.0) + weight
        }
      }
      val degree = graph.degree[node]
      var best = refined[node]
      var bestGain = 0.0
      for (part in towards.keys.sorted()) {
        if (part == refined[node] || !wellConnected(partOutside.getValue(part), partDegree.getValue(part), community)) continue
        val gain = towards.getValue(part) - resolution * degree * partDegree.getValue(part) / graph.total
        if (gain > bestGain + GAIN_EPSILON) {
          best = part
          bestGain = gain
        }
      }
      if (best == refined[node]) continue
      val from = refined[node]
      refined[node] = best
      partDegree[best] = partDegree.getValue(best) + degree
      partSize[best] = partSize.getValue(best) + 1
      partSize[from] = 0
      // Links between the node and the part become internal; its other links inside the community join the part's outside
      val between = towards.getValue(best)
      partOutside[best] = partOutside.getValue(best) - between + (partOutside.getValue(node) - between)
    }
    return refined
  }

  /** Renumbers ids densely in order of first appearance, so the result does not carry internal numbering */
  private fun compact(ids: IntArray): IntArray {
    val renumber = HashMap<Int, Int>()
    return IntArray(ids.size) { renumber.getOrPut(ids[it]) { renumber.size } }
  }

  /**
   * Splits `size` nodes into communities by Leiden over modularity
   *
   * Returns the community of each node, numbered from 0 by size, the largest first, ties by first node
   * Colour and order then follow importance, and the numbering is stable for the same input
   *
   * The links are put in a canonical order first: the order the queue walks the nodes in follows the order the links arrive in
   * And a result that changes with the order of a list is not a property of the graph
   */
  fun detect(size: Int, links: List<WeightedLink>, resolution: Double = DEFAULT_RESOLUTION): IntArray {
    if (size == 0) return IntArray(0)
    var graph = graphOf(size, links.sortedWith(CANONICAL_ORDER))
    if (graph.total == 0.0) return IntArray(size) { it }
    // Each original node's position in the current, possibly aggregated, graph
    var nodeOf = IntArray(size) { it }
    var membership = IntArray(graph.size) { it }

    for (level in 0 until MAX_LEVELS) {
      moveNodes(graph, membership, resolution)
      membership = compact(membership)
      // Every community is one node: nothing coarser is left to find
      if (membership.toSet().size == graph.size) break
      val refined = compact(refine(graph, membership, resolution))
      val partCount = refined.toSet().size
      // Refinement merged nothing, so aggregating would give the same graph back and loop
      if (partCount == graph.size) break
      // The aggregate graph has one node per refined part; it starts in the community its part came from
      val communityOfPart = IntArray(partCount)
      for (node in 0 until graph.size) communityOfPart[refined[node]] = membership[node]
      val aggregateLinks = ArrayList<WeightedLink>()
      for (node in 0 until graph.size) {
        for ((neighbour, weight) in graph.adjacency[node]) {
          // Each undirected link is seen from both ends; keep one, and loops once
          if (neighbour < node) continue
          aggregateLinks.add(WeightedLink(refined[node], refined[neighbour], weight))
        }
      }
      nodeOf = IntArray(size) { refined[nodeOf[it]] }
      graph = graphOf(partCount, aggregateLinks)
      membership = communityOfPart
    }

    return rankBySize(IntArray(size) { membership[nodeOf[it]] })
  }

  /** Community ids from 0, the largest first; equal sizes keep the order of their first node */
  private fun rankBySize(raw: IntArray): IntArray {
    val sizes = HashMap<Int, Int>()
    val first = HashMap<Int, Int>()
    raw.forEachIndexed { node, community ->
      sizes[community] = (sizes[community] ?: 0) + 1
      first.putIfAbsent(community, node)
    }
    val order = sizes.keys.sortedWith(compareBy({ -sizes.getValue(it) }, { first.getValue(it) }))
    val rank = order.withIndex().associate { (index, community) -> community to index }
    return IntArray(raw.size) { rank.getValue(raw[it]) }
  }

  /** Modularity of a partition; the test measures that the detection improves on trivial splits */
  fun modularity(size: Int, links: List<WeightedLink>, membership: IntArray, resolution: Double = DEFAULT_RESOLUTION): Double {
    val graph = graphOf(size, links)
    if (graph.total == 0.0) return 0.0
    val inside = HashMap<Int, Double>()
    val degree = HashMap<Int, Double>()
    for (node in 0 until size) {
      val community = membership[node]
      degree[community] = (degree[community] ?: 0.0) + graph.degree[node]
      for ((neighbour, weight) in graph.adjacency[node]) {
        if (membership[neighbour] == community) {
          // A loop is stored once and counts twice; any other link is met from both ends
          inside[community] = (inside[community] ?: 0.0) + (if (neighbour == node) 2 * weight else weight)
        }
      }
    }
    var q = 0.0
    for ((community, sum) in degree) {
      val share = sum / graph.total
      q += (inside[community] ?: 0.0) / graph.total - resolution * share * share
    }
    return q
  }
}
