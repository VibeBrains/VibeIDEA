// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.watch

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitlesTest {
  private val srt = """
    1
    00:00:01,000 --> 00:00:03,500
    Привет, сегодня разберём

    2
    00:00:03,500 --> 00:00:06,000
    <i>сборку проекта</i>

    3
    00:00:30,000 --> 00:00:32,000
    Вторая часть
  """.trimIndent()

  @Test
  fun `cues carry their start time and lose inline tags`() {
    val cues = Subtitles.parse(srt)
    assertEquals(3, cues.size)
    assertEquals(1.0, cues.first().startSec)
    assertEquals("сборку проекта", cues[1].text, "теги субтитров читателю ничего не дают")
  }

  @Test
  fun `a rolling repeat of auto-subtitles is dropped`() {
    val rolling = """
      1
      00:00:01,000 --> 00:00:02,000
      одна строка

      2
      00:00:02,000 --> 00:00:03,000
      одна строка
    """.trimIndent()
    assertEquals(1, Subtitles.parse(rolling).size)
  }

  @Test
  fun `the transcript is stamped so an answer can be checked against the video`() {
    val text = Subtitles.transcript(Subtitles.parse(srt))
    assertTrue(text.startsWith("[0:01]"), text)
    assertTrue(text.contains("[0:30]"), text)
    assertTrue(text.contains("Привет, сегодня разберём сборку проекта"), text)
  }

  @Test
  fun `a long transcript is cut with the cut said out loud`() {
    val many = (1..2000).joinToString("\n\n") { i ->
      val s = i % 60
      "$i\n00:0${i / 3600}:${String.format("%02d", (i / 60) % 60)}:${String.format("%02d", s)},000 --> 00:00:01,000\nстрока $i"
    }
    val text = Subtitles.transcript(Subtitles.parse(many), maxChars = 500)
    assertTrue(text.length <= 600, "обрезка обязана держаться в рамках: ${text.length}")
  }

  @Test
  fun `an empty or malformed file is not a transcript`() {
    assertTrue(Subtitles.parse("").isEmpty())
    assertTrue(Subtitles.parse("не субтитры вовсе").isEmpty())
    assertEquals("", Subtitles.transcript(emptyList()))
  }

  @Test
  fun `hours appear only when there are hours`() {
    assertEquals("0:05", Subtitles.stamp(5.0))
    assertEquals("4:12", Subtitles.stamp(252.0))
    assertEquals("1:00:01", Subtitles.stamp(3601.0))
  }
}
