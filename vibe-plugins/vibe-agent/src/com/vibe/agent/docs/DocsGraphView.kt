// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.docs

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * The documentation drawn as a map: layers by distance from the entry point, orphans in a band of
 * their own at the bottom.
 *
 * Painting only — every decision about WHERE things go lives in [DocsGraphLayout], which is why
 * this class has no arithmetic worth testing and the layout has no Swing worth mocking.
 */
class DocsGraphView(private val onOpen: (String) -> Unit) : JComponent() {
  private var graph: DocsGraphLayout.Graph = DocsGraphLayout.Graph(emptyList(), emptyList(), 0, 0)
  private var hovered: DocsGraphLayout.Node? = null

  /** Масштаб и сдвиг рисунка; счёт — в [DocsGraphZoom], здесь только состояние и рисование. */
  private var scale = 1.0
  private var offsetX = 0
  private var offsetY = 0

  /** Вписать при первом показе, но НЕ при каждом обновлении: иначе зум сбрасывался бы под руками. */
  private var fitted = false

  /** Точка последнего нажатия для панорамирования; null — тянут не за фон. */
  private var dragFrom: java.awt.Point? = null

  /** Подсвеченные поиском пути; пусто — поиска нет и приглушать нечего. */
  private var highlighted: Set<String> = emptySet()

  init {
    isOpaque = false
    val mouse = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        // Тянем только за фон: нажатие на узел — это будущий клик по документу.
        dragFrom = if (nodeAt(e.x, e.y) == null) e.point else null
      }

      override fun mouseReleased(e: MouseEvent) {
        dragFrom = null
      }

      override fun mouseDragged(e: MouseEvent) {
        val from = dragFrom ?: return
        offsetX += e.x - from.x
        offsetY += e.y - from.y
        dragFrom = e.point
        repaint()
      }

      override fun mouseMoved(e: MouseEvent) {
        val node = nodeAt(e.x, e.y)
        if (node !== hovered) {
          hovered = node
          cursor = if (node != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
          repaint()
        }
      }

      override fun mouseClicked(e: MouseEvent) {
        nodeAt(e.x, e.y)?.let { onOpen(it.path) }
      }

      override fun mouseExited(e: MouseEvent) {
        hovered = null
        repaint()
      }
    }
    addMouseListener(mouse)
    addMouseMotionListener(mouse)
    // Колесо — зум, а не прокрутка: прокручивать нечего, граф не документ. Точка под курсором
    // остаётся на месте (см. DocsGraphZoom.zoomAt), иначе рисунок убегает от мыши.
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
  }

  /** Вписать граф в окно целиком и поставить по центру. */
  fun fitToScreen() {
    val w = JBUI.scale(graph.width)
    val h = JBUI.scale(graph.height)
    if (w <= 0 || h <= 0 || width <= 0 || height <= 0) return
    scale = DocsGraphZoom.fit(w, h, width, height, JBUI.scale(DocsGraphZoom.FIT_MARGIN))
    val (x, y) = DocsGraphZoom.center(w, h, width, height, scale)
    offsetX = x
    offsetY = y
    fitted = true
    repaint()
  }

  /**
   * Подсветка по поиску: найденное остаётся ярким, остальное гаснет.
   *
   * Не фильтр: узел, выброшенный из рисунка, уносит с собой свои связи, и граф начинает врать про
   * связность ровно тогда, когда его об этом спрашивают.
   */
  fun highlight(query: String) {
    val needle = query.trim().lowercase()
    highlighted = if (needle.isEmpty()) emptySet()
    else graph.nodes.filter { it.title.lowercase().contains(needle) || it.path.lowercase().contains(needle) }
      .map { it.path }.toSet()
    repaint()
  }

  /** Сколько узлов подсвечено сейчас — для строки состояния над графом. */
  fun highlightedCount(): Int = highlighted.size

  fun show(graph: DocsGraphLayout.Graph) {
    this.graph = graph
    preferredSize = Dimension(JBUI.scale(graph.width), JBUI.scale(graph.height))
    highlighted = emptySet()
    revalidate()
    // Первый показ вписывает сам: граф, открытый в углу окна и наполовину за краем, человек
    // сначала ловит мышью и только потом читает.
    if (!fitted) javax.swing.SwingUtilities.invokeLater { fitToScreen() }
    repaint()
  }

  /** Экранная точка → координаты рисунка: обратно к тому, в чём считан layout. */
  private fun nodeAt(screenX: Int, screenY: Int): DocsGraphLayout.Node? {
    val px = ((screenX - offsetX) / scale).toInt()
    val py = ((screenY - offsetY) / scale).toInt()
    return nodeAtGraph(px, py)
  }

  /**
   * Попадание — по кругу, с запасом на подпись под ним.
   *
   * Запас не прихоть: круг радиусом семь пикселей мышью не поймать, а подпись — это тот же узел,
   * и человек целится именно в неё.
   */
  private fun nodeAtGraph(px: Int, py: Int): DocsGraphLayout.Node? = graph.nodes.firstOrNull { node ->
    val cx = JBUI.scale(node.x)
    val cy = JBUI.scale(node.y)
    val reach = JBUI.scale(node.radius + HIT_PADDING)
    val labelBottom = cy + JBUI.scale(node.radius + LABEL_GAP + LABEL_HEIGHT)
    val insideCircle = (px - cx) * (px - cx) + (py - cy) * (py - cy) <= reach * reach
    val insideLabel = py in cy..labelBottom && Math.abs(px - cx) <= JBUI.scale(DocsGraphLayout.NODE_WIDTH / 2)
    insideCircle || insideLabel
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      // Зум и сдвиг — одной трансформацией: пересчитывать каждую координату вручную значит
      // однажды забыть про одну из них, и рисунок разъедется по частям.
      g2.translate(offsetX, offsetY)
      g2.scale(scale, scale)
      val byPath = graph.nodes.associateBy { it.path }
      g2.color = EDGE
      for (edge in graph.edges) {
        val from = byPath[edge.from] ?: continue
        val to = byPath[edge.to] ?: continue
        // Линия между ЦЕНТРАМИ: узел теперь точка, и линия, упирающаяся в край прямоугольника,
        // рисовала бы связь не туда.
        g2.drawLine(JBUI.scale(from.x), JBUI.scale(from.y), JBUI.scale(to.x), JBUI.scale(to.y))
      }
      for (node in graph.nodes) {
        val dimmed = highlighted.isNotEmpty() && node.path !in highlighted
        g2.composite = java.awt.AlphaComposite.getInstance(
          java.awt.AlphaComposite.SRC_OVER, if (dimmed) DIM_ALPHA else 1f)
        val cx = JBUI.scale(node.x)
        val cy = JBUI.scale(node.y)
        val r = JBUI.scale(node.radius)
        // Цвет говорит, ПОЧЕМУ узел заслуживает внимания, и никогда не говорит этого один: те же
        // пометки есть словами в дереве слева, поэтому ничто не познаётся только цветом.
        g2.color = when {
          !node.reachable -> ORPHAN_FILL
          node.brokenLinks > 0 -> BROKEN_FILL
          node === hovered -> HOVER_FILL
          node.layer == 0 -> ENTRY_FILL
          else -> FILL
        }
        g2.fillOval(cx - r, cy - r, r * 2, r * 2)
        if (node === hovered) {
          g2.color = HOVER_RING
          g2.drawOval(cx - r - JBUI.scale(3), cy - r - JBUI.scale(3), (r + JBUI.scale(3)) * 2, (r + JBUI.scale(3)) * 2)
        }
        // Подпись ПОД кругом и по центру: подпись сбоку у радиальной раскладки наезжает на соседа
        // с той стороны, где узлов гуще.
        g2.color = TEXT
        val metrics = g2.fontMetrics
        val label = node.title + if (node.brokenLinks > 0) "  ⚠" + node.brokenLinks else ""
        val text = shorten(label, metrics.stringWidth(label), JBUI.scale(DocsGraphLayout.NODE_WIDTH), metrics.charWidth('m'))
        g2.drawString(text, cx - metrics.stringWidth(text) / 2, cy + r + JBUI.scale(LABEL_GAP) + metrics.ascent)
      }
    }
    finally {
      g2.dispose()
    }
  }

  /** Cuts to the width available; an ellipsis is honest, a label spilling over a neighbour is not. */
  private fun shorten(text: String, textWidth: Int, available: Int, charWidth: Int): String {
    if (textWidth <= available || charWidth <= 0) return text
    val fits = (available / charWidth - 1).coerceAtLeast(1)
    return if (fits >= text.length) text else text.take(fits) + "…"
  }

  private companion object {
    /** Насколько гаснет ненайденное поиском: видно, что оно есть, но глаз за него не цепляется. */
    const val DIM_ALPHA = 0.25f

    /** Отступ подписи от круга и её высота — для попадания мышью и расчёта полотна. */
    const val LABEL_GAP = 6
    const val LABEL_HEIGHT = 14

    /** Запас вокруг круга: узел радиусом семь пикселей мышью иначе не поймать. */
    const val HIT_PADDING = 6

    val ENTRY_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.entryNode", JBColor(0x3574F0, 0x548AF7))
    val BROKEN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.brokenNode", JBColor(0xDB3B4B, 0xDB5C5C))
    val HOVER_RING: JBColor get() = JBColor.namedColor("Vibe.Docs.hoverRing", JBColor(0x3574F0, 0x548AF7))

    val FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBackground", UIUtil.getPanelBackground())
    val HOVER_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeHoverBackground", UIUtil.getListSelectionBackground(false))
    val ORPHAN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.orphanBackground", UIUtil.getPanelBackground())
    val BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBorder", JBColor.GRAY)
    val BROKEN_BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.brokenBorder", JBColor.RED)
    val EDGE: JBColor get() = JBColor.namedColor("Vibe.Docs.edge", JBColor.GRAY)
    val TEXT: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeForeground", JBColor.foreground())
  }
}
