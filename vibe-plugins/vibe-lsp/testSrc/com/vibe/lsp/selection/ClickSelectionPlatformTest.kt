// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.lsp.selection

import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/** Click selection on a live editor: the word inside a template literal and the sentence of the fourth click. */
class ClickSelectionPlatformTest : BasePlatformTestCase() {
  private val literal = "const q = `(drPurpose is not null and drOperationId = '\${operationId}' and status = 'end')`; ok."

  fun `test слово внутри шаблонной строки — слово, а не вся строка`() {
    myFixture.configureByText("pricing.ts", literal)
    val offset = literal.indexOf("operationId") + 4
    val range = WordBeforeServerRanges.wordRanges(myFixture.editor, offset).single()
    assertEquals("operationId", range.substring(literal))
  }

  fun `test четвёртый клик выделяет предложение в шаблонной строке и ставит курсор в его конец`() {
    myFixture.configureByText("pricing.ts", literal)
    val offset = literal.indexOf("operationId") + 4
    assertTrue(SentenceClickSelection.select(myFixture.editor, offset))
    val inner = "(drPurpose is not null and drOperationId = '\${operationId}' and status = 'end')"
    assertEquals(inner, myFixture.editor.selectionModel.selectedText)
    assertEquals(literal.indexOf(inner) + inner.length, myFixture.editor.caretModel.offset)
  }

  fun `test пустая строка — выделять нечего, клик остаётся платформе`() {
    myFixture.configureByText("empty.ts", "a\n\nb")
    assertFalse(SentenceClickSelection.select(myFixture.editor, 2))
  }

  fun `test только обычный четвёртый клик левой кнопкой в тексте`() {
    val area = EditorMouseEventArea.EDITING_AREA
    val left = MouseEvent.BUTTON1
    assertTrue(SentenceClickSelection.isSentenceClick(4, left, 0, area))
    assertFalse(SentenceClickSelection.isSentenceClick(3, left, 0, area))
    assertFalse(SentenceClickSelection.isSentenceClick(2, left, 0, area))
    assertFalse(SentenceClickSelection.isSentenceClick(4, MouseEvent.BUTTON3, 0, area))
    assertFalse(SentenceClickSelection.isSentenceClick(4, left, InputEvent.SHIFT_DOWN_MASK, area))
    assertFalse(SentenceClickSelection.isSentenceClick(4, left, 0, EditorMouseEventArea.LINE_NUMBERS_AREA))
  }
}
