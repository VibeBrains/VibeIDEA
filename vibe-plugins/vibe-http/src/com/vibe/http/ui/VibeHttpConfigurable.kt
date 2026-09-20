// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.HttpSettings
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import com.vibe.agent.settings.SettingsUi

/** Настройки клиента запросов: таймауты и поведение по умолчанию. */
class VibeHttpConfigurable : Configurable {
  private val requestTimeout = JSpinner(SpinnerNumberModel(
    HttpSettings.DEFAULT_REQUEST_TIMEOUT_SECONDS, HttpSettings.MIN_TIMEOUT_SECONDS, HttpSettings.MAX_TIMEOUT_SECONDS, 1))
  private val connectTimeout = JSpinner(SpinnerNumberModel(
    HttpSettings.DEFAULT_CONNECT_TIMEOUT_SECONDS, HttpSettings.MIN_TIMEOUT_SECONDS, HttpSettings.MAX_TIMEOUT_SECONDS, 1))
  private val followRedirects = JBCheckBox(t("settings.http.followRedirects"))
  private val keepCookies = JBCheckBox(t("http.settings.keepCookies"))

  override fun getDisplayName(): String = t("settings.http.title")

  /**
   * Прокрутка только вертикальная: страница настроек, которая едет вбок, — дефект, а не мелочь.
   * Правило и способ — docs/vibe/knowledge/ui/settingsPageWidth.md.
   */
  override fun createComponent(): JComponent =
    SettingsUi.page(
      FormBuilder.createFormBuilder()
          .addLabeledComponent(t("settings.http.requestTimeout"), requestTimeout)
          .addLabeledComponent(t("settings.http.connectTimeout"), connectTimeout)
          .addComponent(followRedirects)
          .addComponent(keepCookies)
          .addComponent(SettingsUi.hint(t("settings.http.hint")))
          .panel
          .also { reset() }
    )

  override fun isModified(): Boolean =
    requestTimeout.value != HttpSettings.requestTimeoutSeconds ||
    connectTimeout.value != HttpSettings.connectTimeoutSeconds ||
    followRedirects.isSelected != HttpSettings.followRedirects ||
    keepCookies.isSelected != HttpSettings.keepCookies

  override fun apply() {
    HttpSettings.requestTimeoutSeconds = requestTimeout.value as Int
    HttpSettings.connectTimeoutSeconds = connectTimeout.value as Int
    HttpSettings.followRedirects = followRedirects.isSelected
    HttpSettings.keepCookies = keepCookies.isSelected
  }

  override fun reset() {
    requestTimeout.value = HttpSettings.requestTimeoutSeconds
    connectTimeout.value = HttpSettings.connectTimeoutSeconds
    followRedirects.isSelected = HttpSettings.followRedirects
    keepCookies.isSelected = HttpSettings.keepCookies
  }
}
