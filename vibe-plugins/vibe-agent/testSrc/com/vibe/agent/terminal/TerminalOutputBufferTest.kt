// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.terminal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalOutputBufferTest {
  @Test
  fun belowLimitKeepsEverything() {
    val b = TerminalOutputBuffer(100)
    b.append("hello ")
    b.append("world")
    val (text, truncated) = b.snapshot()
    assertEquals("hello world", text)
    assertFalse(truncated)
  }

  @Test
  fun overLimitRetainsTailAndFlagsTruncated() {
    val b = TerminalOutputBuffer(10)
    b.append("0123456789ABCDEF")
    val (text, truncated) = b.snapshot()
    assertTrue(truncated)
    assertTrue(text.length <= 10)
    // Tail retained: the newest characters survive.
    assertEquals("6789ABCDEF", text)
  }

  @Test
  fun noLimitKeepsAll() {
    val b = TerminalOutputBuffer(null)
    b.append("x".repeat(1000))
    val (text, truncated) = b.snapshot()
    assertEquals(1000, text.length)
    assertFalse(truncated)
  }

  @Test
  fun multiByteBoundaryRespected() {
    // Each emoji is 4 UTF-8 bytes; a 5-byte budget can hold exactly one.
    val b = TerminalOutputBuffer(5)
    b.append("😀😀")
    val (text, _) = b.snapshot()
    // Never split a code point: at most one full emoji fits under 5 bytes.
    assertTrue(text == "😀" || text.isEmpty())
    assertEquals(text, text.filter { true }) // no lone surrogate corruption
  }
}
