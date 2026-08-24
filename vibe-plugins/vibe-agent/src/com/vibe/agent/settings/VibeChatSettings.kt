// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * User-significant chat knobs with defaults (no hard-coded behaviour in the panel).
 * Application-level: the «продолжи» text; project-level: the last chosen target/model,
 * so the composer reopens on what the user picked last time (VibeIDE remembers per tab —
 * tabs arrive with the chat-history wave; until then one remembered choice per project).
 */
object VibeChatSettings {
  const val DEFAULT_CONTINUE_TEXT = "продолжи"
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

  var continueText: String
    get() = PropertiesComponent.getInstance().getValue(KEY_CONTINUE_TEXT, DEFAULT_CONTINUE_TEXT).ifBlank { DEFAULT_CONTINUE_TEXT }
    set(value) = PropertiesComponent.getInstance().setValue(KEY_CONTINUE_TEXT, value.trim(), DEFAULT_CONTINUE_TEXT)

  /** Open chat tabs at once; the oldest inactive tab is silently evicted beyond this. */
  var maxOpenTabs: Int
    get() = PropertiesComponent.getInstance().getInt(KEY_MAX_OPEN_TABS, DEFAULT_MAX_OPEN_TABS).coerceIn(MIN_OPEN_TABS, MAX_OPEN_TABS)
    set(value) = PropertiesComponent.getInstance().setValue(KEY_MAX_OPEN_TABS, value.coerceIn(MIN_OPEN_TABS, MAX_OPEN_TABS), DEFAULT_MAX_OPEN_TABS)

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
}
