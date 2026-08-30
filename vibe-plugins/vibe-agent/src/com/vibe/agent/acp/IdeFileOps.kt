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
  /** What the agent has read, so a write over someone else's change can be recognised. */
  private val seen: com.vibe.agent.edits.WriteGuard.Seen = com.vibe.agent.edits.WriteGuard.Seen(),
  /** One line into the feed: conflicts and applied editor fixes are told, never silent. */
  private val onNotice: (String) -> Unit = {},
  /** The pipeline role in force right now; a judging role is refused the write. */
  private val roleNow: () -> String? = { null },
  /** Reports what the context guard found in a file the agent read; the panel turns it into a line. */
  private val onFinding: (String, List<com.vibe.agent.security.ContextSanitizer.Finding>) -> Unit = { _, _ -> },
) {

  private fun roots(): com.vibe.agent.context.AccessPolicy.Roots =
    com.vibe.agent.context.ProjectContextService.getInstance(project).roots()

  fun readTextFile(params: JsonObject): JsonElement {
    val path = params.getValue("path").jsonPrimitive.content
    // Asked BEFORE the read, not explained after: an ignored bundle or a folder nobody granted
    // is not "read and then filtered" — the bytes never enter the process.
    if (!com.vibe.agent.context.AccessPolicy.mayRead(path, roots())) {
      throw IllegalStateException(t("access.readDenied", "path" to path))
    }
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
    // Remember the file AS THE AGENT SAW IT — a partial read (line/limit) is not the whole file
    // and must not pass for one, or the guard would compare a fragment against the full text and
    // cry conflict on every windowed read.
    if (line == null && limit == null) seen.remember(path, content)
    return buildJsonObject { put("content", clean.text) }
  }

  fun writeTextFile(params: JsonObject): JsonElement {
    val path = Path.of(params.getValue("path").jsonPrimitive.content)
    // A reviewer told «только отчёт» obeys most of the time, and «most of the time» is the whole
    // problem: the one run where it "just fixes" what it found is the run where the review and the
    // fix are the same act, and nobody reviewed the fix.
    val role = roleNow()
    if (!com.vibe.agent.pipelines.RoleRights.mayWrite(role)) {
      throw IllegalStateException(t("role.writeDenied", "role" to role))
    }
    // "Read my notes but do not edit them" is a rule only while something enforces it.
    if (!com.vibe.agent.context.AccessPolicy.mayWrite(path.toString(), roots())) {
      throw IllegalStateException(t("access.writeDenied", "path" to path))
    }
    val content = params.getValue("content").jsonPrimitive.contentOrNull ?: ""
    val exists = Files.exists(path)
    val oldText = readCurrentText(path)
    if (oldText == content) return buildJsonObject { }
    // The silent damage this prevents: the agent read the file, thought for a minute while the
    // human edited it, and is now about to write the whole content composed from the OLD text.
    // Nothing errors; the human's work just disappears, looking like the agent's own edit.
    val verdict = com.vibe.agent.edits.WriteGuard.check(path.toString(), if (exists) oldText else null, seen)
    if (verdict == com.vibe.agent.edits.WriteGuard.Verdict.CONFLICT) {
      onNotice(t("write.conflict", "path" to path))
    }
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
    // The written text is now what the agent last saw: without this every second write of the same
    // file would be reported as a conflict with the agent's own previous write.
    seen.remember(path.toString(), content)
    com.vibe.agent.edits.EditorAutoFix.apply(project, path) { message -> onNotice(message) }
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
