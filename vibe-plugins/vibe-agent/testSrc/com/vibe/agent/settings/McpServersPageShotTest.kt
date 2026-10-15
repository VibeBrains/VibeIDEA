// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager
import kotlin.test.Test

/**
 * The MCP servers page drawn into PNG files, so that it can be looked at
 *
 * The page is assembled from the same view, forms and tokens as the real one, with the palette of two real themes put into the
 * UI defaults the way a theme file puts it, and laid out at a given width
 *
 * It asserts nothing and writes nothing unless `VIBE_SHOTS` names a folder: it is a tool, not a check
 */
class McpServersPageShotTest {
  private class Palette(
    val name: String, val page: Color, val card: Color, val border: Color, val text: Color,
    val ok: Color, val warn: Color, val error: Color,
  )

  private val neon = Palette("neon", Color(0x1E, 0x1F, 0x24), Color(0x16, 0x17, 0x1B), Color(0x3A, 0x40, 0x50), Color(0xE6, 0xE8, 0xEF),
                             Color(0x5F, 0xAD, 0x5F), Color(0xE0, 0xA0, 0x30), Color(0xFF, 0x6B, 0x5A))
  private val porcelain = Palette(
    "porcelain", Color(0xf1, 0xee, 0xe9), Color(0xfd, 0xfc, 0xfa), Color(0xd9, 0xd4, 0xcc), Color(0x2b, 0x2f, 0x33),
    Color(0x4C, 0xB7, 0x82), Color(0xB8, 0x86, 0x0B), Color(0xE0, 0x52, 0x52))

  private val tokens = listOf("Vibe.Settings.cardBackground", "Vibe.Settings.cardBorder", "Vibe.Settings.waitingBorder",
                              "Vibe.Status.ready", "Vibe.Status.waiting", "Vibe.Status.error")
  private val defaults = listOf("Panel.background", "Label.foreground", "CheckBox.foreground", "CheckBox.background",
                                "TextField.foreground", "TextField.background", "TextArea.foreground", "TextArea.background")

  private fun installPalette(palette: Palette) {
    (tokens + defaults).forEach { UIManager.put(it, null) }
    UIManager.put("Vibe.Settings.cardBackground", palette.card)
    UIManager.put("Vibe.Settings.cardBorder", palette.border)
    UIManager.put("Vibe.Settings.waitingBorder", palette.border)
    UIManager.put("Vibe.Status.ready", palette.ok)
    UIManager.put("Vibe.Status.waiting", palette.warn)
    UIManager.put("Vibe.Status.error", palette.error)
    UIManager.put("Panel.background", palette.page)
    UIManager.put("Label.foreground", palette.text)
    UIManager.put("CheckBox.foreground", palette.text)
    UIManager.put("CheckBox.background", palette.page)
    for (field in listOf("TextField", "TextArea")) {
      UIManager.put("$field.foreground", palette.text)
      UIManager.put("$field.background", palette.card)
    }
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

  private fun paintBackgrounds(component: Component, color: Color) {
    if (component is JScrollPane) {
      component.background = color
      component.viewport.background = color
    }
    if (component is Container) component.components.forEach { paintBackgrounds(it, color) }
  }

  /** The page as the settings dialog shows it: the title line on top, the cards scrolling under it */
  private fun outerOf(fixture: McpServersPageFixture, palette: Palette): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
    isOpaque = true
    background = palette.page
    border = JBUI.Borders.empty(8)
    add(fixture.view.header, BorderLayout.NORTH)
    add(SettingsUi.page(fixture.view.content), BorderLayout.CENTER)
    paintBackgrounds(this, palette.page)
  }

  private fun shoot(out: File, name: String, palette: Palette, width: Int, outer: JComponent) {
    // Tall at first, so that the cards are not clipped, then cut to the height they really took
    outer.setBounds(0, 0, width, TALL)
    repeat(4) {
      invalidateAll(outer)
      layOut(outer)
    }
    val content = (outer.getComponent(1) as JScrollPane).viewport.view
    val height = outer.getComponent(0).height + content.preferredSize.height + JBUI.scale(TRIM)
    outer.setBounds(0, 0, width, height)
    repeat(4) {
      invalidateAll(outer)
      layOut(outer)
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = palette.page
    g.fillRect(0, 0, width, height)
    outer.printAll(g)
    g.dispose()
    out.mkdirs()
    ImageIO.write(image, "png", File(out, "mcp-$name-${palette.name}-$width.png"))
  }

  @Test
  fun `shots of the page at the widths that matter`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    for (palette in listOf(neon, porcelain)) {
      installPalette(palette)

      val empty = McpServersPageFixture().rendered(McpServersPageFixture().model(cards = emptyList(), fileExists = false))
      shoot(out, "empty", palette, 660, outerOf(empty, palette))

      shoot(out, "servers", palette, 660, outerOf(McpServersPageFixture().rendered(), palette))
      shoot(out, "servers", palette, 420, outerOf(McpServersPageFixture().rendered(), palette))

      val open = McpServersPageFixture().rendered()
      open.view.cardFolds().forEach { it.expanded = true }
      shoot(out, "servers-open", palette, 660, outerOf(open, palette))

      val form = McpServersPageFixture().rendered()
      form.view.showForm()
      shoot(out, "form", palette, 660, outerOf(form, palette))

      val mistake = McpServersPageFixture().rendered()
      mistake.view.showForm()
      mistake.view.formPanel.type(name = "jira", command = "uvx", args = "mcp-jira --url \"https://x y\"",
                                  env = "JIRA_URL=https://x\nTOKEN")
      mistake.view.formPanel.send()
      shoot(out, "form-error", palette, 660, outerOf(mistake, palette))
    }
    (tokens + defaults).forEach { UIManager.put(it, null) }
  }

  private companion object {
    const val TALL = 3000
    const val TRIM = 24
  }
}
