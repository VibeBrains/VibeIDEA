// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.EmptyIcon
import com.vibe.agent.acp.AgentServerConfig
import com.vibe.agent.acp.SessionModes
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import javax.swing.JList

/** What the next message goes to: an ACP agent, or a provider model over the direct LLM path. */
sealed interface ChatTarget {
  val id: String
  val label: String

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
      is ChatTarget.Agent -> "Агент ACP: ${current.config.command} ${current.config.args.joinToString(" ")}"
      is ChatTarget.Model -> "${current.provider.name} · ${current.model.id}"
      null -> "Нет ни агента, ни провайдера с моделями — откройте настройки"
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
            is ChatTarget.Agent -> append("  агент ACP", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            is ChatTarget.Model -> {
              append("  ${value.provider.name}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
              if (value.static) append("  · кастом", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
          }
        }
      })
      .setNamerForFiltering { t -> t.label + " " + ((t as? ChatTarget.Model)?.provider?.name ?: "агент") }
      .setFilterAlwaysVisible(true)
      .setSelectedValue(selected, true)
      .setItemChosenCallback { t ->
        selected = t
        refresh()
        onChoose(t)
      }
      .createPopup()
      .showUnderneathOf(pill)
  }

  private companion object {
    // Providers are seeded active out of the box, so an empty target list usually means «no key yet».
    const val NONE_LABEL = "Нужен ключ провайдера"
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
    pill.toolTipText = current?.description ?: "Режим сессии агента"
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
      .showUnderneathOf(pill)
  }
}
