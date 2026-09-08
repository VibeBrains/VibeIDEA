// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.http.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.vibe.agent.i18n.VibeI18n.t
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Связь двух половин HTTP-клиента: списка запросов слева и ответа в центре.
 *
 * Отправляет запросы только список — панель ответа их не выполняет вовсе. Иначе «повторить» и
 * «отправить» однажды разойдутся в том, какие переменные подставлены и из какого окружения, а
 * человек будет сравнивать два ответа, полученные по-разному.
 */
@Service(Service.Level.PROJECT)
class HttpWorkbench(private val project: Project) {
  @Volatile var response: HttpResponsePanel? = null

  /** Открывает (или выводит вперёд) вкладку ответа и возвращает её панель. */
  fun openResponse(): HttpResponsePanel? {
    val manager = FileEditorManager.getInstance(project)
    val file = manager.openFiles.firstOrNull { it is HttpResponseVirtualFile } ?: HttpResponseVirtualFile()
    manager.openFile(file, false)
    return response
  }

  companion object {
    fun getInstance(project: Project): HttpWorkbench = project.service()
  }
}

/** Вкладка редактора всегда чья-то: за панелью ответа стоит лёгкий виртуальный файл. */
class HttpResponseVirtualFile : LightVirtualFile(t("http.response.tab")) {
  override fun equals(other: Any?): Boolean = other is HttpResponseVirtualFile
  override fun hashCode(): Int = javaClass.hashCode()
}

class HttpResponseEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val panel = HttpResponsePanel(project)

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = panel
  override fun getName(): String = t("http.response.tab")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun dispose() {}
  override fun getFile(): VirtualFile = file
}

class HttpResponseEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is HttpResponseVirtualFile
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = HttpResponseEditor(project, file)
  override fun getEditorTypeId(): String = "vibe-http-response"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
