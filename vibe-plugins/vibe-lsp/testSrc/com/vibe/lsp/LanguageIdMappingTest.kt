// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сопоставление расширений и `languageId` — не декорация, а то, ЧЕМ tsserver разбирает файл.
 *
 * Дефект 05.09.2026: все расширения были объявлены одним `languageId="typescript"`, включая `.tsx`.
 * Под этим идентификатором синтаксис JSX запрещён, и здоровый файл на 130 строк давал 152 ошибки
 * вида «'>' expected» — на настоящем проекте владельца это выглядело как поломка линтера.
 * Проверено прямым прогоном vtsls: `typescript` — 152 диагностики, `typescriptreact` — ноль.
 *
 * Читаем описание как ДАННЫЕ: тест обязан падать на правке XML, а не повторять её.
 */
class LanguageIdMappingTest {
  private val xml: String by lazy {
    val stream = javaClass.classLoader.getResourceAsStream("META-INF/vibe-lsp4ij-integration.xml")
    requireNotNull(stream) { "не найден META-INF/vibe-lsp4ij-integration.xml" }.bufferedReader().readText()
  }

  private val mapping: List<Pair<List<String>, String>> by lazy {
    Regex("<fileNamePatternMapping patterns=\"([^\"]+)\"[^>]*languageId=\"([^\"]+)\"")
      .findAll(xml)
      .map { match -> match.groupValues[1].split(';').map { it.trim() } to match.groupValues[2] }
      .toList()
  }

  private fun languageIdsOf(pattern: String): List<String> =
    mapping.filter { pattern in it.first }.map { it.second }

  @Test
  fun `tsx и jsx объявлены реактовыми идентификаторами`() {
    assertTrue("typescriptreact" in languageIdsOf("*.tsx"), "под typescript в .tsx каждый тег JSX — ошибка")
    assertTrue("javascriptreact" in languageIdsOf("*.jsx"))
  }

  @Test
  fun `реактовый идентификатор не навешивается на файлы без JSX`() {
    assertEquals(listOf("typescript"), languageIdsOf("*.ts").distinct().filter { it.startsWith("typescript") })
    assertTrue(languageIdsOf("*.mts").none { it.endsWith("react") })
  }

  @Test
  fun `ни один шаблон не объявлен двумя разными идентификаторами у одного сервера`() {
    val perServer = Regex("<fileNamePatternMapping patterns=\"([^\"]+)\" serverId=\"([^\"]+)\" languageId=\"([^\"]+)\"")
      .findAll(xml)
      .flatMap { match ->
        match.groupValues[1].split(';').map { pattern -> Triple(pattern.trim(), match.groupValues[2], match.groupValues[3]) }
      }
      .groupBy { it.first to it.second }
    val conflicting = perServer.filterValues { rows -> rows.map { it.third }.distinct().size > 1 }
    assertTrue(conflicting.isEmpty(), "один шаблон с двумя языками у одного сервера: ${conflicting.keys}")
  }
}
