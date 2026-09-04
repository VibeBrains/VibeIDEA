// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks the well-known local ports whether a model server is running there, and offers the
 * configuration the person would otherwise type by hand.
 *
 * Deliberately NOT automatic in its effect: the probe reports, the person applies. A provider that
 * appeared in the config because something answered on a port is indistinguishable from a
 * misconfiguration, and the first time it happens the config stops being trusted.
 *
 * The timeout is short and the requests are parallel: probing four ports must not be something one
 * notices, especially on a machine where nothing is listening.
 */
object LocalProbeRunner {
  private const val TIMEOUT_MS = 700L

  fun probe(existing: List<ProviderEntry>): List<LocalProbe.Found> {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build()
    val results = java.util.concurrent.ConcurrentHashMap<String, LocalProbe.Found>()
    val threads = LocalProbe.CANDIDATES
      .filterNot { LocalProbe.isConfigured(it, existing) }
      .map { candidate ->
        Thread({
          runCatching {
            val request = HttpRequest.newBuilder(URI.create(candidate.probePath))
              .timeout(Duration.ofMillis(TIMEOUT_MS)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
              val models = LocalProbe.parseModels(response.body())
              // A server that answers but lists nothing is running without models loaded: offering
              // to configure it would produce a provider with an empty picker.
              if (models.isNotEmpty()) results[candidate.id] = LocalProbe.Found(candidate, models)
            }
          }
        }, "vibe-local-probe-" + candidate.id).apply { isDaemon = true }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join(TIMEOUT_MS * 2) }
    return LocalProbe.CANDIDATES.mapNotNull { results[it.id] }
  }
}

/** Tools → «VibeIDEA: найти локальные модели». */
class VibeDetectLocalModelsAction : AnAction({ t("localProbe.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val existing = ProvidersService.load(project.basePath) { }
      val found = LocalProbeRunner.probe(existing)
      ApplicationManager.getApplication().invokeLater {
        if (found.isEmpty()) {
          Messages.showInfoMessage(project, t("localProbe.none"), t("localProbe.title"))
          return@invokeLater
        }
        val report = found.joinToString("\n\n") { entry ->
          t("localProbe.found", "name" to entry.candidate.name, "url" to entry.candidate.baseUrl,
            "models" to entry.models.take(5).joinToString(", ")) + "\n\n" + LocalProbe.suggestedJson(entry)
        }
        val answer = Messages.showYesNoDialog(project, report + "\n\n" + t("localProbe.howTo"),
                                              t("localProbe.title"), t("localProbe.copy"), t("common.close"), null)
        if (answer == Messages.YES) {
          CopyPasteManager.getInstance().setContents(StringSelection(found.joinToString(",\n") { LocalProbe.suggestedJson(it) }))
        }
      }
    }
  }
}

/**
 * The same probe on project open, but silent unless something is found AND nothing is configured:
 * a notification on every start is a notification people turn off.
 */
class LocalProbeStartup : com.intellij.openapi.startup.ProjectActivity {
  override suspend fun execute(project: Project) {
    // NOT «providers are empty»: the defaults seed a folder of provider templates, so that check
    // would silence the probe in every seeded project — which is every project.
    val properties = com.intellij.ide.util.PropertiesComponent.getInstance(project)
    if (properties.getBoolean(KEY_MUTED, false)) return
    val existing = ProvidersService.load(project.basePath) { }
    val found = LocalProbeRunner.probe(existing)
    if (found.isEmpty()) return
    NotificationGroupManager.getInstance().getNotificationGroup(com.vibe.agent.ui.VibeNotifications.AGENT)
      .createNotification(
        t("localProbe.notice", "names" to found.joinToString { it.candidate.name }),
        NotificationType.INFORMATION,
      )
      .addAction(NotificationAction.createSimple(t("localProbe.copy")) {
        CopyPasteManager.getInstance().setContents(StringSelection(found.joinToString(",\n") { LocalProbe.suggestedJson(it) }))
      })
      // Somebody running Ollama for something else entirely must be able to end this conversation.
      .addAction(NotificationAction.createSimple(t("localProbe.mute")) {
        properties.setValue(KEY_MUTED, true)
      })
      .notify(project)
  }

  private companion object {
    const val KEY_MUTED = "vibe.localProbe.muted"
  }
}
