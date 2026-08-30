// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerritoryLockTest {
  private fun run(id: String, status: AgentRunLedger.Status, key: String? = null) = AgentRunLedger.Run(
    runId = id, epoch = "e", source = AgentRunLedger.Source.HTTP_API, goal = "цель",
    status = status, target = null, startedAtMs = 0, idempotencyKey = key,
  )

  @Test
  fun `two spellings of the same corner are the same corner`() {
    assertTrue(TerritoryLock.overlaps("src/ui", "./src/ui/"))
    assertTrue(TerritoryLock.overlaps("SRC/UI", "src/ui"))
  }

  @Test
  fun `a parent and a child overlap, seen from either side`() {
    // Именно эта форма и есть коллизия: один правит src, другой src/ui.
    assertTrue(TerritoryLock.overlaps("src", "src/ui"))
    assertTrue(TerritoryLock.overlaps("src/ui", "src"))
  }

  @Test
  fun `neighbours with a shared prefix do not overlap`() {
    // Ловушка голого startsWith: src/ui и src/uikit — разные углы.
    assertFalse(TerritoryLock.overlaps("src/ui", "src/uikit"))
    assertFalse(TerritoryLock.overlaps("docs", "src"))
  }

  @Test
  fun `the whole project collides with everything`() {
    // Иначе «весь проект» стал бы способом проскочить проверку.
    assertTrue(TerritoryLock.overlaps("", "src/ui"))
    assertTrue(TerritoryLock.overlaps("src/ui", "/"))
  }

  @Test
  fun `conflicts name the claims that stand in the way`() {
    val claims = listOf(
      TerritoryLock.Claim("run-1", listOf("src/ui")),
      TerritoryLock.Claim("run-2", listOf("docs")),
    )
    assertEquals(listOf("run-1"), TerritoryLock.conflicts(claims, "run-3", listOf("src/ui/panel")).map { it.runId })
    assertTrue(TerritoryLock.conflicts(claims, "run-3", listOf("server")).isEmpty())
  }

  @Test
  fun `a run does not conflict with itself`() {
    val claims = listOf(TerritoryLock.Claim("run-1", listOf("src")))
    assertTrue(TerritoryLock.conflicts(claims, "run-1", listOf("src/ui")).isEmpty())
  }

  @Test
  fun `the same idempotency key returns the running run instead of starting a second`() {
    val runs = listOf(run("run-1", AgentRunLedger.Status.RUNNING, "deploy-42"))
    assertEquals("run-1", TerritoryLock.existingRun(runs, "deploy-42")?.runId)
  }

  @Test
  fun `a finished run does not block a new one with the same key`() {
    // Ключ защищает от ДВОЙНОГО запуска, а не запрещает повторить работу завтра.
    val runs = listOf(run("run-1", AgentRunLedger.Status.COMPLETED, "deploy-42"))
    assertNull(TerritoryLock.existingRun(runs, "deploy-42"))
  }

  @Test
  fun `no key means no deduplication`() {
    val runs = listOf(run("run-1", AgentRunLedger.Status.RUNNING, null))
    assertNull(TerritoryLock.existingRun(runs, null))
    assertNull(TerritoryLock.existingRun(runs, "   "))
  }
}
