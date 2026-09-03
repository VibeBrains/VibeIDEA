// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.http.OpenApiImport

/**
 * «Собрать запросы из OpenAPI» — выбрать `openapi.json` и получить готовый файл `.http`.
 *
 * Самая скучная работа при знакомстве с чужим API — перепечатать двадцать эндпоинтов руками.
 * Здесь она делается один раз и превращается в файл, который дальше живёт в git.
 */
class VibeHttpFromOpenApiAction : AnAction({ t("http.action.fromOpenApi") }) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
      .withTitle(t("http.openApi.choose"))
    val chosen = FileChooser.chooseFile(descriptor, project, e.getData(CommonDataKeys.VIRTUAL_FILE)) ?: return
    val text = runCatching { VfsUtil.loadText(chosen) }.getOrNull()
    val spec = text?.let { OpenApiImport.parse(it) }
    if (spec == null) {
      // Отказ называет причину и границу: YAML мы не читаем осознанно, и человек должен узнать
      // об этом здесь, а не решить, что действие сломано.
      Messages.showWarningDialog(project, t("http.openApi.notSpec", "file" to chosen.name), t("http.action.fromOpenApi"))
      return
    }
    val content = OpenApiImport.toHttpFile(
      spec,
      header = t("http.openApi.header"),
      serverNote = { t("http.openApi.server", "url" to it) },
    )
    val name = (spec.title?.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-")?.trim('-')?.take(40)?.ifEmpty { null }
                ?: "api") + ".http"
    val target = com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction<com.intellij.openapi.vfs.VirtualFile?> {
      runCatching {
        val dir = chosen.parent ?: return@runCatching null
        val file = dir.findChild(name) ?: dir.createChildData(this, name)
        VfsUtil.saveText(file, content)
        file
      }.getOrNull()
    }
    if (target == null) {
      Messages.showWarningDialog(project, t("http.openApi.writeFailed", "name" to name), t("http.action.fromOpenApi"))
      return
    }
    FileEditorManager.getInstance(project).openFile(target, true)
  }
}
