// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

/**
 * Force layout of a graph: three forces, one step, one function
 *
 * Why not rings by distance from an entry point: a real graph is two or three stars
 * A doc folder of 31 pages, 33 links and 25 nodes of degree 1 around two hubs lays out as an even ring around a hub
 * Because every leaf is equally far from it
 * Laying out by attraction instead of by depth lets the leaves push each other apart and spread around their hub
 *
 * Three forces, each closing its own failure:
 * - repulsion between every pair opens the clusters (without it everything sticks into one lump)
 * - springs along the edges keep the connected together (without them no cluster is visible)
 * - gravity to the centre keeps unconnected islands from flying off to infinity
 *
 * The class is PURE: no window, no timer, no drawing
 * Positions in, positions out; a step returns the total kinetic energy the caller uses to decide whether to go on
 * So the physics is tested without a screen and the rendering can be rewritten without touching it
 *
 * The coefficients were tuned on a working graph and are not to be tuned again
 */
class ForceLayout(private val graph: CanvasGraph) {
  /** Positions and velocities in the order of [CanvasGraph.nodes]; the index is the identity of a node */
  private val x = DoubleArray(graph.nodes.size)
  private val y = DoubleArray(graph.nodes.size)
  private val vx = DoubleArray(graph.nodes.size)
  private val vy = DoubleArray(graph.nodes.size)

  /** A pinned node is not moved by the simulation: a person is dragging it */
  private var pinned: Int = -1

  /** Steps since the layout was started or last disturbed; the cooling is counted from it */
  private var steps = 0

  /**
   * Mass is the number of springs on a node IN THIS PICTURE, plus one
   * Heavy nodes drift less, so the picture does not breathe around hubs
   * Mass by the size a node is drawn at would send a hub with hundreds of springs flying: its springs outweigh its mass
   */
  private val mass = DoubleArray(graph.nodes.size) { (graph.degrees[it] + 1).toDouble() }

  private val springs: List<Spring> = graph.links.map { link ->
    // The spring grows with the degree of the better connected end
    // 30 leaves on one node at one length push each other into a perfect circle at the very edge, the hedgehog
    val degree = maxOf(graph.degrees[link[0]], graph.degrees[link[1]])
    Spring(link[0], link[1], SPRING_LENGTH * (1 + log2(1.0 + degree) * SPRING_DEGREE_FACTOR))
  }

  private class Spring(val a: Int, val b: Int, val length: Double)

  init {
    seed()
  }

  /**
   * Seeded along a golden-angle spiral, not at random
   *
   * A random seed gives a different picture on every open, which can be neither tested nor recognised
   * The spiral spreads points evenly and identically on every run
   */
  private fun seed() {
    val golden = Math.PI * (3.0 - Math.sqrt(5.0))
    for (i in graph.nodes.indices) {
      val radius = SEED_STEP * Math.sqrt((i + 1).toDouble())
      val angle = i * golden
      x[i] = radius * Math.cos(angle)
      y[i] = radius * Math.sin(angle)
    }
  }

  fun positionX(i: Int): Double = x[i]
  fun positionY(i: Int): Double = y[i]

  fun pin(i: Int) { pinned = i }
  fun unpin() { pinned = -1 }

  /** Moves the pinned node where it is being dragged; whatever the layout had cooled to, it has to move the rest again */
  fun moveTo(i: Int, px: Double, py: Double) {
    x[i] = px
    y[i] = py
    vx[i] = 0.0
    vy[i] = 0.0
    steps = 0
  }

  /**
   * One step of the simulation
   *
   * @return total kinetic energy
   * Below [REST_ENERGY] the motion cannot be told by eye and the loop outside must stop
   * An eternal animation heats a laptop and jitters a picture that has already settled
   */
  fun step(): Double {
    val size = graph.nodes.size
    if (size == 0) return 0.0
    steps++
    val damping = DAMPING * cooling()
    val fx = DoubleArray(size)
    val fy = DoubleArray(size)

    // Repulsion of every pair, O(n^2): four hundred nodes are eighty thousand pairs a frame
    // Barnes-Hut is for when the corpus grows an order of magnitude
    for (i in 0 until size) {
      for (j in i + 1 until size) {
        var dx = x[i] - x[j]
        var dy = y[i] - y[j]
        var distance = Math.sqrt(dx * dx + dy * dy)
        if (distance < MIN_DISTANCE) {
          // Two points in one place give a division by zero and an infinite force
          // They are parted by their indices, deterministically, not at random
          dx = ((i - j) % 3 - 1).toDouble()
          dy = ((i + j) % 3 - 1).toDouble()
          distance = MIN_DISTANCE
        }
        val force = REPULSION / (distance * distance)
        val ux = dx / distance
        val uy = dy / distance
        fx[i] += ux * force; fy[i] += uy * force
        fx[j] -= ux * force; fy[j] -= uy * force
      }
    }

    for (spring in springs) {
      val dx = x[spring.b] - x[spring.a]
      val dy = y[spring.b] - y[spring.a]
      val distance = Math.sqrt(dx * dx + dy * dy).coerceAtLeast(MIN_DISTANCE)
      val force = (distance - spring.length) * SPRING_STRENGTH
      val ux = dx / distance
      val uy = dy / distance
      fx[spring.a] += ux * force; fy[spring.a] += uy * force
      fx[spring.b] -= ux * force; fy[spring.b] -= uy * force
    }

    for (i in 0 until size) {
      fx[i] -= x[i] * GRAVITY
      fy[i] -= y[i] * GRAVITY
    }

    var energy = 0.0
    for (i in 0 until size) {
      if (i == pinned) { vx[i] = 0.0; vy[i] = 0.0; continue }
      vx[i] = (vx[i] + fx[i] / mass[i]) * damping
      vy[i] = (vy[i] + fy[i] / mass[i]) * damping
      // A node cannot move further in one frame than a person can follow
      // Without the cap a light node squeezed between heavy ones is thrown out of the picture in one step
      val speed = Math.sqrt(vx[i] * vx[i] + vy[i] * vy[i])
      if (speed > MAX_SPEED) {
        vx[i] *= MAX_SPEED / speed
        vy[i] *= MAX_SPEED / speed
      }
      x[i] += vx[i]
      y[i] += vy[i]
      energy += mass[i] * (vx[i] * vx[i] + vy[i] * vy[i])
    }
    return energy
  }

  /**
   * Friction multiplier: 1 while the layout is young, then dying away until nothing moves
   *
   * A graph of a few hundred nodes around heavy hubs never settles by itself
   * Its energy falls, rises again and wanders for minutes, because crowded nodes keep kicking each other
   * And the loop that stops by energy then never stops: an eternal animation, the very thing the stop was made against
   * Every graph that settles on its own does so well before [COOL_AFTER_STEPS], so for those this is always 1
   */
  private fun cooling(): Double =
    if (steps <= COOL_AFTER_STEPS) 1.0 else Math.pow(COOLING_PER_STEP, (steps - COOL_AFTER_STEPS).toDouble())

  private fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)

  companion object {
    // Tuned on a working graph; change only with a measurement
    const val REPULSION = 9000.0
    const val SPRING_LENGTH = 70.0
    const val SPRING_STRENGTH = 0.02
    const val GRAVITY = 0.012
    const val DAMPING = 0.82

    /** Below this energy the motion cannot be told by eye, and the animation loop stops */
    const val REST_ENERGY = 0.28

    /** How much longer the spring is at a neighbour of a large degree */
    const val SPRING_DEGREE_FACTOR = 0.15

    /** Pixels per frame a node may move at most */
    const val MAX_SPEED = 40.0

    /** Ten seconds at sixty frames: past this the layout is cooled, whether it has settled or not */
    const val COOL_AFTER_STEPS = 600

    /** Each step past the limit leaves this share of the friction; a hundred steps take it to almost nothing */
    const val COOLING_PER_STEP = 0.97

    private const val SEED_STEP = 24.0
    private const val MIN_DISTANCE = 0.01
  }
}
