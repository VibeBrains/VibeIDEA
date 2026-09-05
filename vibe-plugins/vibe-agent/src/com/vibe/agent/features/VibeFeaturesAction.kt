// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.features

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.vibe.agent.i18n.VibeI18n.t

/**
 * Открывает список возможностей во вкладке редактора.
 *
 * Редактором, а не диалогом: список читают, ищут по нему и оставляют открытым рядом, а диалог
 * закрывают, не дочитав. Файл виртуальный и только для чтения — на диске проекта ему делать нечего.
 */
class VibeFeaturesAction : AnAction({ t("features.action") }) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    open(project)
  }

  companion object {
    fun open(project: Project) {
      val text = FeatureTour.read() ?: return
      val file = LightVirtualFile(t("features.fileName"), text).apply { isWritable = false }
      FileEditorManager.getInstance(project).openFile(file, true)
    }
  }
}
