// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** Root of the VibeIDEA settings hub (children: Провайдеры, Модели). */
class VibeSettingsRoot : Configurable {
  override fun getDisplayName(): String = "VibeIDEA"
  override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
    .addComponent(JBLabel("<html><b>Настройки VibeIDEA</b><br>" +
      "Провайдеры — ключи API и статус их проверки.<br>" +
      "Модели — какие модели видны в списках выбора.<br><br>" +
      "Реестр на диске: каталог .vibe/providers/*.jsonc (тумблер active) + providers.json поверх, " +
      "глобальный ~/.vibe и проектный уровни; ключи хранятся только в защищённом хранилище ОС или .vibe/.env.</html>"))
    .addComponentFillVertically(JPanel(), 0)
    .panel.apply { border = JBUI.Borders.empty(8) }
  override fun isModified(): Boolean = false
  override fun apply() {}
}
