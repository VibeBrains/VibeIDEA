// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

/**
 * Where each document sits when the documentation is drawn rather than listed.
 *
 * A list answers «что есть» and hides the only thing worth seeing: the SHAPE. A folder of thirty
 * files where four are orphans and one page links to everything looks, in a list, exactly like a
 * folder of thirty tidy files. Drawn by distance from the entry point, it does not.
 *
 * Раскладка КОЛЬЦЕВАЯ, а не строками (08.09.2026, замечание владельца «это не граф, а таблички»).
 * Строки прямоугольников читаются как таблица: глаз ищет столбцы и порядок, которых в графе нет.
 * Кольца вокруг входа показывают то единственное, что здесь есть на самом деле, — расстояние от
 * входа: центр, первый круг соседей, второй, и сироты снаружи, вне всяких связей.
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
    /** Центр круга, а не левый верхний угол: узел — точка, и всё считается от неё. */
    val x: Int,
    val y: Int,
    /** Радиус круга: чем больше ссылок сходится в документе, тем он крупнее. */
    val radius: Int,
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

  /** Радиус обычного узла и прибавка за каждую входящую ссылку — крупнее там, куда чаще ведут. */
  const val NODE_RADIUS = 7
  const val RADIUS_PER_LINK = 1
  const val MAX_RADIUS = 16

  /** Расстояние между кольцами. Достаточное, чтобы подписи соседних колец не сталкивались. */
  const val RING_GAP = 150

  /** Сироты — своим кольцом снаружи, за последним достижимым: они ни с чем не связаны. */
  const val ORPHAN_RING_GAP = 90

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
    // Слои нумеруются плотно: пустая глубина в середине оставила бы пустое кольцо, а сироты
    // становятся кольцом сразу за последним достижимым, а не «на бесконечности».
    val layerOrder = byLayer.keys.sorted()
    val ringOf = layerOrder.withIndex().associate { (index, layer) -> layer to index }

    // Входящие ссылки считаются заранее: радиус узла — про то, сколько на него ссылаются, а не
    // про то, сколько ссылается он сам. Страница, на которую ведут все, и есть центр внимания.
    val incoming = HashMap<String, Int>()
    for (doc in ordered) {
      for (link in doc.outgoing) {
        if (!link.broken) incoming[link.to] = (incoming[link.to] ?: 0) + 1
      }
    }

    fun radiusOf(path: String): Int =
      (NODE_RADIUS + (incoming[path] ?: 0) * RADIUS_PER_LINK).coerceAtMost(MAX_RADIUS)

    // Радиус кольца растёт так, чтобы узлам на нём хватало места: у длины окружности есть предел,
    // и двадцать документов на первом кольце иначе слиплись бы в дугу.
    fun ringRadius(ring: Int, count: Int): Int {
      if (ring == 0) return 0
      val byGap = ring * RING_GAP
      val byCount = (count * (NODE_WIDTH / 2 + COLUMN_GAP) / (2 * Math.PI)).toInt()
      return maxOf(byGap, byCount)
    }

    val placed = ArrayList<Node>(ordered.size)
    var maxReach = 0
    for (layer in layerOrder) {
      val row = byLayer.getValue(layer)
      val ring = ringOf.getValue(layer)
      val radius = if (layer == ORPHAN_LAYER) {
        // Сироты — снаружи всего достижимого, чтобы их отдельность была видна, а не вычислялась.
        maxReach + ORPHAN_RING_GAP
      }
      else ringRadius(ring, row.size)
      maxReach = maxOf(maxReach, radius)
      row.forEachIndexed { column, doc ->
        // Угол считается от количества узлов НА ЭТОМ кольце: так они распределены ровно, а
        // соседние кольца не выстраиваются в спицы, которые глаз читает как связи.
        val angle = if (row.size <= 1) 0.0
                    else 2 * Math.PI * column / row.size + ring * ANGLE_OFFSET
        placed.add(
          Node(
            path = doc.path,
            title = doc.title,
            layer = layer,
            column = column,
            x = (radius * Math.cos(angle)).toInt(),
            y = (radius * Math.sin(angle)).toInt(),
            radius = radiusOf(doc.path),
            reachable = doc.path in analysis.reachable,
            brokenLinks = doc.outgoing.count { it.broken },
          )
        )
      }
    }

    // Координаты считались от центра; полотно — это их описанный прямоугольник плюс поле на
    // подпись под самым нижним узлом.
    val span = (placed.maxOfOrNull { maxOf(Math.abs(it.x), Math.abs(it.y)) } ?: 0) + NODE_WIDTH / 2 + MARGIN
    val nodes = placed.map { it.copy(x = it.x + span, y = it.y + span) }

    val edges = ordered.flatMap { doc ->
      doc.outgoing.filter { !it.broken && it.to in kept && it.to != doc.path }.map { Edge(doc.path, it.to) }
    }.distinct()

    val side = span * 2
    return Graph(nodes, edges, side, side)
  }

  /** Сдвиг угла на каждое следующее кольцо: без него узлы выстраиваются в спицы. */
  private const val ANGLE_OFFSET = 0.35

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
