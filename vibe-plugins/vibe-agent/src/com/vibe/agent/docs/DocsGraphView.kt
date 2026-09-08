// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Рисование графа: холст, цикл кадров, область просмотра и мышь.
 *
 * Физики здесь нет ни строки — она в [DocsForceLayout], и это разделение главное: считать силы без
 * окна можно в тесте, а рендер переписать, не трогая проверенный счёт.
 *
 * Цикл останавливается по энергии (см. [DocsForceLayout.REST_ENERGY]): вечная анимация греет
 * ноутбук и дёргает картинку, которая уже сложилась.
 */
class DocsGraphView(private val onOpen: (String) -> Unit) : JComponent() {
  private var graph: DocsGraphLayout.Graph = DocsGraphLayout.Graph(emptyList(), emptyList(), 0, 0)
  private var layout: DocsForceLayout? = null
  private var hovered: Int = -1

  private var scale = 1.0
  private var offsetX = 0
  private var offsetY = 0
  private var fitted = false

  /** Откуда начали тащить — фон или узел; порог в пикселях отличает клик от перетаскивания. */
  private var pressAt: Point? = null
  private var dragging = false
  private var draggedNode = -1

  private var highlighted: Set<String> = emptySet()

  /** Кадры симуляции. Таймер, а не поток: рисование живёт на EDT, и считать надо там же. */
  private val timer = Timer(FRAME_MS) { frame() }

  init {
    isOpaque = false
    val mouse = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        pressAt = e.point
        dragging = false
        draggedNode = nodeAt(e.x, e.y)
        // Узел под курсором прикалывается: симуляция не должна двигать то, что тащит человек.
        if (draggedNode >= 0) layout?.pin(draggedNode)
      }

      override fun mouseDragged(e: MouseEvent) {
        val from = pressAt ?: return
        if (!dragging && Math.abs(e.x - from.x) + Math.abs(e.y - from.y) < DRAG_THRESHOLD) return
        dragging = true
        if (draggedNode >= 0) {
          layout?.moveTo(draggedNode, toGraphX(e.x), toGraphY(e.y))
          wake()
        }
        else {
          offsetX += e.x - from.x
          offsetY += e.y - from.y
          pressAt = e.point
          repaint()
        }
      }

      override fun mouseReleased(e: MouseEvent) {
        // Клик отличается от перетаскивания порогом: иначе каждый клик слегка сдвигает узел.
        if (!dragging && draggedNode >= 0) graph.nodes.getOrNull(draggedNode)?.let { onOpen(it.path) }
        layout?.unpin()
        pressAt = null
        dragging = false
        draggedNode = -1
      }

      override fun mouseMoved(e: MouseEvent) {
        val node = nodeAt(e.x, e.y)
        if (node != hovered) {
          hovered = node
          cursor = if (node >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
          toolTipText = graph.nodes.getOrNull(node)?.let { it.title + "   " + it.path }
          repaint()
        }
      }

      override fun mouseExited(e: MouseEvent) {
        hovered = -1
        repaint()
      }
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    // Колесо — зум К ПОЗИЦИИ КУРСОРА: зум к центру экрана уводит из-под мыши то, на что смотрят.
    addMouseWheelListener { e ->
      val old = scale
      val next = DocsGraphZoom.clamp(
        if (e.wheelRotation < 0) old * DocsGraphZoom.WHEEL_STEP else old / DocsGraphZoom.WHEEL_STEP)
      if (next != old) {
        offsetX = DocsGraphZoom.zoomAt(offsetX, e.x, old, next)
        offsetY = DocsGraphZoom.zoomAt(offsetY, e.y, old, next)
        scale = next
        repaint()
      }
    }
    timer.isRepeats = true
  }

  fun show(graph: DocsGraphLayout.Graph) {
    this.graph = graph
    this.layout = DocsForceLayout(graph)
    highlighted = emptySet()
    hovered = -1
    fitted = false
    wake()
  }

  /** Будит цикл: после перетаскивания или новой раскладки картинка обязана досложиться. */
  private fun wake() {
    if (!timer.isRunning) timer.start()
  }

  private fun frame() {
    val energy = layout?.step() ?: 0.0
    if (!fitted && energy < DocsForceLayout.REST_ENERGY * FIT_ENERGY_FACTOR) fitToScreen()
    if (energy < DocsForceLayout.REST_ENERGY) {
      timer.stop()
      if (!fitted) fitToScreen()
    }
    repaint()
  }

  /** Вписать всё нарисованное в окно с полями. */
  fun fitToScreen() {
    val current = layout ?: return
    if (graph.nodes.isEmpty() || width <= 0 || height <= 0) return
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (i in graph.nodes.indices) {
      minX = minOf(minX, current.positionX(i)); maxX = maxOf(maxX, current.positionX(i))
      minY = minOf(minY, current.positionY(i)); maxY = maxOf(maxY, current.positionY(i))
    }
    val graphWidth = (maxX - minX).coerceAtLeast(1.0)
    val graphHeight = (maxY - minY).coerceAtLeast(1.0)
    scale = DocsGraphZoom.fit(graphWidth.toInt(), graphHeight.toInt(), width, height, JBUI.scale(DocsGraphZoom.FIT_MARGIN))
    offsetX = (width / 2 - (minX + maxX) / 2 * scale).toInt()
    offsetY = (height / 2 - (minY + maxY) / 2 * scale).toInt()
    fitted = true
    repaint()
  }

  fun highlight(query: String) {
    val needle = query.trim().lowercase()
    highlighted = if (needle.isEmpty()) emptySet()
    else graph.nodes.filter { it.title.lowercase().contains(needle) || it.path.lowercase().contains(needle) }
      .map { it.path }.toSet()
    repaint()
  }

  fun highlightedCount(): Int = highlighted.size

  private fun toGraphX(screen: Int): Double = (screen - offsetX) / scale
  private fun toGraphY(screen: Int): Double = (screen - offsetY) / scale

  /** Индекс узла под точкой экрана, или −1. Радиус берётся с запасом: круг в пять пикселей не поймать. */
  private fun nodeAt(screenX: Int, screenY: Int): Int {
    val current = layout ?: return -1
    val gx = toGraphX(screenX)
    val gy = toGraphY(screenY)
    for (i in graph.nodes.indices.reversed()) {
      val r = DocsForceLayout.radiusOf(graph.nodes[i].degree) + HIT_PADDING
      val dx = gx - current.positionX(i)
      val dy = gy - current.positionY(i)
      if (dx * dx + dy * dy <= r * r) return i
    }
    return -1
  }

  override fun paintComponent(g: Graphics) {
    val current = layout ?: return
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.translate(offsetX, offsetY)
      g2.scale(scale, scale)

      val index = graph.nodes.withIndex().associate { (i, node) -> node.path to i }
      g2.color = EDGE
      for (edge in graph.edges) {
        val a = index[edge.from] ?: continue
        val b = index[edge.to] ?: continue
        g2.drawLine(current.positionX(a).toInt(), current.positionY(a).toInt(),
                    current.positionX(b).toInt(), current.positionY(b).toInt())
      }

      // Подписи на мелком масштабе — только у крупных узлов: тридцать подписей поверх друг друга
      // не читаются, а рисовать их на каждом кадре ещё и дорого.
      val labelDegree = if (scale < LABEL_SCALE) topDegreeThreshold() else 0

      for (i in graph.nodes.indices) {
        val node = graph.nodes[i]
        val dimmed = highlighted.isNotEmpty() && node.path !in highlighted
        g2.composite = java.awt.AlphaComposite.getInstance(
          java.awt.AlphaComposite.SRC_OVER, if (dimmed) DIM_ALPHA else 1f)
        val cx = current.positionX(i)
        val cy = current.positionY(i)
        val r = DocsForceLayout.radiusOf(node.degree)
        g2.color = fillOf(node)
        g2.fillOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
        // Обводка — то, что отличает круг от пятна на тёмном фоне и от соседа того же цвета.
        g2.color = if (i == hovered) HOVER_RING else BORDER
        g2.drawOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())

        if (node.degree < labelDegree && i != hovered) continue
        g2.color = TEXT
        val metrics = g2.fontMetrics
        val label = shorten(node.name, metrics)
        g2.drawString(label, (cx - metrics.stringWidth(label) / 2.0).toInt(),
                      (cy + r).toInt() + JBUI.scale(LABEL_GAP) + metrics.ascent)
      }
    }
    finally {
      g2.dispose()
    }
  }

  /** Степень, начиная с которой узел подписывается на мелком масштабе. */
  private fun topDegreeThreshold(): Int {
    val degrees = graph.nodes.map { it.degree }.sortedDescending()
    if (degrees.size <= LABELS_WHEN_SMALL) return 0
    return degrees[LABELS_WHEN_SMALL - 1].coerceAtLeast(1)
  }

  /**
   * Обрезка по последнему пробелу и не короче [MIN_LABEL_CHARS].
   *
   * «Партнёрки легаль…» не опознаётся: имя, обрезанное посреди слова, приходится доугадывать, а
   * подпись существует ровно для того, чтобы не гадать.
   */
  private fun shorten(text: String, metrics: java.awt.FontMetrics): String {
    if (text.length <= MIN_LABEL_CHARS) return text
    val cut = text.take(MIN_LABEL_CHARS)
    val space = cut.lastIndexOf(' ')
    return if (space > MIN_LABEL_CHARS / 2) cut.substring(0, space) + "…" else cut + "…"
  }

  private fun fillOf(node: DocsGraphLayout.Node): Color = when {
    // Состояние важнее принадлежности: битую ссылку и потерянный документ ищут глазами первыми,
    // ради них граф чаще всего и открывают.
    !node.reachable -> ORPHAN_FILL
    node.brokenLinks > 0 -> BROKEN_FILL
    else -> DocsGraphPalette.colorOf(node.category)
  }

  companion object {
    private const val FRAME_MS = 16
    private const val DRAG_THRESHOLD = 3
    private const val HIT_PADDING = 6.0
    private const val LABEL_GAP = 4
    private const val DIM_ALPHA = 0.25f

    /** Ниже этого масштаба подписываются только крупные узлы. */
    private const val LABEL_SCALE = 0.75

    /** Сколько подписей оставить на мелком масштабе. */
    private const val LABELS_WHEN_SMALL = 8

    /** Минимальная длина подписи: короче она перестаёт опознаваться. */
    private const val MIN_LABEL_CHARS = 28

    /** Вписываем, как только движение почти улеглось, — не дожидаясь полной остановки. */
    private const val FIT_ENERGY_FACTOR = 6.0

    val EDGE: JBColor get() = JBColor.namedColor("Vibe.Docs.edge", JBColor.GRAY)
    val BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBorder", JBColor.GRAY)
    val TEXT: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeForeground", UIUtil.getLabelForeground())
    val ORPHAN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.orphanNode", JBColor(0xC27D04, 0xD6AE58))
    val BROKEN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.brokenNode", JBColor(0xDB3B4B, 0xDB5C5C))
    val HOVER_RING: JBColor get() = JBColor.namedColor("Vibe.Docs.hoverRing", JBColor(0x3574F0, 0x548AF7))
  }
}
