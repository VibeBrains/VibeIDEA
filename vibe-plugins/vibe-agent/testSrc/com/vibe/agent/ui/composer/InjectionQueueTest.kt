// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.ui.composer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InjectionQueueTest {
  private fun image(name: String) = ImageAttachment(name, "image/png", byteArrayOf(1))

  @Test
  fun `empty notes are ignored`() {
    val q = InjectionQueue()
    q.add(ComposedMessage("   "))
    assertTrue(q.isEmpty)
    assertNull(q.drain())
  }

  @Test
  fun `drain merges texts with a blank line and concatenates images in order`() {
    val q = InjectionQueue()
    q.add(ComposedMessage("первое", images = listOf(image("a.png"))))
    q.add(ComposedMessage("  второе  ", images = listOf(image("b.png"))))
    val merged = q.drain()!!
    assertEquals("первое\n\nвторое", merged.text)
    assertEquals(listOf("a.png", "b.png"), merged.images.map { it.name })
    assertTrue(q.isEmpty)
  }

  @Test
  fun `removeAt drops one note and notifies listeners`() {
    val q = InjectionQueue()
    var changes = 0
    q.onChange { changes++ }
    q.add(ComposedMessage("a"))
    q.add(ComposedMessage("b"))
    q.removeAt(0)
    assertEquals(1, q.size)
    assertEquals("b", q.snapshot().single().text)
    assertEquals(3, changes)
  }

  @Test
  fun `image-only note is kept and drains to blank text`() {
    val q = InjectionQueue()
    q.add(ComposedMessage("", images = listOf(image("x.png"))))
    val merged = q.drain()!!
    assertEquals("", merged.text)
    assertEquals(1, merged.images.size)
  }
}
