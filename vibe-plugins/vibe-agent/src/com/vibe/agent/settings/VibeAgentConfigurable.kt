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
 * as such. NoScroll + our own scroll pane: the platform's wrapper sizes the view by
 * the unwrapped width of the html hints and adds a horizontal scrollbar (the Providers
 * lesson), but the page is taller than the dialog, so vertical scrolling is on us.
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
  private var checksMaxFiles: JBIntSpinner? = null
  private var checksMaxFileKb: JBIntSpinner? = null
  private var terminalEnabled: JBCheckBox? = null
  private var handshakeTimeout: JBIntSpinner? = null
  private var maskSecrets: JBCheckBox? = null
  private var warnForeign: JBCheckBox? = null
  private var httpApiEnabled: JBCheckBox? = null
  private var httpApiPort: JBIntSpinner? = null

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
    val cMaxFiles = JBIntSpinner(VibeAgentSettings.checksMaxFiles, VibeAgentSettings.MIN_CHECKS_MAX_FILES, VibeAgentSettings.MAX_CHECKS_MAX_FILES).also { checksMaxFiles = it }
    val cMaxFileKb = JBIntSpinner(VibeAgentSettings.checksMaxFileKb, VibeAgentSettings.MIN_CHECKS_MAX_FILE_KB, VibeAgentSettings.MAX_CHECKS_MAX_FILE_KB).also { checksMaxFileKb = it }
    val terminal = JBCheckBox("Разрешать агентам исполнять терминал (ACP terminal/…)", VibeAgentSettings.terminalEnabled).also { terminalEnabled = it }
    val mask = JBCheckBox("Маскировать секреты в отправляемом контексте", VibeAgentSettings.maskSecretsInContext).also { maskSecrets = it }
    val foreign = JBCheckBox("Предупреждать о незнакомом репозитории", VibeAgentSettings.warnForeignProject).also { warnForeign = it }
    val httpApi = JBCheckBox("Входящий HTTP API (только 127.0.0.1)", VibeAgentSettings.httpApiEnabled).also { httpApiEnabled = it }
    val apiPort = JBIntSpinner(VibeAgentSettings.httpApiPort, VibeAgentSettings.MIN_HTTP_API_PORT, VibeAgentSettings.MAX_HTTP_API_PORT)
      .also { httpApiPort = it }
    val handshake = JBIntSpinner(VibeAgentSettings.handshakeTimeoutSec, VibeAgentSettings.MIN_HANDSHAKE_TIMEOUT_SEC, VibeAgentSettings.MAX_HANDSHAKE_TIMEOUT_SEC).also { handshakeTimeout = it }

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
      .addLabeledComponent("Макс. файлов на скан:", cMaxFiles)
      .addLabeledComponent("Макс. размер файла, КБ:", cMaxFileKb)
      .addComponent(hint("Больше файлов/крупнее лимита — не сканируются; о пропуске сообщается в ленте чата."))
      .addComponent(section("Терминал агента"))
      .addComponent(terminal)
      .addComponent(hint("Живой вывод команд Claude показывается всегда. Этот флаг разрешает СТОРОННИМ агентам (Gemini CLI и др.) исполнять команды через " +
        "стандартные методы ACP <code>terminal/…</code> — с клампом таймаута, обрезкой вывода и подтверждением разрушительных команд."))
      .addComponent(section("Контекст: что уходит модели"))
      .addComponent(mask)
      .addComponent(hint("Невидимые символы (в т.ч. Unicode-теги, которых не видно вообще) и bidi-переопределения вырезаются из контекста ВСЕГДА — их не бывает в честном коде, " +
        "а спрятанную в них инструкцию человек при подтверждении не увидит. Текст, похожий на обращение к агенту, и секреты — отмечаются строкой «🛡 контекст» в ленте. " +
        "Этот флаг дополнительно заменяет само значение секрета маркером в том, что отправляется модели; <b>файл на диске не меняется никогда</b>. " +
        "По умолчанию выключен: агенту часто нужно работать с конфигом, где токен есть по делу."))
      .addComponent(foreign)
      .addComponent(hint("Одна строка при первом открытии проекта: содержимое чужого репозитория — данные, а не команды. Ничего не блокирует и больше не повторяется."))
      .addComponent(section("Входящий HTTP API"))
      .addComponent(httpApi)
      .addLabeledComponent("Порт (0 — любой свободный):", apiPort)
      .addComponent(hint("Позволяет запускать агента из CI, бота или крона: <code>POST /run</code> с задачей, <code>GET /health</code> для проверки. " +
        "Слушается ТОЛЬКО петлевой адрес — настройки «слушать снаружи» нет и не будет. Каждый запрос требует токен " +
        "(<code>Authorization: Bearer …</code>), токен живёт в хранилище ОС и показывается действием «VibeIDEA: показать токен HTTP API». " +
        "Задачу выполняет агент в открытом окне; изменения применяются сразу, без перезапуска IDE. Спека — docs/vibe/manuals/httpApiSpec.md."))
      .addComponent(section("Соединение"))
      .addLabeledComponent("Таймаут рукопожатия агента, сек:", handshake)
      .addComponent(hint("Сколько ждать ответа агента на initialize/session/new. Холодный запуск через npx может быть медленным."))
      .addComponentFillVertically(JPanel(), 0)
      .panel.apply { border = JBUI.Borders.empty(8) }
      // NoScroll only removes the platform's wrapper — the scrolling itself is ours, or the page
      // simply gets cut off at the window edge (it is taller than a settings dialog).
      // TracksViewportWidthPanel keeps the html hints wrapping to the width instead of demanding
      // a horizontal scrollbar — the reason the platform wrapper was refused in the first place.
      .let { form -> com.vibe.agent.ui.VibeScroll.pane(TracksViewportWidthPanel(form)).apply { border = JBUI.Borders.empty() } }
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
    checksMaxFiles?.number != VibeAgentSettings.checksMaxFiles ||
    checksMaxFileKb?.number != VibeAgentSettings.checksMaxFileKb ||
    terminalEnabled?.isSelected != VibeAgentSettings.terminalEnabled ||
    handshakeTimeout?.number != VibeAgentSettings.handshakeTimeoutSec ||
    maskSecrets?.isSelected != VibeAgentSettings.maskSecretsInContext ||
    warnForeign?.isSelected != VibeAgentSettings.warnForeignProject ||
    httpApiEnabled?.isSelected != VibeAgentSettings.httpApiEnabled ||
    httpApiPort?.number != VibeAgentSettings.httpApiPort

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
    checksMaxFiles?.let { VibeAgentSettings.checksMaxFiles = it.number }
    checksMaxFileKb?.let { VibeAgentSettings.checksMaxFileKb = it.number }
    terminalEnabled?.let { VibeAgentSettings.terminalEnabled = it.isSelected }
    handshakeTimeout?.let { VibeAgentSettings.handshakeTimeoutSec = it.number }
    maskSecrets?.let { VibeAgentSettings.maskSecretsInContext = it.isSelected }
    warnForeign?.let { VibeAgentSettings.warnForeignProject = it.isSelected }
    // Port first: the listener is (re)started below with the value that has just been stored.
    httpApiPort?.let { VibeAgentSettings.httpApiPort = it.number }
    httpApiEnabled?.let { box ->
      val portChanged = VibeAgentSettings.httpApiEnabled == box.isSelected && box.isSelected
      VibeAgentSettings.httpApiEnabled = box.isSelected
      val service = com.vibe.agent.http.VibeHttpApiService.getInstance()
      // A port change means rebinding — stop, then start on the new one.
      if (portChanged && service.isRunning) { VibeAgentSettings.httpApiEnabled = false; service.sync(); VibeAgentSettings.httpApiEnabled = true }
      service.sync()
    }
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
    checksMaxFiles?.number = VibeAgentSettings.checksMaxFiles
    checksMaxFileKb?.number = VibeAgentSettings.checksMaxFileKb
    handshakeTimeout?.number = VibeAgentSettings.handshakeTimeoutSec
    terminalEnabled?.isSelected = VibeAgentSettings.terminalEnabled
    httpApiEnabled?.isSelected = VibeAgentSettings.httpApiEnabled
    httpApiPort?.number = VibeAgentSettings.httpApiPort
    maskSecrets?.isSelected = VibeAgentSettings.maskSecretsInContext
    warnForeign?.isSelected = VibeAgentSettings.warnForeignProject
  }
}
