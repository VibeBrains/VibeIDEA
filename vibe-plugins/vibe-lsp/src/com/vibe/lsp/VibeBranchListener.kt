// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Смена ветки — повод пересобрать модель языковых серверов, и человек это видит.
 *
 * Почему это нужно: `git checkout` меняет файлы массово, а языковой сервер держит собственную
 * модель проекта, собранную при старте. Без перезапуска он отвечает по прежней ветке — подсказки
 * из другого кода, ошибки на исчезнувших строках, переход в файл, которого больше нет. Своего кода
 * на этот счёт у нас не было вовсе; LSP4IJ шлёт серверу события файлов, но только по тем шаблонам,
 * которые сервер у себя зарегистрировал, — а внутренний индекс это не пересобирает.
 *
 * Почему с прогрессом и сам, без вопроса: так это делает PhpStorm, и так попросил владелец
 * (21.09.2026). Работа идёт в фоне, её видно в «Background Tasks», её можно отменить — а молча
 * съеденные полминуты выглядели бы как «IDE задумалась».
 */
internal class VibeBranchListener(private val project: Project) : BranchChangeListener {
  override fun branchWillChange(branchName: String) = Unit

  override fun branchHasChanged(branchName: String) {
    val manager = LanguageServerManager.getInstance(project)
    val running = BranchSwitchRefresh.SERVER_IDS
      .filter { runCatching { manager.getServerStatus(it) == ServerStatus.started }.getOrDefault(false) }
      .toSet()
    val targets = BranchSwitchRefresh.toRestart(running)
    // Ни один сервер не работает — перезапускать нечего: свою модель они соберут при первом
    // открытии файла, уже по новой ветке.
    if (targets.isEmpty()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(
      project, t("lsp.branch.refresh", "branch" to branchName), true,
    ) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = false
        targets.forEachIndexed { index, id ->
          if (indicator.isCanceled) return
          // Имя сервера в строке прогресса: «Updating indexes» без имени не отвечает на вопрос
          // «почему так долго», а с именем отвечает.
          indicator.text = t("lsp.branch.server", "server" to id)
          indicator.fraction = index.toDouble() / targets.size
          runCatching {
            manager.stop(id)
            manager.start(id)
          }
        }
      }
    })
  }
}
