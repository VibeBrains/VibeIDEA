// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.history

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WirePrefixTest {
  private fun line(role: String, text: String, images: List<String> = emptyList()) =
    WirePrefix.Line(role, text, images)

  private val turn1 = listOf(line("user", "привет"), line("assistant", "здравствуйте"))
  private val turn2 = turn1 + listOf(line("user", "почини тесты"), line("assistant", "готово"))

  @Test
  fun `an appended turn keeps the whole previous request as its prefix`() {
    // This is what a cache hit actually is: not «разговор тот же», but «запрос начинается теми же
    // байтами».
    assertTrue(WirePrefix.appendOnly(turn1, turn2))
    assertEquals(2, WirePrefix.sharedPrefix(turn1, turn2))
  }

  @Test
  fun `an edited earlier message ends the match where it was edited`() {
    val edited = listOf(line("user", "привет!"), line("assistant", "здравствуйте"), line("user", "ещё"))
    assertFalse(WirePrefix.appendOnly(turn1, edited))
    assertEquals(0, WirePrefix.sharedPrefix(turn1, edited))
  }

  @Test
  fun `dropping an image from an earlier message counts as an edit`() {
    // The exact slip this was written for: the same text, one picture lighter, and the cache stops
    // matching from that message on.
    val withImage = listOf(line("user", "смотри", listOf("abc")), line("assistant", "вижу"))
    val without = listOf(line("user", "смотри"), line("assistant", "вижу"), line("user", "дальше"))
    assertFalse(WirePrefix.appendOnly(withImage, without))
    assertEquals(0, WirePrefix.sharedPrefix(withImage, without))
  }

  @Test
  fun `a first turn is append-only by definition`() {
    assertTrue(WirePrefix.appendOnly(emptyList(), turn1))
  }

  @Test
  fun `the image boundary stays put while no new image arrives`() {
    // Recomputing it every turn was the defect: a fifth picture rewrote the message that held the
    // first one, and every token after it was billed as fresh input on every later turn.
    val images = listOf(0, 3, 6, 9)
    val first = WirePrefix.imageCut(images, keep = 2, previousCut = null, newImageArrived = true)
    assertEquals(6, first)
    assertEquals(6, WirePrefix.imageCut(images, keep = 2, previousCut = first, newImageArrived = false))
  }

  @Test
  fun `a new image moves the boundary forward, never back`() {
    val images = listOf(0, 3, 6, 9, 12)
    val moved = WirePrefix.imageCut(images, keep = 2, previousCut = 6, newImageArrived = true)
    assertEquals(9, moved)
    // Backwards would restore images already dropped — a rewrite of history just as costly.
    assertEquals(9, WirePrefix.imageCut(listOf(0, 3), keep = 2, previousCut = 9, newImageArrived = true))
  }

  @Test
  fun `a conversation without images has no boundary to move`() {
    assertEquals(0, WirePrefix.imageCut(emptyList(), keep = 4, previousCut = null, newImageArrived = false))
    assertEquals(5, WirePrefix.imageCut(emptyList(), keep = 4, previousCut = 5, newImageArrived = true))
  }
}
