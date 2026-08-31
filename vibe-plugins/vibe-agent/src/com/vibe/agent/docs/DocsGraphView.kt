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

  init {
    isOpaque = false
    val mouse = object : MouseAdapter() {
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
  }

  fun show(graph: DocsGraphLayout.Graph) {
    this.graph = graph
    preferredSize = Dimension(JBUI.scale(graph.width), JBUI.scale(graph.height))
    revalidate()
    repaint()
  }

  private fun nodeAt(px: Int, py: Int): DocsGraphLayout.Node? = graph.nodes.firstOrNull { node ->
    px >= JBUI.scale(node.x) && px <= JBUI.scale(node.x + DocsGraphLayout.NODE_WIDTH) &&
    py >= JBUI.scale(node.y) && py <= JBUI.scale(node.y + DocsGraphLayout.NODE_HEIGHT)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val byPath = graph.nodes.associateBy { it.path }
      g2.color = EDGE
      for (edge in graph.edges) {
        val from = byPath[edge.from] ?: continue
        val to = byPath[edge.to] ?: continue
        g2.drawLine(
          JBUI.scale(from.x + DocsGraphLayout.NODE_WIDTH / 2), JBUI.scale(from.y + DocsGraphLayout.NODE_HEIGHT),
          JBUI.scale(to.x + DocsGraphLayout.NODE_WIDTH / 2), JBUI.scale(to.y),
        )
      }
      for (node in graph.nodes) {
        val x = JBUI.scale(node.x)
        val y = JBUI.scale(node.y)
        val w = JBUI.scale(DocsGraphLayout.NODE_WIDTH)
        val h = JBUI.scale(DocsGraphLayout.NODE_HEIGHT)
        // Colour says WHY a document is worth attention, and never says it alone: the marks the
        // list shows in words are the same marks, so nothing is knowable only by colour.
        g2.color = when {
          !node.reachable -> ORPHAN_FILL
          node === hovered -> HOVER_FILL
          else -> FILL
        }
        g2.fillRoundRect(x, y, w, h, JBUI.scale(8), JBUI.scale(8))
        g2.color = if (node.brokenLinks > 0) BROKEN_BORDER else BORDER
        g2.drawRoundRect(x, y, w, h, JBUI.scale(8), JBUI.scale(8))
        g2.color = TEXT
        val label = node.title + if (node.brokenLinks > 0) "  ⚠" + node.brokenLinks else ""
        val metrics = g2.fontMetrics
        g2.drawString(
          shorten(label, metrics.stringWidth(label), w - JBUI.scale(16), metrics.charWidth('m')),
          x + JBUI.scale(8), y + h / 2 + metrics.ascent / 2 - JBUI.scale(2),
        )
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
    val FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBackground", UIUtil.getPanelBackground())
    val HOVER_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeHoverBackground", UIUtil.getListSelectionBackground(false))
    val ORPHAN_FILL: JBColor get() = JBColor.namedColor("Vibe.Docs.orphanBackground", UIUtil.getPanelBackground())
    val BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeBorder", JBColor.GRAY)
    val BROKEN_BORDER: JBColor get() = JBColor.namedColor("Vibe.Docs.brokenBorder", JBColor.RED)
    val EDGE: JBColor get() = JBColor.namedColor("Vibe.Docs.edge", JBColor.GRAY)
    val TEXT: JBColor get() = JBColor.namedColor("Vibe.Docs.nodeForeground", JBColor.foreground())
  }
}
