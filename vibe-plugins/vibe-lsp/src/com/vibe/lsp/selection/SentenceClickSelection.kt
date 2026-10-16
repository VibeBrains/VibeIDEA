// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.selection

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/**
 * The fourth click of the mouse selects the sentence: two clicks select a word, three a line, four a sentence.
 *
 * The platform treats the fourth click like the third and selects the line again, so the press is taken before
 * the editor handles it. The listener sits on every editor the platform creates, diff viewers included.
 */
class SentenceClickSelection : EditorFactoryListener {
  override fun editorCreated(event: EditorFactoryEvent) {
    event.editor.addEditorMouseListener(Listener)
  }

  private object Listener : EditorMouseListener {
    override fun mousePressed(event: EditorMouseEvent) {
      val mouse = event.mouseEvent
      if (!isSentenceClick(mouse.clickCount, mouse.button, mouse.modifiersEx, event.area)) return
      if (select(event.editor, event.offset)) event.consume()
    }
  }

  companion object {
    /** The click that selects a sentence. */
    const val SENTENCE_CLICK_COUNT = 4

    private const val ANY_MODIFIER = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
      InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK

    /** A plain left click in the text: a click with a modifier belongs to the platform (multi-caret, box selection). */
    fun isSentenceClick(clickCount: Int, button: Int, modifiersEx: Int, area: EditorMouseEventArea?): Boolean =
      clickCount == SENTENCE_CLICK_COUNT && button == MouseEvent.BUTTON1 &&
        modifiersEx and ANY_MODIFIER == 0 && area == EditorMouseEventArea.EDITING_AREA

    /** Selects the sentence around [offset]; false when there is none and the platform should handle the click. */
    fun select(editor: Editor, offset: Int): Boolean {
      val span = SentenceBounds.find(editor.document.charsSequence, offset) ?: return false
      editor.caretModel.moveToOffset(span.end)
      editor.selectionModel.setSelection(span.start, span.end)
      return true
    }
  }
}
