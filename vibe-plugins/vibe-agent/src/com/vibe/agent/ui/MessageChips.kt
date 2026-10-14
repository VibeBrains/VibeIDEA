// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/** The three things one does with a message that already happened */
internal enum class MessageChip { PIN, REWIND, BRANCH }

/**
 * Which chips of a message are shown
 *
 * They appear while the pointer is over the row, except the pin of a pinned message: that one stays
 * So the state of the message reads without hovering
 */
internal object MessageChipRules {
  fun visible(chip: MessageChip, hovered: Boolean, pinned: Boolean): Boolean = hovered || (chip == MessageChip.PIN && pinned)
}

/**
 * A linear fade between two opacities over a fixed time, driven by a clock that is passed in
 *
 * Retargeting in the middle of a fade starts from the opacity reached so far, so quickly moving in and out of a row never makes a chip jump
 */
internal class ChipFade(private val durationMs: Long, initial: Float) {
  private var from = initial
  private var to = initial
  private var startedAt = 0L

  val target: Float get() = to

  fun retarget(target: Float, nowMs: Long) {
    if (target == to) return
    from = alpha(nowMs)
    to = target
    startedAt = nowMs
  }

  fun alpha(nowMs: Long): Float {
    if (durationMs <= 0L) return to
    val progress = ((nowMs - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
    return from + (to - from) * progress
  }

  fun settled(nowMs: Long): Boolean = durationMs <= 0L || nowMs - startedAt >= durationMs
}

/** What a chip does and says; the text is the tooltip */
internal class MessageChipAction(val tooltip: String, val onClick: () -> Unit)

/**
 * The chips in the top right corner of a user's message, over the bubble and sticking out of its corner
 *
 * They are drawn only while visible and let every mouse event through otherwise, so the text under them stays selectable
 * A pinned message keeps its pin on, tinted with the accent
 */
internal class MessageChipStrip(
  private val pinned: Boolean,
  actions: Map<MessageChip, MessageChipAction>,
) : JPanel(null) {
  private val chips = MessageChip.entries.filter { it in actions }.map { Chip(it, actions.getValue(it)) }
  private val ticker = Timer(FRAME_MS) { tick() }.apply { isRepeats = true }

  init {
    isOpaque = false
    chips.forEach { add(it) }
    val size = JBUI.scale(ChatLayoutSpec.CHIP_SIZE)
    val gap = JBUI.scale(ChatLayoutSpec.CHIP_GAP)
    chips.forEachIndexed { index, chip -> chip.setBounds(index * (size + gap), 0, size, size) }
    preferredSize = Dimension(chips.size * size + (chips.size - 1).coerceAtLeast(0) * gap, size)
  }

  /** The pointer entered or left the row */
  fun setHovered(hovered: Boolean) {
    val now = System.currentTimeMillis()
    chips.forEach { it.fade.retarget(if (MessageChipRules.visible(it.kind, hovered, pinned)) 1f else 0f, now) }
    if (!ticker.isRunning) ticker.start()
  }

  /** Only the drawn chips take the mouse; the empty rest of the strip belongs to the text below */
  override fun contains(x: Int, y: Int): Boolean = chips.any { it.interactive && it.bounds.contains(x, y) }

  override fun removeNotify() {
    ticker.stop()
    super.removeNotify()
  }

  private fun tick() {
    val now = System.currentTimeMillis()
    repaint()
    if (chips.all { it.fade.settled(now) }) ticker.stop()
  }

  private inner class Chip(val kind: MessageChip, action: MessageChipAction) : JComponent() {
    val fade = ChipFade(ChatLayoutSpec.CHIP_FADE_MS.toLong(), if (MessageChipRules.visible(kind, hovered = false, pinned = pinned)) 1f else 0f)
    private var tinted: Pair<Color, Icon>? = null

    /** Clickable only while shown: an invisible chip must not swallow a click meant for the text */
    val interactive: Boolean get() = fade.target > 0f

    init {
      isOpaque = false
      toolTipText = action.tooltip
      cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          if (interactive) action.onClick()
        }
      })
    }

    override fun contains(x: Int, y: Int): Boolean = interactive && super.contains(x, y)

    private val accent: Boolean get() = kind == MessageChip.PIN && pinned

    override fun paintComponent(g: Graphics) {
      val alpha = fade.alpha(System.currentTimeMillis())
      if (alpha <= 0f) return
      val g2 = g.create() as Graphics2D
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.composite = AlphaComposite.SrcOver.derive(alpha)
        val arc = JBUI.scale(ChatLayoutSpec.CHIP_RADIUS) * 2
        g2.color = ChatTheme.BACKGROUND
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        g2.color = if (accent) ChatTabsStrip.UNDERLINE else ChatTabsStrip.SEPARATOR
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        val icon = icon()
        icon.paintIcon(this, g2, (width - icon.iconWidth) / 2, (height - icon.iconHeight) / 2)
      }
      finally {
        g2.dispose()
      }
    }

    /** The glyph fits the chip less its padding; the pin of a pinned message takes the accent colour */
    private fun icon(): Icon {
      val inner = ChatLayoutSpec.CHIP_SIZE - 2 * ChatLayoutSpec.CHIP_PAD
      val base = when (kind) {
        MessageChip.PIN -> AllIcons.General.Pin_tab
        MessageChip.REWIND -> AllIcons.Actions.Rollback
        MessageChip.BRANCH -> AllIcons.Vcs.Branch
      }
      val sized = IconUtil.scale(base, this, inner.toFloat() / base.iconWidth)
      if (!accent) return sized
      val color = ChatTabsStrip.UNDERLINE
      tinted?.takeIf { it.first == color }?.let { return it.second }
      return IconUtil.colorize(sized, color).also { tinted = color to it }
    }
  }

  private companion object {
    const val FRAME_MS = 16
  }
}
