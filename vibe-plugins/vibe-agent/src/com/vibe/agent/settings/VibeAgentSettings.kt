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
  // --- terminal (client-executed terminal/… for non-Claude agents) ---
  const val DEFAULT_TERMINAL_ENABLED = true

  private const val KEY_HOOKS_ENABLED = "vibe.agent.hooks.enabled"
  private const val KEY_AUDIT_ENABLED = "vibe.agent.audit.enabled"
  private const val KEY_AUDIT_ROTATION_MB = "vibe.agent.audit.rotationMB"
  private const val KEY_VERIFY_MODE = "vibe.agent.verifyGate.mode"
  private const val KEY_VERIFY_COMMAND = "vibe.agent.verifyGate.command"
  private const val KEY_VERIFY_MAX_ATTEMPTS = "vibe.agent.verifyGate.maxAttempts"
  private const val KEY_VERIFY_TIMEOUT_MS = "vibe.agent.verifyGate.timeoutMs"
  private const val KEY_CHECKS_MODE = "vibe.agent.turnChecks.mode"
  private const val KEY_CHECKS_MAX_ATTEMPTS = "vibe.agent.turnChecks.maxAttempts"
  private const val KEY_TERMINAL_ENABLED = "vibe.agent.terminal.enabled"

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

  var terminalEnabled: Boolean
    get() = props.getBoolean(KEY_TERMINAL_ENABLED, DEFAULT_TERMINAL_ENABLED)
    set(value) = props.setValue(KEY_TERMINAL_ENABLED, value, DEFAULT_TERMINAL_ENABLED)
}
