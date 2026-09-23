// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Go-to-declaration from the language servers, in place of LSP4IJ's while [PreciseNavigation] is on.
 *
 * What it adds over theirs: an answer per position is remembered, so the mouse crossing a word over and over asks the
 * server once; the wait is bounded, so a hung server does not hold Cmd+B; and the target keeps where it was asked
 * from, so the Ctrl+hover hint can show the signature ([LspSignatureDocumentation]).
 *
 * The platform calls this off the UI thread — in a background read action for Ctrl+hover, which the next mouse move
 * cancels, and under a modal progress for Cmd+B — so waiting for the server here is safe.
 */
class VibeLspGotoDeclarationHandler : GotoDeclarationHandler {
  override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
    if (!PreciseNavigation.isEnabled()) return null
    val project = editor.project ?: return null
    val document = editor.document
    val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
    val known = LspQueries.await(LspQueries.of(project).askDefinitions(file, document, offset), PreciseNavigation.WAIT_MS)
                ?: return null
    val targets: List<PsiElement> = known.value.orEmpty().mapNotNull { LspTarget.of(project, it, file, offset) }
    return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
  }
}
