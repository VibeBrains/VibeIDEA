// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.vibe.lsp.nav.PreciseNavigation

/**
 * Vue: разметка, скрипт и стиль одного `.vue` разом.
 *
 * В открытой платформе `.vue` не существует ни как тип файла, ни как язык — он открывается
 * «неизвестным файлом» без подсветки. Подсветку даёт наша грамматика TextMate
 * ([VibeTextMateBundles]), а смысл — этот сервер: переход из тега в компонент, типы пропсов,
 * директивы.
 *
 * Стартует ТОЛЬКО на `.vue`, поэтому условия включения ему не нужно: проект без Vue не платит
 * за него ничем.
 */
class VueServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    VueConnectionProvider(project.basePath)

  // Точная навигация заменяет переход LSP4IJ, и его надо выключить: пока их обработчик
  // отвечает «да» на весь файл, наша точность ничего не изменит. Ключ реестра выключен
  // по умолчанию, поэтому без него поведение прежнее.
  override fun createClientFeatures(): LSPClientFeatures = PreciseNavigation.features()
}

/** Named, not anonymous: the vintage engine cannot name an anonymous subclass and test discovery dies. */
private class VueConnectionProvider(private val workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.vueCommand(), workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }

  /**
   * Сервер стартует и без `tsdk`, но тогда выводит типы чужим экземпляром TypeScript, а не тем,
   * которым собирается проект. Разница видна там, где проект закрепил свою версию.
   */
  override fun getInitializationOptions(rootFile: VirtualFile?): Any? = tsdkOptions(workingDirectory)
}
