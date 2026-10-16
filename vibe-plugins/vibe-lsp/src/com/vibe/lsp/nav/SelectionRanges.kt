// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPSelectionRangeFeature

/**
 * Where LSP4IJ may ask a server for `textDocument/selectionRange`.
 *
 * Double-click selects a word through the extend-selection handlers. While a server answers selection ranges,
 * LSP4IJ also switches the platform's own word selection off for that file, so the words come from the server alone.
 * A diff viewer shows file revisions as light in-memory copies with the real file's name, so they match the server's
 * file patterns. The server knows the file only by its name and answers for the text on disk, and its ranges land on
 * the wrong text of the revision: a double-click then selects a whole changed block instead of the word.
 *
 * Only a file that lives on disk is the text the server has opened, so only there the server's ranges mean anything.
 */
object SelectionRanges {
  /** Whether the server and the editor look at the same text of this file. */
  fun sharesTextWithServer(file: VirtualFile): Boolean = file.isInLocalFileSystem

  /** The feature for client features of a server: asks the server only about files on disk. */
  fun feature(): LSPSelectionRangeFeature = OnDiskOnly()

  private class OnDiskOnly : LSPSelectionRangeFeature() {
    override fun isSelectionRangeSupported(file: PsiFile): Boolean {
      val virtualFile = file.originalFile.virtualFile ?: return false
      return sharesTextWithServer(virtualFile) && super.isSelectionRangeSupported(file)
    }
  }
}
