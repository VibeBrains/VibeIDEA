// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.util.PropertiesComponent

/**
 * Agent-behaviour knobs with defaults (no hard-coded behaviour in services).
 * Application-level via [PropertiesComponent]; every user-significant threshold
 * lives here so a service never carries a magic number. VibeIDE defaults are
 * kept verbatim where they still apply to the ACP model.
 *
 * Security-shaped switches (hooks, audit) default OFF on purpose: cloning a
 * foreign repository must not run its code or start logging without consent.
 */
object VibeAgentSettings {
  // --- hooks (.vibe/hooks.json) ---
  const val DEFAULT_HOOKS_ENABLED = false
  // --- audit (.vibe/audit.jsonl) ---
  const val DEFAULT_AUDIT_ENABLED = false
  const val DEFAULT_AUDIT_ROTATION_MB = 10
  const val MIN_AUDIT_ROTATION_MB = 1
  const val MAX_AUDIT_ROTATION_MB = 1000
  // --- verify-gate ---
  const val VERIFY_OFF = "off"
  const val VERIFY_WARN = "warn"
  const val VERIFY_ENFORCE = "enforce"
  val VERIFY_MODES = listOf(VERIFY_OFF, VERIFY_WARN, VERIFY_ENFORCE)
  const val DEFAULT_VERIFY_MODE = VERIFY_OFF
  const val DEFAULT_VERIFY_COMMAND = ""
  const val DEFAULT_VERIFY_MAX_ATTEMPTS = 3
  const val MIN_VERIFY_MAX_ATTEMPTS = 1
  const val MAX_VERIFY_MAX_ATTEMPTS = 10
  const val DEFAULT_VERIFY_TIMEOUT_MS = 300_000
  const val MIN_VERIFY_TIMEOUT_MS = 5_000
  const val MAX_VERIFY_TIMEOUT_MS = 1_800_000
  // --- turn checks ---
  const val CHECKS_OFF = "off"
  const val CHECKS_NOTIFY = "notify"
  const val CHECKS_ENFORCE = "enforce"
  val CHECKS_MODES = listOf(CHECKS_OFF, CHECKS_NOTIFY, CHECKS_ENFORCE)
  const val DEFAULT_CHECKS_MODE = CHECKS_NOTIFY
  const val DEFAULT_CHECKS_MAX_ATTEMPTS = 2
  const val MIN_CHECKS_MAX_ATTEMPTS = 1
  const val MAX_CHECKS_MAX_ATTEMPTS = 10
  const val DEFAULT_CHECKS_MAX_FILES = 40
  const val MIN_CHECKS_MAX_FILES = 1
  const val MAX_CHECKS_MAX_FILES = 5000
  const val DEFAULT_CHECKS_MAX_FILE_KB = 512
  const val MIN_CHECKS_MAX_FILE_KB = 16
  const val MAX_CHECKS_MAX_FILE_KB = 65536
  // --- terminal (client-executed terminal/… for non-Claude agents) ---
  const val DEFAULT_TERMINAL_ENABLED = true
  /** Fallback output cap when an agent's terminal/create omits outputByteLimit (never unbounded). */
  const val DEFAULT_TERMINAL_OUTPUT_BYTE_LIMIT = 1_048_576L
  // --- /watch (video and audio review) ---
  const val DEFAULT_WATCH_SCENE_THRESHOLD = 0.3
  const val DEFAULT_WATCH_MAX_FRAMES = 12
  const val MIN_WATCH_MAX_FRAMES = 2
  const val MAX_WATCH_MAX_FRAMES = 40
  const val DEFAULT_WATCH_FRAME_HEIGHT = 720
  const val MIN_WATCH_FRAME_HEIGHT = 240
  const val MAX_WATCH_FRAME_HEIGHT = 1080
  const val DEFAULT_WATCH_SUBTITLE_LANGUAGES = "ru,en"

  // --- design gate ---
  const val DESIGN_OFF = "off"
  const val DESIGN_NOTIFY = "notify"
  const val DESIGN_ENFORCE_FLOOR = "enforceFloor"
  val DESIGN_MODES = listOf(DESIGN_OFF, DESIGN_NOTIFY, DESIGN_ENFORCE_FLOOR)
  /** Off by default: measuring needs an open preview, and a gate that cannot run must not nag. */
  const val DEFAULT_DESIGN_MODE = DESIGN_OFF
  const val DEFAULT_DESIGN_MAX_ATTEMPTS = 2
  const val MIN_DESIGN_MAX_ATTEMPTS = 1
  const val MAX_DESIGN_MAX_ATTEMPTS = 5
  const val DESIGN_MEASURE_TIMEOUT_MS = 20_000L

  // --- FIM autocomplete ---
  const val DEFAULT_FIM_ENABLED = true
  const val DEFAULT_FIM_DEBOUNCE_MS = 250
  const val MIN_FIM_DEBOUNCE_MS = 50
  const val MAX_FIM_DEBOUNCE_MS = 2000
  const val DEFAULT_FIM_CACHE_SIZE = 200
  const val MIN_FIM_CACHE_SIZE = 0
  const val MAX_FIM_CACHE_SIZE = 5000

  // --- run ledger (.vibe/agent-runs.jsonl) ---
  const val DEFAULT_RUN_LEDGER_ENABLED = true
  const val DEFAULT_RUN_LEDGER_MAX_RECORDS = 500
  const val MIN_RUN_LEDGER_MAX_RECORDS = 50
  const val MAX_RUN_LEDGER_MAX_RECORDS = 10_000
  const val DEFAULT_RUN_LEDGER_RETENTION_DAYS = 30
  const val MIN_RUN_LEDGER_RETENTION_DAYS = 1
  const val MAX_RUN_LEDGER_RETENTION_DAYS = 365

  // --- context guard (what goes INTO the model) ---
  /** Masking is off by default: the agent often needs to work with a config that has a token in it. */
  const val DEFAULT_MASK_SECRETS_IN_CONTEXT = false
  /** Warn once per project that was never opened here before — a foreign repo can address the agent. */
  const val DEFAULT_WARN_FOREIGN_PROJECT = true

  // --- incoming HTTP API (VibeIDE contract: loopback only, off by default) ---
  const val DEFAULT_HTTP_API_ENABLED = false
  /** 0 = any free port; the chosen one is reported in the settings page and the panel log. */
  const val DEFAULT_HTTP_API_PORT = 0
  const val MIN_HTTP_API_PORT = 0
  const val MAX_HTTP_API_PORT = 65535
  /** A run started over HTTP cannot wait forever: the caller gets a failure with a reason instead. */
  const val DEFAULT_HTTP_API_WAIT_TIMEOUT_SEC = 600

  // --- ACP handshake (a cold npx-launched agent can be slow to answer initialize) ---
  const val DEFAULT_HANDSHAKE_TIMEOUT_SEC = 60
  const val MIN_HANDSHAKE_TIMEOUT_SEC = 10
  const val MAX_HANDSHAKE_TIMEOUT_SEC = 600

  private const val KEY_HOOKS_ENABLED = "vibe.agent.hooks.enabled"
  private const val KEY_AUDIT_ENABLED = "vibe.agent.audit.enabled"
  private const val KEY_AUDIT_ROTATION_MB = "vibe.agent.audit.rotationMB"
  private const val KEY_VERIFY_MODE = "vibe.agent.verifyGate.mode"
  private const val KEY_VERIFY_COMMAND = "vibe.agent.verifyGate.command"
  private const val KEY_VERIFY_MAX_ATTEMPTS = "vibe.agent.verifyGate.maxAttempts"
  private const val KEY_VERIFY_TIMEOUT_MS = "vibe.agent.verifyGate.timeoutMs"
  private const val KEY_CHECKS_MODE = "vibe.agent.turnChecks.mode"
  private const val KEY_CHECKS_MAX_ATTEMPTS = "vibe.agent.turnChecks.maxAttempts"
  private const val KEY_CHECKS_MAX_FILES = "vibe.agent.turnChecks.maxFiles"
  private const val KEY_CHECKS_MAX_FILE_KB = "vibe.agent.turnChecks.maxFileKb"
  private const val KEY_TERMINAL_ENABLED = "vibe.agent.terminal.enabled"
  private const val KEY_HANDSHAKE_TIMEOUT_SEC = "vibe.agent.handshakeTimeoutSec"
  private const val KEY_WATCH_SCENE_THRESHOLD = "vibe.agent.watch.sceneThreshold"
  private const val KEY_WATCH_MAX_FRAMES = "vibe.agent.watch.maxFrames"
  private const val KEY_WATCH_FRAME_HEIGHT = "vibe.agent.watch.frameHeight"
  private const val KEY_SILENCE_MINUTES = "vibe.agent.silenceMinutes"
  private const val KEY_ROLE_BUDGET = "vibe.agent.roleBudgetTokens"
  private const val KEY_COUNCIL = "vibe.agent.councilAdvisers"
  private const val KEY_CONTEXT_FILTER = "vibe.agent.contextFilterMode"
  private const val KEY_LLM_PROXY = "vibe.agent.llmProxyUrl"
  private const val KEY_DIGEST_TIME = "vibe.agent.digestTime"
  private const val KEY_DOCS_FOLDER = "vibe.agent.docsFolder"
  private const val KEY_FAILOVER = "vibe.agent.failoverChain"

  /** Off by default: a ceiling nobody set must not become a wall in the middle of real work. */
  const val DEFAULT_ROLE_BUDGET_TOKENS = 0
  const val MAX_ROLE_BUDGET_TOKENS = 100_000_000

  /** Minutes of total silence before the turn is called hung; 0 turns the switch off. */
  const val DEFAULT_SILENCE_MINUTES = 5
  const val MAX_SILENCE_MINUTES = 120
  private const val KEY_WATCH_SUB_LANGS = "vibe.agent.watch.subtitleLanguages"
  private const val KEY_DESIGN_MODE = "vibe.agent.design.mode"
  private const val KEY_DESIGN_MAX_ATTEMPTS = "vibe.agent.design.maxAttempts"
  private const val KEY_FIM_ENABLED = "vibe.agent.fim.enabled"
  private const val KEY_FIM_DEBOUNCE_MS = "vibe.agent.fim.debounceMs"
  private const val KEY_FIM_CACHE_SIZE = "vibe.agent.fim.cacheSize"
  private const val KEY_RUN_LEDGER_ENABLED = "vibe.agent.runs.enabled"
  private const val KEY_RUN_LEDGER_MAX = "vibe.agent.runs.maxRecords"
  private const val KEY_RUN_LEDGER_DAYS = "vibe.agent.runs.retentionDays"
  private const val KEY_MASK_SECRETS = "vibe.agent.context.maskSecrets"
  private const val KEY_WARN_FOREIGN = "vibe.agent.context.warnForeignProject"
  private const val KEY_KNOWN_PROJECTS = "vibe.agent.context.knownProjects"
  private const val KEY_HTTP_API_ENABLED = "vibe.agent.httpApi.enabled"
  private const val KEY_HTTP_API_PORT = "vibe.agent.httpApi.port"

  private val props get() = PropertiesComponent.getInstance()

  var hooksEnabled: Boolean
    get() = props.getBoolean(KEY_HOOKS_ENABLED, DEFAULT_HOOKS_ENABLED)
    set(value) = props.setValue(KEY_HOOKS_ENABLED, value, DEFAULT_HOOKS_ENABLED)

  var auditEnabled: Boolean
    get() = props.getBoolean(KEY_AUDIT_ENABLED, DEFAULT_AUDIT_ENABLED)
    set(value) = props.setValue(KEY_AUDIT_ENABLED, value, DEFAULT_AUDIT_ENABLED)

  var auditRotationMb: Int
    get() = props.getInt(KEY_AUDIT_ROTATION_MB, DEFAULT_AUDIT_ROTATION_MB).coerceIn(MIN_AUDIT_ROTATION_MB, MAX_AUDIT_ROTATION_MB)
    set(value) = props.setValue(KEY_AUDIT_ROTATION_MB, value.coerceIn(MIN_AUDIT_ROTATION_MB, MAX_AUDIT_ROTATION_MB), DEFAULT_AUDIT_ROTATION_MB)

  val auditRotationBytes: Long get() = auditRotationMb.toLong() * 1024L * 1024L

  var verifyMode: String
    get() = props.getValue(KEY_VERIFY_MODE, DEFAULT_VERIFY_MODE).let { if (it in VERIFY_MODES) it else DEFAULT_VERIFY_MODE }
    set(value) = props.setValue(KEY_VERIFY_MODE, if (value in VERIFY_MODES) value else DEFAULT_VERIFY_MODE, DEFAULT_VERIFY_MODE)

  var verifyCommand: String
    get() = props.getValue(KEY_VERIFY_COMMAND, DEFAULT_VERIFY_COMMAND)
    set(value) = props.setValue(KEY_VERIFY_COMMAND, value.trim(), DEFAULT_VERIFY_COMMAND)

  var verifyMaxAttempts: Int
    get() = props.getInt(KEY_VERIFY_MAX_ATTEMPTS, DEFAULT_VERIFY_MAX_ATTEMPTS).coerceIn(MIN_VERIFY_MAX_ATTEMPTS, MAX_VERIFY_MAX_ATTEMPTS)
    set(value) = props.setValue(KEY_VERIFY_MAX_ATTEMPTS, value.coerceIn(MIN_VERIFY_MAX_ATTEMPTS, MAX_VERIFY_MAX_ATTEMPTS), DEFAULT_VERIFY_MAX_ATTEMPTS)

  var verifyTimeoutMs: Int
    get() = props.getInt(KEY_VERIFY_TIMEOUT_MS, DEFAULT_VERIFY_TIMEOUT_MS).coerceIn(MIN_VERIFY_TIMEOUT_MS, MAX_VERIFY_TIMEOUT_MS)
    set(value) = props.setValue(KEY_VERIFY_TIMEOUT_MS, value.coerceIn(MIN_VERIFY_TIMEOUT_MS, MAX_VERIFY_TIMEOUT_MS), DEFAULT_VERIFY_TIMEOUT_MS)

  var checksMode: String
    get() = props.getValue(KEY_CHECKS_MODE, DEFAULT_CHECKS_MODE).let { if (it in CHECKS_MODES) it else DEFAULT_CHECKS_MODE }
    set(value) = props.setValue(KEY_CHECKS_MODE, if (value in CHECKS_MODES) value else DEFAULT_CHECKS_MODE, DEFAULT_CHECKS_MODE)

  var checksMaxAttempts: Int
    get() = props.getInt(KEY_CHECKS_MAX_ATTEMPTS, DEFAULT_CHECKS_MAX_ATTEMPTS).coerceIn(MIN_CHECKS_MAX_ATTEMPTS, MAX_CHECKS_MAX_ATTEMPTS)
    set(value) = props.setValue(KEY_CHECKS_MAX_ATTEMPTS, value.coerceIn(MIN_CHECKS_MAX_ATTEMPTS, MAX_CHECKS_MAX_ATTEMPTS), DEFAULT_CHECKS_MAX_ATTEMPTS)

  var checksMaxFiles: Int
    get() = props.getInt(KEY_CHECKS_MAX_FILES, DEFAULT_CHECKS_MAX_FILES).coerceIn(MIN_CHECKS_MAX_FILES, MAX_CHECKS_MAX_FILES)
    set(value) = props.setValue(KEY_CHECKS_MAX_FILES, value.coerceIn(MIN_CHECKS_MAX_FILES, MAX_CHECKS_MAX_FILES), DEFAULT_CHECKS_MAX_FILES)

  var checksMaxFileKb: Int
    get() = props.getInt(KEY_CHECKS_MAX_FILE_KB, DEFAULT_CHECKS_MAX_FILE_KB).coerceIn(MIN_CHECKS_MAX_FILE_KB, MAX_CHECKS_MAX_FILE_KB)
    set(value) = props.setValue(KEY_CHECKS_MAX_FILE_KB, value.coerceIn(MIN_CHECKS_MAX_FILE_KB, MAX_CHECKS_MAX_FILE_KB), DEFAULT_CHECKS_MAX_FILE_KB)

  val checksMaxFileBytes: Long get() = checksMaxFileKb.toLong() * 1024L

  var terminalEnabled: Boolean
    get() = props.getBoolean(KEY_TERMINAL_ENABLED, DEFAULT_TERMINAL_ENABLED)
    set(value) = props.setValue(KEY_TERMINAL_ENABLED, value, DEFAULT_TERMINAL_ENABLED)

  var handshakeTimeoutSec: Int
    get() = props.getInt(KEY_HANDSHAKE_TIMEOUT_SEC, DEFAULT_HANDSHAKE_TIMEOUT_SEC).coerceIn(MIN_HANDSHAKE_TIMEOUT_SEC, MAX_HANDSHAKE_TIMEOUT_SEC)
    set(value) = props.setValue(KEY_HANDSHAKE_TIMEOUT_SEC, value.coerceIn(MIN_HANDSHAKE_TIMEOUT_SEC, MAX_HANDSHAKE_TIMEOUT_SEC), DEFAULT_HANDSHAKE_TIMEOUT_SEC)

  var httpApiEnabled: Boolean
    get() = props.getBoolean(KEY_HTTP_API_ENABLED, DEFAULT_HTTP_API_ENABLED)
    set(value) = props.setValue(KEY_HTTP_API_ENABLED, value, DEFAULT_HTTP_API_ENABLED)

  var httpApiPort: Int
    get() = props.getInt(KEY_HTTP_API_PORT, DEFAULT_HTTP_API_PORT).coerceIn(MIN_HTTP_API_PORT, MAX_HTTP_API_PORT)
    set(value) = props.setValue(KEY_HTTP_API_PORT, value.coerceIn(MIN_HTTP_API_PORT, MAX_HTTP_API_PORT), DEFAULT_HTTP_API_PORT)

  var maskSecretsInContext: Boolean
    get() = props.getBoolean(KEY_MASK_SECRETS, DEFAULT_MASK_SECRETS_IN_CONTEXT)
    set(value) = props.setValue(KEY_MASK_SECRETS, value, DEFAULT_MASK_SECRETS_IN_CONTEXT)

  var warnForeignProject: Boolean
    get() = props.getBoolean(KEY_WARN_FOREIGN, DEFAULT_WARN_FOREIGN_PROJECT)
    set(value) = props.setValue(KEY_WARN_FOREIGN, value, DEFAULT_WARN_FOREIGN_PROJECT)

  /**
   * Projects the user has already worked in. Not a security boundary — a memory: the warning about
   * a repository seen for the first time must appear once, not on every launch.
   */
  var knownProjects: Set<String>
    get() = props.getValue(KEY_KNOWN_PROJECTS).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
    set(value) = props.setValue(KEY_KNOWN_PROJECTS, value.joinToString("\n"))

  var runLedgerEnabled: Boolean
    get() = props.getBoolean(KEY_RUN_LEDGER_ENABLED, DEFAULT_RUN_LEDGER_ENABLED)
    set(value) = props.setValue(KEY_RUN_LEDGER_ENABLED, value, DEFAULT_RUN_LEDGER_ENABLED)

  var runLedgerMaxRecords: Int
    get() = props.getInt(KEY_RUN_LEDGER_MAX, DEFAULT_RUN_LEDGER_MAX_RECORDS)
      .coerceIn(MIN_RUN_LEDGER_MAX_RECORDS, MAX_RUN_LEDGER_MAX_RECORDS)
    set(value) = props.setValue(KEY_RUN_LEDGER_MAX, value.coerceIn(MIN_RUN_LEDGER_MAX_RECORDS, MAX_RUN_LEDGER_MAX_RECORDS), DEFAULT_RUN_LEDGER_MAX_RECORDS)

  var runLedgerRetentionDays: Int
    get() = props.getInt(KEY_RUN_LEDGER_DAYS, DEFAULT_RUN_LEDGER_RETENTION_DAYS)
      .coerceIn(MIN_RUN_LEDGER_RETENTION_DAYS, MAX_RUN_LEDGER_RETENTION_DAYS)
    set(value) = props.setValue(KEY_RUN_LEDGER_DAYS, value.coerceIn(MIN_RUN_LEDGER_RETENTION_DAYS, MAX_RUN_LEDGER_RETENTION_DAYS), DEFAULT_RUN_LEDGER_RETENTION_DAYS)

  var fimEnabled: Boolean
    get() = props.getBoolean(KEY_FIM_ENABLED, DEFAULT_FIM_ENABLED)
    set(value) = props.setValue(KEY_FIM_ENABLED, value, DEFAULT_FIM_ENABLED)

  var fimDebounceMs: Int
    get() = props.getInt(KEY_FIM_DEBOUNCE_MS, DEFAULT_FIM_DEBOUNCE_MS).coerceIn(MIN_FIM_DEBOUNCE_MS, MAX_FIM_DEBOUNCE_MS)
    set(value) = props.setValue(KEY_FIM_DEBOUNCE_MS, value.coerceIn(MIN_FIM_DEBOUNCE_MS, MAX_FIM_DEBOUNCE_MS), DEFAULT_FIM_DEBOUNCE_MS)

  var fimCacheSize: Int
    get() = props.getInt(KEY_FIM_CACHE_SIZE, DEFAULT_FIM_CACHE_SIZE).coerceIn(MIN_FIM_CACHE_SIZE, MAX_FIM_CACHE_SIZE)
    set(value) = props.setValue(KEY_FIM_CACHE_SIZE, value.coerceIn(MIN_FIM_CACHE_SIZE, MAX_FIM_CACHE_SIZE), DEFAULT_FIM_CACHE_SIZE)

  var designMode: String
    get() = props.getValue(KEY_DESIGN_MODE, DEFAULT_DESIGN_MODE).let { if (it in DESIGN_MODES) it else DEFAULT_DESIGN_MODE }
    set(value) = props.setValue(KEY_DESIGN_MODE, if (value in DESIGN_MODES) value else DEFAULT_DESIGN_MODE, DEFAULT_DESIGN_MODE)

  var designMaxAttempts: Int
    get() = props.getInt(KEY_DESIGN_MAX_ATTEMPTS, DEFAULT_DESIGN_MAX_ATTEMPTS).coerceIn(MIN_DESIGN_MAX_ATTEMPTS, MAX_DESIGN_MAX_ATTEMPTS)
    set(value) = props.setValue(KEY_DESIGN_MAX_ATTEMPTS, value.coerceIn(MIN_DESIGN_MAX_ATTEMPTS, MAX_DESIGN_MAX_ATTEMPTS), DEFAULT_DESIGN_MAX_ATTEMPTS)

  var watchSceneThreshold: Double
    get() = props.getValue(KEY_WATCH_SCENE_THRESHOLD)?.toDoubleOrNull()?.coerceIn(0.05, 0.9) ?: DEFAULT_WATCH_SCENE_THRESHOLD
    set(value) = props.setValue(KEY_WATCH_SCENE_THRESHOLD, value.coerceIn(0.05, 0.9).toString())

  /**
   * How long a turn may show no sign of life at all before it is ended.
   *
   * Doubled internally: at the limit the chat says so, at twice the limit it stops. Saying and
   * stopping at the same moment would remove the one chance to say «подожди, оно думает».
   */
  /** Tokens one pipeline role may spend per rolling day; 0 turns the budget off. */
  /** `провайдер/модель` через запятую; пусто — совет выключен и честно об этом говорит. */
  /** auto | raw | aggregate | off — как чистится вывод инструментов до попадания в контекст. */
  /** Прокси только для запросов к моделям; пусто — прямое соединение. */
  /** `ЧЧ:ММ` по местному времени; пусто — сводка выключена. */
  /** Папка документации проекта для панели «Документы». */
  var docsFolder: String
    get() = props.getValue(KEY_DOCS_FOLDER, "docs").ifBlank { "docs" }
    set(value) = props.setValue(KEY_DOCS_FOLDER, value.trim(), "docs")

  var digestTime: String
    get() = props.getValue(KEY_DIGEST_TIME, "")
    set(value) = props.setValue(KEY_DIGEST_TIME, value.trim(), "")

  var llmProxyUrl: String
    get() = props.getValue(KEY_LLM_PROXY, "")
    set(value) = props.setValue(KEY_LLM_PROXY, value.trim(), "")

  /** `провайдер/модель` через запятую: куда уходить, когда выбранная цель не отвечает. */
  var failoverChain: String
    get() = props.getValue(KEY_FAILOVER, "")
    set(value) = props.setValue(KEY_FAILOVER, value.trim(), "")

  var contextFilterMode: String
    get() = props.getValue(KEY_CONTEXT_FILTER, "auto")
    set(value) = props.setValue(KEY_CONTEXT_FILTER, value.trim().lowercase(), "auto")

  var councilAdvisers: String
    get() = props.getValue(KEY_COUNCIL, "")
    set(value) = props.setValue(KEY_COUNCIL, value.trim(), "")

  var roleBudgetTokens: Int
    get() = props.getInt(KEY_ROLE_BUDGET, DEFAULT_ROLE_BUDGET_TOKENS).coerceIn(0, MAX_ROLE_BUDGET_TOKENS)
    set(value) = props.setValue(KEY_ROLE_BUDGET, value.coerceIn(0, MAX_ROLE_BUDGET_TOKENS), DEFAULT_ROLE_BUDGET_TOKENS)

  var agentSilenceMinutes: Int
    get() = props.getInt(KEY_SILENCE_MINUTES, DEFAULT_SILENCE_MINUTES).coerceIn(0, MAX_SILENCE_MINUTES)
    set(value) = props.setValue(KEY_SILENCE_MINUTES, value.coerceIn(0, MAX_SILENCE_MINUTES), DEFAULT_SILENCE_MINUTES)

  var watchMaxFrames: Int
    get() = props.getInt(KEY_WATCH_MAX_FRAMES, DEFAULT_WATCH_MAX_FRAMES).coerceIn(MIN_WATCH_MAX_FRAMES, MAX_WATCH_MAX_FRAMES)
    set(value) = props.setValue(KEY_WATCH_MAX_FRAMES, value.coerceIn(MIN_WATCH_MAX_FRAMES, MAX_WATCH_MAX_FRAMES), DEFAULT_WATCH_MAX_FRAMES)

  var watchFrameHeight: Int
    get() = props.getInt(KEY_WATCH_FRAME_HEIGHT, DEFAULT_WATCH_FRAME_HEIGHT).coerceIn(MIN_WATCH_FRAME_HEIGHT, MAX_WATCH_FRAME_HEIGHT)
    set(value) = props.setValue(KEY_WATCH_FRAME_HEIGHT, value.coerceIn(MIN_WATCH_FRAME_HEIGHT, MAX_WATCH_FRAME_HEIGHT), DEFAULT_WATCH_FRAME_HEIGHT)

  var watchSubtitleLanguages: String
    get() = props.getValue(KEY_WATCH_SUB_LANGS, DEFAULT_WATCH_SUBTITLE_LANGUAGES)
    set(value) = props.setValue(KEY_WATCH_SUB_LANGS, value.trim().ifEmpty { DEFAULT_WATCH_SUBTITLE_LANGUAGES }, DEFAULT_WATCH_SUBTITLE_LANGUAGES)
}
