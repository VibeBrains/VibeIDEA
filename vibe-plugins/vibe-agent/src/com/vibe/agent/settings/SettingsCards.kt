// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Colours of the cards on a settings page
 *
 * A third-party theme declares none of these, so the defaults come from keys every theme has
 * Our themes name them explicitly, each pointing at an entry of its own palette
 */
internal object SettingsTheme {
  val CARD_BACKGROUND: JBColor =
    JBColor.namedColor("Vibe.Settings.cardBackground", JBColor.namedColor("Panel.background", JBColor.PanelBackground))
  val CARD_BORDER: JBColor = JBColor.namedColor("Vibe.Settings.cardBorder", JBColor.border())

  /** The dashed outline of the block of providers that wait for a key: a card that is not quite part of the set yet */
  val WAITING_BORDER: JBColor = JBColor.namedColor("Vibe.Settings.waitingBorder", JBColor.border())
}

/**
 * A rounded card: a fill, a one-pixel outline, padding inside
 *
 * The outline is drawn by the panel itself and not by a `Border`, because a border has no say in the fill
 * and the two have to follow the same curve or the corners show a seam
 */
internal class CardPanel(val dashed: Boolean) : JPanel(BorderLayout()) {
  init {
    isOpaque = false
    border = JBUI.Borders.empty(PAD_VERTICAL, PAD_HORIZONTAL)
  }

  /** Half a pixel in from the edge, so that a one-pixel stroke lands on whole pixels instead of being smeared over two */
  private fun outline(): RoundRectangle2D.Float {
    val arc = JBUI.scale(ARC).toFloat()
    return RoundRectangle2D.Float(HALF_PIXEL, HALF_PIXEL, width - 1f, height - 1f, arc, arc)
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = SettingsTheme.CARD_BACKGROUND
      g2.fill(outline())
    }
    finally {
      g2.dispose()
    }
  }

  override fun paintBorder(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = if (dashed) SettingsTheme.WAITING_BORDER else SettingsTheme.CARD_BORDER
      g2.stroke = if (dashed) dashedStroke() else BasicStroke(1f)
      g2.draw(outline())
    }
    finally {
      g2.dispose()
    }
  }

  private fun dashedStroke(): BasicStroke {
    val dash = JBUI.scale(DASH).toFloat()
    return BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(dash, dash), 0f)
  }

  companion object {
    const val ARC = 8
    const val PAD_VERTICAL = 8
    const val PAD_HORIZONTAL = 10
    private const val DASH = 4
    private const val HALF_PIXEL = 0.5f
  }
}

/**
 * A one-line label that gives way: it asks for no more than [maxPreferred] and can be squeezed down to [minWidth]
 *
 * A status line can be as long as an error message, and a label is as wide as its text asks
 * Left alone it widens the header, the card and the whole page; here it shortens itself with an ellipsis instead
 */
internal class TruncatingLabel(private val maxPreferred: Int = META_PREFERRED, private val minWidth: Int = 0) : JBLabel() {
  init {
    foreground = JBColor.GRAY
  }

  override fun setText(text: String?) {
    super.setText(text)
    // The full text stays reachable: a truncated error message is worth little
    toolTipText = text?.takeIf { it.isNotEmpty() }
  }

  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    return Dimension(minOf(size.width, JBUI.scale(maxPreferred)), size.height)
  }

  override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(minWidth), super.getMinimumSize().height)

  companion object {
    /** A status line is a hint next to the title, and beyond this much of it the title would be squeezed for nothing */
    const val META_PREFERRED = 300

    /** A title is the name of a provider: long enough for any of them, short enough that one cannot take the page */
    const val TITLE_PREFERRED = 360
    const val TITLE_MIN = 96
  }
}

/**
 * A link of a settings page that can be cut with an ellipsis instead of widening the page
 *
 * A short link keeps its own width; the one whose text is a whole sentence gives way down to [MIN_WIDTH]
 */
internal class SettingsLink(text: String, onClick: () -> Unit) : ActionLink(text, { onClick() }) {
  override fun getMinimumSize(): Dimension {
    val size = super.getMinimumSize()
    return Dimension(minOf(size.width, JBUI.scale(MIN_WIDTH)), size.height)
  }

  private companion object {
    const val MIN_WIDTH = 120
  }
}

/** A checkbox of a model row: long labels are cut with an ellipsis instead of widening the page */
internal class ModelCheckBox(text: String, selected: Boolean) : JBCheckBox(text, selected) {
  init {
    // The card behind it has a fill of its own, and a look and feel that paints the checkbox opaque would cut a stripe out of it
    isOpaque = false
  }

  override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), super.getMinimumSize().height)

  private companion object {
    const val MIN_WIDTH = 48
  }
}

/**
 * The line that opens and closes a block: a chevron, a title, a status that gives way, an optional action on the right
 *
 * A click anywhere on the line toggles, the action excepted: it is a link and keeps its own click
 */
internal class FoldHeader(action: JComponent?) : JPanel(BorderLayout(JBUI.scale(GAP), 0)) {
  /** Set by the panel that owns the header, which is the one that knows what to open */
  var onToggle: () -> Unit = {}

  /** The status next to the title: where the models of the block came from */
  val meta = TruncatingLabel()

  private val title = TruncatingLabel(TruncatingLabel.TITLE_PREFERRED, TruncatingLabel.TITLE_MIN).apply {
    font = JBFont.label().asBold()
    foreground = UIUtil.getLabelForeground()
  }
  private var titleText = ""

  var expanded: Boolean = false
    set(value) {
      field = value
      showTitle()
    }

  init {
    isOpaque = false
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    add(title, BorderLayout.WEST)
    add(meta, BorderLayout.CENTER)
    action?.let { add(it, BorderLayout.EAST) }
    val click = object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) = onToggle()
    }
    // The labels are targets of their own (a tooltip makes a label take the mouse events), so the line alone would not hear them
    for (part in listOf(this, title, meta)) part.addMouseListener(click)
  }

  fun setTitle(text: String) {
    titleText = text
    showTitle()
  }

  private fun showTitle() {
    title.text = "${if (expanded) CHEVRON_OPEN else CHEVRON_CLOSED}  $titleText"
  }

  private companion object {
    const val GAP = 8
    const val CHEVRON_OPEN = "▾"
    const val CHEVRON_CLOSED = "▸"
  }
}

/** A header with a body that it opens: collapsed until someone opens it, and not remembered anywhere */
internal class FoldPanel(val header: FoldHeader, private val body: JComponent) : JPanel(BorderLayout()) {
  var expanded: Boolean = false
    set(value) {
      field = value
      header.expanded = value
      body.isVisible = value
      revalidate()
      repaint()
    }

  init {
    isOpaque = false
    add(header, BorderLayout.NORTH)
    add(body, BorderLayout.CENTER)
    header.onToggle = { expanded = !expanded }
    expanded = false
  }
}
