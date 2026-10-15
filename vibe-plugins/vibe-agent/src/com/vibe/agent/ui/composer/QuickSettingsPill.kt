// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.settings.QuickSettings

/**
 * The quick-settings pill of the composer: a menu of the settings people change while working
 *
 * Built of platform actions, so the menu works from the keyboard and filters by typing like every other IDE menu
 * What it offers and what a click writes is [QuickSettings]; this class only draws it
 */
class QuickSettingsPill(
  private val store: QuickSettings.Store = QuickSettings.Ide,
  private val openAllSettings: () -> Unit,
) {
  val pill = PillButton(icon = AllIcons.Actions.Properties, dropdown = true) { show() }
    .apply { toolTipText = t("quick.tooltip") }

  private fun show() {
    val group = DefaultActionGroup()
    for (item in QuickSettings.items(store)) {
      when (item) {
        is QuickSettings.Item.Choice -> group.add(DefaultActionGroup(title(item.id), true).apply {
          item.options.forEach { option -> add(choice(item.id, option)) }
        })
        // A switch is a submenu of two values like its neighbours: a bare toggle makes the popup reserve a check column
        // for every row, and the whole menu opened with a wide empty margin on the left
        is QuickSettings.Item.Switch -> group.add(DefaultActionGroup(title(item.id), true).apply {
          add(switchValue(item.id, false))
          add(switchValue(item.id, true))
        })
      }
    }
    group.addSeparator()
    group.add(object : DumbAwareAction(t("quick.allSettings")) {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
      override fun actionPerformed(e: AnActionEvent) = openAllSettings()
    })
    JBPopupFactory.getInstance()
      .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(pill),
                              JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
      .showUnderneathOf(pill)
  }

  /** One value of a choice: checked when it is the current one, and choosing it writes it */
  private fun choice(id: String, option: String) = object : ToggleAction(optionLabel(id, option)) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean =
      (QuickSettings.items(store).firstOrNull { it.id == id } as? QuickSettings.Item.Choice)?.selected == option
    override fun setSelected(e: AnActionEvent, state: Boolean) = QuickSettings.choose(store, id, option)
  }

  /** One value of a switch: checked when the switch is in it, and choosing it puts the switch there */
  private fun switchValue(id: String, on: Boolean) = object : ToggleAction(if (on) t("quick.switch.on") else t("quick.switch.off")) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    private fun isOn(): Boolean = (QuickSettings.items(store).firstOrNull { it.id == id } as? QuickSettings.Item.Switch)?.on == true
    override fun isSelected(e: AnActionEvent): Boolean = isOn() == on
    override fun setSelected(e: AnActionEvent, state: Boolean) {
      if (isOn() != on) QuickSettings.flip(store, id)
    }
  }

  private fun title(id: String): String = when (id) {
    QuickSettings.TERSE -> t("quick.terse")
    QuickSettings.REASONING -> t("quick.reasoning")
    QuickSettings.OFFLINE -> t("quick.offline")
    else -> t("quick.minimalism")
  }

  /** Literal keys, one per value: the catalogue gate finds a key by its call, and a key built from parts it cannot see */
  private fun optionLabel(id: String, option: String): String = when ("$id.$option") {
    "terse.off" -> t("quick.terse.off")
    "terse.lite" -> t("quick.terse.lite")
    "terse.full" -> t("quick.terse.full")
    "terse.ultra" -> t("quick.terse.ultra")
    "reasoning.off" -> t("quick.reasoning.off")
    "reasoning.low" -> t("quick.reasoning.low")
    "reasoning.medium" -> t("quick.reasoning.medium")
    "reasoning.high" -> t("quick.reasoning.high")
    "minimalism.off" -> t("quick.minimalism.off")
    "minimalism.light" -> t("quick.minimalism.light")
    "minimalism.full" -> t("quick.minimalism.full")
    "minimalism.ultra" -> t("quick.minimalism.ultra")
    else -> option
  }
}
