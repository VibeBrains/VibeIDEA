// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.PermissionMode

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.SessionConfigOption
import com.vibe.agent.acp.SessionModes
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import java.awt.Component
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** What the next message goes to: an ACP agent, or a provider model over the direct LLM path. */
sealed interface ChatTarget {
  val id: String
  val label: String

  /** How the audit journal names what was running — the id, not the display label. */
  fun auditName(): String = id

  data class Agent(val config: AgentServerConfig) : ChatTarget {
    override val id: String get() = "acp:${config.name}"
    override val label: String get() = config.name
  }

  /** [static] = hand-declared in a providers file (marked with a pencil), false = pulled from the provider's live catalog. */
  data class Model(val provider: ProviderEntry, val model: ModelEntry, val static: Boolean) : ChatTarget {
    override val id: String get() = "llm:${provider.id}/${model.id}"
    override val label: String get() = model.name
  }
}

/**
 * The model pill (VibeIDE §6.2): one searchable list, agents first, then models
 * The provider is grey, a check marks the current one, and a pencil marks a model declared by hand
 * Such a model may no longer exist at the provider, so the person must be able to tell it apart
 * With nothing to choose from, the pill asks for a provider key and opens settings
 */
class ModelPicker(
  private val onChoose: (ChatTarget) -> Unit,
  private val onOpenSettings: () -> Unit,
  private val projectBase: String?,
) {
  private var targets: List<ChatTarget> = emptyList()
  var selected: ChatTarget? = null
    private set

  val pill = PillButton(text = NONE_LABEL, dropdown = true) { show() }

  fun setTargets(targets: List<ChatTarget>, selected: ChatTarget?) {
    this.targets = targets
    this.selected = selected ?: targets.firstOrNull()
    refresh()
  }

  private fun refresh() {
    val current = selected
    pill.text = current?.label ?: NONE_LABEL
    pill.toolTipText = when (current) {
      is ChatTarget.Agent -> t("picker.target.agentTooltip", "command" to current.config.command, "args" to current.config.args.joinToString(" "))
      // The label first: a long name is cut with an ellipsis in a narrow row, and the tooltip is where it is read whole
      is ChatTarget.Model -> "${current.label} — ${current.provider.name} · ${current.model.id}"
      null -> t("picker.target.emptyTooltip")
    }
  }

  private fun show() {
    if (targets.isEmpty()) {
      onOpenSettings()
      return
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
      .setRenderer(ChatTargetRenderer(selected?.id) { providerFileLabel(it, projectBase, System.getProperty("user.home")) })
      .setNamerForFiltering { target -> target.label + " " + ((target as? ChatTarget.Model)?.provider?.name ?: t("picker.target.agentWord")) }
      .setFilterAlwaysVisible(true)
      .setSelectedValue(selected, true)
      .setItemChosenCallback { target ->
        selected = target
        refresh()
        onChoose(target)
      }
      .createPopup()
      // The popup builds its own scroll pane deep inside and never exposes it — reach for it
      // through the component tree so the list scrolls with the same thin bar as everything else.
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }

  private companion object {
    // Providers are seeded active out of the box, so an empty target list usually means «no key yet».
    val NONE_LABEL: String get() = t("picker.target.needKey")
  }
}

/**
 * The mode pill: the ACP session modes the agent advertises (session/new → modes)
 * The caption is fixed: the mode in force is the agent's own wording, and as the pill's text it would look like a setting of the IDE
 * What the pill is and the mode in force are in its tooltip; the menu shows the modes, each with its hint behind a help mark
 * Hidden when the current target has no modes (direct LLM chat has no tools to gate)
 */
class ModePicker(private val onChoose: (modeId: String) -> Unit) {
  private var modes: SessionModes? = null
  val pill = PillButton(text = t("picker.mode.label"), dropdown = true) { show() }.apply { isVisible = false }

  fun setModes(modes: SessionModes?) {
    this.modes = modes
    pill.isVisible = modes != null && modes.available.isNotEmpty()
    pill.text = t("picker.mode.label")
    pill.toolTipText = modes?.let { wrappedTooltip(AcpMenu.modeTooltipLines(it)) } ?: t("picker.mode.tooltip")
    pill.revalidate()
  }

  private fun show() {
    val m = modes ?: return
    JBPopupFactory.getInstance().createPopupChooserBuilder(AcpMenu.modeRows(m))
      .setRenderer(AcpMenuRenderer())
      .setItemChosenCallback { row -> onChoose(row.source.id) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}

/**
 * «Автопилот ▾» — насколько агенту разрешено действовать без вопроса.
 *
 * Рядом с моделью и режимом агента, а не в настройках: это решение человек меняет по ходу работы —
 * доверился на рутине, перешёл на «вручную» там, где правка опасна. Спрятанное в настройках, оно
 * меняется раз и навсегда, то есть не меняется.
 */
class PermissionModePicker(private val onChoose: (PermissionMode) -> Unit) {
  val pill = PillButton(text = "", dropdown = true) { show() }.apply { toolTipText = t("permission.pill.tooltip") }

  fun setMode(mode: PermissionMode) {
    pill.text = mode.title
    pill.toolTipText = mode.description
    pill.revalidate()
  }

  private fun show() {
    val current = PermissionMode.of(com.vibe.agent.settings.VibeAgentSettings.permissionMode)
    JBPopupFactory.getInstance().createPopupChooserBuilder(PermissionMode.entries.toList())
      .setRenderer(PermissionModeRenderer(current))
      .setItemChosenCallback { mode -> onChoose(mode) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}

/**
 * One row of a composer picker: the check, the name, an optional grey detail and an optional trailing icon
 * The row carries the tooltip itself: a list asks only the row component for it, not the labels inside
 * ColoredListCellRenderer has one icon slot and the check takes it, so the row is built from labels
 */
internal class PickerRow {
  private val check = JBLabel()
  private val nameLabel = JBLabel()
  private val detailLabel = JBLabel()
  private val trailingLabel = JBLabel()
  private val lead = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(GAP), 0)).apply {
    isOpaque = false
    add(check)
    add(nameLabel)
    add(detailLabel)
  }

  // The trailing mark sits at the right edge, so the marks of all rows form one column whatever the name length
  private val row = JPanel(java.awt.BorderLayout(JBUI.scale(TRAILING_GAP), 0)).apply {
    border = JBUI.Borders.empty(0, JBUI.scale(UIUtil.getListCellHPadding()))
    add(lead, java.awt.BorderLayout.CENTER)
    add(trailingLabel, java.awt.BorderLayout.EAST)
  }

  /** Fills the row for one list cell; the same component goes back for every cell */
  fun paint(
    list: JList<*>,
    selected: Boolean,
    checked: Boolean,
    name: String,
    detail: String?,
    trailing: Icon?,
    tooltip: String?,
  ): Component {
    check.icon = if (checked) AllIcons.Actions.Checked else EmptyIcon.ICON_16
    nameLabel.text = name
    nameLabel.font = list.font
    nameLabel.foreground = if (selected) list.selectionForeground else list.foreground
    detailLabel.text = detail
    detailLabel.isVisible = detail != null
    detailLabel.font = list.font
    detailLabel.foreground = if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
    trailingLabel.icon = trailing
    trailingLabel.isVisible = trailing != null
    row.background = if (selected) list.selectionBackground else list.background
    row.toolTipText = tooltip
    return row
  }

  private companion object {
    // Unscaled gap between the parts of the row
    const val GAP = 4

    // Unscaled least gap between the longest name and the trailing mark
    const val TRAILING_GAP = 12
  }
}

/**
 * The permission mode menu: the check, the mode name, and a help mark that carries the hint as its tooltip
 * The hint is not printed beside the name, because as text it made the menu as wide as the longest hint
 */
internal class PermissionModeRenderer(private val current: PermissionMode) : ListCellRenderer<PermissionMode> {
  private val row = PickerRow()

  override fun getListCellRendererComponent(
    list: JList<out PermissionMode>,
    value: PermissionMode,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ): Component = row.paint(
    list, selected, checked = value == current, name = value.title, detail = null,
    trailing = AllIcons.General.ContextHelp, tooltip = value.description,
  )
}

/**
 * The model list: agents and models, the provider in grey, and a pencil after the provider for a model declared by hand
 * The pencil's tooltip names the providers file the model was read from, shortened by [pathLabel]
 */
internal class ChatTargetRenderer(
  private val selectedId: String?,
  private val pathLabel: (String) -> String,
) : ListCellRenderer<ChatTarget> {
  private val row = PickerRow()

  override fun getListCellRendererComponent(
    list: JList<out ChatTarget>,
    value: ChatTarget,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ): Component {
    val checked = value.id == selectedId
    return when (value) {
      is ChatTarget.Agent -> row.paint(
        list, selected, checked, name = value.label, detail = t("picker.target.acp"), trailing = null, tooltip = null,
      )
      is ChatTarget.Model -> row.paint(
        list, selected, checked, name = value.label, detail = value.provider.name,
        trailing = if (value.static) AllIcons.Actions.Edit else null,
        tooltip = if (value.static) manualTooltip(value.model.sourcePath) else null,
      )
    }
  }

  private fun manualTooltip(path: String?): String =
    path?.let { t("picker.target.manual.tooltip", "path" to pathLabel(it)) } ?: t("picker.target.manual.tooltipNoPath")
}

/**
 * A providers file as the picker names it: from the project root, from `~` for the global folder, otherwise as it is
 */
internal fun providerFileLabel(path: String, projectBase: String?, home: String): String {
  val file = Path.of(path)
  val root = projectBase?.let { Path.of(it) }
  val homeDir = Path.of(home)
  return when {
    root != null && file.startsWith(root) -> root.relativize(file).toString()
    file.startsWith(homeDir) -> "~/" + homeDir.relativize(file).toString()
    else -> path
  }
}

/**
 * The settings pill: the configuration options the agent advertises for this session — switches and choices
 *
 * A separate pill from the mode one on purpose: these are the agent's own settings with the agent's own captions
 * The caption is fixed and the values in force are in the tooltip: the agent's wording would read as a setting of the IDE
 * Hidden whenever the agent offers none — most do, and an always-present empty menu reads as a broken feature
 * A switch flips on click; a choice opens its values with the current one checked
 *
 * The list is never edited locally: the click reports the wanted value, and the caller redraws
 * from what the agent answered
 */
class ConfigOptionsPicker(
  private val onToggle: (configId: String, value: Boolean) -> Unit,
  private val onChoose: (configId: String, value: String) -> Unit,
) {
  private var options: List<SessionConfigOption> = emptyList()
  val pill = PillButton(text = t("picker.config.label"), dropdown = true) { show() }.apply { isVisible = false }

  fun setOptions(options: List<SessionConfigOption>?) {
    this.options = options.orEmpty()
    pill.isVisible = this.options.isNotEmpty()
    pill.text = t("picker.config.label")
    pill.toolTipText = wrappedTooltip(AcpMenu.optionsTooltipLines(this.options))
    pill.revalidate()
  }

  private fun show() {
    if (options.isEmpty()) return
    JBPopupFactory.getInstance().createPopupChooserBuilder(AcpMenu.optionRows(options))
      .setRenderer(AcpMenuRenderer())
      .setItemChosenCallback { row ->
        val option = row.source
        when (val kind = option.kind) {
          is SessionConfigOption.Toggle -> onToggle(option.id, !kind.on)
          is SessionConfigOption.Choice -> showChoices(option.id, kind)
        }
      }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }

  private fun showChoices(configId: String, choice: SessionConfigOption.Choice) {
    JBPopupFactory.getInstance().createPopupChooserBuilder(AcpMenu.choiceRows(configId, choice))
      .setRenderer(AcpMenuRenderer())
      .setItemChosenCallback { row -> if (row.source.value != choice.current) onChoose(configId, row.source.value) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}
