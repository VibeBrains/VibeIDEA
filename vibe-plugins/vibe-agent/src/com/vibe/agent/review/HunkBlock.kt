// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * The block above a hunk: a row with the Accept and Reject buttons and, under it, the old text the hunk replaced or removed
 *
 * Painted by hand rather than built of Swing components: an inlay that holds components has to be told its size,
 * resized with the editor and kept in step with scrolling, and a painted block is none of that
 * The click is handled by the editor's mouse listener, which asks [buttonAt] — the same geometry paints and hit-tests
 *
 * @param hunk the change the block belongs to; a click names it, so a list that moved meanwhile cannot redirect the click
 * @param removed the old lines to show, tabs already spelled out
 * @param hidden how many more old lines there were than fit
 * @param current whether the reader is on this hunk, which fills its toolbar row
 */
internal class HunkBlock(
  val hunk: Hunk,
  private val removed: List<String>,
  hidden: Int,
  private val current: Boolean,
) : EditorCustomElementRenderer {
  enum class Button { ACCEPT, REJECT }

  var hovered: Button? = null

  private val acceptText = t("review.accept")
  private val rejectText = t("review.reject")
  private val moreText: String? = if (hidden > 0) t("review.removedMore", "count" to hidden) else null
  private val oldRows: Int get() = removed.size + if (moreText != null) 1 else 0

  override fun calcWidthInPixels(inlay: Inlay<*>): Int {
    val editor = inlay.editor
    val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
    val widest = (removed + listOfNotNull(moreText)).maxOfOrNull { fm.stringWidth(it) } ?: 0
    val toolbar = buttonRects(editor).maxOf { it.value.x + it.value.width } + JBUI.scale(TOOLBAR_RIGHT_GAP)
    // As wide as the view, so the tinted old text reads as a band and not as a ragged strip
    return maxOf(toolbar, widest + JBUI.scale(TEXT_INSET) * 2, editor.scrollingModel.visibleArea.width)
  }

  override fun calcHeightInPixels(inlay: Inlay<*>): Int = toolbarHeight(inlay.editor) + oldRows * inlay.editor.lineHeight

  override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
    val editor = inlay.editor
    val g2 = g.create() as Graphics2D
    try {
      EditorUIUtil.setupAntialiasing(g2)
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      val toolbar = toolbarHeight(editor)
      if (current) {
        g2.color = ReviewColors.current
        g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, toolbar)
      }
      paintButtons(g2, editor, targetRegion)
      if (oldRows > 0) paintOldText(g2, editor, targetRegion, toolbar)
    }
    finally {
      g2.dispose()
    }
  }

  /** Which button is at [x], [y] counted from the top left corner of the block, if any */
  fun buttonAt(editor: Editor, x: Int, y: Int): Button? =
    buttonRects(editor).entries.firstOrNull { it.value.contains(x, y) }?.key

  private fun paintButtons(g: Graphics2D, editor: Editor, region: Rectangle) {
    g.font = labelFont()
    val fm = g.fontMetrics
    for ((button, rect) in buttonRects(editor)) {
      val color = if (button == Button.ACCEPT) ReviewColors.accept else ReviewColors.reject
      val x = region.x + rect.x
      val y = region.y + rect.y
      val arc = JBUI.scale(ARC)
      if (hovered == button) {
        g.color = withAlpha(color, HOVER_ALPHA)
        g.fillRoundRect(x, y, rect.width, rect.height, arc, arc)
      }
      g.color = color
      g.stroke = BasicStroke(JBUI.scale(1).toFloat())
      g.drawRoundRect(x, y, rect.width - 1, rect.height - 1, arc, arc)
      val label = if (button == Button.ACCEPT) acceptText else rejectText
      g.drawString(label, x + JBUI.scale(BUTTON_PAD_H), y + (rect.height - fm.height) / 2 + fm.ascent)
    }
  }

  private fun paintOldText(g: Graphics2D, editor: Editor, region: Rectangle, top: Int) {
    val lineHeight = editor.lineHeight
    g.color = ReviewColors.removed
    g.fillRect(region.x, region.y + top, region.width, oldRows * lineHeight)
    g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    val fm = g.fontMetrics
    g.color = editor.colorsScheme.defaultForeground
    removed.forEachIndexed { row, line ->
      g.drawString(line, region.x + JBUI.scale(TEXT_INSET), region.y + top + row * lineHeight + (lineHeight - fm.height) / 2 + fm.ascent)
    }
    moreText?.let {
      g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
      g.drawString(it, region.x + JBUI.scale(TEXT_INSET),
                   region.y + top + removed.size * lineHeight + (lineHeight - fm.height) / 2 + fm.ascent)
    }
  }

  /** The two buttons side by side, in coordinates of the block; the toolbar row is a little taller than a line of text */
  private fun buttonRects(editor: Editor): Map<Button, Rectangle> {
    val fm = editor.contentComponent.getFontMetrics(labelFont())
    val inset = JBUI.scale(BUTTON_INSET_V)
    val height = toolbarHeight(editor) - inset * 2
    val acceptWidth = fm.stringWidth(acceptText) + JBUI.scale(BUTTON_PAD_H) * 2
    val rejectWidth = fm.stringWidth(rejectText) + JBUI.scale(BUTTON_PAD_H) * 2
    val left = JBUI.scale(TEXT_INSET)
    return linkedMapOf(
      Button.ACCEPT to Rectangle(left, inset, acceptWidth, height),
      Button.REJECT to Rectangle(left + acceptWidth + JBUI.scale(BUTTON_GAP), inset, rejectWidth, height),
    )
  }

  private fun toolbarHeight(editor: Editor): Int = editor.lineHeight + JBUI.scale(TOOLBAR_EXTRA)

  private fun labelFont() = JBFont.small()

  private fun withAlpha(color: Color, alpha: Int) = Color(color.red, color.green, color.blue, alpha)

  private companion object {
    const val TEXT_INSET = 8
    const val TOOLBAR_RIGHT_GAP = 8
    const val TOOLBAR_EXTRA = 4
    const val BUTTON_INSET_V = 2
    const val BUTTON_PAD_H = 8
    const val BUTTON_GAP = 6
    const val ARC = 6
    const val HOVER_ALPHA = 48
  }
}
