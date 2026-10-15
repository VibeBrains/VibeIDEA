// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager
import kotlin.test.Test

/**
 * The Models page drawn into PNG files, so that it can be looked at
 *
 * The page is assembled from the same view, forms and tokens as the real one, with the palette of two real themes put into the
 * UI defaults the way a theme file puts it, and laid out at a given width
 *
 * It asserts nothing and writes nothing unless `VIBE_SHOTS` names a folder: it is a tool, not a check
 */
class ModelsPageShotTest {
  private class Palette(
    val name: String, val page: Color, val card: Color, val border: Color, val text: Color,
  )

  private val neon = Palette("neon", Color(0x1E, 0x1F, 0x24), Color(0x16, 0x17, 0x1B), Color(0x3A, 0x40, 0x50), Color(0xE6, 0xE8, 0xEF))
  private val porcelain = Palette(
    "porcelain", Color(0xf1, 0xee, 0xe9), Color(0xfd, 0xfc, 0xfa), Color(0xd9, 0xd4, 0xcc), Color(0x2b, 0x2f, 0x33))

  private val tokens = listOf("Vibe.Settings.cardBackground", "Vibe.Settings.cardBorder", "Vibe.Settings.waitingBorder")
  private val defaults = listOf("Panel.background", "Label.foreground", "CheckBox.foreground", "CheckBox.background",
                                "TextField.foreground", "TextField.background")

  private fun installPalette(palette: Palette) {
    (tokens + defaults).forEach { UIManager.put(it, null) }
    UIManager.put("Vibe.Settings.cardBackground", palette.card)
    UIManager.put("Vibe.Settings.cardBorder", palette.border)
    UIManager.put("Vibe.Settings.waitingBorder", palette.border)
    UIManager.put("Panel.background", palette.page)
    UIManager.put("Label.foreground", palette.text)
    UIManager.put("CheckBox.foreground", palette.text)
    UIManager.put("CheckBox.background", palette.page)
    UIManager.put("TextField.foreground", palette.text)
    UIManager.put("TextField.background", palette.card)
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
  private fun outerOf(fixture: ModelsPageFixture, palette: Palette): JComponent = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
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
    ImageIO.write(image, "png", File(out, "models-$name-${palette.name}-$width.png"))
  }

  private val catalogOfOpenai = listOf("gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-codex", "gpt-4.1", "gpt-4.1-mini", "o3", "o3-mini",
                                       "o4-mini", "gpt-4o", "gpt-4o-mini", "codex-mini")

  private fun fixture(): ModelsPageFixture {
    val fixture = ModelsPageFixture().rendered()
    val noon = LocalDate.now().atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fixture.view.addCatalog("openai", catalogOfOpenai)
    fixture.view.addCatalog("zai", listOf("glm-4.6", "glm-4.5v", "glm-4.5-air", "glm-4.5", "glm-4.5-flash"))
    fixture.view.setSource("openai", ModelsPage.Source.Catalog(catalogOfOpenai.size, noon))
    fixture.view.setSource("zai", ModelsPage.Source.Catalog(5, noon, added = 2))
    fixture.view.setSource("ollama", ModelsPage.Source.File)
    return fixture
  }

  @Test
  fun `shots of the page at the widths that matter`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    for (palette in listOf(neon, porcelain)) {
      installPalette(palette)

      shoot(out, "collapsed", palette, 660, outerOf(fixture(), palette))
      shoot(out, "collapsed", palette, 420, outerOf(fixture(), palette))

      val open = fixture()
      open.foldOf("openai").expanded = true
      open.onlyEnabledOf("openai").doClick()
      open.searchOf("openai").text = "gpt-5"
      shoot(out, "open-search", palette, 660, outerOf(open, palette))

      val waiting = fixture()
      waiting.folds().first { it.parent is CardPanel && (it.parent as CardPanel).dashed }.expanded = true
      waiting.foldOf("anthropic").expanded = true
      shoot(out, "waiting-open", palette, 660, outerOf(waiting, palette))
    }
    (tokens + defaults).forEach { UIManager.put(it, null) }
  }

  private companion object {
    const val TALL = 3000
    const val TRIM = 24
  }
}
