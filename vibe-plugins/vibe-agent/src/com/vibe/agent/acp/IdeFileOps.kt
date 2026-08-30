// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.acp

import com.vibe.agent.i18n.VibeI18n.t

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Maps ACP fs requests onto the IDE:
 * reads see unsaved in-editor changes (Document first, disk second);
 * writes go through WriteCommandAction when the file is open, plain NIO + async
 * VFS refresh otherwise — external writes must never leave VFS stale (VibeIDE lesson).
 */
internal class IdeFileOps(
  private val project: Project,
  /** Reports what the context guard found in a file the agent read; the panel turns it into a line. */
  private val onFinding: (String, List<com.vibe.agent.security.ContextSanitizer.Finding>) -> Unit = { _, _ -> },
) {

  fun readTextFile(params: JsonObject): JsonElement {
    val path = params.getValue("path").jsonPrimitive.content
    val line = params["line"]?.jsonPrimitive?.intOrNull
    val limit = params["limit"]?.jsonPrimitive?.intOrNull
    var text: String? = null
    ApplicationManager.getApplication().runReadAction {
      val vFile = LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))
      if (vFile != null) {
        text = FileDocumentManager.getInstance().getDocument(vFile)?.text
      }
    }
    var content = text ?: Files.readString(Path.of(path))
    if (line != null || limit != null) {
      var lines = content.lines()
      // ACP `line` is 1-based: line=1 starts at the first line, so drop (line-1).
      if (line != null) lines = lines.drop((line - 1).coerceAtLeast(0))
      if (limit != null) lines = lines.take(limit)
      content = lines.joinToString("\n")
    }
    // The agent is about to read someone else's file: hidden characters never reach it, and what
    // the guard found is reported to the user through [onFinding] (the panel prints one line).
    val clean = com.vibe.agent.security.ContextSanitizer.sanitize(content, maskSecrets = false)
    if (clean.findings.isNotEmpty()) onFinding(path, clean.findings)
    return buildJsonObject { put("content", clean.text) }
  }

  fun writeTextFile(params: JsonObject): JsonElement {
    val path = Path.of(params.getValue("path").jsonPrimitive.content)
    val content = params.getValue("content").jsonPrimitive.contentOrNull ?: ""
    val oldText = readCurrentText(path)
    if (oldText == content) return buildJsonObject { }
    if (!WritePreview.confirm(project, path.toString(), oldText, content)) {
      throw IllegalStateException(t("write.refused", "path" to path))
    }
    var handledInEditor = false
    ApplicationManager.getApplication().invokeAndWait {
      val vFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@invokeAndWait
      val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@invokeAndWait
      WriteCommandAction.runWriteCommandAction(project, "Vibe Agent Edit", null, {
        document.setText(content)
        FileDocumentManager.getInstance().saveDocument(document)
      })
      handledInEditor = true
    }
    if (!handledInEditor) {
      path.parent?.let { Files.createDirectories(it) }
      Files.writeString(path, content)
      ApplicationManager.getApplication().invokeLater {
        VfsUtil.markDirtyAndRefresh(true, false, false, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
      }
    }
    return buildJsonObject { }
  }

  private fun readCurrentText(path: Path): String {
    var text: String? = null
    ApplicationManager.getApplication().runReadAction {
      val vFile = LocalFileSystem.getInstance().findFileByNioFile(path)
      if (vFile != null) text = FileDocumentManager.getInstance().getDocument(vFile)?.text
    }
    return text ?: runCatching { Files.readString(path) }.getOrDefault("")
  }
}
