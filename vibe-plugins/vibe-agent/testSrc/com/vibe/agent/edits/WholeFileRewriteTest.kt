// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.edits

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WholeFileRewriteTest {
  private fun file(lines: Int, marker: String = "line") = (1..lines).joinToString("\n") { "$marker $it" }

  @Test
  fun `three lines changed in a hundred is worth saying`() {
    // The regression Anthropic names for Fable 5.1: the file comes out right, the diff is huge,
    // and the cost shows up at the end of the month.
    val before = file(100)
    val after = before.lines().toMutableList().apply {
      this[10] = "changed a"; this[20] = "changed b"; this[30] = "changed c"
    }.joinToString("\n")
    val verdict = WholeFileRewrite.check(before, after)
    assertNotNull(verdict)
    assertEquals(3, verdict.changedLines)
    assertEquals(100, verdict.totalLines)
  }

  @Test
  fun `a real refactor is not a rewrite worth mentioning`() {
    // A warning that fires on every genuine reshaping is a warning people learn to scroll past.
    val before = file(100)
    val after = file(100, marker = "totally different")
    assertNull(WholeFileRewrite.check(before, after))
  }

  @Test
  fun `an inserted line does not count as a hundred changed ones`() {
    // Counting by position would call a one-line insertion at the top a total rewrite — the
    // opposite of what this measures.
    val before = file(100)
    val after = "новая первая строка\n" + before
    val verdict = WholeFileRewrite.check(before, after)
    assertNotNull(verdict)
    assertEquals(1, verdict.changedLines)
  }

  @Test
  fun `new and short files say nothing`() {
    assertNull(WholeFileRewrite.check(null, file(500)))
    assertNull(WholeFileRewrite.check(file(10), file(10, marker = "other")))
  }

  @Test
  fun `an identical write is not a rewrite`() {
    assertNull(WholeFileRewrite.check(file(100), file(100)))
  }
}
