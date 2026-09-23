// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.PsiDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.redhat.devtools.lsp4ij.features.LSPPsiElement
import com.redhat.devtools.lsp4ij.features.documentation.LSPDocumentationHelper

/**
 * The Ctrl+hover hint for a symbol a language server knows: its signature from `textDocument/hover`, in place of
 * «символ TypeScript (vtsls)».
 *
 * The platform builds that hint from the documentation target of the go-to target and asks this provider first. It
 * answers for our targets and for LSP4IJ's alike: with precise navigation off the targets are LSP4IJ's, and their
 * hint was just as bare.
 *
 * No answer within [HINT_WAIT_MS] means no target from here, and the platform shows its own hint; the request keeps
 * going, and the next hover over the word shows the signature.
 */
class LspSignatureDocumentation : PsiDocumentationTargetProvider {
  override fun documentationTargets(element: PsiElement, originalElement: PsiElement?): List<DocumentationTarget> {
    val (source, offset) = when (element) {
      is LspTarget -> element.source to element.sourceOffset
      is LSPPsiElement -> {
        val token = originalElement ?: return emptyList()
        val start = token.textRange?.startOffset ?: return emptyList()
        (token.containingFile ?: return emptyList()) to sourceOffset(start, token.text, element.name)
      }
      else -> return emptyList()
    }
    val document = PsiDocumentManager.getInstance(source.project).getDocument(source) ?: return emptyList()
    val answer = LspQueries.await(LspQueries.of(source.project).askHover(source, document, offset), HINT_WAIT_MS)
                   ?.value?.takeIf { it.signature != null }
                 ?: return emptyList()
    return listOf(SignatureTarget(answer, source, (element as? PsiNamedElement)?.name))
  }

  private class SignatureTarget(
    private val answer: LspQueries.HoverAnswer,
    private val file: PsiFile,
    private val name: String?,
  ) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name ?: file.name).presentation()

    override fun computeDocumentationHint(): String? = answer.signature?.let {
      DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(it) + DocumentationMarkup.DEFINITION_END
    }

    // The full answer, rendered the way LSP4IJ renders its own hover: this target also serves Quick Documentation
    // whenever the platform reaches it through the element rather than through LSP4IJ's own provider.
    override fun computeDocumentation(): DocumentationResult =
      DocumentationResult.documentation(LSPDocumentationHelper.convertToHtml(answer.contents, answer.server, file))
  }

  companion object {
    /**
     * How long the hint waits for the server — the bound the platform's own LSP client uses for hover. A Ctrl+hover
     * is cancelled by the next mouse move, but documentation can also be asked from an action update that cannot be
     * cancelled, and nothing tells the two apart.
     */
    const val HINT_WAIT_MS = 300L

    /**
     * Where to ask about the symbol when only the token under the mouse is known: at the target's name inside it.
     *
     * A lexer token of a TextMate file may be wider than the name (`this.sendMail` in some grammars), and its start
     * would then ask about `this`.
     */
    fun sourceOffset(tokenStart: Int, tokenText: String, name: String?): Int {
      val at = name?.takeIf { it.isNotEmpty() }?.let { tokenText.indexOf(it) } ?: -1
      return if (at >= 0) tokenStart + at else tokenStart
    }
  }
}
