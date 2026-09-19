// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * Svelte: компонент целиком — разметка, скрипт, стиль и реактивные объявления.
 *
 * Свой TypeScript сервер находит сам, `typescript.tsdk` ему передавать не нужно — проверено
 * прямым запросом `initialize` 18.09.2026. Плагина к tsserver у Svelte нет: импорт `.svelte` в
 * обычном `.ts` разрешается его собственным `svelte2tsx` внутри самого сервера.
 */
class SvelteServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    SvelteConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class SvelteConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.svelteCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
