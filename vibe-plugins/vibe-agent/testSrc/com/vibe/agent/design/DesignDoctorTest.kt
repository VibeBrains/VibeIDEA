// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignDoctorTest {
  private val labels = object : DesignDoctor.Labels {
    override val noContext = "контекста нет"
    override val pageReachable = "страница доступна"
    override fun context(files: List<String>) = "контекст: " + files.joinToString()
    override fun pageUnreachable(reason: String?) = "страница недоступна: " + (reason ?: "?")
    override fun rules(total: Int, floor: Int, style: Int) = "правил $total (пол $floor, вкус $style)"
    override fun drift(count: Int) = "принято отклонений: $count"
    override fun unknownDrift(ids: List<String>) = "неизвестные id: " + ids.joinToString()
    override fun hook(mode: String) = "хук: $mode"
  }

  private fun report(
    context: List<String> = listOf("design.md"),
    reachable: Boolean = true,
    drift: List<String> = emptyList(),
    unknown: List<String> = emptyList(),
  ) = DesignDoctor.Report(context, reachable, if (reachable) null else "превью закрыто", 30, 10, drift, unknown, "notify")

  @Test
  fun `floor plus style equals the total, always`() {
    // «53 правила (10 пола, 45 вкуса)» — арифметика, которая не сходится, — быстрый способ
    // потерять доверие ко всему отчёту.
    val text = DesignDoctor.render(report(), labels)
    assertTrue(text.contains("правил 30 (пол 10, вкус 20)"))
    assertEquals(20, DesignDoctor.styleRules(30, 10))
    assertEquals(0, DesignDoctor.styleRules(10, 30), "отрицательного числа правил не бывает")
  }

  @Test
  fun `a typo in accepted drift is named, because it switches nothing off`() {
    val unknown = DesignDoctor.unknownDrift(listOf("contrast-text", "опечатка"), setOf("contrast-text"))
    assertEquals(listOf("опечатка"), unknown)
    assertTrue(DesignDoctor.render(report(unknown = unknown), labels).contains("неизвестные id"))
  }

  @Test
  fun `readiness needs context, a reachable page and no typos`() {
    assertTrue(report().copy(unknownDrift = emptyList()).ready)
    assertFalse(report(context = emptyList()).ready)
    assertFalse(report(reachable = false).ready)
    assertFalse(report(unknown = listOf("опечатка")).ready)
  }

  @Test
  fun `an unreachable page says why rather than just no`() {
    // Иначе «страница вне досягаемости» одинаково звучит и для закрытого превью, и для чужого порта.
    assertTrue(DesignDoctor.render(report(reachable = false), labels).contains("превью закрыто"))
  }

  @Test
  fun `accepted drift is mentioned only when there is any`() {
    assertFalse(DesignDoctor.render(report(), labels).contains("принято отклонений"))
    assertTrue(DesignDoctor.render(report(drift = listOf("a")), labels).contains("принято отклонений: 1"))
  }
}
