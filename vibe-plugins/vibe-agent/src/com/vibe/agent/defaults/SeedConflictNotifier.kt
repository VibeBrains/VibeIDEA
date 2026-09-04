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
 *  • «Смержить» — the release on the left, THEIR OWN FILE on the right, editable: the release may
 *    bring something new while their edit is worth keeping, and only a person can say which chunk is
 *    which. The platform's diff viewer moves chunks with its own arrows; we invent nothing;
 *  • «Обновить» — takes the release version AFTER saving theirs as `<файл>.mine`: overwriting is the
 *    only destructive thing seeding does, and it never happens alone;
 *  • «Оставить своё» — recorded against the current revision, so it stays quiet until the next bump.
 */
object SeedConflictNotifier {
  private const val GROUP = com.vibe.agent.ui.VibeNotifications.AGENT

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

    notification.addAction(NotificationAction.createSimple(t("seed.conflict.merge")) {
      conflicts.forEach { showMerge(project, vibeDir, it.path) }
      // Not expired: merging is a conversation with the file, and the notice still holds the other
      // two answers for the files the person decides not to merge.
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

    notification.addAction(NotificationAction.createSimple(t("seed.conflict.adopt")) {
      ApplicationManager.getApplication().executeOnPooledThread {
        val adopted = ArrayList<String>()
        val backups = ArrayList<String>()
        for (conflict in conflicts) {
          val release = VibeDefaults.releaseContent(conflict.path) ?: continue
          val target = vibeDir.resolve(conflict.path)
          val local = readLocal(vibeDir, conflict.path)
          val saved = runCatching {
            // Their copy first, the overwrite second: a failed backup must not cost the edit.
            if (local != null) {
              val backup = vibeDir.resolve(SeedAdopt.backupName(conflict.path))
              backup.parent?.let { Files.createDirectories(it) }
              Files.writeString(backup, local)
              backups.add(SeedAdopt.backupName(conflict.path))
            }
            Files.writeString(target, release)
            true
          }.getOrDefault(false)
          if (saved) adopted.add(conflict.path)
        }
        VibeDefaults.markReconciled(projectBase, adopted)
        refreshVfs(vibeDir)
        ApplicationManager.getApplication().invokeLater {
          if (project.isDisposed || adopted.isEmpty()) return@invokeLater
          // Says where the old content went: a backup nobody knows about is a backup nobody uses.
          NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(
              t("seed.adopt.done", "count" to adopted.size, "word" to filesWord(adopted.size)),
              t("seed.adopt.backups", "files" to backups.joinToString(", ")),
              NotificationType.INFORMATION,
            ).notify(project)
          project.messageBus.syncPublisher(ProvidersChangeListener.TOPIC).providersChanged()
        }
      }
      notification.expire()
    })

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

  /**
   * The release next to THEIR file, with their side editable.
   *
   * A three-way merge would need the base — the release revision their copy grew from — and we do
   * not have its content: the set records only the sha256 of past revisions. Inventing a base to
   * satisfy the merge dialog would produce confident nonsense in the middle pane. So: two panes,
   * their own file on the right as a real document, and the viewer's own arrows to pull in whatever
   * the release brought. Nothing is written behind their back — the file changes only where they
   * click, and only until they save.
   */
  private fun showMerge(project: Project, vibeDir: Path, relative: String) {
    val release = VibeDefaults.releaseContent(relative) ?: return
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(vibeDir.resolve(relative)) ?: return
    val factory = DiffContentFactory.getInstance()
    DiffManager.getInstance().showDiff(
      project,
      SimpleDiffRequest(
        t("seed.merge.title", "file" to relative),
        factory.create(project, release, file.fileType),
        factory.create(project, file),
        t("seed.diff.release"),
        t("seed.merge.yoursEditable"),
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
