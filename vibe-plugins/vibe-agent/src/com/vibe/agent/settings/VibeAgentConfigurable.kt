// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → VibeIDEA → Агент: hooks, audit, verify-gate and turn-checks
 * knobs. Security-shaped switches (hooks, audit) are off by default and labelled
 * as such. NoScroll: the page sizes itself; the platform's extra scroll pane
 * would give html hints a horizontal scrollbar (same lesson as Providers).
 */
class VibeAgentConfigurable : Configurable, Configurable.NoScroll {
  private var hooksEnabled: JBCheckBox? = null
  private var auditEnabled: JBCheckBox? = null
  private var auditRotation: JBIntSpinner? = null
  private var verifyMode: ComboBox<String>? = null
  private var verifyCommand: JBTextField? = null
  private var verifyMaxAttempts: JBIntSpinner? = null
  private var verifyTimeoutSec: JBIntSpinner? = null
  private var checksMode: ComboBox<String>? = null
  private var checksMaxAttempts: JBIntSpinner? = null
  private var terminalEnabled: JBCheckBox? = null

  override fun getDisplayName(): String = "Агент"

  override fun createComponent(): JComponent {
    val hooks = JBCheckBox("Включить хуки проекта (.vibe/hooks.json)", VibeAgentSettings.hooksEnabled).also { hooksEnabled = it }
    val audit = JBCheckBox("Вести журнал аудита (.vibe/audit.jsonl)", VibeAgentSettings.auditEnabled).also { auditEnabled = it }
    val rotation = JBIntSpinner(VibeAgentSettings.auditRotationMb, VibeAgentSettings.MIN_AUDIT_ROTATION_MB, VibeAgentSettings.MAX_AUDIT_ROTATION_MB).also { auditRotation = it }
    val vMode = ComboBox(VibeAgentSettings.VERIFY_MODES.toTypedArray()).apply { item = VibeAgentSettings.verifyMode }.also { verifyMode = it }
    val vCommand = JBTextField(VibeAgentSettings.verifyCommand, 28).also { verifyCommand = it }
    val vAttempts = JBIntSpinner(VibeAgentSettings.verifyMaxAttempts, VibeAgentSettings.MIN_VERIFY_MAX_ATTEMPTS, VibeAgentSettings.MAX_VERIFY_MAX_ATTEMPTS).also { verifyMaxAttempts = it }
    val vTimeout = JBIntSpinner(VibeAgentSettings.verifyTimeoutMs / 1000, VibeAgentSettings.MIN_VERIFY_TIMEOUT_MS / 1000, VibeAgentSettings.MAX_VERIFY_TIMEOUT_MS / 1000).also { verifyTimeoutSec = it }
    val cMode = ComboBox(VibeAgentSettings.CHECKS_MODES.toTypedArray()).apply { item = VibeAgentSettings.checksMode }.also { checksMode = it }
    val cAttempts = JBIntSpinner(VibeAgentSettings.checksMaxAttempts, VibeAgentSettings.MIN_CHECKS_MAX_ATTEMPTS, VibeAgentSettings.MAX_CHECKS_MAX_ATTEMPTS).also { checksMaxAttempts = it }
    val terminal = JBCheckBox("Разрешать агентам исполнять терминал (ACP terminal/…)", VibeAgentSettings.terminalEnabled).also { terminalEnabled = it }

    return FormBuilder.createFormBuilder()
      .addComponent(section("Хуки проекта"))
      .addComponent(hooks)
      .addComponent(hint("Команды из <code>.vibe/hooks.json</code> вокруг вызовов инструментов агента. Выполняются только в доверенной папке (Workspace Trust). " +
        "Выключено по умолчанию: клонированный чужой репозиторий не должен выполнять свой код. Формат — docs/vibe/manuals/hooksSpec.md."))
      .addComponent(section("Аудит"))
      .addComponent(audit)
      .addComponent(hint("Журнал действий агента в <code>.vibe/audit.jsonl</code> (промпты, ответы, вызовы инструментов, разрешения, записи, хуки, гейты). " +
        "Аргументы, тела команд и содержимое файлов не пишутся никогда — только имя инструмента и целевой путь. Формат — docs/vibe/manuals/auditSpec.md."))
      .addLabeledComponent("Ротация журнала, МБ:", rotation)
      .addComponent(section("VERIFY-GATE"))
      .addLabeledComponent("Режим:", vMode)
      .addComponent(hint("Проверка сборки/тестов при завершении хода, менявшего файлы. <b>off</b> — выкл; <b>warn</b> — красный результат не блокирует; " +
        "<b>enforce</b> — «не готово, пока не зелено»: агент возвращается на доработку до предела попыток."))
      .addLabeledComponent("Команда проверки:", vCommand)
      .addComponent(hint("Например <code>./bazel.cmd test //…</code> или <code>npm run verify</code>. Пусто — гейт бездействует."))
      .addLabeledComponent("Попыток возврата (enforce):", vAttempts)
      .addLabeledComponent("Таймаут проверки, сек:", vTimeout)
      .addComponent(section("Проверки хода"))
      .addLabeledComponent("Режим:", cMode)
      .addComponent(hint("Детерминированные проверки изменённых за ход файлов: утечка секрета и запись в защищённый путь. " +
        "<b>off</b> / <b>notify</b> (сообщить) / <b>enforce</b> (вернуть агента). LLM-судья не используется."))
      .addLabeledComponent("Попыток возврата (enforce):", cAttempts)
      .addComponent(section("Терминал агента"))
      .addComponent(terminal)
      .addComponent(hint("Живой вывод команд Claude показывается всегда. Этот флаг разрешает СТОРОННИМ агентам (Gemini CLI и др.) исполнять команды через " +
        "стандартные методы ACP <code>terminal/…</code> — с клампом таймаута, обрезкой вывода и подтверждением разрушительных команд."))
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
  }

  private fun section(text: String): JBLabel = JBLabel("<html><b>$text</b></html>").apply { border = JBUI.Borders.emptyTop(8) }

  private fun hint(html: String): JBLabel = JBLabel("<html>$html</html>").apply {
    foreground = com.intellij.ui.JBColor.GRAY
    font = com.intellij.util.ui.JBFont.label().deriveFont(11f)
  }

  override fun isModified(): Boolean =
    hooksEnabled?.isSelected != VibeAgentSettings.hooksEnabled ||
    auditEnabled?.isSelected != VibeAgentSettings.auditEnabled ||
    auditRotation?.number != VibeAgentSettings.auditRotationMb ||
    (verifyMode?.item ?: VibeAgentSettings.verifyMode) != VibeAgentSettings.verifyMode ||
    (verifyCommand?.text?.trim() ?: VibeAgentSettings.verifyCommand) != VibeAgentSettings.verifyCommand ||
    verifyMaxAttempts?.number != VibeAgentSettings.verifyMaxAttempts ||
    (verifyTimeoutSec?.number ?: 0) * 1000 != VibeAgentSettings.verifyTimeoutMs ||
    (checksMode?.item ?: VibeAgentSettings.checksMode) != VibeAgentSettings.checksMode ||
    checksMaxAttempts?.number != VibeAgentSettings.checksMaxAttempts ||
    terminalEnabled?.isSelected != VibeAgentSettings.terminalEnabled

  override fun apply() {
    hooksEnabled?.let { VibeAgentSettings.hooksEnabled = it.isSelected }
    auditEnabled?.let { VibeAgentSettings.auditEnabled = it.isSelected }
    auditRotation?.let { VibeAgentSettings.auditRotationMb = it.number }
    verifyMode?.let { VibeAgentSettings.verifyMode = it.item }
    verifyCommand?.let { VibeAgentSettings.verifyCommand = it.text }
    verifyMaxAttempts?.let { VibeAgentSettings.verifyMaxAttempts = it.number }
    verifyTimeoutSec?.let { VibeAgentSettings.verifyTimeoutMs = it.number * 1000 }
    checksMode?.let { VibeAgentSettings.checksMode = it.item }
    checksMaxAttempts?.let { VibeAgentSettings.checksMaxAttempts = it.number }
    terminalEnabled?.let { VibeAgentSettings.terminalEnabled = it.isSelected }
  }

  override fun reset() {
    hooksEnabled?.isSelected = VibeAgentSettings.hooksEnabled
    auditEnabled?.isSelected = VibeAgentSettings.auditEnabled
    auditRotation?.number = VibeAgentSettings.auditRotationMb
    verifyMode?.item = VibeAgentSettings.verifyMode
    verifyCommand?.text = VibeAgentSettings.verifyCommand
    verifyMaxAttempts?.number = VibeAgentSettings.verifyMaxAttempts
    verifyTimeoutSec?.number = VibeAgentSettings.verifyTimeoutMs / 1000
    checksMode?.item = VibeAgentSettings.checksMode
    checksMaxAttempts?.number = VibeAgentSettings.checksMaxAttempts
    terminalEnabled?.isSelected = VibeAgentSettings.terminalEnabled
  }
}
