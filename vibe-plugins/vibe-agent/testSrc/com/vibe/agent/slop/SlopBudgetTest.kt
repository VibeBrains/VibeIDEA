// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.slop

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A project's `.vibe/slop.json` may carry a pattern that backtracks exponentially
 * The check must end anyway, name the rule it went without and keep every other finding
 */
class SlopBudgetTest {
  private val shipped = assertNotNull(SlopCheck.builtIn, "the build carries no catalogue")

  /**
   * Nested repetition against a line that almost matches: each extra letter doubles the time, forty take hours
   * Two levels are not enough on JDK 9 and later, which remembers where a simple greedy loop already failed
   */
  private val runaway = """
    {
      "rules": [
        { "id": "X-RUNAWAY", "lang": "any", "kind": "regex", "severity": "minor", "name": "Runaway", "fix": "-",
          "patterns": ["((a+)+)+b"] },
        { "id": "X-SOUND", "lang": "any", "kind": "phrases", "severity": "minor", "name": "Sound", "fix": "-",
          "items": ["в разрезе"] }
      ]
    }
  """.trimIndent()

  private val text = "Отчёт в разрезе месяцев.\n" + "a".repeat(40) + "!\n"

  private fun catalog(): CompiledCatalog = SlopOverrides.parse(runaway) { error(it) }.applyTo(shipped) { error(it) }

  @Test
  fun `a runaway rule is cut at its limit and named, the rest of the check stands`() {
    val budget = SlopBudget(ruleMillis = 300)
    val report = assertTimeoutPreemptively(Duration.ofSeconds(30), ThrowingSupplier { TextSlop.analyze(text, catalog(), budget) })
    assertEquals(listOf("X-RUNAWAY"), report.skipped)
    assertEquals(listOf("X-RUNAWAY"), budget.skipped)
    assertTrue(report.findings.any { it.rule == "X-SOUND" }, "findings=${report.findings}")
    assertTrue(SlopRender.render(report, SlopLabels).contains("X-RUNAWAY"), "the report says which rule it went without")
  }

  @Test
  fun `a rule that ran out is not paid for again within the same round`() {
    val budget = SlopBudget(ruleMillis = 300)
    val catalog = catalog()
    TextSlop.analyze(text, catalog, budget)
    val started = System.nanoTime()
    val second = TextSlop.analyze(text, catalog, budget)
    val tookMillis = (System.nanoTime() - started) / 1_000_000
    assertEquals(listOf("X-RUNAWAY"), second.skipped)
    assertTrue(tookMillis < budget.ruleMillis, "the second text waited for the runaway rule again: $tookMillis ms")
  }

  @Test
  fun `sound rules never come near the default limit`() {
    val report = TextSlop.analyze(text.repeat(2000), shipped, SlopBudget())
    assertEquals(emptyList(), report.skipped)
  }

  @Test
  fun `cancelling stops the check rather than finishing it`() {
    assertFailsWith<CancellationException> {
      TextSlop.analyze(text, catalog(), SlopBudget(ruleMillis = 60_000, isCancelled = { true }))
    }
  }
}
