// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.selection

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.codeInsight.editorActions.SelectWordUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.redhat.devtools.lsp4ij.features.selectionRange.LSPSelectionRangeSupport

/**
 * The platform's own word, offered next to the ranges a language server gives for "Extend Selection".
 *
 * While a server answers `textDocument/selectionRange`, LSP4IJ switches the platform word selection off for the file
 * and takes every step from the server. The server's smallest range at a position inside a template string is the
 * whole literal, so a double-click selected the string instead of the word.
 * The selection picks the smallest range that grows the current one, so this word range comes first
 * and the server's ranges follow as the next steps.
 */
class WordBeforeServerRanges : ExtendWordSelectionHandlerBase() {
  override fun canSelect(e: PsiElement): Boolean {
    val file = e.containingFile ?: return false
    return LSPSelectionRangeSupport.isSelectionRangesAvailable(file)
  }

  override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> =
    wordRanges(editor, cursorOffset)

  companion object {
    /** The word (or the camel hump, as the editor settings say) at [cursorOffset], as the editor selects it. */
    fun wordRanges(editor: Editor, cursorOffset: Int): List<TextRange> {
      val ranges = ArrayList<TextRange>()
      SelectWordUtil.addWordOrLexemeSelection(editor.settings.isCamelWords, editor, cursorOffset, ranges)
      return ranges
    }
  }
}
