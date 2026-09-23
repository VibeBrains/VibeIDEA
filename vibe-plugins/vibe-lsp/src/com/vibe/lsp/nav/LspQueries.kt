// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.LSPIJUtils
import com.redhat.devtools.lsp4ij.LanguageServerItem
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper
import com.redhat.devtools.lsp4ij.usages.LocationData
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The questions our code asks the language servers about one position: where the symbol there is declared, and what
 * the server says about it on hover.
 *
 * One place for both, because the navigation, the Ctrl+hover hint and the agent's trace ask the same questions about
 * the same positions, and an answer fetched for one of them serves the others.
 *
 * The requests go to the servers directly, not through LSP4IJ's feature supports. Its definition support skips a
 * server whose definition feature is switched off — and switching it off is exactly how [PreciseNavigation] keeps
 * LSP4IJ's own go-to handler out of the way, so through the support our own questions would reach nobody. Its hover
 * support keeps its results in a package-private record, unreadable from here.
 *
 * Waiting happens off the UI thread only. The platform asks for go-to targets and for the Ctrl+hover hint in a
 * background read action that the next mouse move cancels, and Cmd+B asks under a modal progress; on the UI thread
 * only an answer already known is given.
 */
@Service(Service.Level.PROJECT)
class LspQueries(private val project: Project) {
  private val definitionAnswers = LspAnswerCache<List<Place>>()
  private val hoverAnswers = LspAnswerCache<HoverAnswer>()

  /** A declaration a server pointed to: the start and the end of its name, and the server that said so. */
  data class Place(
    val file: VirtualFile,
    val line: Int,
    val character: Int,
    val endLine: Int,
    val endCharacter: Int,
    val server: String,
  )

  /**
   * What a server says on hover.
   *
   * The server is kept because LSP4IJ renders hover markup through the server's own features, and the full
   * documentation is rendered the same way here.
   */
  class HoverAnswer(val signature: String?, val contents: List<MarkupContent>, val server: LanguageServerItem)

  /** A question: its answer when already known, otherwise the request in flight. */
  class Asked<V : Any>(val known: LspAnswerCache.Known<V>?, val flight: CompletableFuture<V?>?)

  /** Where the symbol at [offset] is declared. Call in a read action; wait with [await]. */
  fun askDefinitions(file: PsiFile, document: Document, offset: Int): Asked<List<Place>> =
    ask(definitionAnswers, file, document, offset) { virtual, position -> definitionsOf(file, virtual, position) }

  /** What the servers say about the symbol at [offset] on hover. Call in a read action; wait with [await]. */
  fun askHover(file: PsiFile, document: Document, offset: Int): Asked<HoverAnswer> =
    ask(hoverAnswers, file, document, offset) { virtual, position -> hoverOf(file, virtual, position) }

  private fun <V : Any> ask(
    answers: LspAnswerCache<V>,
    file: PsiFile,
    document: Document,
    offset: Int,
    send: (VirtualFile, Position) -> CompletableFuture<V?>,
  ): Asked<V> {
    val virtual = file.virtualFile ?: return Asked(null, null)
    val key = LspAnswerCache.Key(virtual.url, offset)
    val stamp = document.modificationStamp
    answers.known(key, stamp)?.let { return Asked(it, null) }
    val position = LSPIJUtils.toPosition(offset, document)
    return Asked(null, answers.request(key, stamp) { send(virtual, position) })
  }

  private fun definitionsOf(file: PsiFile, virtual: VirtualFile, position: Position): CompletableFuture<List<Place>?> =
    askServers(file, virtual,
               enabled = { it.definitionFeature.isEnabled(file) },
               supported = { PreciseNavigation.serverAnswersDefinitions(it, file) }) { server, document ->
      server.textDocumentService.definition(DefinitionParams(document, position))
    }.thenApply { answers ->
      // Two servers of one file often name the same declaration: vtsls and Angular both answer on `.ts`.
      answers.flatMap { (server, answer) -> LSPIJUtils.getLocations(answer, server) }
        .mapNotNull(::placeOf)
        .distinctBy { Triple(it.file, it.line, it.character) }
    }

  private fun hoverOf(file: PsiFile, virtual: VirtualFile, position: Position): CompletableFuture<HoverAnswer?> =
    askServers(file, virtual,
               enabled = { it.hoverFeature.isEnabled(file) },
               supported = { it.hoverFeature.isSupported(file) }) { server, document ->
      server.textDocumentService.hover(HoverParams(document, position))
    }.thenApply { answers -> answers.firstNotNullOfOrNull { (server, hover) -> answerOf(hover, server) } }

  /**
   * Asks every server of the file that can answer, in LSP4IJ's order, and collects whatever came back.
   *
   * All of them, because one file has several servers, and one that fails must not hide what the others said. The
   * document identifier carries each server's own URI form, as LSP4IJ does for its own requests.
   */
  private fun <R : Any> askServers(
    file: PsiFile,
    virtual: VirtualFile,
    enabled: (LSPClientFeatures) -> Boolean,
    supported: (LSPClientFeatures) -> Boolean,
    send: (LanguageServerItem, TextDocumentIdentifier) -> CompletableFuture<R>,
  ): CompletableFuture<List<Pair<LanguageServerItem, R>>> =
    LanguageServiceAccessor.getInstance(project)
      .getLanguageServers(file, { enabled(it) }, { supported(it) })
      .thenCompose { servers ->
        val asked = servers.map { server ->
          val document = TextDocumentIdentifier(FileUriSupport.toString(virtual, server.clientFeatures))
          send(server, document).handle { answer, error -> if (error == null && answer != null) server to answer else null }
        }
        CompletableFuture.allOf(*asked.toTypedArray()).thenApply { asked.mapNotNull { it.join() } }
      }

  private fun placeOf(data: LocationData): Place? {
    val location = data.location()
    // The server's URI, read by the server's own rules: it may percent-encode what the platform keeps as is.
    val file = FileUriSupport.findFileByUri(location.uri, data.languageServer().clientFeatures) ?: return null
    val range = location.range
    return Place(file, range.start.line, range.start.character, range.end.line, range.end.character,
                 data.languageServer().serverDefinition.displayName)
  }

  private fun answerOf(hover: Hover, server: LanguageServerItem): HoverAnswer? {
    val contents = LSPDocumentationHelper.getValidMarkupContents(hover)
    if (contents.isEmpty()) return null
    return HoverAnswer(HoverSignature.of(contents.map { it.value }), contents, server)
  }

  companion object {
    /**
     * How often a waiting thread looks up: cancellation of a Ctrl+hover must land within one mouse move, and the
     * platform's own LSP client checks just as often.
     */
    private const val POLL_MS = 10L

    fun of(project: Project): LspQueries = project.service()

    /**
     * The answer to [asked], waiting for it at most [waitMs] and giving way to cancellation.
     *
     * Null means «no answer yet», never «nothing there»: the request stays in flight and lands in the cache for the
     * next question. On the UI thread nothing is waited for — a server slow for a second would freeze the editor for
     * that second.
     */
    fun <V : Any> await(asked: Asked<V>, waitMs: Long): LspAnswerCache.Known<V>? {
      asked.known?.let { return it }
      val flight = asked.flight ?: return null
      if (waitMs <= 0 || ApplicationManager.getApplication().isDispatchThread) return null
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
      while (true) {
        ProgressManager.checkCanceled()
        try {
          return LspAnswerCache.Known(flight.get(POLL_MS, TimeUnit.MILLISECONDS))
        }
        catch (e: TimeoutException) {
          if (System.nanoTime() >= deadline) return null
        }
        // A failed request is «no answer», not «nothing here»: the server may simply not be up yet.
        catch (e: ExecutionException) {
          return null
        }
        catch (e: CancellationException) {
          return null
        }
      }
    }
  }
}
