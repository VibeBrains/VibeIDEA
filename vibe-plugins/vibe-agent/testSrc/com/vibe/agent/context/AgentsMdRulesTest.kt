// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `AGENTS.md` подчиняется тем же правилам, что и остальные границы репозитория.
 *
 * Читатель в [ProjectContextService] собирает из файла правило с `alwaysApply = true` и именем
 * [ProjectRules.AGENTS_FILE]; здесь проверяется контракт, на который он опирается.
 */
class AgentsMdRulesTest {
  private fun agents(dir: String, body: String) = ProjectRules.Rule(
    name = ProjectRules.AGENTS_FILE, dir = dir, description = null,
    globs = emptyList(), alwaysApply = true, body = body,
  )

  @Test
  fun `правила из AGENTS_md применяются к ходу без единого файла`() {
    // Смысл конвенции — «читай всегда», а не «читай по просьбе»: ход без правок тоже её слушает.
    val applied = ProjectRules.applicable(listOf(agents("", "пиши тесты")), emptyList(), "привет")
    assertEquals(1, applied.size)
    assertEquals("пиши тесты", applied.single().body)
  }

  @Test
  fun `AGENTS_md пакета перекрывает корневой для файлов пакета`() {
    val kept = ProjectRules.nearestWins(listOf(agents("", "корневое"), agents("packages/ui", "пакетное")))
    assertEquals(1, kept.size)
    assertEquals("пакетное", kept.single().body)
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
