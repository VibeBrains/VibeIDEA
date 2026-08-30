// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.inline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineEditPromptTest {
  private val instructions = InlineEditPrompt.Instructions(
    doc = "документируй", refactor = "рефактори", tests = "напиши тесты", explain = "объясни",
    fix = "почини", free = "сделай что просят", formatRule = "верни только код",
  )

  @Test
  fun `the five verbs are recognised in both languages`() {
    assertEquals(InlineEditPrompt.Command.DOC, InlineEditPrompt.parse("/doc").command)
    assertEquals(InlineEditPrompt.Command.REFACTOR, InlineEditPrompt.parse("/рефактор").command)
    assertEquals(InlineEditPrompt.Command.TESTS, InlineEditPrompt.parse("/tests").command)
    assertEquals(InlineEditPrompt.Command.EXPLAIN, InlineEditPrompt.parse("/объясни").command)
    assertEquals(InlineEditPrompt.Command.FIX, InlineEditPrompt.parse("/fix").command)
  }

  @Test
  fun `anything else is a free-form instruction, not an error`() {
    val request = InlineEditPrompt.parse("перепиши на корутинах")
    assertEquals(InlineEditPrompt.Command.FREE, request.command)
    assertEquals("перепиши на корутинах", request.instruction)
    assertEquals(InlineEditPrompt.Command.FREE, InlineEditPrompt.parse("/чтототакое").command)
  }

  @Test
  fun `a verb keeps the rest of the line as its instruction`() {
    val request = InlineEditPrompt.parse("/refactor вынеси в отдельную функцию")
    assertEquals(InlineEditPrompt.Command.REFACTOR, request.command)
    assertEquals("вынеси в отдельную функцию", request.instruction)
  }

  @Test
  fun `explain never replaces the code with its own explanation`() {
    assertFalse(InlineEditPrompt.parse("/explain").replacesCode)
    assertTrue(InlineEditPrompt.parse("/fix").replacesCode)
  }

  @Test
  fun `the prompt carries the code, the verb and the project rules`() {
    val text = InlineEditPrompt.build(InlineEditPrompt.parse("/fix"), "kotlin", "val x = 1", instructions, "правило проекта")
    assertTrue(text.contains("почини"))
    assertTrue(text.contains("правило проекта"))
    assertTrue(text.contains("```kotlin"))
    assertTrue(text.contains("val x = 1"))
  }

  @Test
  fun `explain is not told to return code only`() {
    val text = InlineEditPrompt.build(InlineEditPrompt.parse("/explain"), "kotlin", "val x = 1", instructions)
    assertFalse(text.contains("верни только код"))
  }

  @Test
  fun `a fenced answer yields the code inside the fence`() {
    val answer = "Вот исправленный вариант:\n\n```kotlin\nval x = 2\n```\n\nГотово."
    assertEquals("val x = 2", InlineEditPrompt.extractCode(answer, "val x = 1"))
  }

  @Test
  fun `a bare code answer is accepted`() {
    assertEquals("val x = 2", InlineEditPrompt.extractCode("val x = 2", "val x = 1"))
  }

  @Test
  fun `an apology is refused rather than pasted into the file`() {
    // Заменить работающий код извинением — единственный исход, который не прощают.
    val original = "fun a() { if (x > 0) { return f(x); } }"
    assertNull(InlineEditPrompt.extractCode("Извините, я не могу помочь с этим запросом.", original))
    assertNull(InlineEditPrompt.extractCode("Этот код и так выглядит корректным, менять нечего в нём совсем.", original))
  }

  @Test
  fun `an empty answer changes nothing`() {
    assertNull(InlineEditPrompt.extractCode("   ", "val x = 1"))
  }

  @Test
  fun `prose is recognised by shape, not by keywords`() {
    val original = "fun a() { if (x > 0) { return f(x); } }"
    assertTrue(InlineEditPrompt.looksLikeProse("Здесь функция возвращает значение при положительном аргументе", original))
    assertFalse(InlineEditPrompt.looksLikeProse("fun a() { return f(x); }", original))
  }
}
