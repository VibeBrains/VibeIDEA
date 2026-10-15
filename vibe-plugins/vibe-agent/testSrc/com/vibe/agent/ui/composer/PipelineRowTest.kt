// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui.composer

import com.intellij.util.ui.JBUI
import com.vibe.agent.i18n.VibeI18n.t
import com.vibe.agent.pipelines.Pipeline
import com.vibe.agent.pipelines.PipelineChain
import com.vibe.agent.pipelines.PipelineStep
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
 * The pipeline menu rows: the name and the number of steps, the chain under them, the way to the file at the bottom
 * Measured on the labels the row paints and on the tooltip the list shows, not on the strings the row is built from
 */
class PipelineRowTest {
  private fun step(role: String, wave: String? = null, model: String? = null) =
    PipelineStep(role = role, task = "t", wave = wave, model = model)

  private val review = Pipeline(
    id = "review", name = "Review and security", description = "Two views at once <then> a summary",
    steps = listOf(step("code-reviewer", "w"), step("security", "w"), step("critic", model = "claude-fable-5-1")),
  )

  private fun render(renderer: PipelineRowRenderer, choice: PipelineChoice, selected: Boolean = false): JComponent =
    renderer.getListCellRendererComponent(JList<PipelineChoice>(), choice, 0, selected, false) as JComponent

  private fun labels(container: Container): List<JLabel> = container.components.flatMap { child ->
    listOfNotNull(child as? JLabel) + ((child as? Container)?.let { labels(it) } ?: emptyList())
  }

  @Test
  fun `a pipeline row shows the name, the number of steps and the chain`() {
    val renderer = PipelineRowRenderer()
    val row = render(renderer, PipelineChoice.Run(review))
    val texts = labels(row).map { it.text.orEmpty() }
    assertTrue("Review and security" in texts, "the name is not painted: $texts")
    assertTrue("3" in texts, "the number of steps is not painted: $texts")
    assertEquals("code-reviewer ∥ security → critic (claude-fable-5-1)", PipelineChain.text(review.steps))
    assertEquals(PipelineChain.tokens(review.steps), renderer.chain.tokens)
  }

  @Test
  fun `no row reserves a column of icons on the left`() {
    val renderer = PipelineRowRenderer()
    listOf(PipelineChoice.Run(review), PipelineChoice.OpenFile).forEach { choice ->
      assertTrue(labels(render(renderer, choice)).all { it.icon == null }, "an icon in the row of $choice")
    }
  }

  @Test
  fun `the tooltip carries the description and the whole chain, escaped`() {
    val tip = render(PipelineRowRenderer(), PipelineChoice.Run(review)).toolTipText.orEmpty()
    assertTrue("Two views at once &lt;then&gt; a summary" in tip, tip)
    assertTrue("code-reviewer ∥ security → critic (claude-fable-5-1)" in tip, tip)
  }

  @Test
  fun `a pipeline without a description has the chain alone in the tooltip`() {
    val tip = pipelineTooltip(review.copy(description = null))
    assertFalse("<br>" in tip, tip)
    assertTrue("code-reviewer" in tip, tip)
  }

  @Test
  fun `the last row opens the file and has no tooltip`() {
    val row = render(PipelineRowRenderer(), PipelineChoice.OpenFile)
    assertEquals(listOf(t("pipeline.picker.open")), labels(row).map { it.text })
    assertNull(row.toolTipText)
  }

  @Test
  fun `a long name and a long chain do not make the row wider than its cap`() {
    val long = Pipeline(
      id = "long", name = "A very long name of a pipeline ".repeat(8),
      steps = List(20) { step("backend-dev", model = "some-very-long-model-name-$it") },
    )
    val row = render(PipelineRowRenderer(), PipelineChoice.Run(long))
    assertTrue(row.preferredSize.width <= JBUI.scale(440), "the row asks for ${row.preferredSize.width}")
  }

  @Test
  fun `a chain that does not fit ends with an ellipsis and stays within the width`() {
    val renderer = PipelineRowRenderer()
    render(renderer, PipelineChoice.Run(review))
    val full = PipelineChain.text(review.steps)
    assertEquals(full, renderer.chain.shownText(10_000))
    val narrow = renderer.chain.shownText(JBUI.scale(120))
    assertTrue(narrow.endsWith("…"), narrow)
    assertTrue(narrow.length < full.length, narrow)
    assertTrue(renderer.chain.getFontMetrics(JBUI.Fonts.smallFont()).stringWidth(narrow) <= JBUI.scale(120) + 1, narrow)
  }
}
