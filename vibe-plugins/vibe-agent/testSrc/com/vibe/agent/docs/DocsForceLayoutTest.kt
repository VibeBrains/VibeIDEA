// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Физика раскладки: проверяется без окна — ради этого она и вынесена из рендера. */
class DocsForceLayoutTest {
  /** Звезда: хаб и листья — ровно та форма, на которой прошлая раскладка дала ежа. */
  private fun star(leaves: Int): DocsGraphLayout.Graph {
    val hub = node("docs/README.md", degree = leaves)
    val nodes = listOf(hub) + (1..leaves).map { node("docs/leaf$it.md", degree = 1) }
    val edges = (1..leaves).map { DocsGraphLayout.Edge("docs/README.md", "docs/leaf$it.md") }
    return DocsGraphLayout.Graph(nodes, edges, 0, 0)
  }

  private fun node(path: String, degree: Int) = DocsGraphLayout.Node(
    path = path, title = path, layer = 0, column = 0, degree = degree,
    category = "", reachable = true, brokenLinks = 0,
  )

  private fun settle(graph: DocsGraphLayout.Graph, steps: Int = 600): DocsForceLayout {
    val layout = DocsForceLayout(graph)
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
    val layout = DocsForceLayout(star(20))
    repeat(400) { layout.step() }
    assertTrue(layout.step() < DocsForceLayout.REST_ENERGY, "энергия обязана падать ниже порога остановки")
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
    val nodes = (1..5).map { node("docs/lonely$it.md", degree = 0) }
    val layout = settle(DocsGraphLayout.Graph(nodes, emptyList(), 0, 0))
    val farthest = nodes.indices.maxOf { Math.hypot(layout.positionX(it), layout.positionY(it)) }
    assertTrue(farthest < 5000, "несвязанные узлы улетели на $farthest — гравитация не работает")
  }

  @Test
  fun `приколотый узел симуляция не двигает`() {
    val layout = DocsForceLayout(star(10))
    layout.pin(0)
    layout.moveTo(0, 123.0, -45.0)
    repeat(50) { layout.step() }
    assertEquals(123.0, layout.positionX(0), 1e-9)
    assertEquals(-45.0, layout.positionY(0), 1e-9)
  }

  @Test
  fun `радиус растёт от степени и ограничен сверху`() {
    assertEquals(4.0, DocsForceLayout.radiusOf(0), 1e-9)
    assertTrue(DocsForceLayout.radiusOf(9) > DocsForceLayout.radiusOf(1))
    assertEquals(DocsForceLayout.MAX_RADIUS, DocsForceLayout.radiusOf(10_000), 1e-9)
  }

  @Test
  fun `пустой граф не роняет шаг`() {
    assertEquals(0.0, DocsForceLayout(DocsGraphLayout.Graph(emptyList(), emptyList(), 0, 0)).step(), 1e-9)
  }

  private companion object {
    /** Минимальный зазор: ближе круги перекрываются даже на крупном масштабе. */
    const val MIN_GAP = 12.0
  }
}
