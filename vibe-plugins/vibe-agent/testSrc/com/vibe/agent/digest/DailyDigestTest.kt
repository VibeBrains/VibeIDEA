// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.digest

import com.vibe.agent.budget.SpendLedger
import com.vibe.agent.runs.AgentRunLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyDigestTest {
  private val labels = object : DailyDigest.Labels {
    override val quiet = "тихо"
    override fun runs(count: Int, changedFiles: Int) = "прогонов $count, файлов $changedFiles"
    override fun problems(failed: Int, orphaned: Int) = "упало $failed, осиротело $orphaned"
    override fun spend(tokens: Long, topRole: String?) = "токенов $tokens, больше всех ${topRole ?: "-"}"
  }

  private fun run(startedAtMs: Long, status: AgentRunLedger.Status, changed: Int = 0) = AgentRunLedger.Run(
    runId = "r$startedAtMs", epoch = "e", source = AgentRunLedger.Source.PIPELINE, goal = "цель",
    status = status, target = null, startedAtMs = startedAtMs, changedFiles = changed,
  )

  private fun spend(tokens: Long, role: String?) = SpendLedger.Entry(0, role, "acp/claude", tokens)

  @Test
  fun `runs older than the window are not counted`() {
    val stats = DailyDigest.collect(
      listOf(run(0, AgentRunLedger.Status.COMPLETED), run(1_000, AgentRunLedger.Status.COMPLETED)),
      emptyList(), sinceMs = 500,
    )
    assertEquals(1, stats.runs)
  }

  @Test
  fun `an orphan is not a failure`() {
    // Прогон, чьё окно умерло, — это не прогон, который пошёл не так; слияние прячет и то, и другое.
    val stats = DailyDigest.collect(
      listOf(run(1, AgentRunLedger.Status.FAILED), run(2, AgentRunLedger.Status.ORPHANED)),
      emptyList(), sinceMs = 0,
    )
    assertEquals(1, stats.failed)
    assertEquals(1, stats.orphaned)
  }

  @Test
  fun `the biggest spender is named`() {
    val stats = DailyDigest.collect(emptyList(), listOf(spend(10, "qa"), spend(900, "code-reviewer")), sinceMs = 0)
    assertEquals("code-reviewer", stats.topRole)
    assertEquals(910, stats.tokens)
  }

  @Test
  fun `a quiet day says so instead of printing zeros`() {
    val stats = DailyDigest.collect(emptyList(), emptyList(), sinceMs = 0)
    assertTrue(stats.isQuiet)
    assertEquals("тихо", DailyDigest.render(stats, labels))
  }

  @Test
  fun `problems are mentioned only when there are any`() {
    val clean = DailyDigest.collect(listOf(run(1, AgentRunLedger.Status.COMPLETED)), emptyList(), 0)
    assertFalse(DailyDigest.render(clean, labels).contains("упало"))
    val broken = DailyDigest.collect(listOf(run(1, AgentRunLedger.Status.FAILED)), emptyList(), 0)
    assertTrue(DailyDigest.render(broken, labels).contains("упало 1"))
  }

  @Test
  fun `the schedule accepts a time and refuses anything else`() {
    assertEquals(9 * 60 + 30, DailyDigest.minutesOfDay("09:30"))
    assertNull(DailyDigest.minutesOfDay("вечером"))
    assertNull(DailyDigest.minutesOfDay("25:00"))
    assertNull(DailyDigest.minutesOfDay("09:70"))
    assertNull(DailyDigest.minutesOfDay(null))
  }

  @Test
  fun `the digest is sent once a day, after its time`() {
    val nine = 9 * 60
    assertFalse(DailyDigest.shouldSend(nowMinutes = nine - 1, today = 5, scheduledMinutes = nine, lastSentDay = 4))
    assertTrue(DailyDigest.shouldSend(nowMinutes = nine, today = 5, scheduledMinutes = nine, lastSentDay = 4))
    assertFalse(DailyDigest.shouldSend(nowMinutes = nine + 5, today = 5, scheduledMinutes = nine, lastSentDay = 5))
  }

  @Test
  fun `a digest missed by hours is not delivered at all`() {
    // Сводка за вчера, пришедшая в полдень, — шум, а не информация.
    val nine = 9 * 60
    assertTrue(DailyDigest.shouldSend(nine + DailyDigest.GRACE_MINUTES, 5, nine, 4))
    assertFalse(DailyDigest.shouldSend(nine + DailyDigest.GRACE_MINUTES + 1, 5, nine, 4))
  }

  @Test
  fun `no schedule means no digest`() {
    assertFalse(DailyDigest.shouldSend(600, 5, scheduledMinutes = null, lastSentDay = 0))
  }
}
