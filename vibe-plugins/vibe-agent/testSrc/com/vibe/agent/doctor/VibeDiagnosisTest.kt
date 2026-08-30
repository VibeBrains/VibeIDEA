// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.doctor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VibeDiagnosisTest {
  private val labels = object : VibeDiagnosis.Labels {
    override fun header(problems: Int, total: Int) = "проблем $problems из $total"
    override val allGood = "всё на месте"
  }

  private fun line(name: String, state: VibeDiagnosis.State) = VibeDiagnosis.Line(name, state, "деталь")

  @Test
  fun `problems come first, because a report is read from the top`() {
    val report = VibeDiagnosis.Report(listOf(
      line("ок", VibeDiagnosis.State.OK),
      line("нет", VibeDiagnosis.State.ABSENT),
      line("подозрительно", VibeDiagnosis.State.WARN),
    ))
    val text = VibeDiagnosis.render(report, labels)
    assertTrue(text.indexOf("нет") < text.indexOf("подозрительно"))
    assertTrue(text.indexOf("подозрительно") < text.indexOf("ок"))
  }

  @Test
  fun `absence is stated as clearly as presence`() {
    // Диагностика, перечисляющая только имеющееся, не объясняет ничего.
    val text = VibeDiagnosis.render(VibeDiagnosis.Report(listOf(line("ключ", VibeDiagnosis.State.ABSENT))), labels)
    assertTrue(text.contains("✖ ключ"))
    assertTrue(text.contains("проблем 1 из 1"))
  }

  @Test
  fun `a clean report says so instead of leaving a bare list`() {
    val text = VibeDiagnosis.render(VibeDiagnosis.Report(listOf(line("ок", VibeDiagnosis.State.OK))), labels)
    assertTrue(text.contains("всё на месте"))
  }

  @Test
  fun `the marks are distinct enough to scan`() {
    assertEquals(3, listOf(VibeDiagnosis.State.OK, VibeDiagnosis.State.WARN, VibeDiagnosis.State.ABSENT)
      .map { VibeDiagnosis.mark(it) }.distinct().size)
  }
}
