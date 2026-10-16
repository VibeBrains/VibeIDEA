// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.SessionConfigOption
import com.vibe.agent.acp.SessionConfigOptions
import com.vibe.agent.acp.SessionMode
import com.vibe.agent.acp.SessionModes
import com.vibe.agent.i18n.VibeI18n.t
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent's own modes and options as the person sees them: known wording in Russian, the rest exactly as sent
 * Measured on the strings the pills and rows end up with, and on the labels a row paints
 */
class AcpMenuTest {
  /** The modes claude-agent-acp advertises, as its source writes them (session-mode.js) */
  private val claudeModes = SessionModes(
    currentModeId = "default",
    available = listOf(
      SessionMode("default", "Manual", "Always ask before making changes"),
      SessionMode("acceptEdits", "Accept edits", "Automatically accept all file edits"),
      SessionMode("plan", "Plan", "Create a plan before making changes"),
      SessionMode("auto", "Auto", "Claude handles permission decisions"),
      SessionMode("bypassPermissions", "Bypass permissions", "Accepts all permissions"),
    ),
  )

  /** The options claude-agent-acp advertises (acp-agent.js buildConfigOptions); the model entries are a sample, the SDK sends them */
  private val claudeOptions: List<SessionConfigOption> = SessionConfigOptions.parse(
    Json.parseToJsonElement(
      """{"configOptions":[
      {"id":"mode","name":"Mode","description":"Session permission mode","category":"mode","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Manual","description":"Always ask before making changes"},
                  {"value":"plan","name":"Plan","description":"Create a plan before making changes"}]},
      {"id":"model","name":"Model","description":"AI model to use","category":"model","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Default (recommended)","description":"Most capable for complex work"},
                  {"value":"haiku","name":"Haiku","description":"Fastest for quick answers"}]},
      {"id":"effort","name":"Effort","description":"Available effort levels for this model","category":"thought_level","type":"select",
       "currentValue":"high","options":[{"value":"default","name":"Default"},{"value":"low","name":"Low"},{"value":"medium","name":"Medium"},
       {"value":"high","name":"High"},{"value":"xhigh","name":"Xhigh"},{"value":"max","name":"Max"}]},
      {"id":"fast","name":"Fast mode","description":"Faster responses on supported models","category":"model_config","type":"boolean","currentValue":false},
      {"id":"agent","name":"Agent","description":"Main-thread agent persona","type":"select","currentValue":"default",
       "options":[{"value":"default","name":"Default","description":"Standard Claude Code agent"},{"value":"reviewer","name":"reviewer","description":"Reviews a diff"}]}
      ]}""",
    ).jsonObject,
  )

  private fun labels(container: Container): List<JLabel> = container.components.flatMap { child ->
    listOfNotNull(child as? JLabel) + ((child as? Container)?.let { labels(it) } ?: emptyList())
  }

  /** What a tooltip says once the markup and the line breaks are gone: wrapping may split a phrase between lines */
  private fun plain(tooltip: String): String = tooltip.replace("<br>", " ").replace(Regex("<[^>]+>"), "")

  private fun paint(row: AcpMenuRow<*>): javax.swing.JComponent =
    AcpMenuRenderer().getListCellRendererComponent(JList<AcpMenuRow<*>>(), row, 0, false, false) as javax.swing.JComponent

  @Test
  fun `every mode of claude agent is translated, name and hint`() {
    val rows = AcpMenu.modeRows(claudeModes)
    assertEquals(
      listOf("acp.mode.default", "acp.mode.acceptEdits", "acp.mode.plan", "acp.mode.auto", "acp.mode.bypassPermissions").map { t(it) },
      rows.map { it.name },
    )
    assertEquals(
      listOf(
        "acp.mode.default.hint", "acp.mode.acceptEdits.hint", "acp.mode.plan.hint", "acp.mode.auto.hint", "acp.mode.bypassPermissions.hint",
      ).map { t(it) },
      rows.map { it.hint },
    )
    rows.forEach { assertNotEquals(it.source.name, it.name, "${it.source.id} is not translated") }
  }

  @Test
  fun `hard breaks of an agent text are spaces, a blank line is a paragraph`() {
    assertEquals(listOf("Use when diagnosing issues", "Second"), paragraphs("Use when\ndiagnosing  issues\n\nSecond"))
  }

  @Test
  fun `the mode pill names the mode in force`() {
    assertEquals("Режим: Вручную", AcpMenu.modePillText(claudeModes))
    assertEquals("Режим", AcpMenu.modePillText(SessionModes("gone", claudeModes.available)))
  }

  @Test
  fun `a mode id we do not know is shown as the agent sent it`() {
    val modes = SessionModes("yolo", listOf(SessionMode("yolo", "YOLO", "Do everything"), SessionMode("default", "Manual", null)))
    val row = AcpMenu.modeRows(modes).first()
    assertEquals("YOLO", row.name)
    assertEquals("Do everything", row.hint)
    assertTrue(row.checked)
  }

  @Test
  fun `a known id under another wording keeps the agent's text`() {
    // Another agent may use the id "default" for something else, and a reworded string must not get an old translation
    val modes = SessionModes("default", listOf(SessionMode("default", "Ask first", "Ask before every tool call")))
    val row = AcpMenu.modeRows(modes).single()
    assertEquals("Ask first", row.name)
    assertEquals("Ask before every tool call", row.hint)
  }

  @Test
  fun `the check marks the mode in force and only it`() {
    val rows = AcpMenu.modeRows(claudeModes.copy(currentModeId = "plan"))
    assertEquals(listOf("plan"), rows.filter { it.checked }.map { it.source.id })
  }

  @Test
  fun `options of claude agent are translated and the model stays as sent`() {
    val rows = AcpMenu.optionRows(claudeOptions)
    assertEquals(
      listOf("acp.option.mode", "acp.option.model", "acp.option.effort", "acp.option.fast", "acp.option.agent").map { t(it) },
      rows.map { it.name },
    )
    val byId = rows.associateBy { it.source.id }
    assertEquals(t("acp.mode.default"), byId.getValue("mode").detail)
    assertEquals("Default (recommended)", byId.getValue("model").detail)
    assertEquals(t("acp.value.effort.high"), byId.getValue("effort").detail)
    assertEquals(t("acp.value.agent.default"), byId.getValue("agent").detail)
    assertNull(byId.getValue("fast").detail)
    assertFalse(byId.getValue("fast").checked)
  }

  @Test
  fun `the values of a choice are translated where known and left alone where not`() {
    val effort = claudeOptions.first { it.id == "effort" }.kind as SessionConfigOption.Choice
    val names = AcpMenu.choiceRows("effort", effort).map { it.name }
    assertEquals(
      listOf("acp.value.effort.default", "acp.value.effort.low", "acp.value.effort.medium", "acp.value.effort.high",
        "acp.value.effort.xhigh", "acp.value.effort.max").map { t(it) },
      names,
    )

    val model = claudeOptions.first { it.id == "model" }.kind as SessionConfigOption.Choice
    val modelRows = AcpMenu.choiceRows("model", model)
    assertEquals(listOf("Default (recommended)", "Haiku"), modelRows.map { it.name })
    assertEquals(listOf("Most capable for complex work", "Fastest for quick answers"), modelRows.map { it.hint })

    val agent = claudeOptions.first { it.id == "agent" }.kind as SessionConfigOption.Choice
    val agentRows = AcpMenu.choiceRows("agent", agent)
    assertEquals(listOf(t("acp.value.agent.default"), "reviewer"), agentRows.map { it.name })
    assertEquals(listOf(t("acp.value.agent.default.hint"), "Reviews a diff"), agentRows.map { it.hint })
  }

  @Test
  fun `an option we do not know keeps its name, hint and values`() {
    val option = SessionConfigOptions.parse(
      Json.parseToJsonElement(
        """{"configOptions":[{"id":"sandbox","name":"Sandbox","description":"Where commands run","type":"select","currentValue":"a",
        "options":[{"value":"a","name":"Local"},{"value":"b","name":"Container"}]}]}""",
      ).jsonObject,
    ).single()
    val row = AcpMenu.optionRows(listOf(option)).single()
    assertEquals("Sandbox", row.name)
    assertEquals("Local", row.detail)
    assertEquals("Where commands run", row.hint)
    assertEquals(listOf("Local", "Container"), AcpMenu.choiceRows("sandbox", option.kind as SessionConfigOption.Choice).map { it.name })
  }

  @Test
  fun `a row paints the name and keeps the hint for the tooltip`() {
    AcpMenu.modeRows(claudeModes).forEach { row ->
      val component = paint(row)
      val texts = labels(component).map { it.text.orEmpty() }
      assertTrue(row.name in texts, "${row.source.id}: the name is not painted")
      assertTrue(texts.none { it.contains(row.hint!!) }, "${row.source.id}: the hint is painted in the row")
      assertTrue(labels(component).any { it.icon == AllIcons.General.ContextHelp }, "${row.source.id}: no help mark")
      assertEquals(row.tooltip, component.toolTipText)
      assertTrue(plain(row.tooltip!!).contains(row.hint!!))
    }
  }

  @Test
  fun `a row without a hint has no help mark and no tooltip`() {
    val row = AcpMenu.modeRows(SessionModes("m", listOf(SessionMode("m", "Mine", null)))).single()
    val component = paint(row)
    assertTrue(labels(component).none { it.icon == AllIcons.General.ContextHelp })
    assertNull(component.toolTipText)
  }

  @Test
  fun `the mode pill follows the mode in force`() {
    val picker = ModePicker { }
    picker.setModes(claudeModes)
    assertEquals("Режим: Вручную", picker.pill.text)
    picker.setModes(claudeModes.copy(currentModeId = "plan"))
    assertEquals("Режим: План", picker.pill.text)
    assertTrue(picker.pill.isVisible)
    picker.setModes(null)
    assertFalse(picker.pill.isVisible)
  }

  @Test
  fun `the mode tooltip says what the pill is and which mode is in force`() {
    val picker = ModePicker { }
    picker.setModes(claudeModes)
    val tip = plain(picker.pill.toolTipText)
    assertTrue(t("picker.mode.tooltip") in tip, tip)
    assertTrue(t("acp.mode.default") in tip, tip)
    assertTrue(t("acp.mode.default.hint") in tip, tip)
  }

  @Test
  fun `the settings pill is one word with no number, and the values are in its tooltip`() {
    val picker = ConfigOptionsPicker({ _, _ -> }, { _, _ -> })
    picker.setOptions(claudeOptions)
    assertEquals(t("picker.config.label"), picker.pill.text)
    assertFalse(picker.pill.text.contains(' '), "more than one word: ${picker.pill.text}")
    assertTrue(picker.pill.text.none { it.isDigit() }, picker.pill.text)
    val tip = plain(picker.pill.toolTipText)
    assertTrue(t("picker.config.tooltip") in tip, tip)
    assertTrue("${t("acp.option.effort")}: ${t("acp.value.effort.high")}" in tip, tip)
    assertTrue("${t("acp.option.fast")}: ${t("acp.value.off")}" in tip, tip)
    assertTrue("${t("acp.option.model")}: Default (recommended)" in tip, tip)
    picker.setOptions(null)
    assertFalse(picker.pill.isVisible)
  }

  @Test
  fun `words are wrapped at the width and no line is wider`() {
    val width = { text: String -> text.length * 10 }
    val lines = wrapWords("one two three four five six seven", 100, width)
    assertEquals(listOf("one two", "three four", "five six", "seven"), lines)
    assertTrue(lines.all { width(it) <= 100 })
  }

  @Test
  fun `a word wider than the width stays whole and nothing is lost`() {
    val lines = wrapWords("a supercalifragilistic b", 100, { it.length * 10 })
    assertEquals(listOf("a", "supercalifragilistic", "b"), lines)
  }

  @Test
  fun `an empty text is one empty line, not no line`() {
    assertEquals(listOf(""), wrapWords("", 100, { it.length * 10 }))
  }

  @Test
  fun `a long description wraps in the tooltip and stays within the cap`() {
    val long = "A description that an agent may send, and that goes on and on without any hope of fitting a single line. ".repeat(6)
    val label = JLabel(wrappedTooltip(listOf(long)))
    // The tooltip is measured with its own font and the label with its own, so a few per cent of drift is allowed
    val cap = JBUI.scale(TOOLTIP_MAX_WIDTH) * 11 / 10
    assertTrue(label.preferredSize.width <= cap, "the tooltip asks for ${label.preferredSize.width}, the cap is $cap")
    val oneLine = JLabel(long).preferredSize
    assertTrue(label.preferredSize.height > oneLine.height * 3, "the text did not wrap: ${label.preferredSize.height} against ${oneLine.height}")
  }

  @Test
  fun `a short description stays on one line`() {
    val tip = wrappedTooltip(listOf("Short"))
    assertFalse("<br>" in tip, tip)
  }

  @Test
  fun `the tooltip escapes what the agent sent`() {
    val tip = wrappedTooltip(listOf("Edits <b>everything</b> & more"))
    assertTrue("&lt;b&gt;everything&lt;/b&gt; &amp; more" in tip, tip)
  }
}
