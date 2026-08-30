// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** Root of the VibeIDEA settings hub (children: providers, models). */
class VibeSettingsRoot : Configurable {
  override fun getDisplayName(): String = "VibeIDEA"
  override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
    .addComponent(JBLabel(t("settings.root.html")))
    .addComponentFillVertically(JPanel(), 0)
    .panel.apply { border = JBUI.Borders.empty(8) }
  override fun isModified(): Boolean = false
  override fun apply() {}
}
