// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.AbstractBorder
import kotlin.math.max

/**
 * The row's own padding with the reply's gutter on the right
 *
 * The gutter is a share of the feed's width, so it is read from the feed's layout each time the row asks for its insets
 * And the row never has to be rebuilt when the panel is resized
 */
internal class AssistantRowBorder(private val metrics: ChatFeedMetrics) : AbstractBorder() {
  override fun getBorderInsets(c: Component?): Insets = getBorderInsets(c, Insets(0, 0, 0, 0))

  override fun getBorderInsets(c: Component?, insets: Insets): Insets {
    insets.set(
      JBUI.scale(ChatLayoutSpec.ROW_PAD_TOP), JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE),
      JBUI.scale(ChatLayoutSpec.ROW_PAD_BOTTOM), JBUI.scale(ChatLayoutSpec.ROW_PAD_SIDE) + metrics.layout.assistantRightMargin)
    return insets
  }
}

/** The rule on the left of a reply, and the room it and its gap take; both are zero where the layout drops the rule */
internal class AssistantRuleBorder(private val metrics: ChatFeedMetrics, private val color: () -> Color) : AbstractBorder() {
  override fun getBorderInsets(c: Component?): Insets = getBorderInsets(c, Insets(0, 0, 0, 0))

  override fun getBorderInsets(c: Component?, insets: Insets): Insets {
    val layout = metrics.layout
    insets.set(0, layout.assistantRule + layout.assistantRuleGap, 0, 0)
    return insets
  }

  override fun paintBorder(c: Component?, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
    val rule = metrics.layout.assistantRule
    if (rule <= 0) return
    g.color = color()
    g.fillRect(x, y, rule, height)
  }
}

/** Time and date texts of a message */
internal object ChatTimeText {
  /** The full date and time of a message, for the tooltip of its clock time; null when the record has no usable stamp */
  fun dateTooltip(atIso: String?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val instant = atIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)
  }
}

/**
 * Follows whether the pointer is over a row or any of its parts
 *
 * Swing sends an exit to the row when the pointer moves onto a child, so every part is listened to
 * And a pointer that left one part is looked up again before the row is called not hovered
 */
internal object HoverTracking {
  fun attach(root: JComponent, onChange: (hovered: Boolean) -> Unit) {
    var hovered = false
    fun set(value: Boolean) {
      if (value == hovered) return
      hovered = value
      onChange(value)
    }
    val listener = object : MouseAdapter() {
      override fun mouseEntered(e: MouseEvent) = set(true)
      override fun mouseExited(e: MouseEvent) = set(runCatching { root.getMousePosition(true) != null }.getOrDefault(false))
    }
    fun hook(component: Component) {
      component.addMouseListener(listener)
      (component as? Container)?.components?.forEach { hook(it) }
    }
    hook(root)
  }
}

/** A small caption in the bubble's secondary colour: the clock time in the corner, or the «You · time» line of the plate */
private fun metaCaption(text: String, tooltip: String?): JLabel = JLabel(text).apply {
  font = JBFont.label().deriveFont(Font.PLAIN, ChatLayoutSpec.USER_META_FONT)
  foreground = ChatTheme.USER_MESSAGE_LABEL
  toolTipText = tooltip
  isOpaque = false
}

/**
 * The user's message: a bubble that fits its text, with the time in its corner
 *
 * The shape follows the feed's layout
 * Wide and narrow: an outline all round and a small radius at the bottom right as a tail, the time at the bottom right inside
 * Tight: a plate across the row with a bar on its left, and the time moved into a «You · time» caption above the text
 * Both labels are always in the bubble and the layout shows one
 *
 * [text] is the prose area with the message
 */
internal class UserBubble(
  private val text: JTextArea,
  time: String,
  caption: String,
  dateTooltip: String?,
  private val metrics: ChatFeedMetrics,
) : JPanel(null) {
  private val timeLabel = metaCaption(time, dateTooltip)
  private val captionLabel = metaCaption(caption, dateTooltip)
  private var measuredFor: Font? = null
  private var measuredWidth = 0

  /** A copy of the text that is only ever measured: see [textHeight] */
  private val probe = JTextArea(text.text).apply {
    lineWrap = true
    wrapStyleWord = true
    border = this@UserBubble.text.border
  }
  private var probedWidth = -1
  private var probedHeight = 0
  private var probedFont: Font? = null

  init {
    isOpaque = false
    add(captionLabel)
    add(text)
    add(timeLabel)
  }

  /** The bubble's size when the row leaves [available] pixels of width for the bubble and its left margin */
  fun sizeFor(available: Int): Dimension {
    val layout = metrics.layout
    val content = max(naturalTextWidth(), if (layout.timeInCorner) timeLabel.preferredSize.width else captionLabel.preferredSize.width)
    val width = bubbleWidth(layout, content, available - layout.userLeftMargin)
    val contentWidth = max(1, width - layout.insetLeft - layout.insetRight)
    var height = layout.insetTop + layout.insetBottom + textHeight(contentWidth)
    if (layout.captionAbove) height += captionLabel.preferredSize.height + JBUI.scale(ChatLayoutSpec.USER_CAPTION_GAP)
    if (layout.timeInCorner) height += timeLabel.preferredSize.height + JBUI.scale(ChatLayoutSpec.USER_TIME_GAP)
    return Dimension(width, height)
  }

  override fun doLayout() {
    val layout = metrics.layout
    captionLabel.isVisible = layout.captionAbove
    timeLabel.isVisible = layout.timeInCorner
    val contentWidth = max(1, width - layout.insetLeft - layout.insetRight)
    var y = layout.insetTop
    if (layout.captionAbove) {
      val size = captionLabel.preferredSize
      captionLabel.setBounds(layout.insetLeft, y, contentWidth, size.height)
      y += size.height + JBUI.scale(ChatLayoutSpec.USER_CAPTION_GAP)
    }
    val textHeight = textHeight(contentWidth)
    text.setBounds(layout.insetLeft, y, contentWidth, textHeight)
    y += textHeight
    if (layout.timeInCorner) {
      val size = timeLabel.preferredSize
      timeLabel.setBounds(layout.insetLeft + contentWidth - size.width, y + JBUI.scale(ChatLayoutSpec.USER_TIME_GAP), size.width, size.height)
    }
  }

  override fun paintComponent(g: Graphics) {
    val layout = metrics.layout
    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
      val w = width.toFloat()
      val h = height.toFloat()
      g2.color = ChatTheme.USER_MESSAGE_BG
      g2.fill(ChatBubbleContour.path(0f, 0f, w, h, layout.radii))
      g2.color = ChatTheme.USER_MESSAGE_BORDER
      if (layout.bar > 0) {
        // The bar is cut by the plate's own corners, the way a border with a radius is
        g2.clip(ChatBubbleContour.path(0f, 0f, w, h, layout.radii))
        g2.fillRect(0, 0, layout.bar, height)
      }
      else if (layout.outline > 0) {
        g2.stroke = java.awt.BasicStroke(layout.outline.toFloat())
        g2.draw(ChatBubbleContour.inset(w, h, layout.radii, layout.outline / 2f))
      }
    }
    finally {
      g2.dispose()
    }
    super.paintComponent(g)
  }

  /** The longest line of the message without any wrapping, measured in the text's own font */
  private fun naturalTextWidth(): Int {
    val font = text.font
    if (measuredFor == font && measuredWidth > 0) return measuredWidth
    val fm = text.getFontMetrics(font)
    measuredWidth = text.text.lineSequence().maxOfOrNull { fm.stringWidth(it) } ?: 0
    measuredFor = font
    return measuredWidth
  }

  /**
   * The height the text takes when wrapped to [contentWidth]
   *
   * Asked of a twin of the text area that nobody sees, sized to the width and then asked for its preferred height
   * Sizing the visible area would invalidate the row it sits in in the middle of that row's own layout
   * The text's own view is not asked: for about one width in thirty it answers a line short, and the last line is cut
   */
  private fun textHeight(contentWidth: Int): Int {
    val font = text.font
    if (probedWidth == contentWidth && probedFont == font) return probedHeight
    probe.font = font
    probe.setSize(contentWidth, Short.MAX_VALUE.toInt())
    probedHeight = probe.preferredSize.height
    probedWidth = contentWidth
    probedFont = font
    return probedHeight
  }
}

/**
 * One row of the feed holding a user's message: the bubble pushed to the right edge, with the chips over its corner
 *
 * The row sizes itself from the bubble instead of leaving that to a layout manager: the bubble's width follows its text
 * And the text's height follows that width
 */
internal class UserMessageRow(
  private val bubble: UserBubble,
  private val chips: MessageChipStrip?,
  private val metrics: ChatFeedMetrics,
) : JPanel(null) {
  init {
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.empty(ChatLayoutSpec.ROW_PAD_TOP, ChatLayoutSpec.ROW_PAD_SIDE, ChatLayoutSpec.ROW_PAD_BOTTOM, ChatLayoutSpec.ROW_PAD_SIDE)
    // Chips first: the first child is painted last, so it lies over the bubble
    chips?.let { add(it) }
    add(bubble)
    chips?.let { strip -> HoverTracking.attach(this) { strip.setHovered(it) } }
  }

  private fun contentWidth(): Int {
    val insets = insets
    val whole = if (width > 0) width else metrics.rowWidth()
    return max(0, whole - insets.left - insets.right)
  }

  override fun getPreferredSize(): Dimension {
    val insets = insets
    val size = bubble.sizeFor(contentWidth())
    return Dimension(contentWidth() + insets.left + insets.right, size.height + insets.top + insets.bottom)
  }

  override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

  /** A row never stretches vertically */
  override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

  override fun doLayout() {
    val insets = insets
    val available = max(0, width - insets.left - insets.right)
    val size = bubble.sizeFor(available)
    bubble.setBounds(insets.left + available - size.width, insets.top, size.width, size.height)
    // The bubble's own layout depends on the feed's layout too, so it is laid out again even when its bounds stayed
    bubble.invalidate()
    chips?.let { strip ->
      val offset = JBUI.scale(ChatLayoutSpec.CHIP_OFFSET)
      val chipSize = strip.preferredSize
      strip.setBounds(bubble.x + bubble.width - chipSize.width + offset, bubble.y - offset, chipSize.width, chipSize.height)
    }
  }
}
