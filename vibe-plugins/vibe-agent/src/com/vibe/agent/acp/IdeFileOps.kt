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
  /**
   * Куда шагу разрешено писать сейчас. Пустая область — ограничения нет.
   *
   * Функцией, а не значением, по той же причине, что и роль: шаги сменяются в течение прогона, а
   * объект файловых операций живёт весь разговор.
   */
  private val scopeNow: () -> com.vibe.agent.pipelines.RolePaths.Scope =
    { com.vibe.agent.pipelines.RolePaths.Scope() },
  /** Reports what the context guard found in a file the agent read; the panel turns it into a line. */
  private val onFinding: (String, List<com.vibe.agent.security.ContextSanitizer.Finding>) -> Unit = { _, _ -> },
) {

  private fun roots(): com.vibe.agent.context.AccessPolicy.Roots =
    com.vibe.agent.context.ProjectContextService.getInstance(project).roots()

  /**
   * The agent's path as the IDE treats it, or a refusal in words the agent can act on.
   *
   * Public so the panel hands the hook and the audit the same spelling the write will use.
   */
  fun resolvePath(raw: String): com.vibe.agent.context.AgentPath =
    when (val result = com.vibe.agent.context.AgentPaths.resolve(raw)) {
      is com.vibe.agent.context.AgentPaths.Result.Resolved -> result.path
      is com.vibe.agent.context.AgentPaths.Result.Refused -> throw IllegalStateException(
        when (result.reason) {
          com.vibe.agent.context.AgentPaths.Refusal.NOT_ABSOLUTE -> t("access.pathNotAbsolute", "path" to raw)
          com.vibe.agent.context.AgentPaths.Refusal.INVALID -> t("access.pathInvalid", "path" to raw)
          com.vibe.agent.context.AgentPaths.Refusal.UNRESOLVABLE -> t("access.pathUnresolvable", "path" to raw)
        }
      )
    }

  fun readTextFile(params: JsonObject): JsonElement {
    val target = resolvePath(params.getValue("path").jsonPrimitive.content)
    // Asked BEFORE the read, not explained after: an ignored bundle or a folder nobody granted
    // is not "read and then filtered" — the bytes never enter the process. Decided on the physical
    // location: text with `..`, or a link inside the project, would otherwise get an answer about
    // one file while the operating system opens another.
    if (!com.vibe.agent.context.AccessPolicy.mayRead(target.canonical.toString(), roots())) {
      throw IllegalStateException(t("access.readDenied", "path" to target.canonical))
    }
    val path = target.normalized
    val line = params["line"]?.jsonPrimitive?.intOrNull
    val limit = params["limit"]?.jsonPrimitive?.intOrNull
    // nonBlocking вместо runReadAction: агент читает файлы пачками с фонового потока, и каждая
    // неотменяемая read action в этот момент блокирует правку в редакторе (устарело в 2026.1).
    val text = com.intellij.openapi.application.ReadAction.nonBlocking<String?> {
      val vFile = LocalFileSystem.getInstance().findFileByNioFile(path)
      if (vFile == null) null else FileDocumentManager.getInstance().getDocument(vFile)?.text
    }.executeSynchronously()
    var content = text ?: Files.readString(path)
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
    if (clean.findings.isNotEmpty()) onFinding(path.toString(), clean.findings)
    // Remember the file AS THE AGENT SAW IT — a partial read (line/limit) is not the whole file
    // and must not pass for one, or the guard would compare a fragment against the full text and
    // cry conflict on every windowed read.
    if (line == null && limit == null) seen.remember(path.toString(), content)
    return buildJsonObject { put("content", clean.text) }
  }

  fun writeTextFile(params: JsonObject): JsonElement {
    val target = resolvePath(params.getValue("path").jsonPrimitive.content)
    val path = target.normalized
    // A reviewer told «только отчёт» obeys most of the time, and «most of the time» is the whole
    // problem: the one run where it "just fixes" what it found is the run where the review and the
    // fix are the same act, and nobody reviewed the fix.
    val role = roleNow()
    if (!com.vibe.agent.pipelines.RoleRights.mayWrite(role)) {
      throw IllegalStateException(t("role.writeDenied", "role" to role))
    }
    // "Read my notes but do not edit them" is a rule only while something enforces it — and it is
    // enforced on the physical location, where the bytes will actually land.
    val roots = roots()
    if (!com.vibe.agent.context.AccessPolicy.mayWrite(target.canonical.toString(), roots)) {
      throw IllegalStateException(t("access.writeDenied", "path" to target.canonical))
    }
    // Область шага: «пишет только тесты» — обещание ровно до тех пор, пока его кто-то проверяет.
    val scope = scopeNow()
    if (scope.stated) {
      // A scope is written relative to the project root, so a place outside the project has no
      // answer in it and is refused. The former fallback matched the absolute path as if it were
      // relative, and a scope of `**` let it through.
      val relative = roots.projectBase?.let { com.vibe.agent.context.AccessPolicy.relativeTo(target.canonical.toString(), it) }
      if (relative == null || !com.vibe.agent.pipelines.RolePaths.mayWrite(relative, scope)) {
        throw IllegalStateException(t("role.pathDenied", "role" to (roleNow() ?: "-"), "path" to (relative ?: target.canonical)))
      }
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
    // Named, never blocked: a rewrite that produces the right file is not a defect, but a cost
    // nobody can see is a cost nobody can decide about.
    com.vibe.agent.edits.WholeFileRewrite.check(if (exists) oldText else null, content)?.let { v ->
      onNotice(t("write.wholeFileRewrite", "path" to path.fileName, "changed" to v.changedLines, "total" to v.totalLines))
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
    val text = com.intellij.openapi.application.ReadAction.nonBlocking<String?> {
      val vFile = LocalFileSystem.getInstance().findFileByNioFile(path)
      if (vFile == null) null else FileDocumentManager.getInstance().getDocument(vFile)?.text
    }.executeSynchronously()
    return text ?: runCatching { Files.readString(path) }.getOrDefault("")
  }
}
