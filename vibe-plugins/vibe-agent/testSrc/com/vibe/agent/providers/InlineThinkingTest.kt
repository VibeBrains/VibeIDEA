// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The splitter that takes `<think>` out of the answer stream.
 *
 * Every case here is a real shape of the wire: a tag cut between chunks is the normal case, not the
 * exotic one, and a turn that ends inside the tag is what a token ceiling looks like.
 */
class InlineThinkingTest {
  private val answer = StringBuilder()
  private val thought = StringBuilder()
  private val splitter = InlineThinking({ answer.append(it) }, { thought.append(it) })

  private fun feed(vararg deltas: String) {
    deltas.forEach { splitter.accept(it) }
    splitter.finish()
  }

  @Test
  fun `text without tags goes through untouched`() {
    feed("Обычный ", "ответ модели")
    assertEquals("Обычный ответ модели", answer.toString())
    assertEquals("", thought.toString())
  }

  @Test
  fun `thinking in one chunk is split off`() {
    feed("<think>размышляю</think>Ответ")
    assertEquals("Ответ", answer.toString())
    assertEquals("размышляю", thought.toString())
  }

  @Test
  fun `tag cut between chunks is still recognised`() {
    feed("<thi", "nk>мысль</thi", "nk>Ответ")
    assertEquals("Ответ", answer.toString())
    assertEquals("мысль", thought.toString())
  }

  @Test
  fun `thinking in the middle of the answer is split off`() {
    feed("Начало. ", "<think>вторая мысль</think>", " Продолжение.")
    assertEquals("Начало.  Продолжение.", answer.toString())
    assertEquals("вторая мысль", thought.toString())
  }

  @Test
  fun `long form tag is recognised too`() {
    feed("<thinking>мысль</thinking>Ответ")
    assertEquals("Ответ", answer.toString())
    assertEquals("мысль", thought.toString())
  }

  @Test
  fun `unclosed tag at the end of the turn stays a thought`() {
    feed("Начало.<think>оборвалось на середине")
    assertEquals("Начало.", answer.toString())
    assertEquals("оборвалось на середине", thought.toString())
  }

  @Test
  fun `a lone angle bracket is not held back forever`() {
    feed("a < b и c <= d")
    assertEquals("a < b и c <= d", answer.toString())
  }

  @Test
  fun `the answer streams without waiting for the whole tag`() {
    // Потоковость и есть смысл разделителя: текст до тега уходит в ленту сразу, а не в конце хода.
    splitter.accept("Первые слова")
    assertEquals("Первые слова", answer.toString())
  }
}
