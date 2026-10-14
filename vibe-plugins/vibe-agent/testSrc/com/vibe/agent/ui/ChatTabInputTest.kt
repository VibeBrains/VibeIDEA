// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What closes a chat tab, and when its × shows
 *
 * The strip never leaves the chat without a thread, so the single tab has no way to close at all
 */
class ChatTabInputTest {
  @Test
  fun `a tab can be closed only while there is another one`() {
    assertFalse(ChatTabInput.closable(0))
    assertFalse(ChatTabInput.closable(1))
    assertTrue(ChatTabInput.closable(2))
  }

  @Test
  fun `delete and backspace close the focused tab`() {
    assertContentEquals(intArrayOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE), ChatTabInput.CLOSE_KEYS)
  }

  @Test
  fun `the middle button closes a tab and the others do not`() {
    assertTrue(ChatTabInput.closeByMouse(MouseEvent.BUTTON2, closable = true))
    assertFalse(ChatTabInput.closeByMouse(MouseEvent.BUTTON1, closable = true))
    assertFalse(ChatTabInput.closeByMouse(MouseEvent.BUTTON3, closable = true))
  }

  @Test
  fun `the only tab ignores the middle button too`() {
    assertFalse(ChatTabInput.closeByMouse(MouseEvent.BUTTON2, closable = false))
  }

  @Test
  fun `the cross shows under the pointer and on the focused tab, never on the only tab`() {
    assertFalse(ChatTabInput.closeVisible(hover = false, focused = false, closable = true))
    assertTrue(ChatTabInput.closeVisible(hover = true, focused = false, closable = true))
    assertTrue(ChatTabInput.closeVisible(hover = false, focused = true, closable = true))
    assertFalse(ChatTabInput.closeVisible(hover = true, focused = true, closable = false))
  }

  @Test
  fun `a closed tab hands the focus to its left neighbour, the first tab to the right one`() {
    val ids = listOf("a", "b", "c")
    assertEquals("b", ChatTabInput.neighbourAfterClose(ids, "a"))
    assertEquals("a", ChatTabInput.neighbourAfterClose(ids, "b"))
    assertEquals("b", ChatTabInput.neighbourAfterClose(ids, "c"))
  }

  @Test
  fun `a tab that is not in the strip has no neighbour`() {
    assertNull(ChatTabInput.neighbourAfterClose(listOf("a", "b"), "x"))
    assertNull(ChatTabInput.neighbourAfterClose(listOf("a"), "a"))
  }
}
