// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.graphview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer

/**
 * Drawing of a graph: canvas, frame loop, viewport and mouse
 *
 * One canvas for the docs graph and the project graph
 * The owner says what a node is called, how big it is and what colour a colour key stands for; the canvas knows nothing else
 *
 * There is no physics in it, not a line: it is in [ForceLayout], and that split is the point
 * Forces can be counted without a window in a test, and the rendering can be rewritten without touching the counted ones
 *
 * The loop stops by energy (see [ForceLayout.REST_ENERGY]): an eternal animation heats a laptop and jitters a settled picture
 */
class GraphCanvas(
  private val onClick: (CanvasNode) -> Unit,
  private val fillOf: (colorKey: String) -> Color,
) : JComponent() {
  private var graph: CanvasGraph = CanvasGraph.EMPTY
  private var layout: ForceLayout? = null
  private var hovered: Int = -1

  private var scale = 1.0
  private var offsetX = 0
  private var offsetY = 0
  private var fitted = false

  /** Where the press started, on the background or on a node; the threshold in pixels tells a click from a drag */
  private var pressAt: Point? = null
  private var dragging = false
  private var draggedNode = -1

  private var highlighted: Set<Int> = emptySet()
  private var labelThreshold = 0

  /** Said in the middle of an empty canvas: a blank rectangle reads as a hang, and it can mean four different things */
  var emptyMessage: String = ""
    set(value) {
      field = value
      repaint()
    }

  /** Frames of the simulation: a timer rather than a thread, because painting lives on the EDT and the counting has to be there too */
  private val timer = Timer(FRAME_MS) { frame() }

  init {
    isOpaque = false
    // Fitting is skipped while the panel has no size, and by then the layout may already have settled and stopped the timer
    // The graph would then sit in the top-left corner until the window is first resized
    // So the first real size asks for the fit itself
    addComponentListener(object : ComponentAdapter() {
      override fun componentResized(e: ComponentEvent) {
        if (!fitted) fitToScreen(final = false)
      }
    })
    val mouse = object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) {
        pressAt = e.point
        dragging = false
        draggedNode = nodeAt(e.x, e.y)
        // The node under the cursor is pinned: the simulation must not move what a person is dragging
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
        // A click differs from a drag by the threshold: otherwise every click nudges the node a little
        if (!dragging && draggedNode >= 0) graph.nodes.getOrNull(draggedNode)?.let(onClick)
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
          toolTipText = graph.nodes.getOrNull(node)?.tooltip?.let(PlainText::safe)
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
    // The wheel zooms TO THE CURSOR: a zoom to the centre takes what a person looks at out from under the mouse
    addMouseWheelListener { e ->
      val old = scale
      val next = GraphZoom.clamp(if (e.wheelRotation < 0) old * GraphZoom.WHEEL_STEP else old / GraphZoom.WHEEL_STEP)
      if (next != old) {
        offsetX = GraphZoom.zoomAt(offsetX, e.x, old, next)
        offsetY = GraphZoom.zoomAt(offsetY, e.y, old, next)
        scale = next
        repaint()
      }
    }
    timer.isRepeats = true
  }

  /**
   * Shows a graph
   *
   * The very same picture again keeps the layout, the zoom and the fit: a refresh that found nothing new must not make the graph jump
   */
  fun show(next: CanvasGraph) {
    if (layout != null && next == graph) return
    graph = next
    labelThreshold = labelThresholdOf(next)
    layout = ForceLayout(next)
    highlighted = emptySet()
    hovered = -1
    fitted = false
    wake()
    // Right away, without waiting for the settling: the start layout is rough but its centre is already right
    // A person sees the graph in the middle from the first frame, not a jump from the corner a second later
    ApplicationManager.getApplication().invokeLater { if (!fitted) fitToScreen(final = false) }
  }

  /** Wakes the loop: after a drag or a new layout the picture has to settle */
  private fun wake() {
    if (!timer.isRunning) timer.start()
  }

  private fun frame() {
    val energy = layout?.step() ?: 0.0
    if (!fitted && energy < ForceLayout.REST_ENERGY * FIT_ENERGY_FACTOR) fitToScreen()
    if (energy < ForceLayout.REST_ENERGY) {
      timer.stop()
      if (!fitted) fitToScreen()
    }
    repaint()
  }

  /** Fits everything drawn into the window with margins, by a person's button, so for good */
  fun fitToScreen() = fitToScreen(final = true)

  /**
   * Fits now
   *
   * [final] answers whether this is the last word
   * While the layout is still spreading the fit is PRELIMINARY: it centres the graph from the first frame
   * But it does not cancel the final one the settled simulation makes
   * Marking an early fit as final would leave the graph at the start scale, and the nodes would spread off the edges
   */
  private fun fitToScreen(final: Boolean) {
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
    scale = GraphZoom.fit(graphWidth.toInt(), graphHeight.toInt(), width, height, JBUI.scale(GraphZoom.FIT_MARGIN))
    offsetX = (width / 2 - (minX + maxX) / 2 * scale).toInt()
    offsetY = (height / 2 - (minY + maxY) / 2 * scale).toInt()
    if (final) fitted = true
    repaint()
  }

  /** Dims every node whose text does not contain the query; an empty query lights them all */
  fun highlight(query: String) {
    highlighted = graph.matching(query)
    repaint()
  }

  fun highlightedCount(): Int = highlighted.size

  private fun toGraphX(screen: Int): Double = (screen - offsetX) / scale
  private fun toGraphY(screen: Int): Double = (screen - offsetY) / scale

  /** Index of the node under a screen point, or -1; the radius has a margin, a circle of five pixels cannot be hit */
  private fun nodeAt(screenX: Int, screenY: Int): Int {
    val current = layout ?: return -1
    val gx = toGraphX(screenX)
    val gy = toGraphY(screenY)
    for (i in graph.nodes.indices.reversed()) {
      val r = graph.nodes[i].radius + HIT_PADDING
      val dx = gx - current.positionX(i)
      val dy = gy - current.positionY(i)
      if (dx * dx + dy * dy <= r * r) return i
    }
    return -1
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val current = layout
      if (current == null || graph.nodes.isEmpty()) {
        paintEmptyMessage(g2)
        return
      }
      g2.translate(offsetX, offsetY)
      g2.scale(scale, scale)

      // A dense graph is a lump of lines at full strength; thinned, the nodes stay readable on top of it
      g2.composite = AlphaComposite.getInstance(
        AlphaComposite.SRC_OVER, if (graph.links.size > DENSE_EDGES) DENSE_EDGE_ALPHA else 1f)
      g2.color = EDGE
      for (link in graph.links) {
        g2.drawLine(current.positionX(link[0]).toInt(), current.positionY(link[0]).toInt(),
                    current.positionX(link[1]).toInt(), current.positionY(link[1]).toInt())
      }

      // At a small scale only the large nodes are captioned: thirty captions on top of each other cannot be read
      // And drawing them on every frame is costly
      val labelDegree = if (scale < graph.labelsFromScale) labelThreshold else 0

      for (i in graph.nodes.indices) {
        val node = graph.nodes[i]
        val found = i in highlighted
        val dimmed = highlighted.isNotEmpty() && !found
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (dimmed) DIM_ALPHA else 1f)
        val cx = current.positionX(i)
        val cy = current.positionY(i)
        val r = node.radius
        val fill = fillOf(node.colorKey)
        if (node.aggregate) {
          // A group among single things: the outer ring says «this opens up»
          g2.color = fill
          val outer = r + AGGREGATE_RING_GAP
          g2.drawOval((cx - outer).toInt(), (cy - outer).toInt(), (outer * 2).toInt(), (outer * 2).toInt())
        }
        g2.color = fill
        g2.fillOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
        // The outline is what tells a circle from a blot on a dark background, and from a neighbour of the same colour
        g2.color = if (i == hovered) HOVER_RING else BORDER
        g2.drawOval((cx - r).toInt(), (cy - r).toInt(), (r * 2).toInt(), (r * 2).toInt())

        if (graph.degrees[i] < labelDegree && i != hovered && !node.aggregate && !found) continue
        g2.color = TEXT
        val metrics = g2.fontMetrics
        val label = shorten(node.label)
        g2.drawString(label, (cx - metrics.stringWidth(label) / 2.0).toInt(),
                      (cy + r).toInt() + JBUI.scale(LABEL_GAP) + metrics.ascent)
      }
    }
    finally {
      g2.dispose()
    }
  }

  private fun paintEmptyMessage(g2: Graphics2D) {
    if (emptyMessage.isEmpty()) return
    g2.color = UIUtil.getInactiveTextColor()
    val metrics = g2.fontMetrics
    val lines = emptyMessage.lines()
    var y = (height - lines.size * metrics.height) / 2 + metrics.ascent
    for (line in lines) {
      g2.drawString(line, (width - metrics.stringWidth(line)) / 2, y)
      y += metrics.height
    }
  }

  /** The degree from which a node is captioned at a small scale */
  private fun labelThresholdOf(graph: CanvasGraph): Int {
    val degrees = graph.degrees.sortedDescending()
    if (degrees.size <= LABELS_WHEN_SMALL) return 0
    return degrees[LABELS_WHEN_SMALL - 1].coerceAtLeast(1)
  }

  /**
   * Cut at the last space and not shorter than [MIN_LABEL_CHARS]
   *
   * A name cut in the middle of a word cannot be recognised: it has to be guessed, and a caption exists so as not to guess
   */
  private fun shorten(text: String): String {
    if (text.length <= MIN_LABEL_CHARS) return text
    val cut = text.take(MIN_LABEL_CHARS)
    val space = cut.lastIndexOf(' ')
    return if (space > MIN_LABEL_CHARS / 2) cut.substring(0, space) + "…" else cut + "…"
  }

  /**
   * Token names keep the `Vibe.Docs` prefix, though the canvas is shared now
   * Users' themes already override them, and a rename would reset those overrides
   */
  companion object {
    private const val FRAME_MS = 16
    private const val DRAG_THRESHOLD = 3
    private const val HIT_PADDING = 6.0
    private const val LABEL_GAP = 4
    private const val DIM_ALPHA = 0.25f
    private const val AGGREGATE_RING_GAP = 3.0

    /** Above this many edges they are drawn thinner */
    private const val DENSE_EDGES = 150
    private const val DENSE_EDGE_ALPHA = 0.35f

    /** How many captions stay at a small scale */
    private const val LABELS_WHEN_SMALL = 8

    /** The minimal length of a caption: shorter it is no longer recognised */
    private const val MIN_LABEL_CHARS = 28

    /** Fitting starts as soon as the motion has almost died down, without waiting for a full stop */
    private const val FIT_ENERGY_FACTOR = 6.0

    val EDGE: JBColor get() = JBColor.namedColor("Vibe.Docs.edge", JBColor.GRAY)
    val BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBorder", JBColor.GRAY)
    val TEXT: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeForeground", UIUtil.getLabelForeground())
    val HOVER_RING: JBColor get() = JBColor.namedColor("Vibe.Docs.hoverRing", JBColor(0x3574F0, 0x548AF7))
  }
}
