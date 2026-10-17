// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.BoxLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatReflowTest {
  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  /** A reply with code in it: prose of both kinds in a box-layout stack, two containers under the row */
  private class Feed {
    private val words = "word ".repeat(120)
    val area = JTextArea(words).apply { lineWrap = true; wrapStyleWord = true }
    val html = JEditorPane("text/html", "<html><body>$words</body></html>")
    private val stack = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(html); add(area) }
    val row = JPanel(BorderLayout()).apply { add(JPanel(BorderLayout()).apply { add(stack, BorderLayout.CENTER) }, BorderLayout.CENTER) }
    val feed = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(row) }
  }

  private fun settle(feed: Feed, width: Int) {
    // A wrapped text knows its height only once it has its width: the live panel gets there by repeated validation
    repeat(4) {
      ChatReflow.invalidateTree(feed.feed)
      feed.feed.setBounds(0, 0, width, feed.feed.preferredSize.height)
      layOut(feed.feed)
    }
  }

  @Test
  fun `text under nested stacks follows the width both wider and narrower`() {
    val f = Feed()
    var previousHeight = Int.MAX_VALUE
    for (width in listOf(300, 700, 300, 900)) {
      settle(f, width)
      assertEquals(width, f.area.width, "the text takes the new width")
      assertEquals(width, f.html.width)
      if (width == 700 || width == 900) assertTrue(f.row.height < previousHeight, "wider text is shorter: ${f.row.height} < $previousHeight")
      previousHeight = f.row.height
    }
  }

  /** A layout that counts how often it is told its measurements are stale: the way a box layout drops its cache */
  private class Counting : java.awt.LayoutManager2 {
    var invalidations = 0
    override fun addLayoutComponent(comp: Component, constraints: Any?) {}
    override fun addLayoutComponent(name: String?, comp: Component) {}
    override fun removeLayoutComponent(comp: Component) {}
    override fun preferredLayoutSize(parent: Container) = java.awt.Dimension()
    override fun minimumLayoutSize(parent: Container) = java.awt.Dimension()
    override fun maximumLayoutSize(target: Container) = java.awt.Dimension()
    override fun layoutContainer(parent: Container) {}
    override fun getLayoutAlignmentX(target: Container) = 0f
    override fun getLayoutAlignmentY(target: Container) = 0f
    override fun invalidateLayout(target: Container) { invalidations++ }
  }

  @Test
  fun `invalidation reaches every descendant`() {
    val counting = Counting()
    val leaf = JPanel(counting)
    val root = JPanel(BorderLayout()).apply { add(JPanel(BorderLayout()).apply { add(leaf) }) }
    val before = counting.invalidations
    ChatReflow.invalidateTree(root)
    assertEquals(before + 1, counting.invalidations)
  }

  /** A tool, not a check: the same feed narrow, then widened in place, written where `VIBE_SHOTS` points */
  @Test
  fun `shots of one feed narrow and then wide`() {
    val folder = System.getenv("VIBE_SHOTS")?.let { java.io.File(it) } ?: return
    folder.mkdirs()
    val f = Feed()
    f.feed.background = java.awt.Color.WHITE
    for (width in listOf(300, 700)) {
      settle(f, width)
      val image = java.awt.image.BufferedImage(width, f.feed.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
      val g = image.createGraphics()
      g.color = java.awt.Color.WHITE
      g.fillRect(0, 0, width, f.feed.height)
      f.feed.printAll(g)
      g.dispose()
      javax.imageio.ImageIO.write(image, "png", java.io.File(folder, "reflow-$width.png"))
    }
  }
}
