// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * SCSS и Sass: переход к переменной, миксину и функции ЧЕРЕЗ файлы.
 *
 * Общий CSS-сервер знает синтаксис, но живёт в одном файле: `@use "variables"` для него — просто
 * строка, и переход по `$brand-color` никуда не ведёт. В реальном проекте на Sass именно эти
 * переходы и нужны, поэтому `.scss` и `.sass` отданы сюда целиком.
 *
 * `.sass` с отступами до сих пор не обслуживал никто: в открытой платформе его нет вовсе.
 */
class SomeSassServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    SomeSassConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class SomeSassConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.someSassCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
