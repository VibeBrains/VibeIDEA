// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerritoryGuessTest {
  @Test
  fun `угол проекта читается из самих слов задачи`() {
    assertEquals(listOf("vibe-plugins/vibe-agent"),
                 TerritoryGuess.prefixes("почини vibe-plugins/vibe-agent/src/com/vibe/agent/ui/AgentPanel.kt"))
  }

  @Test
  fun `путь без папки ничего не занимает — корень общий`() {
    assertNull(TerritoryGuess.prefixOf("README.md"))
    assertTrue(TerritoryGuess.prefixes("обнови README.md и CHANGELOG.md").isEmpty())
  }

  @Test
  fun `общие папки углом не считаются`() {
    assertNull(TerritoryGuess.prefixOf("src/Main.kt"), "src есть у всех — это не чей-то угол")
    assertNull(TerritoryGuess.prefixOf("docs/readme.md"))
  }

  @Test
  fun `версии и числа с точкой углом не становятся`() {
    assertTrue(TerritoryGuess.prefixes("подними версию до 1.2 и перечитай 3.4").isEmpty(),
               "угол по имени «1.2» — способ блокировать прогоны наугад")
  }

  @Test
  fun `вложенные пути схлопываются в один угол`() {
    val found = TerritoryGuess.prefixes("правь ui/panel/a.kt и ui/panel/deep/b.kt")
    assertEquals(1, found.size)
    assertEquals("ui/panel", found.single())
  }

  @Test
  fun `неизвестная территория не занимает весь проект`() {
    // «Весь проект» пересекается со всем и сделал бы второй прогон невозможным по той лишь
    // причине, что задачу сформулировали без пути.
    assertTrue(TerritoryGuess.prefixes("почини падающий тест").isEmpty())
    assertTrue(TerritoryGuess.conflicts(emptyList(), "run-1", emptyList()).isEmpty())
  }

  @Test
  fun `конфликт называет чужой прогон, и только идущий`() {
    val running = run("run-1", AgentRunLedger.Status.RUNNING, listOf("ui/panel"))
    val done = run("run-2", AgentRunLedger.Status.COMPLETED, listOf("ui/panel"))
    val runs = listOf(running, done)
    assertEquals(listOf("run-1"), TerritoryGuess.conflicts(runs, "run-3", listOf("ui/panel/deep")).map { it.runId })
    assertTrue(TerritoryGuess.conflicts(runs, "run-3", listOf("docs/vibe")).isEmpty())
    assertTrue(TerritoryGuess.conflicts(runs, "run-1", listOf("ui/panel")).isEmpty(), "сам себе не конфликт")
  }

  private fun run(id: String, status: AgentRunLedger.Status, territory: List<String>) = AgentRunLedger.Run(
    runId = id, epoch = "e", source = AgentRunLedger.Source.PIPELINE, goal = "цель $id",
    status = status, target = null, startedAtMs = 0L, territory = territory,
  )
}
