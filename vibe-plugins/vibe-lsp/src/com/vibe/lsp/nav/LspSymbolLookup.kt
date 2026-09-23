// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import com.vibe.agent.graph.SymbolTrace
import com.vibe.agent.mcp.VibeSymbolLookup
import java.util.concurrent.TimeUnit

/**
 * The agent's trace asks the language server where a name is declared.
 *
 * The server resolves imports, re-exports and aliases the way the compiler does, which no reading of the text can;
 * the tool's own text reading stays as the fallback and is told why it is one.
 */
class LspSymbolLookup : VibeSymbolLookup {
  override fun lookup(project: Project, path: String, name: String): VibeSymbolLookup.Result {
    val virtual = LocalFileSystem.getInstance().findFileByPath(path) ?: return missed(VibeSymbolLookup.Reason.NOT_SERVED)
    if (!LanguageServersRegistry.getInstance().isFileSupported(virtual, project)) return missed(VibeSymbolLookup.Reason.NOT_SERVED)
    val source = ReadAction.compute<Source?, RuntimeException> {
      val file = PsiManager.getInstance(project).findFile(virtual) ?: return@compute null
      val document = FileDocumentManager.getInstance().getDocument(virtual) ?: return@compute null
      Source(file, document, SymbolTrace.occurrences(document.charsSequence, name).take(PROBES))
    } ?: return missed(VibeSymbolLookup.Reason.NOT_SERVED)
    if (source.offsets.isEmpty()) return missed(VibeSymbolLookup.Reason.NOT_IN_FILE)

    val queries = LspQueries.of(project)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS)
    var answered = false
    for (offset in source.offsets) {
      val asked = ReadAction.compute<LspQueries.Asked<List<LspQueries.Place>>, RuntimeException> {
        queries.askDefinitions(source.file, source.document, offset)
      }
      val known = LspQueries.await(asked, remainingMs(deadline)) ?: break
      answered = true
      val places = known.value.orEmpty()
      if (places.isEmpty()) continue
      // The signature is asked where the name was resolved: the server describes the symbol as used there.
      val hover = ReadAction.compute<LspQueries.Asked<LspQueries.HoverAnswer>, RuntimeException> {
        queries.askHover(source.file, source.document, offset)
      }
      val signature = LspQueries.await(hover, remainingMs(deadline))?.value?.signature
      return VibeSymbolLookup.Result.Found(places.map { VibeSymbolLookup.Place(it.file.path, it.line + 1, it.server) }, signature)
    }
    return missed(if (answered) VibeSymbolLookup.Reason.NOTHING_FOUND else VibeSymbolLookup.Reason.NO_ANSWER)
  }

  private class Source(val file: PsiFile, val document: Document, val offsets: List<Int>)

  private fun missed(reason: VibeSymbolLookup.Reason) = VibeSymbolLookup.Result.Missed(reason)

  private fun remainingMs(deadline: Long): Long = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)

  companion object {
    /**
     * How long the trace waits for the server, all questions together. The agent's tool has no one watching a
     * spinner, but a cold server loading a large project takes seconds; past this bound the text reading answers and
     * says the server was not ready.
     */
    const val WAIT_MS = 5_000L

    /** Occurrences of the name tried before giving up: the first few are imports and uses, the rest repeat them. */
    const val PROBES = 5
  }
}
