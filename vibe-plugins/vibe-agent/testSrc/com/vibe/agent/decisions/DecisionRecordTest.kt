// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.decisions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Журнал решений: запись без «почему» не отвечает на вопрос, ради которого её открывают. */
class DecisionRecordTest {
  private fun decision(
    number: Int = 7,
    question: String = "Чем подсвечивать PHP",
    chosen: String = "TextMate-грамматика платформы",
    rejected: String = "tree-sitter через WASM",
    why: String = "семантика закрыта языковым сервером; tree-sitter стоил бы своего лексера",
    links: List<String> = emptyList(),
  ) = DecisionRecord.Decision(number, question, chosen, rejected, why, "2026-09-05", links)

  @Test
  fun `имя файла начинается с номера и транслитерирует тему`() {
    assertEquals("0007-chem-podsvechivat-php.md", DecisionRecord.fileName(decision()))
  }

  @Test
  fun `русский заголовок не превращается в пустое имя`() {
    assertTrue(DecisionRecord.slug("Почему").isNotEmpty())
    assertEquals("decision", DecisionRecord.slug("！？"), "имя должно остаться осмысленным даже из мусора")
  }

  @Test
  fun `текст решения содержит вопрос, выбор, отвергнутое и причину`() {
    val text = DecisionRecord.render(decision())
    assertTrue(text.startsWith("# 7. Чем подсвечивать PHP"))
    assertTrue("## Решение" in text)
    assertTrue("## Отвергнуто" in text)
    assertTrue("## Почему" in text)
  }

  @Test
  fun `без отвергнутых вариантов раздела нет`() {
    assertTrue("## Отвергнуто" !in DecisionRecord.render(decision(rejected = "")))
  }

  @Test
  fun `ссылки попадают списком`() {
    val text = DecisionRecord.render(decision(links = listOf("docs/knowledge/php.md", "")))
    assertTrue("- docs/knowledge/php.md" in text)
    assertEquals(1, text.lines().count { it.startsWith("- ") }, "пустая ссылка не пишется")
  }

  @Test
  fun `строка индекса ссылается на файл и несёт суть`() {
    val line = DecisionRecord.indexLine(decision())
    assertTrue(line.startsWith("- [7. Чем подсвечивать PHP](0007-chem-podsvechivat-php.md) — "), line)
    assertTrue("tree-sitter стоил" !in line, "в индекс идёт первое предложение причины, а не вся причина")
  }

  @Test
  fun `индекс создаётся, если его ещё нет`() {
    val created = DecisionRecord.appendToIndex(null, decision(), "Решения проекта")
    assertTrue(created.startsWith("# Решения проекта"))
    assertTrue(DecisionRecord.indexLine(decision()) in created)
    val appended = DecisionRecord.appendToIndex(created, decision(number = 8), "Решения проекта")
    assertEquals(1, appended.lines().count { it.startsWith("# ") }, "второй заголовок не добавляется")
  }

  @Test
  fun `номер продолжает существующие, а не начинается заново`() {
    assertEquals(1, DecisionRecord.nextNumber(emptyList()))
    assertEquals(13, DecisionRecord.nextNumber(listOf("0001-a.md", "0012-b.md", "README.md")))
  }

  @Test
  fun `пустые поля отклоняются кодами`() {
    assertNull(DecisionRecord.validate(decision()))
    assertEquals(DecisionRecord.Refusal.NO_QUESTION, DecisionRecord.validate(decision(question = " ")))
    assertEquals(DecisionRecord.Refusal.NO_CHOICE, DecisionRecord.validate(decision(chosen = "")))
    assertEquals(DecisionRecord.Refusal.NO_REASON, DecisionRecord.validate(decision(why = "")))
  }

  @Test
  fun `решение отменяется решением, а не правкой старого`() {
    val text = DecisionRecord.render(decision(number = 12).copy(supersedes = 7))
    assertTrue("**Заменяет решение:** 7" in text)
    val index = DecisionRecord.appendToIndex(null, decision(), "Решения проекта")
    val marked = DecisionRecord.markSuperseded(index, supersededNumber = 7, byNumber = 12)
    assertTrue(marked.lines().any { it.contains(DecisionRecord.SUPERSEDED_MARK) && it.contains("12") }, marked)
    // Файл старого решения не трогаем: он свидетельство того, что и почему решили тогда.
    assertTrue(DecisionRecord.render(decision()).contains("## Почему"))
  }

  @Test
  fun `помеченная строка перестаёт считаться действующей`() {
    val line = DecisionRecord.indexLine(decision())
    assertTrue(DecisionRecord.isActive(line))
    assertFalse(DecisionRecord.isActive(line + " " + DecisionRecord.SUPERSEDED_MARK + " 12"))
  }

  @Test
  fun `повторная пометка не удваивается`() {
    val index = DecisionRecord.appendToIndex(null, decision(), "Решения проекта")
    val once = DecisionRecord.markSuperseded(index, 7, 12)
    assertEquals(once, DecisionRecord.markSuperseded(once, 7, 13), "решение отменяют один раз")
  }
}
