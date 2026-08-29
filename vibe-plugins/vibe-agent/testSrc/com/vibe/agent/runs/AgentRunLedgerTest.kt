// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.runs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentRunLedgerTest {
  private val now = 1_800_000_000_000L
  private val minute = 60_000L

  private fun run(
    id: String = "r1",
    epoch: String = "w1",
    status: AgentRunLedger.Status = AgentRunLedger.Status.RUNNING,
    started: Long = now,
    finished: Long? = null,
    heartbeat: Long = now,
    goal: String = "собери проект",
  ) = AgentRunLedger.Run(
    runId = id, epoch = epoch, source = AgentRunLedger.Source.HTTP_API, goal = goal,
    status = status, target = "acp/Claude Code", startedAtMs = started,
    finishedAtMs = finished, heartbeatAtMs = heartbeat, steps = 2, maxSteps = 5, changedFiles = 3,
    outcome = if (status == AgentRunLedger.Status.RUNNING) null else "готово",
  )

  @Test
  fun `a record survives the round trip`() {
    val original = run(status = AgentRunLedger.Status.COMPLETED, finished = now + minute)
    assertEquals(original, AgentRunLedger.decode(AgentRunLedger.encode(original)))
  }

  @Test
  fun `a broken line is skipped, the rest of the file survives`() {
    assertNull(AgentRunLedger.decode("{ это не json"))
    assertNull(AgentRunLedger.decode("""{"epoch":"w1"}"""))
    val folded = AgentRunLedger.fold(listOf(AgentRunLedger.encode(run()), "мусор", ""))
    assertEquals(1, folded.size)
  }

  @Test
  fun `the last record for a run wins — the file is append-only`() {
    val lines = listOf(
      AgentRunLedger.encode(run()),
      AgentRunLedger.encode(run(status = AgentRunLedger.Status.COMPLETED, finished = now + minute)),
    )
    assertEquals(AgentRunLedger.Status.COMPLETED, AgentRunLedger.fold(lines).single().status)
  }

  @Test
  fun `only metadata is written — no prompt or reply can hide in a record`() {
    val text = AgentRunLedger.encode(run(goal = "секретный промпт"))
    assertTrue(text.contains("секретный промпт"), "цель пишется — это метаданные")
    for (field in listOf("prompt", "messages", "content", "reply", "arguments")) {
      assertFalse(text.contains(field), "поле $field в журнале не место: $text")
    }
  }

  @Test
  fun `a run of a dead window becomes orphaned only after three missed heartbeats`() {
    val late = run(heartbeat = now - AgentRunLedger.HEARTBEAT_INTERVAL_MS * 2)
    assertEquals(AgentRunLedger.Status.RUNNING,
                 AgentRunLedger.markOrphans(listOf(late), now, aliveEpochs = emptySet()).single().status,
                 "два пропуска — ещё не смерть")

    val dead = run(heartbeat = now - AgentRunLedger.HEARTBEAT_INTERVAL_MS * 4)
    val marked = AgentRunLedger.markOrphans(listOf(dead), now, aliveEpochs = emptySet()).single()
    assertEquals(AgentRunLedger.Status.ORPHANED, marked.status)
    assertEquals(dead.heartbeatAtMs, marked.finishedAtMs, "конец — момент последней отметки, а не сейчас")
    assertTrue(marked.outcome!!.contains("окно"), marked.outcome!!)
  }

  @Test
  fun `a run of a LIVE window is never declared dead, however late its heartbeat`() {
    val stale = run(epoch = "w2", heartbeat = now - AgentRunLedger.HEARTBEAT_INTERVAL_MS * 10)
    val result = AgentRunLedger.markOrphans(listOf(stale), now, aliveEpochs = setOf("w2"))
    assertEquals(AgentRunLedger.Status.RUNNING, result.single().status)
  }

  @Test
  fun `a finished run is left alone by the orphan rule`() {
    val done = run(status = AgentRunLedger.Status.COMPLETED, finished = now - minute, heartbeat = now - minute * 100)
    assertEquals(AgentRunLedger.Status.COMPLETED, AgentRunLedger.markOrphans(listOf(done), now, emptySet()).single().status)
  }

  @Test
  fun `truncation drops old finished runs but never an unfinished one`() {
    val day = 24L * 60 * 60 * 1000
    val old = run(id = "old", status = AgentRunLedger.Status.COMPLETED, started = now - 40 * day, finished = now - 40 * day)
    val live = run(id = "live", started = now - 40 * day)
    val kept = AgentRunLedger.truncate(listOf(old, live), now, maxRecords = 500, retentionDays = 30)
    assertEquals(listOf("live"), kept.map { it.runId })
  }

  @Test
  fun `the record cap never evicts unfinished runs`() {
    val finished = (1..10).map {
      run(id = "f$it", status = AgentRunLedger.Status.COMPLETED, started = now - it * minute, finished = now)
    }
    val live = run(id = "live")
    val kept = AgentRunLedger.truncate(finished + live, now, maxRecords = 3, retentionDays = 30)
    assertTrue(kept.any { it.runId == "live" })
    assertEquals(3, kept.size, "два места отданы свежим завершённым, одно — незавершённому")
  }

  @Test
  fun `the summary counts every state, zeros included`() {
    val summary = AgentRunLedger.summarize(listOf(
      run(id = "a"),
      run(id = "b", status = AgentRunLedger.Status.COMPLETED, finished = now),
      run(id = "c", status = AgentRunLedger.Status.ORPHANED, finished = now),
    ))
    assertEquals(1, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(1, summary.orphaned)
    assertEquals(0, summary.failed)
    assertEquals(1, summary.attention)
  }

  @Test
  fun `search looks at the goal and the target, case-insensitively`() {
    val runs = listOf(run(id = "a", goal = "Собери Проект"), run(id = "b", goal = "почини тесты"))
    assertEquals(listOf("a"), AgentRunLedger.search(runs, "собери").map { it.runId })
    assertEquals(2, AgentRunLedger.search(runs, "claude").size, "target тоже ищется")
    assertEquals(2, AgentRunLedger.search(runs, "   ").size, "пустой запрос ничего не фильтрует")
  }
}
