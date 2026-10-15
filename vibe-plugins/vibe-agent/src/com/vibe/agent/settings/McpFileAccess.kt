// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.mcp.McpServersEdit
import com.vibe.agent.mcp.McpServersFile
import java.nio.file.Files

/**
 * `.vibe/mcp.json` of the project as the settings page touches it: read the text, write an edit, open it in the editor
 *
 * The text is the editor's when the file is open (unsaved changes included) and the disk's otherwise: a page that read the disk
 * while the editor held a newer text would write its edit over the person's own. Edits go through the document, as one undoable
 * step, like the edits of the commands menu
 */
internal class McpFileAccess(private val project: Project) {
  private fun virtualFile(): VirtualFile? =
    McpServersFile.pathIn(project.basePath)?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }

  /** The text of the file; null when there is no file yet */
  fun read(): String? {
    val file = virtualFile() ?: return null
    return FileDocumentManager.getInstance().getDocument(file)?.text
           ?: runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
  }

  /**
   * Applies [edit] to the text (null for a file that does not exist yet) and writes the result
   *
   * Null when the file was written; otherwise the reason in words: the edit refused ([name] is the server it concerned)
   * or the system could not write
   */
  fun rewrite(title: String, name: String, edit: (String?) -> McpServersEdit.Outcome): String? {
    val path = McpServersFile.pathIn(project.basePath) ?: return t("settings.mcp.refused.noProject")
    val file = virtualFile()
    val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
    // A file that exists but gives no text must not be taken for a missing one: the edit would write a new file over it
    if (file != null && document == null) return t("settings.mcp.refused.noText")
    val outcome = edit(document?.text)
    if (outcome is McpServersEdit.Outcome.Refused) return McpServersPage.refusal(outcome.reason, name)
    val text = (outcome as McpServersEdit.Outcome.Edited).text
    return try {
      if (document == null) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
      }
      else {
        WriteCommandAction.runWriteCommandAction(project, title, null, { document.setText(text) })
        FileDocumentManager.getInstance().saveDocument(document)
      }
      null
    }
    catch (e: Exception) {
      t("settings.mcp.refused.io", "reason" to (e.message ?: e.javaClass.simpleName))
    }
  }

  /** Opens the file in the editor, with the caret on the entry of [name] when it is given and found */
  fun open(name: String?) {
    val file = virtualFile() ?: return
    val offset = name?.let { n -> FileDocumentManager.getInstance().getDocument(file)?.text?.let { McpServersEdit.offsetOf(it, n) } } ?: 0
    OpenFileDescriptor(project, file, offset).navigate(true)
  }
}
