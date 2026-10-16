// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBFont
import com.vibe.agent.acp.SessionConfigOption
import com.vibe.agent.acp.SessionConfigOptions
import com.vibe.agent.acp.SessionMode
import com.vibe.agent.acp.SessionModes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToolTip
import kotlin.test.Test

/**
 * The pills of an agent's modes and settings, their menus and their tooltips, drawn into PNG files so that they can be looked at
 * The data is what claude-agent-acp sends, including a description long enough to run along one line without the fix
 *
 * It asserts nothing and writes nothing unless `VIBE_SHOTS` names a folder: it is a tool, not a check
 */
class AcpMenuShotTest {
  private class Palette(val background: Color, val foreground: Color, val selectionBackground: Color, val selectionForeground: Color)

  // What a LaF puts into the list defaults; without a LaF in the test they are empty, and the text would be painted in no colour
  private val light = Palette(Color(0xfd, 0xfc, 0xfa), Color(0x2b, 0x2f, 0x33), Color(0xb9, 0xd0, 0xe6), Color(0x2b, 0x2f, 0x33))
  private val night = Palette(Color(0x16, 0x17, 0x1B), Color(0xE6, 0xE8, 0xEF), Color(0x1d, 0x3b, 0x47), Color(0xE6, 0xE8, 0xEF))

  private val modes = SessionModes(
    currentModeId = "default",
    available = listOf(
      SessionMode("default", "Manual", "Always ask before making changes"),
      SessionMode("acceptEdits", "Accept edits", "Automatically accept all file edits"),
      SessionMode("plan", "Plan", "Create a plan before making changes"),
      SessionMode("auto", "Auto", "Claude handles permission decisions"),
      SessionMode("bypassPermissions", "Bypass permissions", "Accepts all permissions"),
    ),
  )

  private val options: List<SessionConfigOption> = SessionConfigOptions.parse(
    Json.parseToJsonElement(
      """{"configOptions":[
      {"id":"mode","name":"Mode","description":"Session permission mode","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Manual","description":"Always ask before making changes"}]},
      {"id":"model","name":"Model","description":"AI model to use","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Default (recommended)","description":"Opus with 1M context, the most capable model for complex work and long sessions"},
                  {"value":"sonnet","name":"Sonnet","description":"Best for everyday tasks"},
                  {"value":"haiku","name":"Haiku","description":"Fastest for quick answers"}]},
      {"id":"effort","name":"Effort","description":"Available effort levels for this model","type":"select","currentValue":"high",
       "options":[{"value":"low","name":"Low"},{"value":"medium","name":"Medium"},{"value":"high","name":"High"},{"value":"xhigh","name":"Xhigh"},{"value":"max","name":"Max"}]},
      {"id":"fast","name":"Fast mode","description":"Faster responses on supported models — requires extra usage to be enabled for this account",
       "type":"boolean","currentValue":false},
      {"id":"agent","name":"Agent","description":"Main-thread agent persona","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Default","description":"Standard Claude Code agent"}]}
      ]}""",
    ).jsonObject,
  )

  /**
   * A drawn stand-in for a platform icon: those are SVG and report one pixel in a test with no application behind it,
   * and a menu without its check and help marks does not show what the person would see
   */
  private class Mark(private val glyph: String, private val color: Color, private val ring: Boolean) : Icon {
    override fun getIconWidth() = SIDE
    override fun getIconHeight() = SIDE
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
      val g2 = g.create()
      (g2 as java.awt.Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g2.color = color
      if (ring) g2.drawOval(x + 2, y + 2, SIDE - 5, SIDE - 5)
      g2.font = JBFont.label().deriveFont(java.awt.Font.BOLD, SIDE - 5f)
      val fm = g2.fontMetrics
      g2.drawString(glyph, x + (SIDE - fm.stringWidth(glyph)) / 2, y + (SIDE + fm.ascent - fm.descent) / 2)
      g2.dispose()
    }

    private companion object {
      const val SIDE = 16
    }
  }

  private fun standIns(component: Container, color: Color) {
    component.components.forEach { child ->
      if (child is JLabel) {
        if (child.icon === AllIcons.General.ContextHelp) child.icon = Mark("?", color, ring = true)
        if (child.icon === AllIcons.Actions.Checked) child.icon = Mark("\u2713", color, ring = false)
      }
      if (child is Container) standIns(child, color)
    }
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

  private fun print(component: JComponent, width: Int, height: Int, background: Color, file: File) {
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

  /** The rows stacked the way the popup list stacks them, all at the width of the widest */
  private fun shoot(rows: List<AcpMenuRow<*>>, palette: Palette, selected: Int, file: File) {
    val renderer = AcpMenuRenderer()
    val list = JList(DefaultListModel<AcpMenuRow<*>>().apply { rows.forEach { addElement(it) } }).apply {
      font = JBFont.label()
      background = palette.background
      foreground = palette.foreground
      selectionBackground = palette.selectionBackground
      selectionForeground = palette.selectionForeground
    }
    fun cell(i: Int): JComponent {
      val row = renderer.getListCellRendererComponent(list, rows[i], i, i == selected, false) as JComponent
      standIns(row, if (i == selected) palette.selectionForeground else palette.foreground)
      return row
    }
    val sizes = rows.indices.map { cell(it).preferredSize }
    val w = sizes.maxOf { it.width }
    val image = BufferedImage(w, sizes.sumOf { it.height }, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = palette.background
    g.fillRect(0, 0, w, image.height)
    var y = 0
    rows.indices.forEach { i ->
      val row = cell(i)
      row.isOpaque = true
      repeat(3) {
        invalidateAll(row)
        row.setBounds(0, 0, w, sizes[i].height)
        layOut(row)
      }
      val part = g.create(0, y, w, sizes[i].height)
      row.printAll(part)
      part.dispose()
      y += sizes[i].height
    }
    g.dispose()
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
  }

  private fun tooltip(html: String, file: File) {
    val tip = JToolTip().apply { tipText = html }
    print(tip, tip.preferredSize.width, tip.preferredSize.height, Color.WHITE, file)
  }

  @Test
  fun `shots of the agent pills, their menus and tooltips`() {
    val folder = System.getenv("VIBE_SHOTS") ?: return
    val out = File(folder)
    val modePicker = ModePicker { }.apply { setModes(modes) }
    val configPicker = ConfigOptionsPicker({ _, _ -> }, { _, _ -> }).apply { setOptions(options) }

    listOf("light" to light, "dark" to night).forEach { (name, palette) ->
      val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
        background = palette.background
        add(modePicker.pill)
        add(configPicker.pill)
        modePicker.pill.foreground = palette.foreground
        configPicker.pill.foreground = palette.foreground
      }
      print(row, row.preferredSize.width, row.preferredSize.height, palette.background, File(out, "acp-pills-row-$name.png"))
      shoot(AcpMenu.modeRows(modes), palette, 1, File(out, "acp-pills-mode-menu-$name.png"))
      shoot(AcpMenu.optionRows(options), palette, 2, File(out, "acp-pills-config-menu-$name.png"))
      val effort = options.first { it.id == "effort" }.kind as SessionConfigOption.Choice
      shoot(AcpMenu.choiceRows("effort", effort), palette, -1, File(out, "acp-pills-effort-menu-$name.png"))
      val model = options.first { it.id == "model" }.kind as SessionConfigOption.Choice
      shoot(AcpMenu.choiceRows("model", model), palette, -1, File(out, "acp-pills-model-menu-$name.png"))
    }
    tooltip(modePicker.pill.toolTipText, File(out, "acp-pills-tooltip-mode.png"))
    tooltip(configPicker.pill.toolTipText, File(out, "acp-pills-tooltip-config.png"))
    val longHint = AcpMenu.choiceRows("model", options.first { it.id == "model" }.kind as SessionConfigOption.Choice).first().tooltip!!
    tooltip(longHint, File(out, "acp-pills-tooltip-hint.png"))
    val fast = AcpMenu.optionRows(options).first { it.source.id == "fast" }.tooltip!!
    tooltip(fast, File(out, "acp-pills-tooltip-fast.png"))
  }
}
