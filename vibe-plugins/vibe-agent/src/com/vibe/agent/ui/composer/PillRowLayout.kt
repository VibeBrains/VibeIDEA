// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * The composer's pill row: one line, and one pill that gives way before the row wraps
 *
 * A plain wrapping row moved whole pills to a second line as soon as a long model name did not fit:
 * The context ring ended up alone under the row, and the history pill on the right centred itself between the lines
 * Here the [elastic] pill shrinks first, down to its minimum, and its label ends with an ellipsis
 * Only when even that does not fit, the row wraps: content is never cut away silently
 *
 * The preferred height follows the container's current width, so a parent asks for exactly the lines the row will take
 */
class PillRowLayout(private val gap: Int, private val minElastic: Int) : LayoutManager {
  /** The pill that shrinks first; null — the row only wraps */
  var elastic: Component? = null

  override fun addLayoutComponent(name: String?, comp: Component?) = Unit
  override fun removeLayoutComponent(comp: Component?) = Unit

  override fun preferredLayoutSize(parent: Container): Dimension = size(parent, preferred = true)

  override fun minimumLayoutSize(parent: Container): Dimension = size(parent, preferred = false)

  override fun layoutContainer(parent: Container) {
    val insets = parent.insets
    val lines = lines(parent, parent.width - insets.left - insets.right)
    var y = insets.top
    for (line in lines) {
      var x = insets.left
      for ((comp, width) in line.items) {
        val h = comp.preferredSize.height
        comp.setBounds(x, y + (line.height - h) / 2, width, h)
        x += width + gap
      }
      y += line.height + gap
    }
  }

  private fun size(parent: Container, preferred: Boolean): Dimension {
    val insets = parent.insets
    val visible = parent.components.filter { it.isVisible }
    // Before the first layout the width is unknown: report one line, the width every pill wants
    val width = parent.width - insets.left - insets.right
    val lines = if (width > 0) lines(parent, width) else listOf(Line(visible.map { it to it.preferredSize.width }))
    val w = if (preferred) lines.maxOfOrNull { it.width(gap) } ?: 0 else minWidth(visible)
    val h = lines.sumOf { it.height } + gap * (lines.size - 1).coerceAtLeast(0)
    return Dimension(w + insets.left + insets.right, h + insets.top + insets.bottom)
  }

  /** The narrowest the row can get without hiding a pill: the widest single pill, the elastic one at its minimum */
  private fun minWidth(visible: List<Component>): Int =
    visible.maxOfOrNull { if (it === elastic) minOf(it.preferredSize.width, minElastic) else it.preferredSize.width } ?: 0

  /** The pills split into lines for [available] width, each with the width it is given */
  internal fun lines(parent: Container, available: Int): List<Line> {
    val visible = parent.components.filter { it.isVisible }
    if (visible.isEmpty()) return emptyList()
    val widths = visible.associateWith { it.preferredSize.width }.toMutableMap()
    val total = widths.values.sum() + gap * (visible.size - 1)
    val flexible = elastic?.takeIf { it in widths }
    if (total > available && flexible != null) {
      val wanted = widths.getValue(flexible)
      widths[flexible] = (wanted - (total - available)).coerceIn(minOf(wanted, minElastic), wanted)
    }
    val lines = ArrayList<Line>()
    var current = ArrayList<Pair<Component, Int>>()
    var used = 0
    for (comp in visible) {
      val w = widths.getValue(comp)
      val needed = if (current.isEmpty()) w else used + gap + w
      if (current.isNotEmpty() && needed > available) {
        lines += Line(current)
        current = ArrayList()
        used = w
      }
      else used = needed
      current += comp to w
    }
    lines += Line(current)
    return lines
  }

  internal class Line(val items: List<Pair<Component, Int>>) {
    val height: Int = items.maxOfOrNull { it.first.preferredSize.height } ?: 0
    fun width(gap: Int): Int = items.sumOf { it.second } + gap * (items.size - 1).coerceAtLeast(0)
  }
}
