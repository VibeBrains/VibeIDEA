// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.help

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Поиск по вшитой документации — и тем, что реально лежит в сборке.
 *
 * Последний тест здесь не про алгоритм, а про обещание инструмента: «попроси у агента `servers.json`
 * в чистом проекте» — это та самая проверка готовности спеки из глобального правила, только
 * прогоняемая машиной.
 */
class HelpSearchTest {
  private val text = """
    # Спека серверов

    Общее вступление.

    ## Формат servers.json

    Поле `command` обязательно.

    ## Пример

    Готовый файл.
  """.trimIndent()

  @Test
  fun `a file is split by headings, with the line of each`() {
    val sections = HelpSearch.splitIntoSections("manuals/serversSpec.md", text)
    assertEquals(listOf("Спека серверов", "Формат servers.json", "Пример"), sections.map { it.heading })
    assertEquals(listOf(1, 5, 9), sections.map { it.line })
    assertTrue(sections[1].body.contains("command"))
  }

  @Test
  fun `a heading match outranks a passing mention in prose`() {
    val sections = HelpSearch.splitIntoSections("a.md", text)
    val hits = HelpSearch.search(sections, "servers.json")
    assertEquals("Формат servers.json", hits.first().section.heading)
  }

  @Test
  fun `nothing found is said in words, with the number of files searched`() {
    val answer = HelpSearch.format(emptyList(), "квантовая телепортация", filesSearched = 33)
    assertTrue(answer.contains("33"), answer)
    assertTrue(answer.contains("не выдумывайте"), answer)
  }

  @Test
  fun `stop words alone find nothing`() {
    assertTrue(HelpSearch.search(HelpSearch.splitIntoSections("a.md", text), "как что для").isEmpty())
  }

  @Test
  fun `the bundle shipped with the plugin is readable and indexed`() {
    assertTrue(HelpBundle.list().size > 10, "файлов набора: " + HelpBundle.list().size)
    assertTrue(HelpBundle.sections.size > 100, "разделов: " + HelpBundle.sections.size)
  }

  @Test
  fun `asked for the servers file format, the bundle answers with its spec`() {
    val answer = HelpBundle.search("servers.json", 5)
    assertTrue(answer.contains("serversSpec.md"), answer.take(400))
  }

  @Test
  fun `asked about language servers, the bundle answers from the manual`() {
    val answer = HelpBundle.search("языковые серверы", 5)
    assertTrue(answer.contains(".md"), answer.take(400))
  }
}
