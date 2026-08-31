// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t
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
  private var silenceMinutes: JBIntSpinner? = null
  private var autopilotEnabled: JBCheckBox? = null
  private var autopilotMaxTurns: JBIntSpinner? = null
  private var autopilotCheckpoint: JBIntSpinner? = null
  private var roleBudgetTokens: JBIntSpinner? = null
  private var councilField: com.intellij.ui.components.JBTextField? = null
  private var contextFilter: com.intellij.openapi.ui.ComboBox<String>? = null
  private var proxyField: com.intellij.ui.components.JBTextField? = null
  private var digestField: com.intellij.ui.components.JBTextField? = null
  private var embeddingField: com.intellij.ui.components.JBTextField? = null
  private var minimalismMode: com.intellij.openapi.ui.ComboBox<String>? = null
  private var metricPattern: com.intellij.ui.components.JBTextField? = null
  private var offlineBox: com.intellij.ui.components.JBCheckBox? = null
  private var reasoningLevel: com.intellij.openapi.ui.ComboBox<String>? = null
  private var docsFolderField: com.intellij.ui.components.JBTextField? = null
  private var watchLangs: com.intellij.ui.components.JBTextField? = null
  private var watchFramesSpinner: JBIntSpinner? = null
  private var watchHeightSpinner: JBIntSpinner? = null
  private var telegramProxyField: com.intellij.ui.components.JBTextField? = null
  private var telegramProjectField: com.intellij.ui.components.JBTextField? = null
  private var metricDirection: com.intellij.openapi.ui.ComboBox<String>? = null
  private var failoverField: com.intellij.ui.components.JBTextField? = null
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
  private var designMode: com.intellij.openapi.ui.ComboBox<String>? = null
  private var designAttempts: JBIntSpinner? = null
  private var fimEnabled: JBCheckBox? = null
  private var fimDebounce: JBIntSpinner? = null
  private var fimCache: JBIntSpinner? = null
  private var runLedger: JBCheckBox? = null
  private var runLedgerMax: JBIntSpinner? = null
  private var runLedgerDays: JBIntSpinner? = null
  private var maskSecrets: JBCheckBox? = null
  private var warnForeign: JBCheckBox? = null
  private var httpApiEnabled: JBCheckBox? = null
  private var httpApiPort: JBIntSpinner? = null

  override fun getDisplayName(): String = t("settings.agent.title")

  override fun createComponent(): JComponent {
    val hooks = JBCheckBox(t("settings.agent.hooks"), VibeAgentSettings.hooksEnabled).also { hooksEnabled = it }
    val audit = JBCheckBox(t("settings.agent.audit"), VibeAgentSettings.auditEnabled).also { auditEnabled = it }
    // Настройки, до которых раньше нельзя было дотянуться иначе как правкой properties: настройка,
    // которую нельзя открыть, — это настройка, которой нет.
    val docsFolder = com.intellij.ui.components.JBTextField(VibeAgentSettings.docsFolder, 16).also { docsFolderField = it }
    val watchLanguages = com.intellij.ui.components.JBTextField(VibeAgentSettings.watchSubtitleLanguages, 16).also { watchLangs = it }
    val watchFrames = JBIntSpinner(VibeAgentSettings.watchMaxFrames, VibeAgentSettings.MIN_WATCH_MAX_FRAMES, VibeAgentSettings.MAX_WATCH_MAX_FRAMES).also { watchFramesSpinner = it }
    val watchHeight = JBIntSpinner(VibeAgentSettings.watchFrameHeight, VibeAgentSettings.MIN_WATCH_FRAME_HEIGHT, VibeAgentSettings.MAX_WATCH_FRAME_HEIGHT).also { watchHeightSpinner = it }
    val telegramProxy = com.intellij.ui.components.JBTextField(VibeAgentSettings.telegramProxy, 24).also { telegramProxyField = it }
    val telegramProject = com.intellij.ui.components.JBTextField(VibeAgentSettings.telegramProject, 24).also { telegramProjectField = it }
    val reasoning = com.intellij.openapi.ui.ComboBox(arrayOf("off", "low", "medium", "high")).also {
      it.selectedItem = VibeAgentSettings.reasoningLevel
      reasoningLevel = it
    }
    val offline = com.intellij.ui.components.JBCheckBox(t("settings.agent.offline"), VibeAgentSettings.offline).also { offlineBox = it }
    val metric = com.intellij.ui.components.JBTextField(VibeAgentSettings.metricPattern, 28).also { metricPattern = it }
    val metricDir = com.intellij.openapi.ui.ComboBox(arrayOf("lower", "higher")).also {
      it.selectedItem = VibeAgentSettings.metricDirection
      metricDirection = it
    }
    val minimalism = com.intellij.openapi.ui.ComboBox(arrayOf("off", "light", "full", "ultra")).also {
      it.selectedItem = VibeAgentSettings.minimalismMode
      minimalismMode = it
    }
    val embedding = com.intellij.ui.components.JBTextField(VibeAgentSettings.embeddingModel, 32).also { embeddingField = it }
    val digest = com.intellij.ui.components.JBTextField(VibeAgentSettings.digestTime, 8).also { digestField = it }
    val proxy = com.intellij.ui.components.JBTextField(VibeAgentSettings.llmProxyUrl, 32).also { proxyField = it }
    val failover = com.intellij.ui.components.JBTextField(VibeAgentSettings.failoverChain, 32).also { failoverField = it }
    val filterMode = com.intellij.openapi.ui.ComboBox(arrayOf("auto", "raw", "aggregate", "off")).also {
      it.selectedItem = VibeAgentSettings.contextFilterMode
      contextFilter = it
    }
    val council = com.intellij.ui.components.JBTextField(VibeAgentSettings.councilAdvisers, 32).also { councilField = it }
    val roleBudget = JBIntSpinner(VibeAgentSettings.roleBudgetTokens, 0, VibeAgentSettings.MAX_ROLE_BUDGET_TOKENS).also { roleBudgetTokens = it }
    val silence = JBIntSpinner(VibeAgentSettings.agentSilenceMinutes, 0, VibeAgentSettings.MAX_SILENCE_MINUTES).also { silenceMinutes = it }
    val autopilot = JBCheckBox(t("autopilot.enabled"), VibeAgentSettings.autopilotEnabled).also { autopilotEnabled = it }
    val autopilotTurns = JBIntSpinner(VibeAgentSettings.autopilotMaxTurns, 0, VibeAgentSettings.MAX_AUTOPILOT_TURNS).also { autopilotMaxTurns = it }
    val autopilotEvery = JBIntSpinner(VibeAgentSettings.autopilotCheckpointEvery, 0, VibeAgentSettings.MAX_AUTOPILOT_TURNS).also { autopilotCheckpoint = it }
    val rotation = JBIntSpinner(VibeAgentSettings.auditRotationMb, VibeAgentSettings.MIN_AUDIT_ROTATION_MB, VibeAgentSettings.MAX_AUDIT_ROTATION_MB).also { auditRotation = it }
    val vMode = ComboBox(VibeAgentSettings.VERIFY_MODES.toTypedArray()).apply { item = VibeAgentSettings.verifyMode }.also { verifyMode = it }
    val vCommand = JBTextField(VibeAgentSettings.verifyCommand, 28).also { verifyCommand = it }
    val vAttempts = JBIntSpinner(VibeAgentSettings.verifyMaxAttempts, VibeAgentSettings.MIN_VERIFY_MAX_ATTEMPTS, VibeAgentSettings.MAX_VERIFY_MAX_ATTEMPTS).also { verifyMaxAttempts = it }
    val vTimeout = JBIntSpinner(VibeAgentSettings.verifyTimeoutMs / 1000, VibeAgentSettings.MIN_VERIFY_TIMEOUT_MS / 1000, VibeAgentSettings.MAX_VERIFY_TIMEOUT_MS / 1000).also { verifyTimeoutSec = it }
    val cMode = ComboBox(VibeAgentSettings.CHECKS_MODES.toTypedArray()).apply { item = VibeAgentSettings.checksMode }.also { checksMode = it }
    val cAttempts = JBIntSpinner(VibeAgentSettings.checksMaxAttempts, VibeAgentSettings.MIN_CHECKS_MAX_ATTEMPTS, VibeAgentSettings.MAX_CHECKS_MAX_ATTEMPTS).also { checksMaxAttempts = it }
    val cMaxFiles = JBIntSpinner(VibeAgentSettings.checksMaxFiles, VibeAgentSettings.MIN_CHECKS_MAX_FILES, VibeAgentSettings.MAX_CHECKS_MAX_FILES).also { checksMaxFiles = it }
    val cMaxFileKb = JBIntSpinner(VibeAgentSettings.checksMaxFileKb, VibeAgentSettings.MIN_CHECKS_MAX_FILE_KB, VibeAgentSettings.MAX_CHECKS_MAX_FILE_KB).also { checksMaxFileKb = it }
    val terminal = JBCheckBox(t("settings.agent.terminal"), VibeAgentSettings.terminalEnabled).also { terminalEnabled = it }
    val design = com.intellij.openapi.ui.ComboBox(VibeAgentSettings.DESIGN_MODES.toTypedArray())
      .also { it.item = VibeAgentSettings.designMode; designMode = it }
    val designTries = JBIntSpinner(VibeAgentSettings.designMaxAttempts, VibeAgentSettings.MIN_DESIGN_MAX_ATTEMPTS, VibeAgentSettings.MAX_DESIGN_MAX_ATTEMPTS)
      .also { designAttempts = it }
    val fim = JBCheckBox(t("settings.agent.fim"), VibeAgentSettings.fimEnabled).also { fimEnabled = it }
    val fimDelay = JBIntSpinner(VibeAgentSettings.fimDebounceMs, VibeAgentSettings.MIN_FIM_DEBOUNCE_MS, VibeAgentSettings.MAX_FIM_DEBOUNCE_MS)
      .also { fimDebounce = it }
    val fimCacheSize = JBIntSpinner(VibeAgentSettings.fimCacheSize, VibeAgentSettings.MIN_FIM_CACHE_SIZE, VibeAgentSettings.MAX_FIM_CACHE_SIZE)
      .also { fimCache = it }
    val ledger = JBCheckBox(t("settings.agent.runLedger"), VibeAgentSettings.runLedgerEnabled).also { runLedger = it }
    val ledgerMax = JBIntSpinner(VibeAgentSettings.runLedgerMaxRecords, VibeAgentSettings.MIN_RUN_LEDGER_MAX_RECORDS, VibeAgentSettings.MAX_RUN_LEDGER_MAX_RECORDS)
      .also { runLedgerMax = it }
    val ledgerDays = JBIntSpinner(VibeAgentSettings.runLedgerRetentionDays, VibeAgentSettings.MIN_RUN_LEDGER_RETENTION_DAYS, VibeAgentSettings.MAX_RUN_LEDGER_RETENTION_DAYS)
      .also { runLedgerDays = it }
    val mask = JBCheckBox(t("settings.agent.maskSecrets"), VibeAgentSettings.maskSecretsInContext).also { maskSecrets = it }
    val foreign = JBCheckBox(t("settings.agent.warnForeign"), VibeAgentSettings.warnForeignProject).also { warnForeign = it }
    val httpApi = JBCheckBox(t("settings.agent.httpApi"), VibeAgentSettings.httpApiEnabled).also { httpApiEnabled = it }
    val apiPort = JBIntSpinner(VibeAgentSettings.httpApiPort, VibeAgentSettings.MIN_HTTP_API_PORT, VibeAgentSettings.MAX_HTTP_API_PORT)
      .also { httpApiPort = it }
    val handshake = JBIntSpinner(VibeAgentSettings.handshakeTimeoutSec, VibeAgentSettings.MIN_HANDSHAKE_TIMEOUT_SEC, VibeAgentSettings.MAX_HANDSHAKE_TIMEOUT_SEC).also { handshakeTimeout = it }

    return FormBuilder.createFormBuilder()
      .addComponent(section(t("settings.agent.section.hooks")))
      .addComponent(hooks)
      .addComponent(hint(t("settings.agent.hint.hooks")))
      .addComponent(section(t("settings.agent.section.audit")))
      .addComponent(audit)
      .addComponent(hint(t("settings.agent.hint.audit")))
      .addLabeledComponent(t("settings.agent.auditRotation"), rotation)
      .addComponent(section(t("settings.agent.section.safety")))
      .addLabeledComponent(t("settings.agent.silence"), silence)
      .addComponent(hint(t("settings.agent.hint.silence")))
      .addComponent(com.intellij.ui.TitledSeparator(t("autopilot.title")))
      .addComponent(autopilot)
      .addLabeledComponent(t("autopilot.maxTurns"), autopilotTurns)
      .addLabeledComponent(t("autopilot.checkpointEvery"), autopilotEvery)
      .addComponent(hint(t("autopilot.hint")))
      .addLabeledComponent(t("settings.agent.reasoning"), reasoning)
      .addComponent(hint(t("settings.agent.hint.reasoning")))
      .addComponent(offline)
      .addComponent(hint(t("settings.agent.hint.offline")))
      .addLabeledComponent(t("settings.agent.metricPattern"), metric)
      .addLabeledComponent(t("settings.agent.metricDirection"), metricDir)
      .addComponent(hint(t("settings.agent.hint.metricPattern")))
      .addLabeledComponent(t("settings.agent.minimalism"), minimalism)
      .addComponent(hint(t("settings.agent.hint.minimalism")))
      .addLabeledComponent(t("settings.agent.embedding"), embedding)
      .addComponent(hint(t("settings.agent.hint.embedding")))
      .addLabeledComponent(t("settings.agent.digest"), digest)
      .addComponent(hint(t("settings.agent.hint.digest")))
      .addLabeledComponent(t("settings.agent.proxy"), proxy)
      .addComponent(hint(t("settings.agent.hint.proxy")))
      .addLabeledComponent(t("settings.agent.failover"), failover)
      .addComponent(hint(t("settings.agent.hint.failover")))
      .addLabeledComponent(t("settings.agent.contextFilter"), filterMode)
      .addComponent(hint(t("settings.agent.hint.contextFilter")))
      .addLabeledComponent(t("settings.agent.council"), council)
      .addComponent(hint(t("settings.agent.hint.council")))
      .addLabeledComponent(t("settings.agent.roleBudget"), roleBudget)
      .addComponent(hint(t("settings.agent.hint.roleBudget")))
      .addComponent(section("VERIFY-GATE"))
      .addLabeledComponent(t("settings.agent.mode"), vMode)
      .addComponent(hint(t("settings.agent.hint.verifyModes")))
      .addLabeledComponent(t("settings.agent.verifyCommand"), vCommand)
      .addComponent(hint(t("settings.agent.hint.verifyCommandHint")))
      .addLabeledComponent(t("settings.agent.bounceAttempts"), vAttempts)
      .addLabeledComponent(t("settings.agent.verifyTimeout"), vTimeout)
      .addComponent(section(t("settings.agent.section.checks")))
      .addLabeledComponent(t("settings.agent.mode"), cMode)
      .addComponent(hint(t("settings.agent.hint.checksModes")))
      .addLabeledComponent(t("settings.agent.bounceAttempts"), cAttempts)
      .addLabeledComponent(t("settings.agent.maxFiles"), cMaxFiles)
      .addLabeledComponent(t("settings.agent.maxFileKb"), cMaxFileKb)
      .addComponent(hint(t("settings.agent.hint.checksLimitsHint")))
      .addComponent(section(t("settings.agent.section.terminal")))
      .addComponent(terminal)
      .addComponent(hint(t("settings.agent.hint.terminalHint")))
      .addComponent(section(t("settings.agent.section.design")))
      .addLabeledComponent(t("settings.agent.mode"), design)
      .addLabeledComponent(t("settings.agent.designAttempts"), designTries)
      .addComponent(hint(t("settings.agent.hint.designHint")))
      .addComponent(section(t("settings.agent.section.fim")))
      .addComponent(fim)
      .addLabeledComponent(t("settings.agent.fimDebounce"), fimDelay)
      .addLabeledComponent(t("settings.agent.fimCache"), fimCacheSize)
      .addComponent(hint(t("settings.agent.hint.fimHint")))
      .addComponent(section(t("settings.agent.section.runs")))
      .addComponent(ledger)
      .addLabeledComponent(t("settings.agent.runsMax"), ledgerMax)
      .addLabeledComponent(t("settings.agent.runsDays"), ledgerDays)
      .addComponent(hint(t("settings.agent.hint.runsHint")))
      .addComponent(section(t("settings.agent.section.context")))
      .addComponent(mask)
      .addComponent(hint(t("settings.agent.hint.contextHint")))
      .addComponent(foreign)
      .addComponent(hint(t("settings.agent.hint.foreignHint")))
      .addComponent(section(t("settings.agent.section.httpApi")))
      .addComponent(httpApi)
      .addLabeledComponent(t("settings.agent.httpPort"), apiPort)
      .addComponent(hint(t("settings.agent.hint.httpApiHint")))
      .addComponent(section(t("settings.agent.section.connection")))
      .addLabeledComponent(t("settings.agent.handshake"), handshake)
      .addComponent(hint(t("settings.agent.hint.handshakeHint")))
      .addComponent(section(t("settings.agent.section.more")))
      .addLabeledComponent(t("settings.agent.docsFolder"), docsFolder)
      .addLabeledComponent(t("settings.agent.watchLanguages"), watchLanguages)
      .addLabeledComponent(t("settings.agent.watchFrames"), watchFrames)
      .addLabeledComponent(t("settings.agent.watchHeight"), watchHeight)
      .addComponent(hint(t("settings.agent.hint.watch")))
      .addLabeledComponent(t("settings.agent.telegramProject"), telegramProject)
      .addLabeledComponent(t("settings.agent.telegramProxy"), telegramProxy)
      .addComponent(hint(t("settings.agent.hint.telegram")))
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
    silenceMinutes?.number != VibeAgentSettings.agentSilenceMinutes ||
    autopilotEnabled?.isSelected != VibeAgentSettings.autopilotEnabled ||
    autopilotMaxTurns?.number != VibeAgentSettings.autopilotMaxTurns ||
    autopilotCheckpoint?.number != VibeAgentSettings.autopilotCheckpointEvery ||
    roleBudgetTokens?.number != VibeAgentSettings.roleBudgetTokens ||
    (councilField?.text?.trim() ?: VibeAgentSettings.councilAdvisers) != VibeAgentSettings.councilAdvisers ||
    (contextFilter?.selectedItem as? String ?: VibeAgentSettings.contextFilterMode) != VibeAgentSettings.contextFilterMode ||
    (proxyField?.text?.trim() ?: VibeAgentSettings.llmProxyUrl) != VibeAgentSettings.llmProxyUrl ||
    (digestField?.text?.trim() ?: VibeAgentSettings.digestTime) != VibeAgentSettings.digestTime ||
    (embeddingField?.text?.trim() ?: VibeAgentSettings.embeddingModel) != VibeAgentSettings.embeddingModel ||
    (minimalismMode?.selectedItem as? String ?: VibeAgentSettings.minimalismMode) != VibeAgentSettings.minimalismMode ||
    (metricPattern?.text?.trim() ?: VibeAgentSettings.metricPattern) != VibeAgentSettings.metricPattern ||
    (offlineBox?.isSelected ?: VibeAgentSettings.offline) != VibeAgentSettings.offline ||
    (reasoningLevel?.selectedItem as? String ?: VibeAgentSettings.reasoningLevel) != VibeAgentSettings.reasoningLevel ||
    (docsFolderField?.text?.trim() ?: VibeAgentSettings.docsFolder) != VibeAgentSettings.docsFolder ||
    (watchLangs?.text?.trim() ?: VibeAgentSettings.watchSubtitleLanguages) != VibeAgentSettings.watchSubtitleLanguages ||
    (watchFramesSpinner?.number ?: VibeAgentSettings.watchMaxFrames) != VibeAgentSettings.watchMaxFrames ||
    (watchHeightSpinner?.number ?: VibeAgentSettings.watchFrameHeight) != VibeAgentSettings.watchFrameHeight ||
    (telegramProxyField?.text?.trim() ?: VibeAgentSettings.telegramProxy) != VibeAgentSettings.telegramProxy ||
    (telegramProjectField?.text?.trim() ?: VibeAgentSettings.telegramProject) != VibeAgentSettings.telegramProject ||
    (metricDirection?.selectedItem as? String ?: VibeAgentSettings.metricDirection) != VibeAgentSettings.metricDirection ||
    (failoverField?.text?.trim() ?: VibeAgentSettings.failoverChain) != VibeAgentSettings.failoverChain ||
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
    (designMode?.item ?: VibeAgentSettings.designMode) != VibeAgentSettings.designMode ||
    designAttempts?.number != VibeAgentSettings.designMaxAttempts ||
    fimEnabled?.isSelected != VibeAgentSettings.fimEnabled ||
    fimDebounce?.number != VibeAgentSettings.fimDebounceMs ||
    fimCache?.number != VibeAgentSettings.fimCacheSize ||
    runLedger?.isSelected != VibeAgentSettings.runLedgerEnabled ||
    runLedgerMax?.number != VibeAgentSettings.runLedgerMaxRecords ||
    runLedgerDays?.number != VibeAgentSettings.runLedgerRetentionDays ||
    maskSecrets?.isSelected != VibeAgentSettings.maskSecretsInContext ||
    warnForeign?.isSelected != VibeAgentSettings.warnForeignProject ||
    httpApiEnabled?.isSelected != VibeAgentSettings.httpApiEnabled ||
    httpApiPort?.number != VibeAgentSettings.httpApiPort

  override fun apply() {
    hooksEnabled?.let { VibeAgentSettings.hooksEnabled = it.isSelected }
    auditEnabled?.let { VibeAgentSettings.auditEnabled = it.isSelected }
    auditRotation?.let { VibeAgentSettings.auditRotationMb = it.number }
    silenceMinutes?.let { VibeAgentSettings.agentSilenceMinutes = it.number }
    autopilotEnabled?.let { VibeAgentSettings.autopilotEnabled = it.isSelected }
    autopilotMaxTurns?.let { VibeAgentSettings.autopilotMaxTurns = it.number }
    autopilotCheckpoint?.let { VibeAgentSettings.autopilotCheckpointEvery = it.number }
    roleBudgetTokens?.let { VibeAgentSettings.roleBudgetTokens = it.number }
    councilField?.let { VibeAgentSettings.councilAdvisers = it.text }
    (contextFilter?.selectedItem as? String)?.let { VibeAgentSettings.contextFilterMode = it }
    proxyField?.let { VibeAgentSettings.llmProxyUrl = it.text }
    digestField?.let { VibeAgentSettings.digestTime = it.text }
    embeddingField?.let { VibeAgentSettings.embeddingModel = it.text }
    (minimalismMode?.selectedItem as? String)?.let { VibeAgentSettings.minimalismMode = it }
    metricPattern?.let { VibeAgentSettings.metricPattern = it.text }
    offlineBox?.let { VibeAgentSettings.offline = it.isSelected }
    (reasoningLevel?.selectedItem as? String)?.let { VibeAgentSettings.reasoningLevel = it }
    docsFolderField?.let { VibeAgentSettings.docsFolder = it.text }
    watchLangs?.let { VibeAgentSettings.watchSubtitleLanguages = it.text }
    watchFramesSpinner?.let { VibeAgentSettings.watchMaxFrames = it.number }
    watchHeightSpinner?.let { VibeAgentSettings.watchFrameHeight = it.number }
    telegramProxyField?.let { VibeAgentSettings.telegramProxy = it.text }
    telegramProjectField?.let { VibeAgentSettings.telegramProject = it.text }
    (metricDirection?.selectedItem as? String)?.let { VibeAgentSettings.metricDirection = it }
    failoverField?.let { VibeAgentSettings.failoverChain = it.text }
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
    designMode?.let { VibeAgentSettings.designMode = it.item }
    designAttempts?.let { VibeAgentSettings.designMaxAttempts = it.number }
    fimEnabled?.let { VibeAgentSettings.fimEnabled = it.isSelected }
    fimDebounce?.let { VibeAgentSettings.fimDebounceMs = it.number }
    fimCache?.let { VibeAgentSettings.fimCacheSize = it.number }
    runLedger?.let { VibeAgentSettings.runLedgerEnabled = it.isSelected }
    runLedgerMax?.let { VibeAgentSettings.runLedgerMaxRecords = it.number }
    runLedgerDays?.let { VibeAgentSettings.runLedgerRetentionDays = it.number }
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
    silenceMinutes?.number = VibeAgentSettings.agentSilenceMinutes
    autopilotEnabled?.isSelected = VibeAgentSettings.autopilotEnabled
    autopilotMaxTurns?.number = VibeAgentSettings.autopilotMaxTurns
    autopilotCheckpoint?.number = VibeAgentSettings.autopilotCheckpointEvery
    roleBudgetTokens?.number = VibeAgentSettings.roleBudgetTokens
    councilField?.text = VibeAgentSettings.councilAdvisers
    contextFilter?.selectedItem = VibeAgentSettings.contextFilterMode
    proxyField?.text = VibeAgentSettings.llmProxyUrl
    digestField?.text = VibeAgentSettings.digestTime
    embeddingField?.text = VibeAgentSettings.embeddingModel
    minimalismMode?.selectedItem = VibeAgentSettings.minimalismMode
    metricPattern?.text = VibeAgentSettings.metricPattern
    offlineBox?.isSelected = VibeAgentSettings.offline
    reasoningLevel?.selectedItem = VibeAgentSettings.reasoningLevel
    docsFolderField?.text = VibeAgentSettings.docsFolder
    watchLangs?.text = VibeAgentSettings.watchSubtitleLanguages
    watchFramesSpinner?.number = VibeAgentSettings.watchMaxFrames
    watchHeightSpinner?.number = VibeAgentSettings.watchFrameHeight
    telegramProxyField?.text = VibeAgentSettings.telegramProxy
    telegramProjectField?.text = VibeAgentSettings.telegramProject
    metricDirection?.selectedItem = VibeAgentSettings.metricDirection
    failoverField?.text = VibeAgentSettings.failoverChain
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
    designMode?.item = VibeAgentSettings.designMode
    designAttempts?.number = VibeAgentSettings.designMaxAttempts
    fimEnabled?.isSelected = VibeAgentSettings.fimEnabled
    fimDebounce?.number = VibeAgentSettings.fimDebounceMs
    fimCache?.number = VibeAgentSettings.fimCacheSize
    runLedger?.isSelected = VibeAgentSettings.runLedgerEnabled
    runLedgerMax?.number = VibeAgentSettings.runLedgerMaxRecords
    runLedgerDays?.number = VibeAgentSettings.runLedgerRetentionDays
    maskSecrets?.isSelected = VibeAgentSettings.maskSecretsInContext
    warnForeign?.isSelected = VibeAgentSettings.warnForeignProject
  }
}
