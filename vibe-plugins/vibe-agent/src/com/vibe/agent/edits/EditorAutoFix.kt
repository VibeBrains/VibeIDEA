// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.vibe.agent.i18n.VibeI18n.t
import java.nio.file.Path

/**
 * Errors the IDE has known how to fix since 2003, fixed by the IDE.
 *
 * Paying a model to notice a missing import, explain it and rewrite the file is absurd: the
 * platform already knows the answer, has it unambiguous, and applies it in a millisecond. What
 * makes this safe rather than magic is the narrowness enforced by [AutoFixPolicy] — imports only,
 * unambiguous only, a handful per file — plus one line in the feed saying what was applied.
 *
 * Anything applied silently would be worse than useless: the next time the agent's code compiles
 * for a reason the author cannot see, they stop trusting the whole edit path.
 */
object EditorAutoFix {
  fun apply(project: Project, path: Path, onNotice: (String) -> Unit) {
    val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      val document = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater
      val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@invokeLater
      val applied = ArrayList<String>()
      // Highlights are whatever the daemon has already computed for this file: we never start an
      // analysis of our own, because a write must not turn into a full inspection run.
      DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.ERROR, 0, document.textLength) { info ->
        val candidates = collectFixes(info)
        val single = candidates.singleOrNull()
        if (single != null && AutoFixPolicy.mayApply(single.familyName, candidates.size, applied.size)) {
          var done = false
          runCatching {
            WriteCommandAction.runWriteCommandAction(project, t("autofix.command"), null, Runnable {
              if (single.isAvailable(project, null, psiFile)) {
                single.invoke(project, null, psiFile)
                done = true
              }
            })
          }
          if (done) applied.add(single.familyName)
        }
        applied.size < AutoFixPolicy.MAX_FIXES_PER_FILE
      }
      if (applied.isNotEmpty()) {
        FileDocumentManager.getInstance().saveDocument(document)
        onNotice(t("autofix.applied", "count" to applied.size, "names" to applied.joinToString(", ")))
      }
    }
  }

  private fun collectFixes(info: HighlightInfo): List<IntentionAction> {
    val fixes = ArrayList<IntentionAction>()
    info.findRegisteredQuickFix<Any?> { descriptor, _ ->
      fixes.add(descriptor.action)
      null
    }
    return fixes
  }
}
