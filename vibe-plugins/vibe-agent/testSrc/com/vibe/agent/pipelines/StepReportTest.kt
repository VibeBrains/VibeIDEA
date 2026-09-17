// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The step's own report: fields when it wrote them, nothing when it did not — and prose keeps working. */
class StepReportTest {
  @Test
  fun `a full report is read field by field`() {
    val report = StepReport.parse("""
      Сделал разбор и прогнал тесты.

      STATUS: DONE
      FILES: src/a.kt, src/b.kt
      TESTS: ProvidersFileTest — было 12 зелёных, стало 14
      INTERFACES:
      - fun parse(text: String): List<Entry>
      - VibeScroll.pane(view)
      REQUIREMENTS:
      - R01 читать поле floating
      CONCERNS:
      - каталог у DeepSeek не проверен
      BLOCKERS: нет
    """.trimIndent())!!
    assertEquals(StepReport.Status.DONE, report.status)
    assertEquals(listOf("src/a.kt", "src/b.kt"), report.files)
    assertEquals("ProvidersFileTest — было 12 зелёных, стало 14", report.tests)
    assertEquals(2, report.interfaces.size)
    assertEquals(listOf("R01 читать поле floating"), report.requirements)
    assertEquals(listOf("каталог у DeepSeek не проверен"), report.concerns)
    assertTrue(report.blockers.isEmpty(), "«нет» — это отсутствие блокеров, а не блокер")
  }

  @Test
  fun `a blocked step names why, and the summary carries the fields on`() {
    val report = StepReport.parse("""
      STATUS: BLOCKED
      FILES: нет
      BLOCKERS:
      - нет ключа провайдера minimax
    """.trimIndent())!!
    assertEquals(StepReport.Status.BLOCKED, report.status)
    assertTrue(report.files.isEmpty())
    val summary = report.summary()
    assertTrue("STATUS: BLOCKED" in summary && "нет ключа провайдера minimax" in summary, summary)
  }

  @Test
  fun `an unknown status is kept as unknown, not read as done`() {
    assertEquals(StepReport.Status.UNKNOWN, StepReport.parse("STATUS: почти готово")!!.status)
    assertEquals(StepReport.Status.DONE_WITH_CONCERNS, StepReport.parse("**STATUS:** done_with_concerns")!!.status)
  }

  @Test
  fun `prose without the contract parses as nothing, and the example inside an answer does not win`() {
    assertNull(StepReport.parse("Готово. Поправил разбор и добавил тест, всё зелёное."))
    assertNull(StepReport.parse("FILES: src/a.kt\nTESTS: прогнал"), "поля без статуса — это не отчёт")
    // A step that first quotes the contract to itself and then fills it in: the last block is the real one.
    val report = StepReport.parse("""
      Отвечу так:
      STATUS: DONE | BLOCKED
      Теперь по делу.

      STATUS: BLOCKED
    """.trimIndent())!!
    assertEquals(StepReport.Status.BLOCKED, report.status)
  }
}
