// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * ESLint as a language server: the project's own rules shown in the editor as you type.
 *
 * Community has no ESLint integration at all, so there is nothing here to conflict with. What the
 * server DOES need is the project's own ESLint — it runs the config it finds, and a project without
 * one gets a server that starts and reports nothing. That is the honest behaviour: inventing rules
 * the project did not ask for would be worse than silence.
 *
 * Две вещи, найденные на живой 0.4.1 в проекте вообще без Node (владелец, 05.09.2026):
 *
 * 1. **Сервер не получал настроек.** vscode-eslint спрашивает их через `workspace/configuration`,
 *    и на пустой ответ падает: «Request textDocument/codeAction failed: Cannot read properties of
 *    null» — это уведомление приходило при каждом открытии файла. Настройки отдаёт
 *    [EslintLanguageClient].
 * 2. **Сервер поднимался там, где линтить нечем.** Проект без конфигурации ESLint не получает от
 *    него ничего, кроме процесса и ошибок, поэтому запуск теперь обусловлен [EslintConfig].
 */
class EslintServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider =
    EslintConnectionProvider(project.basePath)

  override fun createLanguageClient(project: Project): LanguageClientImpl = EslintLanguageClient(project)

  override fun createClientFeatures(): LSPClientFeatures = EslintClientFeatures()
}

private class EslintConnectionProvider(workingDirectory: String?) :
  ProcessStreamConnectionProvider(ServerBinaries.eslintCommand(), workingDirectory)

/**
 * Ответ на `workspace/configuration` — то, без чего сервер падает на первом же запросе.
 *
 * Значения намеренно консервативные: правила берутся из конфигурации проекта, автоправок при
 * сохранении нет, форматирование выключено. Линтер, самовольно правящий чужой файл, — не помощь.
 */
private class EslintLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any {
    val eslint = JsonObject().apply {
      add("validate", JsonPrimitive("on"))
      add("packageManager", JsonPrimitive("npm"))
      add("useESLintClass", JsonPrimitive(false))
      add("experimental", JsonObject().apply { add("useFlatConfig", JsonPrimitive(false)) })
      add("format", JsonPrimitive(false))
      add("quiet", JsonPrimitive(false))
      add("onIgnoredFiles", JsonPrimitive("off"))
      add("run", JsonPrimitive("onType"))
      add("nodePath", JsonPrimitive(""))
      add("options", JsonObject())
      add("problems", JsonObject().apply { add("shortenToSingleLine", JsonPrimitive(false)) })
      add("codeAction", JsonObject().apply {
        add("disableRuleComment", JsonObject().apply {
          add("enable", JsonPrimitive(true))
          add("location", JsonPrimitive("separateLine"))
        })
        add("showDocumentation", JsonObject().apply { add("enable", JsonPrimitive(true)) })
      })
      // Автоправки при сохранении выключены: правка чужого файла без спроса — не то, чего ждут
      // от подсветки ошибок.
      add("codeActionOnSave", JsonObject().apply {
        add("enable", JsonPrimitive(false))
        add("mode", JsonPrimitive("all"))
      })
      add("workingDirectory", JsonObject().apply { add("mode", JsonPrimitive("location")) })
    }
    return JsonObject().apply { add("eslint", eslint) }
  }
}

/** Сервер поднимается только там, где у проекта есть своя конфигурация ESLint. */
private class EslintClientFeatures : LSPClientFeatures() {
  override fun isEnabled(file: VirtualFile): Boolean {
    val base = project?.basePath ?: return false
    val root = runCatching { Path.of(base) }.getOrNull() ?: return false
    val names = runCatching {
      Files.list(root).use { stream -> stream.map { it.fileName.toString() }.toList() }
    }.getOrDefault(emptyList())
    val packageJson = runCatching { Files.readString(root.resolve("package.json")) }.getOrNull()
    return EslintConfig.exists(names, packageJson)
  }
}
