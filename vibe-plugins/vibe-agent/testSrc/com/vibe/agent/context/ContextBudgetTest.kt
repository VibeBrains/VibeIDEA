// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextBudgetTest {
  @Test
  fun `an empty window is quiet rather than alarming`() {
    // A target that never reports a window must not be guessed at: a red badge on no data is noise.
    assertEquals(ContextBudget.Verdict.OK, ContextBudget.check(usedTokens = 1000, windowSize = 0).verdict)
  }

  @Test
  fun `the thresholds are where they are documented`() {
    assertEquals(ContextBudget.Verdict.OK, ContextBudget.check(74, 100).verdict)
    assertEquals(ContextBudget.Verdict.WARN, ContextBudget.check(75, 100).verdict)
    assertEquals(ContextBudget.Verdict.WARN, ContextBudget.check(89, 100).verdict)
    assertEquals(ContextBudget.Verdict.BLOCK, ContextBudget.check(90, 100).verdict)
  }

  @Test
  fun `the session ceiling wins over a window with room left`() {
    val status = ContextBudget.check(usedTokens = 10, windowSize = 100, sessionUsed = 2_000_000, sessionLimit = 2_000_000)
    assertEquals(ContextBudget.Verdict.SESSION_EXCEEDED, status.verdict)
  }

  @Test
  fun `no session limit means no session verdict`() {
    val status = ContextBudget.check(usedTokens = 10, windowSize = 100, sessionUsed = 99_999_999, sessionLimit = null)
    assertEquals(ContextBudget.Verdict.OK, status.verdict)
  }

  @Test
  fun `the percentage is reported alongside the verdict`() {
    assertEquals(50, ContextBudget.check(50, 100).windowPercent)
    assertEquals(100, ContextBudget.check(500, 100).windowPercent)
  }

  @Test
  fun `the estimate never returns zero for real text`() {
    // Rounding down to zero would make a guard that shows green on a full window.
    assertEquals(0, ContextBudget.estimateTokens(""))
    assertTrue(ContextBudget.estimateTokens("x") >= 1)
    assertTrue(ContextBudget.estimateTokens(listOf("abcd", "abcd")) >= 2)
  }
}

class OutputCompressorTest {
  private val marker = { dropped: Int, handle: String -> "… -$dropped ($handle)" }

  private fun lines(n: Int) = (1..n).joinToString("\n") { "строка $it" }

  @Test
  fun `short output is left exactly as it was`() {
    val text = lines(10)
    val result = OutputCompressor.compress(text, "h1", marker)
    assertFalse(result.compressed)
    assertEquals(text, result.text)
    assertNull(result.handle)
  }

  @Test
  fun `long output keeps the head and the tail`() {
    val result = OutputCompressor.compress(lines(500), "h1", marker)
    assertTrue(result.compressed)
    assertTrue(result.text.startsWith("строка 1\n"))
    assertTrue(result.text.trimEnd().endsWith("строка 500"))
  }

  @Test
  fun `the cut is announced with the number of lines and the handle`() {
    // Silent truncation is how a model concludes that a failing test suite passed.
    val result = OutputCompressor.compress(lines(500), "out-7", marker)
    assertTrue(result.text.contains("out-7"))
    assertTrue(result.text.contains("-${result.droppedLines}"))
    assertEquals(500 - OutputCompressor.HEAD_LINES - OutputCompressor.TAIL_LINES, result.droppedLines)
  }

  @Test
  fun `output just under the threshold is not touched`() {
    val result = OutputCompressor.compress(lines(OutputCompressor.MIN_LINES_TO_COMPRESS - 1), "h", marker)
    assertFalse(result.compressed)
  }

  @Test
  fun `the store gives the full text back by handle`() {
    val store = OutputCompressor.Store()
    val handle = store.put("полный вывод")
    assertEquals("полный вывод", store.get(handle))
    assertNull(store.get("нет такого"))
  }

  @Test
  fun `the store forgets the oldest rather than growing without end`() {
    val store = OutputCompressor.Store(capacity = 3)
    val first = store.put("один")
    repeat(3) { store.put("ещё $it") }
    assertEquals(3, store.size())
    assertNull(store.get(first))
  }
}
