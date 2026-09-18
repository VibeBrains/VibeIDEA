// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.PermissionMode

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.EmptyIcon
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.SessionConfigOption
import com.vibe.agent.acp.SessionModes
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import javax.swing.JList

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

  /** [static] = hand-declared in a providers file (shown with the «кастом» badge), false = pulled from the provider's live catalog. */
  data class Model(val provider: ProviderEntry, val model: ModelEntry, val static: Boolean) : ChatTarget {
    override val id: String get() = "llm:${provider.id}/${model.id}"
    override val label: String get() = model.name
  }
}

/**
 * «Модель ▾» pill (VibeIDE §6.2): one searchable list with agents first, then models
 * (name + provider in gray, ✓ at the current one, «кастом» mark on hand-declared entries —
 * the model may no longer exist at the provider, the user must be able to tell).
 * With nothing to choose from the pill turns into «Нужен ключ провайдера» and opens settings.
 */
class ModelPicker(private val onChoose: (ChatTarget) -> Unit, private val onOpenSettings: () -> Unit) {
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
      is ChatTarget.Model -> "${current.provider.name} · ${current.model.id}"
      null -> t("picker.target.emptyTooltip")
    }
  }

  private fun show() {
    if (targets.isEmpty()) {
      onOpenSettings()
      return
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
      .setRenderer(object : ColoredListCellRenderer<ChatTarget>() {
        override fun customizeCellRenderer(list: JList<out ChatTarget>, value: ChatTarget, index: Int, isSelected: Boolean, hasFocus: Boolean) {
          icon = if (value.id == selected?.id) AllIcons.Actions.Checked else EmptyIcon.ICON_16
          append(value.label)
          when (value) {
            is ChatTarget.Agent -> append("  " + t("picker.target.acp"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            is ChatTarget.Model -> {
              append("  ${value.provider.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
              if (value.static) append("  · " + t("picker.target.custom"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
          }
        }
      })
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
 * «Режим ▾» pill: the ACP session modes the agent advertises (session/new → modes).
 * Hidden when the current target has no modes (direct LLM chat has no tools to gate).
 */
class ModePicker(private val onChoose: (modeId: String) -> Unit) {
  private var modes: SessionModes? = null
  val pill = PillButton(text = "", dropdown = true) { show() }.apply { isVisible = false }

  fun setModes(modes: SessionModes?) {
    this.modes = modes
    pill.isVisible = modes != null && modes.available.isNotEmpty()
    val current = modes?.available?.firstOrNull { it.id == modes.currentModeId }
    pill.text = current?.name ?: modes?.currentModeId ?: ""
    pill.toolTipText = current?.description ?: t("picker.mode.tooltip")
    pill.revalidate()
  }

  private fun show() {
    val m = modes ?: return
    JBPopupFactory.getInstance().createPopupChooserBuilder(m.available)
      .setRenderer(object : ColoredListCellRenderer<com.vibe.agent.acp.SessionMode>() {
        override fun customizeCellRenderer(list: JList<out com.vibe.agent.acp.SessionMode>, value: com.vibe.agent.acp.SessionMode, index: Int, isSelected: Boolean, hasFocus: Boolean) {
          icon = if (value.id == m.currentModeId) AllIcons.Actions.Checked else EmptyIcon.ICON_16
          append(value.name)
          value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
      })
      .setItemChosenCallback { mode -> onChoose(mode.id) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}

/**
 * «Настройки ▾» pill: the configuration options the agent advertises for this session — switches and choices.
 *
 * A separate pill from «Режим ▾» on purpose: these are the agent's own settings with the agent's own captions.
 * Hidden whenever the agent offers none — most do, and an always-present empty menu reads as a broken feature.
 * A switch flips on click; a choice opens its values with the current one checked.
 *
 * The list is never edited locally: the click reports the wanted value, and the caller redraws
 * from what the agent answered.
 */
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
      .setRenderer(object : ColoredListCellRenderer<PermissionMode>() {
        override fun customizeCellRenderer(list: JList<out PermissionMode>, value: PermissionMode, index: Int, isSelected: Boolean, hasFocus: Boolean) {
          icon = if (value == current) AllIcons.Actions.Checked else EmptyIcon.ICON_16
          append(value.title)
          append("  " + value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
      })
      .setItemChosenCallback { mode -> onChoose(mode) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}

class ConfigOptionsPicker(
  private val onToggle: (configId: String, value: Boolean) -> Unit,
  private val onChoose: (configId: String, value: String) -> Unit,
) {
  private var options: List<SessionConfigOption> = emptyList()
  val pill = PillButton(text = "", dropdown = true) { show() }.apply { isVisible = false }

  fun setOptions(options: List<SessionConfigOption>?) {
    this.options = options.orEmpty()
    pill.isVisible = this.options.isNotEmpty()
    val toggles = this.options.map { it.kind }.filterIsInstance<SessionConfigOption.Toggle>()
    pill.text =
      if (toggles.size == this.options.size) t("picker.config.label", "on" to toggles.count { it.on }, "total" to toggles.size)
      else t("picker.config.count", "total" to this.options.size)
    pill.toolTipText = t("picker.config.tooltip")
    pill.revalidate()
  }

  private fun show() {
    if (options.isEmpty()) return
    JBPopupFactory.getInstance().createPopupChooserBuilder(options)
      .setRenderer(object : ColoredListCellRenderer<SessionConfigOption>() {
        override fun customizeCellRenderer(list: JList<out SessionConfigOption>, value: SessionConfigOption, index: Int, isSelected: Boolean, hasFocus: Boolean) {
          when (val kind = value.kind) {
            is SessionConfigOption.Toggle -> {
              icon = if (kind.on) AllIcons.Actions.Checked else EmptyIcon.ICON_16
              append(value.name)
            }
            is SessionConfigOption.Choice -> {
              icon = EmptyIcon.ICON_16
              append(value.name)
              append(": ${kind.currentName}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
          }
          value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
      })
      .setItemChosenCallback { option ->
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
    JBPopupFactory.getInstance().createPopupChooserBuilder(choice.options)
      .setRenderer(object : ColoredListCellRenderer<SessionConfigOption.Choice.Option>() {
        override fun customizeCellRenderer(list: JList<out SessionConfigOption.Choice.Option>, value: SessionConfigOption.Choice.Option, index: Int, isSelected: Boolean, hasFocus: Boolean) {
          icon = if (value.value == choice.current) AllIcons.Actions.Checked else EmptyIcon.ICON_16
          append(value.name)
          value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
      })
      .setItemChosenCallback { option -> if (option.value != choice.current) onChoose(configId, option.value) }
      .createPopup()
      .also { com.vibe.agent.ui.VibeScroll.thinAllIn(it.content) }
      .showUnderneathOf(pill)
  }
}
