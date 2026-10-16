// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.vibe.agent.acp.SessionConfigOption
import com.vibe.agent.acp.SessionModes
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.UIManager

// Unscaled width at which the tooltip of an agent's hint wraps its lines
internal const val TOOLTIP_MAX_WIDTH = 360

/**
 * A tooltip whose lines are wrapped by words to fit [maxWidth], so that no text runs along one line
 *
 * The wrapping is done here and not by a CSS width: Swing reads a `px` width as 1.3 times larger, and the
 * platform kit that draws tooltips in the IDE may read it otherwise, so a width in CSS has no size that holds everywhere
 */
internal fun wrappedTooltip(lines: List<String>, maxWidth: Int = JBUI.scale(TOOLTIP_MAX_WIDTH)): String {
  val metrics = JLabel().getFontMetrics(UIManager.getFont("ToolTip.font") ?: JBFont.label())
  val wrapped = lines.flatMap { wrapWords(it, maxWidth, metrics::stringWidth) }
  return "<html>" + wrapped.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
}

/** [text] split at spaces into lines no wider than [maxWidth]; a word wider than that stays whole on a line of its own */
internal fun wrapWords(text: String, maxWidth: Int, width: (String) -> Int): List<String> {
  val lines = ArrayList<String>()
  var line = ""
  for (word in text.split(' ').filter { it.isNotEmpty() }) {
    val joined = if (line.isEmpty()) word else "$line $word"
    if (line.isNotEmpty() && width(joined) > maxWidth) {
      lines += line
      line = word
    }
    else line = joined
  }
  if (line.isNotEmpty() || lines.isEmpty()) lines += line
  return lines
}

/**
 * One row of an agent's menu: the name, an optional grey value after it, and the hint behind a help mark
 * The hint is not printed in the row, because as text it made the menu as wide as the longest description
 */
internal class AcpMenuRow<T>(val source: T, val name: String, val detail: String?, val checked: Boolean, val hint: String?) {
  val tooltip: String? by lazy { hint?.let { wrappedTooltip(listOf(it)) } }
}

/** Draws [AcpMenuRow]s the way the permission menu draws its modes: the check, the name, the help mark at the edge */
internal class AcpMenuRenderer : ListCellRenderer<AcpMenuRow<*>> {
  private val row = PickerRow()

  override fun getListCellRendererComponent(
    list: JList<out AcpMenuRow<*>>,
    value: AcpMenuRow<*>,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ): Component = row.paint(
    list, selected, value.checked, name = value.name, detail = value.detail,
    trailing = if (value.hint != null) AllIcons.General.ContextHelp else null, tooltip = value.tooltip,
  )
}

/** What the pills and menus of the agent's own modes and options say, in one place so that it is measured without Swing */
internal object AcpMenu {
  fun modeRows(modes: SessionModes) = modes.available.map { mode ->
    AcpMenuRow(
      mode, AcpLabels.modeName(mode.id, mode.name), detail = null,
      checked = mode.id == modes.currentModeId, hint = AcpLabels.modeHint(mode.id, mode.description),
    )
  }

  /** A switch shows its state as the check; a choice shows the value in force and opens its values */
  fun optionRows(options: List<SessionConfigOption>) = options.map { option ->
    val name = AcpLabels.optionName(option.id, option.name)
    val hint = AcpLabels.optionHint(option.id, option.description)
    when (val kind = option.kind) {
      is SessionConfigOption.Toggle -> AcpMenuRow(option, name, detail = null, checked = kind.on, hint = hint)
      is SessionConfigOption.Choice -> AcpMenuRow(option, name, detail = currentValueName(option.id, kind), checked = false, hint = hint)
    }
  }

  fun choiceRows(optionId: String, choice: SessionConfigOption.Choice) = choice.options.map { value ->
    AcpMenuRow(
      value, AcpLabels.valueName(optionId, value.value, value.name), detail = null,
      checked = value.value == choice.current, hint = AcpLabels.valueHint(optionId, value.value, value.description),
    )
  }

  private fun currentValueName(optionId: String, choice: SessionConfigOption.Choice): String {
    val current = choice.options.firstOrNull { it.value == choice.current } ?: return choice.current
    return AcpLabels.valueName(optionId, current.value, current.name)
  }

  /** The mode pill names the mode in force, so it is seen without opening anything */
  fun modePillText(modes: SessionModes): String {
    val current = modes.available.firstOrNull { it.id == modes.currentModeId } ?: return t("picker.mode.label")
    return t("picker.mode.labelWith", "value" to AcpLabels.modeName(current.id, current.name))
  }

  /** What the mode pill is, then the mode in force with its hint */
  fun modeTooltipLines(modes: SessionModes): List<String> {
    val current = modes.available.firstOrNull { it.id == modes.currentModeId }
    val name = current?.let { AcpLabels.modeName(it.id, it.name) } ?: modes.currentModeId
    val hint = current?.let { AcpLabels.modeHint(it.id, it.description) }
    return listOf(t("picker.mode.tooltip"), t("picker.mode.current", "value" to listOfNotNull(name, hint).joinToString(" — ")))
  }

  /** What the settings pill is, then every option with the value in force: the pill itself carries no number */
  fun optionsTooltipLines(options: List<SessionConfigOption>): List<String> =
    listOf(t("picker.config.tooltip")) + options.map { option ->
      val value = when (val kind = option.kind) {
        is SessionConfigOption.Toggle -> AcpLabels.switchState(kind.on)
        is SessionConfigOption.Choice -> currentValueName(option.id, kind)
      }
      "${AcpLabels.optionName(option.id, option.name)}: $value"
    }
}
