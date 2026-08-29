// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Shows how the autocomplete is behaving. Until this existed nothing could be said about it at all:
 * whether it answers in time, how often the cache saves a request, how often the model is asked for
 * something it cannot help with.
 */
class VibeFimMetricsAction : AnAction("VibeIDEA: метрики автодополнения") {
  override fun actionPerformed(e: AnActionEvent) {
    val text = VibeFimProvider.metrics.snapshot(VibeFimProvider.cache)
    val reset = Messages.showYesNoDialog(
      e.project,
      text + "\n\nЗамеры считаются с запуска IDE. Задержка — время ответа модели, без дебаунса.",
      "Автодополнение (FIM)", "Сбросить счётчики", "Закрыть", null,
    ) == Messages.YES
    if (reset) VibeFimProvider.metrics.reset()
  }
}
