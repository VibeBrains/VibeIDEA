// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The confirmation dialog's content for the texts that used to stretch the platform dialog across the screen
 * The width and height bounds are checked always; pictures are written only when `VIBE_SHOTS` names a folder
 */
class VibeConfirmDialogTest {
  private val longCommand = "cd /Volumes/Storage/Projects/VibeCode/VibeIDEA && ./bazel.cmd test //vibe-plugins/vibe-agent:vibe-agent_test " +
    "//vibe-plugins/vibe-lsp:vibe-lsp_test --test_output=errors --test_env=VIBE_SHOTS=/private/tmp/shots && " +
    "./vibe-plugins/tools/checkVibeUi.sh && ./vibe-plugins/tools/checkVibeI18n.sh && ./vibe-plugins/tools/checkVibeBranding.sh && " +
    "git status --short | grep -v '^??' | awk '{print \$2}' | xargs -I{} sh -c 'echo {}; wc -l {}' && echo done"

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  private fun textAreas(component: Component, into: MutableList<JTextArea> = mutableListOf()): List<JTextArea> {
    if (component is JTextArea) into += component
    if (component is Container) component.components.forEach { textAreas(it, into) }
    return into
  }

  private fun build(message: String, detail: String?): JComponent =
    VibeConfirmDialog.body(message, detail, UIManager.getIcon("OptionPane.questionIcon") ?: javax.swing.UIManager.getIcon("Tree.openIcon"))

  @Test
  fun `a long one-line command neither widens nor lengthens the dialog`() {
    val body = build("Bash", longCommand.repeat(5))
    val size = body.preferredSize
    // The text column, the icon and the gap: far from the width of the command on one line
    assertTrue(size.width <= JBUI.scale(VibeConfirmDialog.TEXT_WIDTH) + JBUI.scale(120), "width was ${size.width}")
    assertTrue(size.height <= JBUI.scale(520), "height was ${size.height}")
  }

  @Test
  fun `a short question stays short`() {
    val size = build("Delete the file?", null).preferredSize
    assertTrue(size.height < JBUI.scale(120), "height was ${size.height}")
  }

  @Test
  fun `shots of a long command in light and dark`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    listOf("light" to false, "dark" to true).forEach { (name, dark) ->
      JBColor.setDark(dark)
      try {
        val background = if (dark) Color(0x2B, 0x2D, 0x30) else Color(0xF2, 0xF2, 0xF2)
        val foreground = if (dark) Color(0xDF, 0xE1, 0xE5) else Color(0x1E, 0x1F, 0x22)
        listOf("command" to longCommand, "veryLong" to longCommand.repeat(6)).forEach { (label, command) ->
          val body = build("Bash", command)
          textAreas(body).filter { it.font.family != java.awt.Font.MONOSPACED }.forEach { it.foreground = foreground }
          val panel = JPanel(java.awt.BorderLayout()).apply {
            this.background = background
            border = JBUI.Borders.empty(16)
            add(body)
          }
          val size = panel.preferredSize
          repeat(3) { panel.setBounds(0, 0, size.width, size.height); layOut(panel) }
          val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
          val g = image.createGraphics()
          g.color = background
          g.fillRect(0, 0, size.width, size.height)
          panel.printAll(g)
          g.dispose()
          val file = File(folder, "confirm-$label-$name.png")
          file.parentFile.mkdirs()
          ImageIO.write(image, "png", file)
        }
      }
      finally {
        JBColor.setDark(false)
      }
    }
  }
}
