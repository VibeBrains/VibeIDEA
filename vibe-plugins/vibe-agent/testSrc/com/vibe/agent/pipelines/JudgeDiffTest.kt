// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.pipelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A judge without tools sees the run through this diff — whole when it fits, honestly cut when not. */
class JudgeDiffTest {
  private val line = "+" + "x".repeat(99) + "\n"

  @Test
  fun `a diff that fits goes whole`() {
    val diff = "diff --git a/src/App.kt b/src/App.kt\n+val x = 1\n"
    assertEquals(JudgeDiff.Fitted(diff, truncated = false), JudgeDiff.fit(diff, maxTokens = null))
  }

  @Test
  fun `a long diff is cut at a line and says so`() {
    val fitted = JudgeDiff.fit(line.repeat(1_000), maxTokens = null)
    assertTrue(fitted.truncated)
    assertTrue(fitted.text.length <= JudgeDiff.MAX_CHARS, "${fitted.text.length}")
    assertTrue(fitted.text.endsWith("x"), "обрезано по границе строки, а не посреди неё")
  }

  @Test
  fun `a step ceiling leaves half of itself for the answer`() {
    // 1000 tokens ≈ 4000 characters; the diff gets at most half, so the answer still fits.
    val fitted = JudgeDiff.fit(line.repeat(30), maxTokens = 1_000)
    assertTrue(fitted.truncated)
    assertTrue(fitted.text.length <= 2_000, "${fitted.text.length}")
    assertFalse(JudgeDiff.fit(line.repeat(30), maxTokens = 0).truncated, "0 — потолка нет, как и в остальных полях шага")
  }
}
