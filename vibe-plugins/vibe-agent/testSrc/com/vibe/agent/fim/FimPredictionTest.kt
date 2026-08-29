// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FimPredictionTest {
  private fun plan(before: String, after: String, justAccepted: Boolean = false) =
    FimPrediction.plan(prefix = "prefix$before", suffix = "$after suffix", before, after, justAccepted)

  @Test
  fun `an empty line asks for one line`() {
    val plan = plan("    ", "")
    assertEquals(FimPrediction.Type.SINGLE_LINE_FILL, plan.type)
    assertTrue(plan.stop.contains("\n"))
  }

  @Test
  fun `caret in the middle of a typed line asks to fill the middle`() {
    assertEquals(FimPrediction.Type.SINGLE_LINE_MIDDLE, plan("val x = foo(", "bar, baz)").type)
  }

  @Test
  fun `a stub tail is rewritten, not appended to`() {
    // Otherwise the model closes a bracket that is already there: «foo())».
    val plan = FimPrediction.plan("val x = foo(", ")\nnext line\n", "val x = foo(", ")", false)
    assertEquals(FimPrediction.Type.SINGLE_LINE_REDO_SUFFIX, plan.type)
    assertFalse(plan.suffix.startsWith(")"), "хвост текущей строки не должен уезжать модели: ${plan.suffix}")
    assertTrue(plan.suffix.contains("next line"))
  }

  @Test
  fun `after an accepted suggestion the model continues on the next line`() {
    val plan = plan("val x = 1", "", justAccepted = true)
    assertEquals(FimPrediction.Type.MULTI_LINE_NEXT_LINE, plan.type)
    assertTrue(plan.prefix.endsWith("\n"))
    assertEquals(listOf("\n\n"), plan.stop)
  }

  @Test
  fun `caret before someone else's code asks for nothing at all`() {
    // The cheapest win the feature has: a request that could only produce noise is not sent.
    val plan = plan("", "существующий вызов(аргумент)")
    assertEquals(FimPrediction.Type.DO_NOT_PREDICT, plan.type)
    assertFalse(plan.shouldGenerate)
  }

  @Test
  fun `a local model gets a smaller window than a cloud one`() {
    assertEquals(FimPrediction.CONTEXT_LINES_LOCAL, FimPrediction.contextLines(isLocal = true))
    assertEquals(FimPrediction.CONTEXT_LINES_CLOUD, FimPrediction.contextLines(isLocal = false))
  }

  @Test
  fun `the window keeps the lines nearest the caret`() {
    val text = (1..40).joinToString("\n") { "строка $it" }
    assertTrue(FimPrediction.limitPrefix(text, 3).startsWith("строка 38"))
    assertTrue(FimPrediction.limitSuffix(text, 3).endsWith("строка 3"))
  }

  @Test
  fun `acceptance is recognised by the caret and the text behind it`() {
    val served = FimPrediction.Served("/a.kt", caretAfterInsert = 12, text = "println()")
    assertTrue(FimPrediction.wasJustAccepted(served, "/a.kt", 12, "val println()"))
    assertFalse(FimPrediction.wasJustAccepted(served, "/b.kt", 12, "val println()"), "другой файл")
    assertFalse(FimPrediction.wasJustAccepted(served, "/a.kt", 13, "val println()"), "курсор не там")
    assertFalse(FimPrediction.wasJustAccepted(served, "/a.kt", 12, "val что-то ещё"), "текста нашей подсказки нет")
    assertFalse(FimPrediction.wasJustAccepted(null, "/a.kt", 12, "что угодно"))
  }
}
