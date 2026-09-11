// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Каталог ACP-агентов» — что есть у протокола и чего нет у вас.
 *
 * Ходит в сеть по явному нажатию и ничего не устанавливает: показывает экран выбора и, если человек
 * отметил агентов, дописывает их в `~/.jetbrains/acp.json`. Команда видна ДО того, как её выполнят,
 * — запуск чужого процесса решает человек, а не мы за него. Until 11.09.2026 this KDoc promised the
 * write while the code only showed a message.
 */
class VibeAgentRegistryAction : AnAction({ t("registry.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    ApplicationManager.getApplication().executeOnPooledThread {
      val cached = AgentRegistryCache.load()
      val (text, note) = when (val outcome = fetch(cached)) {
        is AgentRegistryCache.Outcome.Fresh -> {
          AgentRegistryCache.save(AgentRegistryCache.Cached(outcome.body, outcome.etag, System.currentTimeMillis()))
          outcome.body to null
        }
        is AgentRegistryCache.Outcome.NotModified -> {
          // The kept catalog is confirmed current: its date is now, not the day it was downloaded.
          AgentRegistryCache.save(outcome.cached.copy(fetchedAtMs = System.currentTimeMillis()))
          outcome.cached.body to null
        }
        is AgentRegistryCache.Outcome.Offline ->
          outcome.cached.body to t("registry.fromCache", "reason" to outcome.reason, "date" to date(outcome.cached.fetchedAtMs))
        is AgentRegistryCache.Outcome.Failed -> {
          ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, t("registry.failed", "reason" to outcome.reason), t("registry.action"))
          }
          return@executeOnPooledThread
        }
      }
      val catalog = AgentRegistry.parse(text)
      val configured = AcpConfig.load(project?.basePath)
      ApplicationManager.getApplication().invokeLater { show(project, catalog, configured, note) }
    }
  }

  /** One request, conditional when a catalog is kept: the answer is then a 304 instead of the whole file. */
  private fun fetch(cached: AgentRegistryCache.Cached?): AgentRegistryCache.Outcome {
    return try {
      val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
      val request = HttpRequest.newBuilder(URI.create(AgentRegistry.URL)).timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET()
      cached?.etag?.let { request.header("If-None-Match", it) }
      val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
      AgentRegistryCache.decide(response.statusCode(), response.body(), response.headers().firstValue("ETag").orElse(null), null, cached)
    }
    catch (e: IOException) {
      AgentRegistryCache.decide(null, null, null, e.message ?: e.javaClass.simpleName, cached)
    }
    catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      AgentRegistryCache.decide(null, null, null, e.javaClass.simpleName, cached)
    }
  }

  private fun show(project: Project?, catalog: List<AgentRegistry.Entry>, configured: List<AgentServerConfig>, note: String?) {
    val fresh = AgentRegistry.newAgents(catalog, configured)
    if (fresh.isEmpty()) {
      Messages.showInfoMessage(project, t("registry.nothingNew", "total" to catalog.size) + (note?.let { "\n\n" + it } ?: ""),
                               t("registry.action"))
      return
    }
    // Лицензия здесь не формальность — реестр под Apache-2.0, а у каждого агента лицензия своя.
    val addable = fresh.filter { AgentRegistry.toAgentEntry(it) != null }
    val notAddable = fresh.filter { AgentRegistry.toAgentEntry(it) == null }.map { entry ->
      "• ${entry.name} ${entry.version ?: ""} · ${entry.license ?: "—"} · ${binaryHint(entry)}"
    } + catalog.filter { AgentRegistry.isConfigured(it, configured) }.map { t("registry.alreadyAdded", "name" to it.name) }
    val summary = t("registry.summary", "total" to catalog.size, "fresh" to fresh.size, "url" to AgentRegistry.URL) +
                  (note?.let { "\n" + it } ?: "")
    val dialog = AgentRegistryDialog(project, addable, notAddable, summary)
    if (!dialog.showAndGet()) return
    val chosen = dialog.checked().mapNotNull { AgentRegistry.toAgentEntry(it) }
    if (chosen.isNotEmpty()) write(project, chosen)
  }

  private fun write(project: Project?, agents: List<AgentServerConfig>) {
    val file = AcpConfig.configPath()
    try {
      val existing = if (Files.isRegularFile(file)) Files.readString(file) else null
      when (val result = AcpConfigWriter.add(existing, agents, AcpConfig.DEFAULT_AGENTS)) {
        is AcpConfigWriter.Result.Written -> {
          if (result.added.isEmpty()) {
            Messages.showInfoMessage(project, t("registry.nothingToWrite", "file" to file), t("registry.action"))
            return
          }
          Files.createDirectories(file.parent)
          Files.writeString(file, result.text)
          val defaults = result.defaults.takeIf { it.isNotEmpty() }
            ?.let { "\n\n" + t("registry.writtenDefaults", "names" to it.joinToString()) }.orEmpty()
          Messages.showInfoMessage(project, t("registry.written", "file" to file, "names" to result.added.joinToString()) + defaults,
                                   t("registry.action"))
        }
        is AcpConfigWriter.Result.Refused ->
          Messages.showInfoMessage(project, t("registry.refused", "file" to file, "snippet" to result.snippet), t("registry.action"))
      }
    }
    catch (e: IOException) {
      Messages.showErrorDialog(project, t("registry.writeFailed", "file" to file, "reason" to (e.message ?: e.javaClass.simpleName)),
                               t("registry.action"))
    }
  }

  /**
   * Что сказать про агента, который поставляется готовым бинарём.
   *
   * Реестр знает архив под ЭТУ машину, команду запуска с аргументами и окружением и — если автор его
   * объявил — sha256. Суммы нет — так и сказано: «проверить нечем», а не прочерк. Скачивание и запуск
   * остаются за человеком — по решению о том, что чужой процесс на его машине заводит он, а не мы.
   */
  private fun binaryHint(entry: AgentRegistry.Entry): String {
    val binary = entry.binary ?: return t("registry.manualInstall")
    val launch = binary.env.entries.joinToString("") { "${it.key}=${it.value} " } +
                 (listOfNotNull(binary.cmd) + binary.args).joinToString(" ").ifEmpty { "—" }
    return t(
      "registry.binaryInstall",
      "archive" to binary.archive,
      "checksum" to (binary.sha256?.let { t("registry.checksum", "sha256" to it) } ?: t("registry.noChecksum")),
      "cmd" to launch,
    )
  }

  private fun date(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(DATE_FORMAT))

  private companion object {
    const val TIMEOUT_SECONDS = 20L
    const val DATE_FORMAT = "dd.MM.yyyy HH:mm"
  }
}
