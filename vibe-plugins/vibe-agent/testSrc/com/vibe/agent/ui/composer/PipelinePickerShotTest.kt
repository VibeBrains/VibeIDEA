// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.defaults.VibeDefaults
import com.vibe.agent.pipelines.PipelinesFile
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JToolTip
import kotlin.test.Test

/**
 * The pipeline menu on the pipelines of the shipped seed, drawn into PNG files so that it can be looked at
 *
 * Numbers say how wide the row is; only a picture says whether two lines and a rule read as a menu
 * The list is the one the popup builds, with the same renderer, laid out at the width the popup would give it
 *
 * It asserts nothing and writes nothing unless `VIBE_SHOTS` names a folder: it is a tool, not a check
 */
class PipelinePickerShotTest {
  private val shown = listOf("review-wave", "cascade-review", "split-by-difficulty", "feature", "cheap-write-strong-review")

  private class Palette(val background: Color, val foreground: Color, val selectionBackground: Color, val selectionForeground: Color)

  // What a LaF puts into the list defaults; without a LaF in the test they are empty, and the text would be painted in no colour
  private val light = Palette(Color(0xfd, 0xfc, 0xfa), Color(0x2b, 0x2f, 0x33), Color(0xb9, 0xd0, 0xe6), Color(0x2b, 0x2f, 0x33))
  private val night = Palette(Color(0x16, 0x17, 0x1B), Color(0xE6, 0xE8, 0xEF), Color(0x1d, 0x3b, 0x47), Color(0xE6, 0xE8, 0xEF))

  private fun seedPipelines() = run {
    val text = VibeDefaults::class.java.getResourceAsStream("/vibeDefaults/pipelines.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    val base = Files.createTempDirectory("vibe-picker-shot")
    Files.createDirectories(PipelinesFile.path(base.toString()).parent)
    Files.writeString(PipelinesFile.path(base.toString()), text)
    PipelinesFile.load(base.toString()) {}.filter { it.id in shown }.sortedBy { shown.indexOf(it.id) }
  }

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  private fun invalidateAll(component: Component) {
    component.invalidate()
    if (component is Container) component.components.forEach { invalidateAll(it) }
  }

  private fun print(component: Component, width: Int, height: Int, background: Color, file: File) {
    repeat(3) {
      invalidateAll(component)
      component.setBounds(0, 0, width, height)
      layOut(component)
    }
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = background
    g.fillRect(0, 0, width, height)
    component.printAll(g)
    g.dispose()
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
  }

  /**
   * The rows stacked the way the popup list stacks them, each laid out at the width of the widest
   * Rows are taken from the renderer itself, so the picture shows what the list would paint
   */
  private fun shoot(choices: List<PipelineChoice>, palette: Palette, selected: Int, width: Int?, file: File) {
    val renderer = PipelineRowRenderer()
    val list = JList(DefaultListModel<PipelineChoice>().apply { choices.forEach { addElement(it) } }).apply {
      font = JBFont.label()
      background = palette.background
      foreground = palette.foreground
      selectionBackground = palette.selectionBackground
      selectionForeground = palette.selectionForeground
    }
    val heights = choices.mapIndexed { i, c -> (renderer.getListCellRendererComponent(list, c, i, i == selected, false) as JComponent).preferredSize }
    val w = width ?: heights.maxOf { it.width }
    val image = BufferedImage(w, heights.sumOf { it.height }, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = palette.background
    g.fillRect(0, 0, w, image.height)
    var y = 0
    choices.forEachIndexed { i, choice ->
      val row = renderer.getListCellRendererComponent(list, choice, i, i == selected, false) as JComponent
      row.isOpaque = true
      repeat(3) {
        invalidateAll(row)
        row.setBounds(0, 0, w, heights[i].height)
        layOut(row)
      }
      val part = g.create(0, y, w, heights[i].height)
      row.printAll(part)
      part.dispose()
      y += heights[i].height
    }
    g.dispose()
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
  }

  @Test
  fun `shots of the pipeline menu`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    val pipelines = seedPipelines()
    val choices = pipelines.map { PipelineChoice.Run(it) } + PipelineChoice.OpenFile
    shoot(choices, light, -1, null, File(out, "pipelines-plain.png"))
    shoot(choices, light, 1, null, File(out, "pipelines-selected.png"))
    shoot(choices, night, 1, null, File(out, "pipelines-dark.png"))
    // A narrow popup: the longest chain is cut, not the menu stretched
    shoot(choices, light, -1, JBUI.scale(300), File(out, "pipelines-narrow.png"))
    val tooltip = JToolTip().apply { tipText = pipelineTooltip(pipelines.first { it.id == "cascade-review" }) }
    print(tooltip, tooltip.preferredSize.width, tooltip.preferredSize.height, Color.WHITE, File(out, "pipelines-tooltip.png"))
  }
}
