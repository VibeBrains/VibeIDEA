// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

/**
 * Turns parsed [MentionToken]s into staged [ContextRef]s using project indices and editor history.
 * Unresolved tokens never block sending: they are reported back as their raw text so the composer
 * can warn the user.
 */
class MentionResolver(private val project: Project) {
  data class Result(val refs: List<ContextRef>, val unresolved: List<String>)

  /** Call from a background thread inside a read action (ReadAction.nonBlocking / ReadAction.compute). */
  fun resolve(tokens: List<MentionToken>, selection: ContextRef.Selection?): Result {
    val refs = ArrayList<ContextRef>()
    val unresolved = ArrayList<String>()
    for (token in tokens) {
      val resolved: List<ContextRef> = when (token) {
        is MentionToken.Path -> listOfNotNull(resolvePath(token.path, directoryOnly = false))
        is MentionToken.Folder -> listOfNotNull(resolvePath(token.path, directoryOnly = true))
        is MentionToken.Selection -> listOfNotNull(selection)
        is MentionToken.Workspace -> listOfNotNull(baseDir()?.let { ContextRef.Folder(it) })
        is MentionToken.Recent -> recentFiles()
        is MentionToken.Agent -> agentFiles()
        is MentionToken.Symbol -> listOfNotNull(resolveSymbol(token.name))
      }
      if (resolved.isEmpty()) unresolved.add(token.raw) else refs.addAll(resolved)
    }
    return Result(refs.distinctBy { it.key }, unresolved)
  }

  private fun baseDir(): VirtualFile? =
    project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.takeIf { it.isDirectory }

  private fun resolvePath(path: String, directoryOnly: Boolean): ContextRef? {
    val byPath = findByPath(path) ?: findByName(path)?.takeIf { !directoryOnly } ?: return null
    return when {
      byPath.isDirectory -> ContextRef.Folder(byPath)
      directoryOnly -> null
      else -> ContextRef.File(byPath)
    }
  }

  private fun findByPath(path: String): VirtualFile? {
    val fs = LocalFileSystem.getInstance()
    if (File(path).isAbsolute) return fs.findFileByPath(path)
    val base = project.basePath ?: return null
    return fs.findFileByPath("$base/$path")
  }

  /** Bare file name (no '/'): unique index match, or the shortest path among several. */
  private fun findByName(name: String): VirtualFile? {
    if (name.contains('/') || DumbService.isDumb(project)) return null
    return try {
      FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        .filter { it.isValid && !it.isDirectory }
        .minByOrNull { it.path.length }
    }
    catch (e: IndexNotReadyException) {
      null
    }
  }

  /** Same list the `@` menu shows under «Недавние» — one source of truth. */
  private fun recentFiles(): List<ContextRef> = MentionIndex(project).recent(RECENT_LIMIT).map { it.ref }

  private fun agentFiles(): List<ContextRef> {
    val base = baseDir() ?: return emptyList()
    return AGENT_FILES.mapNotNull { base.findFileByRelativePath(it) }
      .filter { !it.isDirectory }
      .map { ContextRef.File(it) }
  }

  private fun resolveSymbol(name: String): ContextRef.Selection? {
    if (DumbService.isDumb(project)) return null
    val disposable = Disposer.newDisposable("MentionResolver.symbol")
    return try {
      val model = GotoSymbolModel2(project, disposable)
      model.getElementsByName(name, false, name)
        .asSequence()
        .filterIsInstance<PsiElement>()
        .mapNotNull { selectionOf(it) }
        .firstOrNull()
    }
    catch (e: IndexNotReadyException) {
      null
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Exception) {
      LOG.debug("Symbol lookup failed for '$name'", e)
      null
    }
    finally {
      Disposer.dispose(disposable)
    }
  }

  private fun selectionOf(element: PsiElement): ContextRef.Selection? {
    if (!element.isValid) return null
    val file = element.containingFile?.virtualFile ?: return null
    val range = element.textRange ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    if (range.endOffset > document.textLength) return null
    val fromLine = document.getLineNumber(range.startOffset)
    val toLine = document.getLineNumber(maxOf(range.startOffset, range.endOffset - 1))
    return ContextRef.Selection(file, fromLine + 1, toLine + 1, document.getText(range))
  }

  companion object {
    private val LOG = logger<MentionResolver>()
    const val RECENT_LIMIT = 5
    private val AGENT_FILES = listOf("AGENTS.md", ".vibe/rules.md")
  }
}
