// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ToolTipManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tabs of the chat as real components, headless
 *
 * The cross lies over the tab's edge and takes no room
 * A cross that took room would grow the tab under the pointer and push every tab to its right away from it
 * So what is measured is the tab's width with and without the pointer on it
 */
class ChatTabsStripTest {
  private class Recorder : ChatTabsStrip.Callbacks {
    val selected = mutableListOf<String>()
    val closed = mutableListOf<String>()
    override fun onSelect(threadId: String) { selected += threadId }
    override fun onClose(threadId: String) { closed += threadId }
    override fun onNewChat() {}
    override fun onToggleRail() {}
  }

  private fun descendants(root: Container): List<Component> =
    root.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList()) }

  private fun strip(count: Int, recorder: Recorder = Recorder()): Pair<ChatTabsStrip, List<JPanel>> {
    val strip = ChatTabsStrip(recorder)
    val infos = (1..count).map { ChatTabsStrip.TabInfo("t$it", "Chat number $it", "") }
    strip.update(infos, activeId = "t1", railOpen = false)
    val tabs = descendants(strip).filterIsInstance<JPanel>().filter { it.javaClass.simpleName == "Tab" }
    assertEquals(count, tabs.size)
    // A tooltip's timer would outlive the test, and the platform fails a test that leaves a Swing timer running
    descendants(strip).filterIsInstance<JComponent>().forEach { ToolTipManager.sharedInstance().unregisterComponent(it) }
    tabs.forEach { it.size = it.preferredSize }
    return strip to tabs
  }

  private fun cross(tab: JPanel): JLabel = tab.components.filterIsInstance<JLabel>().single { it.javaClass.simpleName == "CloseMask" }

  private fun mouse(tab: Component, id: Int, button: Int = MouseEvent.NOBUTTON, x: Int = 5) =
    MouseEvent(tab, id, System.currentTimeMillis(), 0, x, 5, 1, false, button)

  @Test
  fun `the pointer on a tab shows the cross and does not change the tab's width`() {
    val (_, tabs) = strip(3)
    val tab = tabs[1]
    val before = tab.preferredSize.width
    assertFalse(cross(tab).isVisible)
    tab.dispatchEvent(mouse(tab, MouseEvent.MOUSE_ENTERED))
    assertTrue(cross(tab).isVisible, "the cross shows under the pointer")
    assertEquals(before, tab.preferredSize.width, "the tab must keep its width")
    tab.dispatchEvent(mouse(tab, MouseEvent.MOUSE_EXITED, x = -5))
    assertFalse(cross(tab).isVisible)
    assertEquals(before, tab.preferredSize.width)
  }

  @Test
  fun `the cross lies over the tab's right edge`() {
    val (_, tabs) = strip(2)
    val tab = tabs[0]
    tab.size = tab.preferredSize
    tab.doLayout()
    val cross = cross(tab)
    assertTrue(cross.x + cross.width <= tab.width, "the cross stays inside the tab")
    assertTrue(cross.x + cross.width >= tab.width - 10, "the cross sits at the right edge, got ${cross.x + cross.width} of ${tab.width}")
    val title = tab.components.filterIsInstance<JLabel>().single { it !== cross }
    assertTrue(cross.x < title.x + title.width, "the cross overlaps the label's end, it does not push it")
  }

  @Test
  fun `the only tab never shows a cross`() {
    val (_, tabs) = strip(1)
    tabs.single().dispatchEvent(mouse(tabs.single(), MouseEvent.MOUSE_ENTERED))
    assertFalse(cross(tabs.single()).isVisible)
  }

  @Test
  fun `delete on the focused tab closes it`() {
    val recorder = Recorder()
    val (_, tabs) = strip(3, recorder)
    val tab = tabs[1]
    val key = assertNotNull(tab.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)))
    tab.actionMap.get(key).actionPerformed(ActionEvent(tab, ActionEvent.ACTION_PERFORMED, ""))
    assertEquals(listOf("t2"), recorder.closed)
  }

  @Test
  fun `backspace closes it too`() {
    val recorder = Recorder()
    val (_, tabs) = strip(2, recorder)
    val tab = tabs[0]
    val key = assertNotNull(tab.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0)))
    tab.actionMap.get(key).actionPerformed(ActionEvent(tab, ActionEvent.ACTION_PERFORMED, ""))
    assertEquals(listOf("t1"), recorder.closed)
  }

  @Test
  fun `the only tab ignores delete`() {
    val recorder = Recorder()
    val (_, tabs) = strip(1, recorder)
    val tab = tabs.single()
    val key = tab.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0))
    tab.actionMap.get(key).actionPerformed(ActionEvent(tab, ActionEvent.ACTION_PERFORMED, ""))
    assertTrue(recorder.closed.isEmpty())
  }

  @Test
  fun `the middle button closes a tab without selecting it`() {
    val recorder = Recorder()
    val (_, tabs) = strip(3, recorder)
    val tab = tabs[2]
    tab.dispatchEvent(mouse(tab, MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON2))
    assertEquals(listOf("t3"), recorder.closed)
    assertTrue(recorder.selected.isEmpty())
  }

  @Test
  fun `the left button selects a tab and does not close it`() {
    val recorder = Recorder()
    val (_, tabs) = strip(3, recorder)
    val tab = tabs[2]
    tab.dispatchEvent(mouse(tab, MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1))
    tab.dispatchEvent(mouse(tab, MouseEvent.MOUSE_CLICKED, MouseEvent.BUTTON1))
    assertEquals(listOf("t3"), recorder.selected)
    assertTrue(recorder.closed.isEmpty())
  }

  @Test
  fun `a tab is as wide as its label plus padding, with or without the cross`() {
    val (_, tabs) = strip(2)
    val tab = tabs[0]
    val label = tab.components.filterIsInstance<JLabel>().single { it.javaClass.simpleName != "CloseMask" }
    val insets = tab.insets
    assertTrue(tab.preferredSize.width >= label.preferredSize.width + insets.left + insets.right)
    assertTrue(tab.preferredSize.width < label.preferredSize.width + insets.left + insets.right + 24,
               "no room is reserved for the cross")
  }
}
