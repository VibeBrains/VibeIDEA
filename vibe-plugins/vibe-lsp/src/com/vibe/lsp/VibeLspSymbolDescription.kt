// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.redhat.devtools.lsp4ij.features.LSPPsiElement

/**
 * What to call a symbol of a file a language server serves, wherever the platform names an element — usages, lists,
 * and the Ctrl+hover hint when the server has not said more.
 *
 * LSP4IJ answers `LSP Symbol` for a function, a type, a variable and an import alike, and a hint reading
 * `LSP Symbol "encodeURIComponent" [interceptor.ts]` says nothing beyond what is already on the screen. The language
 * of the file is known from the same mapping that picks its server, and «символ TypeScript» at least says whose
 * symbol it is.
 *
 * The signature itself is not here: it comes from the server by request, and a description is asked for in places
 * that cannot wait for one. It lives in the documentation target instead ([com.vibe.lsp.nav.LspSignatureDocumentation]),
 * which the Ctrl+hover hint asks first; this description is what remains when the server has not answered yet.
 *
 * Registered before LSP4IJ's provider (`order="first"`): theirs answers for everything, and a provider after it never
 * gets asked.
 */
class VibeLspSymbolDescription : ElementDescriptionProvider {
  override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
    if (element !is LSPPsiElement) return null
    return when (location) {
      is UsageViewTypeLocation -> typeOf(element)
      is UsageViewShortNameLocation, is UsageViewLongNameLocation, is UsageViewNodeTextLocation -> element.name
      else -> null
    }
  }

  /**
   * «Символ <язык>» по имени файла.
   *
   * Язык берётся у того же сопоставления, что выбирает сервер: два ответа об одном файле — «это
   * TypeScript» в навигации и «это PHP» в запуске сервера — разошлись бы в первый же день.
   */
  private fun typeOf(element: LSPPsiElement): String {
    val name = element.containingFile?.name.orEmpty()
    val spec = LspDoctor.serverFor(name, LspDoctor.ALL)
    return spec?.displayName?.let { com.vibe.agent.i18n.VibeI18n.t("lsp.symbolOf", "language" to it) }
           ?: com.vibe.agent.i18n.VibeI18n.t("lsp.symbol")
  }
}
