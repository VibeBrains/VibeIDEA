// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.db.DbSettings
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.vibe.agent.settings.TracksViewportWidthPanel
import com.vibe.agent.ui.VibeScroll

/** Настройки работы с базой: сколько строк показывать, сколько ждать, что прятать. */
class VibeDbConfigurable : Configurable {
  private val previewRows = JSpinner(SpinnerNumberModel(
    DbSettings.DEFAULT_PREVIEW_ROWS, DbSettings.MIN_PREVIEW_ROWS, DbSettings.MAX_PREVIEW_ROWS, 50))
  private val queryTimeout = JSpinner(SpinnerNumberModel(
    DbSettings.DEFAULT_QUERY_TIMEOUT_SECONDS, DbSettings.MIN_QUERY_TIMEOUT_SECONDS, DbSettings.MAX_QUERY_TIMEOUT_SECONDS, 5))
  private val showSystemSchemas = JBCheckBox(t("settings.db.showSystemSchemas"))

  override fun getDisplayName(): String = t("settings.db.title")

  /**
   * Прокрутка только вертикальная: страница настроек, которая едет вбок, — дефект, а не мелочь.
   * Правило и способ — docs/vibe/knowledge/ui/settingsPageWidth.md.
   */
  override fun createComponent(): JComponent =
    VibeScroll.pane(TracksViewportWidthPanel(
      FormBuilder.createFormBuilder()
          .addLabeledComponent(t("settings.db.previewRows"), previewRows)
          .addLabeledComponent(t("settings.db.queryTimeout"), queryTimeout)
          .addComponent(showSystemSchemas)
          .addComponent(JBLabel("<html>" + t("settings.db.hint") + "</html>").apply {
            foreground = com.intellij.ui.JBColor.GRAY
          })
          .panel
          .also { reset() }
    ))

  override fun isModified(): Boolean =
    previewRows.value != DbSettings.previewRows ||
    queryTimeout.value != DbSettings.queryTimeoutSeconds ||
    showSystemSchemas.isSelected != DbSettings.showSystemSchemas

  override fun apply() {
    DbSettings.previewRows = previewRows.value as Int
    DbSettings.queryTimeoutSeconds = queryTimeout.value as Int
    DbSettings.showSystemSchemas = showSystemSchemas.isSelected
  }

  override fun reset() {
    previewRows.value = DbSettings.previewRows
    queryTimeout.value = DbSettings.queryTimeoutSeconds
    showSystemSchemas.isSelected = DbSettings.showSystemSchemas
  }
}
