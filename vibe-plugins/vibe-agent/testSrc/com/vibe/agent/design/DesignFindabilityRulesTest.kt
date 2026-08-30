// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignFindabilityRulesTest {
  private fun page(meta: PageMeta) = DocumentSnapshot(
    url = "https://example.com/", viewportWidthPx = 1280.0, viewportHeightPx = 800.0, meta = meta,
  )

  private val good = PageMeta(
    title = "Каталог возможностей продукта",
    description = "Что умеет продукт: чат, агенты, дизайн-контур, поиск по смыслу и всё остальное разом.",
    lang = "ru",
    viewportContent = "width=device-width, initial-scale=1",
    canonical = "https://example.com/",
    h1Count = 1,
    ogTitle = "Каталог",
    faviconHref = "/favicon.ico",
    charset = "UTF-8",
  )

  private fun rules(meta: PageMeta) = DesignFindabilityRules.all(page(meta)).map { it.rule }

  @Test
  fun `a well-formed page produces nothing`() {
    assertTrue(rules(good).isEmpty(), "нашлось: " + rules(good))
  }

  @Test
  fun `a page without a title, h1, language or viewport is broken for a person`() {
    val found = rules(good.copy(title = "", h1Count = 0, lang = "", viewportContent = ""))
    assertTrue(found.containsAll(listOf(
      DesignRuleCatalog.TITLE_MISSING, DesignRuleCatalog.H1_MISSING,
      DesignRuleCatalog.LANG_MISSING, DesignRuleCatalog.VIEWPORT_MISSING)))
    // Пол качества, а не вкус: это сломано для человека, а не немодно.
    assertTrue(found.all { DesignRuleCatalog.isFloor(it) })
  }

  @Test
  fun `blocked zoom is a floor violation, not a preference`() {
    val found = rules(good.copy(viewportContent = "width=device-width, user-scalable=no"))
    assertEquals(listOf(DesignRuleCatalog.VIEWPORT_BLOCKS_ZOOM), found)
    assertTrue(DesignRuleCatalog.isFloor(DesignRuleCatalog.VIEWPORT_BLOCKS_ZOOM))
  }

  @Test
  fun `maximum-scale one blocks zoom just as surely as user-scalable no`() {
    assertTrue(rules(good.copy(viewportContent = "width=device-width, maximum-scale=1.0"))
                 .contains(DesignRuleCatalog.VIEWPORT_BLOCKS_ZOOM))
  }

  @Test
  fun `two h1 are as wrong as none, but less severely`() {
    assertEquals(listOf(DesignRuleCatalog.H1_MULTIPLE), rules(good.copy(h1Count = 2)))
  }

  @Test
  fun `a relative canonical is worse than none`() {
    // Он резолвится по-разному на каждом хосте, и копии начинают указывать друг на друга.
    assertEquals(listOf(DesignRuleCatalog.CANONICAL_RELATIVE), rules(good.copy(canonical = "/index.html")))
    assertEquals(listOf(DesignRuleCatalog.CANONICAL_MISSING), rules(good.copy(canonical = "")))
  }

  @Test
  fun `a title is judged by length only when it exists`() {
    assertEquals(listOf(DesignRuleCatalog.TITLE_TOO_SHORT), rules(good.copy(title = "Главная")))
    assertEquals(listOf(DesignRuleCatalog.TITLE_TOO_LONG), rules(good.copy(title = "з".repeat(80))))
  }

  @Test
  fun `a description is judged the same way`() {
    assertEquals(listOf(DesignRuleCatalog.DESCRIPTION_TOO_SHORT), rules(good.copy(description = "Коротко")))
    assertEquals(listOf(DesignRuleCatalog.DESCRIPTION_TOO_LONG), rules(good.copy(description = "о".repeat(200))))
    assertEquals(listOf(DesignRuleCatalog.DESCRIPTION_MISSING), rules(good.copy(description = "")))
  }

  @Test
  fun `noindex on a page being designed is almost always a leftover`() {
    assertEquals(listOf(DesignRuleCatalog.ROBOTS_NOINDEX), rules(good.copy(robots = "noindex, nofollow")))
  }

  @Test
  fun `a charset that is not utf-8 is named, and an unreported one is not invented`() {
    assertEquals(listOf(DesignRuleCatalog.CHARSET_NOT_UTF8), rules(good.copy(charset = "windows-1251")))
    assertTrue(rules(good.copy(charset = "")).isEmpty(), "нет данных — не находка")
  }

  @Test
  fun `a missing icon and a missing og title are named separately`() {
    assertEquals(listOf(DesignRuleCatalog.FAVICON_MISSING), rules(good.copy(faviconHref = "")))
    assertEquals(listOf(DesignRuleCatalog.OG_TITLE_MISSING), rules(good.copy(ogTitle = "")))
  }

  @Test
  fun `every findability rule id is in the catalogue`() {
    // Правило, которого нет в каталоге, нельзя ни принять как отклонение, ни объяснить в отчёте.
    val produced = rules(PageMeta()).toSet() + rules(good.copy(title = "з".repeat(80), h1Count = 2,
      description = "о".repeat(200), canonical = "/x", robots = "noindex", charset = "cp1251")).toSet()
    assertTrue(DesignRuleCatalog.ALL.containsAll(produced), "вне каталога: " + (produced - DesignRuleCatalog.ALL.toSet()))
  }
}
