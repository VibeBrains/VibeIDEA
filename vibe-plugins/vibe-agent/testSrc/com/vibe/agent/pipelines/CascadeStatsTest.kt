// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Окупаемость каскада: считается по журналу, а не по вере. */
class CascadeStatsTest {
  private fun gate(ok: Boolean) =
    """{"ts":1,"action":"hook","ok":$ok,"kind":"agent","meta":{"event":"pipelineStepEnd","pipeline":"review","step":"1"}}"""

  private fun skip() =
    """{"ts":2,"action":"hook","ok":true,"kind":"agent","meta":{"event":"pipelineEscalationSkipped","pipeline":"review","step":"2"}}"""

  @Test
  fun `доля эскалаций считается по вердиктам гейта`() {
    val events = CascadeStats.parse(listOf(gate(true), skip(), gate(false), gate(true), skip()))
    val report = CascadeStats.of(events)
    assertEquals(3, report.gated)
    assertEquals(2, report.accepted)
    assertEquals(2, report.skipped)
    assertEquals(1.0 / 3.0, report.escalationShare!!, 1e-9)
  }

  @Test
  fun `пропуск эскалации не считается вердиктом`() {
    // Иначе доля приёмок росла бы от самих пропусков — то есть отчёт хвалил бы себя.
    val report = CascadeStats.of(CascadeStats.parse(listOf(skip(), skip())))
    assertEquals(0, report.gated)
    assertEquals(2, report.skipped)
    assertNull(report.escalationShare)
  }

  @Test
  fun `чужие строки журнала не мешают`() {
    val noise = """{"ts":3,"action":"prompt","ok":true,"kind":"human"}"""
    val report = CascadeStats.of(CascadeStats.parse(listOf(noise, gate(true), "", "не json вовсе")))
    assertEquals(1, report.gated)
    assertEquals(1, report.accepted)
  }

  @Test
  fun `экономия считается только по названным ценам`() {
    val report = CascadeStats.Report(gated = 10, accepted = 8, skipped = 8)
    assertNull(CascadeStats.savings(report, cheapCost = null, strongCost = 1.0))
    assertNull(CascadeStats.savings(report, cheapCost = 0.1, strongCost = null))
    assertEquals(7.2, CascadeStats.savings(report, cheapCost = 0.1, strongCost = 1.0)!!, 1e-9)
  }

  @Test
  fun `каскад дороже одной сильной модели — тоже ответ`() {
    // Отрицательная экономия не прячется: ради этого числа отчёт и заводился.
    val report = CascadeStats.Report(gated = 4, accepted = 1, skipped = 1)
    assertEquals(-0.5, CascadeStats.savings(report, cheapCost = 1.5, strongCost = 1.0)!!, 1e-9)
  }

  @Test
  fun `без пропусков экономии нет, и это ноль, а не незнание`() {
    val report = CascadeStats.Report(gated = 3, accepted = 0, skipped = 0)
    assertEquals(0.0, CascadeStats.savings(report, cheapCost = 0.1, strongCost = 1.0)!!, 1e-9)
  }
}
