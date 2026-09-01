// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * User-significant chat knobs with defaults (no hard-coded behaviour in the panel).
 * Application-level: the «продолжи» text; project-level: the last chosen target/model,
 * so the composer reopens on what the user picked last time (VibeIDE remembers per tab —
 * tabs arrive with the chat-history wave; until then one remembered choice per project).
 */
object VibeChatSettings {
  val DEFAULT_CONTINUE_TEXT: String get() = t("chat.continueDefault")
  const val DEFAULT_MAX_OPEN_TABS = 5
  const val MIN_OPEN_TABS = 1
  const val MAX_OPEN_TABS = 20
  const val DEFAULT_MAX_MESSAGES_PER_THREAD = 500
  const val MIN_MESSAGES_PER_THREAD = 100
  const val MAX_MESSAGES_PER_THREAD = 5000
  private const val KEY_CONTINUE_TEXT = "vibe.chat.continueText"
  private const val KEY_TARGET = "vibe.chat.target"
  private const val KEY_MODEL = "vibe.chat.model"
  private const val KEY_MAX_OPEN_TABS = "vibe.chat.maxOpenTabs"
  private const val KEY_MAX_MESSAGES = "vibe.chat.maxMessagesPerThread"
  private const val KEY_SESSION_TOKENS = "vibe.chat.sessionTokenLimit"
  private const val KEY_SPEND_5H = "vibe.chat.spendLimitCents5h"
  private const val KEY_SPEND_WEEK = "vibe.chat.spendLimitCentsWeek"
  private const val KEY_SPEND_MONTH = "vibe.chat.spendLimitCentsMonth"

  /** Off by default: a ceiling nobody asked for turns into a wall in the middle of real work. */
  const val DEFAULT_SESSION_TOKEN_LIMIT = 0

  var continueText: String
    get() = PropertiesComponent.getInstance().getValue(KEY_CONTINUE_TEXT, DEFAULT_CONTINUE_TEXT).ifBlank { DEFAULT_CONTINUE_TEXT }
    set(value) = PropertiesComponent.getInstance().setValue(KEY_CONTINUE_TEXT, value.trim(), DEFAULT_CONTINUE_TEXT)

  /** Open chat tabs at once; the oldest inactive tab is silently evicted beyond this. */
  var maxOpenTabs: Int
    get() = PropertiesComponent.getInstance().getInt(KEY_MAX_OPEN_TABS, DEFAULT_MAX_OPEN_TABS).coerceIn(MIN_OPEN_TABS, MAX_OPEN_TABS)
    set(value) = PropertiesComponent.getInstance().setValue(KEY_MAX_OPEN_TABS, value.coerceIn(MIN_OPEN_TABS, MAX_OPEN_TABS), DEFAULT_MAX_OPEN_TABS)

  /**
   * Token ceiling for one chat, counted across the whole conversation; 0 turns it off.
   *
   * Separate from the window: the window is a per-request capacity, this is money over time.
   */
  var sessionTokenLimit: Long
    get() = PropertiesComponent.getInstance().getInt(KEY_SESSION_TOKENS, DEFAULT_SESSION_TOKEN_LIMIT).toLong().coerceAtLeast(0)
    set(value) = PropertiesComponent.getInstance().setValue(KEY_SESSION_TOKENS, value.coerceAtLeast(0).toInt(), DEFAULT_SESSION_TOKEN_LIMIT)

  /**
   * Money ceilings for the three rolling windows subscriptions are actually sold in; 0 turns one
   * off. Stored as cents so the setting survives as an Int, and read back as dollars.
   *
   * Off by default for the same reason as the token ceiling: a limit nobody asked for becomes a
   * wall in the middle of real work.
   */
  var spendLimitFiveHours: Double
    get() = readMoney(KEY_SPEND_5H)
    set(value) = writeMoney(KEY_SPEND_5H, value)

  var spendLimitWeek: Double
    get() = readMoney(KEY_SPEND_WEEK)
    set(value) = writeMoney(KEY_SPEND_WEEK, value)

  var spendLimitMonth: Double
    get() = readMoney(KEY_SPEND_MONTH)
    set(value) = writeMoney(KEY_SPEND_MONTH, value)

  /** The three ceilings as the pure checker wants them. */
  fun spendLimits(): com.vibe.agent.budget.SpendCeiling.Limits =
    com.vibe.agent.budget.SpendCeiling.Limits(spendLimitFiveHours, spendLimitWeek, spendLimitMonth)

  private fun readMoney(key: String): Double =
    PropertiesComponent.getInstance().getInt(key, 0).coerceAtLeast(0) / 100.0

  private fun writeMoney(key: String, value: Double) =
    PropertiesComponent.getInstance().setValue(key, Math.round(value.coerceAtLeast(0.0) * 100).toInt(), 0)

  /** Message cap per thread; on overflow the oldest are trimmed with a marker row. */
  var maxMessagesPerThread: Int
    get() = PropertiesComponent.getInstance().getInt(KEY_MAX_MESSAGES, DEFAULT_MAX_MESSAGES_PER_THREAD).coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD)
    set(value) = PropertiesComponent.getInstance().setValue(KEY_MAX_MESSAGES, value.coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD), DEFAULT_MAX_MESSAGES_PER_THREAD)

  /** Target id: `acp:<agent name>` or `llm:<provider id>`; model id only for LLM targets. */
  fun rememberChoice(project: Project, targetId: String, modelId: String?) {
    val props = PropertiesComponent.getInstance(project)
    props.setValue(KEY_TARGET, targetId)
    props.setValue(KEY_MODEL, modelId)
  }

  fun rememberedTarget(project: Project): String? = PropertiesComponent.getInstance(project).getValue(KEY_TARGET)
  fun rememberedModel(project: Project): String? = PropertiesComponent.getInstance(project).getValue(KEY_MODEL)

  /** Local shorthand — the file already reaches PropertiesComponent directly everywhere above. */
  private val props get() = PropertiesComponent.getInstance()

  // --- notification sound ---
  /** On by default: the whole point is to free the person from watching a running turn. */
  const val DEFAULT_SOUND_ENABLED = true
  const val DEFAULT_SOUND_VOLUME = 60
  private const val KEY_SOUND_ENABLED = "vibe.chat.sound.enabled"
  private const val KEY_SOUND_FINISHED = "vibe.chat.sound.onFinished"
  private const val KEY_SOUND_STOPPED = "vibe.chat.sound.onStopped"
  private const val KEY_SOUND_PERMISSION = "vibe.chat.sound.onPermission"
  private const val KEY_SOUND_MUTE_FOCUSED = "vibe.chat.sound.muteWhenFocused"
  private const val KEY_SOUND_VOLUME = "vibe.chat.sound.volume"
  private const val KEY_SOUND_PATH = "vibe.chat.sound.customPath"

  var soundEnabled: Boolean
    get() = props.getBoolean(KEY_SOUND_ENABLED, DEFAULT_SOUND_ENABLED)
    set(value) = props.setValue(KEY_SOUND_ENABLED, value, DEFAULT_SOUND_ENABLED)

  var soundOnTurnFinished: Boolean
    get() = props.getBoolean(KEY_SOUND_FINISHED, true)
    set(value) = props.setValue(KEY_SOUND_FINISHED, value, true)

  var soundOnTurnStopped: Boolean
    get() = props.getBoolean(KEY_SOUND_STOPPED, true)
    set(value) = props.setValue(KEY_SOUND_STOPPED, value, true)

  var soundOnAwaitingPermission: Boolean
    get() = props.getBoolean(KEY_SOUND_PERMISSION, true)
    set(value) = props.setValue(KEY_SOUND_PERMISSION, value, true)

  var soundMuteWhenFocused: Boolean
    get() = props.getBoolean(KEY_SOUND_MUTE_FOCUSED, true)
    set(value) = props.setValue(KEY_SOUND_MUTE_FOCUSED, value, true)

  var soundVolume: Int
    get() = props.getInt(KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME).coerceIn(0, 100)
    set(value) = props.setValue(KEY_SOUND_VOLUME, value.coerceIn(0, 100), DEFAULT_SOUND_VOLUME)

  var soundCustomPath: String
    get() = props.getValue(KEY_SOUND_PATH, "")
    set(value) = props.setValue(KEY_SOUND_PATH, value.trim(), "")

  // --- code block folding ---
  /** Lines beyond which a listing is folded; 0 disables folding entirely. */
  const val DEFAULT_CODE_FOLD_LINES = 24
  const val MAX_CODE_FOLD_LINES = 400
  private const val KEY_CODE_FOLD_LINES = "vibe.chat.codeFoldLines"

  var codeFoldLines: Int
    get() = props.getInt(KEY_CODE_FOLD_LINES, DEFAULT_CODE_FOLD_LINES).coerceIn(0, MAX_CODE_FOLD_LINES)
    set(value) = props.setValue(KEY_CODE_FOLD_LINES, value.coerceIn(0, MAX_CODE_FOLD_LINES), DEFAULT_CODE_FOLD_LINES)
}
