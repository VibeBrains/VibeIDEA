// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.mcp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

/**
 * Where a name used in a file is declared, according to the language server that serves the file.
 *
 * An extension point rather than a call: the servers live in `vibe-lsp`, which depends on this plugin, and a call the
 * other way would be a cycle — the same split as [VibeIdeFacts].
 */
interface VibeSymbolLookup {
  /**
   * The declaration of [name] as used in the file at [path], or why the server could not tell.
   *
   * Blocks the calling thread up to the implementation's own bound; never call it on the UI thread.
   */
  fun lookup(project: Project, path: String, name: String): Result

  sealed interface Result {
    /** The server resolved the name: where it is declared, and the signature the server shows for it on hover. */
    data class Found(val places: List<Place>, val signature: String?) : Result

    /** No answer, and [reason] says why — the caller names it instead of guessing. */
    data class Missed(val reason: Reason) : Result
  }

  enum class Reason {
    /** No language server serves this kind of file. */
    NOT_SERVED,

    /** The name does not occur in the file as a whole word. */
    NOT_IN_FILE,

    /** The server did not answer in time — usually a project it is still loading. */
    NO_ANSWER,

    /** The server answered and knows no declaration of the name. */
    NOTHING_FOUND,
  }

  /** A declaration: absolute path, 1-based line, and the server that pointed to it. */
  data class Place(val path: String, val line: Int, val server: String)

  companion object {
    val EP: ExtensionPointName<VibeSymbolLookup> = ExtensionPointName.create("com.vibe.agent.symbolLookup")

    private val LOG = Logger.getInstance(VibeSymbolLookup::class.java)

    /**
     * The first lookup that resolved the name; otherwise the first reason other than «not served».
     *
     * No lookups at all — the language plugin disabled — means the file is not served.
     */
    fun lookup(project: Project, path: String, name: String): Result {
      var missed = Result.Missed(Reason.NOT_SERVED)
      for (source in EP.extensionList) {
        val result = try {
          source.lookup(project, path, name)
        }
        catch (e: ProcessCanceledException) {
          throw e
        }
        catch (e: Exception) {
          // One broken lookup must not take the whole trace down: the text reading still answers.
          LOG.warn("symbol lookup failed in ${source.javaClass.simpleName}", e)
          Result.Missed(Reason.NO_ANSWER)
        }
        when (result) {
          is Result.Found -> return result
          is Result.Missed -> if (missed.reason == Reason.NOT_SERVED) missed = result
        }
      }
      return missed
    }
  }
}
