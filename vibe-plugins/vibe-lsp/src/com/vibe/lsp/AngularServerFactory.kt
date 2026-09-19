// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * Angular: шаблоны, компоненты и переход из тега в класс.
 *
 * Это ответ на вопрос, с которого началась вся история: почему `<custom-default-header>` в шаблоне
 * подчёркнут жёлтым и никуда не ведёт. Глубокой поддержки Angular в открытой платформе нет вовсе —
 * она живёт в закрытых плагинах платных IDE; официальный сервер Angular (MIT) даёт то же самое по
 * протоколу, и это единственный путь, не требующий чужой лицензии. Разбор — в базе знаний,
 * `knowledge/languages/angularSupport.md`.
 *
 * Работает РЯДОМ с vtsls на одних и тех же `.ts`, и это не ошибка: Angular-сервер отвечает про
 * шаблоны и Angular-сущности, TypeScript-сервер — про язык. Так же устроен VS Code.
 */
class AngularServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    AngularConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class AngularConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.angularCommand(workingDirectory), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
