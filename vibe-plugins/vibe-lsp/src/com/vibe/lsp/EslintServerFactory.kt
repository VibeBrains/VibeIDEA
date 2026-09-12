// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.google.gson.JsonNull
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

/** Ответ на `workspace/configuration` — то, без чего сервер падает на первом же запросе. */
private class EslintLanguageClient(private val project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any = eslintSettings(workspaceFolderUri(project.basePath))
}

/**
 * URI корня проекта в том виде, в каком его ждёт сервер: без завершающего слэша.
 *
 * `Path.toUri()` дописывает слэш каталогу, а сервер отдаёт этот URI в `inferFilePath` и потом
 * склеивает результат с относительными путями — лишний разделитель там ни к чему.
 */
internal fun workspaceFolderUri(basePath: String?): String? {
  val path = basePath?.takeIf { it.isNotBlank() } ?: return null
  val uri = runCatching { Path.of(path).toUri().toString() }.getOrNull() ?: return null
  return uri.trimEnd('/')
}

/**
 * Настройки для `workspace/configuration`.
 *
 * Три значения здесь — не вкус, а лечение падения `textDocument/codeAction` (владелец, 12.09.2026,
 * Windows-сборка 0.5.0: «The "path" argument must be of type string. Received undefined» на каждом
 * открытии файла):
 *
 * 1. **`workspaceFolder` обязателен.** Сервер выводит из него `workspaceFolderPath`, и без него тот
 *    остаётся `undefined`.
 * 2. **`nodePath` = `null`, а не пустая строка.** Пустую строку сервер считает заданным значением и
 *    идёт склеивать её с `workspaceFolderPath` — на `undefined` это и падает. `null` означает
 *    «ищи сам», что нам и нужно.
 * 3. **`experimental` отправляется пустым объектом.** Мы принудительно выключали плоскую
 *    конфигурацию (`useFlatConfig: false`), а ESLint 9 по умолчанию только на ней и работает.
 *    Совсем убрать поле нельзя: сервер читает `settings.experimental.useFlatConfig` без проверки и
 *    падает на `undefined` — проверено стендом.
 *
 * Остальные значения намеренно консервативные: правила берутся из конфигурации проекта, автоправок
 * при сохранении нет, форматирование выключено. Линтер, самовольно правящий чужой файл, — не помощь.
 */
internal fun eslintSettings(workspaceFolderUri: String?): JsonObject {
  val eslint = JsonObject().apply {
    add("validate", JsonPrimitive("on"))
    add("packageManager", JsonPrimitive("npm"))
    add("useESLintClass", JsonPrimitive(false))
    // Объект обязателен, а поле внутри — нет: сервер разыменовывает `settings.experimental`
    // без проверки, но пустой объект оставляет выбор режима конфигурации за ним самим.
    add("experimental", JsonObject())
    add("format", JsonPrimitive(false))
    add("quiet", JsonPrimitive(false))
    add("onIgnoredFiles", JsonPrimitive("off"))
    add("run", JsonPrimitive("onType"))
    add("nodePath", JsonNull.INSTANCE)
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
    if (workspaceFolderUri != null) {
      add("workspaceFolder", JsonObject().apply {
        add("uri", JsonPrimitive(workspaceFolderUri))
        add("name", JsonPrimitive(workspaceFolderUri.substringAfterLast('/')))
      })
    }
  }
  return JsonObject().apply { add("eslint", eslint) }
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
