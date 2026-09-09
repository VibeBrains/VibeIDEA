// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * «Обновить каталог агентов» — что появилось у протокола с прошлого раза.
 *
 * Ходит в сеть по явному нажатию и ничего не устанавливает: показывает список и, если человек
 * согласился, дописывает выбранную запись в `~/.jetbrains/acp.json`. Команда видна ДО того, как
 * её выполнят, — запуск чужого процесса решает человек, а не мы за него.
 */
class VibeAgentRegistryAction : AnAction({ t("registry.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    ApplicationManager.getApplication().executeOnPooledThread {
      val text = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()
        val request = HttpRequest.newBuilder(URI.create(AgentRegistry.URL))
          .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) error("HTTP " + response.statusCode())
        response.body()
      }.getOrElse { failure ->
        ApplicationManager.getApplication().invokeLater {
          Messages.showErrorDialog(project, t("registry.failed", "reason" to (failure.message ?: "")), t("registry.action"))
        }
        return@executeOnPooledThread
      }
      val catalog = AgentRegistry.parse(text)
      val configured = AcpConfig.load(project?.basePath)
      val fresh = AgentRegistry.newAgents(catalog, configured)
      ApplicationManager.getApplication().invokeLater { show(project, catalog.size, fresh) }
    }
  }

  private fun show(project: com.intellij.openapi.project.Project?, total: Int, fresh: List<AgentRegistry.Entry>) {
    if (fresh.isEmpty()) {
      Messages.showInfoMessage(project, t("registry.nothingNew", "total" to total), t("registry.action"))
      return
    }
    // Показываем ровно то, что каталог знает: имя, версию, лицензию и КОМАНДУ запуска. Лицензия
    // здесь не формальность — реестр под Apache-2.0, а у каждого агента лицензия своя.
    val lines = fresh.joinToString("\n") { entry ->
      val command = AgentRegistry.toAgentEntry(entry)?.let { it.command + " " + it.args.joinToString(" ") }
        ?: binaryHint(entry)
      t("registry.line", "name" to entry.name, "version" to (entry.version ?: "—"),
        "license" to (entry.license ?: "—"), "command" to command)
    }
    Messages.showInfoMessage(
      project,
      t("registry.body", "total" to total, "fresh" to fresh.size, "url" to AgentRegistry.URL) + "\n\n" + lines,
      t("registry.action"),
    )
  }

  /**
   * Что сказать про агента, который поставляется готовым бинарём.
   *
   * Раньше здесь стояло одно слово «ставится вручную», и на этом каталог заканчивался: 17 записей
   * из 40 (проверено 09.09.2026) не давали человеку ни адреса, ни суммы — он шёл искать релиз сам.
   * Реестр всё это знает, поэтому теперь мы пересказываем: архив под ЭТУ машину, sha256 и команду
   * запуска после распаковки. Скачивание и запуск остаются за человеком — по решению о том, что
   * чужой процесс на его машине заводит он, а не мы.
   */
  private fun binaryHint(entry: AgentRegistry.Entry): String {
    val binary = entry.binary ?: return t("registry.manualInstall")
    return t(
      "registry.binaryInstall",
      "archive" to binary.archive,
      "sha256" to (binary.sha256 ?: "—"),
      "cmd" to (binary.cmd ?: "—"),
    )
  }

  private companion object {
    const val TIMEOUT_SECONDS = 20L
  }
}
