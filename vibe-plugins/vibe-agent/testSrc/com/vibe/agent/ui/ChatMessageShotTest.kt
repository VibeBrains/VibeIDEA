// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager
import kotlin.test.Test

/**
 * The feed at the widths that matter, drawn into PNG files so that it can be looked at.
 *
 * Numbers say where the bubble is; only a picture says whether it looks like the chat of VibeIDEA's sibling. The
 * feed is assembled from the same rows, borders and tokens as the real one and laid out at a given width, with the
 * palette of two real themes put into the UI defaults the way a theme file puts it
 *
 * It asserts nothing and writes nothing unless `VIBE_SHOTS` names a folder: it is a tool, not a check
 */
class ChatMessageShotTest {
  private class Palette(
    val name: String, val background: Color, val foreground: Color,
    val fill: Color, val outline: Color, val rule: Color, val label: Color,
  )

  private val neon = Palette(
    "neon", Color(0x16, 0x17, 0x1B), Color(0xE6, 0xE8, 0xEF),
    Color(0x00, 0xE5, 0xFF, 0x26), Color(0x00, 0xE5, 0xFF, 0x8c), Color(0xE6, 0xE8, 0xEF, 0x40), Color(0x00, 0xb8, 0xcc))
  private val porcelain = Palette(
    "porcelain", Color(0xfd, 0xfc, 0xfa), Color(0x2b, 0x2f, 0x33),
    Color(0x2f, 0x6f, 0x9e, 0x26), Color(0x2f, 0x6f, 0x9e, 0x8c), Color(0x2b, 0x2f, 0x33, 0x40), Color(0x54, 0x66, 0x74))

  private val tokens = listOf(
    "Vibe.Chat.background", "Vibe.Chat.userMessageBackground", "Vibe.Chat.userMessageBorder",
    "Vibe.Chat.assistantRule", "Vibe.Chat.userMessageLabel", "Vibe.Chat.metaForeground")

  private fun prose(text: String, palette: Palette) = JTextArea().apply {
    isEditable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    font = JBFont.label().deriveFont(13f)
    foreground = palette.foreground
    border = JBUI.Borders.empty()
    this.text = text
  }

  private fun invalidateAll(component: Component) {
    component.invalidate()
    if (component is Container) component.components.forEach { invalidateAll(it) }
  }

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  private fun user(text: String, palette: Palette, metrics: ChatFeedMetrics, chips: Boolean, pinned: Boolean = false, hover: Boolean = false): Pair<JPanel, MessageChipStrip?> {
    val bubble = UserBubble(prose(text, palette), "12:34", "You · 12:34", "14 Oct 2026, 12:34:56", metrics)
    val strip = if (chips) MessageChipStrip(pinned, MessageChip.entries.associateWith { MessageChipAction("tip") {} }) else null
    if (strip != null && hover) strip.setHovered(true)
    return UserMessageRow(bubble, strip, metrics) to strip
  }

  private fun assistant(text: String, palette: Palette, metrics: ChatFeedMetrics): JPanel {
    val body = JPanel(BorderLayout()).apply {
      isOpaque = false
      border = AssistantRuleBorder(metrics) { palette.rule }
      add(prose(text, palette), BorderLayout.CENTER)
    }
    val meta = JLabel("Agent · 12:35").apply {
      font = JBFont.label().deriveFont(Font.PLAIN, 11f)
      foreground = palette.label
    }
    return object : JPanel(BorderLayout(0, JBUI.scale(2))) {
      override fun getMaximumSize() = java.awt.Dimension(JBUI.scale(ChatLayoutSpec.ASSISTANT_MAX_WIDTH), preferredSize.height)
    }.apply {
      isOpaque = false
      alignmentX = Component.LEFT_ALIGNMENT
      border = AssistantRowBorder(metrics)
      add(body, BorderLayout.CENTER)
      add(meta, BorderLayout.SOUTH)
    }
  }

  private fun shoot(out: File, palette: Palette, width: Int) {
    tokens.forEach { UIManager.put(it, null) }
    UIManager.put("Vibe.Chat.background", palette.background)
    UIManager.put("Vibe.Chat.userMessageBackground", palette.fill)
    UIManager.put("Vibe.Chat.userMessageBorder", palette.outline)
    UIManager.put("Vibe.Chat.assistantRule", palette.rule)
    UIManager.put("Vibe.Chat.userMessageLabel", palette.label)
    UIManager.put("Vibe.Chat.metaForeground", palette.label)
    val metrics = ChatFeedMetrics()
    metrics.update(width)
    val strips = mutableListOf<MessageChipStrip>()
    val feed = JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      background = palette.background
      border = JBUI.Borders.empty(ChatLayoutSpec.FEED_PAD)
    }
    val shortText = "Fix the retry pause"
    val longText = "Please look at the retry policy: the pauses should be 1, 2 and 4 seconds, and a Retry-After longer than half a minute must stop the turn. https://example.com/a/very/long/link/that/does/not/break/anywhere/at/all/ever"
    val reply = "Done. The policy now waits 1, 2 and 4 seconds between attempts, honours a Retry-After of up to thirty seconds and stops with the length named when the provider asks for more."
    for ((text, chips, pinned, hover) in listOf(
      listOf<Any>(shortText, true, false, true), listOf<Any>(longText, true, false, false), listOf<Any>(shortText, true, true, false))) {
      val (row, strip) = user(text as String, palette, metrics, chips as Boolean, pinned as Boolean, hover as Boolean)
      strip?.let { strips += it }
      feed.add(row)
      if (feed.componentCount == 2) feed.add(assistant(reply, palette, metrics))
    }
    feed.add(assistant(reply, palette, metrics))
    // A wrapped text knows its height only once it has its width: the live panel gets there by repeated validation
    repeat(4) {
      invalidateAll(feed)
      feed.setBounds(0, 0, width, feed.preferredSize.height)
      layOut(feed)
    }
    Thread.sleep(260)
    val image = BufferedImage(width, feed.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    feed.printAll(g)
    g.dispose()
    strips.forEach { it.removeNotify() }
    out.mkdirs()
    ImageIO.write(image, "png", File(out, "chat-${palette.name}-$width.png"))
    tokens.forEach { UIManager.put(it, null) }
  }

  @Test
  fun `shots of the feed at the widths that matter`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    for (palette in listOf(neon, porcelain)) {
      for (width in listOf(560, 361, 360, 281, 280, 240)) shoot(out, palette, width)
    }
  }
}
