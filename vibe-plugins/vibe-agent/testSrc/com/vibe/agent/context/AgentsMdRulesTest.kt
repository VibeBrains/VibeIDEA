// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `AGENTS.md` подчиняется тем же правилам, что и остальные границы репозитория.
 *
 * Читатель в [ProjectContextService] собирает из файла правило с `alwaysApply = true` и именем
 * [ProjectRules.AGENTS_FILE]; здесь проверяется контракт, на который он опирается.
 */
class AgentsMdRulesTest {
  /** Ровно то, что собирает читатель в [ProjectContextService]: имя — путь файла. */
  private fun agents(dir: String, body: String) = ProjectRules.Rule(
    name = if (dir.isEmpty()) ProjectRules.AGENTS_FILE else "$dir/${ProjectRules.AGENTS_FILE}",
    dir = dir, description = null, globs = emptyList(), alwaysApply = true, body = body,
  )

  @Test
  fun `правила из AGENTS_md применяются к ходу без единого файла`() {
    // Смысл конвенции — «читай всегда», а не «читай по просьбе»: ход без правок тоже её слушает.
    val applied = ProjectRules.applicable(listOf(agents("", "пиши тесты")), emptyList(), "привет")
    assertEquals(1, applied.size)
    assertEquals("пиши тесты", applied.single().body)
  }

  @Test
  fun `AGENTS_md двух пакетов не перекрывают друг друга`() {
    // Правила опознаются по ИМЕНИ, и пока имя было константой «AGENTS.md», ход, тронувший файлы
    // двух пакетов сразу, молча терял правила одного из них: и `applicable`, и `nearestWins`
    // схлопывали их в одно. Имя-путь возвращает файлу личность.
    val rules = listOf(agents("", "корневое"), agents("packages/ui", "для ui"), agents("packages/api", "для api"))
    val kept = ProjectRules.nearestWins(rules)
    assertEquals(3, kept.size, "ни корневое, ни соседнее не должно исчезнуть")
    val applied = ProjectRules.applicable(rules, listOf("packages/ui/Button.tsx", "packages/api/index.ts"), "")
    assertEquals(setOf("для ui", "для api", "корневое"), applied.map { it.body }.toSet())
  }

  @Test
  fun `более близкий AGENTS_md идёт в подсказке после корневого`() {
    // Порядок и есть разрешение конфликта: ближний к файлу читается последним.
    val kept = ProjectRules.nearestWins(listOf(agents("packages/ui", "пакетное"), agents("", "корневое")))
    assertEquals(listOf("корневое", "пакетное"), kept.map { it.body })
  }

  @Test
  fun `правило пакета не касается файлов соседнего пакета`() {
    assertTrue(ProjectRules.coversPath(agents("packages/ui", "для ui"), "packages/ui/Button.tsx"))
    assertFalse(ProjectRules.coversPath(agents("packages/ui", "для ui"), "packages/api/index.ts"))
  }

  @Test
  fun `AGENTS_md и cursorrules живут рядом, а не вместо друг друга`() {
    // У Claude Code фолбэк осмыслен — там своя CLAUDE.md спорит с чужим файлом. У нас обе границы
    // чужие, и выбросить одну значит потерять то, что человек написал.
    val legacy = ProjectRules.Rule(ProjectRules.LEGACY_FILE, "", null, emptyList(), true, "из cursorrules")
    val applied = ProjectRules.applicable(listOf(legacy, agents("", "из AGENTS")), emptyList(), "")
    assertEquals(2, applied.size)
    assertTrue(applied.any { it.name == ProjectRules.AGENTS_FILE })
    assertTrue(applied.any { it.name == ProjectRules.LEGACY_FILE })
  }
}
