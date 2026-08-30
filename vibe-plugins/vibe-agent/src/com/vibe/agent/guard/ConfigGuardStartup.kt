// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.guard

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the project's config files once, when it is opened, and says what it found.
 *
 * At open rather than at first use: a key committed into `providers.json` is a leak the moment the
 * repository is pushed, and telling the person about it after they have already worked for an hour
 * is telling them too late. Silence when nothing is found — a check that speaks up on every start
 * is a check people learn to ignore.
 */
class ConfigGuardStartup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val base = project.basePath ?: return
    val findings = ConfigGuard.FILES.flatMap { relative ->
      val file = Path.of(base, relative)
      if (!Files.isRegularFile(file)) return@flatMap emptyList()
      val text = runCatching { Files.readString(file) }.getOrNull() ?: return@flatMap emptyList()
      ConfigGuard.inspect(relative, text)
    }
    if (findings.isEmpty()) return
    val worst = ConfigGuard.worst(findings)
    val details = findings.take(MAX_SHOWN).joinToString("; ") { describe(it) }
    NotificationGroupManager.getInstance().getNotificationGroup("Vibe Agent")
      .createNotification(
        t("configGuard.found", "count" to findings.size, "details" to details),
        if (worst == ConfigGuard.Severity.ERROR) NotificationType.WARNING else NotificationType.INFORMATION,
      )
      .notify(project)
  }

  private fun describe(finding: ConfigGuard.Finding): String = when (finding.rule) {
    ConfigGuard.RULE_PLAINTEXT_SECRET -> t("configGuard.rule.plaintextSecret", "detail" to finding.detail, "file" to finding.file)
    ConfigGuard.RULE_INSECURE_ENDPOINT -> t("configGuard.rule.insecureEndpoint", "detail" to finding.detail, "file" to finding.file)
    ConfigGuard.RULE_CREDENTIALS_IN_URL -> t("configGuard.rule.credentialsInUrl", "detail" to finding.detail, "file" to finding.file)
    else -> t("configGuard.rule.rawIpEndpoint", "detail" to finding.detail, "file" to finding.file)
  }

  private companion object {
    /** Enough to act on; the rest would turn a warning into a wall of text nobody reads. */
    const val MAX_SHOWN = 3
  }
}
