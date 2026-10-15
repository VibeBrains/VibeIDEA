// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.pipelines.Pipeline
import com.vibe.agent.pipelines.PipelineChain
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** One line of the pipeline menu: a pipeline to run, or the way to the file the pipelines are written in */
sealed interface PipelineChoice {
  data class Run(val pipeline: Pipeline) : PipelineChoice

  data object OpenFile : PipelineChoice
}

/**
 * The pipeline menu under the run button of the composer: a searchable list, two lines per pipeline
 * The name and the number of steps on the first line, the chain of the steps in grey under it
 * The last line, after a rule, opens the file the pipelines are written in
 */
class PipelinePicker(
  private val onRun: (Pipeline) -> Unit,
  private val onOpenFile: () -> Unit,
) {
  fun show(pipelines: List<Pipeline>, anchor: JComponent) {
    val choices = pipelines.map { PipelineChoice.Run(it) } + PipelineChoice.OpenFile
    JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
      .setRenderer(PipelineRowRenderer())
      .setNamerForFiltering { choice ->
        when (choice) {
          is PipelineChoice.Run -> choice.pipeline.name + " " + choice.pipeline.id
          PipelineChoice.OpenFile -> t("pipeline.picker.open")
        }
      }
      .setFilterAlwaysVisible(true)
      .setItemChosenCallback { choice ->
        when (choice) {
          is PipelineChoice.Run -> onRun(choice.pipeline)
          PipelineChoice.OpenFile -> onOpenFile()
        }
      }
      .createPopup()
      // The popup builds its own scroll pane deep inside and never exposes it — reach for it
      // through the component tree so the list scrolls with the same thin bar as everything else.
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(anchor)
  }
}

/**
 * The tooltip of a pipeline row: the description from the file, when there is one, and the whole chain of steps
 * The row cuts the chain with an ellipsis at its width, and this is where it is read to the end
 */
internal fun pipelineTooltip(pipeline: Pipeline): String {
  val lines = listOfNotNull(
    pipeline.description?.trim()?.ifEmpty { null },
    PipelineChain.text(pipeline.steps),
  )
  return "<html><body style='width: ${JBUI.scale(TOOLTIP_WIDTH)}px'>" +
    lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</body></html>"
}

// Unscaled width at which a tooltip wraps its lines
private const val TOOLTIP_WIDTH = 340

/**
 * Draws the rows of the pipeline menu
 * Both kinds of row are single components handed back for every cell, as [PickerRow] does
 */
internal class PipelineRowRenderer : ListCellRenderer<PipelineChoice> {
  private val nameLabel = JBLabel()
  private val countLabel = JBLabel()
  internal val chain = PipelineChainLabel()
  private val head = JPanel(BorderLayout(JBUI.scale(NAME_GAP), 0)).apply {
    isOpaque = false
    add(nameLabel, BorderLayout.CENTER)
    add(countLabel, BorderLayout.EAST)
  }

  // No column is reserved on the left: the row starts at the same edge as the text of any other menu
  private val pipelineRow = CappedRow(BorderLayout(0, JBUI.scale(LINE_GAP))).apply {
    border = JBUI.Borders.empty(ROW_PAD_V, UIUtil.getListCellHPadding())
    add(head, BorderLayout.NORTH)
    add(chain, BorderLayout.SOUTH)
  }
  private val openLabel = JBLabel()
  private val openRow = CappedRow(BorderLayout()).apply {
    border = JBUI.Borders.compound(
      JBUI.Borders.customLine(separator(), 1, 0, 0, 0),
      JBUI.Borders.empty(ROW_PAD_V, UIUtil.getListCellHPadding()),
    )
    add(openLabel, BorderLayout.CENTER)
  }

  override fun getListCellRendererComponent(
    list: JList<out PipelineChoice>,
    value: PipelineChoice,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ): Component {
    val ink = if (selected) list.selectionForeground else list.foreground
    val mutedInk = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
    val background = if (selected) list.selectionBackground else list.background
    return when (value) {
      is PipelineChoice.Run -> {
        nameLabel.text = value.pipeline.name
        nameLabel.font = list.font
        nameLabel.foreground = ink
        countLabel.text = value.pipeline.steps.size.toString()
        countLabel.font = list.font
        countLabel.foreground = mutedInk
        chain.show(PipelineChain.tokens(value.pipeline.steps), mutedInk)
        pipelineRow.background = background
        pipelineRow.toolTipText = pipelineTooltip(value.pipeline)
        pipelineRow
      }
      PipelineChoice.OpenFile -> {
        openLabel.text = t("pipeline.picker.open")
        openLabel.font = list.font
        openLabel.foreground = ink
        openRow.background = background
        openRow.toolTipText = null
        openRow
      }
    }
  }

  private fun separator(): Color =
    JBColor.namedColor("Vibe.Composer.separator", JBColor.namedColor("Separator.separatorColor", JBColor.border()))

  private companion object {
    // Unscaled gap between the name and the number of steps
    const val NAME_GAP = 12

    // Unscaled gap between the two lines of a row
    const val LINE_GAP = 1

    // Unscaled vertical padding of a row
    const val ROW_PAD_V = 3
  }
}

/**
 * A row whose width is kept between a least and a greatest value
 * The menu is as wide as its widest row, and a long name or chain would stretch it across the screen; the row is cut instead
 */
internal class CappedRow(layout: BorderLayout) : JPanel(layout) {
  override fun getPreferredSize(): Dimension {
    val size = super.getPreferredSize()
    return Dimension(size.width.coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(MAX_WIDTH)), size.height)
  }

  private companion object {
    // Unscaled least and greatest width a row asks for
    const val MIN_WIDTH = 260
    const val MAX_WIDTH = 440
  }
}

/**
 * The chain of steps in one line: roles in the small font, a step's own model in the smallest
 * Cut with an ellipsis at the width it gets, since a wrapped chain would make the rows of the menu uneven
 */
internal class PipelineChainLabel : JComponent() {
  private class Piece(val text: String, val font: Font)

  var tokens: List<PipelineChain.Token> = emptyList()
    private set

  init {
    isOpaque = false
  }

  fun show(tokens: List<PipelineChain.Token>, ink: Color) {
    this.tokens = tokens
    foreground = ink
  }

  private fun pieces(): List<Piece> = tokens.map { Piece(it.text, if (it.kind == PipelineChain.Kind.MODEL) MODEL_FONT else CHAIN_FONT) }

  private fun widthOf(text: String, font: Font): Int = getFontMetrics(font).stringWidth(text)

  override fun getPreferredSize(): Dimension =
    Dimension(pieces().sumOf { widthOf(it.text, it.font) }, getFontMetrics(CHAIN_FONT).height)

  /** The text that fits in [limit], cut with an ellipsis when the whole chain does not */
  internal fun shownText(limit: Int): String = fitted(limit).joinToString("") { it.text }

  /** The pieces that fit in [limit], the last of them cut with an ellipsis when the whole chain does not */
  private fun fitted(limit: Int): List<Piece> {
    val all = pieces()
    if (all.sumOf { widthOf(it.text, it.font) } <= limit) return all
    val ellipsis = widthOf(ELLIPSIS, CHAIN_FONT)
    val result = ArrayList<Piece>()
    var used = 0
    for (piece in all) {
      val whole = widthOf(piece.text, piece.font)
      if (used + whole + ellipsis <= limit) {
        result += piece
        used += whole
        continue
      }
      var cut = piece.text.length
      while (cut > 0 && used + widthOf(piece.text.substring(0, cut), piece.font) + ellipsis > limit) cut--
      result += Piece(piece.text.substring(0, cut).trimEnd() + ELLIPSIS, piece.font)
      break
    }
    return result
  }

  override fun paintComponent(g: Graphics) {
    val g2 = g.create() as Graphics2D
    try {
      GraphicsUtil.setupAAPainting(g2)
      g2.color = foreground
      val baseline = getFontMetrics(CHAIN_FONT).ascent
      var x = 0
      for (piece in fitted(width)) {
        g2.font = piece.font
        g2.drawString(piece.text, x, baseline)
        x += widthOf(piece.text, piece.font)
      }
    }
    finally {
      g2.dispose()
    }
  }

  private companion object {
    const val ELLIPSIS = "…"
    val CHAIN_FONT: Font get() = JBUI.Fonts.smallFont()
    val MODEL_FONT: Font get() = JBUI.Fonts.miniFont()
  }
}
