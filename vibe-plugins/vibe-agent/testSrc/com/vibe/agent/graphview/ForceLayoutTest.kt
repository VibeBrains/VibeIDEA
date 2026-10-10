// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Физика раскладки: проверяется без окна — ради этого она и вынесена из рендера. */
class ForceLayoutTest {
  /** Звезда: хаб и листья — ровно та форма, на которой прошлая раскладка дала ежа. */
  private fun star(leaves: Int): CanvasGraph {
    val nodes = listOf(node("docs/README.md")) + (1..leaves).map { node("docs/leaf$it.md") }
    val edges = (1..leaves).map { CanvasEdge("docs/README.md", "docs/leaf$it.md") }
    return CanvasGraph(nodes, edges)
  }

  private fun node(id: String) = CanvasNode(
    id = id, label = id, tooltip = id, searchText = id, colorKey = "", radius = GraphSizing.MIN_RADIUS,
  )

  private fun settle(graph: CanvasGraph, steps: Int = 600): ForceLayout {
    val layout = ForceLayout(graph)
    repeat(steps) { layout.step() }
    return layout
  }

  @Test
  fun `посев детерминированный — две раскладки одного графа совпадают`() {
    // Со случайным посевом каждое открытие даёт другую картинку, и проверить её нельзя ничем.
    val one = settle(star(12), steps = 50)
    val two = settle(star(12), steps = 50)
    for (i in 0..12) {
      assertEquals(one.positionX(i), two.positionX(i), 1e-9)
      assertEquals(one.positionY(i), two.positionY(i), 1e-9)
    }
  }

  @Test
  fun `движение затухает и цикл может остановиться`() {
    // Без затухания и порога картинка дрожит вечно и греет ноутбук.
    val layout = ForceLayout(star(20))
    repeat(400) { layout.step() }
    assertTrue(layout.step() < ForceLayout.REST_ENERGY, "энергия обязана падать ниже порога остановки")
  }

  @Test
  fun `узлы не садятся друг на друга`() {
    val graph = star(20)
    val layout = settle(graph)
    for (i in graph.nodes.indices) {
      for (j in i + 1 until graph.nodes.size) {
        val distance = Math.hypot(layout.positionX(i) - layout.positionX(j), layout.positionY(i) - layout.positionY(j))
        assertTrue(distance > MIN_GAP, "узлы $i и $j слиплись: расстояние $distance")
      }
    }
  }

  @Test
  fun `листья звезды не выстраиваются в идеальное кольцо`() {
    // Ровное кольцо вокруг хаба — тот самый ёж: расстояния от хаба обязаны различаться.
    val graph = star(20)
    val layout = settle(graph)
    val distances = (1..20).map { Math.hypot(layout.positionX(it) - layout.positionX(0), layout.positionY(it) - layout.positionY(0)) }
    val spread = (distances.max() - distances.min()) / distances.average()
    assertTrue(spread > 0.05, "разброс расстояний до хаба $spread — это кольцо, а не раскладка")
  }

  @Test
  fun `остров не улетает в бесконечность`() {
    // Гравитация к центру существует ровно ради несвязанных документов.
    val nodes = (1..5).map { node("docs/lonely$it.md") }
    val layout = settle(CanvasGraph(nodes, emptyList()))
    val farthest = nodes.indices.maxOf { Math.hypot(layout.positionX(it), layout.positionY(it)) }
    assertTrue(farthest < 5000, "несвязанные узлы улетели на $farthest — гравитация не работает")
  }

  @Test
  fun `приколотый узел симуляция не двигает`() {
    val layout = ForceLayout(star(10))
    layout.pin(0)
    layout.moveTo(0, 123.0, -45.0)
    repeat(50) { layout.step() }
    assertEquals(123.0, layout.positionX(0), 1e-9)
    assertEquals(-45.0, layout.positionY(0), 1e-9)
  }

  @Test
  fun `радиус растёт от степени и ограничен сверху`() {
    assertEquals(4.0, GraphSizing.radiusOf(0), 1e-9)
    assertTrue(GraphSizing.radiusOf(9) > GraphSizing.radiusOf(1))
    assertEquals(GraphSizing.MAX_RADIUS, GraphSizing.radiusOf(10_000), 1e-9)
  }

  @Test
  fun `пустой граф не роняет шаг`() {
    assertEquals(0.0, ForceLayout(CanvasGraph.EMPTY).step(), 1e-9)
  }

  @Test
  fun `a hub with hundreds of springs stays on the canvas and never outruns the speed cap`() {
    // Mass counts the springs of THIS picture and the speed per frame is capped: a hub of four hundred files used to fly apart
    val graph = star(400)
    val layout = ForceLayout(graph)
    val previous = DoubleArray(graph.nodes.size * 2) { if (it % 2 == 0) layout.positionX(it / 2) else layout.positionY(it / 2) }
    var fastest = 0.0
    repeat(STEPS_ON_A_BIG_HUB) {
      layout.step()
      for (i in graph.nodes.indices) {
        val dx = layout.positionX(i) - previous[i * 2]
        val dy = layout.positionY(i) - previous[i * 2 + 1]
        fastest = maxOf(fastest, Math.sqrt(dx * dx + dy * dy))
        previous[i * 2] = layout.positionX(i)
        previous[i * 2 + 1] = layout.positionY(i)
      }
    }
    assertTrue(fastest <= ForceLayout.MAX_SPEED + 1e-6, "a node moved $fastest pixels in one frame")
    for (i in graph.nodes.indices) {
      val x = layout.positionX(i)
      val y = layout.positionY(i)
      assertTrue(x.isFinite() && y.isFinite(), "node $i left the number line")
      assertTrue(Math.hypot(x, y) < BIG_HUB_REACH, "node $i flew to ${Math.hypot(x, y)}")
    }
  }

  @Test
  fun `a big hub settles into a ring wider than a small one but not by orders of magnitude`() {
    val small = settle(star(20))
    val big = settle(star(400), steps = STEPS_ON_A_BIG_HUB)
    val reachOf = { layout: ForceLayout, count: Int -> (1..count).maxOf { Math.hypot(layout.positionX(it), layout.positionY(it)) } }
    val ratio = reachOf(big, 400) / reachOf(small, 20)
    assertTrue(ratio in 1.0..20.0, "the reach of 400 leaves is $ratio times the reach of 20")
  }

  @Test
  fun `a layout that never settles by itself is cooled until the loop can stop`() {
    // Hundreds of nodes around a hub keep kicking each other: without the cooling their energy wanders for minutes
    val layout = ForceLayout(star(400))
    repeat(ForceLayout.COOL_AFTER_STEPS + COOLING_STEPS) { layout.step() }
    assertTrue(layout.step() < ForceLayout.REST_ENERGY, "the energy of a big hub is still ${layout.step()} after the cooling")
  }

  @Test
  fun `a graph that settles on its own is not touched by the cooling`() {
    // The star of twenty settles long before the cooling begins, so the cooled and the plain layout are the same layout
    val one = settle(star(20), steps = ForceLayout.COOL_AFTER_STEPS)
    val two = ForceLayout(star(20))
    repeat(ForceLayout.COOL_AFTER_STEPS) { two.step() }
    for (i in 0..20) {
      assertEquals(one.positionX(i), two.positionX(i), 1e-9)
      assertEquals(one.positionY(i), two.positionY(i), 1e-9)
    }
    assertTrue(two.step() < ForceLayout.REST_ENERGY)
  }

  @Test
  fun `dragging a node after the cooling wakes the layout up again`() {
    val layout = ForceLayout(star(10))
    repeat(ForceLayout.COOL_AFTER_STEPS + COOLING_STEPS) { layout.step() }
    layout.pin(0)
    layout.moveTo(0, 500.0, 500.0)
    assertTrue(layout.step() > ForceLayout.REST_ENERGY, "a dragged hub must pull its leaves after it, not find them frozen")
  }

  private companion object {
    /** Минимальный зазор: ближе круги перекрываются даже на крупном масштабе. */
    const val MIN_GAP = 12.0

    /** Frames a graph of hundreds of nodes needs to settle at sixty a second: about five seconds */
    const val STEPS_ON_A_BIG_HUB = 300

    /** Steps after the cooling starts in which it is certain to have stopped everything: 0.97 to the hundredth is under a twentieth */
    const val COOLING_STEPS = 150

    /** A ring of four hundred leaves at the spring length of such a hub is a few hundred pixels across; this is far past it */
    const val BIG_HUB_REACH = 5000.0
  }
}
