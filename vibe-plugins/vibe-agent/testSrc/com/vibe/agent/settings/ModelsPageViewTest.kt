// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the Models page shows and what it does with clicks, driven without an IDE */
class ModelsPageViewTest {
  @Test
  fun `every block is collapsed when the page is drawn`() {
    val page = ModelsPageFixture().rendered()
    val folds = page.folds()
    // Three keyed cards, the waiting card, and one small fold per waiting provider
    assertEquals(3 + 1 + 4, folds.size)
    assertTrue(folds.none { it.expanded }, "a block is open on a fresh page")
  }

  @Test
  fun `providers with a key get plain cards and the waiting ones one dashed card`() {
    val page = ModelsPageFixture().rendered()
    val cards = page.cards()
    assertEquals(listOf(false, false, false, true), cards.map { it.dashed })
  }

  @Test
  fun `the waiting card sits last and its title counts and names the providers`() {
    val page = ModelsPageFixture().rendered()
    val texts = page.textsOf(page.view.content)
    assertTrue("▸  Ждут ключа  (4)" in texts, "title of the waiting block not found in $texts")
    assertTrue("Anthropic, Mistral, Groq…" in texts, "first names not found in $texts")
    val order = page.cards().map { card -> page.textsOf(card).first() }
    assertTrue(order.last().contains("Ждут ключа"))
  }

  @Test
  fun `the summary counts the providers that can be used and the models switched on in them`() {
    val page = ModelsPageFixture().rendered()
    // openai 2 + zai 3 + ollama 1 declared models, all on by default; the models of waiting providers are not counted
    assertTrue("3 провайдера с ключом · 6 моделей включено" in page.textsOf(page.view.header))
  }

  @Test
  fun `a switch taken off moves the summary`() {
    val page = ModelsPageFixture().rendered()
    page.view.rows.first { it.modelId == "gpt-5" }.box.doClick()
    assertTrue("3 провайдера с ключом · 5 моделей включено" in page.textsOf(page.view.header))
  }

  @Test
  fun `a model of a waiting provider does not enter the summary`() {
    val page = ModelsPageFixture().rendered()
    val row = page.view.rows.first { it.providerId == "anthropic" }
    assertTrue(row.box.isSelected)
    row.box.doClick()
    assertTrue("3 провайдера с ключом · 6 моделей включено" in page.textsOf(page.view.header))
  }

  @Test
  fun `the title of a block counts shown over all, and only the switched on show by default after a catalog came`() {
    val page = ModelsPageFixture().rendered()
    val added = page.view.addCatalog("openai", listOf("gpt-5", "gpt-4.1", "o3", "o4-mini"))
    assertEquals(3, added, "gpt-5 was declared already")
    // 2 on, 3 from the catalog off: five models, two shown while the block shows only the switched on
    assertTrue("▸  OpenAI  (2/5)" in page.textsOf(page.view.content))
    assertEquals(listOf("gpt-5", "gpt-5-mini"), page.visibleRows("openai"))
  }

  @Test
  fun `a catalog row is off until a person turns it on`() {
    val page = ModelsPageFixture().rendered()
    page.view.addCatalog("openai", listOf("o3"))
    assertFalse(page.view.rows.first { it.modelId == "o3" }.box.isSelected)
    assertTrue(page.view.rows.first { it.modelId == "o3" }.defaultHidden)
  }

  @Test
  fun `an answer that brings nothing new adds nothing`() {
    val page = ModelsPageFixture().rendered()
    page.view.addCatalog("openai", listOf("o3"))
    assertEquals(0, page.view.addCatalog("openai", listOf("o3", "gpt-5")))
  }

  @Test
  fun `a catalog answer for a provider that waits for a key is ignored`() {
    val page = ModelsPageFixture().rendered()
    assertEquals(0, page.view.addCatalog("anthropic", listOf("claude-opus")))
    assertTrue(page.view.rows.none { it.modelId == "claude-opus" })
  }

  @Test
  fun `the filter of a block works inside that block only`() {
    val page = ModelsPageFixture().rendered()
    page.view.addCatalog("zai", listOf("glm-4.6", "glm-5", "other"))
    page.onlyEnabledOf("zai").doClick()
    page.searchOf("zai").text = "glm-5"
    assertEquals(listOf("glm-5"), page.visibleRows("zai"))
    // The neighbour is untouched
    assertEquals(listOf("gpt-5", "gpt-5-mini"), page.visibleRows("openai"))
  }

  @Test
  fun `the search runs over badges as well as names`() {
    val page = ModelsPageFixture().rendered()
    page.searchOf("zai").text = "vision"
    assertEquals(listOf("glm-4.5v"), page.visibleRows("zai"))
  }

  @Test
  fun `a block with nothing switched on explains the empty list`() {
    val page = ModelsPageFixture()
    page.storedHidden += "openai" to "gpt-5"
    page.storedHidden += "openai" to "gpt-5-mini"
    page.view.render(page.keyed(), page.waiting())
    // The explanation is the only hint in a block, and it is hidden as long as something shows
    val shown = page.all(page.foldOf("openai"), javax.swing.JEditorPane::class.java).filter { it.isVisible }
    assertEquals(1, shown.size, "the explanation is not shown")
    assertEquals(emptyList(), page.visibleRows("openai"))
    assertEquals(0, page.all(page.foldOf("zai"), javax.swing.JEditorPane::class.java).count { it.isVisible })
  }

  @Test
  fun `a redraw keeps the switches not applied, the catalog rows and the open blocks`() {
    val page = ModelsPageFixture().rendered()
    page.view.addCatalog("openai", listOf("o3"))
    page.view.rows.first { it.modelId == "o3" }.box.doClick()
    page.view.rows.first { it.modelId == "gpt-5" }.box.doClick()
    page.foldOf("zai").expanded = true

    page.view.render(page.keyed(), page.waiting())

    val o3 = page.view.rows.first { it.modelId == "o3" }
    assertTrue(o3.box.isSelected, "a switch not applied was lost by the redraw")
    assertTrue(o3.defaultHidden, "a catalog row came back as a declared one")
    assertFalse(page.view.rows.first { it.modelId == "gpt-5" }.box.isSelected)
    assertTrue(page.foldOf("zai").expanded)
    assertFalse(page.foldOf("openai").expanded)
  }

  @Test
  fun `a redraw keeps what is typed into the filter of a block`() {
    val page = ModelsPageFixture().rendered()
    page.onlyEnabledOf("zai").doClick()
    page.searchOf("zai").text = "air"

    page.view.render(page.keyed(), page.waiting())

    assertEquals("air", page.searchOf("zai").text)
    assertFalse(page.onlyEnabledOf("zai").isSelected)
    assertEquals(listOf("glm-4.5-air"), page.visibleRows("zai"))
    // A block nobody touched keeps the default
    assertTrue(page.onlyEnabledOf("openai").isSelected)
  }

  @Test
  fun `a provider that lost its key leaves the catalog rows behind and joins the waiting ones`() {
    val page = ModelsPageFixture().rendered()
    page.view.addCatalog("openai", listOf("o3"))
    page.view.render(page.keyed().filter { it.id != "openai" }, page.waiting() + page.openai())
    assertTrue(page.view.rows.none { it.modelId == "o3" }, "a waiting provider shows the models of its file only")
    assertEquals(2, page.view.rows.count { it.providerId == "openai" })
    assertTrue("2 провайдера с ключом · 4 модели включены" in page.textsOf(page.view.header))
  }

  @Test
  fun `a stored decision is the state of a row`() {
    val page = ModelsPageFixture()
    page.storedHidden += "zai" to "glm-4.6"
    page.view.render(page.keyed(), page.waiting())
    assertFalse(page.view.rows.first { it.modelId == "glm-4.6" }.box.isSelected)
    assertTrue(page.view.rows.first { it.modelId == "glm-4.5v" }.box.isSelected)
  }

  @Test
  fun `the source of a list is shown in the header of its block`() {
    val page = ModelsPageFixture().rendered()
    page.view.setSource("zai", ModelsPage.Source.File)
    assertTrue("модели заданы в файле" in page.textsOf(page.foldOf("zai").header))
  }

  @Test
  fun `without providers the page says so instead of staying blank`() {
    val page = ModelsPageFixture()
    page.view.render(emptyList(), emptyList())
    assertTrue(page.all(page.view.content, javax.swing.JEditorPane::class.java).isNotEmpty())
    assertTrue(page.cards().isEmpty())
  }

  // A click on an ActionLink needs a live Application (the platform wraps the listener in a write-intent action),
  // so the wiring of the three actions is checked by what the links are and say, and the clicks by hand in the IDE
  @Test
  fun `the waiting card offers the key page, and so does every provider in it`() {
    val page = ModelsPageFixture().rendered()
    val links = page.all(page.view.content, com.intellij.ui.components.ActionLink::class.java)
    // One in the explanation and one per waiting provider
    assertEquals(1, links.count { it.text == "Ввести ключ на странице «Провайдеры»" })
    assertEquals(4, links.count { it.text == "ввести ключ" })
  }

  @Test
  fun `a block refreshes its own provider only, and waiting providers have nothing to refresh`() {
    val page = ModelsPageFixture().rendered()
    val refresh = page.all(page.view.content, com.intellij.ui.components.ActionLink::class.java).filter { it.text == "обновить" }
    assertEquals(3, refresh.size)
    assertEquals(listOf("OpenAI", "Z.AI (GLM)", "Ollama (local)"), refresh.map { link ->
      Regex("«(.*)»").find(link.toolTipText.orEmpty())?.groupValues?.get(1)
    })
  }

  @Test
  fun `the header offers neither a search nor an active filter of its own`() {
    val page = ModelsPageFixture().rendered()
    assertTrue(page.all(page.view.header, com.intellij.ui.SearchTextField::class.java).isEmpty())
    assertTrue(page.all(page.view.header, com.intellij.ui.components.JBCheckBox::class.java).isEmpty())
    assertEquals("Обновить все каталоги", page.all(page.view.header, com.intellij.ui.components.ActionLink::class.java).single().text)
  }
}
