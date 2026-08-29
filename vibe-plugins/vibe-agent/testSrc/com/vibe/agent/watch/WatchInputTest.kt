// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchInputTest {
  @Test
  fun `audio and video are told apart by extension`() {
    assertEquals(WatchInput.Kind.AUDIO, WatchInput.classify("/tmp/подкаст.mp3"))
    assertEquals(WatchInput.Kind.VIDEO, WatchInput.classify("/tmp/лекция.mkv"))
  }

  @Test
  fun `a query string is stripped from a URL`() {
    assertEquals(WatchInput.Kind.AUDIO, WatchInput.classify("https://cdn.example.com/ep12.mp3?token=abc#t=30"))
  }

  @Test
  fun `a hash in a LOCAL path is part of the name, not a fragment`() {
    // Cutting at '#' turned real files into "unknown" — the exact bug this rule exists for.
    assertEquals(WatchInput.Kind.VIDEO, WatchInput.classify("/tmp/запись #3.mp4"))
    assertEquals(WatchInput.Kind.AUDIO, WatchInput.classify("/tmp/эфир?черновик.wav"))
  }

  @Test
  fun `a platform page without an extension stays unknown`() {
    assertEquals(WatchInput.Kind.UNKNOWN, WatchInput.classify("https://www.youtube.com/watch?v=abc123"))
    assertEquals(WatchInput.Kind.UNKNOWN, WatchInput.classify(""))
  }

  @Test
  fun `the command splits into a source and a question`() {
    val parsed = WatchInput.parse("/watch https://youtu.be/x  что показывают на экране?")!!
    assertEquals("https://youtu.be/x", parsed.source)
    assertEquals("что показывают на экране?", parsed.question)
  }

  @Test
  fun `a source without a question is fine, a command without a source is not`() {
    assertEquals("", WatchInput.parse("/watch /tmp/a.mp4")!!.question)
    assertNull(WatchInput.parse("/watch"))
    assertNull(WatchInput.parse("расскажи про /watch"))
  }
}
