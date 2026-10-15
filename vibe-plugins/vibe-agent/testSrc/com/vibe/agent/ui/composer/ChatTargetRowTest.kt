// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.icons.AllIcons
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.providers.ModelEntry
import com.vibe.agent.providers.ProviderEntry
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hand-declared model carries a pencil and names its file in the tooltip; a catalog model carries neither
 * Measured on the labels the row paints and on the tooltip the list shows, not on the strings the row is built from
 */
class ChatTargetRowTest {
  private val provider = ProviderEntry(id = "minimax", name = "MiniMax")
  private val path = "/project/.vibe/providers/minimax.jsonc"

  private fun declared(sourcePath: String?, static: Boolean) =
    ChatTarget.Model(provider, ModelEntry(id = "m1", name = "M1", sourcePath = sourcePath), static)

  private fun row(target: ChatTarget): JComponent =
    ChatTargetRenderer(selectedId = null) { providerFileLabel(it, "/project", "/home/u") }
      .getListCellRendererComponent(JList<ChatTarget>(), target, 0, false, false) as JComponent

  private fun labels(container: Container): List<JLabel> = container.components.flatMap { child ->
    listOfNotNull(child as? JLabel) + ((child as? Container)?.let { labels(it) } ?: emptyList())
  }

  @Test
  fun `a hand-declared model shows a pencil and names its file in the tooltip`() {
    val row = row(declared(path, static = true))
    assertTrue(labels(row).any { it.icon == AllIcons.Actions.Edit }, "no pencil on a hand-declared model")
    assertEquals(t("picker.target.manual.tooltip", "path" to ".vibe/providers/minimax.jsonc"), row.toolTipText)
  }

  @Test
  fun `a hand-declared model with no known file says so without a path`() {
    val row = row(declared(null, static = true))
    assertTrue(labels(row).any { it.icon == AllIcons.Actions.Edit })
    assertEquals(t("picker.target.manual.tooltipNoPath"), row.toolTipText)
  }

  @Test
  fun `a model from the live catalog has no pencil and no tooltip`() {
    val row = row(declared(path, static = false))
    assertFalse(labels(row).any { it.icon == AllIcons.Actions.Edit }, "pencil on a catalog model")
    assertNull(row.toolTipText)
  }

  @Test
  fun `the word custom is no longer painted in the row`() {
    val word = t("picker.target.custom")
    val row = row(declared(path, static = true))
    assertTrue(labels(row).none { it.text.orEmpty().contains(word) }, "the custom mark is still in the text")
  }

  @Test
  fun `a providers file is named from the project root, from home, or kept whole`() {
    assertEquals(".vibe/providers/minimax.jsonc", providerFileLabel(path, "/project", "/home/u"))
    assertEquals("~/.vibe/providers/minimax.jsonc", providerFileLabel("/home/u/.vibe/providers/minimax.jsonc", "/project", "/home/u"))
    assertEquals("/srv/providers.json", providerFileLabel("/srv/providers.json", null, "/home/u"))
  }
}
