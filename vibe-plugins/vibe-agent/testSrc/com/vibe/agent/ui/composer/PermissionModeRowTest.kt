// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.vibe.agent.mcp.PermissionMode
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The permission menu row shows the hint as a tooltip, not as text
 * Measured on the labels the row paints, not on the mode's own strings
 */
class PermissionModeRowTest {
  private fun row(mode: PermissionMode, current: PermissionMode = PermissionMode.AUTO): JComponent =
    PermissionModeRenderer(current).getListCellRendererComponent(JList<PermissionMode>(), mode, 0, false, false) as JComponent

  private fun labels(container: Container): List<JLabel> = container.components.flatMap { child ->
    listOfNotNull(child as? JLabel) + ((child as? Container)?.let { labels(it) } ?: emptyList())
  }

  @Test
  fun `every row shows the name and keeps the hint out of its text`() {
    PermissionMode.entries.forEach { mode ->
      val row = row(mode)
      assertEquals(mode.description, row.toolTipText, mode.name)
      val texts = labels(row).map { it.text.orEmpty() }
      assertTrue(mode.title in texts, "${mode.name}: the name is not painted")
      assertTrue(texts.none { it.contains(mode.description) }, "${mode.name}: the hint is painted in the row")
    }
  }

  @Test
  fun `the check marks the current mode and only it`() {
    PermissionMode.entries.forEach { current ->
      PermissionMode.entries.forEach { mode ->
        val marked = labels(row(mode, current)).any { it.icon == AllIcons.Actions.Checked }
        assertEquals(mode == current, marked, "current=${current.name}, row=${mode.name}")
      }
    }
  }
}
