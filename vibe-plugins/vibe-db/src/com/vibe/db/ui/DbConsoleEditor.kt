// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.db.ui

import com.intellij.openapi.fileEditor.FileEditor
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
 * Вкладка консоли базы в центральной панели.
 *
 * Вкладка редактора в IntelliJ всегда чья-то: платформа открывает ФАЙЛ, а не панель, поэтому за
 * консолью стоит лёгкий виртуальный файл. Он же делает вкладку узнаваемой — её можно закрепить,
 * перетащить в другую группу и разделить экран, как любой другой файл.
 */
class DbConsoleEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
  private val panel = DbConsolePanel(project)

  override fun getComponent(): JComponent = panel
  override fun getPreferredFocusedComponent(): JComponent = panel
  override fun getName(): String = t("db.console.tab")
  override fun setState(state: FileEditorState) {}
  override fun isModified(): Boolean = false
  override fun isValid(): Boolean = true
  override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
  override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
  override fun dispose() {}
  override fun getFile(): VirtualFile = file
}

class DbConsoleVirtualFile : LightVirtualFile(t("db.console.tab")) {
  override fun equals(other: Any?): Boolean = other is DbConsoleVirtualFile
  override fun hashCode(): Int = javaClass.hashCode()
}

class DbConsoleEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean = file is DbConsoleVirtualFile
  override fun createEditor(project: Project, file: VirtualFile): FileEditor = DbConsoleEditor(project, file)
  override fun getEditorTypeId(): String = "vibe-db-console"
  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
