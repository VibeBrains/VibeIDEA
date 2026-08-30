// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.defaults

import com.vibe.agent.i18n.VibeI18n.t
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.providers.ProvidersChangeListener
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tells the user that the shared `.vibe` set moved on while their copy of a seeded file differs —
 * the one case seeding cannot resolve on its own. Everything else (new files, untouched copies of
 * older revisions) is handled silently by [VibeDefaults]; interrupting for those would be noise.
 *
 * Three ways out, all non-destructive by default:
 *  • «Сравнить» — the release's content next to theirs in the platform diff viewer;
 *  • «Обновить, сохранив мои тумблеры» — offered only when the sole difference is `active`
 *    values: the file is refreshed and their off-switches move to `<project>/.vibe/providers.json`,
 *    the overlay layer that always wins and that no release ever rewrites;
 *  • «Оставить своё» — recorded against the current revision, so it stays quiet until the next bump.
 */
object SeedConflictNotifier {
  private const val GROUP = "Vibe Agent"

  fun notify(project: Project, projectBase: String, conflicts: List<VibeDefaults.SeedConflict>) {
    if (conflicts.isEmpty()) return
    val vibeDir = Path.of(projectBase, ".vibe")
    val names = conflicts.joinToString(", ") { it.path }
    val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
      .createNotification(
        t("seed.conflict.title", "count" to conflicts.size, "word" to filesWord(conflicts.size)),
        t("seed.conflict.body", "files" to names),
        NotificationType.INFORMATION,
      )

    notification.addAction(NotificationAction.createSimple(t("seed.conflict.compare")) {
      conflicts.forEach { showDiff(project, vibeDir, it.path) }
    })

    val toggleOnly = conflicts.filter { canMergeToggles(vibeDir, it.path) }
    if (toggleOnly.isNotEmpty()) {
      notification.addAction(NotificationAction.createSimple(t("seed.conflict.keepToggles")) {
        ApplicationManager.getApplication().executeOnPooledThread {
          val merged = toggleOnly.filter { SeedToggleMerge.apply(vibeDir, it.path) }
          VibeDefaults.markReconciled(projectBase, merged.map { it.path })
          refreshVfs(vibeDir)
          ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && merged.isNotEmpty()) {
              project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
            }
          }
        }
        notification.expire()
      })
    }

    notification.addAction(NotificationAction.createSimple(t("seed.conflict.keepMine")) {
      ApplicationManager.getApplication().executeOnPooledThread {
        VibeDefaults.markReconciled(projectBase, conflicts.map { it.path })
      }
      notification.expire()
    })

    notification.notify(project)
  }

  private fun showDiff(project: Project, vibeDir: Path, relative: String) {
    val release = VibeDefaults.releaseContent(relative) ?: return
    val local = runCatching { Files.readString(vibeDir.resolve(relative)) }.getOrNull() ?: return
    val factory = DiffContentFactory.getInstance()
    DiffManager.getInstance().showDiff(
      project,
      SimpleDiffRequest(
        t("seed.diff.title", "file" to relative),
        factory.create(project, release),
        factory.create(project, local),
        t("seed.diff.release"),
        t("seed.diff.yours"),
      ),
    )
  }

  private fun canMergeToggles(vibeDir: Path, relative: String): Boolean =
    SeedToggleMerge.toggleDiff(VibeDefaults.releaseContent(relative), readLocal(vibeDir, relative)) != null

  private fun readLocal(vibeDir: Path, relative: String): String? =
    runCatching { Files.readString(vibeDir.resolve(relative)) }.getOrNull()

  private fun refreshVfs(vibeDir: Path) {
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(vibeDir)?.refresh(false, true)
  }

  private fun filesWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> t("seed.word.file.one")
    n % 10 in 2..4 && n % 100 !in 12..14 -> t("seed.word.file.few")
    else -> t("seed.word.file.many")
  }
}
