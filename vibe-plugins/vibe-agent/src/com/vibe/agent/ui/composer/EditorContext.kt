// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/** EDT-only bridges between the composer and the editor: selection capture, active file, navigation. */
object EditorContext {
  /** The active editor's selection as a staged context ref (1-based inclusive lines), or null. */
  fun currentSelection(project: Project): ContextRef.Selection? {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
    val selection = editor.selectionModel
    if (!selection.hasSelection()) return null
    val file = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    val document = editor.document
    val from = document.getLineNumber(selection.selectionStart) + 1
    val lastOffset = maxOf(selection.selectionStart, selection.selectionEnd - 1)
    val to = document.getLineNumber(lastOffset) + 1
    return ContextRef.Selection(file, from, to, selection.selectedText.orEmpty())
  }

  /** The file shown in the active editor (real files only — no settings/webviews), or null. */
  fun activeFile(project: Project): VirtualFile? =
    FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.isInLocalFileSystem && !it.isDirectory }

  fun open(project: Project, ref: ContextRef) {
    when (ref) {
      is ContextRef.File -> OpenFileDescriptor(project, ref.file).navigate(true)
      is ContextRef.Selection -> {
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, ref.file, ref.fromLine - 1, 0), true) ?: return
        val document = editor.document
        val lastLine = (ref.toLine - 1).coerceIn(0, maxOf(0, document.lineCount - 1))
        editor.selectionModel.setSelection(document.getLineStartOffset((ref.fromLine - 1).coerceIn(0, lastLine)), document.getLineEndOffset(lastLine))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
      }
      // A PDF opens in whatever viewer the IDE has for it; there is no line to jump to.
      is ContextRef.Document -> OpenFileDescriptor(project, ref.file).navigate(true)
      is ContextRef.Folder -> {
        val dir = PsiManager.getInstance(project).findDirectory(ref.file) ?: return
        PsiNavigationSupport.getInstance().navigateToDirectory(dir, true)
      }
    }
  }
}
