// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The editor never sends a click after a consumed press, so the buttons of a hunk fire from press and release */
class PressedTargetTest {
  @Test
  fun `release over the pressed target is a click`() {
    val target = PressedTarget<String>()
    target.press("accept")
    assertEquals("accept", target.release("accept"))
  }

  @Test
  fun `release over another target or past the block is not a click`() {
    val target = PressedTarget<String>()
    target.press("accept")
    assertNull(target.release("reject"))
    target.press("accept")
    assertNull(target.release(null))
  }

  @Test
  fun `release without a press, or a second release, is not a click`() {
    val target = PressedTarget<String>()
    assertNull(target.release("accept"))
    target.press("accept")
    target.release("accept")
    assertNull(target.release("accept"))
  }
}
