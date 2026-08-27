// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageSegmentsTest {
  private fun code(s: MessageSegment) = s as MessageSegment.Code
  private fun prose(s: MessageSegment) = s as MessageSegment.Prose

  @Test
  fun plainTextIsOneProse() {
    val segs = MessageSegments.parse("just some text")
    assertEquals(1, segs.size)
    assertEquals("just some text", prose(segs[0]).text)
    assertFalse(MessageSegments.hasCode("just some text"))
  }

  @Test
  fun singleFencedBlockWithLang() {
    val segs = MessageSegments.parse("before\n```kotlin\nval x = 1\n```\nafter")
    assertEquals(3, segs.size)
    assertEquals("before", prose(segs[0]).text)
    assertEquals("kotlin", code(segs[1]).lang)
    assertEquals("val x = 1", code(segs[1]).code)
    assertEquals("after", prose(segs[2]).text)
    assertTrue(MessageSegments.hasCode("x\n```\ny\n```"))
  }

  @Test
  fun fenceWithoutLang() {
    val segs = MessageSegments.parse("```\nplain code\n```")
    assertEquals(1, segs.size)
    assertEquals(null, code(segs[0]).lang)
    assertEquals("plain code", code(segs[0]).code)
  }

  @Test
  fun unterminatedFenceConsumesRest() {
    // A partial stream: everything after the open fence is code, no text lost.
    val segs = MessageSegments.parse("intro\n```py\nprint(1)\nprint(2)")
    assertEquals(2, segs.size)
    assertEquals("intro", prose(segs[0]).text)
    assertEquals("print(1)\nprint(2)", code(segs[1]).code)
  }

  @Test
  fun multipleBlocks() {
    val segs = MessageSegments.parse("```a\n1\n```\n```b\n2\n```")
    assertEquals(2, segs.size)
    assertEquals("a", code(segs[0]).lang)
    assertEquals("b", code(segs[1]).lang)
  }

  @Test
  fun innerBlankLinesPreservedInCode() {
    val segs = MessageSegments.parse("```\nline1\n\nline3\n```")
    assertEquals("line1\n\nline3", code(segs[0]).code)
  }

  @Test
  fun emptyProseBetweenFencesDropped() {
    val segs = MessageSegments.parse("```\na\n```\n\n```\nb\n```")
    assertTrue(segs.all { it is MessageSegment.Code })
    assertEquals(2, segs.size)
  }
}
