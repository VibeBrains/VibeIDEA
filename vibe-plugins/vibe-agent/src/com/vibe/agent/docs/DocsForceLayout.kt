// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Силовая раскладка графа документов: три силы, один шаг — одна функция.
 *
 * Почему не кольца (и почему прошлая попытка дала ежа): у реальной документации граф — это две-три
 * звезды. На проекте владельца 31 документ, 33 ссылки и 25 узлов степени 1 при двух хабах; любая
 * раскладка «по расстоянию от входа» выложит такое ровным кольцом вокруг хаба, потому что
 * расстояние у всех одинаковое. Раскладывать надо не по глубине, а по притяжению: тогда листья
 * расталкиваются между собой и распределяются вокруг своего хаба сами.
 *
 * Три силы, и каждая закрывает свой отказ:
 * - **отталкивание** каждой пары — раскрывает кластеры (без него всё слипается в комок);
 * - **пружины** по рёбрам — связанное держится вместе (без них кластеров не видно);
 * - **гравитация** к центру — без неё несвязанные острова улетают в бесконечность.
 *
 * Класс ЧИСТЫЙ: ни окна, ни таймера, ни рисования. На вход позиции, на выход позиции; шаг
 * возвращает суммарную кинетическую энергию, по которой снаружи решают, продолжать ли цикл. Так
 * физику можно проверить тестом, а рендер переписать, не трогая физику.
 *
 * Коэффициенты выверены на рабочем графе и подбору заново не подлежат.
 */
class DocsForceLayout(private val graph: DocsGraphLayout.Graph) {
  /** Позиции и скорости в порядке [DocsGraphLayout.Graph.nodes]; индекс — тождество узла. */
  private val x = DoubleArray(graph.nodes.size)
  private val y = DoubleArray(graph.nodes.size)
  private val vx = DoubleArray(graph.nodes.size)
  private val vy = DoubleArray(graph.nodes.size)

  /** Приколотый узел симуляция не двигает: его тащит человек. */
  private var pinned: Int = -1

  private val index = graph.nodes.withIndex().associate { (i, node) -> node.path to i }

  /** Масса — степень: тяжёлые дрейфуют меньше, и картинка не «дышит» вокруг хабов. */
  private val mass = DoubleArray(graph.nodes.size) { (graph.nodes[it].degree + 1).toDouble() }

  private val springs: List<Triple<Int, Int, Double>> = graph.edges.mapNotNull { edge ->
    val a = index[edge.from] ?: return@mapNotNull null
    val b = index[edge.to] ?: return@mapNotNull null
    // Длина пружины растёт от степени соседа: 30 листьев на одном узле при одинаковой длине
    // выталкивают друг друга в идеальную окружность у самого края — тот самый ёж.
    val degree = maxOf(graph.nodes[a].degree, graph.nodes[b].degree)
    Triple(a, b, SPRING_LENGTH * (1 + log2(1.0 + degree) * SPRING_DEGREE_FACTOR))
  }

  init {
    seed()
  }

  /**
   * Посев по золотому углу — спиралью, а не случайностью.
   *
   * Со случайным посевом каждое открытие даёт другую картинку: её нельзя ни протестировать, ни
   * узнать. Спираль по золотому углу распределяет точки равномерно и одинаково при каждом запуске.
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

  /** Двигает приколотый узел туда, куда его тащат. */
  fun moveTo(i: Int, px: Double, py: Double) {
    x[i] = px
    y[i] = py
    vx[i] = 0.0
    vy[i] = 0.0
  }

  /**
   * Один шаг симуляции.
   *
   * @return суммарная кинетическая энергия. Ниже [REST_ENERGY] движение не различимо глазом, и
   * цикл снаружи обязан остановиться: вечная анимация греет ноутбук и дёргает картинку.
   */
  fun step(): Double {
    val size = graph.nodes.size
    if (size == 0) return 0.0
    val fx = DoubleArray(size)
    val fy = DoubleArray(size)

    // Отталкивание каждой пары. O(n²) — этого хватает до пятисот узлов (двести узлов ≈ 20 тысяч
    // пар за кадр); Barnes-Hut вводить только если корпус вырастет на порядок.
    for (i in 0 until size) {
      for (j in i + 1 until size) {
        var dx = x[i] - x[j]
        var dy = y[i] - y[j]
        var distance = Math.hypot(dx, dy)
        if (distance < MIN_DISTANCE) {
          // Две точки в одном месте дают деление на ноль и бесконечную силу: разводим их
          // детерминированно, по индексам, а не случайно.
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

    for ((a, b, length) in springs) {
      val dx = x[b] - x[a]
      val dy = y[b] - y[a]
      val distance = Math.hypot(dx, dy).coerceAtLeast(MIN_DISTANCE)
      val force = (distance - length) * SPRING_STRENGTH
      val ux = dx / distance
      val uy = dy / distance
      fx[a] += ux * force; fy[a] += uy * force
      fx[b] -= ux * force; fy[b] -= uy * force
    }

    for (i in 0 until size) {
      fx[i] -= x[i] * GRAVITY
      fy[i] -= y[i] * GRAVITY
    }

    var energy = 0.0
    for (i in 0 until size) {
      if (i == pinned) { vx[i] = 0.0; vy[i] = 0.0; continue }
      vx[i] = (vx[i] + fx[i] / mass[i]) * DAMPING
      vy[i] = (vy[i] + fy[i] / mass[i]) * DAMPING
      x[i] += vx[i]
      y[i] += vy[i]
      energy += mass[i] * (vx[i] * vx[i] + vy[i] * vy[i])
    }
    return energy
  }

  private fun log2(value: Double): Double = Math.log(value) / Math.log(2.0)

  companion object {
    // Выверено на рабочем графе; менять только с замером.
    const val REPULSION = 9000.0
    const val SPRING_LENGTH = 70.0
    const val SPRING_STRENGTH = 0.02
    const val GRAVITY = 0.012
    const val DAMPING = 0.82

    /** Ниже этой энергии движение не различимо глазом — цикл анимации встаёт. */
    const val REST_ENERGY = 0.28

    /** Насколько длиннее пружина у соседа большой степени. */
    const val SPRING_DEGREE_FACTOR = 0.15

    private const val SEED_STEP = 24.0
    private const val MIN_DISTANCE = 0.01

    /** Радиус круга по степени: разница видна, но хаб не занимает пол-экрана. */
    const val MAX_RADIUS = 22.0

    fun radiusOf(degree: Int): Double =
      (4.0 + Math.sqrt(degree.toDouble()) * 2.5).coerceAtMost(MAX_RADIUS)
  }
}
