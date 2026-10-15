// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.settings

import com.vibe.agent.providers.ApiKeyResolver
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** The rules of the Models page that need no screen: who waits for a key, what the header says, where a list came from */
class ModelsPageTest {
  private val utc = ZoneId.of("UTC")
  private val noon = Instant.parse("2026-10-15T12:30:00Z").toEpochMilli()
  private val evening = Instant.parse("2026-10-15T18:05:00Z").toEpochMilli()
  private val nextDay = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()

  private fun assessed(id: String, verdict: ModelsPage.KeyVerdict) = ModelsPage.Assessed(id, id.uppercase(), verdict)

  @Test
  fun `a key found by the quiet lookup needs no question to the keychain`() {
    val verdict = ModelsPage.verdict(quietMissing = false) { fail("the keychain was asked although a key was found") }
    assertEquals(ModelsPage.KeyVerdict.USABLE, verdict)
  }

  @Test
  fun `only an absent keychain entry sends a provider to the waiting ones`() {
    assertEquals(ModelsPage.KeyVerdict.WAITING, ModelsPage.verdict(true) { ApiKeyResolver.Presence.ABSENT })
  }

  @Test
  fun `a key in the keychain that was not read yet is not a missing key`() {
    // The quiet lookup never reads the keychain, so such a key looks exactly like none
    // Calling it missing would send a person to re-enter a key that is fine
    assertEquals(ModelsPage.KeyVerdict.NOT_READ, ModelsPage.verdict(true) { ApiKeyResolver.Presence.PRESENT })
  }

  @Test
  fun `a keychain that could not be asked does not say there is no key`() {
    assertEquals(ModelsPage.KeyVerdict.NOT_READ, ModelsPage.verdict(true) { ApiKeyResolver.Presence.UNKNOWN })
  }

  @Test
  fun `grouping keeps the order of the registry inside both groups`() {
    val grouping = ModelsPage.group(listOf(
      assessed("a", ModelsPage.KeyVerdict.USABLE),
      assessed("b", ModelsPage.KeyVerdict.WAITING),
      assessed("c", ModelsPage.KeyVerdict.NOT_READ),
      assessed("d", ModelsPage.KeyVerdict.WAITING),
      assessed("e", ModelsPage.KeyVerdict.USABLE),
    ))
    assertEquals(listOf("a", "c", "e"), grouping.keyed.map { it.id })
    assertEquals(listOf("b", "d"), grouping.waiting.map { it.id })
  }

  @Test
  fun `nobody waiting leaves the second group empty`() {
    val grouping = ModelsPage.group(listOf(assessed("a", ModelsPage.KeyVerdict.USABLE)))
    assertTrue(grouping.waiting.isEmpty())
    assertEquals(1, grouping.keyed.size)
  }

  @Test
  fun `the title of the waiting block names the first providers and an ellipsis when there are more`() {
    assertEquals("A, B, C…", ModelsPage.leadNames(listOf("A", "B", "C", "D")))
    assertEquals("A, B, C", ModelsPage.leadNames(listOf("A", "B", "C")))
    assertEquals("A", ModelsPage.leadNames(listOf("A")))
    assertEquals("", ModelsPage.leadNames(emptyList()))
    assertEquals("A…", ModelsPage.leadNames(listOf("A", "B"), limit = 1))
  }

  @Test
  fun `the summary agrees with its numbers`() {
    assertEquals("3 провайдера с ключом · 12 моделей включено", ModelsPage.summary(providers = 3, enabledModels = 12))
    assertEquals("1 провайдер с ключом · 1 модель включена", ModelsPage.summary(1, 1))
    assertEquals("2 провайдера с ключом · 2 модели включены", ModelsPage.summary(2, 2))
    assertEquals("0 провайдеров с ключом · 0 моделей включено", ModelsPage.summary(0, 0))
    assertEquals("11 провайдеров с ключом · 11 моделей включено", ModelsPage.summary(11, 11))
    assertEquals("21 провайдер с ключом · 21 модель включена", ModelsPage.summary(21, 21))
  }

  @Test
  fun `a catalog says how many models it holds and when it was fetched`() {
    val text = ModelsPage.sourceText(ModelsPage.Source.Catalog(8, noon), evening, utc)
    assertEquals("каталог: 8 моделей, обновлён 12:30", text)
    assertEquals("каталог: 1 модель, обновлён 12:30", ModelsPage.sourceText(ModelsPage.Source.Catalog(1, noon), evening, utc))
  }

  @Test
  fun `a catalog of another day carries its date so that yesterday is not read as fresh`() {
    assertEquals("каталог: 8 моделей, обновлён 15.10 12:30", ModelsPage.sourceText(ModelsPage.Source.Catalog(8, noon), nextDay, utc))
  }

  @Test
  fun `a catalog that never learned its time does not invent one`() {
    assertEquals("каталог: 8 моделей", ModelsPage.sourceText(ModelsPage.Source.Catalog(8, 0L), evening, utc))
  }

  @Test
  fun `new models of a refresh are named after the catalog line`() {
    val text = ModelsPage.sourceText(ModelsPage.Source.Catalog(8, noon, added = 2), evening, utc)
    assertEquals("каталог: 8 моделей, обновлён 12:30 · добавлено 2", text)
  }

  @Test
  fun `the other sources have a line each`() {
    assertEquals("модели заданы в файле", ModelsPage.sourceText(ModelsPage.Source.File))
    assertEquals("каталог пуст — модели только из файла", ModelsPage.sourceText(ModelsPage.Source.EmptyCatalog))
    assertEquals("запрашиваю каталог моделей…", ModelsPage.sourceText(ModelsPage.Source.Asking))
    assertEquals("каталог не получен: HTTP 401", ModelsPage.sourceText(ModelsPage.Source.Failed("HTTP 401")))
    assertEquals("ключ ещё не прочитан — нажмите «обновить»", ModelsPage.sourceText(ModelsPage.Source.KeyNotRead))
  }

  @Test
  fun `before any answer the header shows the cache, unless the page knows better`() {
    val cached = ModelsPage.Source.Catalog(8, noon)
    assertEquals(cached, ModelsPage.initialSource(fetchEnabled = true, ModelsPage.KeyVerdict.USABLE, cached))
    assertEquals(ModelsPage.Source.Asking, ModelsPage.initialSource(true, ModelsPage.KeyVerdict.USABLE, cached = null))
    // fetch: false means the catalog is never asked for, whatever the cache holds
    assertEquals(ModelsPage.Source.File, ModelsPage.initialSource(false, ModelsPage.KeyVerdict.USABLE, cached))
    // An unread key cannot ask: saying "asking" would promise an answer that does not come
    assertEquals(ModelsPage.Source.KeyNotRead, ModelsPage.initialSource(true, ModelsPage.KeyVerdict.NOT_READ, cached))
  }

  @Test
  fun `a row stays when it matches the search and is switched on, if the block shows only those`() {
    val hay = "GLM-4.6  ·  glm-4.6  · кастом"
    val glm = ModelRows.tokens("glm")
    assertTrue(ModelRows.visible(hay, enabled = true, glm, onlyEnabled = true))
    assertFalse(ModelRows.visible(hay, enabled = false, glm, onlyEnabled = true))
    assertTrue(ModelRows.visible(hay, enabled = false, glm, onlyEnabled = false))
    assertFalse(ModelRows.visible(hay, enabled = true, ModelRows.tokens("gpt"), onlyEnabled = false))
    assertTrue(ModelRows.visible(hay, enabled = true, ModelRows.tokens(""), onlyEnabled = true))
  }
}
