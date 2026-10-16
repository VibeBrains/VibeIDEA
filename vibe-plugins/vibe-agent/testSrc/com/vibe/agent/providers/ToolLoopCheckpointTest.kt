// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import com.vibe.agent.mcp.ToolSearch
import com.vibe.agent.providers.ToolLoopCheckpoint.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tool loop asks at checkpoints instead of stopping dead, and the autopilot passes them within its budget */
class ToolLoopCheckpointTest {
  private val read = listOf(ToolCall("c", "vibe_read_file", """{"path":"config.ts"}"""))
  private val search = listOf(ToolCall("s", ToolSearch.NAME, """{"query":"read file contents"}"""))

  private fun ToolLoopCheckpoint.run(rounds: Int, calls: List<ToolCall> = read, spent: Long = 0): List<Verdict> =
    (1..rounds).map { afterRound(calls, spent) }

  @Test
  fun `eight reads no longer end the turn, the first checkpoint is at the configured round`() {
    val verdicts = ToolLoopCheckpoint(every = 25, autopilot = false, maxTokens = 200_000).run(25)
    assertTrue(verdicts.take(24).all { it == Verdict.GO_ON })
    assertEquals(Verdict.ASK, verdicts.last())
  }

  @Test
  fun `after a yes the next checkpoint is a full interval later`() {
    val checkpoint = ToolLoopCheckpoint(every = 3, autopilot = false, maxTokens = 0)
    assertEquals(listOf(Verdict.GO_ON, Verdict.GO_ON, Verdict.ASK, Verdict.GO_ON, Verdict.GO_ON, Verdict.ASK), checkpoint.run(6))
  }

  @Test
  fun `the autopilot passes a checkpoint within its budget and asks past it`() {
    val within = ToolLoopCheckpoint(every = 2, autopilot = true, maxTokens = 1_000)
    assertEquals(listOf(Verdict.GO_ON, Verdict.PASS_ON_AUTOPILOT), within.run(2, spent = 999))
    val past = ToolLoopCheckpoint(every = 2, autopilot = true, maxTokens = 1_000)
    assertEquals(listOf(Verdict.GO_ON, Verdict.ASK), past.run(2, spent = 1_000))
  }

  @Test
  fun `an autopilot without a token ceiling never asks`() {
    val checkpoint = ToolLoopCheckpoint(every = 2, autopilot = true, maxTokens = 0)
    assertTrue(checkpoint.run(10, spent = 50_000_000).none { it == Verdict.ASK })
  }

  @Test
  fun `the person's yes restarts the budget from what was spent`() {
    val checkpoint = ToolLoopCheckpoint(every = 1, autopilot = true, maxTokens = 1_000)
    assertEquals(Verdict.ASK, checkpoint.afterRound(read, 5_000))
    checkpoint.personAgreed(5_000)
    assertEquals(Verdict.PASS_ON_AUTOPILOT, checkpoint.afterRound(read, 5_500))
  }

  @Test
  fun `rounds of tool searches only are not counted as work`() {
    val checkpoint = ToolLoopCheckpoint(every = 2, autopilot = false, maxTokens = 0)
    assertEquals(listOf(Verdict.GO_ON, Verdict.GO_ON, Verdict.GO_ON), checkpoint.run(3, calls = search))
    assertEquals(0, checkpoint.work)
    assertEquals(listOf(Verdict.GO_ON, Verdict.ASK), checkpoint.run(2))
    assertEquals(5, checkpoint.total)
  }

  @Test
  fun `a round that searches and reads is work`() {
    val checkpoint = ToolLoopCheckpoint(every = 1, autopilot = false, maxTokens = 0)
    assertEquals(Verdict.ASK, checkpoint.afterRound(search + read, 0))
  }

  @Test
  fun `zero never asks, and the hard limit ends the turn whatever was answered`() {
    val checkpoint = ToolLoopCheckpoint(every = 0, autopilot = false, maxTokens = 0, hardLimit = 4)
    assertEquals(listOf(Verdict.GO_ON, Verdict.GO_ON, Verdict.GO_ON, Verdict.STOP), checkpoint.run(4))
  }

  @Test
  fun `a retried request counts towards the hard limit, searches too`() {
    val checkpoint = ToolLoopCheckpoint(every = 0, autopilot = false, maxTokens = 0, hardLimit = 3)
    checkpoint.retried()
    assertEquals(Verdict.GO_ON, checkpoint.afterRound(search, 0))
    assertEquals(Verdict.STOP, checkpoint.afterRound(search, 0))
  }

  @Test
  fun `the stop marker tells the model the task is unfinished and names the rounds`() {
    val marker = ToolLoopCheckpoint.stoppedMarker(25)
    assertTrue("25 tool rounds" in marker)
    assertTrue("not finished" in marker)
  }
}
