// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

/**
 * The TypeScript server: the project's own TypeScript 7 in LSP mode when it has one, the bundled vtsls
 * otherwise — see [TsServerChoice]. The id stays `vtsls` for the registration: it names the language
 * slot, and renaming it would drop every user's per-server LSP4IJ settings.
 */
class VtslsServerFactory : LanguageServerFactory {
  override fun createConnectionProvider(project: Project): StreamConnectionProvider {
    val base = project.basePath?.let { java.nio.file.Path.of(it) }
    val windows = com.vibe.agent.util.ExecutableNames.isWindows()
    val engine = TsServerChoice.forProject(TsServerChoice.stored(), base, windows)
    return VtslsConnectionProvider(TsServerChoice.command(engine, base, windows) { ServerBinaries.vtslsCommand() }, project.basePath)
  }

  override fun createLanguageClient(project: Project): LanguageClientImpl = VtslsLanguageClient(project)
}

/** Отвечает на `workspace/configuration` — иначе плагины tsserver до него не доедут. */
private class VtslsLanguageClient(project: Project) : LanguageClientImpl(project) {
  override fun createSettings(): Any = vtslsSettings()
}

/**
 * Настройки vtsls: плагины tsserver для языков, которых TypeScript не знает сам.
 *
 * Vue и Astro работают в ГИБРИДНОМ режиме. Их собственные серверы держат разметку своего файла, а
 * вот что такое `import X from './X.vue'` в обычном `.ts` — знает только tsserver, и только если
 * ему подключить плагин языка. Без этого половина проекта на Vue (та, что написана на `.ts`) видит
 * красное подчёркивание на каждом импорте компонента, и починить это со стороны сервера Vue нельзя:
 * он тот файл вообще не открывает.
 *
 * `location` обязателен: без него tsserver ищет плагин от места, где лежит он сам, и в чужом
 * проекте не находит ничего. `enableForWorkspaceTypeScriptVersions` — потому что плагин по
 * умолчанию отключается, когда берётся TypeScript проекта, а именно он и берётся чаще всего.
 *
 * Плагин, которого нет на диске, в список не попадает: tsserver на несуществующий `location`
 * отвечает отказом запуска, то есть ломается ВЕСЬ TypeScript, а не один язык.
 */
internal fun vtslsSettings(
  vuePlugin: String? = ServerBinaries.bundledPluginRoot("@vue/typescript-plugin"),
  astroPlugin: String? = ServerBinaries.bundledPluginRoot("@astrojs/ts-plugin"),
): JsonObject {
  val plugins = JsonArray()
  vuePlugin?.let { plugins.add(tsserverPlugin("@vue/typescript-plugin", it, listOf("vue"))) }
  astroPlugin?.let { plugins.add(tsserverPlugin("@astrojs/ts-plugin", it, listOf("astro"))) }
  val tsserver = JsonObject().apply { add("globalPlugins", plugins) }
  return JsonObject().apply { add("vtsls", JsonObject().apply { add("tsserver", tsserver) }) }
}

private fun tsserverPlugin(name: String, location: String, languages: List<String>): JsonObject =
  JsonObject().apply {
    add("name", JsonPrimitive(name))
    add("location", JsonPrimitive(location))
    add("languages", JsonArray().apply { languages.forEach { add(JsonPrimitive(it)) } })
    add("enableForWorkspaceTypeScriptVersions", JsonPrimitive(true))
    add("configNamespace", JsonPrimitive("typescript"))
  }

/**
 * Named rather than anonymous on purpose: the JUnit vintage engine scans every class of the
 * module and cannot build a display name for an anonymous subclass, which fails test discovery
 * for the whole module before a single test runs.
 */
private class VtslsConnectionProvider(command: List<String>, workingDirectory: String?) :
  ProcessStreamConnectionProvider(command, workingDirectory) {
  init { NodeEnvironment.applyTo(this, workingDirectory) }
}
