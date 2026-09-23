// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPDefinitionFeature

/**
 * The switch between LSP4IJ's go-to-declaration and ours, and what each of them may ask the servers.
 *
 * The platform takes the targets of the FIRST go-to handler that returns any. LSP4IJ's handler and ours are both
 * registered first, and their relative order is not promised — with both active, which of them navigates is a coin
 * toss, not a fallback. So while ours is on, theirs is switched off by hiding the definition feature from LSP4IJ.
 *
 * Hiding it hides it from every request made through LSP4IJ's definition support — ours included, had we asked
 * through it. That is why [LspQueries] asks the servers directly and checks the capability with
 * [serverAnswersDefinitions], which sees past the switch.
 */
object PreciseNavigation {
  /**
   * The switch. Off by default: replacing the navigation changes what a person gets under the cursor in every file,
   * and only a person at the keyboard can check that; the key turns it back without rebuilding the IDE.
   */
  const val REGISTRY_KEY = "vibe.lsp.precise.navigation"

  /**
   * How long our go-to waits for the servers. Cmd+B waits under a modal progress holding a read lock, and every write
   * waits with it — a hung server must not hold it longer than a person waits for a click. A warm server answers in
   * tens of milliseconds; the unanswered question stays in flight, and the next Cmd+B gets its answer.
   */
  const val WAIT_MS = 2_000L

  fun isEnabled(): Boolean = Registry.`is`(REGISTRY_KEY, false)

  /** Client features in which LSP4IJ's go-to is off while ours is on. */
  fun install(features: LSPClientFeatures): LSPClientFeatures = features.setDefinitionFeature(Definitions())

  /** Ready features for a factory that needs nothing else. */
  fun features(): LSPClientFeatures = install(LSPClientFeatures())

  /** Whether the server itself answers `textDocument/definition`, whatever LSP4IJ is told. */
  fun serverAnswersDefinitions(features: LSPClientFeatures, file: PsiFile): Boolean =
    when (val definitions = features.definitionFeature) {
      is Definitions -> definitions.serverAnswers(file)
      else -> definitions.isDefinitionSupported(file)
    }

  private class Definitions : LSPDefinitionFeature() {
    override fun isDefinitionSupported(file: PsiFile): Boolean = !isEnabled() && super.isDefinitionSupported(file)

    fun serverAnswers(file: PsiFile): Boolean = super.isDefinitionSupported(file)
  }
}
