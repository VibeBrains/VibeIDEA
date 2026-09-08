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
    /**
     * Степень — число связей узла. Ею определяются и масса в раскладке, и радиус круга: узел, на
     * который сходится половина ссылок проекта, обязан отличаться от листа не только положением.
     */
    val degree: Int,
    /**
     * Верхняя папка документа (`knowledge`, `manuals`, пусто у корневых) — по ней берётся цвет.
     *
     * Именно папка, а не состояние: цвет отвечает на вопрос «из какой это части документации»,
     * который задают глазами, а состояние («битая», «недостижимый») называется отдельными цветами
     * и словами в дереве слева.
     */
    val category: String,
    val reachable: Boolean,
    val brokenLinks: Int,
  ) {
    /** Короткое имя для подписи: заголовок «Документация VibeReel» на графе не читается. */
    val name: String get() = path.substringAfterLast('/').removeSuffix(".md")
  }

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
    val layerOrder = byLayer.keys.sorted()
    val ringOf = layerOrder.withIndex().associate { (index, layer) -> layer to index }

    val edges = ordered.flatMap { doc ->
      doc.outgoing.filter { !it.broken && it.to in kept && it.to != doc.path }.map { Edge(doc.path, it.to) }
    }.distinct()

    // Степень считается по НЕОРИЕНТИРОВАННЫМ связям: для раскладки и для размера узла неважно,
    // кто на кого сослался, важно, сколько нитей его держит.
    val degree = HashMap<String, Int>()
    for (edge in edges) {
      degree[edge.from] = (degree[edge.from] ?: 0) + 1
      degree[edge.to] = (degree[edge.to] ?: 0) + 1
    }

    val nodes = ordered.map { doc ->
      val layer = depths[doc.path] ?: ORPHAN_LAYER
      Node(
        path = doc.path,
        title = doc.title,
        layer = layer,
        column = byLayer.getValue(layer).indexOf(doc),
        degree = degree[doc.path] ?: 0,
        category = categoryOf(doc.path, entryPoint),
        reachable = doc.path in analysis.reachable,
        brokenLinks = doc.outgoing.count { it.broken },
      )
    }

    // Размеров полотна больше нет: координаты считает [DocsForceLayout], и полотно у него своё —
    // оно меняется на каждом шаге симуляции, поэтому «ширина графа» перестала быть свойством
    // модели. Ноль здесь честнее выдуманного числа.
    return Graph(nodes, edges, 0, 0)
  }

  /**
   * Категория — верхняя папка ВНУТРИ папки документации.
   *
   * `docs/knowledge/ai/x.md` → `knowledge`, `docs/README.md` → пусто. Считается от пути входа,
   * потому что корень документации у каждого проекта свой, а «docs» в имени категории одинаков у
   * всех и цвет по нему ничего не различал бы.
   */
  fun categoryOf(path: String, entryPoint: String): String {
    val root = entryPoint.substringBeforeLast('/', "")
    val rest = if (root.isNotEmpty() && path.startsWith("$root/")) path.removePrefix("$root/") else path
    val slash = rest.indexOf('/')
    return if (slash < 0) "" else rest.substring(0, slash)
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
