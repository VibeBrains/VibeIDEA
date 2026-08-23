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
  private const val KEY_CONTINUE_TEXT = "vibe.chat.continueText"
  private const val KEY_TARGET = "vibe.chat.target"
  private const val KEY_MODEL = "vibe.chat.model"

  var continueText: String
    get() = PropertiesComponent.getInstance().getValue(KEY_CONTINUE_TEXT, DEFAULT_CONTINUE_TEXT).ifBlank { DEFAULT_CONTINUE_TEXT }
    set(value) = PropertiesComponent.getInstance().setValue(KEY_CONTINUE_TEXT, value.trim(), DEFAULT_CONTINUE_TEXT)

  /** Target id: `acp:<agent name>` or `llm:<provider id>`; model id only for LLM targets. */
  fun rememberChoice(project: Project, targetId: String, modelId: String?) {
    val props = PropertiesComponent.getInstance(project)
    props.setValue(KEY_TARGET, targetId)
    props.setValue(KEY_MODEL, modelId)
  }

  fun rememberedTarget(project: Project): String? = PropertiesComponent.getInstance(project).getValue(KEY_TARGET)
  fun rememberedModel(project: Project): String? = PropertiesComponent.getInstance(project).getValue(KEY_MODEL)
}
