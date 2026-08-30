// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tools → «VibeIDEA: снять карту построенного»: writes `.vibe/design/uiKit.md` from the code.
 *
 * Written as a DRAFT and never silently: the map is a syntactic scan, it will contain a few things
 * that are not components and miss a few that are, and a person correcting it is part of the design.
 * Overwriting a corrected map with a fresh scan would destroy exactly that work, so an existing file
 * is only replaced after a yes.
 */
class VibeUiKitMapAction : AnAction({ t("uikit.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val base = project.basePath ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      val files = com.vibe.agent.context.ProjectFiles.read(project, SOURCE_EXTENSIONS)
      val map = UiKitMap.scan(files)
      ApplicationManager.getApplication().invokeLater {
        if (map.isEmpty) {
          // An honest empty result: an invented map is worse than none, because it is believed.
          Messages.showInfoMessage(project, t("uikit.empty"), t("uikit.title"))
          return@invokeLater
        }
        val target = Path.of(base, UI_KIT_PATH)
        if (Files.exists(target)) {
          val answer = Messages.showYesNoDialog(project, t("uikit.overwrite", "path" to UI_KIT_PATH),
                                                t("uikit.title"), t("uikit.replace"), t("common.cancel"), null)
          if (answer != Messages.YES) return@invokeLater
        }
        runCatching {
          Files.createDirectories(target.parent)
          Files.writeString(target, UiKitMap.render(map, labels()))
        }.onFailure {
          Messages.showWarningDialog(project, t("uikit.failed", "reason" to it.message), t("uikit.title"))
          return@invokeLater
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let {
          FileEditorManager.getInstance(project).openFile(it, true)
        }
      }
    }
  }


  private fun labels() = object : UiKitMap.Labels {
    override val title: String get() = t("uikit.doc.title")
    override val preamble: String get() = t("uikit.doc.preamble")
    override val tokens: String get() = t("uikit.doc.tokens")
    override val classes: String get() = t("uikit.doc.classes")
    override val components: String get() = t("uikit.doc.components")
    override val empty: String get() = t("uikit.doc.empty")
    override fun more(count: Int) = t("uikit.doc.more", "count" to count)
  }

  private companion object {
    const val UI_KIT_PATH = ".vibe/design/uiKit.md"
    val SOURCE_EXTENSIONS = setOf("css", "scss", "less", "tsx", "jsx", "ts", "js")
  }
}
