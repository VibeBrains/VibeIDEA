// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which chips of a message show, and how they fade in
 *
 * The rule that matters is the pinned one: a pinned message must read as pinned without the pointer on it
 * While the other two chips stay hidden until it comes
 */
class MessageChipsTest {
  @Test
  fun `nothing shows while the pointer is away from an ordinary message`() {
    for (chip in MessageChip.entries) assertFalse(MessageChipRules.visible(chip, hovered = false, pinned = false), "$chip")
  }

  @Test
  fun `all three show under the pointer`() {
    for (pinned in listOf(false, true)) {
      for (chip in MessageChip.entries) assertTrue(MessageChipRules.visible(chip, hovered = true, pinned = pinned), "$chip pinned=$pinned")
    }
  }

  @Test
  fun `a pinned message keeps its pin without the pointer and hides the rest`() {
    assertTrue(MessageChipRules.visible(MessageChip.PIN, hovered = false, pinned = true))
    assertFalse(MessageChipRules.visible(MessageChip.REWIND, hovered = false, pinned = true))
    assertFalse(MessageChipRules.visible(MessageChip.BRANCH, hovered = false, pinned = true))
  }

  @Test
  fun `a fade takes 200 ms from hidden to shown`() {
    val fade = ChipFade(200, initial = 0f)
    fade.retarget(1f, nowMs = 1_000)
    assertEquals(0f, fade.alpha(1_000))
    assertEquals(0.5f, fade.alpha(1_100), 0.001f)
    assertEquals(1f, fade.alpha(1_200))
    assertEquals(1f, fade.alpha(5_000))
    assertFalse(fade.settled(1_199))
    assertTrue(fade.settled(1_200))
  }

  @Test
  fun `leaving in the middle of a fade starts from where it got to, without a jump`() {
    val fade = ChipFade(200, initial = 0f)
    fade.retarget(1f, nowMs = 0)
    val reached = fade.alpha(100)
    fade.retarget(0f, nowMs = 100)
    assertEquals(reached, fade.alpha(100), 0.001f)
    assertEquals(reached / 2, fade.alpha(200), 0.001f)
    assertEquals(0f, fade.alpha(300), 0.001f)
  }

  @Test
  fun `a chip that starts shown stays shown and retargeting to the same value changes nothing`() {
    val fade = ChipFade(200, initial = 1f)
    assertEquals(1f, fade.alpha(0))
    fade.retarget(1f, nowMs = 50)
    assertEquals(1f, fade.alpha(60))
    assertEquals(1f, fade.target)
  }

  @Test
  fun `a fade of no duration is immediate`() {
    val fade = ChipFade(0, initial = 0f)
    fade.retarget(1f, nowMs = 10)
    assertEquals(1f, fade.alpha(10))
    assertTrue(fade.settled(10))
  }
}
