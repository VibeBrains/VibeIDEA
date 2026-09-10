// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор `.vibe/pipelines.json` — и отдельно настройки, мёртвые В КОНТЕКСТЕ.
 *
 * Гейт мёртвых полей такое не ловит по построению: у `maxSteps` и `paths` потребитель есть,
 * просто не на шаге со своей моделью. Снаружи это неотличимо от работающей настройки — человек
 * написал ограничение, IDE промолчала, ограничения нет.
 */
class PipelinesFileTest {
  private fun load(json: String): Pair<List<Pipeline>, List<String>> {
    val base = Files.createTempDirectory("vibe-pipelines-test")
    Files.createDirectories(base.resolve(".vibe"))
    Files.writeString(PipelinesFile.path(base.toString()), json)
    val warnings = ArrayList<String>()
    return PipelinesFile.load(base.toString()) { warnings.add(it) } to warnings
  }

  private fun ownModelStep(extra: String) = """
    { "pipelines": [ { "id": "p", "name": "П", "steps": [
      { "role": "critic", "task": "проверь", "provider": "zai", "model": "glm-5.3"$extra }
    ] } ] }
  """.trimIndent()

  @Test
  fun `maxSteps на шаге со своей моделью предупреждает, но пайплайн живёт`() {
    val (pipelines, warnings) = load(ownModelStep(""", "maxSteps": 5"""))
    assertEquals(1, pipelines.size, "бессмысленное поле — опечатка, ронять из-за неё рабочий пайплайн нельзя")
    assertEquals(1, warnings.size, "молчать нельзя: снаружи неработающая настройка неотличима от работающей")
    assertTrue(warnings.single().contains("maxSteps"), warnings.single())
    assertTrue(warnings.single().contains("maxOutputTokens"), "предупреждение обязано назвать, чем ограничивать вместо него")
  }

  @Test
  fun `paths и denyPaths на шаге со своей моделью предупреждают`() {
    // Прямой запрос не читает и не пишет файлы: ограничивать нечего, а человек считает,
    // что он ограничил.
    val (pipelines, warnings) = load(ownModelStep(""", "paths": ["src/**"]"""))
    assertEquals(1, pipelines.size)
    assertEquals(1, warnings.size)
    assertTrue(warnings.single().contains("paths"), warnings.single())

    val (_, denyWarnings) = load(ownModelStep(""", "denyPaths": ["secrets/**"]"""))
    assertEquals(1, denyWarnings.size, "denyPaths мёртв там ровно так же, как paths")
  }

  @Test
  fun `на обычном шаге те же поля работают и молчат`() {
    // Обратная сторона правила: предупреждение на рабочей настройке — ложное срабатывание,
    // а оно приучает не читать предупреждения.
    val (pipelines, warnings) = load(
      """
      { "pipelines": [ { "id": "p", "name": "П", "steps": [
        { "role": "backend-dev", "task": "сделай", "maxSteps": 5, "paths": ["src/**"], "denyPaths": ["secrets/**"] }
      ] } ] }
      """.trimIndent()
    )
    assertEquals(1, pipelines.size)
    assertTrue(warnings.isEmpty(), "на шаге через агента поля действуют: $warnings")
  }

  @Test
  fun `оба поля разом дают два предупреждения, а не одно`() {
    val (_, warnings) = load(ownModelStep(""", "maxSteps": 5, "paths": ["src/**"]"""))
    assertEquals(2, warnings.size, "каждая мёртвая настройка называется отдельно: $warnings")
  }
}
