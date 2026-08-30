// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ui.Messages
import com.vibe.agent.i18n.VibeI18n.t

/**
 * "Read my notes, but do not edit them" — as a folder the agent is given, not as a sentence
 * in the prompt it may ignore. Attached read-only: [AccessPolicy] refuses writes there always.
 */
class VibeAddReferenceFolderAction : AnAction({ t("reference.add.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
      .withTitle(t("reference.add.chooser"))
    val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
    val service = ProjectContextService.getInstance(project)
    val path = AccessPolicy.normalize(chosen.path)
    val current = service.referenceFolders()
    if (path in current) {
      Messages.showInfoMessage(project, t("reference.already", "path" to path), t("reference.title"))
      return
    }
    service.setReferenceFolders(current + path)
    Messages.showInfoMessage(project, t("reference.added", "path" to path), t("reference.title"))
  }
}

/** The other half: a list that can only grow is a list nobody trusts. */
class VibeManageReferenceFoldersAction : AnAction({ t("reference.manage.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val service = ProjectContextService.getInstance(project)
    val folders = service.referenceFolders()
    if (folders.isEmpty()) {
      Messages.showInfoMessage(project, t("reference.none"), t("reference.title"))
      return
    }
    val choice = Messages.showEditableChooseDialog(
      t("reference.manage.prompt"), t("reference.title"), Messages.getQuestionIcon(),
      folders.toTypedArray(), folders.first(), null,
    ) ?: return
    service.setReferenceFolders(folders.filter { it != choice })
    Messages.showInfoMessage(project, t("reference.removed", "path" to choice), t("reference.title"))
  }
}
