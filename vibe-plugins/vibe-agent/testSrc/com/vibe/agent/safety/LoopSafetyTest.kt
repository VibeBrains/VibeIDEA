// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopDetectorTest {
  private fun check(vararg calls: String) = LoopDetector.check(calls.toList())

  @Test
  fun `ordinary work is not a loop`() {
    assertEquals(LoopDetector.Verdict.OK, check("read a", "edit b", "run tests", "read c").verdict)
  }

  @Test
  fun `a few retries are allowed before the verdict`() {
    // Дважды повторить вызов — нормальная реакция на сетевую ошибку, а не петля.
    assertEquals(LoopDetector.Verdict.OK, check("read a", "read a").verdict)
    assertEquals(LoopDetector.Verdict.OK, check("read a", "read a", "read a").verdict)
  }

  @Test
  fun `the same call four times in a row is a repeat loop`() {
    val finding = check("edit b", "read a", "read a", "read a", "read a")
    assertEquals(LoopDetector.Verdict.REPEAT, finding.verdict)
    assertEquals("read a", finding.fingerprint)
    assertEquals(4, finding.count)
  }

  @Test
  fun `an alternating pair is a cycle even though no two neighbours match`() {
    // Именно эта форма крутится часами: наивная проверка «как в прошлый раз» её не видит.
    val finding = check("read a", "edit b", "read a", "edit b", "read a", "edit b")
    assertEquals(LoopDetector.Verdict.CYCLE, finding.verdict)
    assertTrue(finding.fingerprint.contains("read a"))
  }

  @Test
  fun `a three-step cycle is caught too`() {
    val finding = check("a", "b", "c", "a", "b", "c", "a", "b", "c")
    assertEquals(LoopDetector.Verdict.CYCLE, finding.verdict)
  }

  @Test
  fun `two passes of a pattern are not yet a cycle`() {
    assertEquals(LoopDetector.Verdict.OK, check("a", "b", "a", "b").verdict)
  }

  @Test
  fun `the shortest period wins`() {
    // «A B A B A B» — период два, а не четыре: иначе отчёт называет не ту петлю.
    val finding = check("a", "b", "a", "b", "a", "b", "a", "b", "a", "b", "a", "b")
    assertEquals("a → b", finding.fingerprint)
  }

  @Test
  fun `an empty history is quiet`() {
    assertEquals(LoopDetector.Verdict.OK, LoopDetector.check(emptyList()).verdict)
  }

  @Test
  fun `the fingerprint joins tool and arguments and is bounded`() {
    assertEquals("read|{\"path\":\"a\"}", LoopDetector.fingerprint("Read", "{\"path\":\"a\"}"))
    assertTrue(LoopDetector.fingerprint("read", "x".repeat(10_000)).length <= 400)
  }

  @Test
  fun `history keeps only the recent tail`() {
    val history = LoopDetector.History(limit = 3)
    listOf("a", "b", "c", "d").forEach { history.add(it) }
    assertEquals(listOf("b", "c", "d"), history.snapshot())
    assertEquals(3, history.size())
  }
}

class DeadManSwitchTest {
  private val minute = 60_000L

  @Test
  fun `recent activity is alive`() {
    assertEquals(DeadManSwitch.Verdict.ALIVE, DeadManSwitch.check(lastActivityMs = 0, nowMs = minute, silenceMs = 5 * minute))
  }

  @Test
  fun `silence past the limit is stale, and twice past it is dead`() {
    assertEquals(DeadManSwitch.Verdict.STALE, DeadManSwitch.check(0, 5 * minute, 5 * minute))
    assertEquals(DeadManSwitch.Verdict.STALE, DeadManSwitch.check(0, 9 * minute, 5 * minute))
    assertEquals(DeadManSwitch.Verdict.DEAD, DeadManSwitch.check(0, 10 * minute, 5 * minute))
  }

  @Test
  fun `a zero limit turns the switch off entirely`() {
    // Настройка «выключено» обязана выключать, а не означать «срабатывать всегда».
    assertEquals(DeadManSwitch.Verdict.ALIVE, DeadManSwitch.check(0, 10_000_000, silenceMs = 0))
  }

  @Test
  fun `the silence is reported in whole minutes and never negative`() {
    assertEquals(3, DeadManSwitch.silentMinutes(0, 3 * minute + 5_000))
    assertEquals(0, DeadManSwitch.silentMinutes(minute, 0))
  }
}
