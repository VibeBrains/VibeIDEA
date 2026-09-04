// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * Говорит про отсутствующий `jsconfig.json` — в тот момент, когда это важно: при открытии `.js`.
 *
 * Без него tsserver видит только открытый файл и то, что достижимо через `import`, поэтому в
 * кодовой базе со своим загрузчиком классов (ExtJS, глобалы в `window`) переход к определению
 * молчит. Снаружи это выглядит как «IDE не умеет», хотя это отсутствующий файл на десять строк.
 *
 * Показывается один раз на проект и гасится навсегда одной кнопкой: подсказка, повторяющаяся на
 * каждом файле, — это способ научить людей закрывать подсказки не читая.
 */
class VibeJsConfigNotifier(private val project: Project) : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.extension?.lowercase() != "js") return
    val properties = PropertiesComponent.getInstance(project)
    if (properties.getBoolean(KEY_MUTED, false) || properties.getBoolean(KEY_SHOWN, false)) return
    val base = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return

    // Считаем на фоне: обход дерева проекта на EDT — это подвисание на открытии файла.
    ApplicationManager.getApplication().executeOnPooledThread {
      val described = describe(project, base) ?: return@executeOnPooledThread
      if (!JsProjectConfig.needsConfig(described)) return@executeOnPooledThread
      properties.setValue(KEY_SHOWN, true)
      NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        .createNotification(
          t("lsp.jsconfig.title"),
          t("lsp.jsconfig.body", "count" to described.ownJsFiles, "dirs" to described.sourceDirs.joinToString(", ")),
          NotificationType.INFORMATION,
        )
        .addAction(NotificationAction.createSimpleExpiring(t("lsp.jsconfig.create")) {
          create(base, described)
        })
        .addAction(NotificationAction.createSimpleExpiring(t("lsp.jsconfig.mute")) {
          properties.setValue(KEY_MUTED, true)
        })
        .notify(project)
    }
  }

  /** Что за проект: свои каталоги с `.js`, вендорные и наличие уже готового конфига. */
  private fun describe(project: Project, base: Path): JsProjectConfig.Project? {
    val hasConfig = JsProjectConfig.EXISTING_CONFIGS.any { Files.isRegularFile(base.resolve(it)) }
    if (hasConfig) return null
    val ownFiles = HashMap<String, Int>()
    val vendors = LinkedHashSet<String>()
    runCatching {
      ProjectRootManager.getInstance(project).fileIndex.iterateContent { vf ->
        if (!vf.isDirectory && vf.extension?.lowercase() == "js") {
          val relative = vf.path.removePrefix(base.toString()).trim('/')
          val top = relative.substringBefore('/')
          if (top.isNotEmpty() && relative.contains('/')) {
            if (JsProjectConfig.isVendor(top)) vendors.add(top) else ownFiles.merge(top, 1, Int::plus)
          }
        }
        true
      }
    }
    return JsProjectConfig.Project(
      hasConfig = false,
      ownJsFiles = ownFiles.values.sum(),
      // Каталог с парой файлов в include не нужен: он раздувает программу, ничего не давая.
      sourceDirs = ownFiles.filterValues { it >= MIN_FILES_PER_DIR }.keys.sorted(),
      vendorDirs = vendors.sorted(),
    )
  }

  private fun create(base: Path, described: JsProjectConfig.Project) {
    val target = base.resolve("jsconfig.json")
    val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
    val result = runCatching {
      Files.writeString(target, JsProjectConfig.content(described))
      com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
    }
    if (result.isSuccess) {
      group.createNotification(t("lsp.jsconfig.created", "path" to target.toString()), NotificationType.INFORMATION)
        .notify(project)
    }
    else {
      group.createNotification(
        t("lsp.jsconfig.failed", "reason" to (result.exceptionOrNull()?.message ?: "")),
        NotificationType.WARNING,
      ).notify(project)
    }
  }

  private companion object {
    const val GROUP = com.vibe.agent.ui.VibeNotifications.LANGUAGES
    const val KEY_SHOWN = "vibe.lsp.jsconfigShown"
    const val KEY_MUTED = "vibe.lsp.jsconfigMuted"

    /** Каталог с меньшим числом файлов в include не попадает: программа больше, пользы ноль. */
    const val MIN_FILES_PER_DIR = 5
  }
}
