// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp.nav

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.redhat.devtools.lsp4ij.LSPIJUtils

/**
 * Where a language server's go-to-declaration leads: a place in a file that has no PSI of its own.
 *
 * It also keeps where the question was asked. The Ctrl+hover hint shows what the server says about the symbol under
 * the mouse, and the element the platform hands to the hint provider next to this target is a lexer token of a
 * TextMate file, whose start is not necessarily the position asked about.
 */
internal class LspTarget private constructor(
  private val file: PsiFile,
  private val range: TextRange,
  private val displayName: String,
  private val line: Int,
  val source: PsiFile,
  val sourceOffset: Int,
) : FakePsiElement() {
  override fun getParent(): PsiElement = file

  override fun getContainingFile(): PsiFile = file

  override fun getName(): String = displayName

  // The target's own place, not the inherited zero: the platform previews a target by it, and zero is the top of
  // the file.
  override fun getTextOffset(): Int = range.startOffset

  override fun getTextRange(): TextRange = range

  // Several targets are listed by name and location; without the line, overloads in one file look identical.
  override fun getLocationString(): String = "${file.name}:${line + 1}"

  override fun canNavigate(): Boolean = file.virtualFile?.isValid == true

  override fun navigate(requestFocus: Boolean) {
    val virtual = file.virtualFile ?: return
    OpenFileDescriptor(project, virtual, range.startOffset).navigate(requestFocus)
  }

  companion object {
    /**
     * The target for a place a server named, asked about from [sourceOffset] in [source]; null when the target file is
     * gone or has no text. Call in a read action.
     */
    fun of(project: Project, place: LspQueries.Place, source: PsiFile, sourceOffset: Int): LspTarget? {
      if (!place.file.isValid) return null
      val file = PsiManager.getInstance(project).findFile(place.file) ?: return null
      val document = FileDocumentManager.getInstance().getDocument(place.file) ?: return null
      if (place.line >= document.lineCount) return null
      val start = LSPIJUtils.toOffset(place.line, place.character, document)
      val end = if (place.endLine < document.lineCount) LSPIJUtils.toOffset(place.endLine, place.endCharacter, document) else start
      val declared = declaredName(document.charsSequence, start, end)
      // A range over the whole declaration names nothing by itself; the word the person pointed at is the same
      // name in all but a renamed import.
      val name = declared
                 ?: source.viewProvider.document?.let { wordAround(it.charsSequence, sourceOffset) }?.takeIf { it.isNotEmpty() }
                 ?: place.file.name
      return LspTarget(file, TextRange(start, start + (declared?.length ?: 0)), name, place.line, source, sourceOffset)
    }

    /**
     * The name a server's range points at: the covered text when it is one identifier, the identifier starting there
     * when the range is empty, and null when the range spans more than a name — some servers give the whole
     * declaration, `export function` included.
     */
    fun declaredName(text: CharSequence, start: Int, end: Int): String? {
      if (start !in 0..text.length) return null
      if (end == start) return wordFrom(text, start).takeIf { it.isNotEmpty() }
      if (end !in start..text.length) return null
      val covered = text.subSequence(start, end)
      return covered.toString().takeIf { covered.all(::isNamePart) }
    }

    /** The identifier around [offset]: the mouse can rest anywhere inside a word. */
    fun wordAround(text: CharSequence, offset: Int): String {
      if (offset !in 0..text.length) return ""
      var start = offset
      while (start > 0 && isNamePart(text[start - 1])) start--
      return wordFrom(text, start)
    }

    private fun wordFrom(text: CharSequence, start: Int): String {
      var stop = start
      while (stop < text.length && isNamePart(text[stop])) stop++
      return text.subSequence(start, stop).toString()
    }

    // `$` belongs to the name in JavaScript identifiers and in PHP variables alike.
    private fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
  }
}
