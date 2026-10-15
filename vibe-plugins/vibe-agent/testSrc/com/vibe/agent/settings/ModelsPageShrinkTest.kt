// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Models page has to give way to the window, as every settings page does
 *
 * The cards hold lines that are as long as the text in them: the name of a provider, the label of a model with its badges,
 * the message of a failed request. Each of them used to be a reason for the page to ask for more width than the dialog has,
 * and a settings page that asks too much is cut at the right edge without a word
 */
class ModelsPageShrinkTest {
  private val longName = "A provider whose name is as long as a sentence about it, with a gateway in brackets (EU region)"
  private val longLabel = "A model with a very long marketing name  ·  a-model-id-that-keeps-going-for-a-while-too  · vision · fim · 1M"
  private val longReason = "HTTP 429: the account has used up its quota for this month and the provider asks to try again after the first"

  private fun fixture(): ModelsPageFixture {
    val fixture = ModelsPageFixture()
    fixture.view.render(
      listOf(ModelsPageView.ProviderBlock("long", longName, listOf(ModelsPageView.Declared("m", longLabel)))) + fixture.keyed(),
      fixture.waiting() + ModelsPageView.ProviderBlock("long-waiting", longName, listOf(ModelsPageView.Declared("w", longLabel))),
    )
    fixture.view.setSource("long", ModelsPage.Source.Failed(longReason))
    fixture.view.setSource("zai", ModelsPage.Source.Catalog(120, System.currentTimeMillis(), added = 14))
    return fixture
  }

  private fun layOut(component: Component) {
    if (component is Container) {
      component.doLayout()
      component.components.forEach { layOut(it) }
    }
  }

  @Test
  fun `long names, labels and messages do not hold the page wide`() {
    val fixture = fixture()
    fixture.folds().forEach { it.expanded = true }
    val content = fixture.view.content
    assertTrue(content.minimumSize.width <= MIN_WIDTH_CEILING,
               "the page asks for a minimum of ${content.minimumSize.width} points: it cannot follow a narrow window")
    assertTrue(content.preferredSize.width in 1..PAGE_WIDTH_CEILING,
               "the page asks the dialog for ${content.preferredSize.width} points at a ceiling of $PAGE_WIDTH_CEILING")
  }

  @Test
  fun `a window of ordinary width is followed and not scrolled sideways`() {
    val fixture = fixture()
    fixture.folds().forEach { it.expanded = true }
    val page = SettingsUi.page(fixture.view.content)
    page.setSize(ORDINARY_WIDTH, TALL)
    page.doLayout()
    val view = page.viewport.view as javax.swing.Scrollable
    assertTrue(view.scrollableTracksViewportWidth, "the page asks for a sideways bar at $ORDINARY_WIDTH points")
  }

  @Test
  fun `nothing sticks out of the page at a narrow window`() {
    val fixture = fixture()
    fixture.folds().forEach { it.expanded = true }
    val content = fixture.view.content
    val page = SettingsUi.page(content)
    page.setSize(NARROW_WIDTH, TALL)
    repeat(3) { layOut(page) }

    val wide = ArrayList<String>()
    fun walk(c: Component) {
      if (c.isVisible && c !== page && c !is JScrollPane) {
        val right = SwingUtilities.convertPoint(c.parent, c.x + c.width, 0, content).x
        if (right > content.width) wide += "${c.javaClass.simpleName} ends at $right of ${content.width}"
      }
      if (c is Container) c.components.forEach { walk(it) }
    }
    walk(content)
    assertEquals(emptyList(), wide.take(MAX_REPORTED), "components stick out of the page at $NARROW_WIDTH points")
  }

  private companion object {
    /** The page can be squeezed to about this, which is the narrowest detail pane the settings dialog leaves */
    const val MIN_WIDTH_CEILING = 320

    /** The same order of magnitude the other pages are held to */
    const val PAGE_WIDTH_CEILING = 900
    const val ORDINARY_WIDTH = 660
    const val NARROW_WIDTH = 420
    const val TALL = 4000
    const val MAX_REPORTED = 5
  }
}
