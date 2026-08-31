// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NestedRulesTest {
  @Test
  fun `правила ищутся по папкам-предкам, а не обходом дерева`() {
    // Наивный обход уходит в node_modules и превращает чтение правил в паузу на каждом ходу.
    val dirs = ProjectRules.ruleDirsFor(listOf("packages/ui/src/Button.tsx", "packages/api/index.ts"))
    assertEquals(listOf("", "packages", "packages/api", "packages/ui", "packages/ui/src"), dirs.sorted())
    assertEquals("", dirs.first(), "корень первым — с него начинается перекрытие")
  }

  @Test
  fun `ход без файлов смотрит только в корень`() {
    assertEquals(listOf(""), ProjectRules.ruleDirsFor(emptyList()))
  }

  @Test
  fun `одноимённое правило пакета перекрывает корневое`() {
    val root = ProjectRules.parse("naming", "---\nalwaysApply: true\n---\nкорневое", dir = "")
    val nested = ProjectRules.parse("naming", "---\nalwaysApply: true\n---\nпакетное", dir = "packages/ui")
    val kept = ProjectRules.nearestWins(listOf(root, nested))
    assertEquals(1, kept.size)
    assertEquals("пакетное", kept.single().body)
    assertEquals("packages/ui", kept.single().dir)
  }

  @Test
  fun `разные имена не конкурируют`() {
    val a = ProjectRules.parse("naming", "корень", dir = "")
    val b = ProjectRules.parse("tests", "пакет", dir = "packages/ui")
    assertEquals(2, ProjectRules.nearestWins(listOf(a, b)).size)
  }

  @Test
  fun `вложенное правило накрывает только свою папку`() {
    val nested = ProjectRules.parse("naming", "тело", dir = "packages/ui")
    assertTrue(ProjectRules.coversPath(nested, "packages/ui/src/Button.tsx"))
    assertTrue(ProjectRules.coversPath(nested, "packages/ui"))
    assertFalse(ProjectRules.coversPath(nested, "packages/uikit/src/x.ts"), "префикс имени — не вложенность")
    assertFalse(ProjectRules.coversPath(nested, "packages/api/index.ts"))
    assertTrue(ProjectRules.coversPath(ProjectRules.parse("x", "тело", dir = ""), "любой/путь.kt"))
  }

  @Test
  fun `порядок сохраняет перекрытие — корень раньше, ближнее позже`() {
    val root = ProjectRules.parse("a", "к", dir = "")
    val mid = ProjectRules.parse("b", "с", dir = "packages")
    val deep = ProjectRules.parse("c", "г", dir = "packages/ui/src")
    val ordered = ProjectRules.nearestWins(listOf(deep, root, mid))
    assertEquals(listOf("", "packages", "packages/ui/src"), ordered.map { it.dir })
  }
}
