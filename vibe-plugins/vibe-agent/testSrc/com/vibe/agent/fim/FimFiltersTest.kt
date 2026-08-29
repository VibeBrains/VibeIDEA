// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.fim

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FimFiltersTest {
  @Test
  fun `plain code passes untouched`() {
    val code = "val sum = items.sumOf { it.price }"
    assertEquals(code, FimFilters.clean(code))
  }

  @Test
  fun `a line of prose instead of code is dropped`() {
    val answer = "val x = 1\nЭта функция складывает элементы списка и возвращает сумму"
    assertEquals("val x = 1", FimFilters.clean(answer))
  }

  @Test
  fun `legitimate non-latin code survives — this is the line the filter must not cross`() {
    // A Russian comment, a Chinese string literal and an emoji in a test name are all code.
    val cases = listOf(
      "val имя = \"Пётр\" // владелец записи",
      "val text = \"你好世界\"",
      "fun `тест: сумма пуста`() {}",
    )
    for (line in cases) assertEquals(line, FimFilters.clean(line), line)
  }

  @Test
  fun `a CJK comment is removed but the code before it is kept`() {
    assertEquals("int x = 1;", FimFilters.clean("int x = 1; // 这是一个变量"))
  }

  @Test
  fun `prose detection needs both signs — mostly non-ascii AND no code punctuation`() {
    assertTrue(FimFilters.isProse("Это просто предложение без кода"))
    assertFalse(FimFilters.isProse("если (условие) { сделать() }"), "есть пунктуация кода")
    assertFalse(FimFilters.isProse("plain english explanation here"), "ASCII — не наш случай")
    assertFalse(FimFilters.isProse("   "))
  }

  @Test
  fun `edges keep at most one space`() {
    assertEquals(" foo ", FimFilters.trimEdges("   foo   "))
    assertEquals("foo", FimFilters.trimEdges("foo\n\n"))
    assertEquals("", FimFilters.trimEdges("   \n  "))
  }
}
