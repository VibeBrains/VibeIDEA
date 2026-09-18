// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.vibe.agent.mcp.VibeIdeFacts

/**
 * Языковые серверы — в ответ инструмента «что умеет эта IDE».
 *
 * Без этого агент про них ничего не знал и советовал ставить расширения из маркетплейса чужой IDE:
 * в VibeIDEA расширений нет, языки подключены серверами, и совет был не просто бесполезен, а уводил
 * человека в сторону (поймано владельцем 18.09.2026 на вопросе про Angular).
 *
 * Состояние спрашивается у того же [LspDoctor], который рисует страницу настроек: два ответа об
 * одном сервере — «установлен» на странице и «нет» агенту — разошлись бы в первый же день.
 */
class LspIdeFacts : VibeIdeFacts {
  override fun facts(project: Project): List<String> {
    val checks = LspDoctor.check(LspDoctor.active(PhpServerChoice.effective()))
    val lines = ArrayList<String>()
    lines += "Языки подключены языковыми серверами (LSP), а не плагинами-расширениями: " +
             "маркетплейса расширений в этой IDE нет."
    checks.forEach { check ->
      val state = when {
        !check.installed -> "не установлен, поставить: " + LspDoctor.installCommandFor(check.spec)
        check.source == LspDoctor.Source.BUNDLED -> "наш, в поставке IDE"
        else -> "свой: " + check.path.orEmpty()
      }
      lines += "  " + check.spec.displayName + " (" + check.spec.extensions.joinToString() + ") — " + state
    }
    val node = NodeRuntime.path(project.basePath)
    lines += "Интерпретатор Node: " + (node ?: "не найден — серверы на ноде без него не запустятся")
    return lines
  }
}
